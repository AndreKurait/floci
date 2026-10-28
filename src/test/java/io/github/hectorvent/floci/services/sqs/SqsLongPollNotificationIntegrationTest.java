package io.github.hectorvent.floci.services.sqs;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SqsLongPollNotificationIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @Test
    void sdkLongPollsKeepSameNameQueuesIsolatedAcrossAccountsAndRegions() throws Exception {
        String name = "signal-" + UUID.randomUUID().toString().substring(0, 8);
        try (SqsClient first = client("111111111111", "us-east-1");
             SqsClient second = client("222222222222", "us-gov-west-1");
             ExecutorService consumers = Executors.newVirtualThreadPerTaskExecutor()) {
            String firstUrl = first.createQueue(request -> request.queueName(name)).queueUrl();
            String secondUrl = second.createQueue(request -> request.queueName(name)).queueUrl();
            try {
                Future<ReceiveMessageResponse> firstRead = consumers.submit(() -> receive(first, firstUrl));
                Future<ReceiveMessageResponse> secondRead = consumers.submit(() -> receive(second, secondUrl));
                first.sendMessage(request -> request.queueUrl(firstUrl).messageBody("first"));
                assertEquals("first", firstRead.get(3, TimeUnit.SECONDS).messages().getFirst().body());
                assertFalse(secondRead.isDone(), "A different queue's notification must not end this long poll");
                second.sendMessage(request -> request.queueUrl(secondUrl).messageBody("second"));
                assertEquals("second", secondRead.get(3, TimeUnit.SECONDS).messages().getFirst().body());
            } finally {
                first.deleteQueue(request -> request.queueUrl(firstUrl));
                second.deleteQueue(request -> request.queueUrl(secondUrl));
            }
        }
    }

    @Test
    void deletingAnInflightFifoMessageReleasesTheNextMessageInItsGroup() throws Exception {
        String name = "signal-" + UUID.randomUUID().toString().substring(0, 8) + ".fifo";
        try (SqsClient client = client("111111111111", "us-east-1");
             ExecutorService consumers = Executors.newVirtualThreadPerTaskExecutor()) {
            String url = client.createQueue(request -> request.queueName(name).attributes(Map.of(
                    QueueAttributeName.FIFO_QUEUE, "true",
                    QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))).queueUrl();
            try {
                client.sendMessage(request -> request.queueUrl(url).messageBody("first").messageGroupId("group"));
                client.sendMessage(request -> request.queueUrl(url).messageBody("second").messageGroupId("group"));
                ReceiveMessageResponse first = receive(client, url);
                assertEquals("first", first.messages().getFirst().body());
                Future<ReceiveMessageResponse> second = consumers.submit(() -> receive(client, url));
                client.deleteMessage(request -> request.queueUrl(url)
                        .receiptHandle(first.messages().getFirst().receiptHandle()));
                assertEquals("second", second.get(3, TimeUnit.SECONDS).messages().getFirst().body());
            } finally {
                client.deleteQueue(request -> request.queueUrl(url));
            }
        }
    }

    @Test
    void aDelayedMessageRemainsInvisibleUntilItsConfiguredDelayExpires() throws Exception {
        String name = "signal-" + UUID.randomUUID().toString().substring(0, 8);
        try (SqsClient client = client("111111111111", "us-east-1")) {
            String url = client.createQueue(request -> request.queueName(name)).queueUrl();
            try {
                client.sendMessage(request -> request.queueUrl(url).messageBody("delayed").delaySeconds(1));
                assertTrue(client.receiveMessage(request -> request.queueUrl(url).waitTimeSeconds(0))
                        .messages().isEmpty());
                assertEquals("delayed", receive(client, url).messages().getFirst().body());
            } finally {
                client.deleteQueue(request -> request.queueUrl(url));
            }
        }
    }

    private SqsClient client(String account, String region) {
        return SqsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }

    private static ReceiveMessageResponse receive(SqsClient client, String url) {
        return client.receiveMessage(request -> request.queueUrl(url).maxNumberOfMessages(1)
                .visibilityTimeout(30).waitTimeSeconds(20));
    }
}
