package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@QuarkusTest
class CloudFormationAsyncAcceptanceIntegrationTest {
    @InjectSpy
    SnsService sns;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void slowCreateReturnsItsStackIdWithoutCancellingOrRepeatingTheOperation() throws Exception {
        exercise(false, false);
    }

    @Test
    void slowUpdateReturnsTheSameStackIdAndCompletesOnce() throws Exception {
        exercise(true, false);
    }

    @Test
    void acceptedSlowCreateStillReportsItsActualAsynchronousFailure() throws Exception {
        exercise(false, true);
    }

    @Test
    void circularDependencyIsRefusedBeforeAnOperationIsAccepted() {
        String stack = "async-invalid-" + Long.toString(System.nanoTime(), 36);
        String template = """
                {"Resources":{"First":{"Type":"AWS::SNS::Topic","DependsOn":"Second"},
                "Second":{"Type":"AWS::SNS::Topic","DependsOn":"First"}}}
                """;
        cfn("CreateStack", stack).formParam("TemplateBody", template).post("/")
                .then().statusCode(400).body(containsString("ValidationError"));
        cfn("DescribeStacks", stack).post("/").then().statusCode(400);
    }

    private void exercise(boolean update, boolean fail) throws Exception {
        String stack = "async-accept-" + Long.toString(System.nanoTime(), 36);
        String topic = stack + "-slow";
        String initialId = null;
        if (update) {
            initialId = cfn("CreateStack", stack).formParam("TemplateBody", template(stack + "-base", null))
                    .post("/").then().statusCode(200).extract().xmlPath()
                    .getString("CreateStackResponse.CreateStackResult.StackId");
            awaitStatus(stack, "CREATE_COMPLETE");
        }
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        doAnswer(invocation -> {
            executions.incrementAndGet();
            entered.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test did not release the controlled provisioner");
            }
            if (fail) {
                throw new AwsException("InvalidParameter", "controlled provisioning failure", 400);
            }
            return invocation.callRealMethod();
        }).when(sns).createTopic(eq(topic), anyMap(), anyMap(), eq("us-east-1"));
        String action = update ? "UpdateStack" : "CreateStack";
        String desired = update ? template(stack + "-base", topic) : template(topic, null);
        CompletableFuture<Response> request = CompletableFuture.supplyAsync(() ->
                cfn(action, stack).formParam("TemplateBody", desired).post("/"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "provisioning did not start");
            Response accepted = request.get(5, TimeUnit.SECONDS);
            accepted.then().statusCode(200);
            String id = accepted.xmlPath().getString(action + "Response." + action + "Result.StackId");
            assertTrue(id.contains(":stack/" + stack + "/"), id);
            if (update) {
                assertEquals(initialId, id);
            }
            cfn("DescribeStacks", stack).post("/").then().statusCode(200)
                    .body(containsString(update ? "UPDATE_IN_PROGRESS" : "CREATE_IN_PROGRESS"));
            cfn(action, stack).formParam("TemplateBody", desired).post("/").then().statusCode(400)
                    .body(containsString(update ? "ValidationError" : "AlreadyExistsException"));
            assertEquals(1, executions.get(), "the accepted operation must not be submitted twice");
            release.countDown();
            awaitStatus(stack, fail ? "ROLLBACK_COMPLETE" : update ? "UPDATE_COMPLETE" : "CREATE_COMPLETE");
            assertEquals(1, executions.get());
            if (fail) {
                cfn("DescribeStackEvents", stack).post("/").then().statusCode(200)
                        .body(containsString("controlled provisioning failure"));
            } else {
                cfn("DescribeStackResources", stack).post("/").then().statusCode(200)
                        .body(containsString(topic));
            }
        } finally {
            release.countDown();
            request.get(10, TimeUnit.SECONDS);
            cfn("DeleteStack", stack).post("/").then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(stack);
        }
    }

    private static String template(String first, String second) {
        String additional = second == null ? "" : """
                ,"Second":{"Type":"AWS::SNS::Topic","Properties":{"TopicName":"%s"}}
                """.formatted(second);
        return """
                {"Resources":{"First":{"Type":"AWS::SNS::Topic","Properties":{"TopicName":"%s"}}%s}}
                """.formatted(first, additional);
    }

    private static void awaitStatus(String stack, String status) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                cfn("DescribeStacks", stack).post("/").then().statusCode(200)
                        .body(containsString("<StackStatus>" + status + "</StackStatus>")));
    }

    private static RequestSpecification cfn(String action, String stack) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261008/us-east-1/cloudformation/aws4_request")
                .formParam("Action", action).formParam("StackName", stack);
    }
}
