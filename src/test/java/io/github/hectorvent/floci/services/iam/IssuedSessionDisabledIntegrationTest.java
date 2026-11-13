package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class IssuedSessionDisabledIntegrationTest {
    @Test
    void bothManagementOperationsAreDisabledByDefault() {
        for (String operation : new String[] {"verify", "lookup"}) {
            given().contentType("application/json").body("{}")
                    .post("/_floci/iam/issued-sessions/" + operation).then().statusCode(404)
                    .body("code", equalTo("IssuedSessionVerificationDisabled"));
        }
    }
}
