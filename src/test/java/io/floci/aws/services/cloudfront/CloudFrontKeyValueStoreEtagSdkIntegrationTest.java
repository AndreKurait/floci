package io.floci.aws.services.cloudfront;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.CreateKeyValueStoreResponse;
import software.amazon.awssdk.services.cloudfront.model.DescribeKeyValueStoreResponse;
import software.amazon.awssdk.services.cloudfront.model.UpdateKeyValueStoreResponse;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Native SDK XML/header handling and stale-version refusal; no data-plane or function execution claim. */
@QuarkusTest
class CloudFrontKeyValueStoreEtagSdkIntegrationTest {
    @Test
    void updateAndDeleteDistinguishStaleVersionsFromMalformedHeaders() {
        try (CloudFrontClient client = AwsTestClients.create(
                CloudFrontClient.builder(), "000000000000", Region.US_EAST_1)) {
            String name = "etag-" + UUID.randomUUID().toString().substring(0, 8);
            CreateKeyValueStoreResponse created = client.createKeyValueStore(r -> r.name(name).comment("before"));
            UpdateKeyValueStoreResponse updated =
                    client.updateKeyValueStore(r -> r.name(name).ifMatch(created.eTag()).comment("after"));
            String current = updated.eTag();
            assertNotEquals(created.eTag(), current);
            try {
                for (String stale : List.of(created.eTag(), "E0000000000000")) {
                    assertError("PreconditionFailed", 412,
                            () -> client.updateKeyValueStore(r -> r.name(name).ifMatch(stale).comment("lost")));
                    assertError("PreconditionFailed", 412,
                            () -> client.deleteKeyValueStore(r -> r.name(name).ifMatch(stale)));
                    DescribeKeyValueStoreResponse retained = client.describeKeyValueStore(r -> r.name(name));
                    assertEquals("after", retained.keyValueStore().comment());
                    assertEquals(current, retained.eTag());
                    assertEquals(created.keyValueStore().arn(), retained.keyValueStore().arn());
                }
                for (String malformed : List.of("stale", "")) {
                    assertError("InvalidIfMatchVersion", 400,
                            () -> client.updateKeyValueStore(r -> r.name(name).ifMatch(malformed).comment("lost")));
                    assertError("InvalidIfMatchVersion", 400,
                            () -> client.deleteKeyValueStore(r -> r.name(name).ifMatch(malformed)));
                }
                DescribeKeyValueStoreResponse retained = client.describeKeyValueStore(r -> r.name(name));
                assertEquals("after", retained.keyValueStore().comment());
                assertEquals(current, retained.eTag());
            } finally {
                client.deleteKeyValueStore(r -> r.name(name).ifMatch(current));
            }
            assertError("EntityNotFound", 404, () -> client.describeKeyValueStore(r -> r.name(name)));
        }
    }

    private static void assertError(String code, int status, Runnable action) {
        CloudFrontException failure = assertThrows(CloudFrontException.class, action::run);
        assertEquals(code, failure.awsErrorDetails().errorCode());
        assertEquals(status, failure.statusCode());
    }
}
