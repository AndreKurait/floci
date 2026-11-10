package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.testing.TestTime;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pipes over HTTP with the real poller and the real services behind it, its one-second poll timers
 * on a {@link TestTime}: a source is polled when the test lets a second pass, not when a Vert.x
 * timer gets to it. {@code PipesPollerWiringIntegrationTest} covers the injected poller in real time.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PipesPollerIntegrationTest {

    private static final String SQS_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final TestTime time = TestTime.create();

    @BeforeAll
    static void pollOnAControlledTimeline() {
        QuarkusMock.installMockForType(new PipesPoller(TimelineVertx.on(time),
                bean(SqsService.class), bean(KinesisService.class), bean(DynamoDbStreamReader.class),
                bean(PipesKafkaConsumerManager.class), bean(PipesTargetInvoker.class),
                bean(PipesFilterMatcher.class), bean(ObjectMapper.class), bean(EmulatorConfig.class),
                time.newScheduler()), PipesPoller.class);
    }

    private static <T> T bean(Class<T> type) {
        return Arc.container().instance(type).get();
    }

    private static String receive(String queue, int max) {
        return given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/" + queue)
            .formParam("MaxNumberOfMessages", String.valueOf(max))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();
    }

    private static String visibleMessageCount(String queue) {
        return given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/" + queue)
            .formParam("AttributeName.1", "ApproximateNumberOfMessages")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();
    }

    @Test
    @Order(1)
    void createSourceQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-source-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void createTargetQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-target-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void createPipeFromSqsToSqs() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-source-queue",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:pipe-target-queue",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "DesiredState": "RUNNING"
                }
                """)
        .when()
            .post("/v1/pipes/sqs-to-sqs-pipe")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("RUNNING"));
    }

    @Test
    @Order(4)
    void sendMessageToSourceQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-source-queue")
            .formParam("MessageBody", "hello from pipes")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(5)
    void targetQueueReceivesForwardedMessage() {
        // The pipe polls one second after it started, and not a millisecond sooner.
        time.advance(POLL_INTERVAL.minusMillis(1));
        assertFalse(receive("pipe-target-queue", 1).contains("hello from pipes"),
                "Nothing is forwarded before the first poll");
        assertTrue(visibleMessageCount("pipe-source-queue").contains("<Value>1</Value>"),
                "The message is still in the source before the first poll");

        time.advance(Duration.ofMillis(1));
        String body = receive("pipe-target-queue", 1);
        assertTrue(body.contains("hello from pipes"),
                "Target queue should contain forwarded message but got: " + body);
    }

    @Test
    @Order(6)
    void sourceQueueIsDrained() {
        // The poll that forwarded the message also deleted it from the source.
        assertTrue(visibleMessageCount("pipe-source-queue").contains("<Value>0</Value>"),
                "Source queue should be drained");
    }

    @Test
    @Order(7)
    void stopPipeStopsPolling() {
        given()
            .contentType("application/json")
        .when()
            .post("/v1/pipes/sqs-to-sqs-pipe/stop")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("STOPPED"));

        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-source-queue")
            .formParam("MessageBody", "should stay in source")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        time.advance(Duration.ofMinutes(1));
        assertFalse(receive("pipe-target-queue", 1).contains("should stay in source"),
                "A stopped pipe forwards nothing");

        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-source-queue")
            .formParam("AttributeName.1", "ApproximateNumberOfMessages")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>1</Value>"));
    }

    @Test
    @Order(8)
    void cleanupPipe() {
        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/sqs-to-sqs-pipe")
        .then()
            .statusCode(200);
    }

    // ──────────────────────────── FilterCriteria Tests ────────────────────────────

    @Test
    @Order(10)
    void createFilterSourceQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-filter-source")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(11)
    void createFilterTargetQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-filter-target")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(12)
    void createPipeWithFilterCriteria() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-filter-source",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:pipe-filter-target",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "DesiredState": "RUNNING",
                    "SourceParameters": {
                        "FilterCriteria": {
                            "Filters": [
                                {"Pattern": "{\\"body\\": {\\"status\\": [\\"active\\"]}}"}
                            ]
                        }
                    }
                }
                """)
        .when()
            .post("/v1/pipes/filter-pipe")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("RUNNING"));
    }

    @Test
    @Order(13)
    void sendMatchingAndNonMatchingMessages() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-filter-source")
            .formParam("MessageBody", "{\"status\": \"active\", \"id\": \"match-1\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-filter-source")
            .formParam("MessageBody", "{\"status\": \"inactive\", \"id\": \"no-match\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(14)
    void onlyMatchingMessageForwardedToTarget() {
        time.advance(POLL_INTERVAL);
        String body = receive("pipe-filter-target", 10);
        assertTrue(body.contains("match-1"),
                "Target should contain the matching message");
        assertFalse(body.contains("no-match"),
                "Target should NOT contain the non-matching message");
    }

    @Test
    @Order(15)
    void nonMatchingMessageDeletedFromSource() {
        assertTrue(visibleMessageCount("pipe-filter-source").contains("<Value>0</Value>"),
                "Source queue should be drained (non-matching messages deleted per AWS behavior)");
        time.advance(POLL_INTERVAL);
        assertFalse(receive("pipe-filter-target", 10).contains("no-match"),
                "A later poll must not forward the non-matching message either");
    }

    @Test
    @Order(16)
    void cleanupFilterPipe() {
        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/filter-pipe")
        .then()
            .statusCode(200);
    }

    // ──────────────────────────── Batch Size Tests ────────────────────────────

    @Test
    @Order(20)
    void createBatchSourceQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-batch-source")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(21)
    void createBatchTargetQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-batch-target")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(22)
    void createPipeWithBatchSize() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-batch-source",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:pipe-batch-target",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "DesiredState": "RUNNING",
                    "SourceParameters": {
                        "SqsQueueParameters": {
                            "BatchSize": 1
                        }
                    }
                }
                """)
        .when()
            .post("/v1/pipes/batch-pipe")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("RUNNING"));
    }

    @Test
    @Order(23)
    void sendMultipleMessagesForBatchTest() {
        for (int i = 1; i <= 3; i++) {
            given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "SendMessage")
                .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-batch-source")
                .formParam("MessageBody", "batch-msg-" + i)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }

    @Test
    @Order(24)
    void allBatchMessagesEventuallyForwarded() {
        // BatchSize 1: each poll takes one message, so three messages take three polls.
        Set<String> found = new HashSet<>();
        for (int poll = 1; poll <= 3; poll++) {
            time.advance(POLL_INTERVAL);
            String body = receive("pipe-batch-target", 10);
            for (int j = 1; j <= 3; j++) {
                if (body.contains("batch-msg-" + j)) found.add("batch-msg-" + j);
            }
            assertEquals(poll, found.size(),
                    "One more batch message should reach the target with each poll, found: " + found);
        }
        assertEquals(3, found.size(),
                "All 3 batch messages should eventually reach the target, found: " + found);
        assertTrue(visibleMessageCount("pipe-batch-source").contains("<Value>0</Value>"));
    }

    @Test
    @Order(25)
    void cleanupBatchPipe() {
        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/batch-pipe")
        .then()
            .statusCode(200);
    }

    // ──────────────────────────── InputTemplate Tests ────────────────────────────

    @Test
    @Order(30)
    void createInputTemplateSourceQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-tmpl-source")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(31)
    void createInputTemplateTargetQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-tmpl-target")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(32)
    void createPipeWithInputTemplate() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-tmpl-source",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:pipe-tmpl-target",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "DesiredState": "RUNNING",
                    "TargetParameters": {
                        "InputTemplate": "{\\"transformed\\": <$.body>}"
                    }
                }
                """)
        .when()
            .post("/v1/pipes/tmpl-pipe")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("RUNNING"));
    }

    @Test
    @Order(33)
    void sendMessageForInputTemplate() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-tmpl-source")
            .formParam("MessageBody", "template-test-payload")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(34)
    void inputTemplateTransformsPayload() {
        time.advance(POLL_INTERVAL);
        String body = receive("pipe-tmpl-target", 1);
        assertTrue(body.contains("transformed"),
                "Target should contain the InputTemplate-transformed message but got: " + body);
        assertTrue(body.contains("template-test-payload"),
                "InputTemplate should resolve $.body to the original message body but got: " + body);
    }

    @Test
    @Order(35)
    void cleanupInputTemplatePipe() {
        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/tmpl-pipe")
        .then()
            .statusCode(200);
    }

    // ──────────────────────────── DLQ Routing Tests ────────────────────────────

    @Test
    @Order(40)
    void createDlqSourceQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-dlq-source")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(41)
    void createDlqQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-dlq-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(42)
    void createPipeWithDlqAndBadTarget() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-dlq-source",
                    "Target": "arn:aws:lambda:us-east-1:000000000000:function:nonexistent-fn",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "DesiredState": "RUNNING",
                    "SourceParameters": {
                        "SqsQueueParameters": {
                            "DeadLetterConfig": {
                                "Arn": "arn:aws:sqs:us-east-1:000000000000:pipe-dlq-queue"
                            }
                        }
                    }
                }
                """)
        .when()
            .post("/v1/pipes/dlq-pipe")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("RUNNING"));
    }

    @Test
    @Order(43)
    void sendMessageForDlqTest() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-dlq-source")
            .formParam("MessageBody", "should-end-up-in-dlq")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(44)
    void failedDeliveryRoutesToDlq() {
        time.advance(POLL_INTERVAL);
        String body = receive("pipe-dlq-queue", 1);
        assertTrue(body.contains("should-end-up-in-dlq"),
                "DLQ should contain the failed message but got: " + body);
    }

    @Test
    @Order(45)
    void cleanupDlqPipe() {
        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/dlq-pipe")
        .then()
            .statusCode(200);
    }

    // ──────────────────────────── Message Attributes Tests ────────────────────────────

    @Test
    @Order(50)
    void createMsgAttrSourceQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-msgattr-source")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(51)
    void createMsgAttrTargetQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-msgattr-target")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(52)
    void createMsgAttrPipeWithInputTemplate() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-msgattr-source",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:pipe-msgattr-target",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "DesiredState": "RUNNING",
                    "TargetParameters": {
                        "InputTemplate": "{\\"body\\": <$.body>, \\"traceId\\": <$.messageAttributes.traceId.stringValue>}"
                    }
                }
                """)
        .when()
            .post("/v1/pipes/msgattr-pipe")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("RUNNING"));
    }

    @Test
    @Order(53)
    void sendMessageWithAttributes() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-msgattr-source")
            .formParam("MessageBody", "{\"event\": \"test\"}")
            .formParam("MessageAttribute.1.Name", "traceId")
            .formParam("MessageAttribute.1.Value.DataType", "String")
            .formParam("MessageAttribute.1.Value.StringValue", "trace-abc-123")
            .formParam("MessageAttribute.2.Name", "priority")
            .formParam("MessageAttribute.2.Value.DataType", "Number")
            .formParam("MessageAttribute.2.Value.StringValue", "5")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(54)
    void messageAttributesForwardedAndAccessibleViaInputTemplate() {
        time.advance(POLL_INTERVAL);
        String body = receive("pipe-msgattr-target", 1);
        assertTrue(body.contains("trace-abc-123"),
                "Target should contain traceId extracted from message attributes but got: " + body);
        assertTrue(body.contains("&quot;body&quot;") || body.contains("\"body\""),
                "Target should contain body field from InputTemplate but got: " + body);
    }

    @Test
    @Order(55)
    void cleanupMsgAttrPipe() {
        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/msgattr-pipe")
        .then()
            .statusCode(200);
    }
}
