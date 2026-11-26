package io.floci.aws.services.lambda;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.model.LambdaRuntimeManagementConfig;
import io.floci.aws.services.lambda.model.LambdaUrlConfig;
import io.floci.aws.services.lambda.zip.CodeStore;
import io.floci.aws.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.services.lambda.LambdaRuntimeManagementServiceTest.ACCOUNT;
import static io.floci.aws.services.lambda.LambdaRuntimeManagementServiceTest.PIN;
import static io.floci.aws.services.lambda.LambdaRuntimeManagementServiceTest.REGION;
import static io.floci.aws.services.lambda.LambdaRuntimeManagementServiceTest.function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LambdaRuntimeManagementStorageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, LambdaFunction>> FUNCTIONS = new TypeReference<>() {};
    private static final TypeReference<Map<String, LambdaRuntimeManagementConfig>> SETTINGS = new TypeReference<>() {};
    @TempDir
    Path directory;

    @Test
    void arnlessLegacyRowsKeepAccountIsolationAndBytesAcrossSaveAndRead() throws Exception {
        String otherAccount = "222222222222";
        String logical = "lambda::" + REGION + "::legacy-no-arn::$LATEST";
        InMemoryStorage<String, LambdaFunction> raw = new InMemoryStorage<>();
        InMemoryStorage<String, LambdaRuntimeManagementConfig> settings = new InMemoryStorage<>();
        LambdaFunctionStore functions = store(raw, settings);
        LambdaFunction one = function("legacy-no-arn", "$LATEST", ACCOUNT, REGION);
        LambdaFunction two = function("legacy-no-arn", "$LATEST", otherAccount, REGION);
        one.setFunctionArn(null);
        two.setFunctionArn(null);
        one.setDescription("default account");
        two.setDescription("other account");
        byte[] first = MAPPER.writeValueAsBytes(one);
        byte[] second = MAPPER.writeValueAsBytes(two);
        functions.save(REGION, one);
        functions.saveForAccount(otherAccount, REGION, two);
        assertSame(one, functions.getForAccount(ACCOUNT, REGION, "legacy-no-arn").orElseThrow());
        assertSame(two, functions.getForAccount(otherAccount, REGION, "legacy-no-arn").orElseThrow());
        assertEquals(Set.of(ACCOUNT + "/" + logical, otherAccount + "/" + logical), raw.keys());
        raw.put(logical, one);
        raw.delete(ACCOUNT + "/" + logical);
        Set<String> keys = raw.keys();
        assertTrue(service(functions).authorizationTags(REGION, ACCOUNT, "legacy-no-arn", null).isEmpty());
        assertTrue(service(functions).authorizationTags(REGION, otherAccount, "legacy-no-arn", null).isEmpty());
        assertThrows(AwsException.class, () -> service(functions).get(REGION, "legacy-no-arn", null));
        assertEquals(keys, raw.keys());
        assertArrayEquals(first, MAPPER.writeValueAsBytes(raw.get(logical).orElseThrow()));
        assertArrayEquals(second, MAPPER.writeValueAsBytes(raw.get(otherAccount + "/" + logical).orElseThrow()));
        assertNull(one.getRuntimeManagementGeneration());
        assertNull(two.getRuntimeManagementGeneration());
        assertTrue(settings.isEmpty());
    }

    @Test
    void ambientAndExplicitLegacySavesShareOnlyTheirActualAccountLock() {
        String otherAccount = "222222222222";
        InMemoryStorage<String, LambdaFunction> raw = new InMemoryStorage<>();
        List<Object> acquired = new ArrayList<>();
        LambdaFunctionStore functions = new LambdaFunctionStore(
                new AccountAwareStorageBackend<>(raw, null, ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT)) {
            @Override
            Object lockForConcurrencyOp(String key) {
                Object monitor = super.lockForConcurrencyOp(key);
                acquired.add(monitor);
                return monitor;
            }
        };
        LambdaFunction one = function("missing-owner-metadata", "$LATEST", ACCOUNT, REGION);
        LambdaFunction two = function("missing-owner-metadata", "$LATEST", otherAccount, REGION);
        one.setFunctionArn(null);
        one.setAccountId(null);
        two.setFunctionArn(null);
        two.setAccountId(null);
        functions.save(REGION, one);
        functions.saveForAccount(ACCOUNT, REGION, one);
        functions.saveForAccount(otherAccount, REGION, two);
        assertEquals(3, acquired.size());
        assertSame(acquired.get(0), acquired.get(1));
        assertNotSame(acquired.get(1), acquired.get(2));
        String logical = "/lambda::" + REGION + "::missing-owner-metadata::$LATEST";
        assertSame(one, raw.get(ACCOUNT + logical).orElseThrow());
        assertSame(two, raw.get(otherAccount + logical).orElseThrow());
        assertNull(one.getFunctionArn());
        assertNull(one.getAccountId());
        assertNull(one.getRuntimeManagementGeneration());
        assertNull(two.getRuntimeManagementGeneration());
    }

    @Test
    void ordinarySavesOfValidArnsKeepTheCanonicalFunctionLock() {
        AtomicReference<LambdaFunctionStore> owner = new AtomicReference<>();
        AtomicInteger writes = new AtomicInteger();
        InMemoryStorage<String, LambdaFunction> raw = new InMemoryStorage<>() {
            @Override
            public void put(String key, LambdaFunction value) {
                assertTrue(Thread.holdsLock(owner.get().lockForConcurrencyOp(value.getFunctionArn())));
                writes.incrementAndGet();
                super.put(key, value);
            }
        };
        LambdaFunctionStore functions = store(raw, new InMemoryStorage<>());
        owner.set(functions);
        LambdaFunction valid = function("canonical", "$LATEST", ACCOUNT, REGION);
        functions.create(REGION, valid);
        functions.save(REGION, valid);
        functions.saveForAccount(ACCOUNT, REGION, valid);
        assertEquals(2, writes.get());
    }

    @Test
    void settingsAndGenerationSurvivePersistentReloadAndDeletion() throws Exception {
        PersistentStorage<String, LambdaFunction> raw = new PersistentStorage<>(directory.resolve("functions.json"), FUNCTIONS);
        PersistentStorage<String, LambdaRuntimeManagementConfig> settings =
                new PersistentStorage<>(directory.resolve("settings.json"), SETTINGS);
        LambdaFunctionStore functions = store(raw, settings);
        functions.create(REGION, function("persisted", "$LATEST", ACCOUNT, REGION));
        put(functions, "persisted", "Manual");
        String generation = functions.get(REGION, "persisted").orElseThrow().getRuntimeManagementGeneration();
        raw = new PersistentStorage<>(directory.resolve("functions.json"), FUNCTIONS);
        settings = new PersistentStorage<>(directory.resolve("settings.json"), SETTINGS);
        raw.load();
        settings.load();
        functions = store(raw, settings);
        assertEquals(generation, functions.get(REGION, "persisted").orElseThrow().getRuntimeManagementGeneration());
        assertEquals(PIN, service(functions).get(REGION, "persisted", null).configuration().runtimeVersionArn());
        functions.delete(REGION, "persisted");
        assertTrue(settings.isEmpty());
        functions.create(REGION, function("persisted", "$LATEST", ACCOUNT, REGION));
        assertNotEquals(generation, functions.get(REGION, "persisted").orElseThrow().getRuntimeManagementGeneration());
        assertEquals("Auto", service(functions).get(REGION, "persisted", null).configuration().updateRuntimeOn());
    }

    @Test
    void settingsWriteRefusalPreservesMemoryAndPreviousDiskSnapshot() throws Exception {
        InMemoryStorage<String, LambdaFunction> raw = new InMemoryStorage<>();
        Path path = directory.resolve("settings.json");
        PersistentStorage<String, LambdaRuntimeManagementConfig> settings = new PersistentStorage<>(path, SETTINGS);
        LambdaFunctionStore functions = store(raw, settings);
        functions.create(REGION, function("failure", "$LATEST", ACCOUNT, REGION));
        put(functions, "failure", "Manual");
        byte[] saved = Files.readAllBytes(path);
        Path backup = obstruct(path);
        assertThrows(UncheckedIOException.class, () -> put(functions, "failure", "FunctionUpdate"));
        assertEquals(PIN, service(functions).get(REGION, "failure", null).configuration().runtimeVersionArn());
        assertArrayEquals(saved, Files.readAllBytes(backup));
    }

    @Test
    void legacyGenerationWriteRefusalPreservesUrlRoutingAndVisibleDefault() throws Exception {
        Path path = directory.resolve("functions.json");
        PersistentStorage<String, LambdaFunction> raw = new PersistentStorage<>(path, FUNCTIONS);
        LambdaFunction legacy = function("url", "$LATEST", ACCOUNT, REGION);
        LambdaUrlConfig url = new LambdaUrlConfig();
        url.setFunctionUrl("http://owned-url.lambda-url.us-east-1.localhost/");
        legacy.setUrlConfig(url);
        raw.putAllAtomically(Map.of(ACCOUNT + "/lambda::us-east-1::url::$LATEST", legacy));
        LambdaFunctionStore functions = store(raw, new InMemoryStorage<>());
        byte[] saved = Files.readAllBytes(path);
        Path backup = obstruct(path);
        assertThrows(UncheckedIOException.class, () -> put(functions, "url", "Manual"));
        assertSame(legacy, functions.getByUrlId("owned-url").orElseThrow());
        assertNull(legacy.getRuntimeManagementGeneration());
        assertEquals("Auto", service(functions).get(REGION, "url", null).configuration().updateRuntimeOn());
        assertArrayEquals(saved, Files.readAllBytes(backup));
    }

    @Test
    void failedSettingsCommitMayKeepInternalLegacyGenerationButNoPin() throws Exception {
        InMemoryStorage<String, LambdaFunction> raw = new InMemoryStorage<>();
        FailingSettings settings = new FailingSettings();
        LambdaFunction legacy = function("legacy", "$LATEST", ACCOUNT, REGION);
        raw.put("lambda::us-east-1::legacy::$LATEST", legacy);
        LambdaFunctionStore functions = store(raw, settings);
        settings.failPut = true;
        assertThrows(IllegalStateException.class, () -> put(functions, "legacy", "Manual"));
        assertNotNull(legacy.getRuntimeManagementGeneration());
        assertFalse(raw.keys().contains("lambda::us-east-1::legacy::$LATEST"));
        assertTrue(raw.keys().contains(ACCOUNT + "/lambda::us-east-1::legacy::$LATEST"));
        assertEquals("Auto", service(functions).get(REGION, "legacy", null).configuration().updateRuntimeOn());
        assertTrue(settings.isEmpty());
    }

    @Test
    void settingsDeleteFailurePreservesParentAndPin() throws Exception {
        FailingSettings settings = new FailingSettings();
        LambdaFunctionStore functions = store(new InMemoryStorage<>(), settings);
        functions.create(REGION, function("delete", "$LATEST", ACCOUNT, REGION));
        put(functions, "delete", "Manual");
        settings.failDelete = true;
        assertThrows(IllegalStateException.class, () -> functions.delete(REGION, "delete"));
        assertEquals(PIN, service(functions).get(REGION, "delete", null).configuration().runtimeVersionArn());
    }

    @Test
    void parentDeleteFailureLeavesOnlyDefaultSettingsAndCannotResurrectAPin() throws Exception {
        InMemoryStorage<String, LambdaFunction> raw = new InMemoryStorage<>() {
            @Override
            public void delete(String key) {
                throw new IllegalStateException("injected parent delete failure");
            }
        };
        InMemoryStorage<String, LambdaRuntimeManagementConfig> settings = new InMemoryStorage<>();
        LambdaFunctionStore functions = store(raw, settings);
        functions.create(REGION, function("delete", "$LATEST", ACCOUNT, REGION));
        put(functions, "delete", "Manual");
        assertThrows(IllegalStateException.class, () -> functions.delete(REGION, "delete"));
        assertEquals("Auto", service(functions).get(REGION, "delete", null).configuration().updateRuntimeOn());
        assertTrue(settings.isEmpty());
    }

    @Test
    void staleWriterCannotRestoreTheOldGenerationAfterDeleteAndRecreate() throws Exception {
        InMemoryStorage<String, LambdaRuntimeManagementConfig> settings = new InMemoryStorage<>();
        LambdaFunctionStore functions = store(new InMemoryStorage<>(), settings);
        LambdaFunction old = function("same", "$LATEST", ACCOUNT, REGION);
        functions.create(REGION, old);
        put(functions, "same", "Manual");
        Map<String, LambdaRuntimeManagementConfig> stale = Map.of(settings.keys().iterator().next(),
                settings.scan(key -> true).getFirst());
        Object retainedLock = functions.lockForConcurrencyOp(old.getFunctionArn());
        functions.delete(REGION, "same");
        assertThrows(AwsException.class, () -> functions.save(REGION, old));
        assertThrows(AwsException.class, () -> functions.saveForAccount(ACCOUNT, REGION, old));
        LambdaFunction fresh = function("same", "$LATEST", ACCOUNT, REGION);
        functions.create(REGION, fresh);
        settings.putAll(stale);
        old.setDescription("stale queued writer");
        assertThrows(AwsException.class, () -> functions.save(REGION, old));
        assertThrows(AwsException.class, () -> functions.saveForAccount(ACCOUNT, REGION, old));
        assertSame(fresh, functions.get(REGION, "same").orElseThrow());
        assertSame(retainedLock, functions.lockForConcurrencyOp(fresh.getFunctionArn()));
        assertEquals("Auto", service(functions).get(REGION, "same", null).configuration().updateRuntimeOn());
    }

    @Test
    void publishedVersionsDefaultIndependentlyAndDeletingOneCannotRestoreSettings() throws Exception {
        LambdaFunctionStore functions = store(new InMemoryStorage<>(), new InMemoryStorage<>());
        LambdaService lambda = lambda(functions);
        LambdaFunction latest = function("published", "$LATEST", ACCOUNT, REGION);
        latest.setRevisionId("first");
        functions.create(REGION, latest);
        put(functions, "published", "Manual");
        LambdaFunction version = lambda.publishVersion(REGION, "published", null);
        assertEquals("Auto", service(functions).get(REGION, "published", version.getVersion())
                .configuration().updateRuntimeOn());
        service(functions).put(REGION, "published", version.getVersion(),
                MAPPER.readTree("{\"UpdateRuntimeOn\":\"FunctionUpdate\"}"));
        lambda.deleteVersion(REGION, "published", version.getVersion());
        assertThrows(AwsException.class, () -> service(functions).get(REGION, "published", version.getVersion()));
        functions.save(REGION, version);
        assertEquals("Auto", service(functions).get(REGION, "published", version.getVersion())
                .configuration().updateRuntimeOn());
        assertEquals(PIN, service(functions).get(REGION, "published", null).configuration().runtimeVersionArn());
    }

    @Test
    void settingsAndOrdinaryConfigurationWritersPreserveEachOthersFields() throws Exception {
        LambdaFunctionStore functions = store(new InMemoryStorage<>(), new InMemoryStorage<>());
        LambdaFunction latest = function("concurrent", "$LATEST", ACCOUNT, REGION);
        latest.setDescription("before");
        latest.setRuntime("nodejs20.x");
        latest.setHandler("index.handler");
        functions.create(REGION, latest);
        LambdaService lambda = lambda(functions);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> settings = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 25; i++) {
                    put(functions, "concurrent", "Manual");
                }
                return null;
            });
            Future<?> configuration = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 25; i++) {
                    lambda.updateFunctionConfiguration(REGION, "concurrent", Map.of("Description", "changed-" + i));
                }
                return null;
            });
            start.countDown();
            settings.get(30, TimeUnit.SECONDS);
            configuration.get(30, TimeUnit.SECONDS);
        }
        assertSame(latest, functions.get(REGION, "concurrent").orElseThrow());
        assertEquals("changed-24", latest.getDescription());
        assertEquals(PIN, service(functions).get(REGION, "concurrent", null).configuration().runtimeVersionArn());
    }

    private LambdaService lambda(LambdaFunctionStore functions) {
        return new LambdaService(functions, mock(WarmPool.class), new CodeStore(directory.resolve("code")),
                new ZipExtractor(), new RegionResolver(REGION, ACCOUNT));
    }

    private static LambdaFunctionStore store(StorageBackend<String, LambdaFunction> functions,
                                             StorageBackend<String, LambdaRuntimeManagementConfig> settings) {
        return new LambdaFunctionStore(new AccountAwareStorageBackend<>(functions, null, ACCOUNT),
                new AccountAwareStorageBackend<>(settings, null, ACCOUNT));
    }

    private static LambdaRuntimeManagementService service(LambdaFunctionStore functions) {
        return new LambdaRuntimeManagementService(functions, new LambdaAliasStore(new InMemoryStorage<>()),
                new RegionResolver(REGION, ACCOUNT));
    }

    private static void put(LambdaFunctionStore functions, String name, String mode) throws Exception {
        service(functions).put(REGION, name, null, MAPPER.readTree(MAPPER.writeValueAsString(
                Map.of("UpdateRuntimeOn", mode, "RuntimeVersionArn", PIN))));
    }

    private static Path obstruct(Path path) throws Exception {
        Path backup = path.resolveSibling(path.getFileName() + ".preserved");
        Files.move(path, backup);
        Files.createDirectory(path);
        Files.writeString(path.resolve("blocker"), "owned test obstruction");
        return backup;
    }

    private static final class FailingSettings extends InMemoryStorage<String, LambdaRuntimeManagementConfig> {
        boolean failPut;
        boolean failDelete;

        @Override
        public void putAllAtomically(Map<String, LambdaRuntimeManagementConfig> entries) {
            if (failPut) {
                throw new IllegalStateException("injected setting write failure");
            }
            super.putAllAtomically(entries);
        }

        @Override
        public void deleteAllAtomically(Set<String> keys) {
            if (failDelete) {
                throw new IllegalStateException("injected setting delete failure");
            }
            super.deleteAllAtomically(keys);
        }
    }
}
