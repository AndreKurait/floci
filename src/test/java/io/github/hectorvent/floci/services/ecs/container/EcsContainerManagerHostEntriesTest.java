package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.api.model.VersionComponent;
import com.github.dockerjava.api.model.VersionPlatform;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The /etc/hosts entries of an ECS task container, read off the {@link ContainerSpec} the real
 * {@link EcsContainerManager} hands Docker. The ECS agent adds a hosts entry to a task container
 * only when its container definition declares {@code extraHosts}, so Floci adds none of its own,
 * and a Floci on a Linux host still hands its tasks an endpoint they can reach without one.
 */
class EcsContainerManagerHostEntriesTest {

    private static final String NATIVE_ENDPOINT = "http://host.docker.internal:4566";

    private EmulatorConfig config;
    private LaunchedContainerAwsEnv awsEnv;
    private ContainerLifecycleManager lifecycleManager;
    private DockerClient dockerClient;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().dockerFlags()).thenReturn(Optional.empty());
        when(config.services().ecs().additionalDockerNetworks()).thenReturn(Optional.empty());
        when(config.services().ecs().dockerNetwork()).thenReturn(Optional.empty());
        when(config.services().ecs().extraHosts()).thenReturn(Optional.empty());
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(config.services().ecs().allowPrivilegedContainers()).thenReturn(false);
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("3");

        DockerHostResolver hostResolver = mock(DockerHostResolver.class);
        when(hostResolver.isLinuxHost()).thenReturn(true);
        EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
        when(dns.getServerIp()).thenReturn(Optional.empty());
        ContainerBuilder containerBuilder = new ContainerBuilder(config, hostResolver, dns);

        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        dockerEngine("Docker Engine - Community", "Ubuntu 24.04 LTS", "172.17.0.1");

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(lifecycleManager.resolveImageForLaunch(any(), any(), any()))
                .thenAnswer(invocation -> new LaunchImage(invocation.getArgument(0), null));
        when(lifecycleManager.createAndStartEphemeral(any(), nullable(String.class)))
                .thenReturn(new ContainerInfo("docker-id", Map.of()));
        when(lifecycleManager.createAndStartEphemeral(any(), nullable(String.class), any()))
                .thenReturn(new ContainerInfo("docker-id", Map.of()));

        awsEnv = mock(LaunchedContainerAwsEnv.class);
        nativeFloci();

        EcrRegistryManager ecr = mock(EcrRegistryManager.class);
        when(ecr.rewriteImageUri(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config, mock(RegionResolver.class), awsEnv, mock(SsmService.class),
                mock(SecretsManagerService.class), mock(S3Service.class), ecr, mock(HostVolumePolicy.class));
    }

    @Test
    void taskContainerGetsNoHostsEntryByDefault() {
        manager.startTask(task(), taskDefinition(container("app"), container("sidecar")), null, "eu-west-1");

        for (ContainerSpec spec : specs()) {
            assertEquals(List.of(), spec.extraHosts(), spec.name());
        }
    }

    @Test
    void declaredExtraHostsAreAppliedExactly() {
        ContainerDefinition app = container("app");
        extraHosts(app, host("db.internal", "10.0.0.5"), host("v6.internal", "2001:db8::1"),
                host("gateway.internal", "host-gateway"));

        manager.startTask(task(), taskDefinition(app, container("sidecar")), null, "eu-west-1");

        List<ContainerSpec> specs = specs();
        assertEquals(List.of("db.internal:10.0.0.5", "v6.internal:2001:db8::1", "gateway.internal:host-gateway"),
                specs.get(0).extraHosts());
        // Another container in the task declares none, so it gets none.
        assertEquals(List.of(), specs.get(1).extraHosts());
    }

    @Test
    void nativeLinuxFlociEndpointIsReachableWithoutTheHostsEntry() {
        assumeTrue(linuxJvm(), "host.docker.internal is Docker Desktop's own name off Linux");

        EcsTask task = task();
        manager.startTask(task, taskDefinition(container("app")), null, "eu-west-1");

        ContainerSpec spec = specs().getFirst();
        assertEquals(List.of(), spec.extraHosts());
        List<String> env = spec.env();
        assertTrue(env.contains("AWS_ENDPOINT_URL=http://172.17.0.1:4566"), env.toString());
        assertTrue(env.contains("FLOCI_ENDPOINT=http://172.17.0.1:4566"), env.toString());
        assertTrue(env.contains("FLOCI_HOSTNAME=172.17.0.1"), env.toString());
        String metadataId = task.getContainers().getFirst().getMetadataId();
        assertTrue(env.contains("ECS_CONTAINER_METADATA_URI_V4=http://172.17.0.1:4566/v4/" + metadataId),
                env.toString());
        assertFalse(env.stream().anyMatch(kv -> kv.contains("host.docker.internal")), env.toString());
    }

    @Test
    void containerizedFlociEndpointIsLeftAsItIs() {
        when(awsEnv.flociEndpoint()).thenReturn("http://localhost.floci.io:4566");
        when(awsEnv.flociEndpoint(anyBoolean())).thenReturn("http://localhost.floci.io:4566");
        when(awsEnv.sdkBaselineEnv(anyString(), any())).thenReturn(baseline("http://localhost.floci.io:4566"));

        manager.startTask(task(), taskDefinition(container("app")), null, "eu-west-1");

        ContainerSpec spec = specs().getFirst();
        assertEquals(List.of(), spec.extraHosts());
        assertTrue(spec.env().contains("AWS_ENDPOINT_URL=http://localhost.floci.io:4566"), spec.env().toString());
    }

    @Test
    void configuredExtraHostsRestoreTheOldEntryAndKeepTheName() {
        when(config.services().ecs().extraHosts())
                .thenReturn(Optional.of(List.of("host.docker.internal:host-gateway")));

        manager.startTask(task(), taskDefinition(container("app")), null, "eu-west-1");

        ContainerSpec spec = specs().getFirst();
        assertEquals(List.of("host.docker.internal:host-gateway"), spec.extraHosts());
        assertTrue(spec.env().contains("AWS_ENDPOINT_URL=" + NATIVE_ENDPOINT), spec.env().toString());
    }

    @Test
    void declaredEntriesComeBeforeConfiguredOnes() {
        when(config.services().ecs().extraHosts()).thenReturn(Optional.of(List.of("registry.local:10.9.0.9")));
        ContainerDefinition app = container("app");
        extraHosts(app, host("db.internal", "10.0.0.5"));

        manager.startTask(task(), taskDefinition(app), null, "eu-west-1");

        assertEquals(List.of("db.internal:10.0.0.5", "registry.local:10.9.0.9"), specs().getFirst().extraHosts());
    }

    @Test
    void operatorAddHostForTheAliasKeepsTheName() {
        when(config.services().ecs().dockerFlags())
                .thenReturn(Optional.of("--add-host host.docker.internal:10.9.0.2"));

        manager.startTask(task(), taskDefinition(container("app")), null, "eu-west-1");

        ContainerSpec spec = specs().getFirst();
        assertEquals(List.of("host.docker.internal:10.9.0.2"), spec.extraHosts());
        // Sealed by docker-flags, so no SDK endpoint; the metadata URI keeps the operator's name.
        assertTrue(spec.env().stream().anyMatch(kv ->
                kv.startsWith("ECS_CONTAINER_METADATA_URI_V4=" + NATIVE_ENDPOINT + "/v4/")), spec.env().toString());
    }

    @Test
    void dockerDesktopKeepsTheNameItProvidesItself() {
        dockerEngine("Docker Engine - Community", "Docker Desktop", "172.17.0.1");

        manager.startTask(task(), taskDefinition(container("app")), null, "eu-west-1");

        ContainerSpec spec = specs().getFirst();
        assertEquals(List.of(), spec.extraHosts());
        assertTrue(spec.env().contains("AWS_ENDPOINT_URL=" + NATIVE_ENDPOINT), spec.env().toString());
    }

    @Test
    void unknownBridgeGatewayKeepsTheOldEntryRatherThanGuess() {
        assumeTrue(linuxJvm(), "only a Linux host ever needed the entry");
        dockerEngine("Docker Engine - Community", "Ubuntu 24.04 LTS", null);

        manager.startTask(task(), taskDefinition(container("app")), null, "eu-west-1");

        ContainerSpec spec = specs().getFirst();
        assertEquals(List.of("host.docker.internal:host-gateway"), spec.extraHosts());
        assertTrue(spec.env().contains("AWS_ENDPOINT_URL=" + NATIVE_ENDPOINT), spec.env().toString());
    }

    @Test
    void aDefinitionMappingTheAliasItselfIsNotOverriddenByTheFallbackEntry() {
        dockerEngine("Docker Engine - Community", "Ubuntu 24.04 LTS", null);
        ContainerDefinition app = container("app");
        extraHosts(app, host("host.docker.internal", "10.1.2.3"));

        manager.startTask(task(), taskDefinition(app), null, "eu-west-1");

        assertEquals(List.of("host.docker.internal:10.1.2.3"), specs().getFirst().extraHosts());
    }

    @Test
    void aStoredEntryThatWouldWriteAnotherHostsLineIsDropped() {
        ContainerDefinition app = container("app");
        extraHosts(app, host("db.internal\n10.6.6.6 sts.amazonaws.com", "10.0.0.5"), host("ok.internal", "10.0.0.6"));

        manager.startTask(task(), taskDefinition(app), null, "eu-west-1");

        assertEquals(List.of("ok.internal:10.0.0.6"), specs().getFirst().extraHosts());
    }

    @Test
    void awsvpcTaskDoesNotTakeDeclaredExtraHosts() {
        ContainerDefinition app = container("app");
        extraHosts(app, host("db.internal", "10.0.0.5"));
        TaskDefinition taskDef = taskDefinition(app);
        taskDef.setNetworkMode(NetworkMode.awsvpc);

        manager.startTask(task(), taskDef, null, "eu-west-1");

        assertEquals(List.of(), specs().getFirst().extraHosts());
    }

    private void nativeFloci() {
        when(awsEnv.flociEndpoint()).thenReturn(NATIVE_ENDPOINT);
        when(awsEnv.flociEndpoint(anyBoolean())).thenReturn(NATIVE_ENDPOINT);
        when(awsEnv.sdkBaselineEnv(anyString(), any())).thenReturn(baseline(NATIVE_ENDPOINT));
    }

    private static List<String> baseline(String endpoint) {
        return List.of("AWS_DEFAULT_REGION=eu-west-1", "AWS_REGION=eu-west-1",
                "AWS_ACCESS_KEY_ID=test", "AWS_SECRET_ACCESS_KEY=test", "AWS_SESSION_TOKEN=test",
                "FLOCI_HOSTNAME=" + URI.create(endpoint).getHost(),
                "FLOCI_ENDPOINT=" + endpoint, "AWS_ENDPOINT_URL=" + endpoint);
    }

    private void dockerEngine(String component, String operatingSystem, String bridgeGateway) {
        Version version = mock(Version.class);
        VersionComponent engine = mock(VersionComponent.class);
        when(engine.getName()).thenReturn("Engine");
        when(version.getComponents()).thenReturn(List.of(engine));
        when(version.getPlatform()).thenReturn(new VersionPlatform().withName(component));
        when(dockerClient.versionCmd().exec()).thenReturn(version);
        Info info = mock(Info.class);
        when(info.getOperatingSystem()).thenReturn(operatingSystem);
        when(dockerClient.infoCmd().exec()).thenReturn(info);
        Network bridge = mock(Network.class, RETURNS_DEEP_STUBS);
        Network.Ipam.Config ipam = mock(Network.Ipam.Config.class);
        when(ipam.getGateway()).thenReturn(bridgeGateway);
        when(bridge.getIpam().getConfig()).thenReturn(List.of(ipam));
        when(dockerClient.inspectNetworkCmd().withNetworkId("bridge").exec()).thenReturn(bridge);
    }

    private List<ContainerSpec> specs() {
        ArgumentCaptor<ContainerSpec> captor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager, atLeastOnce()).createAndStartEphemeral(captor.capture(), nullable(String.class));
        return captor.getAllValues();
    }

    private static boolean linuxJvm() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") || os.contains("nix") || os.contains("nux");
    }

    private static Map<String, Object> host(String hostname, String ipAddress) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("hostname", hostname);
        entry.put("ipAddress", ipAddress);
        return entry;
    }

    @SafeVarargs
    private static void extraHosts(ContainerDefinition def, Map<String, Object>... entries) {
        Map<String, Object> unparsed = new LinkedHashMap<>();
        unparsed.put("extraHosts", List.of(entries));
        def.setUnparsed(unparsed);
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
