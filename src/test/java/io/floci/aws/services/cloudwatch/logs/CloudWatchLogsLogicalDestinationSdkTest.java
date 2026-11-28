package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeDestinationsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.Destination;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.SubscriptionFilter;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.KinesisException;
import software.amazon.awssdk.services.kinesis.model.Record;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.LambdaException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real SDK controls for account-isolated logical destination admission and delivery. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
@Timeout(120)
class CloudWatchLogsLogicalDestinationSdkTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SOURCE = "815670000011";
    private static final String RECIPIENT = "815670000012";
    private static final Region REGION = Region.US_EAST_1;
    private static final Region TARGET_REGION = Region.US_WEST_2;

    @Test
    void recipientControlAndCrossAccountDataReachTheExactNativeStream(TestInfo info) throws Exception {
        try (Owned owned = new Owned(info)) {
            String arn = owned.destination("first");
            List<JsonNode> controls = owned.records();
            assertEquals(1, controls.size());
            assertEquals("CONTROL_MESSAGE", controls.getFirst().path("messageType").asText());
            assertEquals(RECIPIENT, controls.getFirst().path("owner").asText());
            owned.grant(arn, SOURCE);
            owned.subscribe(arn, "ERROR");
            owned.emit("INFO ignored", "ERROR delivered");
            List<JsonNode> records = owned.records();
            assertEquals(2, records.size());
            JsonNode data = records.get(1);
            assertEquals("DATA_MESSAGE", data.path("messageType").asText());
            assertEquals(SOURCE, data.path("owner").asText());
            assertEquals(owned.group, data.path("logGroup").asText());
            assertEquals("source", data.path("logStream").asText());
            assertEquals(List.of("filter"), MAPPER.convertValue(data.path("subscriptionFilters"), List.class));
            assertEquals(1, data.path("logEvents").size());
            assertEquals("ERROR delivered", data.path("logEvents").get(0).path("message").asText());
            assertFalse(data.path("logEvents").get(0).path("id").asText().isBlank());
            assertTrue(data.path("logEvents").get(0).path("timestamp").asLong() > 0);
            missingStream(assertThrows(KinesisException.class, () -> owned.sourceKinesis.describeStream(
                    request -> request.streamName(owned.stream))));
        }
    }

    @Test
    void policyRevocationUnsupportedDenyAndDestinationDeletionStopLaterData(TestInfo info) throws Exception {
        try (Owned owned = new Owned(info)) {
            String arn = owned.destination("first");
            denied(() -> owned.subscribe(arn, ""));
            assertTrue(owned.filters().isEmpty());
            owned.grant(arn, SOURCE);
            owned.subscribe(arn, "");
            SubscriptionFilter filter = owned.filters().getFirst();
            owned.emit("first");
            List<JsonNode> delivered = owned.records();
            assertEquals(2, delivered.size());
            owned.grant(arn, "815670000099");
            owned.emit("revoked");
            assertEquals(delivered, owned.records());
            assertEquals(List.of(filter), owned.filters());
            String policy = """
                    {"Statement":[{"Effect":"Allow","Principal":"*","Action":"logs:*","Resource":"*"},
                    {"Effect":"Deny","Principal":"*","Action":"logs:*","Resource":"*",
                    "Condition":{"UnknownOperator":{"aws:PrincipalAccount":"%s"}}}]}
                    """.formatted(SOURCE);
            owned.receiver.putDestinationPolicy(request -> request.destinationName(owned.name("first"))
                    .accessPolicy(policy));
            Destination unchanged = owned.describe("first");
            denied(() -> owned.subscribe(arn, "changed"));
            owned.emit("unsupported-deny");
            assertEquals(delivered, owned.records());
            assertEquals(unchanged, owned.describe("first"));
            assertEquals(List.of(filter), owned.filters());
            owned.grant(arn, SOURCE);
            owned.emit("restored");
            assertEquals(3, owned.records().size());
            owned.receiver.deleteDestination(request -> request.destinationName(owned.name("first")));
            owned.emit("destination-deleted");
            assertEquals(3, owned.records().size());
            assertTrue(owned.receiver.describeDestinations(request -> request.destinationNamePrefix(owned.name("first")))
                    .destinations().isEmpty());
            assertEquals("ACTIVE", owned.kinesis.describeStream(request -> request.streamName(owned.stream))
                    .streamDescription().streamStatusAsString());
        }
    }

    @Test
    void invalidTargetsAndFailedControlDeliveryPreserveTheFullPriorDestination(TestInfo info) throws Exception {
        try (Owned owned = new Owned(info)) {
            String arn = owned.destination("first");
            owned.grant(arn, SOURCE);
            Destination before = owned.describe("first");
            List<JsonNode> records = owned.records();
            for (String target : List.of(owned.targetArn + "-missing",
                    owned.targetArn.replace(RECIPIENT, SOURCE),
                    "arn:aws:firehose:us-west-2:" + RECIPIENT + ":deliverystream/unsupported")) {
                invalid(() -> owned.receiver.putDestination(request -> request.destinationName(owned.name("first"))
                        .targetArn(target).roleArn(owned.roleArn)));
                assertEquals(before, owned.describe("first"));
                assertEquals(records, owned.records());
            }
            invalid(() -> owned.receiver.putDestination(request -> request.destinationName(owned.name("first"))
                    .targetArn(owned.targetArn).roleArn(owned.roleArn + "-missing")));
            assertEquals(before, owned.describe("first"));
            owned.rolePermission(owned.targetArn + "-other");
            invalid(() -> owned.receiver.putDestination(request -> request.destinationName(owned.name("first"))
                    .targetArn(owned.targetArn).roleArn(owned.roleArn)));
            assertEquals(before, owned.describe("first"));
            assertEquals(records, owned.records());
            owned.destinationNames.add(owned.name("new"));
            invalid(() -> owned.receiver.putDestination(request -> request.destinationName(owned.name("new"))
                    .targetArn(owned.targetArn).roleArn(owned.roleArn)));
            assertTrue(owned.receiver.describeDestinations(request -> request.destinationNamePrefix(owned.name("new")))
                    .destinations().isEmpty());
            assertEquals(records, owned.records());
        }
    }

    @Test
    void recipientRoleTrustAndTargetPermissionAreRecheckedForSenderData(TestInfo info) throws Exception {
        try (Owned owned = new Owned(info)) {
            String arn = owned.destination("first");
            owned.grant(arn, SOURCE);
            owned.subscribe(arn, "");
            owned.emit("allowed");
            List<JsonNode> delivered = owned.records();
            owned.iam.updateAssumeRolePolicy(request -> request.roleName(owned.role).policyDocument(trust(RECIPIENT)));
            invalid(() -> owned.subscribe(arn, "updated"));
            owned.emit("sender-no-longer-trusted");
            assertEquals(delivered, owned.records());
            owned.iam.updateAssumeRolePolicy(request -> request.roleName(owned.role).policyDocument(trust(SOURCE, RECIPIENT)));
            owned.rolePermission(owned.targetArn + "-other");
            owned.emit("target-permission-removed");
            assertEquals(delivered, owned.records());
            owned.rolePermission(owned.targetArn);
            owned.emit("allowed-again");
            assertEquals(delivered.size() + 1, owned.records().size());
            assertEquals("", owned.filters().getFirst().filterPattern());
        }
    }

    @Test
    void destinationPagesAndDeletionRemainBoundToAccountRegionAndPrefix(TestInfo info) throws Exception {
        try (Owned owned = new Owned(info)) {
            owned.destination("z");
            owned.destination("a");
            DescribeDestinationsResponse page = owned.receiver.describeDestinations(request ->
                    request.destinationNamePrefix(owned.marker).limit(1));
            assertEquals(List.of(owned.name("a")), page.destinations().stream().map(Destination::destinationName).toList());
            assertNotNull(page.nextToken());
            DescribeDestinationsResponse terminal = owned.receiver.describeDestinations(request ->
                    request.destinationNamePrefix(owned.marker).limit(1).nextToken(page.nextToken()));
            assertEquals(List.of(owned.name("z")), terminal.destinations().stream().map(Destination::destinationName).toList());
            assertNull(terminal.nextToken());
            invalid(() -> owned.sender.describeDestinations(request -> request.destinationNamePrefix(owned.marker)
                    .limit(1).nextToken(page.nextToken())));
            invalid(() -> owned.receiverOtherRegion.describeDestinations(request -> request.destinationNamePrefix(owned.marker)
                    .limit(1).nextToken(page.nextToken())));
            invalid(() -> owned.receiver.describeDestinations(request -> request.destinationNamePrefix(owned.name("a"))
                    .limit(1).nextToken(page.nextToken())));
            assertTrue(owned.sender.describeDestinations(request -> request.destinationNamePrefix(owned.marker))
                    .destinations().isEmpty());
            missingDestination(assertThrows(CloudWatchLogsException.class, () -> owned.sender.deleteDestination(
                    request -> request.destinationName(owned.name("a")))));
            assertEquals(2, owned.receiver.describeDestinations(request -> request.destinationNamePrefix(owned.marker))
                    .destinations().size());
            assertEquals(2, owned.records().size());
        }
    }

    @Test
    void lambdaAdmissionRequiresAnExistingPermittedFunctionAndKeepsMutableCreationTime(TestInfo info) {
        try (Owned owned = new Owned(info)) {
            String functionArn = "arn:aws:lambda:us-east-1:" + SOURCE + ":function:" + owned.marker;
            invalid(() -> owned.subscribe(functionArn, ""));
            assertTrue(owned.filters().isEmpty());
            owned.createFunction();
            invalid(() -> owned.subscribe(functionArn, ""));
            assertTrue(owned.filters().isEmpty());
            owned.lambda.addPermission(request -> request.functionName(owned.marker).statementId("logs")
                    .principal("logs.amazonaws.com").action("lambda:InvokeFunction").sourceAccount(SOURCE)
                    .sourceArn("arn:aws:logs:us-east-1:" + SOURCE + ":log-group:" + owned.group + ":*"));
            owned.subscribe(functionArn, "");
            SubscriptionFilter before = owned.filters().getFirst();
            assertNotNull(before.creationTime());
            owned.subscribe(functionArn, "ERROR");
            SubscriptionFilter updated = owned.filters().getFirst();
            assertEquals(before.creationTime(), updated.creationTime());
            assertEquals("ERROR", updated.filterPattern());
            owned.lambda.removePermission(request -> request.functionName(owned.marker).statementId("logs"));
            invalid(() -> owned.subscribe(functionArn, "WARN"));
            assertEquals(List.of(updated), owned.filters());
        }
    }

    private static String trust(String... accounts) {
        String accountList = Arrays.stream(accounts).map(account -> "\"" + account + "\"")
                .collect(Collectors.joining(","));
        String arns = Arrays.stream(accounts).map(account -> "\"arn:aws:logs:us-east-1:" + account + ":*\"")
                .collect(Collectors.joining(","));
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"logs.amazonaws.com"},
                "Action":"sts:AssumeRole","Condition":{"StringEquals":{"aws:SourceAccount":[%s]},
                "ArnLike":{"aws:SourceArn":[%s]}}}]}
                """.formatted(accountList, arns);
    }

    private static final class Owned implements AutoCloseable {
        private final String marker = "logs-native-" + UUID.randomUUID().toString();
        private final String group = "/" + marker;
        private final String stream = marker;
        private final String role = marker;
        private final String roleArn = "arn:aws:iam::" + RECIPIENT + ":role/" + role;
        private final String targetArn = "arn:aws:kinesis:us-west-2:" + RECIPIENT + ":stream/" + stream;
        private final WireReceipt wire = new WireReceipt();
        private final CloudWatchLogsClient sender = client(CloudWatchLogsClient.builder(), SOURCE, REGION);
        private final CloudWatchLogsClient receiver = client(CloudWatchLogsClient.builder(), RECIPIENT, REGION);
        private final CloudWatchLogsClient receiverOtherRegion = client(CloudWatchLogsClient.builder(), RECIPIENT, TARGET_REGION);
        private final KinesisClient kinesis = client(KinesisClient.builder(), RECIPIENT, TARGET_REGION);
        private final KinesisClient sourceKinesis = client(KinesisClient.builder(), SOURCE, TARGET_REGION);
        private final IamClient iam = client(IamClient.builder(), RECIPIENT, REGION);
        private final IamClient sourceIam = client(IamClient.builder(), SOURCE, REGION);
        private final LambdaClient lambda = client(LambdaClient.builder(), SOURCE, REGION);
        private final Set<String> destinationNames = new LinkedHashSet<>();
        private final String caseName;
        private boolean roleAttempted;
        private boolean streamAttempted;
        private boolean groupAttempted;
        private boolean functionAttempted;
        private boolean lambdaRoleAttempted;

        private Owned(TestInfo info) {
            caseName = info.getTestMethod().orElseThrow().getName();
            try {
                roleAttempted = true;
                iam.createRole(request -> request.roleName(role).assumeRolePolicyDocument(trust(SOURCE, RECIPIENT)));
                rolePermission(targetArn);
                streamAttempted = true;
                kinesis.createStream(request -> request.streamName(stream).shardCount(1));
                groupAttempted = true;
                sender.createLogGroup(request -> request.logGroupName(group).tags(Map.of("audit-owner", marker)));
                sender.createLogStream(request -> request.logGroupName(group).logStreamName("source"));
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanupFailure) {
                    if (failure != cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                }
                throw failure;
            }
        }

        private <B extends AwsClientBuilder<B, T> & AwsSyncClientBuilder<B, T>, T> T client(
                B builder, String account, Region region) {
            builder.overrideConfiguration(config -> config.addExecutionInterceptor(wire)
                    .retryPolicy(RetryPolicy.none()).apiCallTimeout(Duration.ofSeconds(15))
                    .apiCallAttemptTimeout(Duration.ofSeconds(10)));
            return AwsTestClients.create(builder, account, region);
        }

        private String name(String suffix) { return marker + "-" + suffix; }

        private String destination(String suffix) {
            String name = name(suffix);
            destinationNames.add(name);
            return receiver.putDestination(request -> request.destinationName(name).targetArn(targetArn)
                    .roleArn(roleArn)).destination().arn();
        }

        private Destination describe(String suffix) {
            List<Destination> values = receiver.describeDestinations(request -> request.destinationNamePrefix(name(suffix)))
                    .destinations();
            assertEquals(1, values.size());
            assertEquals(name(suffix), values.getFirst().destinationName());
            return values.getFirst();
        }

        private void grant(String arn, String account) {
            receiver.putDestinationPolicy(request -> request.destinationName(arn.substring(arn.lastIndexOf(':') + 1))
                    .accessPolicy("""
                            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"%s"},
                            "Action":"logs:PutSubscriptionFilter","Resource":"%s"}]}
                            """.formatted(account, arn)));
        }

        private void rolePermission(String resource) {
            iam.putRolePolicy(request -> request.roleName(role).policyName("delivery").policyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"kinesis:PutRecord","Resource":"%s"}]}
                    """.formatted(resource)));
        }

        private void subscribe(String arn, String pattern) {
            sender.putSubscriptionFilter(request -> request.logGroupName(group).filterName("filter")
                    .filterPattern(pattern).destinationArn(arn).distribution("ByLogStream"));
        }

        private List<SubscriptionFilter> filters() {
            return sender.describeSubscriptionFilters(request -> request.logGroupName(group)).subscriptionFilters();
        }

        private void emit(String... messages) {
            long now = System.currentTimeMillis();
            List<InputLogEvent> events = Arrays.stream(messages).map(message -> InputLogEvent.builder()
                    .timestamp(now).message(message).build()).toList();
            sender.putLogEvents(request -> request.logGroupName(group).logStreamName("source").logEvents(events));
        }

        private List<JsonNode> records() throws IOException {
            String shard = kinesis.describeStream(request -> request.streamName(stream))
                    .streamDescription().shards().getFirst().shardId();
            String iterator = kinesis.getShardIterator(request -> request.streamName(stream).shardId(shard)
                    .shardIteratorType(ShardIteratorType.TRIM_HORIZON)).shardIterator();
            List<Record> records = kinesis.getRecords(request ->
                    request.shardIterator(iterator).limit(1000)).records();
            assertTrue(records.size() < 1000, "the complete bounded test stream must fit one page");
            List<JsonNode> decoded = new ArrayList<>();
            for (Record record : records) {
                try (GZIPInputStream gzip = new GZIPInputStream(record.data().asInputStream())) {
                    decoded.add(MAPPER.readTree(gzip.readAllBytes()));
                }
            }
            return decoded;
        }

        private void createFunction() {
            lambdaRoleAttempted = true;
            String arn = sourceIam.createRole(request -> request.roleName(marker).assumeRolePolicyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},
                    "Action":"sts:AssumeRole"}]}
                    """)).role().arn();
            functionAttempted = true;
            lambda.createFunction(request -> request.functionName(marker).runtime("python3.12").handler("index.handler")
                    .role(arn).code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(code())).build()));
        }

        @Override
        public void close() {
            IllegalStateException failures = new IllegalStateException("Owned logical Logs fixture cleanup failed");
            if (groupAttempted) {
                cleanup(() -> {
                    try {
                        for (SubscriptionFilter filter : filters()) {
                            assertEquals("filter", filter.filterName());
                            sender.deleteSubscriptionFilter(request -> request.logGroupName(group).filterName(filter.filterName()));
                        }
                        sender.deleteLogGroup(request -> request.logGroupName(group));
                    } catch (CloudWatchLogsException missing) { missingGroup(missing); }
                    missingGroup(assertThrows(CloudWatchLogsException.class, this::filters));
                }, failures);
            }
            for (String name : destinationNames) {
                cleanup(() -> {
                    try { receiver.deleteDestination(request -> request.destinationName(name)); }
                    catch (CloudWatchLogsException missing) { missingDestination(missing); }
                    assertTrue(receiver.describeDestinations(request -> request.destinationNamePrefix(name))
                            .destinations().stream().noneMatch(value -> name.equals(value.destinationName())));
                }, failures);
            }
            if (functionAttempted) {
                cleanup(() -> {
                    try { lambda.deleteFunction(request -> request.functionName(marker)); }
                    catch (LambdaException missing) { missingFunction(missing); }
                    missingFunction(assertThrows(LambdaException.class, () -> lambda.getFunctionConfiguration(
                            request -> request.functionName(marker))));
                }, failures);
            }
            if (lambdaRoleAttempted) { cleanup(() -> deleteRole(sourceIam, marker), failures); }
            if (roleAttempted) {
                cleanup(() -> {
                    try { iam.deleteRolePolicy(request -> request.roleName(role).policyName("delivery")); }
                    catch (IamException missing) { missingRole(missing); }
                }, failures);
                cleanup(() -> deleteRole(iam, role), failures);
            }
            if (streamAttempted) {
                cleanup(() -> {
                    try { kinesis.deleteStream(request -> request.streamName(stream)); }
                    catch (KinesisException missing) { missingStream(missing); }
                    missingStream(assertThrows(KinesisException.class, () -> kinesis.describeStream(
                            request -> request.streamName(stream))));
                }, failures);
            }
            cleanup(sender::close, failures);
            cleanup(receiver::close, failures);
            cleanup(receiverOtherRegion::close, failures);
            cleanup(kinesis::close, failures);
            cleanup(sourceKinesis::close, failures);
            cleanup(iam::close, failures);
            cleanup(sourceIam::close, failures);
            cleanup(lambda::close, failures);
            cleanup(() -> wire.save(caseName, marker, failures.getSuppressed().length == 0), failures);
            if (failures.getSuppressed().length > 0) { throw failures; }
        }
    }

    private static void deleteRole(IamClient iam, String role) {
        try { iam.deleteRole(request -> request.roleName(role)); }
        catch (IamException missing) { missingRole(missing); }
        missingRole(assertThrows(IamException.class, () -> iam.getRole(request -> request.roleName(role))));
    }

    private static void missingRole(IamException failure) {
        assertEquals(404, failure.statusCode());
        assertEquals("NoSuchEntity", failure.awsErrorDetails().errorCode());
    }
    private static void missingStream(KinesisException failure) {
        assertEquals(400, failure.statusCode());
        assertEquals("ResourceNotFoundException", failure.awsErrorDetails().errorCode());
    }
    private static void missingGroup(CloudWatchLogsException failure) { missingDestination(failure); }
    private static void missingDestination(CloudWatchLogsException failure) {
        assertEquals(400, failure.statusCode());
        assertEquals("ResourceNotFoundException", failure.awsErrorDetails().errorCode());
    }
    private static void missingFunction(LambdaException failure) {
        assertEquals(404, failure.statusCode());
        assertEquals("ResourceNotFoundException", failure.awsErrorDetails().errorCode());
    }
    private static void denied(Runnable action) {
        CloudWatchLogsException failure = assertThrows(CloudWatchLogsException.class, action::run);
        assertEquals("AccessDeniedException", failure.awsErrorDetails().errorCode());
    }
    private static void invalid(Runnable action) {
        CloudWatchLogsException failure = assertThrows(CloudWatchLogsException.class, action::run);
        assertEquals(400, failure.statusCode());
        assertEquals("InvalidParameterException", failure.awsErrorDetails().errorCode());
    }

    private static void cleanup(Runnable action, IllegalStateException failures) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (failure != failures) {
                failures.addSuppressed(failure);
            }
        }
    }

    private static byte[] code() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("index.py"));
                zip.write("def handler(event, context):\n    return None\n".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class WireReceipt implements ExecutionInterceptor {
        private static final ExecutionAttribute<Integer> ID = new ExecutionAttribute<>("logs-logical-wire-id");
        private final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertEquals("localhost", context.httpRequest().getUri().getHost());
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
                Path directory = Path.of(System.getProperty("audit.logs.logical.directory",
                        "target/logs-logical-wire"));
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
