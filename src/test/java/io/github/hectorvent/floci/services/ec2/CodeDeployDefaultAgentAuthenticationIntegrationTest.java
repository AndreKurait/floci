package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployAgentCommands;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Uses real IMDS issuance and the SDK signer, with global signature validation disabled. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CodeDeployDefaultAgentAuthenticationIntegrationTest {
    private static final String ACCOUNT = "246813579012";
    private static final String DEFAULT_SERVICE = "codedeploy-commands";
    private static final String DEFAULT_TARGET = "CodeDeployCommandService_v20141006.";

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    IamService iam;
    @Inject
    CodeDeployAgentCommands commands;
    @Inject
    ObjectMapper mapper;
    @Inject
    Clock iamClock;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1", "cn-north-1"})
    void defaultInstanceCanCompleteItsCommandWithoutAgentIamPermissions(String region) throws Exception {
        Agent agent = agent(region);
        Work work = work(region, agent.host());
        HttpResponse<String> poll = call(agent, "PollHostCommand", Map.of("HostIdentifier", agent.host()));
        assertStatus(200, poll);
        assertEquals(work.identifier(), mapper.readTree(poll.body())
                .path("HostCommand").path("HostCommandIdentifier").asText());
        HttpResponse<String> specification = call(agent, "GetDeploymentSpecification",
                Map.of("HostIdentifier", agent.host(), "DeploymentExecutionId", work.deployment()));
        assertStatus(200, specification);
        assertEquals("PKCS7/JSON", mapper.readTree(specification.body())
                .path("DeploymentSpecification").path("GenericEnvelope").path("Format").asText());
        assertStatus(200, call(agent, "PutHostCommandAcknowledgement",
                Map.of("HostCommandIdentifier", work.identifier())));
        assertStatus(200, call(agent, "PostHostCommandUpdate",
                Map.of("HostCommandIdentifier", work.identifier())));
        assertStatus(200, call(agent, "PutHostCommandComplete",
                Map.of("HostCommandIdentifier", work.identifier(), "CommandStatus", "Succeeded")));
        assertEquals("Succeeded", work.ticket().result().toCompletableFuture().join().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PollHostCommand", "GetDeploymentSpecification",
            "PutHostCommandAcknowledgement", "PostHostCommandUpdate", "PutHostCommandComplete"})
    void aBroadPolicyCannotGiveOneInstanceAnotherHostsCommands(String action) throws Exception {
        Agent agent = agent("us-east-1");
        allow(agent, DEFAULT_SERVICE + ":*");
        String other = agent.host() + "-other";
        Work work = work(agent.region(), other);
        Map<String, String> request = switch (action) {
            case "PollHostCommand" -> Map.of("HostIdentifier", other);
            case "GetDeploymentSpecification" -> Map.of(
                    "HostIdentifier", other, "DeploymentExecutionId", work.deployment());
            case "PutHostCommandComplete" -> Map.of(
                    "HostCommandIdentifier", work.identifier(), "CommandStatus", "Succeeded");
            default -> Map.of("HostCommandIdentifier", work.identifier());
        };
        if ("PutHostCommandComplete".equals(action)) {
            commands.acknowledge(ACCOUNT, agent.region(), work.identifier());
        }
        assertStatus(403, call(agent, action, request));
        assertFalse(work.ticket().result().toCompletableFuture().isDone());
    }

    @Test
    void theSameInstanceIdInAnotherRegionDoesNotOwnItsCommands() throws Exception {
        Agent agent = agent("us-east-1");
        allow(agent, DEFAULT_SERVICE + ":*");
        String other = agent.host().replace(":us-east-1:", ":us-west-2:");
        work("us-west-2", other);
        assertStatus(403, signed(agent.credentials(), "us-west-2", DEFAULT_SERVICE,
                DEFAULT_TARGET + "PollHostCommand", Map.of("HostIdentifier", other), Clock.systemUTC()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-secret", "missing-token", "wrong-token", "expired-signature", "wrong-scope"})
    void defaultAuthenticationChecksTheSignatureAndSessionEvenWithoutGlobalValidation(String fault)
            throws Exception {
        Agent agent = agent("us-east-1");
        allow(agent, DEFAULT_SERVICE + ":*");
        work(agent.region(), agent.host());
        AwsSessionCredentials issued = agent.credentials();
        AwsCredentials credentials = switch (fault) {
            case "wrong-secret" -> AwsSessionCredentials.create(
                    issued.accessKeyId(), "wrong-secret", issued.sessionToken());
            case "missing-token" -> AwsBasicCredentials.create(issued.accessKeyId(), issued.secretAccessKey());
            case "wrong-token" -> AwsSessionCredentials.create(
                    issued.accessKeyId(), issued.secretAccessKey(), "wrong-token");
            default -> issued;
        };
        Clock clock = "expired-signature".equals(fault)
                ? Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC) : Clock.systemUTC();
        String scope = "wrong-scope".equals(fault) ? "sts" : DEFAULT_SERVICE;
        assertStatus(403, signed(credentials, agent.region(), scope, DEFAULT_TARGET + "PollHostCommand",
                Map.of("HostIdentifier", agent.host()), clock));
    }

    @ParameterizedTest
    @ValueSource(strings = {"iam-user", "named-role-session", "instance-identity-role"})
    void onlyTheInstanceProfileCredentialCanUseDefaultCommands(String caller) throws Exception {
        Agent agent = agent("us-east-1");
        allow(agent, DEFAULT_SERVICE + ":*");
        work(agent.region(), agent.host());
        AwsCredentials credentials = switch (caller) {
            case "iam-user" -> {
                String user = "default-agent-" + UUID.randomUUID();
                agent.client().createUser(request -> request.userName(user));
                cleanup.register(() -> agent.client().deleteUser(request -> request.userName(user)));
                agent.client().putUserPolicy(request -> request.userName(user).policyName("agent")
                        .policyDocument(policy(DEFAULT_SERVICE + ":*")));
                cleanup.register(() -> agent.client().deleteUserPolicy(request -> request.userName(user)
                        .policyName("agent")));
                AccessKey key = agent.client().createAccessKey(request -> request.userName(user)).accessKey();
                cleanup.register(() -> agent.client().deleteAccessKey(request -> request.userName(user)
                        .accessKeyId(key.accessKeyId())));
                yield AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey());
            }
            case "named-role-session" -> {
                try (StsClient sts = StsClient.builder().endpointOverride(endpoint)
                        .region(Region.of(agent.region())).credentialsProvider(root()).build()) {
                    Credentials session = sts.assumeRole(request -> request.roleArn(agent.roleArn())
                            .roleSessionName(agent.instance().getInstanceId())).credentials();
                    yield AwsSessionCredentials.create(
                            session.accessKeyId(), session.secretAccessKey(), session.sessionToken());
                }
            }
            default -> credentials(agent.metadata(), "/latest/meta-data/identity-credentials/ec2/"
                    + "security-credentials/ec2-instance");
        };
        assertStatus(403, signed(credentials, agent.region(), DEFAULT_SERVICE,
                DEFAULT_TARGET + "PollHostCommand", Map.of("HostIdentifier", agent.host()), Clock.systemUTC()));
    }

    @Test
    void retiringTheMetadataRegistrationRevokesDefaultAgentAccess() throws Exception {
        Agent agent = agent("us-east-1");
        allow(agent, DEFAULT_SERVICE + ":*");
        work(agent.region(), agent.host());
        agent.metadata().server().unregisterInstance(agent.instance());
        assertStatus(403, call(agent, "PollHostCommand", Map.of("HostIdentifier", agent.host())));
    }

    @Test
    void anExpiredInstanceCredentialCannotReceiveCommands() throws Exception {
        Agent agent = agent("us-east-1");
        work(agent.region(), agent.host());
        String accessKeyId = agent.credentials().accessKeyId();
        iam.ec2InstanceProfileSession(accessKeyId).orElseThrow()
                .setExpiration(iamClock.instant().minusSeconds(1));
        assertTrue(iam.ec2InstanceProfileSession(accessKeyId).isEmpty(),
                "the fixture must expire the session on IAM's injected clock");
        assertStatus(403, call(agent, "PollHostCommand", Map.of("HostIdentifier", agent.host())));
    }

    @Test
    void secureCommandsStillRequireTheirOwnIamPermission() throws Exception {
        Agent agent = agent("us-east-1");
        work(agent.region(), agent.host());
        String service = "codedeploy-commands-secure";
        String target = "CodeDeployCommandService.PollHostCommand";
        Map<String, String> request = Map.of("HostIdentifier", agent.host());
        allow(agent, DEFAULT_SERVICE + ":*");
        assertStatus(400, signed(agent.credentials(), agent.region(), service, target, request, Clock.systemUTC()));
        allow(agent, service + ":*");
        assertStatus(200, signed(agent.credentials(), agent.region(), service, target, request, Clock.systemUTC()));
    }

    @Test
    void defaultAgentIdentityDoesNotGrantDeploymentManagement() throws Exception {
        Agent agent = agent("us-east-1");
        assertStatus(400, signed(agent.credentials(), agent.region(), DEFAULT_SERVICE,
                "CodeDeploy_20141006.ListApplications", Map.of(), Clock.systemUTC()));
    }

    private Agent agent(String region) throws Exception {
        String name = "default-agent-" + UUID.randomUUID();
        IamClient client = IamClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(root()).build();
        cleanup.register(client::close);
        String rootArn = AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam", ACCOUNT, "root").toString();
        String roleArn = client.createRole(request -> request.roleName(name).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"ec2.amazonaws.com","AWS":"%s"},"Action":"sts:AssumeRole"}]}
                """.formatted(rootArn))).role().arn();
        cleanup.register(() -> client.deleteRole(request -> request.roleName(name)));
        String profileArn = client.createInstanceProfile(request -> request.instanceProfileName(name))
                .instanceProfile().arn();
        cleanup.register(() -> client.deleteInstanceProfile(request -> request.instanceProfileName(name)));
        client.addRoleToInstanceProfile(request -> request.instanceProfileName(name).roleName(name));
        cleanup.register(() -> client.removeRoleFromInstanceProfile(
                request -> request.instanceProfileName(name).roleName(name)));
        Instance instance = new Instance();
        instance.setInstanceId("i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17));
        instance.setRegion(region);
        instance.setOwnerId(ACCOUNT);
        instance.setIamInstanceProfileArn(profileArn);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.defaultRegion()).thenReturn(region);
        Ec2MetadataTestFixture metadata = new Ec2MetadataTestFixture();
        cleanup.register(() -> {
            try {
                metadata.close();
            } catch (Exception failure) {
                throw new IllegalStateException("Could not retire the test metadata server", failure);
            }
        });
        metadata.start(iam, config, instance);
        AwsSessionCredentials credentials = credentials(metadata, "/latest/meta-data/iam/security-credentials/" + name);
        String host = AwsArnUtils.Arn.of("ec2", region, ACCOUNT, "instance/" + instance.getInstanceId()).toString();
        return new Agent(client, name, roleArn, region, instance, metadata, credentials, host);
    }

    private AwsSessionCredentials credentials(Ec2MetadataTestFixture metadata, String path) throws Exception {
        HttpResponse<String> token = metadata.putToken("60");
        assertEquals(200, token.statusCode());
        HttpResponse<String> response = metadata.get(path, token.body());
        assertEquals(200, response.statusCode());
        JsonNode document = mapper.readTree(response.body());
        return AwsSessionCredentials.create(document.path("AccessKeyId").asText(),
                document.path("SecretAccessKey").asText(), document.path("Token").asText());
    }

    private void allow(Agent agent, String action) {
        String name = "agent-" + UUID.randomUUID();
        agent.client().putRolePolicy(request -> request.roleName(agent.role()).policyName(name)
                .policyDocument(policy(action)));
        cleanup.register(() -> agent.client().deleteRolePolicy(
                request -> request.roleName(agent.role()).policyName(name)));
    }

    private static String policy(String action) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"%s","Resource":"*"}]}
                """.formatted(action);
    }

    private static StaticCredentialsProvider root() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test"));
    }

    private Work work(String region, String host) {
        String deployment = "d-" + UUID.randomUUID();
        CodeDeployAgentCommands.Session session = commands.open(ACCOUNT, region, deployment, deployment,
                List.of(host), Map.of("DeploymentId", deployment));
        cleanup.register(() -> commands.close(session));
        CodeDeployAgentCommands.Ticket ticket = commands.enqueue(session, host, "DownloadBundle");
        Map<?, ?> command = (Map<?, ?>) commands.poll(ACCOUNT, region, host).get("HostCommand");
        return new Work(deployment, (String) command.get("HostCommandIdentifier"), ticket);
    }

    private HttpResponse<String> call(Agent agent, String action, Map<String, String> request) throws Exception {
        return signed(agent.credentials(), agent.region(), DEFAULT_SERVICE, DEFAULT_TARGET + action,
                request, Clock.systemUTC());
    }

    private HttpResponse<String> signed(AwsCredentials credentials, String region, String service,
                                        String target, Map<String, ?> input, Clock clock) throws Exception {
        byte[] body = mapper.writeValueAsBytes(input);
        SdkHttpFullRequest request = SdkHttpFullRequest.builder().method(SdkHttpMethod.POST).uri(endpoint)
                .putHeader("Content-Type", "application/x-amz-json-1.1").putHeader("X-Amz-Target", target)
                .contentStreamProvider(() -> new ByteArrayInputStream(body)).build();
        SdkHttpFullRequest signed = Aws4Signer.create().sign(request, Aws4SignerParams.builder()
                .awsCredentials(credentials).signingName(service).signingRegion(Region.of(region))
                .signingClockOverride(clock).build());
        HttpRequest.Builder http = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        signed.headers().forEach((name, values) -> {
            if (!"host".equalsIgnoreCase(name) && !"content-length".equalsIgnoreCase(name)) {
                values.forEach(value -> http.header(name, value));
            }
        });
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(http.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void assertStatus(int expected, HttpResponse<String> response) {
        assertEquals(expected, response.statusCode(), response.body());
        if (expected >= 400) {
            assertTrue(response.body().contains("Exception"), response.body());
        }
    }

    private record Agent(IamClient client, String role, String roleArn, String region, Instance instance,
                         Ec2MetadataTestFixture metadata, AwsSessionCredentials credentials, String host) {}

    private record Work(String deployment, String identifier, CodeDeployAgentCommands.Ticket ticket) {}
}
