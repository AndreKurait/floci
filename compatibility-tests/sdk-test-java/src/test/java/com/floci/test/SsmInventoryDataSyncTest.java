package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.InventoryItem;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("SSM inventory delivery to S3")
class SsmInventoryDataSyncTest {
    private static final HttpClient AGENT_HTTP = TestFixtures.emulatorHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exportsUploadedInventoryRefreshesItAndStopsAfterDeletion() throws Exception {
        assumeFalse(TestFixtures.isRealAws(), "Registers an emulator-only managed node.");
        String account = Long.toString(100000000000L
                + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 900000000000L));
        String region = TestFixtures.region().id();
        String instance = "i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        String bucket = TestFixtures.uniqueName("ssm-inventory");
        String sync = TestFixtures.uniqueName("inventory");
        String witness = TestFixtures.uniqueName("witness");
        String suffix = "/AWS:Application/accountid=" + account + "/region=" + region
                + "/resourcetype=ManagedInstanceInventory/" + instance + ".json";
        String key = "reports" + suffix;
        Set<String> ownedSyncs = new LinkedHashSet<>();
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(account, "test"));
        try (SsmClient ssm = SsmClient.builder().endpointOverride(TestFixtures.endpoint())
                     .region(TestFixtures.region()).credentialsProvider(credentials)
                     .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             S3Client s3 = S3Client.builder().endpointOverride(TestFixtures.endpoint())
                     .region(TestFixtures.region()).credentialsProvider(credentials).forcePathStyle(true)
                     .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            boolean bucketCreated = false;
            try {
                s3.createBucket(request -> {
                    request.bucket(bucket);
                    if (!"us-east-1".equals(region)) {
                        request.createBucketConfiguration(configuration -> configuration.locationConstraint(region));
                    }
                });
                bucketCreated = true;
                register(account, region, instance);
                upload(ssm, instance, "report-one");
                create(ssm, sync, bucket, region, "reports");
                ownedSyncs.add(sync);
                awaitReport(ssm, s3, sync, bucket, key, "report-one");
                JsonNode first = JSON.readTree(
                        s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asUtf8String());
                assertEquals(instance, first.path("resourceId").asText());
                assertEquals("report-one", first.path("Name").asText());
                assertTrue(first.path("captureTime").isTextual());

                upload(ssm, instance, "report-two");
                awaitReport(ssm, s3, sync, bucket, key, "report-two");
                String second = s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asUtf8String();
                assertFalse(second.contains("report-one"));
                ssm.deleteResourceDataSync(request -> request.syncName(sync));
                ownedSyncs.remove(sync);

                upload(ssm, instance, "report-three");
                create(ssm, witness, bucket, region, "witness");
                ownedSyncs.add(witness);
                // Observe another completed worker cycle instead of sleeping to guess that deletion took effect.
                awaitReport(ssm, s3, witness, bucket, "witness" + suffix, "report-three");
                assertEquals(second, s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asUtf8String());
                assertTrue(ssm.listResourceDataSync().resourceDataSyncItems().stream()
                        .noneMatch(item -> sync.equals(item.syncName())));
            } finally {
                try {
                    assertAll(ownedSyncs.stream().map(name -> (Executable)
                            () -> ssm.deleteResourceDataSync(request -> request.syncName(name))));
                } finally {
                    if (bucketCreated) {
                        for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket)).contents()) {
                            s3.deleteObject(request -> request.bucket(bucket).key(object.key()));
                        }
                        s3.deleteBucket(request -> request.bucket(bucket));
                    }
                }
            }
        }
    }

    private static void create(SsmClient ssm, String name, String bucket, String region, String prefix) {
        ssm.createResourceDataSync(request -> request.syncName(name).s3Destination(destination ->
                destination.bucketName(bucket).region(region).prefix(prefix).syncFormat("JsonSerDe")));
    }

    private static void upload(SsmClient ssm, String instance, String value) {
        ssm.putInventory(request -> request.instanceId(instance).items(InventoryItem.builder()
                .typeName("AWS:Application").schemaVersion("1.1").captureTime("2026-01-02T03:04:05Z")
                .content(Map.of("Name", value)).build()));
    }

    private static void awaitReport(SsmClient ssm, S3Client s3, String sync, String bucket,
                                    String key, String expected) throws InterruptedException {
        TestFixtures.await(() -> ssm.listResourceDataSync().resourceDataSyncItems().stream()
                        .anyMatch(item -> sync.equals(item.syncName())
                                && "Successful".equals(item.lastStatusAsString()))
                        && s3.getObjectAsBytes(request -> request.bucket(bucket).key(key))
                                .asUtf8String().contains(expected),
                sync, "inventory report " + expected, Duration.ofSeconds(30).toNanos(), 100,
                () -> ssm.listResourceDataSync().resourceDataSyncItems().toString());
    }

    private static void register(String account, String region, String instance) throws Exception {
        String body = """
                {"InstanceId":"%s","AgentName":"amazon-ssm-agent","AgentVersion":"3.3.0",
                 "PlatformType":"Linux","PlatformName":"Example Linux"}
                """.formatted(instance);
        HttpRequest request = HttpRequest.newBuilder(TestFixtures.endpoint())
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonSSM.UpdateInstanceInformation")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260102/" + region
                        + "/ssm/aws4_request, SignedHeaders=host, Signature=example")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = AGENT_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }
}
