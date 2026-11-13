package io.github.hectorvent.floci.services.kinesis;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.KinesisException;

import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.services.kinesis.KinesisResourcePolicyServiceTest.*;
import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class KinesisResourcePolicyIamIntegrationTest {
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void exactActionsResourcesAndOwnTagsAuthorizeBeforeReadingOrChangingPolicy() {
        String name = "policy-iam-" + UUID.randomUUID();
        String siblingName = "policy-sibling-" + UUID.randomUUID();
        String userName = "policy-user-" + UUID.randomUUID();
        String account = "000000000000";
        RestAssuredJsonUtils.configureAwsContentTypes();
        try (KinesisClient root = AwsTestClients.create(KinesisClient.builder(), account, Region.US_EAST_1)) {
            EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup,
                    RestAssured.port, auth(account, "iam"), userName);
            root.createStream(r -> r.streamName(name).shardCount(1));
            String arn = root.describeStreamSummary(r -> r.streamName(name)).streamDescriptionSummary().streamARN();
            String child = null;
            boolean siblingCreated = false;
            try (KinesisClient user = AwsTestClients.create(KinesisClient.builder(),
                    credentials.accessKeyId(), credentials.secretAccessKey(), Region.US_EAST_1)) {
                root.createStream(r -> r.streamName(siblingName).shardCount(1));
                siblingCreated = true;
                String sibling = root.describeStreamSummary(r -> r.streamName(siblingName)).streamDescriptionSummary().streamARN();
                child = root.registerStreamConsumer(r -> r.streamARN(arn).consumerName("child")
                        .tags(Map.of("owner", "consumer"))).consumer().consumerARN();
                root.addTagsToStream(r -> r.streamName(name).tags(Map.of("owner", "parent")));
                for (String target : new String[]{arn, child}) {
                    policy(account, userName, "GetResourcePolicy", target, null);
                    assertEquals("{}", user.getResourcePolicy(r -> r.resourceARN(target)).policy());
                    forbidden(() -> user.putResourcePolicy(r -> r.resourceARN(target).policy(POLICY)));
                    forbidden(() -> user.deleteResourcePolicy(r -> r.resourceARN(target)));
                    forbidden(() -> user.getResourcePolicy(r -> r.resourceARN(sibling)));
                    policy(account, userName, "PutResourcePolicy", target, null);
                    user.putResourcePolicy(r -> r.resourceARN(target).policy(POLICY));
                    forbidden(() -> user.getResourcePolicy(r -> r.resourceARN(target)));
                    forbidden(() -> user.deleteResourcePolicy(r -> r.resourceARN(target)));
                    assertEquals(POLICY, root.getResourcePolicy(r -> r.resourceARN(target)).policy());
                    policy(account, userName, "DeleteResourcePolicy", target, null);
                    user.deleteResourcePolicy(r -> r.resourceARN(target));
                    forbidden(() -> user.putResourcePolicy(r -> r.resourceARN(target).policy(DENY)));
                    assertEquals("{}", root.getResourcePolicy(r -> r.resourceARN(target)).policy());
                }
                String consumer = child;
                policy(account, userName, "*ResourcePolicy", "*", "parent");
                user.putResourcePolicy(r -> r.resourceARN(arn).policy(POLICY));
                forbidden(() -> user.putResourcePolicy(r -> r.resourceARN(consumer).policy(DENY)));
                assertEquals("{}", root.getResourcePolicy(r -> r.resourceARN(consumer)).policy());
                policy(account, userName, "*ResourcePolicy", "*", "consumer");
                user.putResourcePolicy(r -> r.resourceARN(consumer).policy(DENY));
                assertEquals(DENY, user.getResourcePolicy(r -> r.resourceARN(consumer)).policy());
                forbidden(() -> user.getResourcePolicy(r -> r.resourceARN(arn)));
                forbidden(() -> user.deleteResourcePolicy(r -> r.resourceARN(arn)));
                assertEquals(POLICY, root.getResourcePolicy(r -> r.resourceARN(arn)).policy());

                policy(account, userName, "PutResourcePolicy", arn, null);
                given().header("Authorization", auth(credentials.accessKeyId(), "kinesis"))
                        .header("X-Amz-Target", "Kinesis_20131202.PutResourcePolicy")
                        .contentType("application/x-amz-json-1.1")
                        .body(Map.of("ResourceARN", sibling, "StreamARN", arn, "StreamName", name, "Policy", POLICY))
                        .post("/").then().statusCode(400).body("__type", containsString("AccessDeniedException"));
                assertEquals("{}", root.getResourcePolicy(r -> r.resourceARN(sibling)).policy());
                assertEquals(POLICY, root.getResourcePolicy(r -> r.resourceARN(arn)).policy());
            } finally {
                if (child != null) {
                    String owned = child;
                    root.deregisterStreamConsumer(r -> r.consumerARN(owned));
                }
                if (siblingCreated) {
                    root.deleteStream(r -> r.streamName(siblingName));
                }
                root.deleteStream(r -> r.streamName(name));
            }
        }
    }

    private static void policy(String account, String userName, String action, String resource, String tag) {
        String condition = tag == null ? "" : ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/owner\":\"" + tag + "\"}}";
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(account, "iam"), userName, "resource-policy", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"kinesis:%s","Resource":"%s"%s}]}
                """.formatted(action, resource, condition));
    }

    private static void forbidden(Executable call) {
        KinesisException error = assertThrows(KinesisException.class, call);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }
}
