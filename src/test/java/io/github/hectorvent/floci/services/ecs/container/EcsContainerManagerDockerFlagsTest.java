package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ECS task containers sealed like Lambda's: {@code floci.services.ecs.docker-flags} and
 * {@code floci.services.ecs.additional-docker-networks} are applied to every task container, a
 * configured {@code --dns} replaces Floci's embedded DNS, and a sealed task reaches AWS by name
 * (no Floci endpoint variables). A container definition's {@code dockerLabels} become Docker
 * labels, without displacing Floci's own ownership labels.
 */
class EcsContainerManagerDockerFlagsTest {

    private static final String FLAGS = "--dns 10.9.0.53 --add-host floci:10.9.0.2 "
            + "-v /run/aoscp/ca.pem:/etc/aoscp/ca.pem:ro --env SSL_CERT_FILE=/etc/aoscp/ca.pem";

    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private LaunchedContainerAwsEnv awsEnv;
    private EmulatorConfig config;
    private DockerClient dockerClient;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.resolveImageForLaunch(any(), any(), any()))
                .thenAnswer(invocation -> new LaunchImage(invocation.getArgument(0), null));
        when(lifecycleManager.createAndStartEphemeral(any(), nullable(String.class)))
                .thenReturn(new ContainerInfo("docker-id", Map.of()));
        when(lifecycleManager.createAndStartEphemeral(any(), nullable(String.class), any())).thenAnswer(invocation -> {
            Consumer<String> beforeStart = invocation.getArgument(2);
            beforeStart.accept("docker-id");
            return new ContainerInfo("docker-id", Map.of());
        });
        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().dockerFlags()).thenReturn(Optional.empty());
        when(config.services().ecs().additionalDockerNetworks()).thenReturn(Optional.empty());

        awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(anyString(), any())).thenReturn(List.of(
                "AWS_DEFAULT_REGION=eu-west-1",
                "AWS_REGION=eu-west-1",
                "AWS_ACCESS_KEY_ID=test",
                "AWS_SECRET_ACCESS_KEY=test",
                "AWS_SESSION_TOKEN=test",
                "FLOCI_HOSTNAME=floci",
                "FLOCI_ENDPOINT=http://floci:4566",
                "AWS_ENDPOINT_URL=http://floci:4566"));
        when(awsEnv.flociEndpoint()).thenReturn("http://floci:4566");
        when(awsEnv.flociEndpoint(anyBoolean())).thenAnswer(invocation ->
                (boolean) invocation.getArgument(0) ? "http://floci:4566" : "http://10.9.0.2:4566");

        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config, mock(RegionResolver.class), awsEnv, mock(SsmService.class),
                mock(SecretsManagerService.class), mock(S3Service.class), ecrRegistryManager,
                mock(HostVolumePolicy.class));
    }

    @Test
    void dockerFlagsAreAppliedBeforeTheEmbeddedDnsFallback() {
        when(config.services().ecs().dockerFlags()).thenReturn(Optional.of(FLAGS));

        manager.startTask(task(), taskDefinition(container("app")), null, "eu-west-1");

        InOrder order = inOrder(builder);
        order.verify(builder).withDnsServer("10.9.0.53");
        order.verify(builder).withEmbeddedDns();
        verify(builder).withExtraHost("floci", "10.9.0.2");
        verify(builder).withReadOnlyBind("/run/aoscp/ca.pem", "/etc/aoscp/ca.pem");
        verify(builder).withEnv(List.of("SSL_CERT_FILE=/etc/aoscp/ca.pem"));
    }

    @Test
    void sealedTaskReachesAwsByNameButKeepsRegionAndTaskMetadata() {
        when(config.services().ecs().dockerFlags()).thenReturn(Optional.of(FLAGS));

        EcsTask task = task();
        manager.startTask(task, taskDefinition(container("app")), null, "eu-west-1");

        List<String> env = taskEnv();
        assertFalse(env.stream().anyMatch(kv -> kv.startsWith("AWS_ENDPOINT_URL=")), env.toString());
        assertFalse(env.stream().anyMatch(kv -> kv.startsWith("FLOCI_ENDPOINT=")), env.toString());
        assertFalse(env.stream().anyMatch(kv -> kv.startsWith("FLOCI_HOSTNAME=")), env.toString());
        assertTrue(env.contains("AWS_REGION=eu-west-1"), env.toString());
        assertTrue(env.contains("AWS_DEFAULT_REGION=eu-west-1"), env.toString());
        // Floci's embedded DNS is not the container's resolver any more, so the metadata URI must
        // not depend on it: it names the address Floci is reachable at directly.
        String metadataId = task.getContainers().getFirst().getMetadataId();
        assertTrue(env.contains("ECS_CONTAINER_METADATA_URI_V4=http://10.9.0.2:4566/v4/" + metadataId),
                env.toString());
    }

    @Test
    void sealedTaskKeepsAnEndpointItsDefinitionSetsItself() {
        when(config.services().ecs().dockerFlags()).thenReturn(Optional.of(FLAGS));
        ContainerDefinition app = container("app");
        app.setEnvironment(List.of(new KeyValuePair("AWS_ENDPOINT_URL", "https://aoscp-aws")));

        manager.startTask(task(), taskDefinition(app), null, "eu-west-1");

        assertTrue(taskEnv().contains("AWS_ENDPOINT_URL=https://aoscp-aws"));
    }

    @Test
    void unsealedTaskStillGetsTheFlociEndpoint() {
        manager.startTask(task(), taskDefinition(container("app")), null, "eu-west-1");

        List<String> env = taskEnv();
        assertTrue(env.contains("AWS_ENDPOINT_URL=http://floci:4566"), env.toString());
        assertTrue(env.contains("FLOCI_ENDPOINT=http://floci:4566"), env.toString());
    }

    @Test
    void unsupportedDockerFlagFailsTheLaunchBeforeAnyContainerExists() {
        when(config.services().ecs().dockerFlags()).thenReturn(Optional.of("--cap-add NET_ADMIN"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> manager.startTask(task(), taskDefinition(container("app")), null, "eu-west-1"));

        assertTrue(failure.getMessage().contains("ECS"), failure.getMessage());
        verify(lifecycleManager, never()).createAndStartEphemeral(any(), nullable(String.class));
        verify(lifecycleManager, never()).createAndStartEphemeral(any(), nullable(String.class), any());
    }

    @Test
    void additionalNetworksAreJoinedBeforeTheContainerStarts() {
        when(config.services().ecs().additionalDockerNetworks()).thenReturn(Optional.of(List.of("metadata-net")));
        when(dockerClient.inspectContainerCmd("docker-id").exec().getNetworkSettings().getNetworks())
                .thenReturn(Map.of());
        when(dockerClient.inspectNetworkCmd().withNetworkId("metadata-net").exec().getId())
                .thenReturn("metadata-id");
        ConnectToNetworkCmd connect = mock(ConnectToNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.connectToNetworkCmd()).thenReturn(connect);

        manager.startTask(task(), taskDefinition(container("app")), null, "eu-west-1");

        verify(lifecycleManager).createAndStartEphemeral(any(), nullable(String.class), any());
        verify(connect).withNetworkId("metadata-id");
        verify(connect).withContainerId("docker-id");
        verify(connect).exec();
    }

    @Test
    void invalidAdditionalNetworksFailBeforeAnyContainerExists() {
        when(config.services().ecs().additionalDockerNetworks()).thenReturn(Optional.of(List.of("a", "a")));

        assertThrows(IllegalArgumentException.class,
                () -> manager.startTask(task(), taskDefinition(container("app")), null, "eu-west-1"));

        verify(lifecycleManager, never()).createAndStartEphemeral(any(), nullable(String.class));
        verify(lifecycleManager, never()).createAndStartEphemeral(any(), nullable(String.class), any());
    }

    @Test
    void dockerLabelsAreAppliedWithoutDisplacingFlociOwnershipLabels() {
        ContainerDefinition app = container("app");
        app.setDockerLabels(Map.of(
                "com.example.team", "search",
                ContainerStorageHelper.SERVICE_LABEL, "spoofed",
                ContainerStorageHelper.ECS_RUN_LABEL, "spoofed"));

        manager.startTask(task(), taskDefinition(app), null, "eu-west-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
        verify(builder, atLeastOnce()).withLabels(labels.capture());
        // The builder merges label maps in call order, later calls winning.
        Map<String, String> effective = new LinkedHashMap<>();
        labels.getAllValues().forEach(effective::putAll);
        assertEquals("search", effective.get("com.example.team"), effective.toString());
        assertEquals("ecs", effective.get(ContainerStorageHelper.SERVICE_LABEL), effective.toString());
        assertNotEquals("spoofed", effective.get(ContainerStorageHelper.ECS_RUN_LABEL), effective.toString());
    }

    /** The env built from the task definition and baseline (not the docker-flags' --env list). */
    private List<String> taskEnv() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> envCaptor = ArgumentCaptor.forClass(List.class);
        verify(builder, atLeastOnce()).withEnv(envCaptor.capture());
        return envCaptor.getAllValues().stream()
                .filter(env -> env.stream().anyMatch(kv -> kv.startsWith("AWS_REGION=")))
                .findFirst()
                .orElseThrow();
    }

    private static EcsTask task() {
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:eu-west-1:123456789012:task/app-cluster/0123456789abcdef0123456789abcdef");
        return task;
    }

    private static TaskDefinition taskDefinition(ContainerDefinition... containers) {
        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("app-api");
        taskDef.setContainerDefinitions(List.of(containers));
        return taskDef;
    }

    private static ContainerDefinition container(String name) {
        ContainerDefinition def = new ContainerDefinition();
        def.setName(name);
        def.setImage(name + ":latest");
        return def;
    }
}
