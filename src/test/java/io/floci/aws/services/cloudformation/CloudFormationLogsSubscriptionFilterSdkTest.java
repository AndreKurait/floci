package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
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
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeSubscriptionFiltersResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.SubscriptionFilter;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
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
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Real SDK lifecycle controls; the Lambda destination is created but never invoked. */
@QuarkusTest
@Timeout(120)
class CloudFormationLogsSubscriptionFilterSdkTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "815670000001";
    private static final Region REGION = Region.US_EAST_1;
    private static final Set<String> SETTLED = Set.of("CREATE_COMPLETE", "ROLLBACK_COMPLETE", "ROLLBACK_FAILED",
            "UPDATE_COMPLETE", "UPDATE_ROLLBACK_COMPLETE", "UPDATE_ROLLBACK_FAILED", "DELETE_COMPLETE",
            "DELETE_FAILED");

    @Test
    void createMutableUpdateDefaultDistributionAndDeleteHaveNativeBackingState(TestInfo info) {
        try (Owned owned = new Owned(info)) {
            ObjectNode initial = owned.template("first", owned.groupOne, "", "Random", false);
            owned.create(initial, "CREATE_COMPLETE");
            SubscriptionFilter first = owned.onlyFilter(owned.groupOne);
            owned.assertFilter(first, owned.groupOne, "first", "", "Random");
            assertNotNull(first.creationTime());
            owned.assertIdentity("first");

            ObjectNode updated = owned.template("first", owned.groupOne, "ERROR", "ByLogStream", false);
            owned.update(updated, "UPDATE_COMPLETE");
            owned.assertFilter(owned.onlyFilter(owned.groupOne), owned.groupOne, "first", "ERROR", "ByLogStream");
            assertEquals(first.creationTime(), owned.onlyFilter(owned.groupOne).creationTime());
            owned.assertIdentity("first");
            owned.update(owned.template("first", owned.groupOne, "WARN", null, false), "UPDATE_COMPLETE");
            owned.assertFilter(owned.onlyFilter(owned.groupOne), owned.groupOne, "first", "WARN", "ByLogStream");
            assertEquals(first.creationTime(), owned.onlyFilter(owned.groupOne).creationTime());
            owned.deleteStack();
            assertTrue(owned.filters(owned.groupOne).isEmpty());
        }
    }

    @Test
    void nameAndGroupReplacementDeleteTheOldFilterFirstEvenWithRetain(TestInfo info) {
        try (Owned owned = new Owned(info)) {
            owned.create(owned.template("first", owned.groupOne, "", "Random", true), "CREATE_COMPLETE");
            owned.assertIdentity("first");
            owned.assertFilter(owned.onlyFilter(owned.groupOne), owned.groupOne, "first", "", "Random");
            Set<String> beforeName = owned.eventIds();
            owned.update(owned.template("second", owned.groupOne, "", "Random", true), "UPDATE_COMPLETE");
            owned.assertIdentity("second");
            owned.assertFilter(owned.onlyFilter(owned.groupOne), owned.groupOne, "second", "", "Random");
            owned.assertDeleteBeforeCreate(beforeName, "first", "second");
            Set<String> beforeGroup = owned.eventIds();
            owned.update(owned.template("second", owned.groupTwo, "", "Random", true), "UPDATE_COMPLETE");
            owned.assertIdentity("second");
            assertTrue(owned.filters(owned.groupOne).isEmpty());
            owned.assertFilter(owned.onlyFilter(owned.groupTwo), owned.groupTwo, "second", "", "Random");
            owned.assertDeleteBeforeCreate(beforeGroup, "second", "second");
            owned.deleteStack();
            assertTrue(owned.filters(owned.groupOne).isEmpty());
            assertTrue(owned.filters(owned.groupTwo).isEmpty());
        }
    }

    @Test
    void generatedNameRemainsStableAcrossMutableUpdates(TestInfo info) {
        try (Owned owned = new Owned(info)) {
            owned.create(owned.template(null, owned.groupOne, "", null, false), "CREATE_COMPLETE");
            SubscriptionFilter first = owned.onlyFilter(owned.groupOne);
            assertFalse(first.filterName().isBlank());
            owned.assertIdentity(first.filterName());
            owned.update(owned.template(null, owned.groupOne, "ERROR", "ByLogStream", false), "UPDATE_COMPLETE");
            owned.assertFilter(owned.onlyFilter(owned.groupOne), owned.groupOne, first.filterName(),
                    "ERROR", "ByLogStream");
            owned.assertIdentity(first.filterName());
            assertEquals(first.creationTime(), owned.onlyFilter(owned.groupOne).creationTime());
        }
    }

    @Test
    void aCreateCollisionPreservesTheCompleteExistingNativeFilterThroughStackDeletion(TestInfo info) {
        try (Owned owned = new Owned(info)) {
            owned.logs.putSubscriptionFilter(builder -> builder.logGroupName(owned.groupOne).filterName("occupied")
                    .filterPattern("WARN").destinationArn(owned.functionArn).distribution("ByLogStream"));
            List<SubscriptionFilter> original = owned.filters(owned.groupOne);
            owned.create(owned.template("occupied", owned.groupOne, "ERROR", "Random", false), "ROLLBACK_COMPLETE");
            assertEquals(original, owned.filters(owned.groupOne));
            owned.deleteStack();
            assertEquals(original, owned.filters(owned.groupOne));
        }
    }

    @Test
    void aLaterResourceFailureRestoresTheFullPriorDefinitionAndAllowsAnExactRetry(TestInfo info) {
        try (Owned owned = new Owned(info)) {
            ObjectNode originalTemplate = owned.template("first", owned.groupOne, "WARN", "ByLogStream", false);
            owned.create(originalTemplate, "CREATE_COMPLETE");
            List<SubscriptionFilter> original = owned.filters(owned.groupOne);
            ObjectNode refused = owned.template("first", owned.groupOne, "ERROR", "Random", false);
            owned.addSecond(refused, owned.groupOne + "-missing");
            owned.update(refused, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(original, owned.filters(owned.groupOne), "rollback includes the original creation time");
            assertTrue(owned.filters(owned.groupTwo).isEmpty());
            owned.assertIdentity("first");
            ObjectNode retry = refused.deepCopy();
            ((ObjectNode) retry.path("Resources").path("Second").path("Properties"))
                    .put("LogGroupName", owned.groupTwo);
            owned.update(retry, "UPDATE_COMPLETE");
            owned.assertFilter(owned.onlyFilter(owned.groupOne), owned.groupOne, "first", "ERROR", "Random");
            owned.assertFilter(owned.onlyFilter(owned.groupTwo), owned.groupTwo, "second", "", "Random");
        }
    }

    @Test
    void unsupportedRoleUpdatePreservesTheOwnedFilterAndAValidRetrySucceeds(TestInfo info) {
        try (Owned owned = new Owned(info)) {
            owned.create(owned.template("first", owned.groupOne, "", "Random", false), "CREATE_COMPLETE");
            List<SubscriptionFilter> original = owned.filters(owned.groupOne);
            ObjectNode refused = owned.template("first", owned.groupOne, "ERROR", "Random", false);
            ((ObjectNode) refused.path("Resources").path("Filter").path("Properties"))
                    .put("RoleArn", owned.roleArn);
            owned.update(refused, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(original, owned.filters(owned.groupOne));
            owned.assertIdentity("first");
            ObjectNode retry = refused.deepCopy();
            ((ObjectNode) retry.path("Resources").path("Filter").path("Properties")).remove("RoleArn");
            owned.update(retry, "UPDATE_COMPLETE");
            owned.assertFilter(owned.onlyFilter(owned.groupOne), owned.groupOne, "first", "ERROR", "Random");
        }
    }

    private static final class Owned implements AutoCloseable {
        private final WireReceipt wire = new WireReceipt();
        private final CloudFormationClient cfn = client(CloudFormationClient.builder());
        private final CloudWatchLogsClient logs = client(CloudWatchLogsClient.builder());
        private final IamClient iam = client(IamClient.builder());
        private final LambdaClient lambda = client(LambdaClient.builder());
        private final String token = "cfn-logs-" + UUID.randomUUID().toString().replace("-", "");
        private final String groupOne = "/" + token + "/one";
        private final String groupTwo = "/" + token + "/two";
        private final String roleName = token + "-role";
        private final String functionName = token + "-function";
        private final String stackName = token + "-stack";
        private final Set<String> groups = new LinkedHashSet<>();
        private String roleArn;
        private String functionArn;
        private String stackId;
        private boolean stackAttempted;
        private boolean functionAttempted;
        private boolean roleAttempted;
        private final String caseName;

        private Owned(TestInfo info) {
            caseName = info.getTestMethod().orElseThrow().getName();
            try {
                roleAttempted = true;
                roleArn = iam.createRole(builder -> builder.roleName(roleName).assumeRolePolicyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":
                        {"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                        """)).role().arn();
                functionAttempted = true;
                functionArn = lambda.createFunction(builder -> builder.functionName(functionName).role(roleArn)
                        .runtime("python3.12").handler("index.handler").timeout(3).memorySize(128)
                        .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(code())).build()))
                        .functionArn();
                for (String group : List.of(groupOne, groupTwo)) {
                    groups.add(group);
                    logs.createLogGroup(builder -> builder.logGroupName(group));
                    lambda.addPermission(builder -> builder.functionName(functionName)
                            .statementId("logs-" + groups.size()).principal("logs.amazonaws.com")
                            .action("lambda:InvokeFunction").sourceAccount(ACCOUNT)
                            .sourceArn("arn:aws:logs:" + REGION.id() + ":" + ACCOUNT + ":log-group:" + group + ":*"));
                }
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

        private <B extends AwsClientBuilder<B, T> & AwsSyncClientBuilder<B, T>, T> T client(B builder) {
            return AwsTestClients.create(builder.overrideConfiguration(configuration -> configuration
                    .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                    .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), ACCOUNT, REGION);
        }

        private ObjectNode template(String name, String group, String pattern, String distribution, boolean retain) {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("AWSTemplateFormatVersion", "2010-09-09");
            ObjectNode resource = root.putObject("Resources").putObject("Filter");
            resource.put("Type", "AWS::Logs::SubscriptionFilter");
            if (retain) {
                resource.put("UpdateReplacePolicy", "Retain");
            }
            ObjectNode properties = resource.putObject("Properties");
            properties.put("LogGroupName", group).put("FilterPattern", pattern).put("DestinationArn", functionArn);
            if (name != null) {
                properties.put("FilterName", name);
            }
            if (distribution != null) {
                properties.put("Distribution", distribution);
            }
            root.putObject("Outputs").putObject("FilterName").putObject("Value").put("Ref", "Filter");
            return root;
        }

        private void addSecond(ObjectNode template, String group) {
            ObjectNode resource = ((ObjectNode) template.path("Resources")).putObject("Second");
            resource.put("Type", "AWS::Logs::SubscriptionFilter").put("DependsOn", "Filter");
            resource.putObject("Properties").put("FilterName", "second").put("LogGroupName", group)
                    .put("FilterPattern", "").put("DestinationArn", functionArn).put("Distribution", "Random");
        }

        private void create(ObjectNode template, String expected) {
            stackAttempted = true;
            stackId = cfn.createStack(builder -> builder.stackName(stackName).templateBody(template.toString())
                    .clientRequestToken(token + "-create")).stackId();
            assertNotNull(stackId);
            assertEquals(expected, awaitSettled(Set.of()).stackStatusAsString());
        }

        private void update(ObjectNode template, String expected) {
            Set<String> before = eventIds();
            String returned = cfn.updateStack(builder -> builder.stackName(stackId).templateBody(template.toString())
                    .clientRequestToken(token + "-" + UUID.randomUUID())).stackId();
            assertEquals(stackId, returned);
            assertEquals(expected, awaitSettled(before).stackStatusAsString());
        }

        private Stack stack(String name) {
            List<Stack> stacks = cfn.describeStacks(builder -> builder.stackName(name)).stacks();
            assertEquals(1, stacks.size());
            return stacks.getFirst();
        }

        private Stack awaitSettled(Set<String> previousEvents) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                Stack stack = stack(stackId);
                String status = stack.stackStatusAsString();
                if (SETTLED.contains(status) && events().stream().anyMatch(event ->
                        !previousEvents.contains(event.eventId()) && stackId.equals(event.stackId())
                                && "AWS::CloudFormation::Stack".equals(event.resourceType())
                                && status.equals(event.resourceStatusAsString()))) {
                    return stack;
                }
                pause();
            }
            return fail("Owned stack did not publish a new terminal stack event: " + stackId);
        }

        private List<StackEvent> events() {
            List<StackEvent> events = new ArrayList<>();
            Set<String> tokens = new HashSet<>();
            String next = null;
            do {
                String pageToken = next;
                DescribeStackEventsResponse response = cfn.describeStackEvents(builder ->
                        builder.stackName(stackId).nextToken(pageToken));
                events.addAll(response.stackEvents());
                next = response.nextToken();
                if (next != null) {
                    assertTrue(tokens.add(next), "stack event pagination advances");
                    assertTrue(tokens.size() <= 100);
                }
            } while (next != null);
            return events;
        }

        private Set<String> eventIds() {
            Set<String> ids = new HashSet<>();
            for (StackEvent event : events()) {
                assertNotNull(event.eventId());
                assertTrue(ids.add(event.eventId()), "each stack event has a distinct ID");
            }
            return ids;
        }

        private void assertDeleteBeforeCreate(Set<String> previous, String oldId, String newId) {
            List<StackEvent> current = new ArrayList<>(events());
            Collections.reverse(current);
            int delete = -1;
            int create = -1;
            for (int index = 0; index < current.size(); index++) {
                StackEvent event = current.get(index);
                if (previous.contains(event.eventId()) || !"Filter".equals(event.logicalResourceId())
                        || !"AWS::Logs::SubscriptionFilter".equals(event.resourceType())) {
                    continue;
                }
                if ("DELETE_COMPLETE".equals(event.resourceStatusAsString()) && oldId.equals(event.physicalResourceId())) {
                    assertEquals(-1, delete);
                    delete = index;
                }
                if ("CREATE_IN_PROGRESS".equals(event.resourceStatusAsString()) && newId.equals(event.physicalResourceId())) {
                    assertEquals(-1, create);
                    create = index;
                }
            }
            assertTrue(delete >= 0, "the old filter has a DELETE_COMPLETE event");
            assertTrue(create > delete, "the new filter starts after old deletion completes");
        }

        private void assertIdentity(String expected) {
            Stack current = stack(stackId);
            assertEquals(List.of(expected), current.outputs().stream()
                    .filter(output -> "FilterName".equals(output.outputKey())).map(output -> output.outputValue()).toList());
            List<StackResource> resources = cfn.describeStackResources(builder -> builder.stackName(stackId))
                    .stackResources().stream().filter(resource -> "Filter".equals(resource.logicalResourceId())).toList();
            assertEquals(1, resources.size());
            assertEquals(expected, resources.getFirst().physicalResourceId());
            assertEquals("AWS::Logs::SubscriptionFilter", resources.getFirst().resourceType());
        }

        private List<SubscriptionFilter> filters(String group) {
            List<SubscriptionFilter> filters = new ArrayList<>();
            Set<String> tokens = new HashSet<>();
            String next = null;
            do {
                String pageToken = next;
                DescribeSubscriptionFiltersResponse response = logs.describeSubscriptionFilters(builder ->
                        builder.logGroupName(group).nextToken(pageToken));
                filters.addAll(response.subscriptionFilters());
                next = response.nextToken();
                if (next != null) {
                    assertTrue(tokens.add(next), "filter pagination advances");
                    assertTrue(tokens.size() <= 100);
                }
            } while (next != null);
            return filters;
        }

        private SubscriptionFilter onlyFilter(String group) {
            List<SubscriptionFilter> filters = filters(group);
            assertEquals(1, filters.size(), "one native subscription filter backs the stack");
            return filters.getFirst();
        }

        private void assertFilter(SubscriptionFilter filter, String group, String name, String pattern,
                                  String distribution) {
            assertEquals(group, filter.logGroupName());
            assertEquals(name, filter.filterName());
            assertEquals(pattern, filter.filterPattern());
            assertEquals(functionArn, filter.destinationArn());
            assertEquals(distribution, filter.distributionAsString());
            assertNotNull(filter.creationTime());
            assertTrue(filter.creationTime() > 0);
            assertTrue(filter.roleArn() == null || filter.roleArn().isEmpty());
        }

        private void deleteStack() {
            if (!stackAttempted) {
                return;
            }
            if (stackId == null) {
                try {
                    stackId = stack(stackName).stackId();
                } catch (CloudFormationException failure) {
                    requireStackAbsent(failure);
                    stackAttempted = false;
                    return;
                }
            }
            Set<String> before = eventIds();
            cfn.deleteStack(builder -> builder.stackName(stackId).clientRequestToken(token + "-delete"));
            assertEquals("DELETE_COMPLETE", awaitSettled(before).stackStatusAsString());
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    stack(stackName);
                    pause();
                } catch (CloudFormationException failure) {
                    requireStackAbsent(failure);
                    stackAttempted = false;
                    return;
                }
            }
            fail("Deleted owned stack still resolves by name");
        }

        @Override
        public void close() {
            IllegalStateException failures = new IllegalStateException("Owned Logs CFN SDK cleanup failed");
            cleanup(this::deleteStack, failures);
            for (String group : groups) {
                cleanup(() -> {
                    List<SubscriptionFilter> existing;
                    try {
                        existing = filters(group);
                    } catch (CloudWatchLogsException missing) {
                        assertEquals(400, missing.statusCode());
                        assertEquals("ResourceNotFoundException", missing.awsErrorDetails().errorCode());
                        return;
                    }
                    for (SubscriptionFilter filter : existing) {
                        assertEquals(functionArn, filter.destinationArn(), "cleanup only handles this fixture destination");
                        logs.deleteSubscriptionFilter(builder ->
                                builder.logGroupName(group).filterName(filter.filterName()));
                    }
                    assertTrue(filters(group).isEmpty());
                    logs.deleteLogGroup(builder -> builder.logGroupName(group));
                    CloudWatchLogsException missing = assertThrows(CloudWatchLogsException.class, () -> filters(group));
                    assertEquals(400, missing.statusCode());
                    assertEquals("ResourceNotFoundException", missing.awsErrorDetails().errorCode());
                }, failures);
            }
            if (functionAttempted) {
                cleanup(() -> {
                    try {
                        lambda.deleteFunction(builder -> builder.functionName(functionName));
                    } catch (LambdaException missing) {
                        requireFunctionAbsent(missing);
                    }
                    requireFunctionAbsent(assertThrows(LambdaException.class,
                            () -> lambda.getFunctionConfiguration(builder -> builder.functionName(functionName))));
                }, failures);
            }
            if (roleAttempted) {
                cleanup(() -> {
                    try {
                        iam.deleteRole(builder -> builder.roleName(roleName));
                    } catch (IamException missing) {
                        requireRoleAbsent(missing);
                    }
                    requireRoleAbsent(assertThrows(IamException.class,
                            () -> iam.getRole(builder -> builder.roleName(roleName))));
                }, failures);
            }
            cleanup(cfn::close, failures);
            cleanup(logs::close, failures);
            cleanup(lambda::close, failures);
            cleanup(iam::close, failures);
            cleanup(() -> wire.save(caseName, token, failures.getSuppressed().length == 0), failures);
            if (failures.getSuppressed().length > 0) {
                throw failures;
            }
        }
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

    private static void requireStackAbsent(CloudFormationException failure) {
        assertEquals(400, failure.statusCode());
        assertEquals("ValidationError", failure.awsErrorDetails().errorCode());
        assertTrue(failure.awsErrorDetails().errorMessage().contains("does not exist"));
    }

    private static void requireFunctionAbsent(LambdaException failure) {
        assertEquals(404, failure.statusCode());
        assertEquals("ResourceNotFoundException", failure.awsErrorDetails().errorCode());
    }

    private static void requireRoleAbsent(IamException failure) {
        assertEquals(404, failure.statusCode());
        assertEquals("NoSuchEntity", failure.awsErrorDetails().errorCode());
    }

    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the owned stack", failure);
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
        private static final ExecutionAttribute<Integer> ID = new ExecutionAttribute<>("logs-cfn-wire-id");
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
                Path directory = Path.of(System.getProperty("audit.logs.subscription.directory",
                        "target/logs-subscription-wire"));
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
