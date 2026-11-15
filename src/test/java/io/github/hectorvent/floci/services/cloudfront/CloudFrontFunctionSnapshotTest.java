package io.github.hectorvent.floci.services.cloudfront;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudFrontFunctionSnapshotTest {

    private static final String ACCOUNT = "111111111111";
    private static final String OTHER = "222222222222";
    private static final String NAME = "owned-function";
    private static final String CODE = "function handler(event){return event.request;}";
    private static final TypeReference<Map<String, CloudFrontFunction>> FUNCTIONS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Map<String, String>>> TAGS = new TypeReference<>() {};

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void scopedAndLegacySnapshotsAndTagsLeaveRawStorageUnchanged(String mode) throws Exception {
        try (Fixture fixture = new Fixture(mode)) {
            CloudFrontFunction legacy = function("DEVELOPMENT", "old", CODE);
            fixture.functions.put(NAME, legacy);
            fixture.tags.put(arn("aws"), Map.of("owner", "legacy"));
            fixture.flush();
            Map<String, String> before = files();
            Set<String> functionKeys = fixture.functions.keys();
            Set<String> tagKeys = fixture.tags.keys();
            assertEquals(CODE, fixture.testing.capture(ACCOUNT, "aws", NAME, "DEVELOPMENT", "old").code());
            assertEquals(Map.of("owner", "legacy"), fixture.testing.currentTags(NAME));
            assertEquals(404, assertThrows(AwsException.class,
                    () -> fixture.testing.capture(OTHER, "aws", NAME, "DEVELOPMENT", "old")).getHttpStatus());
            fixture.flush();
            assertEquals(before, files());
            assertEquals(functionKeys, fixture.functions.keys());
            assertEquals(tagKeys, fixture.tags.keys());

            fixture.functions.put(ACCOUNT + "/" + NAME, function("DEVELOPMENT", "current", CODE + "// scoped"));
            fixture.functions.put(OTHER + "/" + NAME, function("DEVELOPMENT", "foreign", CODE + "// foreign"));
            fixture.tags.put(ACCOUNT + "/" + arn("aws"), Map.of("owner", "scoped"));
            fixture.flush();
            before = files();
            functionKeys = fixture.functions.keys();
            tagKeys = fixture.tags.keys();
            assertEquals(CODE + "// scoped",
                    fixture.testing.capture(ACCOUNT, "aws", NAME, "DEVELOPMENT", "current").code());
            assertEquals(Map.of("owner", "scoped"), fixture.testing.currentTags(NAME));
            assertEquals(CODE + "// foreign",
                    fixture.testing.capture(OTHER, "aws", NAME, "DEVELOPMENT", "foreign").code());
            assertEquals(arn("aws-cn"),
                    fixture.testing.capture(ACCOUNT, "aws-cn", NAME, "DEVELOPMENT", "current").arn());
            fixture.flush();
            assertEquals(before, files());
            assertEquals(functionKeys, fixture.functions.keys());
            assertEquals(tagKeys, fixture.tags.keys());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void bareLegacyLiveStageAndCorruptRowsAreDistinguishedWithoutMigration(String mode) throws Exception {
        try (Fixture fixture = new Fixture(mode)) {
            fixture.functions.put(NAME, function("LIVE", "live", CODE));
            fixture.flush();
            Map<String, String> before = files();
            assertEquals("LIVE", fixture.testing.capture(ACCOUNT, "aws", NAME, "LIVE", "live").stage());
            assertEquals(404, assertThrows(AwsException.class,
                    () -> fixture.testing.capture(ACCOUNT, "aws", NAME, "DEVELOPMENT", "live")).getHttpStatus());
            fixture.flush();
            assertEquals(before, files());
            assertEquals(Set.of(NAME), fixture.functions.keys());

            CloudFrontFunction corrupt = function("DEVELOPMENT", "current", CODE);
            corrupt.setName("different-parent");
            fixture.functions.put(ACCOUNT + "/" + NAME, corrupt);
            fixture.flush();
            before = files();
            assertEquals(400, assertThrows(AwsException.class,
                    () -> fixture.testing.capture(ACCOUNT, "aws", NAME, "DEVELOPMENT", "current")).getHttpStatus());
            fixture.flush();
            assertEquals(before, files());
            corrupt.setName(NAME);
            corrupt.setStage("UNKNOWN");
            fixture.functions.put(ACCOUNT + "/" + NAME, corrupt);
            fixture.flush();
            before = files();
            assertEquals(400, assertThrows(AwsException.class,
                    () -> fixture.testing.capture(ACCOUNT, "aws", NAME, "DEVELOPMENT", "current")).getHttpStatus());
            fixture.flush();
            assertEquals(before, files());
        }
    }

    @Test
    void capturedSourceAndGenerationRemainDetachedFromLaterReplacement() throws Exception {
        try (Fixture fixture = new Fixture("memory")) {
            CloudFrontFunction original = function("DEVELOPMENT", "captured", CODE);
            fixture.functions.put(ACCOUNT + "/" + NAME, original);
            CloudFrontFunctionSnapshot captured = fixture.testing.capture(ACCOUNT, "aws", NAME, "DEVELOPMENT", "captured");
            original.setFunctionCode(encoded("changed in place"));
            original.setEtag("in-place-change");
            fixture.functions.delete(ACCOUNT + "/" + NAME);
            fixture.functions.put(ACCOUNT + "/" + NAME, function("DEVELOPMENT", "replacement", "replacement source"));
            assertEquals(CODE, captured.code());
            assertEquals("captured", captured.etag());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), captured.createdTime());
        }
    }

    @Test
    void invalidInputAndStaleVersionsDoNotReachEvaluation() throws Exception {
        try (Fixture fixture = new Fixture("memory")) {
            fixture.functions.put(ACCOUNT + "/" + NAME, function("DEVELOPMENT", "current", CODE));
            for (String event : List.of("!", encoded("null"), encoded("[]"), encoded("{}{}"),
                    encoded("{"), Base64.getEncoder().encodeToString(new byte[] {(byte) 0xc3, (byte) 0x28}),
                    encoded("{\"padding\":\"" + "x".repeat(40960) + "\"}"))) {
                assertEquals(400, assertThrows(AwsException.class,
                        () -> fixture.testing.test(NAME, "DEVELOPMENT", "current", event)).getHttpStatus());
            }
            for (String stage : List.of("", "development", "UNKNOWN")) {
                assertEquals(400, assertThrows(AwsException.class,
                        () -> fixture.testing.test(NAME, stage, "current", encoded("{}"))).getHttpStatus());
            }
            assertEquals(400, assertThrows(AwsException.class,
                    () -> fixture.testing.test(NAME, null, null, encoded("{}"))).getHttpStatus());
            assertEquals(412, assertThrows(AwsException.class,
                    () -> fixture.testing.test(NAME, null, "previous", encoded("{}"))).getHttpStatus());
            assertEquals(0, fixture.evaluations.get());
            fixture.testing.test(NAME, null, "current", encoded("{}"));
            assertEquals(1, fixture.evaluations.get());
        }
    }

    private Map<String, String> files() throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                result.put(directory.relativize(path).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        }
        return result;
    }

    private static CloudFrontFunction function(String stage, String etag, String code) {
        CloudFrontFunction function = new CloudFrontFunction();
        function.setName(NAME);
        function.setStage(stage);
        function.setStatus("LIVE".equals(stage) ? "DEPLOYED" : "UNPUBLISHED");
        function.setEtag(etag);
        function.setFunctionCode(encoded(code));
        function.setRuntime("cloudfront-js-2.0");
        function.setComment("owned");
        function.setCreatedTime(Instant.parse("2026-01-01T00:00:00Z"));
        function.setLastModifiedTime(function.getCreatedTime());
        return function;
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String arn(String partition) {
        return AwsArnUtils.Arn.global(partition, "cloudfront", ACCOUNT, "function/" + NAME).toString();
    }

    private final class Fixture implements AutoCloseable {
        private final StorageBackend<String, CloudFrontFunction> functions;
        private final StorageBackend<String, Map<String, String>> tags;
        private final AtomicInteger evaluations = new AtomicInteger();
        private final CloudFrontFunctionTesting testing;

        private Fixture(String mode) {
            functions = store(mode, "functions", FUNCTIONS);
            tags = store(mode, "tags", TAGS);
            functions.load();
            tags.load();
            AccountAwareStorageBackend<CloudFrontFunction> scopedFunctions =
                    new AccountAwareStorageBackend<>(functions, null, ACCOUNT);
            AccountAwareStorageBackend<Map<String, String>> scopedTags =
                    new AccountAwareStorageBackend<>(tags, null, ACCOUNT);
            StorageFactory factory = mock(StorageFactory.class);
            when(factory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                    invocation.getArgument(1, String.class).equals("cloudfront-functions.json") ? scopedFunctions : scopedTags);
            CloudFrontService service = mock(CloudFrontService.class);
            when(service.readFunctionState(any())).thenAnswer(invocation -> invocation.getArgument(0, Supplier.class).get());
            RegionResolver regions = mock(RegionResolver.class);
            when(regions.getPartition()).thenReturn("aws");
            testing = new CloudFrontFunctionTesting(factory, service, (capture, event) -> {
                CloudFrontFunctionSnapshot snapshot = capture.get();
                evaluations.incrementAndGet();
                return new CloudFrontFunctionRuntime.Result(snapshot,
                        new CloudFrontFunctionRuntime.Evaluation("{\"request\":{}}", null, List.of()));
            }, regions, new ObjectMapper());
        }

        private <V> StorageBackend<String, V> store(String mode, String name, TypeReference<Map<String, V>> type) {
            Path file = directory.resolve(name + ".json");
            return switch (mode) {
                case "persistent" -> new PersistentStorage<>(file, type);
                case "hybrid" -> new HybridStorage<>(file, type, 3600000);
                case "wal" -> new WalStorage<>(file, directory.resolve(name + ".wal"), type, 3600000);
                default -> new InMemoryStorage<>();
            };
        }

        private void flush() {
            functions.flush();
            tags.flush();
        }

        private void shutdown(StorageBackend<?, ?> store) {
            if (store instanceof HybridStorage<?, ?> hybrid) {
                hybrid.shutdown();
            } else if (store instanceof WalStorage<?, ?> wal) {
                wal.shutdown();
            }
        }

        @Override
        public void close() {
            shutdown(functions);
            shutdown(tags);
        }
    }
}
