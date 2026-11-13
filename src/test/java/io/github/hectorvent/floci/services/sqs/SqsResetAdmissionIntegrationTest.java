package io.github.hectorvent.floci.services.sqs;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.net.URI;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SqsResetAdmissionIntegrationTest {
    @Inject
    SqsService service;

    @TestHTTPResource
    URI endpoint;

    @Test
    void sdkRejectsNewWorkDuringResetAndResumesAfterwards() {
        String name = "reset-sdk-" + UUID.randomUUID();
        try (SqsClient client = SqsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("000000000000", "test")))
                .overrideConfiguration(configuration -> configuration.retryPolicy(RetryPolicy.builder().numRetries(0).build()))
                .build()) {
            try {
                service.beforeReset();
                SqsException failure = assertThrows(SqsException.class,
                        () -> client.createQueue(request -> request.queueName(name)));
                assertEquals(503, failure.statusCode());
                assertEquals("ServiceUnavailable", failure.awsErrorDetails().errorCode());
                assertTrue(client.listQueues(request -> request.queueNamePrefix(name)).queueUrls().isEmpty());
            } finally {
                service.afterReset();
                for (String created : client.listQueues(request -> request.queueNamePrefix(name)).queueUrls()) {
                    client.deleteQueue(request -> request.queueUrl(created));
                }
            }
            String url = client.createQueue(request -> request.queueName(name)).queueUrl();
            try {
                client.sendMessage(request -> request.queueUrl(url).messageBody("after-reset"));
                assertEquals("after-reset", client.receiveMessage(request -> request.queueUrl(url)).messages().getFirst().body());
            } finally {
                client.deleteQueue(request -> request.queueUrl(url));
            }
        }
    }

    @Test
    void queryProtocolRejectsNewWorkBeforeMutation() {
        String name = "reset-query-" + UUID.randomUUID();
        try {
            service.beforeReset();
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateQueue").formParam("QueueName", name)
                    .when().post("/").then().statusCode(503)
                    .body(containsString("<Code>ServiceUnavailable</Code>"));
            assertTrue(service.listQueues(name, "us-east-1").isEmpty());
        } finally {
            service.afterReset();
            service.listQueues(name, "us-east-1").forEach(queue -> service.deleteQueue(queue.getQueueUrl(), "us-east-1"));
        }
    }
}
