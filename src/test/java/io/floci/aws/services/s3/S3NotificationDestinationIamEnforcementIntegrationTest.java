package io.floci.aws.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.sns.SnsService;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.services.sqs.model.Message;
import io.floci.aws.testing.IamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3NotificationDestinationIamEnforcementIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String BUCKET_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "000000000002";

    @Inject SqsService sqsService;
    @InjectSpy SnsService snsService;
    @Inject ObjectMapper objectMapper;

    @TestHTTPResource("/")
    URI endpoint;

    private String bucket;

    @BeforeEach
    void createBucket() {
        bucket = "notification-enforced-" + UUID.randomUUID().toString().substring(0, 8);
        given().header("Authorization", authorization(BUCKET_ACCOUNT, "s3"))
                .when().put("/" + bucket).then().statusCode(200);
    }

    @AfterEach
    void removeBucket() {
        given().header("Authorization", authorization(BUCKET_ACCOUNT, "s3"))
                .when().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void crossAccountQueueIsValidatedWithoutReceivingTestMessage() throws Exception {
        String otherQueueName = "notification-other-" + UUID.randomUUID().toString().substring(0, 8);
        String ownQueueName = "notification-own-" + UUID.randomUUID().toString().substring(0, 8);
        String otherQueueUrl = createQueue(OTHER_ACCOUNT, otherQueueName);
        String ownQueueUrl = createQueue(BUCKET_ACCOUNT, ownQueueName);
        String otherQueueArn = queueArn(OTHER_ACCOUNT, otherQueueName);
        String ownQueueArn = queueArn(BUCKET_ACCOUNT, ownQueueName);
        try {
            putNotification(otherQueueArn).statusCode(200);
            assertTrue(sqsService.receiveMessage(otherQueueUrl, 1, 0, 0, REGION).isEmpty());
            getNotification().body(containsString(otherQueueArn));

            String missingQueueArn = queueArn(OTHER_ACCOUNT, "notification-missing-" + UUID.randomUUID());
            putNotification(missingQueueArn).statusCode(400)
                    .body(containsString("The destination queue does not exist"));
            getNotification().body(containsString(otherQueueArn))
                    .body(not(containsString(missingQueueArn)));
            assertTrue(sqsService.receiveMessage(otherQueueUrl, 1, 0, 0, REGION).isEmpty());

            putNotification(ownQueueArn).statusCode(200);
            List<Message> ownMessages = sqsService.receiveMessage(ownQueueUrl, 1, 0, 0, REGION);
            assertFalse(ownMessages.isEmpty());
            JsonNode testEvent = objectMapper.readTree(ownMessages.getFirst().getBody());
            assertEquals("s3:TestEvent", testEvent.path("Event").asText());
            assertFalse(testEvent.has("Records"));
            assertTrue(sqsService.receiveMessage(otherQueueUrl, 1, 0, 0, REGION).isEmpty());
        } finally {
            deleteQueue(BUCKET_ACCOUNT, ownQueueUrl);
            deleteQueue(OTHER_ACCOUNT, otherQueueUrl);
        }
    }

    @Test
    void crossAccountTopicIsValidatedWithoutPublishingTestMessage() {
        String otherTopicName = "notification-other-" + UUID.randomUUID().toString().substring(0, 8);
        String ownTopicName = "notification-own-" + UUID.randomUUID().toString().substring(0, 8);
        String otherTopicArn = createTopic(OTHER_ACCOUNT, otherTopicName);
        String ownTopicArn = createTopic(BUCKET_ACCOUNT, ownTopicName);
        try {
            assertTrue(snsService.topicExists(otherTopicArn, REGION));
            putTopicNotification(otherTopicArn).statusCode(200);
            verify(snsService, never()).publish(eq(otherTopicArn), isNull(), anyString(),
                    eq("Amazon S3 Notification"), eq(REGION));
            getNotification().body(containsString(otherTopicArn));

            String missingTopicArn = topicArn(OTHER_ACCOUNT, "notification-missing-" + UUID.randomUUID());
            putTopicNotification(missingTopicArn).statusCode(400)
                    .body(containsString("The destination topic does not exist"));
            getNotification().body(containsString(otherTopicArn))
                    .body(not(containsString(missingTopicArn)));

            putTopicNotification(ownTopicArn).statusCode(200);
            verify(snsService).publish(eq(ownTopicArn), isNull(), anyString(),
                    eq("Amazon S3 Notification"), eq(REGION));
        } finally {
            deleteTopic(BUCKET_ACCOUNT, ownTopicArn);
            deleteTopic(OTHER_ACCOUNT, otherTopicArn);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "principal", "sourceArn", "sourceAccount", "deny", "allow", "revoked"})
    void objectNotificationsRequireTheTopicsServiceGrant(String policyCase) throws Exception {
        StaticCredentialsProvider recipient = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(OTHER_ACCOUNT, "test"));
        try (SnsClient sns = SnsClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                     .credentialsProvider(recipient).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             SqsClient sqs = SqsClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                     .credentialsProvider(recipient).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             S3Client s3 = S3Client.builder().endpointOverride(endpoint).region(Region.of(REGION))
                     .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(
                             AwsBasicCredentials.create(BUCKET_ACCOUNT, "test")))
                     .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String topic = sns.createTopic(request -> request.name("notification-" + UUID.randomUUID())).topicArn();
            String queue = null;
            try {
                queue = sqs.createQueue(request -> request.queueName("notification-" + UUID.randomUUID())).queueUrl();
                String queueUrl = queue;
                String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
                sqs.setQueueAttributes(request -> request.queueUrl(queueUrl).attributes(Map.of(QueueAttributeName.POLICY,
                        """
                        {"Statement":[{"Effect":"Allow","Principal":{"Service":"sns.amazonaws.com"},
                        "Action":"sqs:SendMessage","Resource":"%s",
                        "Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}
                        """.formatted(queueArn, topic))));
                sns.subscribe(request -> request.topicArn(topic).protocol("sqs").endpoint(queueArn));
                putTopicNotification(topic).statusCode(200);
                if (!"missing".equals(policyCase)) {
                    String principal = "principal".equals(policyCase) ? "events.amazonaws.com" : "s3.amazonaws.com";
                    String sourceArn = "arn:aws:s3:::" + bucket + ("sourceArn".equals(policyCase) ? "-wrong" : "");
                    String account = "sourceAccount".equals(policyCase) ? OTHER_ACCOUNT : BUCKET_ACCOUNT;
                    String effect = "deny".equals(policyCase) ? "Deny" : "Allow";
                    sns.setTopicAttributes(request -> request.topicArn(topic).attributeName("Policy").attributeValue(
                            """
                            {"Statement":[{"Effect":"%s","Principal":{"Service":"%s"},
                            "Action":"sns:Publish","Resource":"%s","Condition":{
                            "ArnEquals":{"aws:SourceArn":"%s"},"StringEquals":{"aws:SourceAccount":"%s"}}}]}
                            """.formatted(effect, principal, topic, sourceArn, account)));
                }
                if ("revoked".equals(policyCase)) {
                    s3.putObject(request -> request.bucket(bucket).key("event"), RequestBody.fromString("first"));
                    assertEquals(1, sqs.receiveMessage(request -> request.queueUrl(queueUrl)).messages().size());
                    sqs.purgeQueue(request -> request.queueUrl(queueUrl));
                    sns.setTopicAttributes(request -> request.topicArn(topic).attributeName("Policy")
                            .attributeValue("{\"Statement\":[]}"));
                }
                s3.putObject(request -> request.bucket(bucket).key("event"), RequestBody.fromString("event"));
                assertEquals("event", s3.getObjectAsBytes(request -> request.bucket(bucket).key("event")).asUtf8String());
                int delivered = sqs.receiveMessage(request -> request.queueUrl(queueUrl)).messages().size();
                assertEquals("allow".equals(policyCase) ? 1 : 0, delivered, policyCase);
            } finally {
                s3.deleteObject(request -> request.bucket(bucket).key("event"));
                if (queue != null) {
                    String queueUrl = queue;
                    sqs.deleteQueue(request -> request.queueUrl(queueUrl));
                }
                sns.deleteTopic(request -> request.topicArn(topic));
            }
        }
    }

    private static String createQueue(String accountId, String queueName) {
        return given().header("Authorization", authorization(accountId, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue").formParam("QueueName", queueName)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private static void deleteQueue(String accountId, String queueUrl) {
        given().header("Authorization", authorization(accountId, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteQueue").formParam("QueueUrl", queueUrl)
                .when().post("/").then().statusCode(200);
    }

    private static String createTopic(String accountId, String topicName) {
        return given().header("Authorization", authorization(accountId, "sns"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateTopic").formParam("Name", topicName)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
    }

    private static void deleteTopic(String accountId, String topicArn) {
        given().header("Authorization", authorization(accountId, "sns"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteTopic").formParam("TopicArn", topicArn)
                .when().post("/").then().statusCode(200);
    }

    private ValidatableResponse putNotification(String queueArn) {
        String xml = "<NotificationConfiguration><QueueConfiguration><Queue>" + queueArn
                + "</Queue><Event>s3:ObjectCreated:*</Event></QueueConfiguration></NotificationConfiguration>";
        return given().header("Authorization", authorization(BUCKET_ACCOUNT, "s3"))
                .contentType("application/xml").body(xml)
                .when().put("/" + bucket + "?notification").then();
    }

    private ValidatableResponse putTopicNotification(String topicArn) {
        String xml = "<NotificationConfiguration><TopicConfiguration><Topic>" + topicArn
                + "</Topic><Event>s3:ObjectCreated:*</Event></TopicConfiguration></NotificationConfiguration>";
        return given().header("Authorization", authorization(BUCKET_ACCOUNT, "s3"))
                .contentType("application/xml").body(xml)
                .when().put("/" + bucket + "?notification").then();
    }

    private ValidatableResponse getNotification() {
        return given().header("Authorization", authorization(BUCKET_ACCOUNT, "s3"))
                .when().get("/" + bucket + "?notification").then().statusCode(200);
    }

    private static String queueArn(String accountId, String queueName) {
        return "arn:aws:sqs:" + REGION + ":" + accountId + ":" + queueName;
    }

    private static String topicArn(String accountId, String topicName) {
        return "arn:aws:sns:" + REGION + ":" + accountId + ":" + topicName;
    }

    private static String authorization(String accountId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20261001/" + REGION
                + "/" + service + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
