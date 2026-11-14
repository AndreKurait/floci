package io.floci.aws.services.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectOwnership;
import software.amazon.awssdk.services.s3.model.PartitionedPrefix;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.SimplePrefix;
import software.amazon.awssdk.services.s3.model.TargetObjectKeyFormat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real SDK traffic, destination object reads and response-ID correlation; local timing is bounded. */
@QuarkusTest
@Timeout(90)
class S3ServerAccessLoggingSdkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "741624100002";
    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final Pattern ROW = Pattern.compile(
            "^(\\S+) (\\S+) \\[([^]]+)] (\\S+) (\\S+) (\\S+) (\\S+) (\\S+) \"(.*)\" "
                    + "(\\d{3}) (\\S+) (\\S+) (\\S+) (\\S+) (\\S+) \"(.*)\" \"(.*)\" "
                    + "(\\S+) (\\S+) (\\S+) (\\S+) (\\S+) (\\S+) (\\S+) (\\S+) (\\S+) (\\S+)$");

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    Clock clock;

    @Test
    void realCrudVersionAndRangeResponsesAppearInDeliveredLogObjects() {
        try (Owned owned = new Owned("crud")) {
            Held source = owned.bucket("source", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            Held target = owned.bucket("target", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            owned.allow(target, source, false);
            owned.s3.putBucketVersioning(b -> b.bucket(source.name).versioningConfiguration(v -> v.status("Enabled")));
            owned.s3.putBucketVersioning(b -> b.bucket(target.name).versioningConfiguration(v -> v.status("Enabled")));
            owned.enable(source, target, "logs/", "SimplePrefix");
            byte[] body = "actual uploaded object bytes".getBytes(StandardCharsets.UTF_8);
            PutObjectResponse put = owned.s3.putObject(b -> b.bucket(source.name).key("object"), RequestBody.fromBytes(body));
            String version = put.versionId();
            assertNotNull(version);
            ResponseBytes<GetObjectResponse> get = owned.s3.getObjectAsBytes(b ->
                    b.bucket(source.name).key("object").versionId(version));
            assertArrayEquals(body, get.asByteArray());
            String head = owned.s3.headObject(b -> b.bucket(source.name).key("object").versionId(version))
                    .responseMetadata().requestId();
            ResponseBytes<GetObjectResponse> range = owned.s3.getObjectAsBytes(b ->
                    b.bucket(source.name).key("object").versionId(version).range("bytes=1-3"));
            assertArrayEquals(new byte[]{body[1], body[2], body[3]}, range.asByteArray());
            String list = owned.s3.listObjectsV2(b -> b.bucket(source.name)).responseMetadata().requestId();
            String delete = owned.s3.deleteObject(b -> b.bucket(source.name).key("object").versionId(version))
                    .responseMetadata().requestId();
            S3Exception missing = assertThrows(S3Exception.class, () ->
                    owned.s3.getObjectAsBytes(b -> b.bucket(source.name).key("missing")));
            assertEquals("NoSuchKey", missing.awsErrorDetails().errorCode());
            String missingId = missing.awsErrorDetails().sdkHttpResponse()
                    .firstMatchingHeader("x-amz-request-id").orElseThrow();
            Map<String, LogRow> rows = owned.awaitRows(target, "logs/",
                    Set.of(put.responseMetadata().requestId(), get.response().responseMetadata().requestId(),
                            head, range.response().responseMetadata().requestId(), list, delete, missingId));
            check(rows.get(put.responseMetadata().requestId()), "REST.PUT.OBJECT", 200, "-", "-", "-");
            check(rows.get(get.response().responseMetadata().requestId()), "REST.GET.OBJECT", 200,
                    version, Integer.toString(body.length), Integer.toString(body.length));
            check(rows.get(head), "REST.HEAD.OBJECT", 200, version, "-", Integer.toString(body.length));
            check(rows.get(range.response().responseMetadata().requestId()), "REST.GET.OBJECT", 206,
                    version, "3", Integer.toString(body.length));
            check(rows.get(delete), "REST.DELETE.OBJECT", 204, version, "-", "-");
            assertEquals("REST.GET.BUCKET", rows.get(list).field(7));
            assertEquals("-", rows.get(list).field(8));
            assertEquals("NoSuchKey", rows.get(missingId).field(11));
            assertEquals("404", rows.get(missingId).field(10));
            for (LogRow row : rows.values()) {
                assertEquals(source.name, row.field(2));
                assertEquals("-", row.field(5), "Verification-disabled traffic has no verified requester");
                assertEquals("-", row.field(20));
                assertEquals("-", row.field(22));
                assertEquals("-", row.field(14));
                assertEquals("-", row.field(15));
                assertEquals("-", row.field(27));
                assertFalse(row.field(19).equals("-"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SimplePrefix", "EventTime", "DeliveryTime"})
    void deliversExactKeyLayoutsWithLiteralPrefixes(String format) {
        try (Owned owned = new Owned("layout-" + format)) {
            Held source = owned.bucket("source", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            Held target = owned.bucket("target", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            owned.allow(target, source, false);
            String prefix = "literal &/no-added-slash";
            owned.enable(source, target, prefix, format);
            Instant before = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            String id = owned.put(source, "object");
            LogRow row = owned.awaitRows(target, prefix, Set.of(id)).get(id);
            Instant after = clock.instant().plusSeconds(1);
            String suffix = row.objectKey.substring(prefix.length());
            if (!"SimplePrefix".equals(format)) {
                String required = ACCOUNT + "/us-east-1/" + source.name + "/";
                assertTrue(suffix.startsWith(required));
                suffix = suffix.substring(required.length());
                assertTrue(suffix.matches("\\d{4}/\\d{2}/\\d{2}/\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-.+"));
                String directoryDate = suffix.substring(0, 10);
                suffix = suffix.substring(11);
                assertEquals(suffix.substring(0, 10).replace('-', '/'), directoryDate);
            }
            assertTrue(suffix.matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-[A-Za-z0-9]+"));
            Instant timestamp = LocalDateTime.parse(suffix.substring(0, 19), FILE_TIME).toInstant(ZoneOffset.UTC);
            if ("EventTime".equals(format)) {
                assertEquals(Instant.from(LOG_TIME.parse(row.field(3))).truncatedTo(ChronoUnit.DAYS), timestamp);
            } else {
                assertFalse(timestamp.isBefore(before));
                assertFalse(timestamp.isAfter(after));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void encodedObjectNamesSurvivePathAndVirtualHostRequests(boolean virtualHost) {
        try (Owned owned = new Owned("encoded-" + virtualHost)) {
            Held source = owned.bucket("source", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            Held target = owned.bucket("target", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            owned.allow(target, source, false);
            owned.enable(source, target, "logs/", "SimplePrefix");
            S3Client client = virtualHost ? owned.virtualClient(source) : owned.s3;
            source.objectClient = client;
            String key = (virtualHost ? "/" : "") + "caf\u00e9 space/+plus//slash";
            String id = client.putObject(b -> b.bucket(source.name).key(key), RequestBody.fromString("payload"))
                    .responseMetadata().requestId();
            assertEquals("payload", client.getObjectAsBytes(b -> b.bucket(source.name).key(key)).asUtf8String());
            assertEquals(Set.of(key), owned.keys(source, ""));
            LogRow row = owned.awaitRows(target, "logs/", Set.of(id)).get(id);
            // The pinned Apache transport encodes empty path segments after SDK interception.
            String wireKey = virtualHost ? "%2Fcaf%C3%A9%20space/%2Bplus/%2Fslash"
                    : "caf%C3%A9%20space/%2Bplus//slash";
            assertEquals(wireKey, row.field(8));
            String prefix = virtualHost ? "/" : "/" + source.name + "/";
            assertEquals("PUT " + prefix + wireKey + " HTTP/1.1", row.field(9));
            assertEquals("REST.PUT.OBJECT", row.field(7));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"policy-revoked", "explicit-deny", "requester-pays", "sse-kms"})
    void unsupportedOrDeniedDestinationsDoNotChangeSuccessfulSourceWrites(String change) {
        try (Owned owned = new Owned("destination-" + change)) {
            Held source = owned.bucket("source", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            Held target = owned.bucket("target", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            owned.allow(target, source, false);
            owned.enable(source, target, "logs/", "SimplePrefix");
            owned.awaitRows(target, "logs/", Set.of(owned.put(source, "control")));
            Set<String> before = owned.keys(target, "logs/");
            switch (change) {
                case "policy-revoked" -> owned.s3.deleteBucketPolicy(b -> b.bucket(target.name));
                case "explicit-deny" -> owned.allow(target, source, true);
                case "requester-pays" -> owned.s3.putBucketRequestPayment(b -> b.bucket(target.name)
                        .requestPaymentConfiguration(p -> p.payer("Requester")));
                case "sse-kms" -> owned.s3.putBucketEncryption(b -> b.bucket(target.name)
                        .serverSideEncryptionConfiguration(c -> c.rules(r -> r
                                .applyServerSideEncryptionByDefault(e -> e.sseAlgorithm("aws:kms")))));
                default -> throw new AssertionError(change);
            }
            owned.put(source, "still-successful");
            owned.unchanged(target, "logs/", before);
            assertEquals("payload", owned.s3.getObjectAsBytes(b ->
                    b.bucket(source.name).key("still-successful")).asUtf8String());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"other-region", "object-lock", "missing"})
    void unavailableTargetsReceiveNoObjects(String kind) {
        try (Owned owned = new Owned("unavailable-" + kind)) {
            Held source = owned.bucket("source", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            Region region = "other-region".equals(kind) ? Region.US_WEST_2 : Region.US_EAST_1;
            S3Client client = owned.client(ACCOUNT, region, null, false);
            Held target = owned.bucket("target", client, ACCOUNT, region, "object-lock".equals(kind), false);
            owned.allow(target, source, false);
            owned.enable(source, target, "logs/", "SimplePrefix");
            if ("missing".equals(kind)) {
                owned.deleteBucket(target);
            }
            owned.put(source, "still-successful");
            if ("missing".equals(kind)) {
                sleep(600);
                absent(target.client, target.name);
            } else {
                owned.unchanged(target, "logs/", Set.of());
            }
        }
    }

    @Test
    void sameNamedOtherAccountTargetIsIsolatedAndAnOwnedTargetStillDelivers() {
        try (Owned owned = new Owned("accounts")) {
            Held source = owned.bucket("source", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            S3Client other = owned.client(OTHER_ACCOUNT, Region.US_EAST_1, null, false);
            Held foreign = owned.bucket("target", other, OTHER_ACCOUNT, Region.US_EAST_1, false, false);
            owned.allow(foreign, source, false);
            owned.enable(source, foreign, "logs/", "SimplePrefix");
            owned.put(source, "isolated");
            owned.unchanged(foreign, "logs/", Set.of());
            Held local = owned.bucket("target", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            owned.allow(local, source, false);
            String id = owned.put(source, "owned");
            owned.awaitRows(local, "logs/", Set.of(id));
            owned.unchanged(foreign, "logs/", Set.of());
        }
    }

    @Test
    void disablingAndRepointingLoggingChangesTheActualDestination() {
        try (Owned owned = new Owned("repoint")) {
            Held source = owned.bucket("source", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            Held first = owned.bucket("first", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            Held second = owned.bucket("second", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            owned.allow(first, source, false);
            owned.allow(second, source, false);
            owned.enable(source, first, "logs/", "SimplePrefix");
            owned.awaitRows(first, "logs/", Set.of(owned.put(source, "first")));
            Set<String> before = owned.keys(first, "logs/");
            owned.disable(source);
            owned.put(source, "disabled");
            owned.unchanged(first, "logs/", before);
            owned.enable(source, second, "logs/", "SimplePrefix");
            owned.awaitRows(second, "logs/", Set.of(owned.put(source, "second")));
            owned.unchanged(first, "logs/", before);
        }
    }

    @Test
    void aRecreatedDestinationReceivesNewRequestsWithoutResurrectingDeletedLogObjects() {
        try (Owned owned = new Owned("recreated-target")) {
            Held source = owned.bucket("source", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            Held target = owned.bucket("target", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            owned.allow(target, source, false);
            owned.enable(source, target, "logs/", "SimplePrefix");
            String oldId = owned.put(source, "old-request");
            owned.awaitRows(target, "logs/", Set.of(oldId));
            owned.empty(target);
            owned.deleteBucket(target);
            assertEquals(target, owned.buckets.remove(ACCOUNT + "/" + target.name));
            Held recreated = owned.bucket("target", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            assertEquals(target.name, recreated.name);
            owned.allow(recreated, source, false);
            String newId = owned.put(source, "new-request");
            Map<String, LogRow> rows = owned.awaitRows(recreated, "logs/", Set.of(newId));
            assertFalse(rows.containsKey(oldId));
        }
    }

    @Test
    void legacyLogDeliveryAclWorksAndExplicitDenyWins() {
        try (Owned owned = new Owned("acl")) {
            Held source = owned.bucket("source", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            Held target = owned.bucket("target", owned.s3, ACCOUNT, Region.US_EAST_1, false, true);
            owned.enable(source, target, "logs/", "SimplePrefix");
            owned.awaitRows(target, "logs/", Set.of(owned.put(source, "allowed")));
            Set<String> before = owned.keys(target, "logs/");
            owned.allow(target, source, true);
            owned.put(source, "denied-delivery");
            owned.unchanged(target, "logs/", before);
        }
    }

    @Test
    void loggingToTheSourceBucketDoesNotRecursivelyLogInternalWrites() {
        try (Owned owned = new Owned("self")) {
            Held source = owned.bucket("source", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            owned.allow(source, source, false);
            owned.enable(source, source, "logs/", "SimplePrefix");
            String id = owned.put(source, "object");
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            int listRequests = 0;
            Set<String> keys;
            do {
                keys = owned.keys(source, "logs/");
                listRequests++;
                if (!keys.isEmpty()) {
                    break;
                }
                sleep(100);
            } while (System.nanoTime() < deadline);
            assertFalse(keys.isEmpty(), "The ordinary PUT must produce an actual log object");
            owned.disable(source);
            sleep(300);
            Set<String> finalKeys = owned.keys(source, "logs/");
            assertTrue(finalKeys.size() <= listRequests + 1, "Only the PUT and actual listing requests can produce records");
            assertTrue(owned.rows(source, "logs/").containsKey(id));
            owned.unchanged(source, "logs/", finalKeys);
        }
    }

    @Test
    void unsignedRequestsAreRecordedAndQuerySigningFieldsAreExcluded() {
        try (Owned owned = new Owned("authentication-subset")) {
            Held source = owned.bucket("source", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            Held target = owned.bucket("target", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            owned.put(source, "object");
            owned.allow(target, source, false);
            owned.enable(source, target, "logs/", "SimplePrefix");
            S3Client unsigned = owned.client(ACCOUNT, Region.US_EAST_1, null, true);
            String id = unsigned.getObjectAsBytes(b -> b.bucket(source.name).key("object"))
                    .response().responseMetadata().requestId();
            LogRow row = owned.awaitRows(target, "logs/", Set.of(id)).get(id);
            assertEquals("-", row.field(5));
            assertEquals("-", row.field(20));
            assertEquals("-", row.field(22));
            Set<String> before = owned.keys(target, "logs/");
            String sentinel = "local-query-signature-" + owned.marker;
            S3Client query = owned.client(ACCOUNT, Region.US_EAST_1, new ExecutionInterceptor() {
                @Override
                public SdkHttpRequest modifyHttpRequest(Context.ModifyHttpRequest context, ExecutionAttributes attributes) {
                    return context.httpRequest().toBuilder().putRawQueryParameter("X-Amz-Signature", sentinel).build();
                }
            }, true);
            assertEquals("payload", query.getObjectAsBytes(b -> b.bucket(source.name).key("object")).asUtf8String());
            owned.unchanged(target, "logs/", before);
            assertTrue(owned.rows(target, "logs/").values().stream().noneMatch(value -> value.line.contains(sentinel)));
        }
    }

    @Test
    void cloudFormationLoggingConfigurationProducesRealLogObjects() {
        try (Owned owned = new Owned("cloudformation")) {
            Held source = owned.enroll("source", owned.s3, ACCOUNT, Region.US_EAST_1);
            Held target = owned.enroll("target", owned.s3, ACCOUNT, Region.US_EAST_1);
            owned.createStack(source, target);
            assertEquals(target.name, owned.s3.getBucketLogging(b -> b.bucket(source.name)).loggingEnabled().targetBucket());
            owned.awaitRows(target, "logs/", Set.of(owned.put(source, "cfn-created")));
        }
    }

    @Test
    void aLostCreateResponseStillCleansTheAttemptedBucketBeforeTagsExist() {
        try (Owned owned = new Owned("lost-create-response")) {
            S3Client failing = owned.client(ACCOUNT, Region.US_EAST_1, new ExecutionInterceptor() {
                @Override
                public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
                    if (context.request() instanceof CreateBucketRequest) {
                        throw new IllegalStateException("injected lost CreateBucket response");
                    }
                }
            }, false);
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    owned.bucket("attempted", failing, ACCOUNT, Region.US_EAST_1, false, false));
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertEquals("injected lost CreateBucket response", cause.getMessage());
            Held attempted = owned.buckets.values().iterator().next();
            assertFalse(attempted.tagged);
            assertTrue(owned.exists(attempted));
            // Owned.close verifies the nonce/account/Region/creation window and typed final absence.
        }
    }

    private static void check(LogRow row, String operation, int status, String version, String bytes, String size) {
        assertNotNull(row);
        assertEquals(operation, row.field(7));
        assertEquals(Integer.toString(status), row.field(10));
        assertEquals(version, row.field(18));
        assertEquals(bytes, row.field(12));
        assertEquals(size, row.field(13));
    }

    record LogRow(String objectKey, String line, List<String> fields) {
        String field(int index) {
            return fields.get(index - 1);
        }
    }

    static final class Held {
        final String name;
        final S3Client client;
        S3Client objectClient;
        final String account;
        final Region region;
        final Instant attemptedAt = Instant.now();
        Instant createdAt;
        boolean tagged;

        Held(String name, S3Client client, String account, Region region) {
            this.name = name;
            this.client = client;
            this.objectClient = client;
            this.account = account;
            this.region = region;
        }
    }

    final class Owned implements AutoCloseable {
        final String marker = UUID.randomUUID().toString().replace("-", "");
        final String caseName;
        final WireReceipt wire = new WireReceipt(marker, endpoint.getPort());
        final List<S3Client> clients = new ArrayList<>();
        final Map<String, Held> buckets = new LinkedHashMap<>();
        final S3Client s3;
        final CloudFormationClient cfn;
        final AwsBasicCredentials defaultCredentials;
        final String stackName = "s3-access-log-" + marker;
        boolean stackAttempted;

        Owned(String caseName) {
            this(caseName, AwsBasicCredentials.create(ACCOUNT, "test"));
        }

        Owned(String caseName, AwsBasicCredentials defaultCredentials) {
            this.caseName = caseName;
            this.defaultCredentials = defaultCredentials;
            s3 = client(ACCOUNT, Region.US_EAST_1, null, false);
            cfn = CloudFormationClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(defaultCredentials))
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                            .socketTimeout(Duration.ofSeconds(5)))
                    .overrideConfiguration(c -> c.addExecutionInterceptor(wire).retryPolicy(RetryPolicy.none())
                            .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))).build();
        }

        S3Client client(String account, Region region, ExecutionInterceptor interceptor, boolean anonymous) {
            return client(account, region, interceptor, anonymous,
                    ACCOUNT.equals(account) ? defaultCredentials : AwsBasicCredentials.create(account, "test"));
        }

        S3Client client(String account, Region region, ExecutionInterceptor interceptor, boolean anonymous,
                        AwsBasicCredentials credentials) {
            S3Client client = S3Client.builder().endpointOverride(endpoint).region(region).forcePathStyle(true)
                    .credentialsProvider(anonymous ? AnonymousCredentialsProvider.create()
                            : StaticCredentialsProvider.create(credentials))
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                            .socketTimeout(Duration.ofSeconds(5)))
                    .overrideConfiguration(c -> {
                        if (interceptor != null) {
                            c.addExecutionInterceptor(interceptor);
                        }
                        c.addExecutionInterceptor(wire).retryPolicy(RetryPolicy.none())
                                .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8));
                    }).build();
            clients.add(client);
            return client;
        }

        S3Client virtualClient(Held source) {
            S3Client client = S3Client.builder().endpointOverride(endpoint).region(source.region).forcePathStyle(false)
                    .credentialsProvider(StaticCredentialsProvider.create(ACCOUNT.equals(source.account)
                            ? defaultCredentials : AwsBasicCredentials.create(source.account, "test")))
                    .httpClientBuilder(ApacheHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                            .socketTimeout(Duration.ofSeconds(5)).connectionAcquisitionTimeout(Duration.ofSeconds(3))
                            .proxyConfiguration(ProxyConfiguration.builder().useSystemPropertyValues(false)
                                    .useEnvironmentVariableValues(false).build())
                            .dnsResolver(host -> {
                                assertTrue(host.equals(source.name + ".localhost") || host.equals("localhost"));
                                return new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})};
                            }))
                    .overrideConfiguration(c -> c.addExecutionInterceptor(wire).retryPolicy(RetryPolicy.none())
                            .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))).build();
            clients.add(client);
            return client;
        }

        Held enroll(String suffix, S3Client client, String account, Region region) {
            String name = "sal-" + marker + "-" + suffix;
            String identity = account + "/" + name;
            assertFalse(buckets.containsKey(identity));
            absent(client, name);
            Held held = new Held(name, client, account, region);
            buckets.put(identity, held);
            return held;
        }

        Held bucket(String suffix, S3Client client, String account, Region region, boolean lock, boolean legacyAcl) {
            Held held = enroll(suffix, client, account, region);
            client.createBucket(b -> {
                b.bucket(held.name);
                if (!Region.US_EAST_1.equals(region)) {
                    b.createBucketConfiguration(c -> c.locationConstraint(region.id()));
                }
                if (lock) {
                    b.objectLockEnabledForBucket(true);
                }
                if (legacyAcl) {
                    b.objectOwnership(ObjectOwnership.BUCKET_OWNER_PREFERRED);
                }
            });
            if (legacyAcl) {
                String owner = client.getBucketAcl(b -> b.bucket(held.name)).owner().id();
                client.putBucketAcl(b -> b.bucket(held.name)
                        .grantFullControl("id=\"" + owner + "\"")
                        .grantWrite("uri=\"http://acs.amazonaws.com/groups/s3/LogDelivery\"")
                        .grantReadACP("uri=\"http://acs.amazonaws.com/groups/s3/LogDelivery\""));
            }
            held.createdAt = listed(held).creationDate();
            client.putBucketTagging(b -> b.bucket(held.name).tagging(t ->
                    t.tagSet(tag -> tag.key("access-log-test-owner").value(marker))));
            held.tagged = true;
            verifyOwner(held);
            return held;
        }

        Bucket listed(Held held) {
            return held.client.listBuckets().buckets().stream().filter(bucket -> bucket.name().equals(held.name))
                    .findFirst().orElseThrow(() -> new AssertionError("Attempted bucket is absent from its account"));
        }

        boolean exists(Held held) {
            try {
                String location = held.client.getBucketLocation(b -> b.bucket(held.name).expectedBucketOwner(held.account))
                        .locationConstraintAsString();
                assertEquals(held.region.id(), location == null || location.isEmpty() ? "us-east-1" : location);
                return true;
            } catch (S3Exception absent) {
                missing(absent);
                return false;
            }
        }

        void verifyOwner(Held held) {
            assertTrue(held.name.startsWith("sal-" + marker + "-"));
            assertTrue(exists(held));
            Instant created = listed(held).creationDate();
            assertNotNull(created);
            assertFalse(created.isBefore(held.attemptedAt.minusSeconds(2)));
            assertFalse(created.isAfter(Instant.now().plusSeconds(2)));
            if (held.createdAt != null) {
                assertEquals(held.createdAt, created, "A recreated bucket must not be mistaken for this attempt");
            }
            try {
                Map<String, String> tags = held.client.getBucketTagging(b -> b.bucket(held.name)).tagSet().stream()
                        .collect(Collectors.toMap(tag -> tag.key(), tag -> tag.value()));
                assertEquals(marker, tags.get("access-log-test-owner"));
            } catch (S3Exception missingTags) {
                assertFalse(held.tagged, "A confirmed ownership tag must still be present");
                assertEquals(404, missingTags.statusCode());
                assertEquals("NoSuchTagSet", missingTags.awsErrorDetails().errorCode());
                // This fixture installs tags after creation. Typed initial absence, the attempted
                // nonce name, account/Region and creation window bound this lost-response recovery.
            }
        }

        void allow(Held target, Held source, boolean deny) {
            target.client.putBucketPolicy(b -> b.bucket(target.name).policy(policy(target, source, deny)));
        }

        void enable(Held source, Held target, String prefix, String format) {
            TargetObjectKeyFormat keyFormat = "SimplePrefix".equals(format)
                    ? TargetObjectKeyFormat.builder().simplePrefix(SimplePrefix.builder().build()).build()
                    : TargetObjectKeyFormat.builder().partitionedPrefix(PartitionedPrefix.builder()
                            .partitionDateSource(format).build()).build();
            source.client.putBucketLogging(b -> b.bucket(source.name).bucketLoggingStatus(status ->
                    status.loggingEnabled(logging -> logging.targetBucket(target.name).targetPrefix(prefix)
                            .targetObjectKeyFormat(keyFormat))));
        }

        void disable(Held source) {
            source.client.putBucketLogging(b -> b.bucket(source.name).bucketLoggingStatus(status -> { }));
        }

        String put(Held source, String key) {
            return source.client.putObject(b -> b.bucket(source.name).key(key), RequestBody.fromString("payload"))
                    .responseMetadata().requestId();
        }

        Set<String> keys(Held target, String prefix) {
            Set<String> keys = new LinkedHashSet<>();
            target.client.listObjectsV2Paginator(b -> b.bucket(target.name).prefix(prefix)).contents()
                    .forEach(object -> keys.add(object.key()));
            return keys;
        }

        Map<String, LogRow> rows(Held target, String prefix) {
            Map<String, LogRow> result = new LinkedHashMap<>();
            for (String key : keys(target, prefix)) {
                ResponseBytes<GetObjectResponse> response =
                        target.client.getObjectAsBytes(b -> b.bucket(target.name).key(key));
                assertEquals("AES256", response.response().serverSideEncryptionAsString());
                String body = response.asUtf8String();
                assertTrue(body.endsWith("\n"));
                for (String line : body.split("\n")) {
                    Matcher matcher = ROW.matcher(line);
                    assertTrue(matcher.matches(), "Delivered object must have the documented field layout: " + line);
                    List<String> fields = new ArrayList<>();
                    for (int field = 1; field <= matcher.groupCount(); field++) {
                        fields.add(matcher.group(field));
                    }
                    result.put(matcher.group(6), new LogRow(key, line, List.copyOf(fields)));
                }
            }
            return result;
        }

        Map<String, LogRow> awaitRows(Held target, String prefix, Set<String> requestIds) {
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            Map<String, LogRow> found;
            do {
                found = rows(target, prefix);
                if (found.keySet().containsAll(requestIds)) {
                    return found;
                }
                sleep(100);
            } while (System.nanoTime() < deadline);
            assertTrue(found.keySet().containsAll(requestIds), "Actual SDK response IDs must appear in delivered objects");
            return found;
        }

        void unchanged(Held target, String prefix, Set<String> before) {
            long deadline = System.nanoTime() + Duration.ofMillis(600).toNanos();
            do {
                assertEquals(before, keys(target, prefix));
                sleep(100);
            } while (System.nanoTime() < deadline);
        }

        void createStack(Held source, Held target) {
            missingStack(assertThrows(CloudFormationException.class, () ->
                    cfn.describeStacks(b -> b.stackName(stackName))), stackName);
            ObjectNode template = MAPPER.createObjectNode();
            ObjectNode resources = template.putObject("Resources");
            ObjectNode destination = resources.putObject("Destination").put("Type", "AWS::S3::Bucket")
                    .putObject("Properties").put("BucketName", target.name);
            destination.putArray("Tags").addObject().put("Key", "access-log-test-owner").put("Value", marker);
            ObjectNode policy = resources.putObject("Policy").put("Type", "AWS::S3::BucketPolicy")
                    .putObject("Properties");
            policy.putObject("Bucket").put("Ref", "Destination");
            try {
                policy.set("PolicyDocument", MAPPER.readTree(policy(target, source, false)));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            ObjectNode origin = resources.putObject("Source").put("Type", "AWS::S3::Bucket")
                    .put("DependsOn", "Policy").putObject("Properties").put("BucketName", source.name);
            origin.putArray("Tags").addObject().put("Key", "access-log-test-owner").put("Value", marker);
            origin.putObject("LoggingConfiguration").put("DestinationBucketName", target.name).put("LogFilePrefix", "logs/");
            stackAttempted = true;
            cfn.createStack(b -> b.stackName(stackName).templateBody(template.toString())
                    .tags(tag -> tag.key("access-log-test-owner").value(marker)));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            Stack stack;
            do {
                stack = stack();
                if ("CREATE_COMPLETE".equals(stack.stackStatusAsString())) {
                    break;
                }
                assertEquals("CREATE_IN_PROGRESS", stack.stackStatusAsString());
                sleep(100);
            } while (System.nanoTime() < deadline);
            assertEquals("CREATE_COMPLETE", stack.stackStatusAsString());
            source.tagged = true;
            target.tagged = true;
            source.createdAt = listed(source).creationDate();
            target.createdAt = listed(target).creationDate();
            verifyOwner(source);
            verifyOwner(target);
        }

        Stack stack() {
            Stack stack = cfn.describeStacks(b -> b.stackName(stackName)).stacks().getFirst();
            assertEquals(stackName, stack.stackName());
            assertTrue(stack.stackId().startsWith("arn:aws:cloudformation:us-east-1:" + ACCOUNT + ":stack/" + stackName + "/"));
            assertEquals(marker, stack.tags().stream().filter(tag -> tag.key().equals("access-log-test-owner"))
                    .findFirst().orElseThrow().value());
            return stack;
        }

        void empty(Held held) {
            verifyOwner(held);
            held.client.listObjectVersionsPaginator(b -> b.bucket(held.name)).forEach(page -> {
                page.versions().forEach(version -> held.objectClient.deleteObject(b ->
                        b.bucket(held.name).key(version.key()).versionId(version.versionId())));
                page.deleteMarkers().forEach(marker -> held.objectClient.deleteObject(b ->
                        b.bucket(held.name).key(marker.key()).versionId(marker.versionId())));
            });
            assertTrue(keys(held, "").isEmpty());
        }

        void deleteBucket(Held held) {
            verifyOwner(held);
            held.client.deleteBucket(b -> b.bucket(held.name).expectedBucketOwner(held.account));
            absent(held.client, held.name);
        }

        @Override
        public void close() {
            AssertionError failures = new AssertionError("Owned S3 access-log cleanup failed");
            for (Held held : buckets.values()) {
                cleanup(() -> {
                    if (exists(held)) {
                        verifyOwner(held);
                        disable(held);
                    }
                }, failures);
            }
            for (Held held : buckets.values()) {
                cleanup(() -> {
                    if (exists(held)) {
                        empty(held);
                    }
                }, failures);
            }
            if (stackAttempted) {
                cleanup(() -> {
                    try {
                        stack();
                    } catch (CloudFormationException absent) {
                        missingStack(absent, stackName);
                        return;
                    }
                    cfn.deleteStack(b -> b.stackName(stackName));
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    do {
                        try {
                            stack();
                        } catch (CloudFormationException absent) {
                            missingStack(absent, stackName);
                            return;
                        }
                        sleep(100);
                    } while (System.nanoTime() < deadline);
                    throw new AssertionError("Owned stack deletion did not close");
                }, failures);
            }
            for (Held held : buckets.values()) {
                cleanup(() -> {
                    if (exists(held)) {
                        empty(held);
                        deleteBucket(held);
                    }
                    absent(held.client, held.name);
                }, failures);
            }
            cleanup(() -> wire.save(caseName, marker, failures.getSuppressed().length == 0), failures);
            cleanup(cfn::close, failures);
            clients.forEach(client -> cleanup(client::close, failures));
            if (failures.getSuppressed().length > 0) {
                throw failures;
            }
        }
    }

    private static String policy(Held target, Held source, boolean deny) {
        String allow = """
                {"Effect":"Allow","Principal":{"Service":"logging.s3.amazonaws.com"},
                 "Action":"s3:PutObject","Resource":"arn:aws:s3:::%s/*",
                 "Condition":{"ArnEquals":{"aws:SourceArn":"arn:aws:s3:::%s"},
                   "StringEquals":{"aws:SourceAccount":"%s"}}}
                """.formatted(target.name, source.name, source.account);
        String refusal = deny ? """
                ,{"Effect":"Deny","Principal":"*","Action":"s3:PutObject","Resource":"arn:aws:s3:::%s/*"}
                """.formatted(target.name) : "";
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + allow + refusal + "]}";
    }

    private static void missing(S3Exception error) {
        assertEquals(404, error.statusCode());
        assertEquals("NoSuchBucket", error.awsErrorDetails().errorCode());
    }

    private static void absent(S3Client client, String bucket) {
        missing(assertThrows(S3Exception.class, () -> client.getBucketLocation(b -> b.bucket(bucket))));
    }

    private static void missingStack(CloudFormationException error, String lookup) {
        assertEquals(400, error.statusCode());
        assertEquals("ValidationError", error.awsErrorDetails().errorCode());
        assertEquals("Stack with id " + lookup + " does not exist", error.awsErrorDetails().errorMessage());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void cleanup(Runnable task, AssertionError failures) {
        try {
            task.run();
        } catch (Throwable failure) {
            failures.addSuppressed(failure);
        }
    }

    private static final class WireReceipt implements ExecutionInterceptor {
        private static final ExecutionAttribute<Integer> ID = new ExecutionAttribute<>("s3-access-log-wire-id");
        private final List<Map<String, Object>> calls = new ArrayList<>();
        private final String marker;
        private final int port;

        WireReceipt(String marker, int port) {
            this.marker = marker;
            this.port = port;
        }

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            String host = context.httpRequest().getUri().getHost();
            assertTrue(host.equals("localhost") || host.startsWith("sal-" + marker + "-")
                    && host.endsWith(".localhost"));
            assertEquals(port, context.httpRequest().getUri().getPort());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            assertTrue(calls.size() < 3000);
            attributes.putAttribute(ID, calls.size());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", calls.size());
            row.put("operation", context.request().getClass().getSimpleName().replaceFirst("Request$", ""));
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            row.put("requestModel", model(context.request()));
            Map<String, List<String>> headers = new LinkedHashMap<>(context.httpRequest().headers());
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("authorization")
                    || name.equalsIgnoreCase("x-amz-security-token"));
            row.put("requestHeaders", headers);
            row.put("requestBodyAvailable", context.requestBody().isPresent());
            try (InputStream stream = context.requestBody().isPresent()
                    ? context.requestBody().orElseThrow().contentStreamProvider().newStream()
                    : InputStream.nullInputStream()) {
                row.put("requestBase64", encode(stream));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            calls.add(row);
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                               ExecutionAttributes attributes) {
            Map<String, Object> row = calls.get(attributes.getAttribute(ID));
            row.put("status", context.httpResponse().statusCode());
            row.put("responseHeaders", context.httpResponse().headers());
            try (InputStream stream = context.responseBody().orElseGet(InputStream::nullInputStream)) {
                String encoded = encode(stream);
                row.put("responseBase64", encoded);
                return Optional.of(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
            calls.get(attributes.getAttribute(ID)).put("responseModel", model(context.response()));
        }

        @Override
        public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
            Integer id = attributes.getAttribute(ID);
            if (id == null) {
                return;
            }
            Map<String, Object> row = calls.get(id);
            row.put("exceptionType", context.exception().getClass().getName());
            if (context.exception() instanceof AwsServiceException failure) {
                row.put("errorCode", failure.awsErrorDetails().errorCode());
                row.put("errorMessage", failure.awsErrorDetails().errorMessage());
                row.put("errorStatus", failure.statusCode());
            }
        }

        private static Object model(Object value) {
            if (value instanceof SdkPojo pojo) {
                Map<String, Object> fields = new LinkedHashMap<>();
                pojo.sdkFields().forEach(field -> {
                    Object member = field.getValueOrDefault(pojo);
                    if (member != null) {
                        fields.put(field.memberName(), model(member));
                    }
                });
                return fields;
            }
            if (value instanceof List<?> list) {
                return list.stream().map(WireReceipt::model).toList();
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> fields = new LinkedHashMap<>();
                map.forEach((key, member) -> fields.put(key.toString(), model(member)));
                return fields;
            }
            if (value instanceof SdkBytes bytes) {
                return Base64.getEncoder().encodeToString(bytes.asByteArray());
            }
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                return value;
            }
            return value.toString();
        }

        private static String encode(InputStream stream) throws IOException {
            byte[] bytes = stream.readNBytes(1_048_577);
            assertTrue(bytes.length <= 1_048_576);
            return Base64.getEncoder().encodeToString(bytes);
        }

        private void save(String caseName, String marker, boolean cleanupComplete) {
            try {
                Path directory = Path.of(System.getProperty("audit.s3.access.log.directory",
                        "target/s3-access-log-wire"));
                Files.createDirectories(directory);
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(marker + ".json").toFile(),
                        Map.of("case", caseName, "marker", marker, "cleanupComplete", cleanupComplete, "calls", calls));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "every attempted transmission has a complete captured response");
        }
    }
}
