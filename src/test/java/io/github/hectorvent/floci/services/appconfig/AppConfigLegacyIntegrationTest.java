package io.github.hectorvent.floci.services.appconfig;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class AppConfigLegacyIntegrationTest {
    private String application;
    private String environment;
    private String profile;
    private String name;

    @BeforeEach
    void deployHostedConfiguration() {
        name = "legacy-" + UUID.randomUUID();
        application = given().contentType(ContentType.JSON).body(Map.of("Name", name))
                .post("/applications").then().statusCode(201).extract().path("Id");
        environment = given().contentType(ContentType.JSON).body(Map.of("Name", "local"))
                .post("/applications/" + application + "/environments")
                .then().statusCode(201).extract().path("Id");
        profile = given().contentType(ContentType.JSON)
                .body(Map.of("Name", "settings", "LocationUri", "hosted"))
                .post("/applications/" + application + "/configurationprofiles")
                .then().statusCode(201).extract().path("Id");
        given().contentType(ContentType.JSON).body("{\"enabled\":true}")
                .post("/applications/" + application + "/configurationprofiles/" + profile
                        + "/hostedconfigurationversions").then().statusCode(201);
        String strategy = given().contentType(ContentType.JSON).body(Map.of(
                "Name", name, "DeploymentDurationInMinutes", 0, "GrowthFactor", 100,
                "FinalBakeTimeInMinutes", 0, "ReplicateTo", "NONE"))
                .post("/deploymentstrategies").then().statusCode(201).extract().path("Id");
        given().contentType(ContentType.JSON).body(Map.of("ConfigurationProfileId", profile,
                "ConfigurationVersion", "1", "DeploymentStrategyId", strategy))
                .post("/applications/" + application + "/environments/" + environment + "/deployments")
                .then().statusCode(201);
    }

    private String path() {
        return "/applications/" + application + "/environments/" + environment + "/configurations/" + profile;
    }

    @Test
    void readsTheDeployedVersionByIdsAndNames() {
        for (String selected : new String[]{path(),
                "/applications/" + name + "/environments/local/configurations/settings"}) {
            given().queryParam("client_id", "reader").get(selected).then()
                    .statusCode(200).header("Configuration-Version", "1").body("enabled", equalTo(true));
        }
    }

    @Test
    void unchangedVersionHasNoBodyAndDoesNotCreateAPollingSession() {
        given().queryParam("client_id", "reader").queryParam("client_configuration_version", "1")
                .get(path()).then().statusCode(204).header("Configuration-Version", "1").body(equalTo(""));
        given().queryParam("client_id", "another-reader").get(path())
                .then().statusCode(200).body("enabled", equalTo(true));
    }

    @Test
    void anUndeployedNewVersionDoesNotReplaceTheActiveVersion() {
        given().contentType(ContentType.JSON).body("{\"enabled\":false}")
                .post("/applications/" + application + "/configurationprofiles/" + profile
                        + "/hostedconfigurationversions").then().statusCode(201);
        given().queryParam("client_id", "reader").get(path())
                .then().statusCode(200).header("Configuration-Version", "1").body("enabled", equalTo(true));
    }

    @Test
    void refusesMissingOrOversizedClientAndVersion() {
        given().get(path()).then().statusCode(400);
        given().queryParam("client_id", "x".repeat(65)).get(path()).then().statusCode(400);
        given().queryParam("client_id", "reader").queryParam("client_configuration_version", "x".repeat(1025))
                .get(path()).then().statusCode(400);
    }

    @Test
    void absentEnvironmentDoesNotReceiveAnotherEnvironmentsDeployment() {
        given().queryParam("client_id", "reader")
                .get("/applications/" + application + "/environments/absent/configurations/" + profile)
                .then().statusCode(404);
    }
}
