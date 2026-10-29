package io.github.hectorvent.floci.core.common.docker;

import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily starts, health-checks, and reuses one container per arbitrary key. Pulled out of
 * {@code VerdaccioSidecarManager} (one Verdaccio container per CodeArtifact npm repository) since
 * that lifecycle logic (map of running containers, per-key start lock so concurrent first-use of
 * two different keys never blocks on each other, health poll, restart-on-unhealthy, stop-all on
 * shutdown) has nothing Verdaccio-specific in it; only how to actually build and start a container
 * for a given key does. {@code PypiserverSidecarManager} and {@code BagetterSidecarManager}
 * already reuse this directly instead of re-deriving the same map/lock/poll skeleton; a future
 * per-repository sidecar for a new format can do the same.
 *
 * <p>Callers own the health-check path and the actual container creation (image, config, env),
 * supplied per call since those are exactly the format-specific parts.
 */
public class PerKeyContainerPool {

    private static final Logger LOG = Logger.getLogger(PerKeyContainerPool.class);

    /** What a {@link Starter} hands back once its container is created and started. */
    public record StartedContainer(String containerId, String baseUrl) {}

    /** Builds and starts a fresh container for the key {@link #ensureReady} was called with. */
    @FunctionalInterface
    public interface Starter {
        StartedContainer start();
    }

    private final ContainerLifecycleManager lifecycleManager;
    private final String healthPath;
    private final ConcurrentHashMap<String, StartedContainer> containers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> startLocks = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();
    private record PendingRemoval(String key, StartedContainer container) {}

    private final Map<String, PendingRemoval> pendingCleanup = new ConcurrentHashMap<>();
    private long generation;

    /**
     * @param healthPath path (e.g. {@code /-/ping}) appended to a container's base URL to probe
     *                   readiness; expected to return HTTP 200 once the container can serve
     */
    public PerKeyContainerPool(ContainerLifecycleManager lifecycleManager, String healthPath) {
        this.lifecycleManager = lifecycleManager;
        this.healthPath = healthPath;
    }

    /**
     * Base URL of a ready container for {@code key}, starting one via {@code starter} if this is
     * the first use or the previous container is no longer healthy. Concurrent calls for
     * different keys never block on each other; concurrent calls for the same key serialize so
     * only one container is ever started for it.
     */
    public String ensureReady(String key, Starter starter) {
        StartedContainer existing = containers.get(key);
        if (existing != null && SidecarHealthHelper.probeHealth(existing.baseUrl(), healthPath)) {
            return existing.baseUrl();
        }
        Object lock = startLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            existing = containers.get(key);
            if (existing != null && SidecarHealthHelper.probeHealth(existing.baseUrl(), healthPath)) {
                return existing.baseUrl();
            }
            if (existing != null) {
                LOG.warnv("Sidecar container for key {0} is no longer healthy; restarting it", key);
                removeOwned(new PendingRemoval(key, existing));
            }
            long startedGeneration;
            synchronized (registryLock) {
                startedGeneration = generation;
            }
            StartedContainer started = starter.start();
            boolean reset;
            synchronized (registryLock) {
                reset = generation != startedGeneration;
                if (!reset) {
                    containers.put(key, started);
                }
            }
            if (reset) {
                LOG.warnv("Sidecar pool was reset while starting a container for key {0}; stopping it", key);
                removeOwned(new PendingRemoval(key, started));
                throw new IllegalStateException("Sidecar pool was reset while starting a container for key " + key);
            }
            SidecarHealthHelper.waitForHealth(started.baseUrl(), healthPath);
            return started.baseUrl();
        }
    }

    /**
     * Whether a container for {@code key} is currently tracked by this pool, without starting one
     * for a key that has none: a plain map read, never {@link #ensureReady}'s own health probe or
     * {@code Starter} call. For a caller that only wants to ask an already-running container
     * something (whether it holds any content worth enumerating, say) and must not provision a
     * brand-new one just to find out it has nothing.
     */
    public boolean isTracked(String key) {
        return containers.containsKey(key);
    }

    /**
     * Stops and removes the container for one key and retries its previously failed removals.
     * Failed removal remains owned for the next per-key cleanup or pool reset.
     * Shares {@link #ensureReady}'s per-key lock so a stop racing a concurrent first-use of the
     * same key serializes against it, instead of running between the moment that start commits to
     * a container and the moment it becomes visible here: without the shared lock, a stop that
     * finds nothing to remove could be immediately followed by that start's own container
     * registration, orphaning it since nothing calls stop for the key again.
     */
    public void stopContainer(String key) {
        if (key == null) {
            return;
        }
        Object lock = startLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            List<PendingRemoval> removals;
            synchronized (registryLock) {
                StartedContainer container = containers.remove(key);
                if (container != null) {
                    pendingCleanup.put(container.containerId(), new PendingRemoval(key, container));
                }
                removals = pendingCleanup.values().stream()
                        .filter(removal -> key.equals(removal.key())).toList();
            }
            removals.forEach(this::removeOwned);
        }
    }

    private void removeOwned(PendingRemoval removal) {
        StartedContainer container = removal.container();
        synchronized (registryLock) {
            pendingCleanup.put(container.containerId(), removal);
        }
        lifecycleManager.stopAndRemoveStrict(container.containerId(), null);
        synchronized (registryLock) {
            pendingCleanup.remove(container.containerId(), removal);
        }
    }

    /** Detaches this generation, then retries owned removals without blocking later starts. */
    public void stopAll() {
        Map<String, PendingRemoval> detached;
        synchronized (registryLock) {
            generation++;
            containers.forEach((key, container) -> pendingCleanup.put(container.containerId(),
                    new PendingRemoval(key, container)));
            containers.clear();
            detached = Map.copyOf(pendingCleanup);
        }
        if (detached.isEmpty()) {
            return;
        }
        LOG.infov("Stopping {0} pooled sidecar container(s)", detached.size());
        detached.forEach((containerId, removal) -> {
            try {
                removeOwned(removal);
            } catch (Exception e) {
                LOG.debugv(e, "Failed to stop pooled sidecar container {0}; retained for retry", containerId);
            }
        });
    }
}
