package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ec2.Ec2MetadataServer;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ECS on EC2 without a task role: the task's environment carries no AWS keys, so the SDK falls
 * through to IMDS, which serves the container instance's instance-profile role. Floci's stand-in
 * for that profile is the container instance's {@code floci.iam-instance-profile} attribute, which
 * placement copies onto the task. The IMDS here is Floci's real one, reached over HTTP from the
 * address the task container is registered under, IMDSv2 token first as the Go SDK does.
 */
class EcsContainerManagerInstanceRoleTest {

    private static final String ACCOUNT = "123456789012";
    private static final String PROFILE = "arn:aws:iam::" + ACCOUNT + ":instance-profile/app/app-host";
    private static final String ROLE = "AppInstanceRole";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT + ":role/" + ROLE;
    private static final String INSTANCE_ID = "i-0abc000000000001";
    private static final String CONTAINER_IP = "127.0.0.1";

    private final HttpClient http = HttpClient.newHttpClient();
    private ContainerBuilder.Builder builder;
    private IamService iam;
    private Vertx vertx;
    private Ec2MetadataServer imds;
    private String imdsEndpoint;
    private EcsContainerManager manager;
    private ContainerLifecycleManager lifecycleManager;
    private DockerClient dockerClient;

    @BeforeEach
    void setUp() throws Exception {
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.resolveImageForLaunch(any(), any(), any()))
                .thenAnswer(invocation -> new LaunchImage(invocation.getArgument(0), null));
        when(lifecycleManager.createAndStartEphemeral(any(), nullable(String.class))).thenReturn(new ContainerInfo("docker-id", Map.of()));
        when(lifecycleManager.createAndStartEphemeral(any(), nullable(String.class), any())).thenAnswer(invocation -> {
            invocation.<Consumer<String>>getArgument(2).accept("docker-id");
            return new ContainerInfo("docker-id", Map.of());
        });
        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn(CONTAINER_IP);
        when(dockerClient.inspectContainerCmd(anyString()).exec().getNetworkSettings().getNetworks())
                .thenReturn(Map.of("app-net", network));
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        int port = FreePorts.anyInterfacePort();
        when(config.services().ec2().imdsPort()).thenReturn(port);
        when(config.services().ecs().dockerFlags()).thenReturn(Optional.empty());
        when(config.services().ecs().additionalDockerNetworks()).thenReturn(Optional.empty());
        when(config.services().ecs().taskRoleCredentials().enabled()).thenReturn(false);

        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(anyString(), any())).thenReturn(List.of(
                "AWS_DEFAULT_REGION=eu-west-1", "AWS_REGION=eu-west-1",
                "AWS_ACCESS_KEY_ID=test", "AWS_SECRET_ACCESS_KEY=test", "AWS_SESSION_TOKEN=test",
                "AWS_ENDPOINT_URL=http://floci:4566"));
        when(awsEnv.flociEndpoint()).thenReturn("http://floci:4566");

        iam = mock(IamService.class);
        InstanceProfile profile = new InstanceProfile();
        profile.setArn(PROFILE);
        profile.setInstanceProfileName("app-host");
        profile.setRoleNames(List.of(ROLE));
        when(iam.findInstanceProfile(ACCOUNT, "app-host")).thenReturn(Optional.of(profile));
        IamRole role = new IamRole();
        role.setRoleName(ROLE);
        role.setRoleId("AROAAPPROLE000000001");
        role.setArn(ROLE_ARN);
        when(iam.findRole(ACCOUNT, ROLE)).thenReturn(Optional.of(role));

        vertx = Vertx.vertx();
        imds = new Ec2MetadataServer(vertx, config, iam);
        imds.start().get(10, TimeUnit.SECONDS);
        imdsEndpoint = "http://127.0.0.1:" + port;

        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));
        manager = new EcsContainerManager(containerBuilder, lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config, mock(RegionResolver.class), awsEnv, mock(SsmService.class),
                mock(SecretsManagerService.class), mock(S3Service.class), ecrRegistryManager,
                mock(HostVolumePolicy.class));
        manager.instanceMetadataServer = imds;
    }

    @AfterEach
    void tearDown() throws Exception {
        try (http) {
            imds.stop();
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void taskWithoutATaskRoleGetsNoKeysAndReadsTheInstanceRoleFromImds() throws Exception {
        EcsTaskHandle handle = manager.startTask(task("0123456789abcdef0123456789abcdef", PROFILE),
                taskDefinition(null), null, "eu-west-1");

        List<String> env = env();
        assertFalse(env.stream().anyMatch(kv -> kv.startsWith("AWS_ACCESS_KEY_ID=")), env.toString());
        assertFalse(env.stream().anyMatch(kv -> kv.startsWith("AWS_SECRET_ACCESS_KEY=")), env.toString());
        assertFalse(env.stream().anyMatch(kv -> kv.startsWith("AWS_SESSION_TOKEN=")), env.toString());
        assertTrue(env.stream().anyMatch(kv -> kv.startsWith("AWS_EC2_METADATA_SERVICE_ENDPOINT=")), env.toString());
        assertTrue(env.contains("AWS_REGION=eu-west-1"), env.toString());

        // IMDSv2, as the Go SDK v1 does it: a token first, then every read with it.
        String token = send(HttpRequest.newBuilder(URI.create(imdsEndpoint + "/latest/api/token"))
                .header("X-aws-ec2-metadata-token-ttl-seconds", "21600")
                .PUT(HttpRequest.BodyPublishers.noBody())).body();
        HttpResponse<String> roles = get("/latest/meta-data/iam/security-credentials/", token);
        assertEquals(200, roles.statusCode());
        assertEquals(ROLE, roles.body());
        HttpResponse<String> credentials = get("/latest/meta-data/iam/security-credentials/" + ROLE, token);
        assertEquals(200, credentials.statusCode(), credentials.body());
        JsonObject json = new JsonObject(credentials.body());
        assertEquals("Success", json.getString("Code"));
        assertTrue(json.getString("AccessKeyId").startsWith("ASIA"), json.encode());

        // Minted as the EC2 instance-profile session it is, so IAM authenticates the key as an
        // assumed-role session of the profile's role in the instance's account.
        ArgumentCaptor<SessionCredential> session = ArgumentCaptor.forClass(SessionCredential.class);
        verify(iam).registerEc2InstanceSession(session.capture());
        assertEquals(json.getString("AccessKeyId"), session.getValue().getAccessKeyId());
        assertEquals(ROLE_ARN, session.getValue().getRoleArn());
        assertEquals(ACCOUNT, session.getValue().getOriginAccountId());
        assertEquals(INSTANCE_ID, session.getValue().getEc2InstanceId());

        manager.stopTask(handle);

        verify(iam).unregisterSession(ACCOUNT, json.getString("AccessKeyId"));
        assertEquals(404, get("/latest/meta-data/iam/security-credentials/" + ROLE, null).statusCode());
    }

    @Test
    void instanceRoleOutlivesATaskWhileAnotherTaskStillRunsOnTheInstance() throws Exception {
        EcsTaskHandle first = manager.startTask(task("0123456789abcdef0123456789abcde1", PROFILE),
                taskDefinition(null), null, "eu-west-1");
        manager.startTask(task("0123456789abcdef0123456789abcde2", PROFILE), taskDefinition(null), null, "eu-west-1");
        String key = new JsonObject(get("/latest/meta-data/iam/security-credentials/" + ROLE, null).body())
                .getString("AccessKeyId");

        manager.stopTask(first);

        verify(iam, never()).unregisterSession(ACCOUNT, key);
    }

    @Test
    void theInstanceRoleIsServedFromTheMomentTheTaskContainerStarts() throws Exception {
        // A Go SDK process asks IMDS for credentials within milliseconds of starting (some services
        // exit on the first refusal), so the address has to resolve before startTask returns.
        int[] status = new int[1];
        Answer<ContainerInfo> starts = invocation -> {
            if (invocation.getArguments().length > 2) {
                invocation.<Consumer<String>>getArgument(2).accept("docker-id");
            }
            status[0] = get("/latest/meta-data/iam/security-credentials/", null).statusCode();
            return new ContainerInfo("docker-id", Map.of());
        };
        doAnswer(starts).when(lifecycleManager)
                .createAndStartEphemeral(any(), nullable(String.class), any());
        doAnswer(starts).when(lifecycleManager).createAndStartEphemeral(any(), nullable(String.class));

        manager.startTask(task("0123456789abcdef0123456789abcdef", PROFILE), taskDefinition(null), null, "eu-west-1");

        assertEquals(200, status[0]);
    }

    @Test
    void aTaskOnAReusedAddressKeepsItsInstanceRoleWhenTheEarlierTaskThereIsReleased() throws Exception {
        // Docker hands a stopped container's address to the next container. Releasing the earlier
        // task (its STOPPED reconcile can come seconds later) must not unregister the task that now
        // holds the address.
        EcsTaskHandle earlier = manager.startTask(task("0123456789abcdef0123456789abcde1", PROFILE),
                taskDefinition(null), null, "eu-west-1");
        manager.startTask(task("0123456789abcdef0123456789abcde2", PROFILE), taskDefinition(null), null, "eu-west-1");

        manager.stopTask(earlier);

        HttpResponse<String> roles = get("/latest/meta-data/iam/security-credentials/", null);
        assertEquals(200, roles.statusCode(), roles.body());
        assertEquals(ROLE, roles.body());
    }

    @Test
    void aPendingContainerDockerCannotInspectServesNoOne() throws Exception {
        // inspectContainerIp falls back to 127.0.0.1; a pending container must not match a host-local caller.
        int[] status = new int[1];
        doAnswer(invocation -> {
            invocation.<Consumer<String>>getArgument(2).accept("docker-id");
            when(dockerClient.inspectContainerCmd(anyString()).exec()).thenThrow(new RuntimeException("daemon busy"));
            status[0] = get("/latest/meta-data/iam/security-credentials/", null).statusCode();
            return new ContainerInfo("docker-id", Map.of());
        }).when(lifecycleManager).createAndStartEphemeral(any(), nullable(String.class), any());

        manager.startTask(task("0123456789abcdef0123456789abcdef", PROFILE), taskDefinition(null), null, "eu-west-1");

        assertEquals(404, status[0]);
    }

    @Test
    void aContainerThatFailsToStartLeavesNoPendingRegistration() {
        doAnswer(invocation -> {
            invocation.<Consumer<String>>getArgument(2).accept("docker-id");
            throw new RuntimeException("start failed");
        }).when(lifecycleManager).createAndStartEphemeral(any(), nullable(String.class), any());

        assertThrows(RuntimeException.class, () -> manager.startTask(
                task("0123456789abcdef0123456789abcdef", PROFILE), taskDefinition(null), null, "eu-west-1"));

        assertEquals(0, imds.pendingContainerCount());
    }

    @Test
    void taskWithATaskRoleKeepsTodaysCredentials() {
        manager.startTask(task("0123456789abcdef0123456789abcdef", PROFILE),
                taskDefinition("arn:aws:iam::" + ACCOUNT + ":role/TaskRole"), null, "eu-west-1");

        assertTrue(env().contains("AWS_ACCESS_KEY_ID=test"), env().toString());
    }

    @Test
    void taskOnAnInstanceWithoutAProfileKeepsTodaysCredentials() throws Exception {
        manager.startTask(task("0123456789abcdef0123456789abcdef", null), taskDefinition(null), null, "eu-west-1");

        assertTrue(env().contains("AWS_ACCESS_KEY_ID=test"), env().toString());
        assertEquals(404, get("/latest/meta-data/iam/security-credentials/", null).statusCode());
    }

    private List<String> env() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> envCaptor = ArgumentCaptor.forClass(List.class);
        verify(builder, atLeastOnce()).withEnv(envCaptor.capture());
        return envCaptor.getAllValues().getFirst();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(imdsEndpoint + path)).GET();
        if (token != null) {
            request.header("X-aws-ec2-metadata-token", token);
        }
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static EcsTask task(String id, String profileArn) {
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:eu-west-1:" + ACCOUNT + ":task/app-cluster/" + id);
        task.setEc2InstanceId(INSTANCE_ID);
        task.setAvailabilityZone("eu-west-1a");
        task.setIamInstanceProfileArn(profileArn);
        return task;
    }

    private static TaskDefinition taskDefinition(String taskRoleArn) {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app-api:local");
        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("app-api");
        taskDef.setTaskRoleArn(taskRoleArn);
        taskDef.setContainerDefinitions(List.of(app));
        return taskDef;
    }
}
