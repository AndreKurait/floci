package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsArnUtils.Arn;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.cloudformation.model.TemplateStage;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetProvisionedConcurrencyConfigResponse;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.LambdaException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** SDK requests create actual Docker Lambda workers through the CFN alias declaration. */
@QuarkusTest
@Timeout(720)
class CloudFormationLambdaProvisionedConcurrencySdkTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "815670000041";
    private static final Set<String> TERMINAL = Set.of("CREATE_COMPLETE", "CREATE_FAILED",
            "ROLLBACK_COMPLETE", "ROLLBACK_FAILED", "UPDATE_COMPLETE", "UPDATE_ROLLBACK_COMPLETE",
            "UPDATE_ROLLBACK_FAILED", "DELETE_COMPLETE", "DELETE_FAILED");

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void stackInitializesResizesAndRemovesItsDeclaredCapacity(boolean sam, boolean noValue) {
        try (Owned owned = new Owned("lifecycle-" + sam + "-" + noValue)) {
            owned.create(owned.template(sam, true, noValue, false, "initial"), "CREATE_COMPLETE");
            String version = owned.aliasVersion();
            assertTrue(version.matches("[0-9]+"));
            String physical = owned.aliasPhysicalId(sam);
            assertEquals(owned.functionArn() + ":live", physical);
            owned.assertReady(1);
            owned.invoke(1);

            owned.update(owned.template(sam, true, noValue, false, "initial"), 2, true);

            assertEquals(version, owned.aliasVersion());
            assertEquals(physical, owned.aliasPhysicalId(sam));
            owned.assertReady(2);
            owned.invoke(2);

            owned.update(owned.template(sam, noValue, noValue, false, "initial"), 2, false);

            owned.assertCapacityAbsent();
            assertEquals(version, owned.aliasVersion());
            assertEquals(physical, owned.aliasPhysicalId(sam));
            owned.delete();
            owned.assertFunctionAbsent();
            owned.assertRoleAbsent();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializationFailureRollsBackTheDeclaringStack(boolean sam) {
        try (Owned owned = new Owned("failed-initialization-" + sam)) {
            List<StackEvent> events = owned.create(
                    owned.template(sam, true, false, true, "broken"), "ROLLBACK_COMPLETE");

            assertTrue(events.stream().anyMatch(event -> "AWS::Lambda::Alias".equals(event.resourceType())
                    && "CREATE_FAILED".equals(event.resourceStatusAsString())
                    && event.resourceStatusReason() != null
                    && event.resourceStatusReason().toLowerCase(Locale.ROOT).contains("initialization")));
            String logicalAlias = sam ? "FunctionAliasLive" : "Alias";
            Arn stackArn = AwsArnUtils.parse(owned.stackId);
            String aliasArn = new Arn(stackArn.partition(), "lambda", stackArn.region(),
                    stackArn.accountId(), "function:" + owned.function + ":live").toString();
            String versionArn = new Arn(stackArn.partition(), "lambda", stackArn.region(),
                    stackArn.accountId(), "function:" + owned.function + ":1").toString();
            List<StackEvent> deletedAliases = events.stream().filter(event -> owned.stackId.equals(event.stackId())
                    && logicalAlias.equals(event.logicalResourceId())
                    && "AWS::Lambda::Alias".equals(event.resourceType())
                    && "DELETE_COMPLETE".equals(event.resourceStatusAsString())
                    && aliasArn.equals(event.physicalResourceId())).toList();
            assertEquals(1, deletedAliases.size());
            if (!sam) {
                List<StackEvent> versionDeletes = events.stream().filter(event -> owned.stackId.equals(event.stackId())
                        && "Version".equals(event.logicalResourceId())
                        && "AWS::Lambda::Version".equals(event.resourceType())
                        && versionArn.equals(event.physicalResourceId())
                        && "DELETE_IN_PROGRESS".equals(event.resourceStatusAsString())).toList();
                assertEquals(1, versionDeletes.size());
                assertFalse(deletedAliases.getFirst().timestamp().isAfter(versionDeletes.getFirst().timestamp()));
                assertTrue(events.stream().anyMatch(event -> owned.stackId.equals(event.stackId())
                        && "Version".equals(event.logicalResourceId())
                        && "AWS::Lambda::Version".equals(event.resourceType())
                        && versionDeletes.getFirst().physicalResourceId().equals(event.physicalResourceId())
                        && "DELETE_COMPLETE".equals(event.resourceStatusAsString())));
            }
            owned.assertFunctionAbsent();
            owned.assertRoleAbsent();
            owned.delete();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updateWithoutADeclarationPreservesTheExternalAllocation(boolean sam) {
        try (Owned owned = new Owned("external-allocation-" + sam)) {
            owned.create(owned.template(sam, false, false, false, "initial"), "CREATE_COMPLETE");
            String version = owned.aliasVersion();
            String physical = owned.aliasPhysicalId(sam);
            owned.lambda.putProvisionedConcurrencyConfig(request -> request.functionName(owned.function)
                    .qualifier("live").provisionedConcurrentExecutions(1));
            owned.awaitReady(1);
            owned.invoke(1);

            List<StackEvent> events = owned.update(owned.withAliasDescription(sam, "changed"), 1, true);

            assertTrue(events.stream().anyMatch(event -> owned.stackId.equals(event.stackId())
                    && (sam ? "FunctionAliasLive" : "Alias").equals(event.logicalResourceId())
                    && physical.equals(event.physicalResourceId())
                    && "AWS::Lambda::Alias".equals(event.resourceType())
                    && "UPDATE_COMPLETE".equals(event.resourceStatusAsString())));
            assertEquals(version, owned.aliasVersion());
            assertEquals(physical, owned.aliasPhysicalId(sam));
            assertEquals("changed", owned.lambda.getAlias(request -> request.functionName(owned.function)
                    .name("live")).description());
            owned.assertReady(1);
            owned.invoke(3);
            owned.delete();
            owned.assertFunctionAbsent();
            owned.assertRoleAbsent();
        }
    }

    private static final class Owned implements AutoCloseable {
        private final WireReceipt wire = new WireReceipt();
        private final String marker = "cfn-pc-" + UUID.randomUUID().toString().replace("-", "");
        private final String function = marker + "-function";
        private final String role = marker + "-role";
        private final String caseName;
        private final CloudFormationClient cfn;
        private final LambdaClient lambda;
        private final IamClient iam;
        private String stackId;
        private boolean attempted;
        private boolean deleted;

        private Owned(String caseName) {
            this.caseName = caseName;
            cfn = AwsTestClients.create(CloudFormationClient.builder().overrideConfiguration(config ->
                    config.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10))
                            .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), ACCOUNT, Region.US_EAST_1);
            lambda = AwsTestClients.create(LambdaClient.builder().overrideConfiguration(config ->
                    config.apiCallTimeout(Duration.ofSeconds(90)).apiCallAttemptTimeout(Duration.ofSeconds(80))
                            .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), ACCOUNT, Region.US_EAST_1);
            iam = AwsTestClients.create(IamClient.builder().overrideConfiguration(config ->
                    config.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10))
                            .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), ACCOUNT, Region.US_EAST_1);
        }

        private String template(boolean sam, boolean declared, boolean noValue, boolean broken, String description) {
            String code = broken ? "raise RuntimeError('intentional initialization failure')\n" : """
                    import os
                    def handler(event, context):
                        return {'initialization': os.environ.get('AWS_LAMBDA_INITIALIZATION_TYPE'),
                                'value': event['value'], 'version': context.function_version}
                    """;
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("FunctionName", function);
            properties.put("Runtime", "python3.12");
            properties.put("Handler", "index.handler");
            properties.put("Role", Map.of("Fn::GetAtt", List.of("Role", "Arn")));
            properties.put("MemorySize", 128);
            Map<String, Object> resources = new LinkedHashMap<>();
            resources.put("Role", Map.of("Type", "AWS::IAM::Role", "Properties", Map.of("RoleName", role,
                    "AssumeRolePolicyDocument", Map.of("Version", "2012-10-17", "Statement", List.of(
                            Map.of("Effect", "Allow", "Principal", Map.of("Service", "lambda.amazonaws.com"),
                                    "Action", "sts:AssumeRole"))))));
            resources.put("Function", Map.of("Type", sam ? "AWS::Serverless::Function" : "AWS::Lambda::Function",
                    "Properties", properties));
            Map<String, Object> alias;
            if (sam) {
                properties.put("InlineCode", code);
                properties.put("AutoPublishAlias", "live");
                alias = properties;
            } else {
                properties.put("Code", Map.of("ZipFile", code));
                resources.put("Version", Map.of("Type", "AWS::Lambda::Version",
                        "Properties", Map.of("FunctionName", Map.of("Ref", "Function"))));
                alias = new LinkedHashMap<>();
                alias.put("FunctionName", Map.of("Ref", "Function"));
                alias.put("Name", "live");
                alias.put("FunctionVersion", Map.of("Fn::GetAtt", List.of("Version", "Version")));
                resources.put("Alias", Map.of("Type", "AWS::Lambda::Alias", "Properties", alias));
            }
            if (declared) {
                Map<String, Object> count = Map.of("ProvisionedConcurrentExecutions", Map.of("Ref", "WarmCount"));
                alias.put("ProvisionedConcurrencyConfig", noValue
                        ? Map.of("Fn::If", List.of("KeepCapacity", count, Map.of("Ref", "AWS::NoValue"))) : count);
            }
            Map<String, Object> template = new LinkedHashMap<>();
            template.put("Description", description);
            template.put("Parameters", Map.of("WarmCount", Map.of("Type", "Number", "Default", 1),
                    "KeepCapacity", Map.of("Type", "String", "Default", "yes")));
            template.put("Conditions", Map.of("KeepCapacity", Map.of("Fn::Equals",
                    List.of(Map.of("Ref", "KeepCapacity"), "yes"))));
            template.put("Resources", resources);
            if (sam) {
                template.put("Transform", "AWS::Serverless-2016-10-31");
            }
            try {
                return MAPPER.writeValueAsString(template);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        private String functionArn() {
            return lambda.getFunction(request -> request.functionName(function)).configuration().functionArn()
                    .replace(":$LATEST", "");
        }

        private String aliasVersion() {
            return lambda.getAlias(request -> request.functionName(function).name("live")).functionVersion();
        }

        private String aliasPhysicalId(boolean sam) {
            return cfn.describeStackResource(request -> request.stackName(stackId)
                    .logicalResourceId(sam ? "FunctionAliasLive" : "Alias"))
                    .stackResourceDetail().physicalResourceId();
        }

        private void assertReady(int count) {
            GetProvisionedConcurrencyConfigResponse current = lambda.getProvisionedConcurrencyConfig(request ->
                    request.functionName(function).qualifier("live"));
            assertEquals("READY", current.statusAsString());
            assertEquals(count, current.requestedProvisionedConcurrentExecutions());
            assertEquals(count, current.allocatedProvisionedConcurrentExecutions());
        }

        private void awaitReady(int count) {
            long started = System.nanoTime();
            while (System.nanoTime() - started < Duration.ofMinutes(5).toNanos()) {
                GetProvisionedConcurrencyConfigResponse current = lambda.getProvisionedConcurrencyConfig(request ->
                        request.functionName(function).qualifier("live"));
                assertNotEquals("FAILED", current.statusAsString(), current.toString());
                if ("READY".equals(current.statusAsString())
                        && current.requestedProvisionedConcurrentExecutions() == count
                        && current.allocatedProvisionedConcurrentExecutions() == count) {
                    return;
                }
                pause();
            }
            throw new AssertionError("External allocation did not initialize");
        }

        private void invoke(int value) {
            InvokeResponse response = lambda.invoke(request -> request.functionName(function).qualifier("live")
                    .payload(SdkBytes.fromUtf8String("{\"value\":" + value + "}")));
            assertEquals(200, response.statusCode());
            assertNull(response.functionError());
            try {
                JsonNode result = MAPPER.readTree(response.payload().asUtf8String());
                assertEquals("provisioned-concurrency", result.path("initialization").asText());
                assertEquals(value, result.path("value").asInt());
                assertEquals(aliasVersion(), result.path("version").asText());
                assertEquals(aliasVersion(), response.executedVersion());
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        private void assertCapacityAbsent() {
            LambdaException failure = assertThrows(LambdaException.class, () ->
                    lambda.getProvisionedConcurrencyConfig(request -> request.functionName(function).qualifier("live")));
            assertEquals(404, failure.statusCode());
            assertEquals("ProvisionedConcurrencyConfigNotFoundException", failure.awsErrorDetails().errorCode());
        }

        private void assertFunctionAbsent() {
            missingLambda(assertThrows(LambdaException.class, () ->
                    lambda.getFunction(request -> request.functionName(function))));
        }

        private void assertRoleAbsent() {
            missingRole(assertThrows(IamException.class, () -> iam.getRole(request -> request.roleName(role))));
        }

        private List<StackEvent> create(String template, String expected) {
            assertFunctionAbsent();
            assertRoleAbsent();
            missingStack(assertThrows(CloudFormationException.class, () ->
                    cfn.describeStacks(request -> request.stackName(marker))), marker);
            attempted = true;
            stackId = cfn.createStack(request -> request.stackName(marker).templateBody(template)
                    .capabilitiesWithStrings("CAPABILITY_NAMED_IAM")).stackId();
            return awaitEvents(Set.of(), expected);
        }

        private String withAliasDescription(boolean sam, String description) {
            String processed = cfn.getTemplate(request -> request.stackName(stackId)
                    .templateStage(TemplateStage.PROCESSED)).templateBody();
            try {
                ObjectNode template = (ObjectNode) MAPPER.readTree(processed);
                JsonNode alias = template.path("Resources").path(sam ? "FunctionAliasLive" : "Alias");
                assertEquals("AWS::Lambda::Alias", alias.path("Type").asText());
                assertFalse(alias.path("Properties").has("ProvisionedConcurrencyConfig"));
                ((ObjectNode) alias.path("Properties")).put("Description", description);
                return MAPPER.writeValueAsString(template);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        private List<StackEvent> update(String template, int count, boolean keep) {
            Set<String> before = ids(events());
            cfn.updateStack(request -> request.stackName(stackId).templateBody(template)
                    .capabilitiesWithStrings("CAPABILITY_NAMED_IAM").parameters(
                            parameter -> parameter.parameterKey("WarmCount").parameterValue(Integer.toString(count)),
                            parameter -> parameter.parameterKey("KeepCapacity").parameterValue(keep ? "yes" : "no")));
            return awaitEvents(before, "UPDATE_COMPLETE");
        }

        private void delete() {
            Set<String> before = ids(events());
            cfn.deleteStack(request -> request.stackName(stackId));
            awaitEvents(before, "DELETE_COMPLETE");
            missingStack(assertThrows(CloudFormationException.class, () ->
                    cfn.describeStacks(request -> request.stackName(marker))), marker);
            deleted = true;
        }

        private List<StackEvent> events() {
            List<StackEvent> result = new ArrayList<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            for (int page = 0; page < 20; page++) {
                String next = cursor;
                DescribeStackEventsResponse response = cfn.describeStackEvents(request ->
                        request.stackName(stackId).nextToken(next));
                result.addAll(response.stackEvents());
                cursor = response.nextToken();
                if (cursor == null) {
                    assertEquals(result.size(), ids(result).size(), "Duplicate event ids");
                    return result;
                }
                assertTrue(cursors.add(cursor), "Repeated event cursor");
            }
            throw new AssertionError("Owned event history exceeded twenty pages");
        }

        private List<StackEvent> awaitEvents(Set<String> before, String expected) {
            long started = System.nanoTime();
            List<StackEvent> latest = List.of();
            while (System.nanoTime() - started < Duration.ofSeconds(330).toNanos()) {
                latest = events().stream().filter(event -> !before.contains(event.eventId())).toList();
                for (StackEvent event : latest) {
                    if (stackId.equals(event.stackId()) && stackId.equals(event.physicalResourceId())
                            && marker.equals(event.logicalResourceId())
                            && "AWS::CloudFormation::Stack".equals(event.resourceType())
                            && TERMINAL.contains(event.resourceStatusAsString())) {
                        assertEquals(expected, event.resourceStatusAsString(), latest.toString());
                        assertEquals(expected, cfn.describeStacks(request -> request.stackName(stackId))
                                .stacks().getFirst().stackStatusAsString());
                        return latest;
                    }
                }
                pause();
            }
            throw new AssertionError("Owned stack did not reach " + expected + ": " + latest);
        }

        private void cleanupStack() {
            if (!attempted || deleted) {
                return;
            }
            String lookup = stackId == null ? marker : stackId;
            try {
                stackId = cfn.describeStacks(request -> request.stackName(lookup)).stacks().getFirst().stackId();
            } catch (CloudFormationException missing) {
                missingStack(missing, lookup);
                deleted = true;
                return;
            }
            long started = System.nanoTime();
            while (System.nanoTime() - started < Duration.ofSeconds(330).toNanos()) {
                String status = cfn.describeStacks(request -> request.stackName(stackId))
                        .stacks().getFirst().stackStatusAsString();
                if (TERMINAL.contains(status)) {
                    if ("DELETE_COMPLETE".equals(status)) {
                        missingStack(assertThrows(CloudFormationException.class, () ->
                                cfn.describeStacks(request -> request.stackName(marker))), marker);
                        deleted = true;
                    } else {
                        delete();
                    }
                    return;
                }
                pause();
            }
            throw new AssertionError("Owned stack remained in progress during cleanup");
        }

        @Override
        public void close() {
            IllegalStateException failures = new IllegalStateException("Owned CFN capacity cleanup failed: " + caseName);
            cleanup(this::cleanupStack, failures);
            if (attempted) {
                cleanup(() -> {
                    try {
                        lambda.deleteAlias(request -> request.functionName(function).name("live"));
                    } catch (LambdaException missing) {
                        missingLambda(missing);
                    }
                    missingLambda(assertThrows(LambdaException.class, () ->
                            lambda.getAlias(request -> request.functionName(function).name("live"))));
                }, failures);
                cleanup(() -> {
                    try {
                        lambda.deleteFunction(request -> request.functionName(function));
                    } catch (LambdaException missing) {
                        missingLambda(missing);
                    }
                    assertFunctionAbsent();
                }, failures);
                cleanup(() -> {
                    try {
                        iam.deleteRole(request -> request.roleName(role));
                    } catch (IamException missing) {
                        missingRole(missing);
                    }
                    assertRoleAbsent();
                }, failures);
                cleanup(this::cleanupStack, failures);
            }
            cleanup(cfn::close, failures);
            cleanup(lambda::close, failures);
            cleanup(iam::close, failures);
            cleanup(() -> wire.save(caseName, marker, failures.getSuppressed().length == 0), failures);
            if (failures.getSuppressed().length > 0) {
                throw failures;
            }
        }
    }

    private static Set<String> ids(List<StackEvent> events) {
        return events.stream().map(StackEvent::eventId).collect(Collectors.toSet());
    }

    private static void missingLambda(LambdaException failure) {
        assertEquals(404, failure.statusCode());
        assertEquals("ResourceNotFoundException", failure.awsErrorDetails().errorCode());
    }

    private static void missingRole(IamException failure) {
        assertEquals(404, failure.statusCode());
        assertEquals("NoSuchEntity", failure.awsErrorDetails().errorCode());
    }

    private static void missingStack(CloudFormationException failure, String lookup) {
        assertEquals(400, failure.statusCode());
        assertEquals("ValidationError", failure.awsErrorDetails().errorCode());
        assertEquals("Stack with id " + lookup + " does not exist", failure.awsErrorDetails().errorMessage());
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
            Thread.sleep(200);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static final class WireReceipt implements ExecutionInterceptor {
        private static final ExecutionAttribute<Integer> ID = new ExecutionAttribute<>("lambda-provisioned-cfn-wire-id");
        private final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertEquals("localhost", context.httpRequest().getUri().getHost());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            assertTrue(calls.size() < 10000);
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
                Path directory = Path.of(System.getProperty("audit.lambda.provisioned.cfn.directory",
                        "target/lambda-provisioned-cfn-wire"));
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
