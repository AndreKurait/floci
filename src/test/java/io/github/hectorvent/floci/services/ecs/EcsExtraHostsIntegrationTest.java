package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * A container definition's {@code extraHosts}: each HostEntry needs both members, is kept as
 * declared, and cannot smuggle a second hosts-file line in through its hostname.
 */
@QuarkusTest
class EcsExtraHostsIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";
    private static final String DEFINITION = "{\"family\":\"extra-hosts-%s\",\"containerDefinitions\":"
            + "[{\"name\":\"app\",\"image\":\"nginx:latest\",\"memory\":128,\"extraHosts\":%s}]}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response register(String family, String extraHosts, int expectedStatus) {
        return given().contentType(CT).header("X-Amz-Target", TARGET + "RegisterTaskDefinition")
                .body(DEFINITION.formatted(family, extraHosts))
                .when().post("/")
                .then().statusCode(expectedStatus)
                .extract().response();
    }

    @Test
    void declaredHostEntriesAreKeptAsDeclared() {
        register("kept", "[{\"hostname\":\"db.internal\",\"ipAddress\":\"10.0.0.5\"},"
                + "{\"hostname\":\"gw.internal\",\"ipAddress\":\"host-gateway\"}]", 200)
                .then()
                .body("taskDefinition.containerDefinitions[0].extraHosts[0].hostname", equalTo("db.internal"))
                .body("taskDefinition.containerDefinitions[0].extraHosts[0].ipAddress", equalTo("10.0.0.5"))
                .body("taskDefinition.containerDefinitions[0].extraHosts[1].ipAddress", equalTo("host-gateway"));
    }

    @Test
    void aHostEntryNeedsBothMembers() {
        register("no-ip", "[{\"hostname\":\"db.internal\"}]", 400)
                .then().body("message", containsString("ipAddress"));
        register("no-name", "[{\"ipAddress\":\"10.0.0.5\"}]", 400)
                .then().body("message", containsString("hostname"));
    }

    @Test
    void aHostnameCannotCarryAnotherHostsLine() {
        register("newline", "[{\"hostname\":\"db.internal\\n10.6.6.6 sts.amazonaws.com\","
                + "\"ipAddress\":\"10.0.0.5\"}]", 400)
                .then().body("message", containsString("extraHosts"));
        register("colon", "[{\"hostname\":\"db:internal\",\"ipAddress\":\"10.0.0.5\"}]", 400)
                .then().body("message", containsString("extraHosts"));
    }
}
