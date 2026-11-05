package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SnsException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SnsSqsConfirmationSdkIntegrationTest {
    private static final String TOPIC_OWNER = "861122223333";
    private static final String QUEUE_OWNER = "864455556666";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVENT = "{\"kind\":\"inventory\"}";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({
        "us-east-1,us-west-2",
        "us-gov-west-1,us-gov-east-1",
        "cn-north-1,cn-northwest-1"
    })
    void recipientMustConfirmBeforeFilteredCrossAccountDelivery(String topicRegion, String queueRegion) throws Exception {
        try (SnsClient owner = sns(TOPIC_OWNER, topicRegion);
             SqsClient recipient = sqs(QUEUE_OWNER, queueRegion);
             SnsClient anonymous = anonymous()) {
            String topic = topic(owner, TOPIC_OWNER, topicRegion);
            String queue = queue(recipient, QUEUE_OWNER, queueRegion);
            String queueArn = queueArn(recipient, queue);
            allow(recipient, queue, queueArn, topic, TOPIC_OWNER);
            String subscription = owner.subscribe(request -> request.topicArn(topic).protocol("sqs")
                    .endpoint(queueArn).returnSubscriptionArn(true).attributes(Map.of(
                            "FilterPolicy", "{\"kind\":[\"inventory\"]}", "FilterPolicyScope", "MessageBody",
                            "RawMessageDelivery", "true"))).subscriptionArn();
            assertEquals("true", owner.getSubscriptionAttributes(request -> request.subscriptionArn(subscription))
                    .attributes().get("PendingConfirmation"));
            assertFalse(owner.getSubscriptionAttributes(request -> request.subscriptionArn(subscription))
                    .attributes().containsKey("ConfirmationToken"));

            Message message = recipient.receiveMessage(request -> request.queueUrl(queue)).messages().getFirst();
            JsonNode confirmation = JSON.readTree(message.body());
            assertEquals("SubscriptionConfirmation", confirmation.path("Type").asText());
            assertEquals(topic, confirmation.path("TopicArn").asText());
            assertTrue(confirmation.hasNonNull("Signature"));
            String token = confirmation.path("Token").asText();
            assertFalse(token.isBlank());
            recipient.deleteMessage(request -> request.queueUrl(queue).receiptHandle(message.receiptHandle()));
            owner.publish(request -> request.topicArn(topic).message(EVENT));
            assertTrue(recipient.receiveMessage(request -> request.queueUrl(queue)).messages().isEmpty());

            SnsException badToken = assertThrows(SnsException.class, () -> anonymous.confirmSubscription(
                    request -> request.topicArn(topic).token("invalid")));
            assertEquals("AuthorizationError", badToken.awsErrorDetails().errorCode());
            assertThrows(SnsException.class, () -> anonymous.confirmSubscription(
                    request -> request.topicArn(topic).token(token).authenticateOnUnsubscribe("true")));
            assertThrows(SnsException.class, () -> owner.setSubscriptionAttributes(request ->
                    request.subscriptionArn(subscription).attributeName("PendingConfirmation").attributeValue("false")));
            assertEquals("true", owner.getSubscriptionAttributes(request -> request.subscriptionArn(subscription))
                    .attributes().get("PendingConfirmation"));
            assertEquals(subscription, anonymous.confirmSubscription(request ->
                    request.topicArn(topic).token(token)).subscriptionArn());

            owner.publish(request -> request.topicArn(topic).message("{\"kind\":\"other\"}"));
            assertTrue(recipient.receiveMessage(request -> request.queueUrl(queue)).messages().isEmpty());
            owner.publish(request -> request.topicArn(topic).message(EVENT));
            Message delivered = recipient.receiveMessage(request -> request.queueUrl(queue)).messages().getFirst();
            assertEquals(EVENT, delivered.body());
            recipient.deleteMessage(request -> request.queueUrl(queue).receiptHandle(delivered.receiptHandle()));

            allow(recipient, queue, queueArn, topic, QUEUE_OWNER);
            owner.publish(request -> request.topicArn(topic).message(EVENT));
            assertTrue(recipient.receiveMessage(request -> request.queueUrl(queue)).messages().isEmpty(),
                    "Confirmation must not bypass the queue's SourceAccount condition.");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queueOwnersSubscriptionIsAutomaticEvenWhenTopicHasAnotherOwner(boolean foreignTopic) {
        String topicAccount = foreignTopic ? TOPIC_OWNER : QUEUE_OWNER;
        try (SnsClient owner = sns(topicAccount, "us-east-1");
             SnsClient subscriber = sns(QUEUE_OWNER, "us-east-1");
             SqsClient recipient = sqs(QUEUE_OWNER, "us-west-2")) {
            String topic = topic(owner, topicAccount, "us-east-1");
            String queue = queue(recipient, QUEUE_OWNER, "us-west-2");
            String queueArn = queueArn(recipient, queue);
            owner.setTopicAttributes(request -> request.topicArn(topic).attributeName("Policy").attributeValue("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"%s"},
                    "Action":"sns:Subscribe","Resource":"%s"}]}""".formatted(QUEUE_OWNER, topic)));
            allow(recipient, queue, queueArn, topic, topicAccount);
            String subscription = subscriber.subscribe(request -> request.topicArn(topic).protocol("sqs")
                    .endpoint(queueArn).attributes(Map.of("RawMessageDelivery", "true"))).subscriptionArn();
            assertTrue(subscription.startsWith(topic + ":"));
            assertEquals("false", subscriber.getSubscriptionAttributes(request -> request.subscriptionArn(subscription))
                    .attributes().get("PendingConfirmation"));
            assertTrue(recipient.receiveMessage(request -> request.queueUrl(queue)).messages().isEmpty());
            owner.publish(request -> request.topicArn(topic).message(EVENT));
            assertEquals(EVENT, recipient.receiveMessage(request -> request.queueUrl(queue)).messages().getFirst().body());
            owner.deleteTopic(request -> request.topicArn(topic));
            assertFalse(subscriber.listSubscriptions().subscriptions().stream()
                    .anyMatch(item -> subscription.equals(item.subscriptionArn())));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"query-get", "json"})
    void confirmationLinksAndJsonUseTheSameTokenAndOwnerRules(String protocol) throws Exception {
        try (SnsClient owner = sns(TOPIC_OWNER, "us-east-2"); SqsClient recipient = sqs(QUEUE_OWNER, "us-east-1")) {
            String topic = topic(owner, TOPIC_OWNER, "us-east-2");
            String queue = queue(recipient, QUEUE_OWNER, "us-east-1");
            String queueArn = queueArn(recipient, queue);
            allow(recipient, queue, queueArn, topic, TOPIC_OWNER);
            String pending = owner.subscribe(request -> request.topicArn(topic).protocol("sqs")
                    .endpoint(queueArn)).subscriptionArn();
            assertEquals("pending confirmation", pending);
            Message message = recipient.receiveMessage(request -> request.queueUrl(queue)).messages().getFirst();
            JsonNode confirmation = JSON.readTree(message.body());
            String token = confirmation.path("Token").asText();
            String actual = owner.listSubscriptionsByTopic(request -> request.topicArn(topic))
                    .subscriptions().getFirst().subscriptionArn();
            if ("query-get".equals(protocol)) {
                URI link = URI.create(confirmation.path("SubscribeURL").asText());
                given().baseUri(endpoint.toString()).queryParam("Action", "ConfirmSubscription")
                        .queryParam("TopicArn", topic).queryParam("Token", "wrong")
                        .get("/").then().statusCode(403);
                given().baseUri(endpoint.toString()).get("/?" + link.getRawQuery())
                        .then().statusCode(200)
                        .body("ConfirmSubscriptionResponse.ConfirmSubscriptionResult.SubscriptionArn", equalTo(actual));
            } else {
                given().baseUri(endpoint.toString()).contentType("application/x-amz-json-1.0")
                        .header("X-Amz-Target", "SNS_20100331.ConfirmSubscription")
                        .body(Map.of("TopicArn", topic, "Token", token, "AuthenticateOnUnsubscribe", "true"))
                        .post("/").then().statusCode(403);
                given().baseUri(endpoint.toString()).contentType("application/x-amz-json-1.0")
                        .header("X-Amz-Target", "SNS_20100331.ConfirmSubscription")
                        .body(Map.of("TopicArn", topic, "Token", token))
                        .post("/").then().statusCode(200).body("SubscriptionArn", equalTo(actual));
            }
            assertEquals("false", owner.getSubscriptionAttributes(request -> request.subscriptionArn(actual))
                    .attributes().get("PendingConfirmation"));
        }
    }

    @Test
    void queuePolicyMustAdmitTheConfirmationAndASubscribeRetryCanRecover() {
        try (SnsClient owner = sns(TOPIC_OWNER, "us-east-1"); SqsClient recipient = sqs(QUEUE_OWNER, "us-west-2")) {
            String topic = topic(owner, TOPIC_OWNER, "us-east-1");
            String queue = queue(recipient, QUEUE_OWNER, "us-west-2");
            String queueArn = queueArn(recipient, queue);
            String pending = owner.subscribe(request -> request.topicArn(topic).protocol("sqs")
                    .endpoint(queueArn).returnSubscriptionArn(true)).subscriptionArn();
            assertTrue(recipient.receiveMessage(request -> request.queueUrl(queue)).messages().isEmpty());
            allow(recipient, queue, queueArn, topic, TOPIC_OWNER);
            assertEquals(pending, owner.subscribe(request -> request.topicArn(topic).protocol("sqs")
                    .endpoint(queueArn).returnSubscriptionArn(true)).subscriptionArn());
            assertEquals(1, recipient.receiveMessage(request -> request.queueUrl(queue)).messages().size());
        }
    }

    @Test
    void anIamSenderNeedsTheDestinationQueuesGrantEvenWithItsOwnSendPermission() {
        String user = "queue-sender-" + UUID.randomUUID();
        String authorization = EnforcementFixtures.auth(TOPIC_OWNER, "iam");
        UserCredentials keys = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), authorization, user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, user, "send", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sqs:SendMessage","Resource":"*"}]}
                """);
        try (SqsClient sender = sqs(AwsBasicCredentials.create(keys.accessKeyId(), keys.secretAccessKey()), "us-east-1");
             SqsClient recipient = sqs(QUEUE_OWNER, "us-east-1")) {
            String queue = queue(recipient, QUEUE_OWNER, "us-east-1");
            String arn = queueArn(recipient, queue);
            SqsException denied = assertThrows(SqsException.class, () -> sender.sendMessage(
                    request -> request.queueUrl(queue).messageBody("denied")));
            assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
            assertTrue(denied.awsErrorDetails().errorMessage().contains("sqs:SendMessage"));
            recipient.setQueueAttributes(request -> request.queueUrl(queue).attributes(Map.of(
                    QueueAttributeName.POLICY, """
                            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                            "Principal":{"AWS":"arn:aws:iam::%s:user/%s"},
                            "Action":"sqs:SendMessage","Resource":"%s"}]}""".formatted(TOPIC_OWNER, user, arn))));
            sender.sendMessage(request -> request.queueUrl(queue).messageBody("allowed"));
            assertEquals("allowed", recipient.receiveMessage(request -> request.queueUrl(queue))
                    .messages().getFirst().body());
        }
    }

    private String topic(SnsClient client, String account, String region) {
        String arn = client.createTopic(request -> request.name("confirmation-" + UUID.randomUUID())).topicArn();
        cleanup.register(() -> {
            try (SnsClient owner = sns(account, region)) {
                try {
                    owner.deleteTopic(request -> request.topicArn(arn));
                } catch (SnsException expected) {
                    if (expected.statusCode() != 404) {
                        throw expected;
                    }
                }
            }
        });
        return arn;
    }

    private String queue(SqsClient client, String account, String region) {
        String url = client.createQueue(request -> request.queueName("confirmation-" + UUID.randomUUID())).queueUrl();
        cleanup.register(() -> {
            try (SqsClient owner = sqs(account, region)) {
                owner.deleteQueue(request -> request.queueUrl(url));
            }
        });
        return url;
    }

    private static String queueArn(SqsClient client, String queue) {
        return client.getQueueAttributes(request -> request.queueUrl(queue).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    private static void allow(SqsClient client, String queue, String queueArn, String topic, String sourceAccount) {
        client.setQueueAttributes(request -> request.queueUrl(queue).attributes(Map.of(QueueAttributeName.POLICY, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"sns.amazonaws.com"},
                "Action":"sqs:SendMessage","Resource":"%s","Condition":{"ArnEquals":{"aws:SourceArn":"%s"},
                "StringEquals":{"aws:SourceAccount":"%s"}}}]}""".formatted(queueArn, topic, sourceAccount))));
    }

    private SnsClient sns(String account, String region) {
        return SnsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private SqsClient sqs(String account, String region) {
        return sqs(AwsBasicCredentials.create(account, "test"), region);
    }

    private SqsClient sqs(AwsBasicCredentials credentials, String region) {
        return SqsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private SnsClient anonymous() {
        return SnsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
