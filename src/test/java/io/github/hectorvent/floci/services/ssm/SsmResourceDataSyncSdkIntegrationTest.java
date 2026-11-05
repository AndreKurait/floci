package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.InventoryItem;
import software.amazon.awssdk.services.ssm.model.ListResourceDataSyncResponse;
import software.amazon.awssdk.services.ssm.model.ResourceDataSyncItem;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmResourceDataSyncSdkIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(830022220000L);
    private static final String CAPTURE = "2026-01-02T03:04:05Z";
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void createsInventoryBaselineInS3ThroughTheSdk(String region) throws Exception {
        String account = Long.toString(ACCOUNTS.incrementAndGet());
        String instance = register(account, region);
        String bucket = "inventory-sync-" + UUID.randomUUID();
        String name = "inventory-baseline";
        String key = "reports/AWS:Application/accountid=" + account + "/region=" + region
                + "/resourcetype=ManagedInstanceInventory/" + instance + ".json";
        try (SsmClient ssm = ssm(account, region); S3Client s3 = s3(account, region)) {
            s3.createBucket(request -> {
                request.bucket(bucket);
                if (!"us-east-1".equals(region)) {
                    request.createBucketConfiguration(configuration -> configuration.locationConstraint(region));
                }
            });
            boolean created = false;
            try {
                ssm.putInventory(request -> request.instanceId(instance)
                        .items(InventoryItem.builder().typeName("AWS:Application").schemaVersion("1.1")
                                .captureTime(CAPTURE).content(Map.of("Name", "inventory-agent", "Version", "1")).build()));
                ssm.createResourceDataSync(request -> request.syncName(name).s3Destination(destination -> destination
                        .bucketName(bucket).region(region).prefix("reports").syncFormat("JsonSerDe")));
                created = true;
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    ResourceDataSyncItem sync = ssm.listResourceDataSync().resourceDataSyncItems().getFirst();
                    assertEquals("Successful", sync.lastStatusAsString());
                    assertNotNull(sync.lastSuccessfulSyncTime());
                });
                String body = s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asUtf8String();
                List<String> lines = body.lines().toList();
                assertEquals(1, lines.size());
                JsonNode entry = JSON.readTree(lines.getFirst());
                assertEquals("inventory-agent", entry.path("Name").asText());
                assertEquals(instance, entry.path("resourceId").asText());
                assertEquals(CAPTURE, entry.path("captureTime").asText());
                assertEquals("1.1", entry.path("schemaVersion").asText());
            } finally {
                if (created) {
                    ssm.deleteResourceDataSync(request -> request.syncName(name));
                }
                for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket)).contents()) {
                    s3.deleteObject(request -> request.bucket(bucket).key(object.key()));
                }
                s3.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }

    @Test
    void exportsRefreshesAndEmptyContentAndStopsAfterDeletion() throws Exception {
        try (Scenario scenario = new Scenario("us-east-1", false)) {
            scenario.upload("original", "first", CAPTURE);
            scenario.create("inventory-sync");
            scenario.awaitSuccess();
            assertEquals("original", JSON.readTree(scenario.body()).path("Name").asText());

            String refreshed = "2026-01-02T05:04:05Z";
            scenario.ssm.putInventory(request -> request.instanceId(scenario.instance)
                    .items(InventoryItem.builder().typeName("AWS:Application").schemaVersion("1.1")
                            .captureTime(refreshed).contentHash("first").build()));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                JsonNode row = JSON.readTree(scenario.body());
                assertEquals("original", row.path("Name").asText());
                assertEquals(refreshed, row.path("captureTime").asText());
            });
            scenario.ssm.putInventory(request -> request.instanceId(scenario.instance)
                    .items(InventoryItem.builder().typeName("AWS:Application").schemaVersion("1.1")
                            .captureTime(refreshed).contentHash("empty").content(List.of()).build()));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("", scenario.body()));
            scenario.ssm.deleteResourceDataSync(request -> request.syncName("inventory-sync"));
            scenario.upload("after-deletion", "different", refreshed);
            await().during(Duration.ofMillis(350)).atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertEquals("", scenario.body()));
            assertTrue(scenario.ssm.listResourceDataSync().resourceDataSyncItems().isEmpty());
        }
    }

    @Test
    void limitsPagesAndTokensRemainBoundToTheirAccountRegionTypeAndMembership() {
        try (Scenario scenario = new Scenario("us-east-1", false);
             SsmClient foreign = ssm(Long.toString(ACCOUNTS.incrementAndGet()), scenario.region);
             SsmClient otherRegion = ssm(scenario.account, "us-west-2")) {
            for (int index = 0; index < 5; index++) {
                scenario.create("sync-" + index);
            }
            assertError("ResourceDataSyncAlreadyExistsException", () -> scenario.create("sync-0"));
            assertError("ResourceDataSyncCountExceededException", () -> scenario.create("sixth"));
            ListResourceDataSyncResponse first = scenario.ssm.listResourceDataSync(request -> request.maxResults(2));
            assertEquals(List.of("sync-0", "sync-1"),
                    first.resourceDataSyncItems().stream().map(ResourceDataSyncItem::syncName).toList());
            assertNotNull(first.nextToken());
            ListResourceDataSyncResponse second = scenario.ssm.listResourceDataSync(
                    request -> request.maxResults(2).nextToken(first.nextToken()));
            assertEquals(List.of("sync-2", "sync-3"),
                    second.resourceDataSyncItems().stream().map(ResourceDataSyncItem::syncName).toList());
            ListResourceDataSyncResponse last = scenario.ssm.listResourceDataSync(
                    request -> request.maxResults(2).nextToken(second.nextToken()));
            assertEquals(List.of("sync-4"),
                    last.resourceDataSyncItems().stream().map(ResourceDataSyncItem::syncName).toList());
            assertTrue(foreign.listResourceDataSync().resourceDataSyncItems().isEmpty());
            assertTrue(otherRegion.listResourceDataSync().resourceDataSyncItems().isEmpty());
            assertTrue(scenario.ssm.listResourceDataSync(request -> request.syncType("SyncFromSource"))
                    .resourceDataSyncItems().isEmpty());
            assertError("InvalidNextToken", () -> foreign.listResourceDataSync(
                    request -> request.nextToken(first.nextToken())));
            assertError("InvalidNextToken", () -> otherRegion.listResourceDataSync(
                    request -> request.nextToken(first.nextToken())));
            assertError("InvalidNextToken", () -> scenario.ssm.listResourceDataSync(
                    request -> request.nextToken(first.nextToken()).syncType("SyncFromSource")));
            assertError("ResourceDataSyncNotFoundException",
                    () -> foreign.deleteResourceDataSync(request -> request.syncName("sync-0")));
            assertError("ResourceDataSyncNotFoundException", () -> scenario.ssm.deleteResourceDataSync(
                    request -> request.syncName("sync-0").syncType("SyncFromSource")));
            scenario.ssm.deleteResourceDataSync(request -> request.syncName("sync-0"));
            assertError("InvalidNextToken", () -> scenario.ssm.listResourceDataSync(
                    request -> request.nextToken(first.nextToken())));
            scenario.create("sync-0");
            assertError("InvalidNextToken", () -> scenario.ssm.listResourceDataSync(
                    request -> request.nextToken(first.nextToken())));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"kms", "organizations", "explorer", "other-partition"})
    void unsupportedSyncModesFailWithoutCreatingAConfiguration(String mode) {
        try (Scenario scenario = new Scenario("us-east-1", false)) {
            assertError("ResourceDataSyncInvalidConfigurationException",
                    () -> scenario.ssm.createResourceDataSync(request -> {
                        request.syncName("unsupported").s3Destination(destination -> {
                            destination.bucketName(scenario.bucket).region(scenario.region).syncFormat("JsonSerDe");
                            if ("kms".equals(mode)) {
                                destination.awskmsKeyARN("arn:aws:kms:us-east-1:" + scenario.account
                                        + ":key/00000000-0000-4000-8000-000000000001");
                            } else if ("organizations".equals(mode)) {
                                destination.destinationDataSharing(sharing ->
                                        sharing.destinationDataSharingType("Organization"));
                            } else if ("other-partition".equals(mode)) {
                                destination.region("us-gov-west-1");
                            }
                        });
                        if ("explorer".equals(mode)) {
                            request.syncType("SyncFromSource").syncSource(source -> source
                                    .sourceType("SingleAccountMultiRegions").sourceRegions(scenario.region));
                        }
                    }));
            assertTrue(scenario.ssm.listResourceDataSync().resourceDataSyncItems().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-region", "object-lock"})
    void invalidDestinationsNeverReportSuccessfulDelivery(String condition) {
        try (Scenario scenario = new Scenario("us-east-1", "object-lock".equals(condition))) {
            scenario.upload("application", "first", CAPTURE);
            scenario.ssm.createResourceDataSync(request -> request.syncName("invalid-destination")
                    .s3Destination(destination -> destination.bucketName(scenario.bucket).syncFormat("JsonSerDe")
                            .region("wrong-region".equals(condition) ? "us-west-2" : scenario.region)));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                ResourceDataSyncItem sync = scenario.ssm.listResourceDataSync().resourceDataSyncItems().getFirst();
                assertEquals("Failed", sync.lastStatusAsString());
                assertTrue(sync.lastSyncStatusMessage().contains(
                        "wrong-region".equals(condition) ? "Region" : "Object Lock"));
                assertNull(sync.lastSuccessfulSyncTime());
            });
            assertTrue(scenario.s3.listObjectsV2(request -> request.bucket(scenario.bucket)).contents().isEmpty());
        }
    }

    @Test
    void validatesRequestBoundsAndMemberTypesOnTheWire() {
        try (Scenario scenario = new Scenario("us-east-1", false)) {
            assertError("ValidationException",
                    () -> scenario.ssm.listResourceDataSync(request -> request.maxResults(0)));
            assertError("ValidationException",
                    () -> scenario.ssm.listResourceDataSync(request -> request.maxResults(51)));
            assertError("ValidationException", () -> scenario.create(""));
            assertError("ResourceDataSyncInvalidConfigurationException",
                    () -> scenario.ssm.createResourceDataSync(request -> request.syncName("no-destination")));
            assertError("ResourceDataSyncInvalidConfigurationException",
                    () -> scenario.ssm.listResourceDataSync(request -> request.syncType("Invalid")));
            assertError("InvalidNextToken",
                    () -> scenario.ssm.listResourceDataSync(request -> request.nextToken("bad-token")));
            given().header("X-Amz-Target", "AmazonSSM.CreateResourceDataSync")
                    .contentType("application/x-amz-json-1.1")
                    .body("""
                            {"SyncName":"wrong-type","S3Destination":{
                              "BucketName":17,"Region":"us-east-1","SyncFormat":"JsonSerDe"}}
                            """)
                    .post("/").then().statusCode(400).body("__type", equalTo("SerializationException"));
        }
    }

    private static void assertError(String code, Runnable request) {
        SsmException error = assertThrows(SsmException.class, request::run);
        assertEquals(400, error.statusCode());
        assertEquals(code, error.awsErrorDetails().errorCode());
    }

    private static final class Scenario implements AutoCloseable {
        private final String account = Long.toString(ACCOUNTS.incrementAndGet());
        private final String bucket = "inventory-sync-" + UUID.randomUUID();
        private final String region;
        private final String instance;
        private final SsmClient ssm;
        private final S3Client s3;

        private Scenario(String region, boolean objectLock) {
            this.region = region;
            instance = register(account, region);
            ssm = ssm(account, region);
            s3 = s3(account, region);
            try {
                s3.createBucket(request -> request.bucket(bucket).objectLockEnabledForBucket(objectLock));
            } catch (RuntimeException e) {
                ssm.close();
                s3.close();
                throw e;
            }
        }

        private void upload(String name, String hash, String capture) {
            ssm.putInventory(request -> request.instanceId(instance)
                    .items(InventoryItem.builder().typeName("AWS:Application").schemaVersion("1.1")
                            .captureTime(capture).contentHash(hash).content(Map.of("Name", name)).build()));
        }

        private void create(String name) {
            ssm.createResourceDataSync(request -> request.syncName(name).s3Destination(destination ->
                    destination.bucketName(bucket).region(region).prefix("reports").syncFormat("JsonSerDe")));
        }

        private String body() {
            String key = "reports/AWS:Application/accountid=" + account + "/region=" + region
                    + "/resourcetype=ManagedInstanceInventory/" + instance + ".json";
            return s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asUtf8String();
        }

        private void awaitSuccess() {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("Successful",
                    ssm.listResourceDataSync().resourceDataSyncItems().getFirst().lastStatusAsString()));
        }

        @Override
        public void close() {
            try {
                try {
                    for (ResourceDataSyncItem sync : ssm.listResourceDataSync().resourceDataSyncItems()) {
                        ssm.deleteResourceDataSync(request -> request.syncName(sync.syncName()));
                    }
                } finally {
                    for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket)).contents()) {
                        s3.deleteObject(request -> request.bucket(bucket).key(object.key()));
                    }
                    s3.deleteBucket(request -> request.bucket(bucket));
                }
            } finally {
                ssm.close();
                s3.close();
            }
        }
    }

    private static String register(String account, String region) {
        String instance = "i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        given().header("X-Amz-Target", "AmazonSSM.UpdateInstanceInformation")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260102/" + region
                        + "/ssm/aws4_request, SignedHeaders=host, Signature=example")
                .contentType("application/x-amz-json-1.1")
                .body(Map.of("InstanceId", instance, "AgentName", "amazon-ssm-agent",
                        "PlatformType", "Linux", "PlatformName", "Example Linux", "AgentVersion", "3.3.0"))
                .post("/").then().statusCode(200);
        return instance;
    }

    private static SsmClient ssm(String account, String region) {
        return SsmClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private static S3Client s3(String account, String region) {
        return S3Client.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
