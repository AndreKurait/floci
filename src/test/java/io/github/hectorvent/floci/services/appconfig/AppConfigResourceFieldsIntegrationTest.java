package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class AppConfigResourceFieldsIntegrationTest {

    private static final String ALARM_ARN = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration-errors";
    private static final String SECOND_ALARM_ARN = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration-latency";
    private static final String ALARM_ROLE_ARN = "arn:aws:iam::000000000000:role/appconfig-alarm-monitor";

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void environmentReturnsTheMonitorsItWasCreatedWith() {
        String applicationId = createApplication("monitored-app");
        String environmentId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\":\"monitored\",\"Monitors\":["
                        + "{\"AlarmArn\":\"" + ALARM_ARN + "\",\"AlarmRoleArn\":\"" + ALARM_ROLE_ARN + "\"},"
                        + "{\"AlarmArn\":\"" + SECOND_ALARM_ARN + "\"}]}")
                .when().post("/applications/" + applicationId + "/environments")
                .then().statusCode(201)
                .body("Monitors.AlarmArn", contains(ALARM_ARN, SECOND_ALARM_ARN))
                .body("Monitors[0].AlarmRoleArn", equalTo(ALARM_ROLE_ARN))
                .body("Monitors[1]", not(hasKey("AlarmRoleArn")))
                .extract().path("Id");

        given()
                .when().get("/applications/" + applicationId + "/environments/" + environmentId)
                .then().statusCode(200)
                .body("Monitors", equalTo(List.of(
                        Map.of("AlarmArn", ALARM_ARN, "AlarmRoleArn", ALARM_ROLE_ARN),
                        Map.of("AlarmArn", SECOND_ALARM_ARN))));

        given()
                .when().get("/applications/" + applicationId + "/environments")
                .then().statusCode(200)
                .body("Items.find { it.Id == '" + environmentId + "' }.Monitors.AlarmArn",
                        contains(ALARM_ARN, SECOND_ALARM_ARN));
    }

    @Test
    void environmentCreatedWithoutMonitorsReturnsNone() {
        String applicationId = createApplication("unmonitored-app");
        String environmentId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\":\"unmonitored\"}")
                .when().post("/applications/" + applicationId + "/environments")
                .then().statusCode(201)
                .body("$", not(hasKey("Monitors")))
                .extract().path("Id");

        given()
                .when().get("/applications/" + applicationId + "/environments/" + environmentId)
                .then().statusCode(200)
                .body("$", not(hasKey("Monitors")));
    }

    @Test
    void invalidMonitorsAreRefusedAndNoEnvironmentIsCreated() {
        String applicationId = createApplication("refused-monitors-app");
        String monitor = "{\"AlarmArn\":\"" + ALARM_ARN + "\"}";
        List<String> invalid = List.of(
                "\"not-a-list\"",
                "[" + String.join(",", Collections.nCopies(6, monitor)) + "]",
                "[\"not-a-monitor\"]",
                "[null]",
                "[{}]",
                "[{\"AlarmArn\":\"\"}]",
                "[{\"AlarmArn\":1}]",
                "[{\"AlarmArn\":\"" + "a".repeat(2049) + "\"}]",
                "[{\"AlarmArn\":\"" + ALARM_ARN + "\",\"AlarmRoleArn\":1}]",
                "[{\"AlarmArn\":\"" + ALARM_ARN + "\",\"AlarmRoleArn\":\"appconfig-alarm-monitor\"}]",
                "[{\"AlarmArn\":\"" + ALARM_ARN + "\",\"AlarmRoleArn\":\"arn:aws:iam::000000000000:user/monitor\"}]",
                "[{\"AlarmArn\":\"" + ALARM_ARN + "\",\"AlarmRoleArn\":\"" + ALARM_ROLE_ARN + "x".repeat(2048) + "\"}]");
        for (String monitors : invalid) {
            given()
                    .contentType(ContentType.JSON)
                    .body("{\"Name\":\"refused\",\"Monitors\":" + monitors + "}")
                    .when().post("/applications/" + applicationId + "/environments")
                    .then().statusCode(400)
                    .body("__type", equalTo("BadRequestException"));
        }

        given()
                .when().get("/applications/" + applicationId + "/environments")
                .then().statusCode(200)
                .body("Items", empty());
    }

    @Test
    void deploymentReturnsTheDeployedProfileNameAndLocation() {
        String applicationId = createApplication("deployment-source-app");
        String environmentId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\":\"deployment-source-env\"}")
                .when().post("/applications/" + applicationId + "/environments")
                .then().statusCode(201)
                .extract().path("Id");
        String profileId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\":\"service-settings\",\"LocationUri\":\"ssm-parameter://service-settings\","
                        + "\"Type\":\"AWS.Freeform\"}")
                .when().post("/applications/" + applicationId + "/configurationprofiles")
                .then().statusCode(201)
                .extract().path("Id");
        String deployments = "/applications/" + applicationId + "/environments/" + environmentId + "/deployments";

        int deploymentNumber = given()
                .contentType(ContentType.JSON)
                .body("{\"ConfigurationProfileId\":\"" + profileId + "\",\"ConfigurationVersion\":\"1\","
                        + "\"DeploymentStrategyId\":\"AppConfig.AllAtOnce\"}")
                .when().post(deployments)
                .then().statusCode(201)
                .body("ConfigurationName", equalTo("service-settings"))
                .body("ConfigurationLocationUri", equalTo("ssm-parameter://service-settings"))
                .extract().path("DeploymentNumber");

        given()
                .when().get(deployments + "/" + deploymentNumber)
                .then().statusCode(200)
                .body("ConfigurationName", equalTo("service-settings"))
                .body("ConfigurationLocationUri", equalTo("ssm-parameter://service-settings"));
    }

    @Test
    void profileCreatedWithoutTypeReturnsNoType() {
        String applicationId = createApplication("untyped-profile-app");
        String profileId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\":\"untyped\",\"LocationUri\":\"hosted\"}")
                .when().post("/applications/" + applicationId + "/configurationprofiles")
                .then().statusCode(201)
                .body("Type", nullValue())
                .extract().path("Id");

        given()
                .when().get("/applications/" + applicationId + "/configurationprofiles/" + profileId)
                .then().statusCode(200)
                .body("Type", nullValue());
    }

    private static String createApplication(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"Name\":\"" + name + "\"}")
                .when().post("/applications")
                .then().statusCode(201)
                .extract().path("Id");
    }
}
