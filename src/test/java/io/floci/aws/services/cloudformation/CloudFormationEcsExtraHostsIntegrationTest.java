package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An {@code AWS::ECS::TaskDefinition} container's {@code ExtraHosts} reach the task definition in
 * the API's HostEntry shape, and an entry RegisterTaskDefinition refuses fails the stack.
 */
@QuarkusTest
class CloudFormationEcsExtraHostsIntegrationTest {

    private static final String ECS_CT = "application/x-amz-json-1.1";
    private static final String ECS_TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String TEMPLATE = """
            {
              "Resources": {
                "TaskDef": {
                  "Type": "AWS::ECS::TaskDefinition",
                  "Properties": {
                    "Family": "%s",
                    "NetworkMode": "bridge",
                    "ContainerDefinitions": [
                      {"Name": "app", "Image": "busybox", "Memory": 128, "ExtraHosts": %s}
                    ]
                  }
                }
              }
            }
            """;

    private final List<String> stacksToDelete = new ArrayList<>();

    @AfterEach
    void deleteStacks() {
        for (String name : stacksToDelete) {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteStack")
                    .formParam("StackName", name)
                    .when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(name);
        }
        stacksToDelete.clear();
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void extraHostsReachTheTaskDefinition() {
        String name = "cfn-extra-hosts-" + System.nanoTime();
        stacksToDelete.add(name);
        createStack(name, TEMPLATE.formatted(name, "[{\"Hostname\": \"db.internal\", \"IpAddress\": \"10.0.0.5\"},"
                + " {\"Hostname\": \"gw.internal\", \"IpAddress\": \"host-gateway\"}]"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());

        given().contentType(ECS_CT)
                .header("X-Amz-Target", ECS_TARGET + "DescribeTaskDefinition")
                .body("{\"taskDefinition\":\"" + name + "\"}")
                .when().post("/").then().statusCode(200)
                .body("taskDefinition.containerDefinitions[0].extraHosts", equalTo(List.of(
                        Map.of("hostname", "db.internal", "ipAddress", "10.0.0.5"),
                        Map.of("hostname", "gw.internal", "ipAddress", "host-gateway"))));
    }

    @Test
    void aHostEntryWithoutAnAddressFailsTheStack() {
        String name = "cfn-extra-hosts-invalid-" + System.nanoTime();
        stacksToDelete.add(name);
        createStack(name, TEMPLATE.formatted(name, "[{\"Hostname\": \"db.internal\"}]"));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(name).status());
    }

    private static void createStack(String name, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", name)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
    }
}
