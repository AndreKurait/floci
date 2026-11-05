package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.S3IamEnforcementProfile;
import io.github.hectorvent.floci.testing.S3TestClients;
import io.github.hectorvent.floci.testing.TestAccounts;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class S3CrossAccountNotificationSdkIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<Executable> cleanup = new ArrayList<>();

    @Inject S3Service service;
    @Inject RegionResolver regions;

    @AfterEach
    void removeOwnedResourcesAndCloseClients() {
        try {
            assertAll(cleanup.reversed());
        } finally {
            cleanup.clear();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "us-east-1,us-west-2",
        "us-gov-west-1,us-gov-east-1",
        "cn-north-1,cn-northwest-1"
    })
    void foreignUploadsUseTheBucketsNotificationAccount(String workerRegion, String bucketRegion) throws Exception {
        String ownerAccount = TestAccounts.randomId();
        String writerAccount = TestAccounts.randomId();
        Region region = Region.of(bucketRegion);
        AccessKey ownerKey = createS3User(ownerAccount, region, "s3:*");
        AccessKey writerKey = createS3User(writerAccount, region, "s3:PutObject");
        S3Client owner = closeAfterTest(AwsTestClients.create(
                S3Client.builder().forcePathStyle(true), ownerKey.accessKeyId(), ownerKey.secretAccessKey(), region));
        S3Client writer = closeAfterTest(AwsTestClients.create(
                S3Client.builder().forcePathStyle(true), writerKey.accessKeyId(), writerKey.secretAccessKey(), region));
        SnsClient sns = closeAfterTest(AwsTestClients.create(SnsClient.builder(), ownerAccount, region));
        SqsClient sqs = closeAfterTest(AwsTestClients.create(SqsClient.builder(), ownerAccount, region));
        String bucket = "notification-owner-" + UUID.randomUUID();
        owner.createBucket(request -> request.bucket(bucket)
                .createBucketConfiguration(configuration -> configuration.locationConstraint(bucketRegion)));
        cleanup.add(() -> S3TestClients.deleteBucket(owner, bucket));
        String topic = sns.createTopic(request -> request.name(bucket)).topicArn();
        cleanup.add(() -> sns.deleteTopic(request -> request.topicArn(topic)));
        String queue = sqs.createQueue(request -> request.queueName(bucket)).queueUrl();
        cleanup.add(() -> sqs.deleteQueue(request -> request.queueUrl(queue)));
        String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queue)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        String partition = topic.split(":")[1];
        String bucketArn = "arn:" + partition + ":s3:::" + bucket;
        sns.setTopicAttributes(request -> request.topicArn(topic).attributeName("Policy").attributeValue("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"s3.amazonaws.com"},
                "Action":"sns:Publish","Resource":"%s","Condition":{"ArnEquals":{"aws:SourceArn":"%s"},
                "StringEquals":{"aws:SourceAccount":"%s"}}}]}""".formatted(topic, bucketArn, ownerAccount)));
        sqs.setQueueAttributes(request -> request.queueUrl(queue).attributes(Map.of(QueueAttributeName.POLICY, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"sns.amazonaws.com"},
                "Action":"sqs:SendMessage","Resource":"%s",
                "Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}""".formatted(queueArn, topic))));
        String subscription = sns.subscribe(request -> request.topicArn(topic).protocol("sqs")
                .endpoint(queueArn).returnSubscriptionArn(true)).subscriptionArn();
        sns.setSubscriptionAttributes(request -> request.subscriptionArn(subscription)
                .attributeName("RawMessageDelivery").attributeValue("true"));
        owner.putBucketNotificationConfiguration(request -> request.bucket(bucket)
                .notificationConfiguration(configuration -> configuration.topicConfigurations(destination ->
                        destination.id("bucket-owner").topicArn(topic).eventsWithStrings("s3:ObjectCreated:Put"))));
        assertEquals("s3:TestEvent", nextEvent(sqs, queue).path("Event").asText());
        owner.putObject(request -> request.bucket(bucket).key("owner.json"), RequestBody.fromString("owner"));
        assertEvent(nextEvent(sqs, queue), bucket, "owner.json", bucketRegion);

        owner.putBucketPolicy(request -> request.bucket(bucket).policy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"AWS":"arn:%s:iam::%s:root"},"Action":"s3:PutObject",
                "Resource":"%s/*"}]}""".formatted(partition, writerAccount, bucketArn)));
        writer.putObject(request -> request.bucket(bucket).key("sdk.json"), RequestBody.fromString("sdk"));
        assertEquals("sdk", owner.getObjectAsBytes(request -> request.bucket(bucket).key("sdk.json")).asUtf8String());
        assertEvent(nextEvent(sqs, queue), bucket, "sdk.json", bucketRegion);

        RequestScopes.runAs(writerAccount, workerRegion, () -> {
            service.putObject(bucket, "worker.json", "worker".getBytes(StandardCharsets.UTF_8),
                    "application/json", Map.of());
            assertEquals(writerAccount, regions.getAccountId());
            assertEquals(workerRegion, regions.getRegion());
        });
        assertEquals("worker", owner.getObjectAsBytes(request -> request.bucket(bucket).key("worker.json"))
                .asUtf8String());
        assertEvent(nextEvent(sqs, queue), bucket, "worker.json", bucketRegion);

        owner.deleteBucketPolicy(request -> request.bucket(bucket));
        S3Exception denied = assertThrows(S3Exception.class, () -> writer.putObject(
                request -> request.bucket(bucket).key("denied.json"), RequestBody.fromString("denied")));
        assertEquals("AccessDenied", denied.awsErrorDetails().errorCode());
        assertTrue(sqs.receiveMessage(request -> request.queueUrl(queue).waitTimeSeconds(1)).messages().isEmpty());
    }

    private AccessKey createS3User(String account, Region region, String action) {
        IamClient iam = closeAfterTest(AwsTestClients.create(IamClient.builder(), account, region));
        String user = "notification-writer-" + UUID.randomUUID();
        iam.createUser(request -> request.userName(user));
        cleanup.add(() -> iam.deleteUser(request -> request.userName(user)));
        AccessKey key = iam.createAccessKey(request -> request.userName(user)).accessKey();
        cleanup.add(() -> iam.deleteAccessKey(request -> request.userName(user).accessKeyId(key.accessKeyId())));
        iam.putUserPolicy(request -> request.userName(user).policyName("S3").policyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"%s","Resource":"*"}]}
                """.formatted(action)));
        cleanup.add(() -> iam.deleteUserPolicy(request -> request.userName(user).policyName("S3")));
        return key;
    }

    private <T extends AutoCloseable> T closeAfterTest(T client) {
        cleanup.add(client::close);
        return client;
    }

    private static void assertEvent(JsonNode message, String bucket, String key, String region) {
        assertEquals(1, message.path("Records").size());
        JsonNode event = message.path("Records").get(0);
        assertEquals("ObjectCreated:Put", event.path("eventName").asText());
        assertEquals(region, event.path("awsRegion").asText());
        assertEquals(bucket, event.path("s3").path("bucket").path("name").asText());
        assertEquals("bucket-owner", event.path("s3").path("configurationId").asText());
        assertEquals(key, URLDecoder.decode(
                event.path("s3").path("object").path("key").asText(), StandardCharsets.UTF_8));
    }

    private static JsonNode nextEvent(SqsClient sqs, String queue) throws Exception {
        AtomicReference<Message> found = new AtomicReference<>();
        await().alias("S3 event in the bucket owner's topic").atMost(Duration.ofSeconds(10)).until(() -> {
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
}
