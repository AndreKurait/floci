package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.FilterRule;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class S3NotificationFilterSdkIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(830033340000L);
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void encodedFiltersDeliverDecodedObjectKeysThroughSns(String region) throws Exception {
        URI endpoint = URI.create("http://localhost:" + RestAssured.port);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(Long.toString(ACCOUNTS.incrementAndGet()), "test"));
        String name = "notification-filter-" + UUID.randomUUID();
        try (S3Client s3 = S3Client.builder().endpointOverride(endpoint).region(Region.of(region))
                     .credentialsProvider(credentials).forcePathStyle(true)
                     .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             SnsClient sns = SnsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                     .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             SqsClient sqs = SqsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                     .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             OwnedResources owned = new OwnedResources()) {
            s3.createBucket(request -> {
                request.bucket(name);
                if (!"us-east-1".equals(region)) {
                    request.createBucketConfiguration(configuration -> configuration.locationConstraint(region));
                }
            });
            owned.add(() -> {
                for (S3Object object : s3.listObjectsV2(request -> request.bucket(name)).contents()) {
                    s3.deleteObject(request -> request.bucket(name).key(object.key()));
                }
                s3.deleteBucket(request -> request.bucket(name));
            });
            String queue = sqs.createQueue(request -> request.queueName(name)).queueUrl();
            owned.add(() -> sqs.deleteQueue(request -> request.queueUrl(queue)));
            String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queue)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
            String topic = sns.createTopic(request -> request.name(name)).topicArn();
            owned.add(() -> sns.deleteTopic(request -> request.topicArn(topic)));
            String subscription = sns.subscribe(request -> request.topicArn(topic).protocol("sqs")
                    .endpoint(queueArn).returnSubscriptionArn(true)).subscriptionArn();
            sns.setSubscriptionAttributes(request -> request.subscriptionArn(subscription)
                    .attributeName("RawMessageDelivery").attributeValue("true"));

            List<Example> examples = List.of(
                    new Example("AWS%3AApplication/", ".json", "AWS:Application/node.json",
                            "AWS%3AApplication/node.json"),
                    new Example("reports+from+nodes/", "%2Bresult.json", "reports from nodes/a+result.json",
                            "reports+from+nodes/a+result.json"),
                    new Example("r%C3%A9sum%C3%A9/%25/", ".json", "résumé/%/node.json",
                            "résumé/%25/node.json"),
                    new Example("literal%253A/", "%252B.json", "literal%3A/node%2B.json",
                            "literal%253A/node%252B.json"));
            for (Example example : examples) {
                s3.putBucketNotificationConfiguration(request -> request.bucket(name)
                        .notificationConfiguration(configuration -> configuration.topicConfigurations(topicConfig ->
                                topicConfig.id("encoded-filter").topicArn(topic).eventsWithStrings("s3:ObjectCreated:Put")
                                        .filter(filter -> filter.key(key -> key.filterRules(
                                                FilterRule.builder().name("prefix").value(example.prefix()).build(),
                                                FilterRule.builder().name("suffix").value(example.suffix()).build()))))));
                List<FilterRule> roundTrip = s3.getBucketNotificationConfiguration(request -> request.bucket(name))
                        .topicConfigurations().getFirst().filter().key().filterRules();
                assertEquals(List.of(example.prefix(), example.suffix()),
                        roundTrip.stream().map(FilterRule::value).toList());
                assertEquals("s3:TestEvent", nextEvent(sqs, queue).path("Event").asText());

                s3.putObject(request -> request.bucket(name).key(example.rejected()), RequestBody.fromString("wrong prefix"));
                s3.putObject(request -> request.bucket(name).key(example.accepted() + "-wrong"),
                        RequestBody.fromString("wrong suffix"));
                PutObjectResponse uploaded = s3.putObject(request -> request.bucket(name).key(example.accepted()),
                        RequestBody.fromString("matching object"));
                JsonNode event = nextEvent(sqs, queue).path("Records").get(0);
                assertEquals("ObjectCreated:Put", event.path("eventName").asText());
                assertEquals("encoded-filter", event.path("s3").path("configurationId").asText());
                assertEquals(example.accepted(), URLDecoder.decode(
                        event.path("s3").path("object").path("key").asText(), StandardCharsets.UTF_8));
                assertEquals(uploaded.eTag().replace("\"", ""),
                        event.path("s3").path("object").path("eTag").asText());
                // S3 hands an event to the topic, and the topic to the queue, on the upload's own request:
                // what the rejected keys produced was in the queue before their uploads answered.
                assertTrue(sqs.receiveMessage(request -> request.queueUrl(queue).waitTimeSeconds(0))
                        .messages().isEmpty(), "Rejected keys must not produce a notification.");
            }
        }
    }

    private static JsonNode nextEvent(SqsClient sqs, String queue) throws Exception {
        AtomicReference<Message> found = new AtomicReference<>();
        await().alias("S3 notification after matching the decoded filter")
                .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(10)).until(() -> {
                    List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queue)
                            .maxNumberOfMessages(1).waitTimeSeconds(1)).messages();
                    if (messages.isEmpty()) {
                        return false;
                    }
                    found.set(messages.getFirst());
                    return true;
                });
        Message message = found.get();
        sqs.deleteMessage(request -> request.queueUrl(queue).receiptHandle(message.receiptHandle()));
        return JSON.readTree(message.body());
    }

    private record Example(String prefix, String suffix, String accepted, String rejected) {}

    private static final class OwnedResources implements AutoCloseable {
        private final List<Executable> cleanup = new ArrayList<>();

        void add(Executable action) {
            cleanup.addFirst(action);
        }

        @Override
        public void close() {
            assertAll(cleanup);
        }
    }
}
