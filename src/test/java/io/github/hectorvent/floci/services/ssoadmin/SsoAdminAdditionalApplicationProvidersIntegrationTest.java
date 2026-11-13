package io.github.hectorvent.floci.services.ssoadmin;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/**
 * {@code floci.services.ssoadmin.additional-application-providers} lets CreateApplication admit the listed
 * AWS-managed providers over the JSON protocol, and only those.
 */
@QuarkusTest
@TestProfile(SsoAdminAdditionalApplicationProvidersIntegrationTest.Profile.class)
class SsoAdminAdditionalApplicationProvidersIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sso/aws4_request";
    private static final String INSTANCE_ARN = "arn:aws:sso:::instance/ssoins-7223b02a5d9f7c8e";

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ssoadmin.additional-application-providers", "example-service,example/team");
        }
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createApplicationAdmitsAConfiguredProvider() {
        createApplication("arn:aws:sso::aws:applicationProvider/example-service", "Configured service")
            .statusCode(200)
            .body("ApplicationArn", startsWith("arn:aws:sso::000000000000:application/"));
        createApplication("arn:aws:sso::aws:applicationProvider/example/team", "Configured team")
            .statusCode(200);
    }

    @Test
    void createApplicationStillRefusesAProviderThatIsNotConfigured() {
        createApplication("arn:aws:sso::aws:applicationProvider/other-service", "Unlisted service")
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));
    }

    private ValidatableResponse createApplication(String providerArn, String name) {
        return given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", AUTH_HEADER)
            .header("X-Amz-Target", "SWBExternalService.CreateApplication")
            .body("{\"InstanceArn\":\"" + INSTANCE_ARN + "\",\"ApplicationProviderArn\":\"" + providerArn
                    + "\",\"Name\":\"" + name + "\"}")
        .when()
            .post("/")
        .then();
    }
}
