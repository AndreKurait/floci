package io.github.hectorvent.floci.services.pipes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The injected poller in real time: its own Vert.x timer and its own poll threads carry a message
 * from the source queue to the target. {@code PipesPollerIntegrationTest} and
 * {@code PipesPollerScheduleTest} cover what a poll does and when, on a controlled timeline.
 */
@QuarkusTest
class PipesPollerWiringIntegrationTest {

    private static final String SQS_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String QUEUE_URL = "http://localhost:4566/000000000000/";

    @Test
    void aRunningPipeIsPolledByTheServicesOwnTimer() {
        createQueue("pipe-wiring-source");
        createQueue("pipe-wiring-target");
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-wiring-source",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:pipe-wiring-target",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "DesiredState": "RUNNING"
                }
                """)
        .when()
            .post("/v1/pipes/pipe-wiring")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("RUNNING"));
        try {
            given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "SendMessage")
                .formParam("QueueUrl", QUEUE_URL + "pipe-wiring-source")
                .formParam("MessageBody", "carried by the real timer")
            .when()
                .post("/")
            .then()
                .statusCode(200);

            StringBuilder received = new StringBuilder();
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                    .until(() -> received.append(given()
                            .contentType(SQS_CONTENT_TYPE)
                            .formParam("Action", "ReceiveMessage")
                            .formParam("QueueUrl", QUEUE_URL + "pipe-wiring-target")
                            .formParam("MaxNumberOfMessages", "1")
                        .when()
                            .post("/")
                        .then()
                            .statusCode(200)
                            .extract().body().asString())
                            .indexOf("carried by the real timer") >= 0);
            assertTrue(received.indexOf("carried by the real timer") >= 0);
        } finally {
            given().contentType("application/json").when().delete("/v1/pipes/pipe-wiring")
                    .then().statusCode(200);
        }
    }

    private static void createQueue(String name) {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", name)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
