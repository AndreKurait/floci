package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
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
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.HttpTokensState;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/** Connects the real metadata issuer and SDK provider to cross-account execute-api authorization. */
@QuarkusTest
@TestProfile(Ec2ServicePrincipalIntegrationTest.Enabled.class)
class Ec2ServicePrincipalIntegrationTest {
    private static final String ACCOUNT = "246813579012";
    private static final Region REGION = Region.US_EAST_1;

    @Inject
    IamService iamService;
    @Inject
    Ec2Service ec2Service;
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    public static class Enabled extends SignedIamEnforcementProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.services.iam.service-principal-memberships-path",
                    Path.of("src/test/resources/iam/service-principal-memberships.json").toAbsolutePath().toString());
            overrides.put("floci.services.iot.enabled", "false");
            return overrides;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"allow", "no-identity-allow", "no-resource-allow",
            "identity-deny", "resource-deny", "non-member", "wrong-service"})
    void imdsRoleSessionNeedsBothCrossAccountGrants(String scenario) throws Exception {
        String account = scenario.equals("non-member") ? "135792468013" : ACCOUNT;
        String name = "imds-api-" + UUID.randomUUID().toString().substring(0, 8);
        StaticCredentialsProvider administrator = bootstrapAdministrator(name, account);
        IamClient iam = IamClient.builder().endpointOverride(endpoint).region(REGION)
                .credentialsProvider(administrator).build();
        cleanup.register(iam::close);
        Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint).region(REGION)
                .credentialsProvider(administrator).build();
        cleanup.register(ec2::close);
        ApiGatewayClient gateway = ApiGatewayClient.builder().endpointOverride(endpoint).region(REGION)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        cleanup.register(gateway::close);

        Role role = iam.createRole(request -> request.roleName(name).path("/workloads/")
                .assumeRolePolicyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                        """)).role();
        cleanup.register(() -> iam.deleteRole(request -> request.roleName(name)));
        String profile = iam.createInstanceProfile(request -> request.instanceProfileName(name))
                .instanceProfile().arn();
        cleanup.register(() -> iam.deleteInstanceProfile(request -> request.instanceProfileName(name)));
        iam.addRoleToInstanceProfile(request -> request.instanceProfileName(name).roleName(name));
        cleanup.register(() -> iam.removeRoleFromInstanceProfile(
                request -> request.instanceProfileName(name).roleName(name)));

        String api = gateway.createRestApi(request -> request.name(name)).id();
        cleanup.register(() -> gateway.deleteRestApi(request -> request.restApiId(api)));
        String resource = gateway.getResources(request -> request.restApiId(api)).items().getFirst().id();
        gateway.putMethod(request -> request.restApiId(api).resourceId(resource).httpMethod("GET")
                .authorizationType("AWS_IAM"));
        gateway.putMethodResponse(request -> request.restApiId(api).resourceId(resource)
                .httpMethod("GET").statusCode("200"));
        gateway.putIntegration(request -> request.restApiId(api).resourceId(resource).httpMethod("GET")
                .type(IntegrationType.MOCK).requestTemplates(Map.of("application/json", "{\"statusCode\":200}")));
        gateway.putIntegrationResponse(request -> request.restApiId(api).resourceId(resource)
                .httpMethod("GET").statusCode("200")
                .responseTemplates(Map.of("application/json", "{\"reached\":\"integration\"}")));
        String invokeArn = "arn:aws:execute-api:us-east-1:000000000000:" + api + "/test/GET/";
        if (!scenario.equals("no-resource-allow")) {
            String statement = """
                    {"Effect":"Allow","Principal":{"Service":"%s"},"Action":"execute-api:Invoke","Resource":"%s"}
                    """.formatted(scenario.equals("wrong-service") ? "other.aws.internal" : "example.aws.internal", invokeArn).strip();
            String deny = scenario.equals("resource-deny")
                    ? "," + statement.replace("\"Allow\"", "\"Deny\"") : "";
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[" + statement + deny + "]}";
            gateway.updateRestApi(request -> request.restApiId(api).patchOperations(
                    operation -> operation.op("replace").path("/policy").value(policy)));
        }
        gateway.createDeployment(request -> request.restApiId(api).stageName("test"));
        if (!scenario.equals("no-identity-allow")) {
            String statement = """
                    {"Effect":"Allow","Action":"execute-api:Invoke","Resource":"%s"}
                    """.formatted(invokeArn).strip();
            String deny = scenario.equals("identity-deny")
                    ? "," + statement.replace("\"Allow\"", "\"Deny\"") : "";
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[" + statement + deny + "]}";
            iam.putRolePolicy(request -> request.roleName(name).policyName("invoke").policyDocument(policy));
            cleanup.register(() -> iam.deleteRolePolicy(request -> request.roleName(name).policyName("invoke")));
        }

        String instanceId = ec2.runInstances(request -> request.imageId("ami-0abcdef1234567890")
                .instanceType("t3.micro").minCount(1).maxCount(1)
                .iamInstanceProfile(value -> value.arn(profile))
                .metadataOptions(value -> value.httpTokens(HttpTokensState.REQUIRED)))
                .instances().getFirst().instanceId();
        cleanup.register(() -> ec2.terminateInstances(request -> request.instanceIds(instanceId)));
        Instance instance = ec2Service.findInstanceForAccount(account, REGION.id(), instanceId).orElseThrow();
        try (Ec2MetadataTestFixture metadata = new Ec2MetadataTestFixture()) {
            metadata.start(iamService, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), instance);
            assertEquals(401, metadata.get("/latest/meta-data/iam/security-credentials/", null).statusCode());
            try (InstanceProfileCredentialsProvider provider = InstanceProfileCredentialsProvider.builder()
                    .endpoint(metadata.endpoint()).build();
                 StsClient sts = StsClient.builder().endpointOverride(endpoint).region(REGION)
                         .credentialsProvider(provider).build();
                 HttpClient http = HttpClient.newHttpClient()) {
                AwsSessionCredentials credentials = assertInstanceOf(
                        AwsSessionCredentials.class, provider.resolveCredentials());
                assertTrue(sts.getCallerIdentity().arn().startsWith(
                        "arn:aws:sts::" + account + ":assumed-role/" + name + "/"));
                URI uri = endpoint.resolve("execute-api/" + api + "/test/");
                HttpResponse<String> response = invoke(http, uri, credentials);
                assertEquals(scenario.equals("allow") ? 200 : 403, response.statusCode(), response.body());
                if (scenario.equals("allow")) {
                    assertTrue(response.body().contains("integration"), response.body());
                    assertEquals(403, invoke(http, uri, AwsSessionCredentials.create(credentials.accessKeyId(),
                            "wrong-secret", credentials.sessionToken())).statusCode());
                    assertEquals(403, invoke(http, uri, AwsSessionCredentials.create(credentials.accessKeyId(),
                            credentials.secretAccessKey(), "wrong-token")).statusCode());
                    assertEquals(403, invoke(http, uri, AwsBasicCredentials.create(credentials.accessKeyId(),
                            credentials.secretAccessKey())).statusCode());
                    metadata.server().unregisterInstance(instance);
                    assertEquals(403, invoke(http, uri, credentials).statusCode(),
                            "A revoked metadata session must no longer authenticate");
                }
            }
        }
    }

    @Test
    void serviceAccountCanAssumeCustomerRoleWithoutTransferringMembership() {
        String name = "service-trust-" + UUID.randomUUID().toString().substring(0, 8);
        StaticCredentialsProvider administrator = bootstrapAdministrator(name, ACCOUNT);
        String trust = """
                {"Version":"2012-10-17","Statement":{"Effect":"Allow","Action":"sts:AssumeRole",
                "Principal":{"Service":"example.amazonaws.com"}}}
                """;
        String assumeAny = """
                {"Statement":{"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*"}}
                """;
        try (IamClient customer = IamClient.builder().endpointOverride(endpoint).region(REGION)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .build();
             IamClient member = IamClient.builder().endpointOverride(endpoint).region(REGION)
                     .credentialsProvider(administrator).build();
             StsClient caller = StsClient.builder().endpointOverride(endpoint).region(REGION)
                     .credentialsProvider(administrator).build()) {
            Role target = customer.createRole(request -> request.roleName(name).assumeRolePolicyDocument(trust)).role();
            try {
                assertEquals(403, assertThrows(StsException.class, () -> caller.assumeRole(request ->
                        request.roleArn(target.arn()).roleSessionName("before-identity-grant"))).statusCode());
                member.putUserPolicy(request -> request.userName(name).policyName("assume")
                        .policyDocument(assumeAny.replace("\"*\"", "\"" + target.arn() + "\"")));
                cleanup.register(() -> RequestScopes.runAs(ACCOUNT, REGION.id(),
                        () -> iamService.deleteUserPolicy(name, "assume")));
                Credentials session = caller.assumeRole(request ->
                        request.roleArn(target.arn()).roleSessionName("customer")).credentials();
                customer.putRolePolicy(request -> request.roleName(name).policyName("assume").policyDocument(assumeAny));
                try (StsClient delegated = StsClient.builder().endpointOverride(endpoint).region(REGION)
                        .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                                session.accessKeyId(), session.secretAccessKey(), session.sessionToken()))).build()) {
                    assertEquals("000000000000", delegated.getCallerIdentity().account());
                    Role memberRole = member.createRole(request -> request.roleName(name)
                            .assumeRolePolicyDocument(trust)).role();
                    try {
                        assertEquals(403, assertThrows(StsException.class, () -> delegated.assumeRole(request ->
                                request.roleArn(memberRole.arn()).roleSessionName("no-membership"))).statusCode());
                    } finally {
                        member.deleteRole(request -> request.roleName(name));
                    }
                } finally {
                    customer.deleteRolePolicy(request -> request.roleName(name).policyName("assume"));
                }
                customer.updateAssumeRolePolicy(request -> request.roleName(name)
                        .policyDocument(trust.replace("\"Allow\"", "\"Deny\"")));
                assertEquals(403, assertThrows(StsException.class, () -> caller.assumeRole(request ->
                        request.roleArn(target.arn()).roleSessionName("explicit-deny"))).statusCode());
            } finally {
                customer.deleteRole(request -> request.roleName(name));
            }
        }
    }

    private StaticCredentialsProvider bootstrapAdministrator(String name, String account) {
        AccessKey key = RequestScopes.callAs(account, REGION.id(), () -> {
            iamService.createUser(name, "/");
            iamService.putUserPolicy(name, "setup",
                    "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":[\"iam:*\",\"ec2:*\"],\"Resource\":\"*\"}}");
            return iamService.createAccessKey(name);
        });
        cleanup.register(() -> RequestScopes.runAs(account, REGION.id(), () -> {
            iamService.deleteAccessKey(name, key.getAccessKeyId());
            iamService.deleteUserPolicy(name, "setup");
            iamService.deleteUser(name);
        }));
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                key.getAccessKeyId(), key.getSecretAccessKey()));
    }

    private HttpResponse<String> invoke(HttpClient client, URI uri, AwsCredentials credentials) throws Exception {
        SdkHttpFullRequest signed = sign(uri, credentials);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
        signed.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host")) {
                values.forEach(value -> request.header(name, value));
            }
        });
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static SdkHttpFullRequest sign(URI uri, AwsCredentials credentials) {
        return Aws4Signer.create().sign(SdkHttpFullRequest.builder()
                        .uri(uri).method(SdkHttpMethod.GET).build(),
                Aws4SignerParams.builder().awsCredentials(credentials)
                        .signingName("execute-api").signingRegion(REGION).build());
    }

}
