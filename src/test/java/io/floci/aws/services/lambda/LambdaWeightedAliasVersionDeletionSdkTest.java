package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.AliasRoutingConfiguration;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.GetAliasResponse;
import software.amazon.awssdk.services.lambda.model.LambdaException;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.lambda.model.UpdateAliasRequest;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Published versions remain protected while either target of a weighted alias references them. */
@QuarkusTest
@Timeout(90)
class LambdaWeightedAliasVersionDeletionSdkTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "815670000041";

    @Test
    void weightedReferenceRefusesDeletionAndRemovingItAllowsAnUnchangedRetry() {
        try (Owned owned = new Owned("weighted-reference")) {
            FunctionConfiguration primary = owned.configuration(owned.primaryVersion);
            FunctionConfiguration secondary = owned.configuration(owned.secondaryVersion);
            FunctionConfiguration latest = owned.configuration("$LATEST");
            assertNotEquals(primary.version(), secondary.version());
            assertNotEquals(primary.codeSha256(), secondary.codeSha256());
            assertEquals(primary.role(), secondary.role());
            assertEquals(primary.deadLetterConfig(), secondary.deadLetterConfig());
            GetAliasResponse before = owned.alias();
            assertEquals(primary.version(), before.functionVersion());
            assertEquals(Map.of(secondary.version(), 0.1), before.routingConfig().additionalVersionWeights());

            LambdaException weighted = assertThrows(LambdaException.class, () ->
                    owned.lambda.deleteFunction(request -> request.functionName(owned.marker)
                            .qualifier(owned.secondaryVersion)));
            conflict(weighted);
            assertTrue(before.equalsBySdkFields(owned.alias()));
            assertEquals(primary, owned.configuration(owned.primaryVersion));
            assertEquals(secondary, owned.configuration(owned.secondaryVersion));
            assertEquals(latest, owned.configuration("$LATEST"));

            owned.lambda.deleteAlias(request -> request.functionName(owned.marker).name("live"));
            missingLambda(assertThrows(LambdaException.class, owned::alias));
            owned.lambda.createAlias(request -> request.functionName(owned.marker).name("live")
                    .description(before.description()).functionVersion(owned.primaryVersion));
            GetAliasResponse cleared = owned.alias();
            assertEquals(owned.primaryVersion, cleared.functionVersion());
            assertEquals(before.aliasArn(), cleared.aliasArn());
            assertEquals(before.description(), cleared.description());
            assertTrue(cleared.routingConfig() == null
                    || cleared.routingConfig().additionalVersionWeights().isEmpty());
            owned.lambda.deleteFunction(request -> request.functionName(owned.marker)
                    .qualifier(owned.secondaryVersion));
            missingLambda(assertThrows(LambdaException.class, () ->
                    owned.configuration(owned.secondaryVersion)));
            assertTrue(cleared.equalsBySdkFields(owned.alias()));
            assertEquals(primary, owned.configuration(owned.primaryVersion));
            assertEquals(latest, owned.configuration("$LATEST"));

            conflict(assertThrows(LambdaException.class, () ->
                    owned.lambda.deleteFunction(request -> request.functionName(owned.marker)
                            .qualifier(owned.primaryVersion))));
            assertTrue(cleared.equalsBySdkFields(owned.alias()));
            assertEquals(primary, owned.configuration(owned.primaryVersion));
            owned.lambda.deleteAlias(request -> request.functionName(owned.marker).name("live"));
            owned.aliasAttempted = false;
            missingLambda(assertThrows(LambdaException.class, owned::alias));
            owned.lambda.deleteFunction(request -> request.functionName(owned.marker)
                    .qualifier(owned.primaryVersion));
            missingLambda(assertThrows(LambdaException.class, () ->
                    owned.configuration(owned.primaryVersion)));
            assertEquals(latest, owned.configuration("$LATEST"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void omittedRoutingKeepsWeightsWhileExplicitEmptyWeightsClearThemAndUnblockDeletion(boolean includeWeights) {
        try (Owned owned = new Owned(includeWeights ? "explicit-empty" : "empty-routing-object")) {
            GetAliasResponse before = owned.alias();
            FunctionConfiguration primary = owned.configuration(owned.primaryVersion);
            FunctionConfiguration secondary = owned.configuration(owned.secondaryVersion);
            FunctionConfiguration latest = owned.configuration("$LATEST");
            assertEquals(Map.of(owned.secondaryVersion, 0.1), before.routingConfig().additionalVersionWeights());

            owned.lambda.updateAlias(request -> request.functionName(owned.marker).name("live")
                    .description("updated without routing"));
            GetAliasResponse omitted = owned.alias();
            assertEquals(before.functionVersion(), omitted.functionVersion());
            assertEquals(before.aliasArn(), omitted.aliasArn());
            assertEquals(before.routingConfig(), omitted.routingConfig());
            assertEquals("updated without routing", omitted.description());
            assertEquals(primary, owned.configuration(owned.primaryVersion));
            assertEquals(secondary, owned.configuration(owned.secondaryVersion));

            AliasRoutingConfiguration.Builder routing = AliasRoutingConfiguration.builder();
            if (includeWeights) {
                routing.additionalVersionWeights(Map.of());
            }
            owned.lambda.updateAlias(request -> request.functionName(owned.marker).name("live")
                    .functionVersion(owned.primaryVersion)
                    .routingConfig(routing.build()));
            GetAliasResponse cleared = owned.alias();
            assertEquals(owned.primaryVersion, cleared.functionVersion());
            assertEquals(before.aliasArn(), cleared.aliasArn());
            assertEquals(omitted.description(), cleared.description());
            assertTrue(cleared.routingConfig() == null
                    || cleared.routingConfig().additionalVersionWeights().isEmpty(),
                    "Explicit empty AdditionalVersionWeights must clear the prior weighted reference");
            assertEquals(primary, owned.configuration(owned.primaryVersion));
            assertEquals(secondary, owned.configuration(owned.secondaryVersion));
            assertEquals(latest, owned.configuration("$LATEST"));

            owned.lambda.deleteFunction(request -> request.functionName(owned.marker)
                    .qualifier(owned.secondaryVersion));
            missingLambda(assertThrows(LambdaException.class, () ->
                    owned.configuration(owned.secondaryVersion)));
            assertTrue(cleared.equalsBySdkFields(owned.alias()));
            assertEquals(primary, owned.configuration(owned.primaryVersion));
            assertEquals(latest, owned.configuration("$LATEST"));
            conflict(assertThrows(LambdaException.class, () ->
                    owned.lambda.deleteFunction(request -> request.functionName(owned.marker)
                            .qualifier(owned.primaryVersion))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"invalid\"", "42", "true", "[]"})
    void malformedWeightTypesAreRejectedBeforeChangingAnyAliasOrVersion(String malformedWeights) {
        try (Owned owned = new Owned("malformed-weights")) {
            GetAliasResponse before = owned.alias();
            FunctionConfiguration primary = owned.configuration(owned.primaryVersion);
            FunctionConfiguration secondary = owned.configuration(owned.secondaryVersion);
            FunctionConfiguration latest = owned.configuration("$LATEST");
            LambdaException failure = assertThrows(LambdaException.class, () -> owned.malformedUpdate(
                    "{\"Description\":\"must not persist\",\"RoutingConfig\":{\"AdditionalVersionWeights\":"
                            + malformedWeights + "}}"));
            assertEquals(400, failure.statusCode());
            assertEquals("InvalidParameterValueException", failure.awsErrorDetails().errorCode());
            assertTrue(before.equalsBySdkFields(owned.alias()));
            assertEquals(primary, owned.configuration(owned.primaryVersion));
            assertEquals(secondary, owned.configuration(owned.secondaryVersion));
            assertEquals(latest, owned.configuration("$LATEST"));
        }
    }

    private static final class Owned implements AutoCloseable {
        private final String caseName;
        private final String marker = "weighted-version-" + UUID.randomUUID().toString().replace("-", "");
        private final WireReceipt wire = new WireReceipt();
        private LambdaClient lambda;
        private IamClient iam;
        private String primaryVersion;
        private String secondaryVersion;
        private boolean roleAttempted;
        private boolean functionAttempted;
        private boolean aliasAttempted;

        private Owned(String caseName) {
            this.caseName = caseName;
            try {
                lambda = AwsTestClients.create(LambdaClient.builder().overrideConfiguration(config ->
                        config.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10))
                                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), ACCOUNT, Region.US_EAST_1);
                iam = AwsTestClients.create(IamClient.builder().overrideConfiguration(config ->
                        config.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10))
                                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), ACCOUNT, Region.US_EAST_1);
                roleAttempted = true;
                String roleArn = iam.createRole(request -> request.roleName(marker).assumeRolePolicyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                        """)).role().arn();
                functionAttempted = true;
                lambda.createFunction(request -> request.functionName(marker).runtime(Runtime.PYTHON3_12)
                        .handler("handler.handler").role(roleArn)
                        .code(code -> code.zipFile(SdkBytes.fromByteArray(zip("primary")))));
                awaitFunctionReady();
                primaryVersion = lambda.publishVersion(request -> request.functionName(marker)).version();
                lambda.updateFunctionCode(request -> request.functionName(marker)
                        .zipFile(SdkBytes.fromByteArray(zip("secondary"))));
                awaitFunctionReady();
                secondaryVersion = lambda.publishVersion(request -> request.functionName(marker)).version();
                aliasAttempted = true;
                lambda.createAlias(request -> request.functionName(marker).name("live").description("owned weighted alias")
                        .functionVersion(primaryVersion)
                        .routingConfig(config -> config.additionalVersionWeights(Map.of(secondaryVersion, 0.1))));
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

        private FunctionConfiguration configuration(String version) {
            return lambda.getFunction(request -> request.functionName(marker).qualifier(version)).configuration();
        }

        private GetAliasResponse alias() {
            return lambda.getAlias(request -> request.functionName(marker).name("live"));
        }

        /** The SDK model cannot express malformed map types, so replace only this request's HTTP body. */
        private void malformedUpdate(String body) {
            ExecutionInterceptor malformed = new ExecutionInterceptor() {
                @Override
                public SdkHttpRequest modifyHttpRequest(Context.ModifyHttpRequest context, ExecutionAttributes attributes) {
                    assertTrue(context.request() instanceof UpdateAliasRequest);
                    return context.httpRequest().toBuilder()
                            .putHeader("Content-Length", Integer.toString(body.getBytes(StandardCharsets.UTF_8).length))
                            .build();
                }

                @Override
                public Optional<RequestBody> modifyHttpContent(Context.ModifyHttpRequest context,
                                                               ExecutionAttributes attributes) {
                    assertTrue(context.request() instanceof UpdateAliasRequest);
                    return Optional.of(RequestBody.fromString(body));
                }
            };
            try (LambdaClient malformedClient = AwsTestClients.create(LambdaClient.builder()
                    .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(15))
                            .apiCallAttemptTimeout(Duration.ofSeconds(10)).retryPolicy(RetryPolicy.none())
                            .addExecutionInterceptor(malformed).addExecutionInterceptor(wire)), ACCOUNT, Region.US_EAST_1)) {
                malformedClient.updateAlias(request -> request.functionName(marker).name("live"));
            }
        }

        private void awaitFunctionReady() {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                FunctionConfiguration current = configuration("$LATEST");
                assertNotEquals("Failed", current.stateAsString(), current.toString());
                assertNotEquals("Failed", current.lastUpdateStatusAsString(), current.toString());
                if ("Active".equals(current.stateAsString())
                        && (current.lastUpdateStatusAsString() == null
                        || "Successful".equals(current.lastUpdateStatusAsString()))) {
                    return;
                }
                pause();
            }
            throw new AssertionError("Owned function did not become ready before publishing");
        }

        @Override
        public void close() {
            IllegalStateException failures = new IllegalStateException("Owned weighted alias fixture cleanup failed");
            if (lambda != null && aliasAttempted) {
                cleanup(() -> {
                    try {
                        lambda.deleteAlias(request -> request.functionName(marker).name("live"));
                    } catch (LambdaException missing) {
                        missingLambda(missing);
                    }
                    missingLambda(assertThrows(LambdaException.class, this::alias));
                }, failures);
            }
            if (lambda != null && functionAttempted) {
                cleanup(() -> {
                    try {
                        lambda.deleteFunction(request -> request.functionName(marker));
                    } catch (LambdaException missing) {
                        missingLambda(missing);
                    }
                    missingLambda(assertThrows(LambdaException.class, () ->
                            lambda.getFunction(request -> request.functionName(marker))));
                }, failures);
            }
            if (iam != null && roleAttempted) {
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
            if (lambda != null) {
                cleanup(lambda::close, failures);
            }
            if (iam != null) {
                cleanup(iam::close, failures);
            }
            cleanup(() -> wire.save(caseName, marker, failures.getSuppressed().length == 0), failures);
            if (failures.getSuppressed().length > 0) {
                throw failures;
            }
        }
    }

    private static byte[] zip(String value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("handler.py"));
                zip.write(("def handler(event, context):\n    return '" + value + "'\n").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void conflict(LambdaException failure) {
        assertEquals(409, failure.statusCode());
        assertEquals("ResourceConflictException", failure.awsErrorDetails().errorCode());
    }

    private static void missingLambda(LambdaException failure) {
        assertEquals(404, failure.statusCode());
        assertEquals("ResourceNotFoundException", failure.awsErrorDetails().errorCode());
    }

    private static void missingRole(IamException failure) {
        assertEquals(404, failure.statusCode());
        assertEquals("NoSuchEntity", failure.awsErrorDetails().errorCode());
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
        private static final ExecutionAttribute<Integer> ID = new ExecutionAttribute<>("lambda-weighted-alias-wire-id");
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
                Path directory = Path.of(System.getProperty("audit.lambda.weighted.alias.directory",
                        "target/lambda-weighted-alias-wire"));
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
