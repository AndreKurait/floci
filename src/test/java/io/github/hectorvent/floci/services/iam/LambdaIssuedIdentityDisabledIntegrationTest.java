package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class LambdaIssuedIdentityDisabledIntegrationTest {
    @Test
    void facilityIsDisabledByDefault() {
        given().contentType("application/json").body("{}").post("/_floci/lambda/issued-identities/verify")
                .then().statusCode(404).body("code", equalTo("LambdaIdentityVerificationDisabled"));
    }
}
