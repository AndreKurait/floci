package io.floci.aws.services.sqs;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SqsTestQueue;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void queueUrlLookupRequiresTheOwnersArnAndHonorsExplicitDeny() {
        SqsTestQueue queue = queue();
        queue.addPermission("url-scoped", OTHER, "GetQueueUrl");
        String ownerOnly = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                 "Action":"sqs:GetQueueUrl","Resource":"%s"}]}
                """.formatted(queue.arn());
        ownerRequest(user(OTHER, ownerOnly), queue).statusCode(200).body(containsString(queue.url()));
        String callerOnly = ownerOnly.replace(OWNER, OTHER);
        ownerRequest(user(OTHER, callerOnly), queue).statusCode(403);
        String denied = """
                {"Version":"2012-10-17","Statement":[
                 {"Effect":"Allow","Action":"sqs:GetQueueUrl","Resource":"*"},
                 {"Effect":"Deny","Action":"sqs:GetQueueUrl","Resource":"%s"}]}
                """.formatted(queue.arn());
        ownerRequest(user(OTHER, denied), queue).statusCode(403);
    }

    @Test
    void anEmptyOwnerUsesTheCallersQueueAndStillEnforcesItsPolicy() {
        SqsTestQueue queue = queue();
        String scoped = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                 "Action":"sqs:GetQueueUrl","Resource":"%s"}]}
                """.formatted(queue.arn());
        String allowed = user(OWNER, scoped);
        String denied = user(OWNER, scoped.replace("\"Allow\"", "\"Deny\""));
        ownerRequest(allowed, queue, "").statusCode(200).body(containsString(queue.url()));
        jsonOwnerRequest(allowed, queue, "").statusCode(200).body("QueueUrl", is(queue.url()));
        ownerRequest(denied, queue, "").statusCode(403);
        jsonOwnerRequest(denied, queue, "").statusCode(400).body("__type", is("AccessDeniedException"));
    }

    @Test
    void aMalformedOwnerNeverFallsBackToAnExistingCallersQueue() {
        SqsTestQueue queue = queue();
        String allowed = user(OWNER, READ_ONLY);
        ownerRequest(allowed, queue, "not-an-account").statusCode(400)
                .body(containsString("AWS.SimpleQueueService.NonExistentQueue"));
        jsonOwnerRequest(allowed, queue, "not-an-account").statusCode(400)
                .body("__type", is("QueueDoesNotExist"));
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
    void anotherAccountGetsTheQueueUrlByOwnerOnlyWhenTheQueueGrantsIt() {
        // A producer in another account finds a queue it may send to by name and owner (QueueOwnerAWSAccountId),
        // as an SDK client resolving a cross-account queue does.
        SqsTestQueue queue = queue();
        String name = "sqs-owner-" + UUID.randomUUID();
        String authorization = auth(OTHER, REGION, "iam");
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), authorization, name);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), authorization, name, "p", READ_ONLY);

        ownerRequest(credentials.accessKeyId(), queue).statusCode(403);

        queue.addPermission("url-from-other", OTHER, "GetQueueUrl");
        ownerRequest(credentials.accessKeyId(), queue).statusCode(200).body(containsString(queue.url()));
        try (SqsClient client = SqsClient.builder().endpointOverride(baseUri).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(credentials.accessKeyId(), credentials.secretAccessKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10)))
                .build()) {
            assertEquals(queue.url(), client.getQueueUrl(request -> request.queueName(queueName(queue))
                    .queueOwnerAWSAccountId(OWNER)).queueUrl());
        }
        // The grant names GetQueueUrl only.
        send(credentials.accessKeyId(), queue).statusCode(403);
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

    private ValidatableResponse ownerRequest(String accessKey, SqsTestQueue queue) {
        return ownerRequest(accessKey, queue, OWNER);
    }

    private ValidatableResponse ownerRequest(String accessKey, SqsTestQueue queue, String owner) {
        return given().port(baseUri.getPort()).header("Authorization", auth(accessKey, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl").formParam("Version", "2012-11-05")
                .formParam("QueueName", queueName(queue)).formParam("QueueOwnerAWSAccountId", owner)
                .post("/").then();
    }

    private ValidatableResponse jsonOwnerRequest(String accessKey, SqsTestQueue queue, String owner) {
        return given().port(baseUri.getPort()).header("Authorization", auth(accessKey, "sqs"))
                .header("X-Amz-Target", "AmazonSQS.GetQueueUrl").contentType("application/x-amz-json-1.0")
                .body(Map.of("QueueName", queueName(queue), "QueueOwnerAWSAccountId", owner))
                .post("/").then();
    }

    private static String queueName(SqsTestQueue queue) {
        return queue.url().substring(queue.url().lastIndexOf('/') + 1);
    }

    private static String policy(String effect, String principal, String queueArn) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s","Principal":{"AWS":"%s"},
                 "Action":"sqs:SendMessage","Resource":"%s"}]}
                """.formatted(effect, principal, queueArn);
    }

    private SqsTestQueue queue() {
        SqsTestQueue queue = SqsTestQueue.create(sqsService, OWNER, REGION, "sqs-policy-");
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
