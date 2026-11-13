package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.LambdaUrlConfig;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaRecursionConfigServiceTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String NAME = "recursion-settings";
    private static final String ARN = "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + NAME;
    @TempDir Path directory;

    @Test
    void defaultsValidationAndIndependentFunctions() {
        LambdaFunctionStore store = store(new InMemoryStorage<>());
        LambdaFunction fn = function(NAME);
        fn.setRevisionId("original-revision");
        fn.setLastModified(123456789L);
        store.saveForAccount(ACCOUNT, REGION, fn);
        store.saveForAccount(ACCOUNT, REGION, function("other"));
        LambdaService service = service(store);
        assertEquals("Terminate", service.getFunctionRecursionConfig(REGION, NAME));
        assertEquals("Allow", service.putFunctionRecursionConfig(REGION, NAME, "Allow"));
        assertEquals("Allow", service.getFunctionRecursionConfig(REGION, ARN));
        assertEquals("Terminate", service.getFunctionRecursionConfig(REGION, "other"));
        for (String invalid : new String[]{null, "", "allow", "Unknown", " Allow"}) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.putFunctionRecursionConfig(REGION, NAME, invalid));
            assertEquals("ValidationException", error.getErrorCode());
            assertEquals("Allow", service.getFunctionRecursionConfig(REGION, NAME));
        }
        assertEquals("original-revision", fn.getRevisionId());
        assertEquals(123456789L, fn.getLastModified());
        assertEquals("Terminate", service.putFunctionRecursionConfig(REGION, NAME, "Terminate"));
    }

    @Test
    void qualifiedOrMalformedSelectorsCannotChangeTheLatestFunction() {
        LambdaFunctionStore store = store(new InMemoryStorage<>());
        store.saveForAccount(ACCOUNT, REGION, function(NAME));
        LambdaService service = service(store);
        for (String invalid : List.of(NAME + ":1", NAME + ":$LATEST", NAME + ":live", ARN + ":1",
                "has.dot", "x".repeat(65), "arn:aws:lambda:::function:" + NAME)) {
            assertEquals("ValidationException", assertThrows(AwsException.class,
                    () -> service.putFunctionRecursionConfig(REGION, invalid, "Allow")).getErrorCode());
            assertEquals("ValidationException", assertThrows(AwsException.class,
                    () -> service.getFunctionRecursionConfig(REGION, invalid)).getErrorCode());
        }
        assertEquals("Terminate", service.getFunctionRecursionConfig(REGION, NAME));
        for (String selector : List.of(NAME, "function:" + NAME, ACCOUNT + ":function:" + NAME,
                REGION + ":" + ACCOUNT + ":function:" + NAME, ARN)) {
            assertEquals("Allow", service.putFunctionRecursionConfig(REGION, selector, "Allow"));
            assertEquals("Allow", service.getFunctionRecursionConfig(REGION, selector));
        }
    }

    @Test
    void explicitAccountRegionAndPartitionCannotEscapeCallerScope() {
        LambdaFunctionStore store = store(new InMemoryStorage<>());
        store.saveForAccount(ACCOUNT, REGION, function(NAME));
        LambdaFunction foreign = function(NAME);
        foreign.setAccountId("222222222222");
        foreign.setFunctionArn(ARN.replace(ACCOUNT, "222222222222"));
        store.saveForAccount("222222222222", REGION, foreign);
        store.saveForAccount(ACCOUNT, "eu-west-1", function(NAME));
        LambdaService service = service(store);
        for (String selector : List.of(foreign.getFunctionArn(), ARN.replace(REGION, "eu-west-1"),
                ARN.replace("arn:aws:", "arn:aws-cn:"), "222222222222:function:" + NAME)) {
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> service.putFunctionRecursionConfig(REGION, selector, "Allow")).getErrorCode());
        }
        assertEquals("Terminate", foreign.getRecursiveLoop());
        assertEquals("Terminate", store.getForAccount(ACCOUNT, "eu-west-1", NAME).orElseThrow().getRecursiveLoop());
        assertEquals("Terminate", service.getFunctionRecursionConfig(REGION, NAME));
    }

    @ParameterizedTest
    @MethodSource("io.github.hectorvent.floci.testing.PartitionMatrix#cases")
    void canonicalAndPartialSelectorsPreserveEverySupportedPartition(PartitionCase partition) {
        LambdaFunctionStore store = store(new InMemoryStorage<>());
        LambdaFunction fn = function(NAME);
        String arn = "arn:" + partition.partition() + ":lambda:" + partition.region() + ":" + ACCOUNT + ":function:" + NAME;
        fn.setFunctionArn(arn);
        store.saveForAccount(ACCOUNT, partition.region(), fn);
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(directory), new ZipExtractor(),
                new RegionResolver(partition.region(), ACCOUNT));
        for (String selector : List.of(NAME, ACCOUNT + ":function:" + NAME, arn)) {
            assertEquals("Allow", service.putFunctionRecursionConfig(partition.region(), selector, "Allow"));
            assertEquals("Allow", service.getFunctionRecursionConfig(partition.region(), selector));
        }
        String wrongPartition = arn.replace("arn:" + partition.partition() + ":", "arn:aws-wrong:");
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.putFunctionRecursionConfig(partition.region(), wrongPartition, "Terminate")).getErrorCode());
        assertEquals("Allow", fn.getRecursiveLoop());
    }

    @Test
    void preWriteFailureRestoresLiveSettingAndKeepsAttachedUrlRouting() {
        FailingStorage backend = new FailingStorage();
        LambdaFunctionStore store = store(backend);
        LambdaFunction fn = function(NAME);
        fn.setUrlConfig(url("old-id"));
        store.saveForAccount(ACCOUNT, REGION, fn);
        LambdaService service = service(store);
        backend.fail = true;
        assertThrows(IllegalStateException.class, () -> service.putFunctionRecursionConfig(REGION, NAME, "Allow"));
        assertEquals("Terminate", fn.getRecursiveLoop());
        assertEquals("Terminate", service.getFunctionRecursionConfig(REGION, NAME));
        assertSame(fn, store.getByUrlId("old-id").orElseThrow());
        backend.fail = false;
        LambdaFunction replacement = function(NAME);
        replacement.setUrlConfig(url("new-id"));
        store.saveForAccount(ACCOUNT, REGION, replacement);
        assertTrue(store.getByUrlId("old-id").isEmpty());
        assertSame(replacement, store.getByUrlId("new-id").orElseThrow());
        assertEquals("Allow", service.putFunctionRecursionConfig(REGION, NAME, "Allow"));
    }

    @Test
    void successfulSaveKeepsDevUrlReplacementDeindexingForTheLiveInstance() {
        LambdaFunctionStore store = store(new InMemoryStorage<>());
        LambdaFunction fn = function(NAME);
        fn.setUrlConfig(url("old-id"));
        store.saveForAccount(ACCOUNT, REGION, fn);
        fn.setUrlConfig(url("new-id"));
        assertEquals("Allow", service(store).putFunctionRecursionConfig(REGION, NAME, "Allow"));
        assertTrue(store.getByUrlId("old-id").isEmpty());
        assertSame(fn, store.getByUrlId("new-id").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void settingSurvivesActualBackendReloadAndDeletionDoesNotReturn(String mode) {
        StorageBackend<String, LambdaFunction> first = backend(mode);
        try {
            first.load();
            LambdaFunctionStore store = store(first);
            store.saveForAccount(ACCOUNT, REGION, function(NAME));
            service(store).putFunctionRecursionConfig(REGION, NAME, "Allow");
            first.flush();
        } finally {
            shutdown(first);
        }
        StorageBackend<String, LambdaFunction> second = backend(mode);
        try {
            second.load();
            LambdaFunctionStore reloaded = store(second);
            assertEquals("Allow", service(reloaded).getFunctionRecursionConfig(REGION, NAME));
            service(reloaded).deleteFunction(REGION, NAME);
            second.flush();
        } finally {
            shutdown(second);
        }
        StorageBackend<String, LambdaFunction> third = backend(mode);
        try {
            third.load();
            assertTrue(store(third).getForAccount(ACCOUNT, REGION, NAME).isEmpty());
        } finally {
            shutdown(third);
        }
    }

    @Test
    void legacyMissingAndNullSettingsDefaultToTerminate() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String json : List.of("{}", "{\"recursiveLoop\":null}")) {
            assertEquals("Terminate", mapper.readValue(json, LambdaFunction.class).getRecursiveLoop());
        }
    }

    @Test
    void waitingWriteCannotResurrectDeletedFunction() throws Exception {
        LambdaFunctionStore store = store(new InMemoryStorage<>());
        store.saveForAccount(ACCOUNT, REGION, function(NAME));
        LambdaService service = service(store);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> worker = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<?> result;
            synchronized (service.lockForConcurrencyOp(ARN)) {
                result = executor.submit(() -> {
                    worker.set(Thread.currentThread());
                    started.countDown();
                    service.putFunctionRecursionConfig(REGION, NAME, "Allow");
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                awaitBlockedOrDone(worker.get());
                service.deleteFunction(REGION, NAME);
            }
            ExecutionException error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertEquals("ResourceNotFoundException", assertInstanceOf(AwsException.class, error.getCause()).getErrorCode());
            assertFalse(store.getForAccount(ACCOUNT, REGION, NAME).isPresent());
            store.saveForAccount(ACCOUNT, REGION, function(NAME));
            assertEquals("Terminate", service.getFunctionRecursionConfig(REGION, NAME));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentReadWaitsForFailedWriteToRestoreTheLiveValue() throws Exception {
        BlockingStorage backend = new BlockingStorage();
        LambdaFunctionStore store = store(backend);
        store.saveForAccount(ACCOUNT, REGION, function(NAME));
        LambdaService service = service(store);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> reader = new AtomicReference<>();
        CountDownLatch readStarted = new CountDownLatch(1);
        backend.block = true;
        try {
            Future<?> write = executor.submit(() -> service.putFunctionRecursionConfig(REGION, NAME, "Allow"));
            assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
            Future<String> read = executor.submit(() -> {
                reader.set(Thread.currentThread());
                readStarted.countDown();
                return service.getFunctionRecursionConfig(REGION, NAME);
            });
            assertTrue(readStarted.await(5, TimeUnit.SECONDS));
            awaitBlockedOrDone(reader.get());
            assertFalse(read.isDone(), "a read must not expose the temporarily changed live object");
            backend.release.countDown();
            ExecutionException failed = assertThrows(ExecutionException.class, () -> write.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failed.getCause());
            assertEquals("Terminate", read.get(5, TimeUnit.SECONDS));
        } finally {
            backend.release.countDown();
            executor.shutdownNow();
        }
    }

    private LambdaService service(LambdaFunctionStore store) {
        return new LambdaService(store, new WarmPool(), new CodeStore(directory), new ZipExtractor(),
                new RegionResolver(REGION, ACCOUNT));
    }

    private static LambdaFunctionStore store(StorageBackend<String, LambdaFunction> backend) {
        return new LambdaFunctionStore(new AccountAwareStorageBackend<>(backend, null, ACCOUNT));
    }

    private static LambdaFunction function(String name) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName(name);
        fn.setAccountId(ACCOUNT);
        fn.setFunctionArn(ARN.replace(NAME, name));
        fn.setVersion("$LATEST");
        return fn;
    }

    private static LambdaUrlConfig url(String id) {
        LambdaUrlConfig config = new LambdaUrlConfig();
        config.setFunctionUrl("http://" + id + ".lambda-url.us-east-1.localhost/");
        return config;
    }

    private StorageBackend<String, LambdaFunction> backend(String mode) {
        TypeReference<Map<String, LambdaFunction>> type = new TypeReference<>() {};
        Path snapshot = directory.resolve("functions.json");
        return switch (mode) {
            case "persistent" -> new PersistentStorage<>(snapshot, type);
            case "hybrid" -> new HybridStorage<>(snapshot, type, 3600000);
            case "wal" -> new WalStorage<>(snapshot, directory.resolve("functions.wal"), type, 3600000);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static void shutdown(StorageBackend<String, LambdaFunction> backend) {
        if (backend instanceof HybridStorage<String, LambdaFunction> hybrid) {
            hybrid.shutdown();
        }
        if (backend instanceof WalStorage<String, LambdaFunction> wal) {
            wal.shutdown();
        }
    }

    private static final class BlockingStorage extends InMemoryStorage<String, LambdaFunction> {
        private boolean block;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void put(String key, LambdaFunction value) {
            if (block) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("fixture release timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                throw new IllegalStateException("injected pre-write failure");
            }
            super.put(key, value);
        }
    }

    private static final class FailingStorage extends InMemoryStorage<String, LambdaFunction> {
        private boolean fail;
        @Override
        public void put(String key, LambdaFunction value) {
            if (fail) {
                throw new IllegalStateException("injected pre-write failure");
            }
            super.put(key, value);
        }
    }
}
