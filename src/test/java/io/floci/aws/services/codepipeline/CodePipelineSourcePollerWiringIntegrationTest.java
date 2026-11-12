package io.floci.aws.services.codepipeline;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/**
 * The injected service in real time: its own source poller, started at startup on the configured
 * interval, notices a changed S3 source object and starts an execution. {@code
 * CodePipelineIntegrationTest} covers what a poll starts and when, on a controlled timeline.
 */
@QuarkusTest
class CodePipelineSourcePollerWiringIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET = "CodePipeline_20150709.";
    private static final String PIPELINE = "source-poller-wiring-pipeline";
    private static final String BUCKET = "codepipeline-poller-wiring-source";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aChangedS3SourceIsPickedUpByTheServicesOwnPoller() {
        given().when().put("/" + BUCKET).then().statusCode(200);
        putSource("baseline artifact");
        post("CreatePipeline", """
                {
                    "pipeline": {
                        "name": "%s",
                        "roleArn": "arn:aws:iam::000000000000:role/codepipeline-role",
                        "artifactStore": {"type": "S3", "location": "codepipeline-artifacts"},
                        "stages": [{
                            "name": "Source",
                            "actions": [{
                                "name": "SourceObject",
                                "actionTypeId": {
                                    "category": "Source",
                                    "owner": "AWS",
                                    "provider": "S3",
                                    "version": "1"
                                },
                                "configuration": {"S3Bucket": "%s", "S3ObjectKey": "source.zip"},
                                "outputArtifacts": [{"name": "SourceOutput"}]
                            }]
                        }, {
                            "name": "Approve",
                            "actions": [{
                                "name": "ManualApproval",
                                "actionTypeId": {
                                    "category": "Approval",
                                    "owner": "AWS",
                                    "provider": "Manual",
                                    "version": "1"
                                }
                            }]
                        }]
                    }
                }
                """.formatted(PIPELINE, BUCKET)).then().statusCode(200);
        try {
            putSource("changed artifact");

            Response executions = await("an execution started by the source poller")
                    .atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                    .until(() -> post("ListPipelineExecutions", """
                            {"pipelineName": "%s"}
                            """.formatted(PIPELINE)),
                            listed -> listed.jsonPath().getList("pipelineExecutionSummaries").size() == 1);
            post("GetPipelineExecution", """
                    {"pipelineName": "%s", "pipelineExecutionId": "%s"}
                    """.formatted(PIPELINE,
                            executions.jsonPath().getString("pipelineExecutionSummaries[0].pipelineExecutionId")))
                    .then()
                    .statusCode(200)
                    .body("pipelineExecution.trigger.triggerType", equalTo("PollForSourceChanges"))
                    .body("pipelineExecution.trigger.triggerDetail", equalTo("s3://" + BUCKET + "/source.zip"));
        } finally {
            post("DeletePipeline", """
                    {"name": "%s"}
                    """.formatted(PIPELINE)).then().statusCode(200);
        }
    }

    private static void putSource(String body) {
        given()
                .contentType("application/octet-stream")
                .body(body.getBytes(StandardCharsets.UTF_8))
        .when()
                .put("/" + BUCKET + "/source.zip")
        .then()
                .statusCode(200);
    }

    private static Response post(String action, String body) {
        return given()
                .header("X-Amz-Target", TARGET + action)
                .contentType(CONTENT_TYPE)
                .body(body)
        .when()
                .post("/");
    }
}
