package io.github.hectorvent.floci.services.kinesis;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.InvalidArgumentException;
import software.amazon.awssdk.services.kinesis.model.ResourceNotFoundException;

import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.services.kinesis.KinesisResourcePolicyServiceTest.*;
import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class KinesisResourcePolicyIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1", "us-gov-west-1"})
    void nativeSdkStoresReadsAndDeletesBothResourceKindsInEachPartition(String region) {
        String name = "policy-" + UUID.randomUUID();
        try (KinesisClient client = AwsTestClients.create(KinesisClient.builder(), ACCOUNT, Region.of(region))) {
            client.createStream(r -> r.streamName(name).shardCount(1));
            String arn = client.describeStreamSummary(r -> r.streamName(name)).streamDescriptionSummary().streamARN();
            String child = null;
            try {
                child = client.registerStreamConsumer(r -> r.streamARN(arn).consumerName("child")
                        .tags(Map.of("fixture", name))).consumer().consumerARN();
                for (String target : new String[]{arn, child}) {
                    assertEquals("{}", client.getResourcePolicy(r -> r.resourceARN(target)).policy());
                    client.putResourcePolicy(r -> r.resourceARN(target).policy(POLICY));
                    assertEquals(POLICY, client.getResourcePolicy(r -> r.resourceARN(target)).policy());
                    assertThrows(InvalidArgumentException.class, () -> client.putResourcePolicy(r -> r.resourceARN(target)
                            .policy(POLICY.replace("Allow", "Permit"))));
                    assertEquals(POLICY, client.getResourcePolicy(r -> r.resourceARN(target)).policy());
                    client.putResourcePolicy(r -> r.resourceARN(target).policy(DENY));
                    assertEquals(DENY, client.getResourcePolicy(r -> r.resourceARN(target)).policy());
                    client.deleteResourcePolicy(r -> r.resourceARN(target));
                    assertEquals("{}", client.getResourcePolicy(r -> r.resourceARN(target)).policy());
                    assertThrows(ResourceNotFoundException.class, () -> client.deleteResourcePolicy(r -> r.resourceARN(target)));
                }
                // Configuration is persisted, but resource-policy data-plane evaluation is explicitly not implemented.
                client.putResourcePolicy(r -> r.resourceARN(arn).policy(DENY.replace("kinesis:GetRecords", "kinesis:PutRecord").replace("222222222222", ACCOUNT)));
                assertNotNull(client.putRecord(r -> r.streamName(name).partitionKey("key")
                        .data(SdkBytes.fromUtf8String("metadata scope"))).sequenceNumber());
            } finally {
                if (child != null) {
                    String owned = child;
                    client.deregisterStreamConsumer(r -> r.consumerARN(owned));
                    assertThrows(ResourceNotFoundException.class, () -> client.getResourcePolicy(r -> r.resourceARN(owned)));
                }
                client.deleteStream(r -> r.streamName(name));
                assertThrows(ResourceNotFoundException.class, () -> client.getResourcePolicy(r -> r.resourceARN(arn)));
            }
        }
    }

    @Test
    void nativeSdkRejectsMissingParentsAndInvalidPolicyReplacements() {
        String name = "policy-validation-" + UUID.randomUUID();
        String arn = "arn:aws:kinesis:us-east-1:" + ACCOUNT + ":stream/" + name;
        try (KinesisClient client = AwsTestClients.create(KinesisClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertThrows(ResourceNotFoundException.class,
                    () -> client.registerStreamConsumer(r -> r.streamARN(arn).consumerName("child")));
            client.createStream(r -> r.streamName(name).shardCount(1));
            try {
                assertTrue(client.listStreamConsumers(r -> r.streamARN(arn)).consumers().isEmpty());
                client.putResourcePolicy(r -> r.resourceARN(arn).policy(POLICY));
                for (String invalid : new String[]{POLICY + " ".repeat(20 * 1024),
                        POLICY.replace("kinesis:GetRecords", "kinesis:*"),
                        POLICY.replace("\"kinesis:GetRecords\"", "[\"kinesis:GetRecords\",\"*\"]")}) {
                    assertThrows(InvalidArgumentException.class,
                            () -> client.putResourcePolicy(r -> r.resourceARN(arn).policy(invalid)));
                    assertEquals(POLICY, client.getResourcePolicy(r -> r.resourceARN(arn)).policy());
                }
            } finally {
                client.deleteStream(r -> r.streamName(name));
            }
        }
    }

    @Test
    void nativeClientsIsolateSameNamesAndRejectForeignFullArnSelectors() {
        String name = "policy-scope-" + UUID.randomUUID();
        try (KinesisClient first = AwsTestClients.create(KinesisClient.builder(), ACCOUNT, Region.US_EAST_1);
             KinesisClient otherAccount = AwsTestClients.create(KinesisClient.builder(), "222222222222", Region.US_EAST_1);
             KinesisClient otherRegion = AwsTestClients.create(KinesisClient.builder(), ACCOUNT, Region.US_WEST_2)) {
            KinesisClient[] clients = {first, otherAccount, otherRegion};
            String[] arns = new String[3];
            try {
                for (int i = 0; i < clients.length; i++) {
                    clients[i].createStream(r -> r.streamName(name).shardCount(1));
                    arns[i] = clients[i].describeStreamSummary(r -> r.streamName(name)).streamDescriptionSummary().streamARN();
                    String arn = arns[i];
                    String document = POLICY.replace("\"Effect\"", "\"Sid\":\"scope" + i + "\",\"Effect\"");
                    clients[i].putResourcePolicy(r -> r.resourceARN(arn).policy(document));
                }
                for (int i = 0; i < clients.length; i++) {
                    KinesisClient client = clients[i];
                    for (int j = 0; j < clients.length; j++) {
                        String arn = arns[j];
                        if (i == j) {
                            assertEquals(POLICY.replace("\"Effect\"", "\"Sid\":\"scope" + i + "\",\"Effect\""),
                                    client.getResourcePolicy(r -> r.resourceARN(arn)).policy());
                        } else {
                            assertThrows(ResourceNotFoundException.class, () -> client.getResourcePolicy(r -> r.resourceARN(arn)));
                            assertThrows(ResourceNotFoundException.class, () -> client.putResourcePolicy(r -> r.resourceARN(arn).policy(DENY)));
                            assertThrows(ResourceNotFoundException.class, () -> client.deleteResourcePolicy(r -> r.resourceARN(arn)));
                        }
                    }
                }
            } finally {
                for (int i = 0; i < clients.length; i++) {
                    if (arns[i] != null) {
                        clients[i].deleteStream(r -> r.streamName(name));
                    }
                }
            }
        }
    }

    @Test
    void malformedNativeMembersReturnAwsErrorsWithoutCoercingPolicyValues() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        for (String[] sample : new String[][]{
                {"{}", "InvalidArgumentException"},
                {"{\"ResourceARN\":42,\"Policy\":\"{}\"}", "InvalidArgumentException"},
                {"{\"ResourceARN\":\"arn:aws:kinesis:us-east-1:111111111111:stream/target\",\"Policy\":{}}",
                        "InvalidArgumentException"}}) {
            given().header("Authorization", auth(ACCOUNT, "kinesis"))
                    .header("X-Amz-Target", "Kinesis_20131202.PutResourcePolicy")
                    .contentType("application/x-amz-json-1.1").body(sample[0]).post("/")
                    .then().statusCode(400).body("__type", containsString(sample[1]));
        }
    }
}
