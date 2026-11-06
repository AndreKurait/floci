package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.iam.DerivedSigningKeyIntegrationTestAccess;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** EC2 sessions minted live through IMDS: the instance-profile role is eligible, the identity credential is not. */
@QuarkusTest
@TestProfile(DerivedSigningKeyIntegrationTestAccess.Enabled.class)
class DerivedSigningKeyEc2IntegrationTest {

    @Inject
    IamService iam;

    @Test
    void anInstanceProfileSessionIsEligibleAndTheIdentityCredentialIsNot() throws Exception {
        String account = "246813579012";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String role = "derive-ec2-" + suffix;
        String profile = "derive-profile-" + suffix;
        String auth = "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260916/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=abc";
        given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", role)
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":"
                        + "\"Allow\",\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}")
                .post("/").then().statusCode(200);
        given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", profile)
                .post("/").then().statusCode(200);
        given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "AddRoleToInstanceProfile").formParam("InstanceProfileName", profile)
                .formParam("RoleName", role).post("/").then().statusCode(200);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(account);
        Instance instance = new Instance();
        instance.setInstanceId("i-0a1b2c3d4e5f60718");
        instance.setRegion("us-east-1");
        instance.setOwnerId(account);
        instance.setIamInstanceProfileArn("arn:aws:iam::" + account + ":instance-profile/" + profile);
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.start(iam, config, instance);
            String token = fixture.putToken("60").body();
            HttpResponse<String> profileCredential = fixture.get(
                    "/latest/meta-data/iam/security-credentials/" + role, token);
            assertEquals(200, profileCredential.statusCode());
            JsonObject credential = new JsonObject(profileCredential.body());
            Response response = DerivedSigningKeyIntegrationTestAccess.derive(credential.getString("AccessKeyId"),
                    credential.getString("Token"));
            response.then().statusCode(200);
            assertEquals("EC2_INSTANCE_PROFILE", response.path("credentialKind"));
            assertArrayEquals(SigV4RequestValidator.deriveSigningKey(credential.getString("SecretAccessKey"),
                            DerivedSigningKeyIntegrationTestAccess.today(), "us-east-1",
                            DerivedSigningKeyIntegrationTestAccess.SERVICE),
                    Base64.getDecoder().decode(response.path("derivedKey").toString()));

            HttpResponse<String> identityCredential = fixture.get(
                    "/latest/meta-data/identity-credentials/ec2/security-credentials/ec2-instance", token);
            assertEquals(200, identityCredential.statusCode());
            JsonObject identity = new JsonObject(identityCredential.body());
            DerivedSigningKeyIntegrationTestAccess.derive(identity.getString("AccessKeyId"), identity.getString("Token"))
                    .then().statusCode(403);
            fixture.server().unregisterInstance(instance);
        } finally {
            given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "RemoveRoleFromInstanceProfile").formParam("InstanceProfileName", profile)
                    .formParam("RoleName", role).post("/");
            given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteInstanceProfile").formParam("InstanceProfileName", profile).post("/");
            given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteRole").formParam("RoleName", role).post("/");
        }
    }
}
