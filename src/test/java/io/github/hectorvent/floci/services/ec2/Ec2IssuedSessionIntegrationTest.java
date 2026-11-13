package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(Ec2IssuedSessionIntegrationTest.Enabled.class)
class Ec2IssuedSessionIntegrationTest {
    private static final String BASE = "/_floci/iam/issued-sessions/";
    private static final String ACCOUNT = "246813579012";
    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;

    public static class Enabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.issued-session-verification-enabled", "true");
        }
    }

    @Test
    void verifiesImdsProfileProofAgainstSdkCallerIdentityAndRejectsRevocation() throws Exception {
        String name = "instance-proof-" + UUID.randomUUID().toString().substring(0, 8);
        try (IamClient admin = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCOUNT, "test"))).build()) {
            admin.createRole(r -> r.roleName(name).assumeRolePolicyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                    "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                    """));
            String profile = admin.createInstanceProfile(r -> r.instanceProfileName(name)).instanceProfile().arn();
            admin.addRoleToInstanceProfile(r -> r.instanceProfileName(name).roleName(name));
            try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
                Instance instance = new Instance();
                instance.setInstanceId("i-0123456789abcdef0");
                instance.setRegion("us-east-1");
                instance.setOwnerId(ACCOUNT);
                instance.setIamInstanceProfileArn(profile);
                fixture.start(iam, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), instance);
                String token = fixture.putToken("60").body();
                JsonObject credentials = new JsonObject(fixture.get(
                        "/latest/meta-data/iam/security-credentials/" + name, token).body());
                AwsSessionCredentials identity = AwsSessionCredentials.create(credentials.getString("AccessKeyId"),
                        credentials.getString("SecretAccessKey"), credentials.getString("Token"));
                try (StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(identity)).build()) {
                    GetCallerIdentityResponse caller = sts.getCallerIdentity();
                    Map<String, String> request = proof(identity);
                    String response = given().contentType("application/json").body(request).post(BASE + "verify")
                            .then().statusCode(200).body("kind", equalTo("EC2_INSTANCE_PROFILE"))
                            .body("principalArn", equalTo(caller.arn())).body("principalId", equalTo(caller.userId()))
                            .body("roleSessionName", equalTo(instance.getInstanceId()))
                            .extract().asString();
                    assertFalse(response.contains(identity.secretAccessKey()));
                    assertFalse(response.contains(identity.sessionToken()));
                    Map<String, String> tampered = new HashMap<>(request);
                    tampered.put("signature", "0".repeat(64));
                    given().contentType("application/json").body(tampered).post(BASE + "verify")
                            .then().statusCode(403);
                    fixture.server().unregisterInstance(instance);
                    given().contentType("application/json").body(request).post(BASE + "verify")
                            .then().statusCode(403);
                    given().contentType("application/json").body(Map.of("correlationId", "revoked",
                            "accessKeyId", identity.accessKeyId())).post(BASE + "lookup").then().statusCode(403);
                }
            } finally {
                admin.removeRoleFromInstanceProfile(r -> r.instanceProfileName(name).roleName(name));
                admin.deleteInstanceProfile(r -> r.instanceProfileName(name));
                admin.deleteRole(r -> r.roleName(name));
            }
        }
    }

    private static Map<String, String> proof(AwsSessionCredentials credentials) throws Exception {
        String time = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        String date = time.substring(0, 8);
        String data = "AWS4-HMAC-SHA256\n" + time + "\n" + date
                + "/us-east-1/ExampleService/aws4_request\n" + "a".repeat(64);
        String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(
                SigV4RequestValidator.deriveSigningKey(credentials.secretAccessKey(), date,
                        "us-east-1", "ExampleService"), data));
        return Map.of("correlationId", "ec2-proof", "accessKeyId", credentials.accessKeyId(),
                "sessionToken", credentials.sessionToken(), "stringToSign", data, "signature", signature);
    }
}
