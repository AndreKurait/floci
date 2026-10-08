package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AppConfigCfnIntegrationTest {
    private static final String STACK = "appconfig-owned-resources-test";
    private static final String ALARM = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration";
    private static final String TEMPLATE = """
            {
              "Resources": {
                "Application": {"Type":"AWS::AppConfig::Application","Properties":{"Name":"cfn-configuration-test"}},
                "Environment": {"Type":"AWS::AppConfig::Environment","Properties":{
                  "ApplicationId":{"Ref":"Application"},"Name":"testing",
                  "Monitors":[{"AlarmArn":"arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration"}]}},
                "Profile": {"Type":"AWS::AppConfig::ConfigurationProfile","Properties":{
                  "ApplicationId":{"Ref":"Application"},"Name":"settings","LocationUri":"hosted"}},
                "Strategy": {"Type":"AWS::AppConfig::DeploymentStrategy","Properties":{
                  "Name":"configuration-test-immediate","DeploymentDurationInMinutes":0,
                  "FinalBakeTimeInMinutes":0,"GrowthFactor":100,"ReplicateTo":"NONE"}}
              },
              "Outputs": {
                "AppRef":{"Value":{"Ref":"Application"}},"AppId":{"Value":{"Fn::GetAtt":["Application","ApplicationId"]}},
                "EnvRef":{"Value":{"Ref":"Environment"}},"EnvId":{"Value":{"Fn::GetAtt":["Environment","EnvironmentId"]}},
                "ProfileRef":{"Value":{"Ref":"Profile"}},"ProfileId":{"Value":{"Fn::GetAtt":["Profile","ConfigurationProfileId"]}},
                "StrategyRef":{"Value":{"Ref":"Strategy"}},"StrategyId":{"Value":{"Fn::GetAtt":["Strategy","Id"]}}
              }
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void stackCreatesRealConfigurationResourcesAndDeletesTheirDeployedState() {
        cfn("CreateStack").formParam("TemplateBody", TEMPLATE).post("/").then().statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(STACK).status());
        String described = cfn("DescribeStacks").post("/").then().statusCode(200)
                .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>")).extract().asString();
        Map<String, String> outputs = XmlParser.extractPairs(described, "Outputs", "OutputKey", "OutputValue");
        for (String resource : List.of("App", "Env", "Profile", "Strategy")) {
            assertEquals(outputs.get(resource + "Ref"), outputs.get(resource + "Id"));
        }
        String app = outputs.get("AppId");
        String env = outputs.get("EnvId");
        String profile = outputs.get("ProfileId");
        String strategy = outputs.get("StrategyId");
        String envPath = "/applications/" + app + "/environments/" + env;
        String profilePath = "/applications/" + app + "/configurationprofiles/" + profile;
        try {
            appconfig().get(envPath).then().statusCode(200)
                    .body("ApplicationId", equalTo(app), "Monitors[0].AlarmArn", equalTo(ALARM))
                    .body("Monitors[0]", not(hasKey("AlarmRoleArn")));
            appconfig().get(profilePath).then().statusCode(200)
                    .body("Name", equalTo("settings"), "LocationUri", equalTo("hosted"),
                            "Type", equalTo("AWS.Freeform"));
            appconfig().get("/deploymentstrategies/" + strategy).then().statusCode(200)
                    .body("DeploymentDurationInMinutes", equalTo(0), "GrowthFactor", equalTo(100f));
            appconfig().body("{\"enabled\":true}").post(profilePath + "/hostedconfigurationversions")
                    .then().statusCode(201).header("Version-Number", equalTo("1"));
            int deployment = appconfig().body(Map.of("ConfigurationProfileId", profile,
                            "ConfigurationVersion", "1", "DeploymentStrategyId", strategy))
                    .post(envPath + "/deployments").then().statusCode(201)
                    .body("ConfigurationLocationUri", equalTo("hosted"), "ConfigurationName", equalTo("settings"))
                    .extract().path("DeploymentNumber");
            appconfig().get(envPath + "/deployments/" + deployment).then().statusCode(200)
                    .body("ApplicationId", equalTo(app), "EnvironmentId", equalTo(env),
                            "ConfigurationProfileId", equalTo(profile), "ConfigurationLocationUri", equalTo("hosted"));
            cfn("UpdateStack").formParam("TemplateBody", TEMPLATE).post("/").then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(STACK).status());
            appconfig().get(envPath).then().statusCode(200).body("Id", equalTo(env));
            cfn("DeleteStack").post("/").then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(STACK);
            appconfig().get(envPath).then().statusCode(404);
            appconfig().get(profilePath).then().statusCode(404);
            appconfig().get(profilePath + "/hostedconfigurationversions/1").then().statusCode(404);
            appconfig().get(envPath + "/deployments/" + deployment).then().statusCode(404);
            appconfig().get("/deploymentstrategies/" + strategy).then().statusCode(404);
            appconfig().get("/applications/" + app).then().statusCode(404);
        } finally {
            cfn("DeleteStack").post("/");
        }
    }

    private static RequestSpecification cfn(String action) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261008/us-east-1/cloudformation/aws4_request")
                .formParam("Action", action).formParam("StackName", STACK);
    }

    private static RequestSpecification appconfig() {
        return given().contentType(ContentType.JSON)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261008/us-east-1/appconfig/aws4_request");
    }
}
