package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Default configuration: the endpoint is off, and even a correctly signed caller request gets nothing. */
@QuarkusTest
class DerivedSigningKeysDisabledIntegrationTest {

    @Test
    void anEmptyRequestFindsTheEndpointDisabled() {
        given().contentType("application/json").body("{}").post(DerivedSigningKeyController.DERIVE_PATH)
                .then().statusCode(404).header("Cache-Control", "no-store")
                .body("code", equalTo("DerivedSigningKeysDisabled"));
    }

    @Test
    void aSignedCallerRequestFindsTheEndpointDisabled() {
        byte[] body = "{\"correlationId\":\"off\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = DerivedSigningKeyTestSigner.sign("localhost:" + RestAssured.port,
                DerivedSigningKeyController.DERIVE_PATH, body, DerivedSigningKeyCallerAuthenticatorTest.CALLER,
                DerivedSigningKeyCallerAuthenticatorTest.SECRET, null, "us-east-1", "floci", Instant.now());
        DerivedSigningKeyIntegrationTest.post(body, headers).then().statusCode(404)
                .body("code", equalTo("DerivedSigningKeysDisabled"));
    }
}
