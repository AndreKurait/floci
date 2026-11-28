package io.floci.aws.core.common.docker;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

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
    private final BiPredicate<String, String> healthProbe;
    private final BiConsumer<String, String> healthWait;
    private final ConcurrentHashMap<String, StartedContainer> containers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> startLocks = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();
    private record PendingRemoval(String key, String containerId) {}

    private final Map<String, PendingRemoval> pendingCleanup = new ConcurrentHashMap<>();
    private long generation;

    private final ConcurrentHashMap<String, Integer> activeUses = new ConcurrentHashMap<>();
    private final Map<String, List<StartedContainer>> retiredContainers = new ConcurrentHashMap<>();

    /** Protects containers observed by every admitted use, including an unhealthy predecessor. */
    public void beginUse(String key) {
        activeUses.merge(key, 1, Integer::sum);
    }

    public void endUse(String key) {
        Object lock = startLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            AtomicBoolean drained = new AtomicBoolean();
            activeUses.compute(key, (k, active) -> {
                if (active == null) {
                    throw new IllegalStateException("No active container use for key " + key);
                }
                drained.set(active == 1);
                return active == 1 ? null : active - 1;
            });
            if (drained.get()) {
                List<PendingRemoval> retired;
                synchronized (registryLock) {
                    retired = retiredContainers.getOrDefault(key, List.of()).stream()
                            .map(container -> new PendingRemoval(key, container.containerId())).toList();
                }
                removeEveryOwned(retired);
            }
        }
    }

    private static RuntimeException accumulateFailure(RuntimeException failure, RuntimeException next) {
        if (failure == null) {
            return next;
        }
        if (failure != next) {
            failure.addSuppressed(next);
        }
        return failure;
    }

    private void removeEveryOwned(List<PendingRemoval> removals) {
        RuntimeException failure = null;
        for (PendingRemoval removal : removals) {
            try {
                removeOwned(removal);
            } catch (RuntimeException e) {
                failure = accumulateFailure(failure, e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * @param healthPath path (e.g. {@code /-/ping}) appended to a container's base URL to probe
     *                   readiness; expected to return HTTP 200 once the container can serve
     */
    public PerKeyContainerPool(ContainerLifecycleManager lifecycleManager, String healthPath) {
        this(lifecycleManager, healthPath, SidecarHealthHelper::probeHealth, SidecarHealthHelper::waitForHealth);
    }

    PerKeyContainerPool(ContainerLifecycleManager lifecycleManager, String healthPath,
                        BiPredicate<String, String> healthProbe, BiConsumer<String, String> healthWait) {
        this.lifecycleManager = lifecycleManager;
        this.healthPath = healthPath;
        this.healthProbe = healthProbe;
        this.healthWait = healthWait;
    }

    /**
     * Base URL of a ready container for {@code key}, starting one via {@code starter} if this is
     * the first use or the previous container is no longer healthy. Concurrent calls for
     * different keys never block on each other; concurrent calls for the same key serialize so
     * only one container is ever started for it.
     */
    public String ensureReady(String key, Starter starter) {
        return ensureReady(key, starter, true);
    }

    /** A caller with other admitted uses must keep their container alive during a failed probe. */
    public String ensureReady(String key, Starter starter, boolean mayRestart) {
        Object lock = startLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            StartedContainer existing = containers.get(key);
            if (existing != null && healthProbe.test(existing.baseUrl(), healthPath)) {
                return existing.baseUrl();
            }
            if (existing != null && !mayRestart) {
                healthWait.accept(existing.baseUrl(), healthPath);
                return existing.baseUrl();
            }
            if (existing != null) {
                LOG.warnv("Sidecar container for key {0} is no longer healthy; restarting it", key);
                AtomicBoolean retained = new AtomicBoolean();
                synchronized (registryLock) {
                    // Admission and detachment share a short map operation, never Docker I/O.
                    activeUses.compute(key, (k, active) -> {
                        retained.set(active != null);
                        if (active != null) {
                            retiredContainers.computeIfAbsent(key, unused -> new ArrayList<>()).add(existing);
                            containers.remove(key, existing);
                        }
                        return active;
                    });
                }
                if (!retained.get()) {
                    removeOwned(new PendingRemoval(key, existing.containerId()));
                }
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
                removeOwned(new PendingRemoval(key, started.containerId()));
                throw new IllegalStateException("Sidecar pool was reset while starting a container for key " + key);
            }
            healthWait.accept(started.baseUrl(), healthPath);
            synchronized (registryLock) {
                if (generation != startedGeneration || containers.get(key) != started) {
                    throw new IllegalStateException("Sidecar pool was reset while waiting for container readiness for key "
                            + key);
                }
            }
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
        return containers.containsKey(key) || retiredContainers.containsKey(key);
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
        stopContainer(key, false);
    }

    /** Stops one key only after Docker confirms that its container is gone. */
    public void stopContainerStrict(String key) {
        stopContainer(key, true);
    }

    private void stopContainer(String key, boolean strict) {
        if (key == null) {
            return;
        }
        Object lock = startLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            List<PendingRemoval> removals;
            synchronized (registryLock) {
                StartedContainer container = strict ? containers.get(key) : containers.remove(key);
                if (container != null) {
                    pendingCleanup.put(container.containerId(), new PendingRemoval(key, container.containerId()));
                }
                Map<String, PendingRemoval> owned = new LinkedHashMap<>();
                for (StartedContainer retired : retiredContainers.getOrDefault(key, List.of())) {
                    owned.put(retired.containerId(), new PendingRemoval(key, retired.containerId()));
                }
                pendingCleanup.values().stream().filter(removal -> key.equals(removal.key()))
                        .forEach(removal -> owned.put(removal.containerId(), removal));
                removals = List.copyOf(owned.values());
            }
            removeEveryOwned(removals);
        }
    }

    /**
     * Compensates a starter failure after container creation but before it returns a ready endpoint.
     * Failed removal remains owned for release or reset; the caller still throws its startup failure.
     */
    public void removeFailedStart(String key, String containerId, RuntimeException startupFailure) {
        try {
            removeOwned(new PendingRemoval(key, containerId));
        } catch (RuntimeException cleanupFailure) {
            if (cleanupFailure != startupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private void removeOwned(PendingRemoval removal) {
        synchronized (registryLock) {
            pendingCleanup.put(removal.containerId(), removal);
        }
        lifecycleManager.stopAndRemoveStrict(removal.containerId(), null);
        synchronized (registryLock) {
            pendingCleanup.remove(removal.containerId(), removal);
            List<StartedContainer> retired = retiredContainers.get(removal.key());
            if (retired != null) {
                retired.removeIf(container -> removal.containerId().equals(container.containerId()));
                if (retired.isEmpty()) {
                    retiredContainers.remove(removal.key(), retired);
                }
            }
            containers.computeIfPresent(removal.key(), (key, active) ->
                    removal.containerId().equals(active.containerId()) ? null : active);
        }
    }

    /** Detaches this generation, then retries owned removals without blocking later starts. */
    public void stopAll() {
        Map<String, PendingRemoval> detached;
        synchronized (registryLock) {
            generation++;
            retiredContainers.forEach((key, retired) -> retired.forEach(container ->
                    pendingCleanup.put(container.containerId(), new PendingRemoval(key, container.containerId()))));
            containers.forEach((key, container) -> pendingCleanup.put(container.containerId(),
                    new PendingRemoval(key, container.containerId())));
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

    /** Stops unprotected containers, retaining failed removals for a later teardown retry. */
    public void stopAllStrictExcept(Set<String> protectedKeys) {
        List<String> keys;
        synchronized (registryLock) {
            generation++;
            Set<String> ownedKeys = new HashSet<>(containers.keySet());
            ownedKeys.addAll(retiredContainers.keySet());
            pendingCleanup.values().forEach(removal -> ownedKeys.add(removal.key()));
            keys = List.copyOf(ownedKeys);
        }
        RuntimeException failure = null;
        for (String key : keys) {
            if (protectedKeys.contains(key)) {
                continue;
            }
            try {
                stopContainerStrict(key);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
