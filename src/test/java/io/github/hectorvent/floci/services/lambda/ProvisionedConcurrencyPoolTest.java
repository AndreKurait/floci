package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaRuntimeLauncher;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ProvisionedConcurrencyPoolTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private final AccountAwareStorageBackend<LambdaFunction> functionStorage =
            AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final AccountAwareStorageBackend<LambdaAlias> aliasStorage =
            AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final AccountAwareStorageBackend<ProvisionedConcurrencyPool.Configuration> storage =
            AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final LambdaFunctionStore functions = new LambdaFunctionStore(functionStorage);
    private final LambdaAliasStore aliases = new LambdaAliasStore(aliasStorage);
    private final Launcher launcher = new Launcher();
    private ProvisionedConcurrencyPool pool;
    private LambdaFunction version;

    @BeforeEach
    void setUp() {
        version = function(ACCOUNT, REGION, "1");
        function(ACCOUNT, REGION, "$LATEST");
        alias(ACCOUNT, REGION, "live", "1", Map.of());
        pool = newPool();
    }

    @AfterEach
    void tearDown() {
        launcher.launchGate.complete(null);
        pool.shutdown();
    }

    private ProvisionedConcurrencyPool newPool() {
        return new ProvisionedConcurrencyPool(launcher, functions, aliases, storage, Clock.systemUTC(), 4, 8, false);
    }

    private LambdaFunction function(String account, String region, String number) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("example");
        fn.setAccountId(account);
        fn.setFunctionArn("arn:aws:lambda:" + region + ":" + account + ":function:example"
                + ("$LATEST".equals(number) ? "" : ":" + number));
        fn.setVersion(number);
        fn.setRevisionId(account + region + number);
        fn.setTimeout(3);
        functions.saveForAccount(account, region, fn);
        return fn;
    }

    private void alias(String account, String region, String name, String number, Map<String, Double> weights) {
        LambdaAlias alias = new LambdaAlias();
        alias.setName(name);
        alias.setFunctionName("example");
        alias.setFunctionVersion(number);
        alias.setAliasArn("arn:aws:lambda:" + region + ":" + account + ":function:example:" + name);
        alias.setRoutingConfig(weights);
        aliasStorage.putForAccount(account, "alias::" + region + "::example::" + name, alias);
    }

    private ProvisionedConcurrencyPool.Status put(int count) {
        return pool.put(REGION, "example", "live", count, ACCOUNT);
    }

    private ProvisionedConcurrencyPool.Status get() {
        return pool.get(REGION, "example", "live", ACCOUNT);
    }

    private Environment launched() throws Exception {
        Environment environment = launcher.launched.poll(5, TimeUnit.SECONDS);
        assertNotNull(environment);
        return environment;
    }

    private void ready() {
        await().atMost(Duration.ofSeconds(5)).until(() -> "READY".equals(get().status()));
    }

    @Test
    void initializationThenExactQualifierReuseDoesNotInvokeHandler() throws Exception {
        assertEquals("IN_PROGRESS", put(1).status());
        Environment first = launched();
        assertEquals("IN_PROGRESS", get().status());
        assertEquals(0, get().allocated());
        assertNull(pool.acquire(version, "live"));
        first.initialized.complete(null);
        ready();
        assertEquals(1, get().allocated());
        assertEquals(1, get().available());
        assertNull(pool.acquire(version, null));
        assertNull(pool.acquire(version, "1"));
        assertNull(pool.acquire(version, "other"));
        assertNull(pool.acquire(function("222222222222", REGION, "1"), "live"));
        assertNull(pool.acquire(function(ACCOUNT, "eu-west-1", "1"), "live"));
        assertSame(first.handle, pool.acquire(version, "live"));
        assertEquals(0, get().available());
        assertTrue(pool.release(first.handle, false));
        assertSame(first.handle, pool.acquire(version, "live"));
        assertTrue(pool.release(first.handle, false));
        verify(first.runtime, never()).enqueue(any());
        assertEquals(1, launcher.starts.get());
    }

    @Test
    void repeatedPutPreservesTheCurrentAllocation() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        String modified = get().lastModified();
        assertEquals("READY", put(1).status());
        assertEquals(modified, get().lastModified());
        assertEquals(1, launcher.starts.get());
    }

    @Test
    void deleteDuringInitializationStopsOnceAndNeverRestoresReady() throws Exception {
        put(1);
        Environment first = launched();
        pool.delete(REGION, "example", "live", ACCOUNT);
        await().atMost(Duration.ofSeconds(5)).until(() -> first.stops.get() == 1);
        first.initialized.complete(null);
        AwsException missing = assertThrows(AwsException.class, this::get);
        assertEquals("ProvisionedConcurrencyConfigNotFoundException", missing.getErrorCode());
        pool.reconcile();
        assertEquals(1, launcher.starts.get());
        assertEquals(1, first.stops.get());
    }

    @Test
    void deleteBeforeLaunchReturnsStopsTheLateHandle() throws Exception {
        launcher.launchGate = new CompletableFuture<>();
        put(1);
        Environment first = launched();
        pool.delete(REGION, "example", "live", ACCOUNT);
        launcher.launchGate.complete(null);
        await().atMost(Duration.ofSeconds(5)).until(() -> first.stops.get() == 1);
        assertThrows(AwsException.class, this::get);
    }

    @Test
    void failedStopRemainsOwnedAndDeleteReportsRetryableFailure() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        launcher.stopFailures.set(1);
        AwsException failure = assertThrows(AwsException.class,
                () -> pool.delete(REGION, "example", "live", ACCOUNT));
        assertEquals(500, failure.getHttpStatus());
        assertTrue(first.alive);
        pool.reconcileSafely();
        assertFalse(first.alive);
        assertEquals(2, first.stops.get());
        assertDoesNotThrow(() -> pool.delete(REGION, "example", "live", ACCOUNT));
    }

    @Test
    void initializationFailureIsFailedAndExplicitPutCanRetry() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.completeExceptionally(new IllegalStateException("controlled init failure"));
        await().atMost(Duration.ofSeconds(5)).until(() -> "FAILED".equals(get().status()));
        assertEquals(0, get().allocated());
        assertEquals(1, first.stops.get());
        put(1);
        Environment second = launched();
        second.initialized.complete(null);
        ready();
        assertEquals(2, launcher.starts.get());
    }

    @Test
    void failedEnvironmentIsReplacedAfterReady() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        first.alive = false;
        pool.reconcile();
        Environment replacement = launched();
        assertEquals("IN_PROGRESS", get().status());
        replacement.initialized.complete(null);
        ready();
        assertEquals(1, first.stops.get());
    }

    @Test
    void targetChangeRefusesOldInitializedEnvironment() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        function(ACCOUNT, REGION, "2");
        alias(ACCOUNT, REGION, "live", "2", Map.of());
        assertNull(pool.acquire(version, "live"));
        assertEquals("FAILED", get().status());
        assertEquals(1, first.stops.get());
        put(1);
        Environment second = launched();
        second.initialized.complete(null);
        ready();
    }

    @Test
    void restoredDesiredConfigurationMustInitializeNewProcesses() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        pool.shutdown();
        pool = newPool();
        pool.start();
        Environment second = launched();
        assertEquals("IN_PROGRESS", get().status());
        assertEquals(1, first.stops.get());
        second.initialized.complete(null);
        ready();
    }

    @Test
    void configurationValidationHasNoLaunchSideEffects() {
        assertThrows(AwsException.class, () -> put(0));
        assertThrows(AwsException.class, () -> put(5));
        assertThrows(AwsException.class, () -> pool.put(REGION, "example", null, 1, ACCOUNT));
        assertThrows(AwsException.class, () -> pool.put(REGION, "example", "$LATEST", 1, ACCOUNT));
        assertThrows(AwsException.class, () -> pool.put(REGION, "absent", "live", 1, ACCOUNT));
        assertThrows(AwsException.class, () -> pool.put(REGION, "example", "2", 1, ACCOUNT));
        alias(ACCOUNT, REGION, "weighted", "1", Map.of("2", 0.1));
        assertThrows(AwsException.class, () -> pool.put(REGION, "example", "weighted", 1, ACCOUNT));
        launcher.supported = false;
        assertThrows(AwsException.class, () -> put(1));
        assertEquals(0, launcher.starts.get());
    }

    @Test
    void reservationsAndMultipleQualifiersShareTheFunctionCeiling() throws Exception {
        LambdaFunction latest = functions.getForAccount(ACCOUNT, REGION, "example").orElseThrow();
        latest.setReservedConcurrentExecutions(1);
        assertThrows(AwsException.class, () -> put(2));
        put(1);
        launched();
        assertThrows(AwsException.class, () -> pool.put(REGION, "example", "1", 1, ACCOUNT));
        assertThrows(AwsException.class, () -> pool.validateReservation(latest, 0));
    }

    @Test
    void deletingOneAccountPreservesTheSameFunctionInAnotherAccount() throws Exception {
        function("222222222222", REGION, "$LATEST");
        function("222222222222", REGION, "1");
        alias("222222222222", REGION, "live", "1", Map.of());
        put(1);
        Environment first = launched();
        pool.put(REGION, "example", "live", 1, "222222222222");
        Environment second = launched();
        first.initialized.complete(null);
        second.initialized.complete(null);
        ready();
        pool.deleteFunction(version);
        assertEquals(1, first.stops.get());
        assertEquals(0, second.stops.get());
        await().atMost(Duration.ofSeconds(5)).until(() ->
                "READY".equals(pool.get(REGION, "example", "live", "222222222222").status()));
    }

    private static final class Environment {
        final RuntimeApiServer runtime = mock(RuntimeApiServer.class);
        final CompletableFuture<Void> initialized = new CompletableFuture<>();
        final AtomicInteger stops = new AtomicInteger();
        final ContainerHandle handle;
        volatile boolean alive = true;

        Environment(int id, LambdaFunction fn) throws Exception {
            handle = new ContainerHandle("environment-" + id, fn.getFunctionName(), runtime, ContainerState.WARM);
            doAnswer(call -> {
                initialized.get(5, TimeUnit.SECONDS);
                return null;
            }).when(runtime).awaitInitialization(anyLong());
            when(runtime.isInitialized()).thenAnswer(call ->
                    initialized.isDone() && !initialized.isCompletedExceptionally() && alive);
        }
    }

    private static final class Launcher implements LambdaRuntimeLauncher {
        final BlockingQueue<Environment> launched = new LinkedBlockingQueue<>();
        final Map<ContainerHandle, Environment> environments = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stopFailures = new AtomicInteger();
        volatile CompletableFuture<Void> launchGate = CompletableFuture.completedFuture(null);
        boolean supported = true;

        @Override
        public ContainerHandle launch(LambdaFunction fn) {
            throw new AssertionError("Provisioning must not invoke the on-demand launch path");
        }

        @Override
        public boolean supportsProvisionedConcurrency() {
            return supported;
        }

        @Override
        public ContainerHandle launchProvisioned(LambdaFunction fn) {
            try {
                Environment environment = new Environment(starts.incrementAndGet(), fn);
                environments.put(environment.handle, environment);
                launched.add(environment);
                launchGate.get(5, TimeUnit.SECONDS);
                return environment.handle;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public boolean isAlive(ContainerHandle handle) {
            return environments.get(handle).alive;
        }

        @Override
        public void stop(ContainerHandle handle) {
            Environment environment = environments.get(handle);
            environment.stops.incrementAndGet();
            if (stopFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("controlled first stop failure");
            }
            environment.alive = false;
            environment.initialized.completeExceptionally(new IllegalStateException("stopped"));
        }
    }
}
