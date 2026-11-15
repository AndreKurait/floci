package io.github.hectorvent.floci.services.applicationautoscaling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils.Arn;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
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
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableTarget;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalingActivity;
import software.amazon.awssdk.services.applicationautoscaling.model.ScheduledAction;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetProvisionedConcurrencyConfigResponse;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.github.hectorvent.floci.services.applicationautoscaling.ScheduledActionFixtures.schedule;
import static org.junit.jupiter.api.Assertions.*;

/** Public SDK calls drive the real background scheduler and Docker Lambda pool. */
@QuarkusTest
@Timeout(420)
class LambdaScheduledActionSdkTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "815670000061";
    private static final String DIMENSION = "lambda:function:ProvisionedConcurrency";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scheduledBoundsInitializeAndRemoveAliasOrVersionCapacity(boolean alias) {
        try (Owned owned = new Owned("bounds-" + alias, ACCOUNT, Region.US_EAST_1, null)) {
            owned.create(alias);
            owned.register(false);
            ScalableTarget target = owned.target();
            ScheduledAction original = owned.put("out", 1, null);
            owned.awaitActivity("out");
            owned.assertReady();
            owned.invoke();
            owned.assertBounds(1, 1);
            owned.assertActionIdentity(original);

            owned.put("minimum", 0, null);
            owned.awaitActivity("minimum");
            owned.assertBounds(0, 1);
            owned.assertReady();
            owned.put("in", null, 0);
            owned.awaitActivity("in");
            owned.awaitCapacityAbsent();
            owned.assertBounds(0, 0);
            assertEquals(target.scalableTargetARN(), owned.target().scalableTargetARN());
            assertEquals(target.creationTime(), owned.target().creationTime());
            owned.assertFunctionIdentity();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suspendedOccurrenceRequiresExplicitReschedule(boolean alias) {
        try (Owned owned = new Owned("suspended-" + alias, ACCOUNT, Region.US_EAST_1, null)) {
            owned.create(alias);
            owned.register(true);
            ScheduledAction original = owned.put("suspended", 1, 1);
            owned.waitPast(owned.deadline);
            assertTrue(owned.activities().isEmpty());
            owned.assertBounds(0, 1);
            owned.assertCapacityAbsent();
            owned.scaling.registerScalableTarget(b -> b.serviceNamespace("lambda").resourceId(owned.resource)
                    .scalableDimension(DIMENSION).suspendedState(s -> s.scheduledScalingSuspended(false)
                            .dynamicScalingInSuspended(true).dynamicScalingOutSuspended(true)));
            owned.assertCapacityAbsent();
            owned.deadline = Instant.now().plusSeconds(3);
            owned.scaling.putScheduledAction(b -> b.serviceNamespace("lambda").resourceId(owned.resource)
                    .scalableDimension(DIMENSION).scheduledActionName("suspended").schedule(schedule(owned.deadline)));
            owned.awaitActivity("suspended");
            owned.assertReady();
            owned.assertBounds(1, 1);
            owned.assertActionIdentity(original);
            owned.invoke();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationAndDeregistrationPreserveNativeCapacity(boolean alias) {
        try (Owned owned = new Owned("cancel-" + alias, ACCOUNT, Region.US_EAST_1, null)) {
            owned.create(alias);
            owned.lambda.putProvisionedConcurrencyConfig(b -> b.functionName(owned.function)
                    .qualifier(owned.qualifier).provisionedConcurrentExecutions(1));
            owned.assertReady();
            owned.register(false);
            owned.put("cancel", 0, 0);
            owned.scaling.deleteScheduledAction(b -> b.serviceNamespace("lambda").resourceId(owned.resource)
                    .scalableDimension(DIMENSION).scheduledActionName("cancel"));
            owned.waitPast(owned.deadline);
            assertTrue(owned.actions().isEmpty());
            assertTrue(owned.activities().isEmpty());
            owned.assertReady();
            owned.put("deregister", 0, 0);
            owned.deregister();
            owned.waitPast(owned.deadline);
            owned.assertTargetAbsent();
            assertTrue(owned.actions().isEmpty());
            owned.assertReady();
            owned.invoke();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backgroundSchedulingUsesTheTargetAccountAndRegion(boolean alias) {
        try (Owned owner = new Owned("isolation-owner-" + alias, ACCOUNT, Region.US_EAST_1, null);
             Owned account = new Owned("isolation-account-" + alias, "815670000062", Region.US_EAST_1, owner.function);
             Owned region = new Owned("isolation-region-" + alias, ACCOUNT, Region.US_WEST_2, owner.function)) {
            for (Owned owned : List.of(owner, account, region)) {
                owned.create(alias);
                owned.register(false);
                owned.assertCapacityAbsent();
            }
            assertEquals(owner.resource, account.resource);
            assertEquals(owner.resource, region.resource);
            owner.put("owner-only", 1, 1);
            owner.awaitActivity("owner-only");
            owner.assertReady();
            owner.invoke();
            for (Owned other : List.of(account, region)) {
                assertTrue(other.actions().isEmpty());
                assertTrue(other.activities().isEmpty());
                other.assertBounds(0, 1);
                other.assertCapacityAbsent();
                other.assertFunctionIdentity();
            }
        }
    }

    private static final class Owned implements AutoCloseable {
        private final String marker = "lambda-aas-" + UUID.randomUUID().toString().replace("-", "");
        private final String role = marker + "-role";
        private final WireReceipt wire = new WireReceipt();
        private final String caseName;
        private final String account;
        private final Region region;
        private final String function;
        private final ApplicationAutoScalingClient scaling;
        private final LambdaClient lambda;
        private final IamClient iam;
        private String version;
        private String qualifier;
        private String resource;
        private String roleId;
        private boolean roleAttempted;
        private boolean functionAttempted;
        private boolean targetAttempted;
        private Instant deadline;

        private Owned(String caseName, String account, Region region, String function) {
            this.caseName = caseName;
            this.account = account;
            this.region = region;
            this.function = function == null ? marker : function;
            scaling = AwsTestClients.create(ApplicationAutoScalingClient.builder().overrideConfiguration(c ->
                    c.apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(15))
                            .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, region);
            lambda = AwsTestClients.create(LambdaClient.builder().overrideConfiguration(c ->
                    c.apiCallTimeout(Duration.ofSeconds(90)).apiCallAttemptTimeout(Duration.ofSeconds(80))
                            .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, region);
            iam = AwsTestClients.create(IamClient.builder().overrideConfiguration(c ->
                    c.apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(15))
                            .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, region);
        }

        private void create(boolean alias) {
            missing(assertThrows(IamException.class, () -> iam.getRole(b -> b.roleName(role))), "NoSuchEntity");
            roleAttempted = true;
            roleId = iam.createRole(b -> b.roleName(role).path("/" + marker + "/")
                    .tags(t -> t.key("fixture").value(marker)).assumeRolePolicyDocument("""
                            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                            "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                            """)).role().roleId();
            missing(assertThrows(LambdaException.class, () -> lambda.getFunction(b -> b.functionName(function))),
                    "ResourceNotFoundException");
            functionAttempted = true;
            lambda.createFunction(b -> b.functionName(function).runtime("python3.12").handler("index.handler")
                    .role(roleArn()).memorySize(128).timeout(10).tags(Map.of("fixture", marker))
                    .code(code -> code.zipFile(SdkBytes.fromByteArray(code()))));
            version = lambda.publishVersion(b -> b.functionName(function)).version();
            assertTrue(version.matches("[1-9][0-9]*"));
            qualifier = alias ? "live" : version;
            resource = "function:" + function + ":" + qualifier;
            if (alias) {
                lambda.createAlias(b -> b.functionName(function).name(qualifier).functionVersion(version));
            }
            assertFunctionIdentity();
        }

        private String roleArn() {
            return Arn.global(AwsRegions.partitionFor(region.id()), "iam", account,
                    "role/" + marker + "/" + role).toString();
        }

        private String functionArn() {
            return Arn.of("lambda", region.id(), account, "function:" + function).toString();
        }

        private void assertFunctionIdentity() {
            assertEquals(functionArn(), lambda.getFunction(b -> b.functionName(function)).configuration().functionArn());
            assertEquals(marker, lambda.getFunction(b -> b.functionName(function)).tags().get("fixture"));
            assertEquals(roleArn(), lambda.getFunction(b -> b.functionName(function)).configuration().role());
            assertEquals(version, lambda.getFunction(b -> b.functionName(function).qualifier(version)).configuration().version());
            if ("live".equals(qualifier)) {
                assertEquals(version, lambda.getAlias(b -> b.functionName(function).name(qualifier)).functionVersion());
            }
        }

        private void register(boolean suspended) {
            assertTargetAbsent();
            targetAttempted = true;
            scaling.registerScalableTarget(b -> b.serviceNamespace("lambda").resourceId(resource)
                    .scalableDimension(DIMENSION).minCapacity(0).maxCapacity(1)
                    .tags(Map.of("fixture", marker)).suspendedState(s -> s.scheduledScalingSuspended(suspended)));
            assertEquals(marker, scaling.listTagsForResource(b -> b.resourceARN(target().scalableTargetARN())).tags().get("fixture"));
        }

        private ScalableTarget target() {
            List<ScalableTarget> targets = scaling.describeScalableTargets(b -> b.serviceNamespace("lambda")
                    .resourceIds(resource).scalableDimension(DIMENSION)).scalableTargets();
            assertEquals(1, targets.size());
            ScalableTarget target = targets.getFirst();
            assertEquals(resource, target.resourceId());
            assertEquals(DIMENSION, target.scalableDimensionAsString());
            assertEquals("lambda", target.serviceNamespaceAsString());
            assertTrue(target.scalableTargetARN().contains(":" + region.id() + ":" + account + ":"));
            return target;
        }

        private void assertTargetAbsent() {
            assertTrue(scaling.describeScalableTargets(b -> b.serviceNamespace("lambda")
                    .resourceIds(resource).scalableDimension(DIMENSION)).scalableTargets().isEmpty());
        }

        private void assertBounds(int min, int max) {
            assertEquals(min, target().minCapacity());
            assertEquals(max, target().maxCapacity());
        }

        private ScheduledAction put(String name, Integer min, Integer max) {
            deadline = Instant.now().plusSeconds(3);
            scaling.putScheduledAction(b -> b.serviceNamespace("lambda").resourceId(resource)
                    .scalableDimension(DIMENSION).scheduledActionName(name).schedule(schedule(deadline)).timezone("UTC")
                    .scalableTargetAction(a -> a.minCapacity(min).maxCapacity(max)));
            ScheduledAction action = actions().stream().filter(a -> name.equals(a.scheduledActionName())).findFirst().orElseThrow();
            assertEquals(schedule(deadline), action.schedule());
            assertEquals(min, action.scalableTargetAction().minCapacity());
            assertEquals(max, action.scalableTargetAction().maxCapacity());
            assertEquals(resource, action.resourceId());
            assertEquals(DIMENSION, action.scalableDimensionAsString());
            assertNotNull(action.creationTime());
            assertTrue(action.scheduledActionARN().contains(":" + region.id() + ":" + account + ":"));
            return action;
        }

        private List<ScheduledAction> actions() {
            return scaling.describeScheduledActions(b -> b.serviceNamespace("lambda").resourceId(resource)
                    .scalableDimension(DIMENSION)).scheduledActions();
        }

        private void assertActionIdentity(ScheduledAction original) {
            ScheduledAction current = actions().stream().filter(a -> original.scheduledActionName().equals(a.scheduledActionName()))
                    .findFirst().orElseThrow();
            assertEquals(original.scheduledActionARN(), current.scheduledActionARN());
            assertEquals(original.creationTime(), current.creationTime());
            assertEquals(original.resourceId(), current.resourceId());
            assertEquals("UTC", current.timezone());
        }

        private List<ScalingActivity> activities() {
            return scaling.describeScalingActivities(b -> b.serviceNamespace("lambda").resourceId(resource)
                    .scalableDimension(DIMENSION)).scalingActivities();
        }

        private void awaitActivity(String name) {
            long until = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < until) {
                List<ScalingActivity> matching = activities().stream().filter(a -> ("scheduled action " + name).equals(a.cause())).toList();
                if (!matching.isEmpty()) {
                    assertEquals(1, matching.size());
                    ScalingActivity activity = matching.getFirst();
                    assertEquals("Successful", activity.statusCodeAsString(), activity.toString());
                    assertEquals(resource, activity.resourceId());
                    assertEquals(DIMENSION, activity.scalableDimensionAsString());
                    assertNotNull(activity.startTime());
                    assertNotNull(activity.endTime());
                    return;
                }
                pause();
            }
            throw new AssertionError("No successful background activity for " + name);
        }

        private void assertReady() {
            long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (System.nanoTime() < until) {
                GetProvisionedConcurrencyConfigResponse response = lambda.getProvisionedConcurrencyConfig(
                        b -> b.functionName(function).qualifier(qualifier));
                assertEquals(1, response.requestedProvisionedConcurrentExecutions());
                assertNotEquals("FAILED", response.statusAsString(), response.toString());
                if ("READY".equals(response.statusAsString())) {
                    assertEquals(1, response.allocatedProvisionedConcurrentExecutions());
                    return;
                }
                pause();
            }
            throw new AssertionError("Owned provisioned capacity did not become READY");
        }

        private void assertCapacityAbsent() {
            missing(assertThrows(LambdaException.class, () -> lambda.getProvisionedConcurrencyConfig(
                    b -> b.functionName(function).qualifier(qualifier))), "ProvisionedConcurrencyConfigNotFoundException");
        }

        private void awaitCapacityAbsent() {
            long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (System.nanoTime() < until) {
                try {
                    lambda.getProvisionedConcurrencyConfig(b -> b.functionName(function).qualifier(qualifier));
                } catch (LambdaException absent) {
                    missing(absent, "ProvisionedConcurrencyConfigNotFoundException");
                    return;
                }
                pause();
            }
            throw new AssertionError("Owned allocation remained after zero-capacity schedule");
        }

        private void invoke() {
            InvokeResponse response = lambda.invoke(b -> b.functionName(function).qualifier(qualifier)
                    .payload(SdkBytes.fromUtf8String("{}")));
            assertEquals(200, response.statusCode());
            assertNull(response.functionError());
            assertEquals(version, response.executedVersion());
            try {
                JsonNode value = MAPPER.readTree(response.payload().asByteArray());
                assertEquals("provisioned-concurrency", value.path("initialization").asText());
                assertEquals(version, value.path("version").asText());
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        private void waitPast(Instant at) {
            long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (Instant.now().isBefore(at.plusSeconds(2))) {
                assertTrue(System.nanoTime() < until, "Local scheduler wait exceeded deadline");
                pause();
            }
        }

        private void deregister() {
            scaling.deregisterScalableTarget(b -> b.serviceNamespace("lambda").resourceId(resource).scalableDimension(DIMENSION));
            assertTargetAbsent();
            assertTrue(actions().isEmpty());
        }

        @Override
        public void close() {
            IllegalStateException failures = new IllegalStateException("Owned scheduled Lambda cleanup failed: " + caseName);
            if (targetAttempted) {
                cleanup(() -> {
                    List<ScalableTarget> targets = scaling.describeScalableTargets(b -> b.serviceNamespace("lambda")
                            .resourceIds(resource).scalableDimension(DIMENSION)).scalableTargets();
                    if (!targets.isEmpty()) {
                        assertEquals(marker, scaling.listTagsForResource(b -> b.resourceARN(target().scalableTargetARN())).tags().get("fixture"));
                        deregister();
                    }
                    assertTargetAbsent();
                    assertTrue(actions().isEmpty());
                }, failures);
            }
            if (functionAttempted) {
                cleanup(() -> {
                    try {
                        assertEquals(marker, lambda.getFunction(b -> b.functionName(function)).tags().get("fixture"));
                        assertEquals(functionArn(), lambda.getFunction(b -> b.functionName(function)).configuration().functionArn());
                        assertEquals(roleArn(), lambda.getFunction(b -> b.functionName(function)).configuration().role());
                        lambda.deleteFunction(b -> b.functionName(function));
                    } catch (LambdaException absent) {
                        missing(absent, "ResourceNotFoundException");
                    }
                    missing(assertThrows(LambdaException.class, () -> lambda.getFunction(b -> b.functionName(function))),
                            "ResourceNotFoundException");
                    if (qualifier != null) {
                        missing(assertThrows(LambdaException.class, () -> lambda.getProvisionedConcurrencyConfig(
                                b -> b.functionName(function).qualifier(qualifier))), "ResourceNotFoundException");
                    }
                }, failures);
            }
            if (roleAttempted) {
                cleanup(() -> {
                    try {
                        assertEquals(roleArn(), iam.getRole(b -> b.roleName(role)).role().arn());
                        assertEquals("/" + marker + "/", iam.getRole(b -> b.roleName(role)).role().path());
                        if (roleId != null) {
                            assertEquals(roleId, iam.getRole(b -> b.roleName(role)).role().roleId());
                        }
                        iam.deleteRole(b -> b.roleName(role));
                    } catch (IamException absent) {
                        missing(absent, "NoSuchEntity");
                    }
                    missing(assertThrows(IamException.class, () -> iam.getRole(b -> b.roleName(role))), "NoSuchEntity");
                }, failures);
            }
            cleanup(scaling::close, failures);
            cleanup(lambda::close, failures);
            cleanup(iam::close, failures);
            cleanup(() -> wire.save(caseName, marker, failures.getSuppressed().length == 0), failures);
            if (failures.getSuppressed().length > 0) {
                throw failures;
            }
        }
    }

    private static byte[] code() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.py"));
            zip.write("""
                    import os
                    def handler(event, context):
                        return {'initialization': os.environ.get('AWS_LAMBDA_INITIALIZATION_TYPE'),
                                'version': context.function_version}
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void missing(AwsServiceException failure, String code) {
        assertEquals(404, failure.statusCode());
        assertEquals(code, failure.awsErrorDetails().errorCode());
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
        private static final ExecutionAttribute<Integer> ID = new ExecutionAttribute<>("lambda-scheduled-wire-id");
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
                Path directory = Path.of(System.getProperty("audit.lambda.scheduled.directory",
                        "target/lambda-scheduled-wire"));
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
