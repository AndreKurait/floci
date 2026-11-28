package io.floci.aws.core.common;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionMatrix;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.mq.MqClient;
import software.amazon.awssdk.services.mq.model.CreateConfigurationResponse;
import software.amazon.awssdk.services.mq.model.MqException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that REST calls for unimplemented operations signed for a service sharing a path
 * with another service's controller are rejected with 404 UnknownOperationException before
 * reaching the unrelated controller (issue #6426).
 */
@QuarkusTest
class RestCrossServiceRouteCollisionIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void querySignedCollidingRestCallIsSafelyRejectedWith404(String authorization) {
        RequestSpecification spec = given()
            .queryParam("X-Amz-Credential", "test/20260707/us-east-1/appsync/aws4_request")
            .contentType("application/json")
            .body("{}");
        if (authorization != null) {
            spec.header("Authorization", authorization);
        }
        spec.when()
            .post("/v2/apis")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .header("X-Amzn-Errortype", "UnknownOperationException")
            .body("__type", equalTo("UnknownOperationException"))
            .body("message", equalTo("Unknown operation: POST /v2/apis"));
    }

    @ParameterizedTest
    @CsvSource({
            "appsync, POST, /v2/apis",
            "appsync, GET, /v2/apis",
            "appsync, GET, /v2/apis/api-123",
            "appsync, DELETE, /v2/apis/api-123",
            "app-integrations, POST, /applications",
            "app-integrations, GET, /applications",
            "app-integrations, GET, /applications/app-123",
            "app-integrations, DELETE, /applications/app-123",
            "servicecatalog, POST, /applications",
            "servicecatalog, GET, /applications",
            "servicecatalog, GET, /applications/app-123",
            "servicecatalog, DELETE, /applications/app-123",
            "securityhub, GET, /associations",
            "securityhub, POST, /associations",
            "macie2, GET, /jobs/job-123",
            "connect, POST, /rules/inst-123",
            "connect, GET, /rules/inst-123",
            "connect, GET, /workspaces/inst-123",
            "inspector2, POST, /cluster/get",
            "lakeformation, POST, /CommitTransaction",
            "bedrock, GET, /resourcepolicy/arn:aws:bedrock:us-east-1:123456789012:probe",
            "bedrock, PUT, /resourcepolicy/arn:aws:bedrock:us-east-1:123456789012:probe",
            "bedrock, DELETE, /resourcepolicy/arn:aws:bedrock:us-east-1:123456789012:probe"
    })
    void collidingRestCallsAreSafelyRejectedWith404(String service, String method, String path) {
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-east-1", service))
            .contentType("application/json")
            .body("{}")
        .when()
            .request(method, path)
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .header("X-Amzn-Errortype", "UnknownOperationException")
            .header("x-amzn-query-error", "UnknownOperationException;Sender")
            .body("__type", equalTo("UnknownOperationException"))
            .body("message", equalTo("Unknown operation: " + method + " " + path));
    }

    @Test
    void mqAndKafkaConfigurationLifecyclesStayWithinTheirSigningScope() {
        String account = Long.toString(ThreadLocalRandom.current().nextLong(740000000000L, 749999999999L));
        String name = "collision-" + UUID.randomUUID();
        try (MqClient mq = AwsTestClients.create(MqClient.builder(), account, Region.US_EAST_1)) {
            String kafkaArn = kafkaRequest(account).body(Map.of("name", name, "serverProperties",
                    Base64.getEncoder().encodeToString("auto.create.topics.enable=false\n".getBytes(StandardCharsets.UTF_8))))
                    .post("/v1/configurations").then().statusCode(200).extract().path("arn");
            try {
                CreateConfigurationResponse created = mq.createConfiguration(r -> r.name(name)
                        .engineType("RABBITMQ").engineVersion("3.13").authenticationStrategy("SIMPLE"));
                try {
                    assertNotNull(created.id(), "The MQ route must return its own configuration identity");
                    assertTrue(created.arn().contains(":mq:"), created.arn());
                    assertEquals(List.of(created.arn()), mq.listConfigurations().configurations().stream()
                            .map(configuration -> configuration.arn()).toList());
                    kafkaRequest(account).get("/v1/configurations").then().statusCode(200)
                            .body("configurations.arn", equalTo(List.of(kafkaArn)));
                    assertEquals(created.id(), mq.describeConfiguration(r -> r.configurationId(created.id())).id());
                    kafkaRequest(account).get("/v1/configurations/{arn}", kafkaArn).then().statusCode(200)
                            .body("latestRevision.revision", equalTo(1));
                    String data = Base64.getEncoder().encodeToString("heartbeat = 120\n".getBytes(StandardCharsets.UTF_8));
                    assertEquals(2, mq.updateConfiguration(r -> r.configurationId(created.id())
                            .data(data).description("MQ revision")).latestRevision().revision());
                    assertEquals("MQ revision", mq.describeConfiguration(r -> r.configurationId(created.id()))
                            .description());
                    kafkaRequest(account).get("/v1/configurations/{arn}", kafkaArn).then().statusCode(200)
                            .body("latestRevision.revision", equalTo(1));
                } finally {
                    if (created.id() != null) {
                        mq.deleteConfiguration(r -> r.configurationId(created.id()));
                    } else if (created.arn() != null && created.arn().contains(":kafka:")) {
                        // If routing regresses, remove only the returned Kafka resource with our reserved name.
                        kafkaRequest(account).get("/v1/configurations/{arn}", created.arn()).then().statusCode(200)
                                .body("name", equalTo(name));
                        kafkaRequest(account).delete("/v1/configurations/{arn}", created.arn()).then().statusCode(200);
                    }
                }
                assertEquals(404, assertThrows(MqException.class,
                        () -> mq.describeConfiguration(r -> r.configurationId(created.id()))).statusCode());
                assertEquals(List.of(), mq.listConfigurations().configurations());
                kafkaRequest(account).get("/v1/configurations").then().statusCode(200)
                        .body("configurations.arn", equalTo(List.of(kafkaArn)));
            } finally {
                kafkaRequest(account).delete("/v1/configurations/{arn}", kafkaArn).then().statusCode(200);
            }
        }
    }

    private static RequestSpecification kafkaRequest(String account) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                + "/20261125/us-east-1/kafka/aws4_request, SignedHeaders=host, Signature=00")
                .contentType("application/json");
    }

    @Test
    void legitimateAppConfigCallIsNotRejectedByCollisionGuard() {
        // AppConfig owns /applications, so signed as appconfig it must not be rejected with UnknownOperationException
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-east-1", "appconfig"))
            .contentType("application/json")
            .body("{\"Name\":\"route-collision-app\"}")
        .when()
            .post("/applications")
        .then()
            .statusCode(201);
    }

    @Test
    void legitimateBedrockResourcePolicyWithHyphenIsNotRejectedByCollisionGuard() {
        // Bedrock owns /resource-policy (with hyphen), must not collide with /resourcepolicy
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-east-1", "bedrock"))
            .contentType("application/json")
        .when()
            .get("/resource-policy/arn:aws:bedrock:us-east-1:123456789012:test-resource")
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void legitimateAppSyncChannelNamespaceCallIsNotRejectedByCollisionGuard() {
        // AppSync owns /v2/apis/{apiId}/channelNamespaces, so signed as appsync it reaches AppSync
        // (returning AppSync's NotFoundException for an unknown apiId, NOT UnknownOperationException)
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-east-1", "appsync"))
            .contentType("application/json")
            .body("{\"name\":\"test-ns\"}")
        .when()
            .post("/v2/apis/api-123/channelNamespaces")
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", equalTo("NotFoundException"));
    }
}
