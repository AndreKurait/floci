package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;
import software.amazon.awssdk.services.sns.model.PublishBatchResponse;
import software.amazon.awssdk.services.sns.model.SnsException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publish to another account's topic, as AWS serves it: the topic is the one the ARN's account
 * owns, the caller needs that topic's access policy as well as its own permission, and the topic
 * owner's subscriptions receive the message. The SDK clients use SNS's Query protocol, the one
 * boto3 and aws-sdk-go send. Publishers are IAM users: a bare account-id key is Floci's
 * account-root stand-in, which IAM enforcement does not evaluate.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SnsCrossAccountPublishSdkIntegrationTest {
    private static final String REGION = "eu-west-1";
    private static final String TOPIC_OWNER = "871122223333";
    private static final String PUBLISHER = "874455556666";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PUBLISH_ALLOWED = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sns:Publish","Resource":"*"}]}""";
    private static final String NOTHING_ALLOWED = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sqs:ListQueues","Resource":"*"}]}""";
    private static final String EVENT = "{\"orderId\":\"o-1\"}";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTopicPolicyGrantAdmitsAnotherAccountsPublishAndTheOwnersSubscriberReceivesIt(boolean batch)
            throws Exception {
        try (SnsClient owner = sns(TOPIC_OWNER); SnsClient publisher = publisher(PUBLISH_ALLOWED);
             SqsClient recipient = sqs(TOPIC_OWNER)) {
            String topic = topic(owner);
            String queue = subscribedQueue(owner, recipient, topic);
            grantPublish(owner, topic, "\"AWS\":\"arn:aws:iam::" + PUBLISHER + ":root\"");

            publish(publisher, topic, batch);

            Message delivered = recipient.receiveMessage(request -> request.queueUrl(queue)).messages().getFirst();
            JsonNode notification = JSON.readTree(delivered.body());
            assertEquals("Notification", notification.path("Type").asText());
            assertEquals(topic, notification.path("TopicArn").asText());
            assertEquals(EVENT, notification.path("Message").asText());
            assertEquals("String.Array", notification.path("MessageAttributes").path("eventType").path("Type").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void withoutTheTopicsGrantAnotherAccountsPublishIsRefusedAndNothingIsDelivered(boolean batch) {
        try (SnsClient owner = sns(TOPIC_OWNER); SnsClient publisher = publisher(PUBLISH_ALLOWED);
             SqsClient recipient = sqs(TOPIC_OWNER)) {
            String topic = topic(owner);
            String queue = subscribedQueue(owner, recipient, topic);

            SnsException refused = assertThrows(SnsException.class, () -> publish(publisher, topic, batch));
            assertEquals(403, refused.statusCode());
            assertEquals("AuthorizationError", refused.awsErrorDetails().errorCode());

            grantPublish(owner, topic, "\"AWS\":\"arn:aws:iam::" + TOPIC_OWNER + ":root\"");
            assertThrows(SnsException.class, () -> publish(publisher, topic, batch));
            assertTrue(recipient.receiveMessage(request -> request.queueUrl(queue)).messages().isEmpty());
        }
    }

    @Test
    void theTopicsGrantDoesNotReplaceTheCallersOwnPermission() {
        try (SnsClient owner = sns(TOPIC_OWNER); SnsClient publisher = publisher(NOTHING_ALLOWED);
             SqsClient recipient = sqs(TOPIC_OWNER)) {
            String topic = topic(owner);
            String queue = subscribedQueue(owner, recipient, topic);
            grantPublish(owner, topic, "\"AWS\":\"arn:aws:iam::" + PUBLISHER + ":root\"");

            SnsException refused = assertThrows(SnsException.class, () -> publish(publisher, topic, false));
            assertEquals(403, refused.statusCode());
            assertTrue(recipient.receiveMessage(request -> request.queueUrl(queue)).messages().isEmpty());
        }
    }

    @Test
    void aForeignArnNeverSelectsTheCallersOwnTopicOfTheSameName() throws Exception {
        try (SnsClient publisher = publisher(PUBLISH_ALLOWED); SnsClient root = sns(PUBLISHER);
             SqsClient own = sqs(PUBLISHER)) {
            String name = "cross-account-" + UUID.randomUUID();
            String ownTopic = root.createTopic(request -> request.name(name)).topicArn();
            cleanup.register(() -> {
                try (SnsClient client = sns(PUBLISHER)) {
                    client.deleteTopic(request -> request.topicArn(ownTopic));
                }
            });
            String queue = subscribedQueue(root, own, ownTopic, PUBLISHER);
            String foreign = "arn:aws:sns:" + REGION + ":" + TOPIC_OWNER + ":" + name;
            SnsException missing = assertThrows(SnsException.class,
                    () -> publisher.publish(request -> request.topicArn(foreign).message(EVENT)));
            assertTrue(missing.statusCode() >= 400 && missing.statusCode() < 500, missing.toString());
            assertTrue(own.receiveMessage(request -> request.queueUrl(queue)).messages().isEmpty());
        }
    }

    private void publish(SnsClient publisher, String topic, boolean batch) {
        Map<String, MessageAttributeValue> attributes = Map.of("eventType", MessageAttributeValue.builder()
                .dataType("String.Array").stringValue("[\"ORDER_CREATED\"]").build());
        if (!batch) {
            assertFalse(publisher.publish(request -> request.topicArn(topic).message(EVENT)
                    .messageAttributes(attributes)).messageId().isBlank());
            return;
        }
        PublishBatchResponse response = publisher.publishBatch(request -> request.topicArn(topic)
                .publishBatchRequestEntries(List.of(PublishBatchRequestEntry.builder().id("e1").message(EVENT)
                        .messageAttributes(attributes).build())));
        assertTrue(response.failed().isEmpty(), response.failed().toString());
        assertEquals(1, response.successful().size());
    }

    private static void grantPublish(SnsClient owner, String topic, String principal) {
        owner.setTopicAttributes(request -> request.topicArn(topic).attributeName("Policy").attributeValue("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{%s},
                "Action":"sns:Publish","Resource":"%s"}]}""".formatted(principal, topic)));
    }

    private String topic(SnsClient owner) {
        String arn = owner.createTopic(request -> request.name("cross-account-" + UUID.randomUUID())).topicArn();
        cleanup.register(() -> {
            try (SnsClient client = sns(TOPIC_OWNER)) {
                client.deleteTopic(request -> request.topicArn(arn));
            }
        });
        return arn;
    }

    private String subscribedQueue(SnsClient owner, SqsClient recipient, String topic) {
        return subscribedQueue(owner, recipient, topic, TOPIC_OWNER);
    }

    /** The topic owner's own queue, admitting the topic's deliveries, subscribed with an eventType filter. */
    private String subscribedQueue(SnsClient owner, SqsClient recipient, String topic, String account) {
        String queue = recipient.createQueue(request -> request.queueName("cross-account-" + UUID.randomUUID()))
                .queueUrl();
        cleanup.register(() -> {
            try (SqsClient client = sqs(account)) {
                client.deleteQueue(request -> request.queueUrl(queue));
            }
        });
        String queueArn = recipient.getQueueAttributes(request -> request.queueUrl(queue)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        recipient.setQueueAttributes(request -> request.queueUrl(queue).attributes(Map.of(QueueAttributeName.POLICY, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"sns.amazonaws.com"},
                "Action":"sqs:SendMessage","Resource":"%s","Condition":{"ArnEquals":{"aws:SourceArn":"%s"},
                "StringEquals":{"aws:SourceAccount":"%s"}}}]}""".formatted(queueArn, topic, account))));
        String subscription = owner.subscribe(request -> request.topicArn(topic).protocol("sqs").endpoint(queueArn)
                .attributes(Map.of("FilterPolicy", "{\"eventType\":[\"ORDER_CREATED\"]}")))
                .subscriptionArn();
        assertTrue(subscription.startsWith(topic + ":"), subscription);
        return queue;
    }

    /** An SNS client for a new IAM user of PUBLISHER holding {@code policy} inline. */
    private SnsClient publisher(String policy) {
        String user = "publisher-" + UUID.randomUUID();
        String authorization = EnforcementFixtures.auth(PUBLISHER, "iam");
        UserCredentials keys = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), authorization, user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, user, "inline", policy);
        return SnsClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(keys.accessKeyId(), keys.secretAccessKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private SnsClient sns(String account) {
        return SnsClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private SqsClient sqs(String account) {
        return SqsClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
