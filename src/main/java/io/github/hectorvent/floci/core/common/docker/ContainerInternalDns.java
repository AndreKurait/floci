package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateVolumeResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.Network;
import io.github.hectorvent.floci.config.EmulatorConfig;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Docker's resolver answers peer aliases on internal bridges but refuses to forward other
 * questions, including questions for a configured DNS server on that same bridge. Keep it first
 * and expose the configured resolvers directly after it, before the workload starts.
 */
final class ContainerInternalDns {
    private static final Logger LOG = Logger.getLogger(ContainerInternalDns.class);
    private static final String RESOLVER_PATH = "/etc/resolv.conf";
    private static final String VOLUME_PATH = "/.floci-dns";
    private static final String CONFIG_LABEL = "io.floci.dns.config-id";
    private static final Set<String> SPECIAL_NETWORKS = Set.of("bridge", "default", "host", "none");

    private final DockerClient docker;
    private final EmulatorConfig config;
    private final long retryBackoffMillis;
    private final Map<Key, PreparedVolume> volumes = new HashMap<>();

    ContainerInternalDns(DockerClient docker, EmulatorConfig config, long retryBackoffMillis) {
        this.docker = docker;
        this.config = config;
        this.retryBackoffMillis = retryBackoffMillis;
    }

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    synchronized Lease configure(ContainerSpec spec, String image, HostConfig hostConfig) {
        String networkName = spec.networkMode();
        if (spec.dnsServers() == null || spec.dnsServers().isEmpty()
                || networkName == null || networkName.isBlank()
                || SPECIAL_NETWORKS.contains(networkName) || networkName.startsWith("container:")
                || spec.labels() != null && "true".equals(
                        spec.labels().get(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL))
                || hasResolverMount(spec)) {
            return () -> { };
        }
        Network network = docker.inspectNetworkCmd().withNetworkId(networkName).exec();
        if (!Boolean.TRUE.equals(network.getInternal()) || !"bridge".equals(network.getDriver())) {
            return () -> { };
        }
        Key key = new Key(network.getId(), resolverContents(spec.dnsServers()));
        PreparedVolume volume = volumes.get(key);
        if (volume != null && !existsAndOwned(volume)) {
            volumes.remove(key);
            volume = null;
        }
        if (volume == null) {
            volume = prepare(image, key.contents());
            volumes.put(key, volume);
        }
        List<Mount> mounts = hostConfig.getMounts() == null
                ? new ArrayList<>() : new ArrayList<>(hostConfig.getMounts());
        // The directory mount gives Docker a real volume reference. The file bind uses the
        // daemon-reported mountpoint, so it also works when Floci and Docker run on different hosts.
        mounts.add(volumeMount(volume.name).withReadOnly(true));
        mounts.add(new Mount().withType(MountType.BIND).withSource(volume.mountpoint + "/resolv.conf")
                .withTarget(RESOLVER_PATH).withReadOnly(true));
        hostConfig.withMounts(mounts);
        PreparedVolume leased = volume;
        leased.inFlight++;
        return () -> release(leased);
    }

    private synchronized void release(PreparedVolume volume) {
        volume.inFlight--;
    }

    private static boolean hasResolverMount(ContainerSpec spec) {
        return spec.volumesFrom() != null && !spec.volumesFrom().isEmpty()
                || spec.mounts() != null && spec.mounts().stream()
                    .anyMatch(mount -> coversResolver(mount.getTarget()))
                || spec.binds() != null && spec.binds().stream()
                    .anyMatch(bind -> coversResolver(bind.getVolume().getPath()));
    }

    private static boolean coversResolver(String target) {
        if (target == null) {
            return false;
        }
        String normalized = target.replaceAll("/+$", "");
        return RESOLVER_PATH.equals(normalized) || RESOLVER_PATH.startsWith(normalized + "/")
                || VOLUME_PATH.equals(normalized) || VOLUME_PATH.startsWith(normalized + "/");
    }

    static String resolverContents(List<String> servers) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        ordered.add("127.0.0.11");
        for (String server : servers) {
            ordered.add(InetAddress.ofLiteral(server).getHostAddress());
        }
        StringBuilder contents = new StringBuilder();
        ordered.forEach(server -> contents.append("nameserver ").append(server).append('\n'));
        return contents.append("options ndots:0\n").toString();
    }

    private PreparedVolume prepare(String image, String contents) {
        String id = UUID.randomUUID().toString();
        String name = ContainerStorageHelper.dockerName(config, "dns-" + id);
        Map<String, String> labels = ContainerStorageHelper.defaultLabels(config);
        labels.put(ContainerStorageHelper.COMPONENT_LABEL, "container-dns");
        labels.put(CONFIG_LABEL, id);
        CreateVolumeResponse created = docker.createVolumeCmd().withName(name).withDriver("local")
                .withLabels(labels).exec();
        try {
            String mountpoint = created.getMountpoint();
            if (mountpoint == null || !mountpoint.startsWith("/")) {
                throw new IllegalStateException("Docker did not report an absolute DNS volume mountpoint");
            }
            populate(image, name, labels, contents);
            return new PreparedVolume(name, mountpoint, Map.copyOf(labels));
        } catch (RuntimeException failure) {
            try {
                docker.removeVolumeCmd(name).exec();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void populate(String image, String name, Map<String, String> labels, String contents) {
        HostConfig helperHost = HostConfig.newHostConfig().withNetworkMode("none")
                .withMounts(List.of(volumeMount(name)));
        String helper = docker.createContainerCmd(image).withHostConfig(helperHost)
                .withEntrypoint("/floci-dns-preparation")
                .withLabels(labels).exec().getId();
        RuntimeException primaryFailure = null;
        try {
            // Archive copy mounts volumes for a stopped container. No helper process executes,
            // including for scratch and non-root workload images.
            RetryingTarCopier.copyBytes(docker, helper, VOLUME_PATH, "resolv.conf",
                    contents.getBytes(StandardCharsets.UTF_8), 0444,
                    RetryingTarCopier.DEFAULT_MAX_ATTEMPTS, retryBackoffMillis);
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                docker.removeContainerCmd(helper).withForce(true).withRemoveVolumes(true).exec();
            } catch (RuntimeException cleanupFailure) {
                if (primaryFailure == null) {
                    throw cleanupFailure;
                }
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static Mount volumeMount(String name) {
        return new Mount().withType(MountType.VOLUME).withSource(name).withTarget(VOLUME_PATH);
    }

    private boolean existsAndOwned(PreparedVolume volume) {
        try {
            InspectVolumeResponse inspected = docker.inspectVolumeCmd(volume.name).exec();
            if (inspected.getLabels() == null
                    || !inspected.getLabels().entrySet().containsAll(volume.labels.entrySet())
                    || !volume.mountpoint.equals(inspected.getMountpoint())) {
                throw new IllegalStateException("DNS volume ownership or mountpoint changed: " + volume.name);
            }
            return true;
        } catch (NotFoundException gone) {
            return false;
        }
    }

    /** Called after workload teardown; Docker refuses removal while any workload still mounts it. */
    synchronized void removeUnusedVolumes() {
        Iterator<PreparedVolume> iterator = volumes.values().iterator();
        while (iterator.hasNext()) {
            PreparedVolume volume = iterator.next();
            if (volume.inFlight != 0) {
                continue;
            }
            try {
                if (existsAndOwned(volume)) {
                    docker.removeVolumeCmd(volume.name).exec();
                }
                iterator.remove();
            } catch (RuntimeException failure) {
                LOG.warnv(failure, "Could not remove DNS volume {0}; keeping it for another cleanup", volume.name);
            }
        }
    }

    private record Key(String networkId, String contents) {}

    private static final class PreparedVolume {
        private final String name;
        private final String mountpoint;
        private final Map<String, String> labels;
        private int inFlight;

        private PreparedVolume(String name, String mountpoint, Map<String, String> labels) {
            this.name = name;
            this.mountpoint = mountpoint;
            this.labels = labels;
        }
    }
}
