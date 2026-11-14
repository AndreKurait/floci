package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsCanonicalUserId;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WriteProfile;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.testing.StorageFactories;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3AccessLogDeliveryTest {

    private static final String ACCOUNT = "741624100001";
    private static final String OTHER_ACCOUNT = "741624100002";
    private static final String REGION = "us-east-1";
    private static final Instant RECEIVED = Instant.parse("2026-11-12T23:59:58Z");
    private static final Instant DELIVERED = Instant.parse("2026-11-13T00:00:02Z");
    private static final byte[] BODY = "observed request\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({
            "SIMPLE,prefix2026-11-13-00-00-02-unique",
            "EVENT,prefix741624100001/us-east-1/source/2026/11/12/2026-11-12-00-00-00-unique",
            "DELIVERY,prefix741624100001/us-east-1/source/2026/11/13/2026-11-13-00-00-02-unique"
    })
    void keyLayoutsUseTheRequiredClockAndLiteralPrefix(S3AccessLogConfiguration.DateSource source, String expected) {
        S3AccessLogConfiguration configuration = new S3AccessLogConfiguration("target", "prefix", source);
        assertEquals(expected, configuration.objectKey(ACCOUNT, REGION, "source", RECEIVED, DELIVERED, "unique"));
    }

    @Test
    void parserPreservesLiteralPrefixAndDefaultsPartitionedDateToEventTime() {
        S3AccessLogConfiguration parsed = S3AccessLogConfiguration.parse(configuration(
                "<TargetObjectKeyFormat><PartitionedPrefix/></TargetObjectKeyFormat>", " /a&amp;&lt; "));
        assertNotNull(parsed);
        assertEquals(" /a&< ", parsed.prefix());
        assertEquals(S3AccessLogConfiguration.DateSource.EVENT, parsed.dateSource());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<LoggingEnabled/>",
            "<BucketLoggingStatus><LoggingEnabled>",
            "<BucketLoggingStatus/><extra/>",
            "<TargetGrants/>",
            "<TargetBucket>another</TargetBucket>",
            "<TargetObjectKeyFormat><SimplePrefix/><PartitionedPrefix/></TargetObjectKeyFormat>",
            "<TargetObjectKeyFormat><PartitionedPrefix><PartitionDateSource>Unknown</PartitionDateSource></PartitionedPrefix></TargetObjectKeyFormat>"
    })
    void unsupportedOrMalformedStoredXmlDoesNotBecomeADeliveryConfiguration(String fragment) {
        String xml = fragment.startsWith("<BucketLoggingStatus") || fragment.equals("<LoggingEnabled/>")
                ? fragment : configuration(fragment, "logs/");
        assertNull(S3AccessLogConfiguration.parse(xml));
    }

    @Test
    void recordPreservesRawQuotedFieldsAndMarksUnmeasuredFieldsUnavailable() {
        S3AccessLogRecord record = record("agent\"quoted\\literal", 200, 3, 3L);
        String owner = AwsCanonicalUserId.forAccount(ACCOUNT);
        assertEquals(owner + " source [12/Nov/2026:23:59:58 +0000] 127.0.0.1 " + owner
                        + " actual-id REST.GET.OBJECT a%20b+%C3%A9 "
                        + "\"GET /source/a%20b+%C3%A9?versionId=v1 HTTP/1.1\" 200 - 3 3 - - "
                        + "\"-\" \"agent\"quoted\\literal\" v1 actual-host-id SigV4 - AuthHeader localhost - - - -\n",
                new String(record.bytes(ACCOUNT, "source"), StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"line\ninjected", "line\rinjected", "tab\tvalue"})
    void rawControlCharactersCannotInjectLogRecords(String agent) {
        assertNull(record(agent, 200, 0, null).bytes(ACCOUNT, "source"));
    }

    @Test
    void unverifiedRequestsDoNotClaimAnAuthenticatedRequesterAndRecordsAreBounded() {
        String denied = new String(record("agent", 403, 0, null, false).bytes(ACCOUNT, "source"), StandardCharsets.UTF_8);
        assertTrue(denied.contains("127.0.0.1 - actual-id"));
        assertTrue(denied.endsWith("actual-host-id - - - localhost - - - -\n"));
        assertNull(record("a".repeat(S3AccessLogRecord.MAX_RECORD_BYTES), 200, 0, null).bytes(ACCOUNT, "source"));
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404})
    void verifiedErrorsRetainAuthenticationFields(int status) {
        String line = new String(record("agent", status, 0, null).bytes(ACCOUNT, "source"), StandardCharsets.UTF_8);
        assertTrue(line.contains("127.0.0.1 " + AwsCanonicalUserId.forAccount(ACCOUNT) + " actual-id"));
        assertTrue(line.endsWith("actual-host-id SigV4 - AuthHeader localhost - - - -\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void deliveryUsesExistingStorageAndRestartSemantics(String mode) {
        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            S3Service s3 = stored(first, mode);
            setup(s3);
            s3.putBucketVersioning("target", "Enabled");
            s3.deliverAccessLog(s3.accessLogTarget("source"), "logs/record", BODY);
            S3Object written = s3.getObject("target", "logs/record");
            assertArrayEquals(BODY, written.getData());
            assertEquals("AES256", written.getServerSideEncryption());
            assertNotNull(written.getVersionId());
            assertEquals(ACCOUNT, written.getOwnerAccountId());
        } finally {
            first.shutdownAll();
        }
        StorageFactory reopened = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            S3Service s3 = stored(reopened, mode);
            if ("memory".equals(mode)) {
                assertFalse(s3.bucketExists("source"));
            } else {
                assertEquals(configuration("", "logs/"), s3.getBucketLogging("source"));
                assertArrayEquals(BODY, s3.getObject("target", "logs/record").getData());
                assertEquals("AES256", s3.getObject("target", "logs/record").getServerSideEncryption());
                s3.deliverAccessLog(s3.accessLogTarget("source"), "logs/after-restart", BODY);
                assertArrayEquals(BODY, s3.getObject("target", "logs/after-restart").getData());
            }
        } finally {
            reopened.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"disable", "repoint", "recreate-source", "recreate-target", "region", "lock", "payer", "kms"})
    void queuedRecordsCannotOutliveTheirConfigurationOrBucketIncarnations(String change) {
        S3Service s3 = memory();
        setup(s3);
        S3Service.AccessLogTarget captured = s3.accessLogTarget("source");
        assertNotNull(captured);
        switch (change) {
            case "disable" -> s3.putBucketLogging("source", null);
            case "repoint" -> s3.putBucketLogging("source", configuration("", "different/"));
            case "recreate-source" -> {
                s3.deleteBucket("source");
                s3.createBucket("source", REGION);
                s3.putBucketLogging("source", configuration("", "logs/"));
            }
            case "recreate-target" -> {
                s3.deleteBucket("target");
                s3.createBucket("target", REGION);
                s3.putBucketPolicy("target", policy("source", ACCOUNT, false));
            }
            case "region" -> captured.destination().setRegion("us-west-2");
            case "lock" -> captured.destination().setObjectLockEnabled(true);
            case "payer" -> s3.putBucketRequestPayment("target",
                    "<RequestPaymentConfiguration><Payer>Requester</Payer></RequestPaymentConfiguration>");
            case "kms" -> s3.putBucketEncryption("target",
                    "<ServerSideEncryptionConfiguration><Rule><ApplyServerSideEncryptionByDefault>"
                            + "<SSEAlgorithm>aws:kms</SSEAlgorithm></ApplyServerSideEncryptionByDefault>"
                            + "</Rule></ServerSideEncryptionConfiguration>");
            default -> throw new AssertionError(change);
        }
        s3.deliverAccessLog(captured, "logs/record", BODY);
        assertTrue(s3.listObjects("target", null, null, 100).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-policy", "wrong-source", "wrong-account", "deny", "wrong-service"})
    void currentTypedServicePolicyMustAllowTheExactDelivery(String refusal) {
        S3Service s3 = memory();
        setup(s3);
        S3Service.AccessLogTarget captured = s3.accessLogTarget("source");
        switch (refusal) {
            case "missing-policy" -> s3.deleteBucketPolicy("target");
            case "wrong-source" -> s3.putBucketPolicy("target", policy("other-source", ACCOUNT, false));
            case "wrong-account" -> s3.putBucketPolicy("target", policy("source", OTHER_ACCOUNT, false));
            case "deny" -> s3.putBucketPolicy("target", policy("source", ACCOUNT, true));
            case "wrong-service" -> s3.putBucketPolicy("target",
                    policy("source", ACCOUNT, false).replace("logging.s3", "cloudtrail"));
            default -> throw new AssertionError(refusal);
        }
        s3.deliverAccessLog(captured, "logs/record", BODY);
        assertTrue(s3.listObjects("target", null, null, 100).isEmpty());
    }

    @Test
    void legacyLogDeliveryAclNeedsBothGrantsAndCannotOverrideAnExplicitDeny() {
        S3Service s3 = memory();
        s3.createBucket("source", REGION);
        s3.createBucket("target", REGION, "BucketOwnerPreferred", "log-delivery-write",
                null, null, null, null, null);
        s3.putBucketLogging("source", configuration("", "logs/"));
        S3Service.AccessLogTarget captured = s3.accessLogTarget("source");
        s3.deliverAccessLog(captured, "logs/allowed", BODY);
        assertArrayEquals(BODY, s3.getObject("target", "logs/allowed").getData());
        s3.putBucketPolicy("target", policy("source", ACCOUNT, true));
        s3.deliverAccessLog(captured, "logs/denied", BODY);
        assertEquals(1, s3.listObjects("target", null, null, 100).size());
        s3.deleteBucketPolicy("target");
        captured.destination().setAcl(captured.destination().getAcl().replace("READ_ACP", "READ"));
        s3.deliverAccessLog(captured, "logs/missing-acp", BODY);
        assertEquals(1, s3.listObjects("target", null, null, 100).size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void destinationLookupNeverWidensToAnotherAccount(boolean globalNamespace) {
        RequestContext context = new RequestContext();
        context.setAccountId(ACCOUNT);
        @SuppressWarnings("unchecked")
        Instance<RequestContext> instance = mock(Instance.class);
        when(instance.get()).thenReturn(context);
        AccountAwareStorageBackend<Bucket> buckets =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), instance, ACCOUNT);
        AccountAwareStorageBackend<S3Object> objects =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), instance, ACCOUNT);
        S3Service s3 = new S3Service(buckets, objects, directory, true, globalNamespace);
        s3.createBucket("source", REGION);
        s3.putBucketLogging("source", configuration("", "logs/"));
        context.setAccountId(OTHER_ACCOUNT);
        s3.createBucket("target", REGION);
        s3.putBucketPolicy("target", policy("source", ACCOUNT, false));
        context.setAccountId(ACCOUNT);
        assertNull(s3.accessLogTarget("source"));
        assertTrue(objects.scanForAccount(OTHER_ACCOUNT, key -> true).isEmpty());
    }

    @Test
    void queueSaturationDropsOnlyPendingRecordsAndCloseDoesNotRetainWork() throws Exception {
        S3Service s3 = mock(S3Service.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            calls.incrementAndGet();
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            completed.countDown();
            return null;
        }).when(s3).deliverAccessLog(any(), anyString(), any());
        S3Service.AccessLogTarget target = new S3Service.AccessLogTarget(ACCOUNT, REGION, "source",
                new Bucket("source"), configuration("", "logs/"),
                new S3AccessLogConfiguration("target", "logs/", S3AccessLogConfiguration.DateSource.SIMPLE),
                new Bucket("target"));
        S3AccessLogDelivery delivery = new S3AccessLogDelivery(s3, Clock.fixed(DELIVERED, ZoneOffset.UTC), 1);
        try {
            delivery.submit(target, record("agent", 200, 3, 3L));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            delivery.submit(target, record("agent", 200, 3, 3L));
            delivery.submit(target, record("agent", 200, 3, 3L));
            release.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
            delivery.close();
        }
        delivery.submit(target, record("agent", 200, 3, 3L));
        assertEquals(2, calls.get());
    }

    private S3Service memory() {
        return new S3Service(AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT), directory, true);
    }

    private S3Service stored(StorageFactory factory, String mode) {
        return new S3Service(factory.create("s3", "s3-buckets.json",
                new TypeReference<Map<String, Bucket>>() { }),
                factory.create("s3", "s3-objects.json", new TypeReference<Map<String, S3Object>>() { },
                        WriteProfile.APPEND_HEAVY),
                directory.resolve("s3"), "memory".equals(mode));
    }

    private static void setup(S3Service s3) {
        s3.createBucket("source", REGION);
        s3.createBucket("target", REGION);
        s3.putBucketPolicy("target", policy("source", ACCOUNT, false));
        s3.putBucketLogging("source", configuration("", "logs/"));
    }

    private static String configuration(String extra, String prefix) {
        return "<BucketLoggingStatus><LoggingEnabled><TargetBucket>target</TargetBucket><TargetPrefix>"
                + prefix + "</TargetPrefix>" + extra + "</LoggingEnabled></BucketLoggingStatus>";
    }

    private static String policy(String source, String account, boolean deny) {
        String statement = """
                {"Effect":"Allow","Principal":{"Service":"logging.s3.amazonaws.com"},
                 "Action":"s3:PutObject","Resource":"arn:aws:s3:::target/logs/*",
                 "Condition":{"ArnEquals":{"aws:SourceArn":"arn:aws:s3:::%s"},
                 "StringEquals":{"aws:SourceAccount":"%s","aws:RequestedRegion":"us-east-1"},
                 "Bool":{"aws:PrincipalIsAWSService":"true"}}}
                """.formatted(source, account);
        String explicitDeny = deny ? """
                ,{"Effect":"Deny","Principal":"*","Action":"s3:PutObject","Resource":"arn:aws:s3:::target/*"}
                """ : "";
        // CDK's enforceSSL Deny must not mistake the original local HTTP request for a service call.
        String sslDeny = """
                ,{"Effect":"Deny","Principal":"*","Action":"s3:*","Resource":"arn:aws:s3:::target/*",
                  "Condition":{"Bool":{"aws:SecureTransport":"false"}}}
                """;
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + statement + explicitDeny + sslDeny + "]}";
    }

    private static S3AccessLogRecord record(String agent, int status, long bytes, Long size) {
        return record(agent, status, bytes, size, true);
    }

    private static S3AccessLogRecord record(String agent, int status, long bytes, Long size, boolean verified) {
        return new S3AccessLogRecord(RECEIVED, "127.0.0.1", AwsCanonicalUserId.forAccount(ACCOUNT),
                "REST.GET.OBJECT", "a%20b+%C3%A9", "GET /source/a%20b+%C3%A9?versionId=v1 HTTP/1.1",
                null, agent, "v1", "localhost", verified, status, null,
                "actual-id", "actual-host-id", bytes, size);
    }
}
