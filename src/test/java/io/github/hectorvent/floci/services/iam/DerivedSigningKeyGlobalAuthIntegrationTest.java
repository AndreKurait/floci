package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** With global signature validation and IAM enforcement on, the endpoint behaves the same. */
@QuarkusTest
@TestProfile(DerivedSigningKeyGlobalAuthIntegrationTest.Strict.class)
class DerivedSigningKeyGlobalAuthIntegrationTest {

    public static class Strict implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(DerivedSigningKeyIntegrationTest.enabledOverrides());
            overrides.put("floci.auth.validate-signatures", "true");
            overrides.put("floci.services.iam.enforcement-enabled", "true");
            return overrides;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A Query call really signed with the well-known local pair, as validation now requires. */
    private static ValidatableResponse signedQuery(String service, Map<String, String> params) {
        String form = params.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        byte[] body = form.getBytes(StandardCharsets.UTF_8);
        String type = "application/x-www-form-urlencoded";
        Map<String, String> headers = DerivedSigningKeyTestSigner.sign("localhost:" + RestAssured.port, "/", body, type,
                "test", "test", null, "us-east-1", service, Instant.now());
        RequestSpecification spec = given().config(RestAssuredConfig.config().encoderConfig(
                EncoderConfig.encoderConfig().appendDefaultContentCharsetToContentTypeIfUndefined(false)))
                .contentType(type).body(form);
        headers.forEach((name, value) -> {
            if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Content-Type")) {
                spec.header(name, value);
            }
        });
        return spec.post("/").then();
    }

    @Test
    void theCallerStillGetsAKeyAndOthersAreStillRefused() throws Exception {
        String role = "strict-" + UUID.randomUUID();
        String arn = signedQuery("iam", Map.of("Action", "CreateRole", "RoleName", role,
                        "AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":"
                                + "\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"},"
                                + "\"Action\":\"sts:AssumeRole\"}]}"))
                .statusCode(200).extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");
        ValidatableResponse assumed = signedQuery("sts", Map.of("Action", "AssumeRole",
                "RoleArn", arn, "RoleSessionName", "strict-session")).statusCode(200);
        String prefix = "AssumeRoleResponse.AssumeRoleResult.Credentials.";
        String key = assumed.extract().path(prefix + "AccessKeyId");
        String token = assumed.extract().path(prefix + "SessionToken");
        try {
            DerivedSigningKeyIntegrationTest.derive(DerivedSigningKeyIntegrationTest.request(key, token, "signing"))
                    .then().statusCode(200).body("credentialKind", equalTo("ASSUMED_ROLE"));
            byte[] body = JSON.writeValueAsBytes(DerivedSigningKeyIntegrationTest.request(key, token, "signing"));
            DerivedSigningKeyIntegrationTest.post(body, DerivedSigningKeyIntegrationTest.signAs("test", "test", "floci",
                    body)).then().statusCode(403).body("code", equalTo("DerivedSigningKeyCallerRefused"));
            DerivedSigningKeyIntegrationTest.post(body, Map.of()).then().statusCode(403)
                    .body("derivedKey", nullValue());
            // Ordinary AWS calls are unchanged: a correctly signed one still works.
            signedQuery("sts", Map.of("Action", "GetCallerIdentity")).statusCode(200);
        } finally {
            signedQuery("iam", Map.of("Action", "DeleteRole", "RoleName", role));
        }
    }
}
