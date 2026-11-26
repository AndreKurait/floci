package io.floci.aws.services.ecs;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * EC2 launch type placement as real ECS does it: a task with no launch type and no capacity
 * provider strategy (on a cluster without a default strategy) is an EC2 task; an EC2 task is
 * placed on a registered ACTIVE container instance and carries its ARN and Availability Zone; with
 * no container instance there is nowhere to place it and RunTask fails; and a container instance
 * is the EC2 instance its agent's instance identity document names.
 */
@QuarkusTest
class EcsEc2LaunchPlacementIntegrationTest {

    private static final String TARGET_PREFIX = "AmazonEC2ContainerServiceV20141113.";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification ecs(String action) {
        return given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET_PREFIX + action);
    }

    private static String newCluster(String prefix) {
        String cluster = prefix + "-" + Long.toString(System.nanoTime(), 36);
        ecs("CreateCluster").body("{\"clusterName\": \"" + cluster + "\"}").when().post("/").then().statusCode(200);
        ecs("RegisterTaskDefinition").body("""
                {"family": "%s", "networkMode": "bridge",
                 "containerDefinitions": [{"name": "app", "image": "public.ecr.aws/amazonlinux/amazonlinux:2",
                                           "memory": 128, "essential": true}]}
                """.formatted(cluster)).when().post("/").then().statusCode(200);
        return cluster;
    }

    private static String registerInstance(String cluster, String instanceId, String zone) {
        String document = ("{\\\"accountId\\\":\\\"123456789012\\\",\\\"availabilityZone\\\":\\\"%s\\\","
                + "\\\"instanceId\\\":\\\"%s\\\",\\\"instanceType\\\":\\\"m5.large\\\","
                + "\\\"privateIp\\\":\\\"10.0.1.5\\\",\\\"region\\\":\\\"us-east-1\\\"}").formatted(zone, instanceId);
        return ecs("RegisterContainerInstance").body("""
                {"cluster": "%s", "instanceIdentityDocument": "%s",
                 "totalResources": [{"name": "CPU", "type": "INTEGER", "integerValue": 2048},
                                    {"name": "MEMORY", "type": "INTEGER", "integerValue": 4096}]}
                """.formatted(cluster, document))
            .when().post("/")
            .then().statusCode(200)
            .body("containerInstance.ec2InstanceId", equalTo(instanceId))
            .extract().path("containerInstance.containerInstanceArn");
    }

    @Test
    void containerInstanceIsTheEc2InstanceItsIdentityDocumentNames() {
        String cluster = newCluster("ci-identity");
        String arn = registerInstance(cluster, "i-0123456789abcdef0", "us-east-1b");
        ecs("DescribeContainerInstances")
            .body("{\"cluster\": \"" + cluster + "\", \"containerInstances\": [\"" + arn + "\"]}")
        .when().post("/")
        .then().statusCode(200)
            .body("containerInstances[0].ec2InstanceId", equalTo("i-0123456789abcdef0"));
    }

    @Test
    void runTaskWithoutLaunchTypeIsPlacedOnARegisteredContainerInstanceAsEc2() {
        String cluster = newCluster("ec2-default");
        String arn = registerInstance(cluster, "i-0aaaaaaaaaaaaaaa1", "us-east-1c");
        ecs("RunTask").body("{\"cluster\": \"" + cluster + "\", \"taskDefinition\": \"" + cluster + "\"}")
        .when().post("/")
        .then().statusCode(200)
            .body("tasks[0].launchType", equalTo("EC2"))
            .body("tasks[0].containerInstanceArn", equalTo(arn))
            .body("tasks[0].availabilityZone", equalTo("us-east-1c"));
    }

    @Test
    void ec2RunTaskWithNoContainerInstanceHasNowhereToRun() {
        String cluster = newCluster("ec2-empty");
        ecs("RunTask").body("{\"cluster\": \"" + cluster + "\", \"taskDefinition\": \"" + cluster
                + "\", \"launchType\": \"EC2\"}")
        .when().post("/")
        .then().statusCode(400)
            .body("__type", containsString("InvalidParameterException"))
            .body("message", containsString("No Container Instances were found in your cluster"));
    }

    @Test
    void createServiceWithoutLaunchTypeIsAnEc2Service() {
        String cluster = newCluster("ec2-service");
        registerInstance(cluster, "i-0bbbbbbbbbbbbbbb2", "us-east-1a");
        ecs("CreateService").body("""
                {"cluster": "%s", "serviceName": "svc", "taskDefinition": "%s", "desiredCount": 0}
                """.formatted(cluster, cluster))
        .when().post("/")
        .then().statusCode(200)
            .body("service.launchType", equalTo("EC2"));
    }
}
