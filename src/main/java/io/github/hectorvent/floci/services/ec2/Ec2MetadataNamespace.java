package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LocallyBuiltHelperImage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Keeps the guest image stopped while Floci prepares its link-local metadata route. */
final class Ec2MetadataNamespace {
    static final String HELPER_LABEL = "io.floci.ec2.metadata-helper";
    private static final String DEFAULT_IMAGE = "floci/network-helper:local";

    private final DockerClient dockerClient;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;

    Ec2MetadataNamespace(DockerClient dockerClient, ContainerBuilder containerBuilder,
                         ContainerLifecycleManager lifecycleManager, EmulatorConfig config) {
        this.dockerClient = dockerClient;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
    }

    String create(String instanceId, String accountId, String region, int sshHostPort) {
        String image = config.services().ec2().metadataHelperImage();
        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder, image, DEFAULT_IMAGE,
                "/docker/network-helper.Dockerfile");
        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(ContainerStorageHelper.resourceName(config, "ec2-imds", null, instanceId))
                .withDockerNetwork(Optional.empty())
                .withEmbeddedDns()
                .withHostDockerInternalOnLinux()
                .withEntrypoint(List.of("sh", "-c"))
                .withCmd(List.of("exec sleep 2147483647"))
                .withCapAdd("NET_ADMIN")
                .withPortBinding(22, sshHostPort)
                .withLabels(ContainerStorageHelper.resourceIdentityLabels("ec2", instanceId, accountId, region))
                .withLabels(Map.of(HELPER_LABEL, "true",
                        ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)))
                .build();
        return lifecycleManager.createAndStart(spec).containerId();
    }
}
