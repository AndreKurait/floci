package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.api.model.VersionComponent;
import io.github.hectorvent.floci.core.common.docker.BridgeGatewayRelay;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * How an ECS task container reaches a Floci endpoint that names {@code host.docker.internal},
 * which is what a Floci running directly on a host hands its containers.
 *
 * <p>The ECS agent adds {@code /etc/hosts} entries to a task container only when its container
 * definition declares {@code extraHosts}, so Floci does not add {@code host.docker.internal} to a
 * task container by itself. Where the container engine does not provide that name, the task is
 * given the address Docker's {@code host-gateway} stands for instead: the default {@code bridge}
 * network's IPv4 gateway, the address {@code BridgeGatewayRelay} listens on for a loopback-bound
 * Floci. Docker Desktop and Podman resolve {@code host.docker.internal} themselves, and a host that
 * is not Linux is always Docker Desktop, so the name is kept there. When the engine cannot be
 * identified or the bridge has no IPv4 gateway, the old {@code host.docker.internal:host-gateway}
 * entry is kept, as Lambda does, rather than handing the task an address that may not be Floci.
 */
final class EcsHostAlias {

    private static final Logger LOG = Logger.getLogger(EcsHostAlias.class);

    static final String HOST_DOCKER_INTERNAL = "host.docker.internal";
    static final String HOST_GATEWAY_ENTRY = HOST_DOCKER_INTERNAL + ":host-gateway";
    private static final String DEFAULT_BRIDGE = "bridge";

    /** What a task container needs to reach a Floci URL naming {@code host.docker.internal}. */
    record Resolution(String gatewayAddress, boolean addHostEntry) {
        static final Resolution KEEP_NAME = new Resolution(null, false);
        static final Resolution ADD_ENTRY = new Resolution(null, true);

        /** {@code url} with a {@code host.docker.internal} host replaced by the gateway address. */
        String rewrite(String url) {
            if (url == null || gatewayAddress == null) {
                return url;
            }
            try {
                URI uri = new URI(url);
                if (!namesHostAlias(uri) || uri.getScheme() == null) {
                    return url;
                }
                // Only the host changes; the rest is kept as it was written, encodings included.
                StringBuilder rewritten = new StringBuilder(uri.getScheme()).append("://");
                if (uri.getRawUserInfo() != null) {
                    rewritten.append(uri.getRawUserInfo()).append('@');
                }
                rewritten.append(gatewayAddress);
                if (uri.getPort() >= 0) {
                    rewritten.append(':').append(uri.getPort());
                }
                if (uri.getRawPath() != null) {
                    rewritten.append(uri.getRawPath());
                }
                if (uri.getRawQuery() != null) {
                    rewritten.append('?').append(uri.getRawQuery());
                }
                if (uri.getRawFragment() != null) {
                    rewritten.append('#').append(uri.getRawFragment());
                }
                return rewritten.toString();
            } catch (URISyntaxException e) {
                return url;
            }
        }

        /** A bare host name, as FLOCI_HOSTNAME carries it. */
        String rewriteHost(String host) {
            return gatewayAddress != null && namesHostAlias(host) ? gatewayAddress : host;
        }
    }

    /** How long an inconclusive lookup stands before the next task asks Docker again. */
    static final long RETRY_AFTER_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final Supplier<DockerClient> dockerClient;
    private final BooleanSupplier linuxHost;
    private final LongSupplier nanoClock;
    private volatile Resolution resolved;
    private volatile boolean fallbackWarned;
    private long retryAt;
    private boolean failedBefore;

    EcsHostAlias(Supplier<DockerClient> dockerClient, BooleanSupplier linuxHost) {
        this(dockerClient, linuxHost, System::nanoTime);
    }

    EcsHostAlias(Supplier<DockerClient> dockerClient, BooleanSupplier linuxHost, LongSupplier nanoClock) {
        this.dockerClient = dockerClient;
        this.linuxHost = linuxHost;
        this.nanoClock = nanoClock;
    }

    static boolean namesHostAlias(String host) {
        return host != null && HOST_DOCKER_INTERNAL.equalsIgnoreCase(host);
    }

    static boolean namesHostAlias(URI uri) {
        return uri != null && namesHostAlias(uri.getHost());
    }

    /** True for a {@code hostname:ip} entry that maps {@code host.docker.internal} itself. */
    static boolean isHostAliasEntry(String entry) {
        int separator = entry == null ? -1 : entry.indexOf(':');
        return separator > 0 && separator < entry.length() - 1
                && namesHostAlias(entry.substring(0, separator));
    }

    /**
     * Looked up once the engine answers. An unidentified engine, a missing gateway or a daemon that
     * did not answer keeps the old entry and is asked again after {@link #RETRY_AFTER_NANOS}, so a
     * slow daemon does not hold up every launch behind the same lookup.
     */
    Resolution resolve() {
        Resolution current = resolved;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (resolved != null) {
                return resolved;
            }
            if (!linuxHost.getAsBoolean()) {
                resolved = Resolution.KEEP_NAME;
                return resolved;
            }
            long now = nanoClock.getAsLong();
            if (failedBefore && now - retryAt < 0) {
                return Resolution.ADD_ENTRY;
            }
            Resolution result;
            try {
                result = probe(dockerClient.get());
            } catch (RuntimeException e) {
                warnFallback("the Docker daemon did not answer (" + e.getMessage() + ")");
                result = null;
            }
            if (result == null) {
                failedBefore = true;
                retryAt = now + RETRY_AFTER_NANOS;
                return Resolution.ADD_ENTRY;
            }
            resolved = result;
            return result;
        }
    }

    private Resolution probe(DockerClient docker) {
        if (docker == null) {
            warnFallback("no Docker client");
            return null;
        }
        List<String> engineNames = engineNames(docker.versionCmd().exec());
        if (engineNames.stream().anyMatch(name -> name.contains("podman"))) {
            return Resolution.KEEP_NAME;
        }
        Info info = docker.infoCmd().exec();
        String os = info == null ? null : info.getOperatingSystem();
        if (os != null && os.toLowerCase(Locale.ROOT).contains("docker desktop")) {
            return Resolution.KEEP_NAME;
        }
        // Distribution builds (docker.io, moby-engine) leave the platform name empty and report
        // only an "Engine" component; Podman's carries "podman" and was caught above.
        if (engineNames.stream().noneMatch(name -> name.contains("docker") || name.contains("moby")
                || name.equals("engine"))) {
            warnFallback("the container engine could not be identified");
            return null;
        }
        String gateway = BridgeGatewayRelay.gatewayOf(docker.inspectNetworkCmd().withNetworkId(DEFAULT_BRIDGE).exec());
        if (gateway == null) {
            warnFallback("the default bridge network has no IPv4 gateway");
            return null;
        }
        LOG.infov("ECS task containers reach Floci at the Docker bridge gateway {0}"
                + " instead of a host.docker.internal hosts entry", gateway);
        return new Resolution(gateway, false);
    }

    private static List<String> engineNames(Version version) {
        if (version == null) {
            return List.of();
        }
        List<VersionComponent> components = version.getComponents();
        return Stream.concat(
                        Stream.of(version.getPlatform() == null ? null : version.getPlatform().getName()),
                        components == null ? Stream.empty()
                                : components.stream().filter(Objects::nonNull).map(VersionComponent::getName))
                .filter(Objects::nonNull)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
    }

    private void warnFallback(String reason) {
        if (!fallbackWarned) {
            fallbackWarned = true;
            LOG.warnv("Cannot tell how ECS task containers reach host.docker.internal: {0}; keeping the"
                    + " host.docker.internal:host-gateway hosts entry on task containers", reason);
        }
    }
}
