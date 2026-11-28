package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsResponse;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeDestinationsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeSubscriptionFiltersResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.Destination;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.SubscriptionFilter;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.KinesisException;
import software.amazon.awssdk.services.kinesis.model.Record;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Real CFN-to-Logs-to-Kinesis delivery with distinct source and recipient accounts. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
@Timeout(120)
class CloudFormationLogsLogicalDestinationSdkTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SOURCE = "815670000021";
    private static final String RECIPIENT = "815670000022";
    private static final Region REGION = Region.US_EAST_1;
    private static final Region TARGET_REGION = Region.US_WEST_2;
    private static final Set<String> SETTLED = Set.of("CREATE_COMPLETE", "ROLLBACK_COMPLETE", "ROLLBACK_FAILED",
            "UPDATE_COMPLETE", "UPDATE_ROLLBACK_COMPLETE", "UPDATE_ROLLBACK_FAILED",
            "DELETE_COMPLETE", "DELETE_FAILED");

    @Test
    void cloudFormationFilterDeliversAcrossAccountsAndStopsAfterDeletion(TestInfo info) throws Exception {
        try (Owned owned = new Owned(info)) {
            List<JsonNode> control = owned.records();
            assertEquals(1, control.size());
            assertEquals("CONTROL_MESSAGE", control.getFirst().path("messageType").asText());
            assertEquals(RECIPIENT, control.getFirst().path("owner").asText());
            owned.create();
            SubscriptionFilter original = owned.onlyFilter();
            assertEquals("filter", original.filterName());
            assertEquals("", original.filterPattern());
            assertEquals(owned.destinationArn, original.destinationArn());
            assertNotNull(original.creationTime());
            assertTrue(original.roleArn() == null || original.roleArn().isEmpty());
            owned.assertIdentity();
            owned.emit("first");
            List<JsonNode> first = owned.records();
            assertEquals(2, first.size());
            JsonNode delivered = first.get(1);
            assertEquals("DATA_MESSAGE", delivered.path("messageType").asText());
            assertEquals(SOURCE, delivered.path("owner").asText());
            assertEquals(owned.group, delivered.path("logGroup").asText());
            assertEquals("source", delivered.path("logStream").asText());
            assertEquals(List.of("filter"), MAPPER.convertValue(delivered.path("subscriptionFilters"), List.class));
            assertEquals(1, delivered.path("logEvents").size());
            assertEquals("first", delivered.path("logEvents").get(0).path("message").asText());
            assertFalse(delivered.path("logEvents").get(0).path("id").asText().isBlank());
            assertTrue(delivered.path("logEvents").get(0).path("timestamp").asLong() > 0);
            owned.update();
            SubscriptionFilter updated = owned.onlyFilter();
            assertEquals("ERROR", updated.filterPattern());
            assertEquals(original.creationTime(), updated.creationTime());
            owned.assertIdentity();
            owned.emit("INFO ignored", "ERROR delivered");
            List<JsonNode> afterUpdate = owned.records();
            assertEquals(3, afterUpdate.size());
            assertEquals(1, afterUpdate.get(2).path("logEvents").size());
            assertEquals("ERROR delivered", afterUpdate.get(2).path("logEvents").get(0).path("message").asText());
            Destination destination = owned.destination();
            owned.deleteStack();
            assertTrue(owned.filters().isEmpty());
            owned.emit("ERROR after deletion");
            assertEquals(afterUpdate, owned.records());
            assertEquals(destination, owned.destination());
            assertEquals("ACTIVE", owned.kinesis.describeStream(request -> request.streamName(owned.marker))
                    .streamDescription().streamStatusAsString());
            missingStream(assertThrows(KinesisException.class, () -> owned.sourceKinesis.describeStream(
                    request -> request.streamName(owned.marker))));
        }
    }

    private static final class Owned implements AutoCloseable {
        private final String marker = "cfn-logical-" + UUID.randomUUID().toString();
        private final String group = "/" + marker;
        private final String roleArn = "arn:aws:iam::" + RECIPIENT + ":role/" + marker;
        private final String targetArn = "arn:aws:kinesis:us-west-2:" + RECIPIENT + ":stream/" + marker;
        private final WireReceipt wire = new WireReceipt();
        private final CloudFormationClient cfn = client(CloudFormationClient.builder(), SOURCE, REGION);
        private final CloudWatchLogsClient sender = client(CloudWatchLogsClient.builder(), SOURCE, REGION);
        private final CloudWatchLogsClient receiver = client(CloudWatchLogsClient.builder(), RECIPIENT, REGION);
        private final KinesisClient kinesis = client(KinesisClient.builder(), RECIPIENT, TARGET_REGION);
        private final KinesisClient sourceKinesis = client(KinesisClient.builder(), SOURCE, TARGET_REGION);
        private final IamClient iam = client(IamClient.builder(), RECIPIENT, REGION);
        private final String caseName;
        private String destinationArn;
        private String stackId;
        private boolean roleAttempted;
        private boolean streamAttempted;
        private boolean groupAttempted;
        private boolean destinationAttempted;
        private boolean stackAttempted;

        private Owned(TestInfo info) {
            caseName = info.getTestMethod().orElseThrow().getName();
            try {
                roleAttempted = true;
                iam.createRole(request -> request.roleName(marker).assumeRolePolicyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Principal":{"Service":"logs.amazonaws.com"},"Action":"sts:AssumeRole",
                        "Condition":{"StringEquals":{"aws:SourceAccount":["%s","%s"]},
                        "ArnLike":{"aws:SourceArn":["arn:aws:logs:us-east-1:%s:*","arn:aws:logs:us-east-1:%s:*"]}}}]}
                        """.formatted(SOURCE, RECIPIENT, SOURCE, RECIPIENT)));
                iam.putRolePolicy(request -> request.roleName(marker).policyName("delivery").policyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Action":"kinesis:PutRecord","Resource":"%s"}]}
                        """.formatted(targetArn)));
                streamAttempted = true;
                kinesis.createStream(request -> request.streamName(marker).shardCount(1));
                groupAttempted = true;
                sender.createLogGroup(request -> request.logGroupName(group).tags(Map.of("audit-owner", marker)));
                sender.createLogStream(request -> request.logGroupName(group).logStreamName("source"));
                destinationAttempted = true;
                destinationArn = receiver.putDestination(request -> request.destinationName(marker)
                        .targetArn(targetArn).roleArn(roleArn)).destination().arn();
                receiver.putDestinationPolicy(request -> request.destinationName(marker).accessPolicy("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"%s"},
                        "Action":"logs:PutSubscriptionFilter","Resource":"%s"}]}
                        """.formatted(SOURCE, destinationArn)));
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
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

        private String template(String pattern) {
            ObjectNode template = MAPPER.createObjectNode();
            template.put("AWSTemplateFormatVersion", "2010-09-09");
            template.putObject("Parameters").putObject("GroupName").put("Type", "String").put("Default", group);
            ObjectNode filter = template.putObject("Resources").putObject("Filter");
            filter.put("Type", "AWS::Logs::SubscriptionFilter");
            ObjectNode properties = filter.putObject("Properties");
            properties.put("FilterName", "filter").put("FilterPattern", pattern).put("DestinationArn", destinationArn);
            properties.putObject("LogGroupName").put("Ref", "GroupName");
            template.putObject("Outputs").putObject("FilterName").putObject("Value").put("Ref", "Filter");
            return template.toString();
        }

        private void create() {
            stackAttempted = true;
            stackId = cfn.createStack(request -> request.stackName(marker).templateBody(template(""))
                    .clientRequestToken(marker + "-create")).stackId();
            assertNotNull(stackId);
            assertEquals("CREATE_COMPLETE", awaitSettled(Set.of()).stackStatusAsString());
        }

        private void update() {
            Set<String> previous = eventIds();
            assertEquals(stackId, cfn.updateStack(request -> request.stackName(stackId)
                    .templateBody(template("ERROR")).clientRequestToken(marker + "-update")).stackId());
            assertEquals("UPDATE_COMPLETE", awaitSettled(previous).stackStatusAsString());
        }

        private Stack stack(String id) {
            List<Stack> values = cfn.describeStacks(request -> request.stackName(id)).stacks();
            assertEquals(1, values.size());
            assertEquals(marker, values.getFirst().stackName());
            return values.getFirst();
        }

        private Stack awaitSettled(Set<String> previousEvents) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                Stack current = stack(stackId);
                String status = current.stackStatusAsString();
                if (SETTLED.contains(status) && events().stream().anyMatch(event ->
                        !previousEvents.contains(event.eventId()) && stackId.equals(event.stackId())
                                && "AWS::CloudFormation::Stack".equals(event.resourceType())
                                && status.equals(event.resourceStatusAsString()))) {
                    return current;
                }
                pause();
            }
            return fail("Owned stack did not publish a new terminal stack event: " + stackId);
        }

        private List<StackEvent> events() {
            List<StackEvent> values = new ArrayList<>();
            Set<String> tokens = new HashSet<>();
            String next = null;
            do {
                String token = next;
                DescribeStackEventsResponse response = cfn.describeStackEvents(request ->
                        request.stackName(stackId).nextToken(token));
                values.addAll(response.stackEvents());
                next = response.nextToken();
                if (next != null) {
                    assertTrue(tokens.add(next), "event pagination advances");
                    assertTrue(tokens.size() <= 100);
                }
            } while (next != null);
            return values;
        }

        private Set<String> eventIds() {
            Set<String> ids = new HashSet<>();
            for (StackEvent event : events()) {
                assertNotNull(event.eventId());
                assertTrue(ids.add(event.eventId()));
            }
            return ids;
        }

        private void assertIdentity() {
            assertEquals(List.of("filter"), stack(stackId).outputs().stream()
                    .filter(output -> "FilterName".equals(output.outputKey()))
                    .map(output -> output.outputValue()).toList());
            List<StackResource> resources = cfn.describeStackResources(request -> request.stackName(stackId))
                    .stackResources().stream()
                    .filter(resource -> "Filter".equals(resource.logicalResourceId())).toList();
            assertEquals(1, resources.size());
            assertEquals("filter", resources.getFirst().physicalResourceId());
            assertEquals("AWS::Logs::SubscriptionFilter", resources.getFirst().resourceType());
        }

        private void deleteStack() {
            if (!stackAttempted) {
                return;
            }
            String lookup = stackId == null ? marker : stackId;
            Stack current;
            try {
                current = stack(lookup);
            } catch (CloudFormationException missing) {
                assertEquals(400, missing.statusCode());
                assertEquals("ValidationError", missing.awsErrorDetails().errorCode());
                assertEquals("Stack with id " + lookup + " does not exist", missing.awsErrorDetails().errorMessage());
                stackAttempted = false;
                return;
            }
            stackId = current.stackId();
            if (!"DELETE_COMPLETE".equals(current.stackStatusAsString())) {
                Set<String> previous = eventIds();
                cfn.deleteStack(request -> request.stackName(stackId).clientRequestToken(marker + "-delete"));
                assertEquals("DELETE_COMPLETE", awaitSettled(previous).stackStatusAsString());
            }
            assertEquals("DELETE_COMPLETE", stack(stackId).stackStatusAsString());
            stackAttempted = false;
        }

        private List<SubscriptionFilter> filters() {
            DescribeSubscriptionFiltersResponse response = sender.describeSubscriptionFilters(request ->
                    request.logGroupName(group));
            assertNull(response.nextToken(), "the one-filter fixture inventory must fit one page");
            return response.subscriptionFilters();
        }

        private SubscriptionFilter onlyFilter() {
            List<SubscriptionFilter> values = filters();
            assertEquals(1, values.size());
            return values.getFirst();
        }

        private Destination destination() {
            DescribeDestinationsResponse response = receiver.describeDestinations(request ->
                    request.destinationNamePrefix(marker));
            assertNull(response.nextToken());
            assertEquals(1, response.destinations().size());
            assertEquals(marker, response.destinations().getFirst().destinationName());
            return response.destinations().getFirst();
        }

        private void emit(String... messages) {
            long now = System.currentTimeMillis();
            List<InputLogEvent> values = Arrays.stream(messages).map(message -> InputLogEvent.builder()
                    .timestamp(now).message(message).build()).toList();
            sender.putLogEvents(request -> request.logGroupName(group).logStreamName("source").logEvents(values));
        }

        private List<JsonNode> records() throws IOException {
            String shard = kinesis.describeStream(request -> request.streamName(marker))
                    .streamDescription().shards().getFirst().shardId();
            String iterator = kinesis.getShardIterator(request -> request.streamName(marker).shardId(shard)
                    .shardIteratorType(ShardIteratorType.TRIM_HORIZON)).shardIterator();
            List<Record> records = kinesis.getRecords(request -> request.shardIterator(iterator).limit(1000)).records();
            assertTrue(records.size() < 1000, "the complete bounded test stream must fit one page");
            List<JsonNode> decoded = new ArrayList<>();
            for (Record record : records) {
                try (GZIPInputStream gzip = new GZIPInputStream(record.data().asInputStream())) {
                    decoded.add(MAPPER.readTree(gzip.readAllBytes()));
                }
            }
            return decoded;
        }

        @Override
        public void close() {
            IllegalStateException failures = new IllegalStateException("Owned CFN logical Logs cleanup failed");
            cleanup(this::deleteStack, failures);
            if (groupAttempted) {
                cleanup(() -> {
                    try {
                        for (SubscriptionFilter filter : filters()) {
                            assertEquals("filter", filter.filterName());
                            sender.deleteSubscriptionFilter(request ->
                                    request.logGroupName(group).filterName(filter.filterName()));
                        }
                        sender.deleteLogGroup(request -> request.logGroupName(group));
                    } catch (CloudWatchLogsException missing) {
                        missingDestination(missing);
                    }
                    missingDestination(assertThrows(CloudWatchLogsException.class, this::filters));
                }, failures);
            }
            if (destinationAttempted) {
                cleanup(() -> {
                    try {
                        receiver.deleteDestination(request -> request.destinationName(marker));
                    } catch (CloudWatchLogsException missing) {
                        missingDestination(missing);
                    }
                    DescribeDestinationsResponse response = receiver.describeDestinations(request ->
                            request.destinationNamePrefix(marker));
                    assertNull(response.nextToken());
                    assertTrue(response.destinations().isEmpty());
                }, failures);
            }
            if (roleAttempted) {
                cleanup(() -> {
                    try {
                        iam.deleteRolePolicy(request -> request.roleName(marker).policyName("delivery"));
                    } catch (IamException missing) {
                        missingRole(missing);
                    }
                }, failures);
                cleanup(() -> {
                    try {
                        iam.deleteRole(request -> request.roleName(marker));
                    } catch (IamException missing) {
                        missingRole(missing);
                    }
                    missingRole(assertThrows(IamException.class, () ->
                            iam.getRole(request -> request.roleName(marker))));
                }, failures);
            }
            if (streamAttempted) {
                cleanup(() -> {
                    try {
                        kinesis.deleteStream(request -> request.streamName(marker));
                    } catch (KinesisException missing) {
                        missingStream(missing);
                    }
                    missingStream(assertThrows(KinesisException.class, () ->
                            kinesis.describeStream(request -> request.streamName(marker))));
                }, failures);
            }
            cleanup(cfn::close, failures);
            cleanup(sender::close, failures);
            cleanup(receiver::close, failures);
            cleanup(kinesis::close, failures);
            cleanup(sourceKinesis::close, failures);
            cleanup(iam::close, failures);
            cleanup(() -> wire.save(caseName, marker, failures.getSuppressed().length == 0), failures);
            if (failures.getSuppressed().length > 0) {
                throw failures;
            }
        }
    }

    private static void missingRole(IamException failure) {
        assertEquals(404, failure.statusCode());
        assertEquals("NoSuchEntity", failure.awsErrorDetails().errorCode());
    }

    private static void missingStream(KinesisException failure) {
        assertEquals(400, failure.statusCode());
        assertEquals("ResourceNotFoundException", failure.awsErrorDetails().errorCode());
    }

    private static void missingDestination(CloudWatchLogsException failure) {
        assertEquals(400, failure.statusCode());
        assertEquals("ResourceNotFoundException", failure.awsErrorDetails().errorCode());
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

    private static void pause() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static final class WireReceipt implements ExecutionInterceptor {
        private static final ExecutionAttribute<Integer> ID = new ExecutionAttribute<>("logs-cfn-logical-wire-id");
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
                Path directory = Path.of(System.getProperty("audit.logs.cfn.logical.directory",
                        "target/logs-cfn-logical-wire"));
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
