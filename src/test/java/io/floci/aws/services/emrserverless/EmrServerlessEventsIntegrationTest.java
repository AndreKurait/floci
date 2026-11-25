package io.floci.aws.services.emrserverless;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.testing.EmrServerlessRequests;
import io.floci.aws.testing.EventRuleQueue;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EmrServerlessRequests.emrServerless;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code aws.emr-serverless} job run state change events reach an EventBridge rule's target on the
 * default bus of the run's account and Region. The runs are in a Region no other EMR Serverless test
 * uses, so the rule sees only this test's.
 */
@QuarkusTest
class EmrServerlessEventsIntegrationTest {

    private static final String REGION = "eu-south-2";
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-serverless-job";
    private static final String EVENTS = "{\"source\":[\"aws.emr-serverless\"],"
            + "\"detail-type\":[\"EMR Serverless Job Run State Change\"]}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aBatchRunAndACancelledStreamingRunReportEachStateTheyEnter() throws Exception {
        try (EventRuleQueue queue = EventRuleQueue.route(REGION, "emr-serverless-job-run-events", EVENTS)) {
            String applicationId = EmrServerlessRequests.createSparkApplication(REGION, "emr-7.5.0");
            String batch = EmrServerlessRequests.startJobRun(REGION, applicationId, run(""));
            String streaming = EmrServerlessRequests.startJobRun(REGION, applicationId,
                    run(", \"mode\": \"STREAMING\""));
            emrServerless(REGION).when().delete("/applications/" + applicationId + "/jobruns/" + streaming)
                    .then().statusCode(200);

            List<JsonNode> events = drain(queue, 6);
            assertThat(events.stream().map(EmrServerlessEventsIntegrationTest::transition).toList(),
                    containsInAnyOrder(batch + " SUBMITTED<-", batch + " RUNNING<-SUBMITTED",
                            batch + " SUCCESS<-RUNNING", streaming + " SUBMITTED<-",
                            streaming + " RUNNING<-SUBMITTED", streaming + " CANCELLED<-RUNNING"));
            for (JsonNode event : events) {
                assertEquals("aws.emr-serverless", event.path("source").asText());
                assertEquals("EMR Serverless Job Run State Change", event.path("detail-type").asText());
                assertEquals(ACCOUNT, event.path("account").asText());
                assertEquals(REGION, event.path("region").asText());
                assertEquals(0, event.path("resources").size());
                JsonNode detail = event.path("detail");
                String jobRunId = detail.path("jobRunId").asText();
                assertEquals(applicationId, detail.path("applicationId").asText());
                assertEquals("arn:aws:emr-serverless:" + REGION + ":" + ACCOUNT + ":/applications/" + applicationId
                        + "/jobruns/" + jobRunId, detail.path("arn").asText());
                assertEquals("emr-7.5.0", detail.path("releaseLabel").asText());
                assertEquals("arn:aws:iam::" + ACCOUNT + ":root", detail.path("createdBy").asText());
            }
            assertEquals(List.of(), drain(queue, 0));
        }
    }

    @Test
    void aFinishedRunReportsZeroBilledAndTotalResourceUtilization() {
        // A Region of its own, whose events no rule of the other test can see.
        String region = "eu-central-2";
        String applicationId = EmrServerlessRequests.createSparkApplication(region, "emr-7.5.0");
        String succeeded = EmrServerlessRequests.startJobRun(region, applicationId, run(""));
        String cancelled = EmrServerlessRequests.startJobRun(region, applicationId, run(", \"mode\": \"STREAMING\""));
        jobRun(region, applicationId, cancelled).body("jobRun.state", equalTo("RUNNING"))
                .body("jobRun.billedResourceUtilization", nullValue());
        emrServerless(region).when().delete("/applications/" + applicationId + "/jobruns/" + cancelled)
                .then().statusCode(200);

        for (String jobRunId : List.of(succeeded, cancelled)) {
            ValidatableResponse finished = jobRun(region, applicationId, jobRunId);
            for (String utilization : List.of("billedResourceUtilization", "totalResourceUtilization")) {
                finished.body("jobRun." + utilization + ".vCPUHour", equalTo(0.0f))
                        .body("jobRun." + utilization + ".memoryGBHour", equalTo(0.0f))
                        .body("jobRun." + utilization + ".storageGBHour", equalTo(0.0f));
            }
        }
    }

    private static ValidatableResponse jobRun(String region, String applicationId, String jobRunId) {
        return emrServerless(region).when().get("/applications/" + applicationId + "/jobruns/" + jobRunId)
                .then().statusCode(200);
    }

    private static String run(String extra) {
        return """
                {"clientToken": "%s", "executionRoleArn": "%s",
                 "jobDriver": {"sparkSubmit": {"entryPoint": "s3://bucket/jobs/app.jar"}}%s}
                """.formatted(UUID.randomUUID(), ROLE, extra);
    }

    /**
     * The events on the queue once {@code expected} of them have arrived, and then any that follow within a
     * second: EMR Serverless sends them after the calls that made the changes.
     */
    private static List<JsonNode> drain(EventRuleQueue queue, int expected) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() -> {
            events.addAll(queue.drain());
            return events.size() >= expected;
        });
        Thread.sleep(1000);
        events.addAll(queue.drain());
        return events;
    }

    // The run's id, the state the event reports and the state before it: "<id> SUBMITTED<-" for a run just submitted.
    private static String transition(JsonNode event) {
        JsonNode detail = event.path("detail");
        return detail.path("jobRunId").asText() + " " + detail.path("state").asText() + "<-"
                + detail.path("previousState").asText();
    }
}
