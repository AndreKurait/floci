package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.model.Network;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.netty.util.NetUtil;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Prepares DNS before runtime initialization on Docker bridges without external forwarding. */
final class LambdaDnsResolver {

    private static final String RESOLV_CONF = "/etc/resolv.conf";
    private static final Set<String> SPECIAL_NETWORKS = Set.of("bridge", "default", "host", "none");

    private LambdaDnsResolver() {
    }

    static String prepare(ContainerBuilder.Builder workload, ContainerBuilder containers,
                          ContainerLifecycleManager lifecycle, EmulatorConfig config, String platform) {
        ContainerSpec spec = workload.build();
        if (!requiresResolver(spec, lifecycle)) {
            return null;
        }
        byte[] content = resolverContent(spec);
        String suffix = UUID.randomUUID().toString();
        String volume = ContainerStorageHelper.dockerName(config, "lambda-dns-" + suffix);
        String directory = "/floci-dns-" + suffix;
        Map<String, String> labels = new LinkedHashMap<>(spec.labels());
        labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
        String helperId = null;
        try {
            lifecycle.ensureVolume(volume, labels);
            ContainerSpec helper = containers.newContainer(spec.image())
                    .withName(volume + "-init")
                    .withEnv(List.of())
                    .withNetworkMode("none")
                    .withLabels(labels)
                    .withNamedVolume(volume, directory, false)
                    .build();
            helperId = platform == null ? lifecycle.create(helper) : lifecycle.create(helper, platform);
            RetryingTarCopier.copyBytes(lifecycle.getDockerClient(), helperId, directory,
                    "resolv.conf", content, 0444);
            String mountpoint = lifecycle.getDockerClient().inspectVolumeCmd(volume).exec().getMountpoint();
            if (mountpoint == null || !mountpoint.startsWith("/") || mountpoint.contains("\n")) {
                throw new IllegalStateException("Docker did not return an absolute resolver volume mountpoint");
            }
            lifecycle.removeIfExistsStrict(helperId, true);
            helperId = null;
            // The named mount keeps Docker's in-use protection; the file bind survives runtime restarts.
            workload.withNamedVolume(volume, directory, true)
                    .withReadOnlyBind(mountpoint + "/resolv.conf", RESOLV_CONF);
            return volume;
        } catch (RuntimeException failure) {
            if (helperId != null) {
                try {
                    lifecycle.removeIfExistsStrict(helperId, true);
                } catch (RuntimeException cleanupFailure) {
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            try {
                lifecycle.removeVolumeStrict(volume);
            } catch (RuntimeException cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    static boolean requiresResolver(ContainerSpec spec, ContainerLifecycleManager lifecycle) {
        String networkMode = spec.networkMode();
        if (spec.dnsServers().isEmpty() || networkMode == null || networkMode.isBlank()
                || SPECIAL_NETWORKS.contains(networkMode) || networkMode.startsWith("container:")
                || !spec.volumesFrom().isEmpty()
                || spec.binds().stream().anyMatch(bind -> coversResolver(bind.getVolume().getPath()))
                || spec.mounts().stream().anyMatch(mount -> coversResolver(mount.getTarget()))) {
            return false;
        }
        Network network = lifecycle.getDockerClient().inspectNetworkCmd().withNetworkId(networkMode).exec();
        return Boolean.TRUE.equals(network.getInternal());
    }

    private static boolean coversResolver(String target) {
        return target != null && (target.equals(RESOLV_CONF) || target.equals("/etc") || target.equals("/"));
    }

    static byte[] resolverContent(ContainerSpec spec) {
        StringBuilder content = new StringBuilder();
        for (String server : spec.dnsServers()) {
            if (!NetUtil.isValidIpV4Address(server) && !NetUtil.isValidIpV6Address(server)) {
                throw new IllegalArgumentException("Lambda DNS requires an IP address");
            }
            content.append("nameserver ").append(server).append('\n');
        }
        return content.append("options ndots:0\n").toString().getBytes(StandardCharsets.UTF_8);
    }
}
