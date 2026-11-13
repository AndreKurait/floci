package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3.S3Service.LogExportDestination;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.LambdaNotification;
import io.github.hectorvent.floci.services.s3.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.s3.model.ObjectLockRetention;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class S3LogExportDestinationTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private static final String SOURCE = "arn:aws:logs:us-east-1:123456789012:log-group:group";
    private static final String KEY = "prefix/task/stream/0.gz";
    private final InMemoryStorage<String, Bucket> rawBuckets = new InMemoryStorage<>();
    private final AccountAwareStorageBackend<Bucket> buckets = new AccountAwareStorageBackend<>(rawBuckets, null, ACCOUNT);
    private final AccountAwareStorageBackend<S3Object> objects = AccountAwareStorageBackend.inMemory(ACCOUNT);
    @TempDir
    Path directory;

    @Test
    void nativeWriteStoresRealBytesAndMetadataAndRefusesOverwrite() {
        S3Service service = service(null);
        seed(REGION, ACCOUNT);
        LogExportDestination receipt = admit(service);
        byte[] body = new byte[] {31, (byte) 139, 8, 0};
        service.writeLogExportObject(receipt, SOURCE, KEY, body);
        S3Object object = service.getObject("bucket", KEY);
        assertArrayEquals(body, object.getData());
        assertEquals("application/gzip", object.getContentType());
        assertEquals(4, object.getSize());
        assertEquals(ACCOUNT, object.getOwnerAccountId());
        assertThrows(AwsException.class, () -> service.writeLogExportObject(receipt, SOURCE, KEY, new byte[] {1}));
        assertArrayEquals(body, service.getObject("bucket", KEY).getData());
    }

    @Test
    void serviceReadHasNoWriteAclConditionAndServiceWriteCarriesItsRequiredAcl() {
        S3Service service = service(null);
        Bucket bucket = seed(REGION, ACCOUNT);
        bucket.setPolicy("""
                {"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Principal":{"Service":"logs.us-east-1.amazonaws.com"},
                "Action":"s3:GetBucketAcl","Resource":"arn:aws:s3:::bucket",
                "Condition":{"StringEquals":{"aws:SourceAccount":"123456789012"},
                "ArnLike":{"aws:SourceArn":"arn:aws:logs:us-east-1:123456789012:log-group:group"},
                "Null":{"s3:x-amz-acl":"true"}}},
                {"Effect":"Allow","Principal":{"Service":"logs.us-east-1.amazonaws.com"},
                "Action":"s3:PutObject","Resource":"arn:aws:s3:::bucket/*",
                "Condition":{"StringEquals":{"aws:SourceAccount":"123456789012",
                "s3:x-amz-acl":"bucket-owner-full-control"},
                "ArnLike":{"aws:SourceArn":"arn:aws:logs:us-east-1:123456789012:log-group:group"}}}]}
                """);
        service.writeLogExportObject(admit(service), SOURCE, KEY, new byte[] {1, 2, 3});
        assertArrayEquals(new byte[] {1, 2, 3}, service.getObject("bucket", KEY).getData());
    }

    @ParameterizedTest
    @ValueSource(strings = {"principal", "account", "source", "acl", "read", "write", "deny"})
    void independentlyMissingOrDeniedServicePermissionRefusesBeforeObjectCommit(String alteration) {
        S3Service service = service(null);
        Bucket bucket = seed(REGION, ACCOUNT);
        String policy = policy(REGION, ACCOUNT);
        policy = switch (alteration) {
            case "principal" -> policy.replace("logs.us-east-1.amazonaws.com", "sns.amazonaws.com");
            case "account" -> policy.replace("\"aws:SourceAccount\":\"123456789012\"", "\"aws:SourceAccount\":\"999999999999\"");
            case "source" -> policy.replace("log-group:group", "log-group:other");
            case "acl" -> policy.replace("bucket-owner-full-control", "private");
            case "read" -> policy.replace("s3:GetBucketAcl", "s3:ListBucket");
            case "write" -> policy.replace("s3:PutObject", "s3:GetObject");
            case "deny" -> policy.replace("\"Effect\":\"Allow\"", "\"Effect\":\"Deny\"");
            default -> throw new AssertionError(alteration);
        };
        bucket.setPolicy(policy);
        LogExportDestination read = service.readLogExportDestination(ACCOUNT, REGION, "bucket");
        assertNull(read.epoch());
        assertThrows(AwsException.class, () -> service.admitLogExportDestination(read, SOURCE, List.of(KEY)));
        assertNull(service.readLogExportDestination(ACCOUNT, REGION, "bucket").epoch());
        assertTrue(objects.keysForAccount(ACCOUNT).isEmpty());
    }

    @Test
    void liveServicePolicyRevocationStopsAnAdmittedWrite() {
        S3Service service = service(null);
        Bucket bucket = seed(REGION, ACCOUNT);
        LogExportDestination receipt = admit(service);
        bucket.setPolicy(null);
        assertThrows(AwsException.class, () -> service.writeLogExportObject(receipt, SOURCE, KEY, new byte[] {1}));
        assertTrue(objects.keysForAccount(ACCOUNT).isEmpty());
    }

    @Test
    void nativeReaderNeverMigratesLegacyOrBorrowsForeignOwnerRegionOrInnerName() {
        S3Service service = service(null);
        Bucket legacy = seed(REGION, ACCOUNT);
        buckets.deleteForAccount(ACCOUNT, "bucket");
        rawBuckets.put("bucket", legacy);
        assertThrows(AwsException.class, () -> service.readLogExportDestination(ACCOUNT, REGION, "bucket"));
        assertEquals(Set.of("bucket"), rawBuckets.keys());
        rawBuckets.delete("bucket");
        seed(REGION, "999999999999");
        assertThrows(AwsException.class, () -> service.readLogExportDestination(ACCOUNT, REGION, "bucket"));
        Bucket own = seed("us-west-2", ACCOUNT);
        assertThrows(AwsException.class, () -> service.readLogExportDestination(ACCOUNT, REGION, "bucket"));
        own.setRegion(REGION);
        own.setName("other");
        assertThrows(AwsException.class, () -> service.readLogExportDestination(ACCOUNT, REGION, "bucket"));
    }

    @Test
    void deletionRecreationAndFailedDeletionInvalidateCapturedExportLifetime() {
        S3Service service = service(null);
        seed(REGION, ACCOUNT);
        LogExportDestination before = admit(service);
        service.deleteBucket("bucket");
        service.createBucket("bucket", REGION);
        Bucket fresh = buckets.getForAccount(ACCOUNT, "bucket").orElseThrow();
        fresh.setPolicy(policy(REGION, ACCOUNT));
        assertThrows(AwsException.class, () -> service.writeLogExportObject(before, SOURCE, KEY, new byte[] {1}));
        LogExportDestination current = admit(service);
        service.writeLogExportObject(current, SOURCE, KEY, new byte[] {2});
        assertEquals("BucketNotEmpty", assertThrows(AwsException.class,
                () -> service.deleteBucket("bucket")).getErrorCode());
        assertThrows(AwsException.class, () -> service.writeLogExportObject(current, SOURCE, KEY + "next", new byte[] {3}));
        assertArrayEquals(new byte[] {2}, service.getObject("bucket", KEY).getData());
    }

    @Test
    void resetInvalidatesReceiptEvenWhenSameBucketMetadataIsRestored() {
        S3Service service = service(null);
        seed(REGION, ACCOUNT);
        LogExportDestination before = admit(service);
        service.clear();
        assertThrows(AwsException.class, () -> service.writeLogExportObject(before, SOURCE, KEY, new byte[] {1}));
        assertTrue(objects.keysForAccount(ACCOUNT).isEmpty());
    }

    @Test
    void notificationCallbackCanAcquireBucketWriteStripeAfterTheObjectCommit() throws Exception {
        AtomicBoolean callbackCompleted = new AtomicBoolean();
        S3Service[] reference = new S3Service[1];
        S3Service service = service((region, function, payload, type) -> {
            CompletableFuture<Void> writeStripe = CompletableFuture.runAsync(() -> {
                assertEquals("BucketNotEmpty", assertThrows(AwsException.class,
                        () -> reference[0].deleteBucket("bucket")).getErrorCode());
            });
            try {
                writeStripe.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("Notification callback could not acquire the bucket write stripe", e);
            }
            callbackCompleted.set(true);
        });
        reference[0] = service;
        Bucket bucket = seed(REGION, ACCOUNT);
        NotificationConfiguration notifications = new NotificationConfiguration();
        notifications.setLambdaFunctionConfigurations(List.of(new LambdaNotification("notify",
                "arn:aws:lambda:us-east-1:123456789012:function:callback", List.of("s3:ObjectCreated:*"))));
        bucket.setNotificationConfiguration(notifications);
        service.writeLogExportObject(admit(service), SOURCE, KEY, "body".getBytes(StandardCharsets.UTF_8));
        assertTrue(callbackCompleted.get(), "notification must complete outside the export's read stripe");
        assertArrayEquals("body".getBytes(StandardCharsets.UTF_8), service.getObject("bucket", KEY).getData());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cn-north-1", "us-gov-west-1"})
    void destinationArnsAndServicePrincipalsFollowSourcePartition(String region) {
        S3Service service = service(null);
        seed(region, ACCOUNT);
        String partition = region.startsWith("cn-") ? "aws-cn" : "aws-us-gov";
        String source = "arn:" + partition + ":logs:" + region + ":" + ACCOUNT + ":log-group:group";
        LogExportDestination read = service.readLogExportDestination(ACCOUNT, region, "bucket");
        assertEquals("arn:" + partition + ":s3:::bucket/" + KEY, read.objectArn(KEY));
        assertNotNull(service.admitLogExportDestination(read, source, List.of(KEY)).epoch());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void detachedBucketMetadataAndRealObjectBodySurviveStorageReopen(String mode) throws Exception {
        Path bodies = directory.resolve("bodies");
        byte[] body = new byte[] {31, (byte) 139, 8, 0, 11, 22, 33};
        StorageFactory first = StorageFactories.open(directory.resolve("metadata"), mode, ACCOUNT);
        try {
            AccountAwareStorageBackend<Bucket> storedBuckets = first.create("s3", "buckets.json",
                    new TypeReference<Map<String, Bucket>>() {});
            AccountAwareStorageBackend<S3Object> storedObjects = first.create("s3", "objects.json",
                    new TypeReference<Map<String, S3Object>>() {});
            Bucket bucket = new Bucket("bucket");
            bucket.setCreationDate(Instant.EPOCH);
            bucket.setRegion(REGION);
            bucket.setPolicy(policy(REGION, ACCOUNT));
            storedBuckets.putForAccount(ACCOUNT, "bucket", bucket);
            S3Service service = new S3Service(storedBuckets, storedObjects, bodies, false, (S3Service.LambdaInvoker) null,
                    new RegionResolver(REGION, ACCOUNT));
            service.writeLogExportObject(admit(service), SOURCE, KEY, body);
            try (InputStream stream = service.openObjectStream("bucket", KEY, null)) {
                assertArrayEquals(body, stream.readAllBytes());
            }
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = StorageFactories.open(directory.resolve("metadata"), mode, ACCOUNT);
        try {
            S3Service service = new S3Service(second.create("s3", "buckets.json",
                    new TypeReference<Map<String, Bucket>>() {}),
                    second.create("s3", "objects.json", new TypeReference<Map<String, S3Object>>() {}),
                    bodies, false, (S3Service.LambdaInvoker) null, new RegionResolver(REGION, ACCOUNT));
            S3Object object = service.getObject("bucket", KEY);
            try (InputStream stream = service.openObjectStream("bucket", KEY, null)) {
                assertArrayEquals(body, stream.readAllBytes());
            }
            assertEquals(body.length, object.getSize());
            assertEquals("application/gzip", object.getContentType());
            assertEquals(ACCOUNT, object.getOwnerAccountId());
            assertNotNull(service.admitLogExportDestination(
                    service.readLogExportDestination(ACCOUNT, REGION, "bucket"), SOURCE, List.of(KEY + "-next")).epoch());
        } finally {
            second.shutdownAll();
        }
    }

    @Test
    void nativeCoreAppliesDefaultEncryptionVersioningAndRetention() {
        S3Service service = service(null);
        Bucket bucket = seed(REGION, ACCOUNT);
        bucket.setVersioningStatus("Enabled");
        bucket.setObjectLockEnabled(true);
        bucket.setDefaultRetention(new ObjectLockRetention("COMPLIANCE", "Days", 1));
        bucket.setEncryptionConfiguration("""
                <ServerSideEncryptionConfiguration><Rule><ApplyServerSideEncryptionByDefault>
                <SSEAlgorithm>AES256</SSEAlgorithm>
                </ApplyServerSideEncryptionByDefault></Rule></ServerSideEncryptionConfiguration>
                """);
        service.writeLogExportObject(admit(service), SOURCE, KEY, new byte[] {1, 2, 3});
        S3Object object = service.getObject("bucket", KEY);
        assertNotNull(object.getVersionId());
        assertNotEquals("null", object.getVersionId());
        assertEquals("AES256", object.getServerSideEncryption());
        assertEquals("COMPLIANCE", object.getObjectLockMode());
        assertTrue(object.getRetainUntilDate().isAfter(Instant.now()));
        assertEquals(ACCOUNT, object.getOwnerAccountId());
    }

    private S3Service service(S3Service.LambdaInvoker invoker) {
        return new S3Service(buckets, objects, directory, true, invoker, new RegionResolver(REGION, ACCOUNT));
    }

    private Bucket seed(String region, String owner) {
        Bucket bucket = new Bucket("bucket");
        bucket.setCreationDate(Instant.EPOCH);
        bucket.setRegion(region);
        bucket.setPolicy(policy(region, owner));
        buckets.putForAccount(owner, "bucket", bucket);
        return bucket;
    }

    private LogExportDestination admit(S3Service service) {
        return service.admitLogExportDestination(service.readLogExportDestination(ACCOUNT, REGION, "bucket"),
                SOURCE, List.of(KEY));
    }

    private static String policy(String region, String owner) {
        String partition = region.startsWith("cn-") ? "aws-cn" : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
        String suffix = region.startsWith("cn-") ? "amazonaws.com.cn" : "amazonaws.com";
        return """
                {"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Principal":{"Service":"logs.%s.%s"},"Action":"s3:GetBucketAcl",
                "Resource":"arn:%s:s3:::bucket",
                "Condition":{"StringEquals":{"aws:SourceAccount":"%s"},
                "ArnLike":{"aws:SourceArn":"arn:%s:logs:%s:%s:log-group:group"},
                "Bool":{"aws:PrincipalIsAWSService":"true"}}},
                {"Effect":"Allow","Principal":{"Service":"logs.%s.%s"},"Action":"s3:PutObject",
                "Resource":"arn:%s:s3:::bucket/*",
                "Condition":{"StringEquals":{"aws:SourceAccount":"%s","s3:x-amz-acl":"bucket-owner-full-control"},
                "ArnLike":{"aws:SourceArn":"arn:%s:logs:%s:%s:log-group:group"},
                "Bool":{"aws:PrincipalIsAWSService":"true"}}}]}
                """.formatted(region, suffix, partition, owner, partition, region, owner,
                        region, suffix, partition, owner, partition, region, owner);
    }
}
