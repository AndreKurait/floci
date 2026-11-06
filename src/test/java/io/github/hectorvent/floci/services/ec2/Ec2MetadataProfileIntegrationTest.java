package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

@QuarkusTest
class Ec2MetadataProfileIntegrationTest {
    private static final String ACCOUNT = "246813579012";
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
            """;

    @Inject
    IamService iam;

    @ParameterizedTest
    @ValueSource(strings = {"latest", "2012-01-12", "2016-09-02"})
    void iamInfoMatchesCreatedProfileAndRejectsMissingOrMismatchedAttachments(String build) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String roleName = "metadata-role-" + suffix;
        String profileName = "metadata-profile-" + suffix;
        iamRequest("CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
                .post("/").then().statusCode(200);
        Response created = iamRequest("CreateInstanceProfile")
                .formParam("InstanceProfileName", profileName).formParam("Path", "/nodes/")
                .post("/").then().statusCode(200).extract().response();
        String profileArn = created.xmlPath().getString(
                "CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.Arn");
        String profileId = created.xmlPath().getString(
                "CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.InstanceProfileId");
        Instance instance = Ec2MetadataTestFixture.instance("i-0123456789abcdef0");
        instance.setIamInstanceProfileArn(profileArn);
        boolean roleAdded = false;
        boolean profileDeleted = false;
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.start(iam, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), instance);
            String token = token(fixture);
            assertEquals(404, info(fixture, token, build).statusCode());
            String roleDirectory = "/" + build + "/meta-data/iam/security-credentials";
            assertEquals(404, fixture.get(roleDirectory, token).statusCode());
            iamRequest("AddRoleToInstanceProfile")
                    .formParam("InstanceProfileName", profileName).formParam("RoleName", roleName)
                    .post("/").then().statusCode(200);
            roleAdded = true;
            HttpResponse<String> response = info(fixture, token, build);
            assertEquals(200, response.statusCode());
            JsonObject body = new JsonObject(response.body());
            assertEquals("Success", body.getString("Code"));
            assertEquals(profileArn, body.getString("InstanceProfileArn"));
            assertEquals(profileId, body.getString("InstanceProfileId"));
            String rolePath = "/" + build + "/meta-data/iam/security-credentials/";
            assertEquals(roleName, fixture.get(rolePath, token).body());
            HttpResponse<String> withoutTrailingSlash = fixture.get(roleDirectory, token);
            assertEquals(200, withoutTrailingSlash.statusCode(),
                    "The native patch module requests the role directory without a trailing slash");
            assertEquals(roleName, withoutTrailingSlash.body());
            HttpResponse<String> credentials = fixture.get(rolePath + roleName, token);
            assertEquals(200, credentials.statusCode());
            JsonObject session = new JsonObject(credentials.body());
            assertFalse(session.getString("AccessKeyId", "").isBlank());
            assertFalse(session.getString("Token", "").isBlank());
            JsonObject latest = new JsonObject(fixture.get(
                    "/latest/meta-data/iam/security-credentials/" + roleName, token).body());
            assertEquals(latest.getString("AccessKeyId"), session.getString("AccessKeyId"));
            assertEquals(latest.getString("Token"), session.getString("Token"));
            instance.setIamInstanceProfileArn(profileArn.replace("/nodes/", "/other/"));
            assertEquals(404, info(fixture, token, build).statusCode());
            instance.setIamInstanceProfileArn(profileArn);
            iamRequest("RemoveRoleFromInstanceProfile")
                    .formParam("InstanceProfileName", profileName).formParam("RoleName", roleName)
                    .post("/").then().statusCode(200);
            roleAdded = false;
            assertEquals(404, info(fixture, token, build).statusCode());
            assertEquals(404, fixture.get(roleDirectory, token).statusCode());
            iamRequest("DeleteInstanceProfile").formParam("InstanceProfileName", profileName)
                    .post("/").then().statusCode(200);
            profileDeleted = true;
            assertEquals(404, info(fixture, token, build).statusCode());
        } finally {
            if (roleAdded) {
                iamRequest("RemoveRoleFromInstanceProfile")
                        .formParam("InstanceProfileName", profileName).formParam("RoleName", roleName)
                        .post("/").then().statusCode(200);
            }
            if (!profileDeleted) {
                iamRequest("DeleteInstanceProfile")
                        .formParam("InstanceProfileName", profileName).post("/").then().statusCode(200);
            }
            iamRequest("DeleteRole").formParam("RoleName", roleName).post("/").then().statusCode(200);
        }
    }

    @Test
    void clusterNodeReportsItsSynthesizedProfileUntilIamHoldsOne() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String roleName = "node-role-" + suffix;
        String profileName = "cluster-" + suffix + "-node-profile";
        String profileArn = "arn:aws:iam::" + ACCOUNT + ":instance-profile/" + profileName;
        Instance node = Ec2MetadataTestFixture.instance("i-0fedcba9876543210");
        node.setIamInstanceProfileArn(profileArn);
        node.setIamInstanceProfileId("AIPA0FEDCBA9876543210");
        boolean roleCreated = false;
        boolean profileCreated = false;
        boolean roleAttached = false;
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.start(iam, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), node);
            assertEquals(404, info(fixture, token(fixture)).statusCode());

            fixture.server().unregisterInstance(node);
            fixture.server().reconcileClusterNodeAddresses(Set.of(Ec2MetadataTestFixture.CALLER_IP), node);
            HttpResponse<String> synthesized = info(fixture, token(fixture));
            assertEquals(200, synthesized.statusCode());
            JsonObject body = new JsonObject(synthesized.body());
            assertEquals(profileArn, body.getString("InstanceProfileArn"));
            assertEquals("AIPA0FEDCBA9876543210", body.getString("InstanceProfileId"));

            iamRequest("CreateRole").formParam("RoleName", roleName)
                    .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
                    .post("/").then().statusCode(200);
            roleCreated = true;
            String iamProfileId = iamRequest("CreateInstanceProfile")
                    .formParam("InstanceProfileName", profileName).post("/").then().statusCode(200)
                    .extract().path("CreateInstanceProfileResponse.CreateInstanceProfileResult"
                            + ".InstanceProfile.InstanceProfileId");
            profileCreated = true;
            // Once IAM holds the profile its answer wins: no role yet is the empty-profile 404.
            assertEquals(404, info(fixture, token(fixture)).statusCode());
            iamRequest("AddRoleToInstanceProfile")
                    .formParam("InstanceProfileName", profileName).formParam("RoleName", roleName)
                    .post("/").then().statusCode(200);
            roleAttached = true;
            HttpResponse<String> backed = info(fixture, token(fixture));
            assertEquals(200, backed.statusCode());
            assertEquals(iamProfileId, new JsonObject(backed.body()).getString("InstanceProfileId"));
        } finally {
            if (roleAttached) {
                iamRequest("RemoveRoleFromInstanceProfile")
                        .formParam("InstanceProfileName", profileName).formParam("RoleName", roleName)
                        .post("/").then().statusCode(200);
            }
            if (profileCreated) {
                iamRequest("DeleteInstanceProfile")
                        .formParam("InstanceProfileName", profileName).post("/").then().statusCode(200);
            }
            if (roleCreated) {
                iamRequest("DeleteRole").formParam("RoleName", roleName).post("/").then().statusCode(200);
            }
        }
    }

    private static String token(Ec2MetadataTestFixture fixture) throws Exception {
        HttpResponse<String> token = fixture.putToken("60");
        assertEquals(200, token.statusCode());
        return token.body();
    }

    private static HttpResponse<String> info(Ec2MetadataTestFixture fixture, String token) throws Exception {
        return info(fixture, token, "latest");
    }

    private static HttpResponse<String> info(Ec2MetadataTestFixture fixture, String token, String build) throws Exception {
        return fixture.get("/" + build + "/meta-data/iam/info", token);
    }

    private static RequestSpecification iamRequest(String action) {
        return given().header("Authorization", EnforcementFixtures.auth(ACCOUNT, "iam"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action);
    }
}
