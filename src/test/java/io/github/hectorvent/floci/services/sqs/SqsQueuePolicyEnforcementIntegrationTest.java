package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SqsTestQueue;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SqsQueuePolicyEnforcementIntegrationTest {
    private static final String OWNER = "000000000451";
    private static final String OTHER = "000000000452";
    private static final String REGION = "us-east-1";
    private static final String SEND = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sqs:SendMessage","Resource":"*"}]}
            """;
    private static final String READ_ONLY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sqs:GetQueueUrl","Resource":"*"}]}
            """;

    @TestHTTPResource
    URI baseUri;

    @Inject
    SqsService sqsService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anotherAccountNeedsTheQueueToGrantIt() {
        SqsTestQueue queue = queue();
        String accessKey = user(OTHER, SEND);

        send(accessKey, queue).statusCode(403);

        queue.addPermission("from-other", OTHER, "SendMessage");
        send(accessKey, queue).statusCode(200);
        assertEquals("hello", queue.receive().getFirst().getBody());
    }

    @Test
    void aQueueGrantDoesNotReplaceTheOtherAccountsOwnPolicy() {
        SqsTestQueue queue = queue();
        queue.addPermission("from-other", OTHER, "SendMessage");

        send(user(OTHER, READ_ONLY), queue).statusCode(403);
    }

    @Test
    void aQueuePolicyAloneAllowsAUserOfItsOwnAccount() {
        SqsTestQueue queue = queue();
        String name = "sqs-policy-" + UUID.randomUUID();
        String accessKey = user(OWNER, name, READ_ONLY);

        send(accessKey, queue).statusCode(403);

        queue.setPolicy(policy("Allow", "arn:aws:iam::" + OWNER + ":user/" + name, queue.arn()));
        send(accessKey, queue).statusCode(200);
    }

    @Test
    void aQueuePolicyDenyRefusesAUserAllowedTheAction() {
        SqsTestQueue queue = queue();
        String name = "sqs-policy-" + UUID.randomUUID();
        String accessKey = user(OWNER, name, SEND);

        send(accessKey, queue).statusCode(200);

        queue.setPolicy(policy("Deny", "arn:aws:iam::" + OWNER + ":user/" + name, queue.arn()));
        send(accessKey, queue).statusCode(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetQueueUrl", "CreateQueue"})
    void queryNameOperationsHonorScopedAllows(String operation) {
        SqsTestQueue allowed = queue();
        SqsTestQueue denied = queue();
        String accessKey = user(OWNER, identityPolicy("Allow", operation, allowed.arn()));

        nameRequest(accessKey, operation, allowed, denied.url()).statusCode(200);
        nameRequest(accessKey, operation, denied, allowed.url()).statusCode(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetQueueUrl", "CreateQueue"})
    void queryNameOperationsHonorScopedDenies(String operation) {
        SqsTestQueue allowed = queue();
        SqsTestQueue denied = queue();
        String policy = """
                {"Version":"2012-10-17","Statement":[
                 {"Effect":"Allow","Action":"sqs:%s","Resource":"*"},
                 {"Effect":"Deny","Action":"sqs:%s","Resource":"%s"}]}
                """.formatted(operation, operation, denied.arn());
        String accessKey = user(OWNER, policy);

        nameRequest(accessKey, operation, allowed, denied.url()).statusCode(200);
        nameRequest(accessKey, operation, denied, allowed.url()).statusCode(403);
    }

    @Test
    void legacyQueryGetQueueUrlHonorsTheNamedQueuePolicy() {
        SqsTestQueue allowed = queue();
        SqsTestQueue denied = queue();
        String accessKey = user(OWNER, identityPolicy("Allow", "GetQueueUrl", allowed.arn()));
        for (SqsTestQueue queue : new SqsTestQueue[]{allowed, denied}) {
            given().port(baseUri.getPort()).header("Authorization", auth(accessKey, "sqs"))
                    .queryParam("Action", "GetQueueUrl").queryParam("Version", "2012-11-05")
                    .queryParam("QueueName", queueName(queue))
                    .get("/").then().statusCode(queue == allowed ? 200 : 403);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void sdkJsonNameOperationsHonorScopedAllowsAndDenies(String region) {
        SqsTestQueue allowed = queue(region);
        SqsTestQueue denied = queue(region);
        String name = "sqs-name-sdk-" + UUID.randomUUID();
        String authorization = auth(OWNER, region, "iam");
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), authorization, name);
        String policy = """
                {"Version":"2012-10-17","Statement":[
                 {"Effect":"Allow","Action":["sqs:GetQueueUrl","sqs:CreateQueue"],"Resource":"%s"}]}
                """.formatted(allowed.arn());
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), authorization, name, "p", policy);
        try (SqsClient client = SqsClient.builder().endpointOverride(baseUri).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(credentials.accessKeyId(), credentials.secretAccessKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10)))
                .build()) {
            assertEquals(allowed.url(), client.getQueueUrl(request -> request.queueName(queueName(allowed))).queueUrl());
            assertEquals(allowed.url(), client.createQueue(request -> request.queueName(queueName(allowed))).queueUrl());
            assertDenied(assertThrows(SqsException.class, () ->
                    client.getQueueUrl(request -> request.queueName(queueName(denied)))));
            assertDenied(assertThrows(SqsException.class, () ->
                    client.createQueue(request -> request.queueName(queueName(denied)))));

            EnforcementFixtures.putUserPolicy(baseUri.getPort(), authorization, name, "p", """
                    {"Version":"2012-10-17","Statement":[
                     {"Effect":"Allow","Action":"sqs:*","Resource":"*"},
                     {"Effect":"Deny","Action":["sqs:GetQueueUrl","sqs:CreateQueue"],"Resource":"%s"}]}
                    """.formatted(denied.arn()));
            assertEquals(allowed.url(), client.getQueueUrl(request -> request.queueName(queueName(allowed))).queueUrl());
            assertDenied(assertThrows(SqsException.class, () ->
                    client.getQueueUrl(request -> request.queueName(queueName(denied)))));
            assertDenied(assertThrows(SqsException.class, () ->
                    client.createQueue(request -> request.queueName(queueName(denied)))));
        }
    }

    private static void assertDenied(SqsException error) {
        assertEquals(400, error.statusCode()); // JSON's existing authorization error differs from Query's 403.
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }

    private static String identityPolicy(String effect, String operation, String arn) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s","Action":"sqs:%s","Resource":"%s"}]}
                """.formatted(effect, operation, arn);
    }

    private static String queueName(SqsTestQueue queue) {
        return queue.url().substring(queue.url().lastIndexOf('/') + 1);
    }

    private ValidatableResponse nameRequest(String accessKey, String operation, SqsTestQueue queue, String decoyUrl) {
        return given().port(baseUri.getPort()).header("Authorization", auth(accessKey, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .queryParam("QueueName", "url-query-decoy")
                .formParam("Action", operation).formParam("Version", "2012-11-05")
                .formParam("QueueName", queueName(queue)).formParam("QueueUrl", decoyUrl)
                .post("/").then();
    }

    private static String policy(String effect, String principal, String queueArn) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s","Principal":{"AWS":"%s"},
                 "Action":"sqs:SendMessage","Resource":"%s"}]}
                """.formatted(effect, principal, queueArn);
    }

    private SqsTestQueue queue() {
        return queue(REGION);
    }

    private SqsTestQueue queue(String region) {
        SqsTestQueue queue = SqsTestQueue.create(sqsService, OWNER, region, "sqs-policy-");
        cleanup.register(queue::delete);
        return queue;
    }

    private String user(String account, String policy) {
        return user(account, "sqs-policy-" + UUID.randomUUID(), policy);
    }

    private String user(String account, String name, String policy) {
        String authorization = auth(account, "iam");
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), authorization, name);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), authorization, name, "p", policy);
        return credentials.accessKeyId();
    }

    private ValidatableResponse send(String accessKey, SqsTestQueue queue) {
        return given().port(baseUri.getPort()).header("Authorization", auth(accessKey, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "SendMessage").formParam("Version", "2012-11-05")
                .formParam("QueueUrl", queue.url()).formParam("MessageBody", "hello")
                .post("/").then();
    }
}
