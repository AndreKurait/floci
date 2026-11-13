package io.github.hectorvent.floci.services.kinesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.InvalidArgumentException;
import software.amazon.awssdk.services.kinesis.model.ResourceNotFoundException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.services.kinesis.KinesisResourcePolicyServiceTest.*;
import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class KinesisPolicyReviewIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeJsonAndCborRejectUnsupportedDocumentsAndPreserveBothPolicies(boolean cbor) throws Exception {
        String name = "review-wire-" + UUID.randomUUID();
        try (KinesisClient client = AwsTestClients.create(KinesisClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            client.createStream(r -> r.streamName(name).shardCount(1));
            String parent = client.describeStreamSummary(r -> r.streamName(name)).streamDescriptionSummary().streamARN();
            String consumer = null;
            try {
                consumer = client.registerStreamConsumer(r -> r.streamARN(parent).consumerName("child"))
                        .consumer().consumerARN();
                for (String target : new String[]{parent, consumer}) {
                    client.putResourcePolicy(r -> r.resourceARN(target).policy(POLICY));
                    for (String rejected : KinesisPolicyReviewCases.rejectedPolicies().toList()) {
                        JsonNode body = JSON.valueToTree(Map.of("ResourceARN", target, "Policy", rejected));
                        byte[] request = (cbor ? CBOR : JSON).writeValueAsBytes(body);
                        Response response = given().header("Authorization", auth(ACCOUNT, "kinesis"))
                                .header("X-Amz-Target", "Kinesis_20131202.PutResourcePolicy")
                                .contentType(cbor ? "application/x-amz-cbor-1.1" : "application/x-amz-json-1.1")
                                .body(request).post("/");
                        assertEquals(400, response.statusCode());
                        JsonNode error = (cbor ? CBOR : JSON).readTree(response.asByteArray());
                        assertTrue(error.path("__type").asText().endsWith("InvalidArgumentException"));
                        assertEquals(POLICY, client.getResourcePolicy(r -> r.resourceARN(target)).policy());
                    }
                    client.putResourcePolicy(r -> r.resourceARN(target)
                            .policy(KinesisPolicyReviewCases.supportedPrincipals()));
                    assertEquals(KinesisPolicyReviewCases.supportedPrincipals(),
                            client.getResourcePolicy(r -> r.resourceARN(target)).policy());
                    assertThrows(InvalidArgumentException.class, () -> client.putResourcePolicy(r -> r
                            .resourceARN(target).policy(POLICY.replace("kinesis:GetRecords", "kinesis:*"))));
                }
            } finally {
                if (consumer != null) {
                    String owned = consumer;
                    client.deregisterStreamConsumer(r -> r.consumerARN(owned));
                }
                client.deleteStream(r -> r.streamName(name));
            }
        }
    }

    @Test
    void nativeRegistrationRequiresAnExistingExactParentAndKeepsPositiveTags() {
        String name = "review-parent-" + UUID.randomUUID();
        String absent = "arn:aws:kinesis:" + REGION + ":" + ACCOUNT + ":stream/" + name;
        try (KinesisClient client = AwsTestClients.create(KinesisClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertMissingRegistration(client, absent, "child");
            client.createStream(r -> r.streamName(name).shardCount(1));
            String child = null;
            try {
                assertTrue(client.listStreamConsumers(r -> r.streamARN(absent)).consumers().isEmpty());
                child = client.registerStreamConsumer(r -> r.streamARN(absent).consumerName("child")
                        .tags(Map.of("owner", "kept"))).consumer().consumerARN();
                assertEquals("{}", client.getResourcePolicy(r -> r.resourceARN(absent)).policy());
                assertEquals(1, client.listStreamConsumers(r -> r.streamARN(absent)).consumers().size());
            } finally {
                if (child != null) {
                    String owned = child;
                    client.deregisterStreamConsumer(r -> r.consumerARN(owned));
                }
                client.deleteStream(r -> r.streamName(name));
            }
            assertMissingRegistration(client, absent, "after-delete");
        }
    }

    private static void assertMissingRegistration(KinesisClient client, String streamArn, String name) {
        AtomicReference<String> unexpectedlyCreated = new AtomicReference<>();
        try {
            ResourceNotFoundException missing = assertThrows(ResourceNotFoundException.class, () ->
                    unexpectedlyCreated.set(client.registerStreamConsumer(r -> r.streamARN(streamArn)
                            .consumerName(name)).consumer().consumerARN()));
            assertEquals(400, missing.statusCode());
        } finally {
            if (unexpectedlyCreated.get() != null) {
                client.deregisterStreamConsumer(r -> r.consumerARN(unexpectedlyCreated.get()));
            }
        }
    }
}
