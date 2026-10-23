package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProvisionedConcurrencyPoolTest {
    @TempDir
    Path storagePath;

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
    private ProvisionedConcurrencyPool pool;
    private LambdaFunction version;

    @BeforeEach
    void setUp() {
        version = function(ACCOUNT, REGION, "1");
        function(ACCOUNT, REGION, "$LATEST");
        alias(ACCOUNT, REGION, "live", "1", Map.of());
        pool = newPool();
    }

    private ProvisionedConcurrencyPool newPool() {
        return new ProvisionedConcurrencyPool(functions, aliases, storage, Clock.systemUTC(), 4, 8);
    }

    private LambdaFunction function(String account, String region, String number) {
        LambdaFunction fn = LambdaFunctionTestFixtures.function(account, region, "example", number);
        fn.setTimeout(3);
        functions.saveForAccount(account, region, fn);
        return fn;
    }

    private void alias(String account, String region, String name, String number, Map<String, Double> weights) {
        LambdaAlias alias = LambdaFunctionTestFixtures.alias(account, region, "example", name, number);
        alias.setRoutingConfig(weights);
        aliasStorage.putForAccount(account, "alias::" + region + "::example::" + name, alias);
    }

    private ProvisionedConcurrencyPool.Status put(int count) {
        return pool.put(REGION, "example", "live", count, ACCOUNT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"example", "absent"})
    void unpublishedVersionErrorsDependOnTheOperation(String name) {
        AwsException put = assertThrows(AwsException.class,
                () -> pool.put(REGION, name, "$LATEST", 1, ACCOUNT));
        assertEquals("InvalidParameterValueException", put.getErrorCode());
        assertEquals("Provisioned Concurrency Configs cannot be applied to unpublished function versions.",
                put.getMessage());
        for (Runnable operation : List.<Runnable>of(
                () -> pool.get(REGION, name, "$LATEST", ACCOUNT),
                () -> pool.delete(REGION, name, "$LATEST", ACCOUNT))) {
            AwsException error = assertThrows(AwsException.class, operation::run);
            assertEquals("InvalidParameterValueException", error.getErrorCode());
            assertEquals("The function resource provided must be an alias or a published version.",
                    error.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void invalidCountUsesAwsValidationBeforeAllocating(int count) {
        AwsException error = assertThrows(AwsException.class, () -> put(count));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals("1 validation error detected: Value '" + count
                + "' at 'provisionedConcurrentExecutions' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 1", error.getMessage());
        assertTrue(storage.scanAllAccounts().isEmpty());
    }

    @Test
    void missingConfigurationUsesAwsMessageAndDeleteIsIdempotent() {
        AwsException error = assertThrows(AwsException.class, this::get);
        assertEquals("ProvisionedConcurrencyConfigNotFoundException", error.getErrorCode());
        assertEquals("No Provisioned Concurrency Config found for this function", error.getMessage());
        assertDoesNotThrow(() -> pool.delete(REGION, "example", "live", ACCOUNT));
        assertTrue(storage.scanAllAccounts().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "example, missing, Cannot find alias arn: arn:aws:lambda:us-east-1:111111111111:function:example:missing",
            "example, 99, Function not found: arn:aws:lambda:us-east-1:111111111111:function:example:99",
            "absent, missing, Cannot find alias arn: arn:aws:lambda:us-east-1:111111111111:function:absent:missing",
            "absent, 99, Function not found: arn:aws:lambda:us-east-1:111111111111:function:absent:99"
    })
    void missingTargetsUseTheQualifiedAwsMessageForEveryOperation(String name, String qualifier, String message) {
        for (String reference : List.of(name, ACCOUNT + ":function:" + name,
                "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + name,
                "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + name + ":" + qualifier)) {
            for (Runnable operation : List.<Runnable>of(
                    () -> pool.get(REGION, reference, qualifier, ACCOUNT),
                    () -> pool.put(REGION, reference, qualifier, 1, ACCOUNT),
                    () -> pool.delete(REGION, reference, qualifier, ACCOUNT))) {
                AwsException error = assertThrows(AwsException.class, operation::run);
                assertEquals("ResourceNotFoundException", error.getErrorCode());
                assertEquals(404, error.getHttpStatus());
                assertEquals(message, error.getMessage());
            }
        }
        assertTrue(storage.scanAllAccounts().isEmpty());
    }

    @Test
    void missingTargetMessageUsesTheNamedAccountAndRegionalPartition() {
        AwsException error = assertThrows(AwsException.class,
                () -> pool.get("cn-north-1", "222222222222:function:absent", "live", ACCOUNT));
        assertEquals("Cannot find alias arn: arn:aws-cn:lambda:cn-north-1:222222222222:function:absent:live",
                error.getMessage());
        assertTrue(storage.scanAllAccounts().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "peer"})
    void provisionedAliasRefusesAnotherQualifierOfTheSameVersion(String qualifier) {
        alias(ACCOUNT, REGION, "peer", "1", Map.of());
        put(1);
        AwsException error = assertThrows(AwsException.class,
                () -> pool.put(REGION, "example", qualifier, 1, ACCOUNT));
        assertEquals("ResourceConflictException", error.getErrorCode());
        assertEquals(qualifier.equals("1") ? "Version is pointed by a Provisioned Concurrency alias"
                : "Alias can't be used for Provisioned Concurrency configuration on an already Provisioned version",
                error.getMessage());
        assertEquals(1, storage.scanAllAccounts().size());
    }

    @Test
    void provisionedVersionRefusesAliasButAllowsADifferentVersion() {
        pool.put(REGION, "example", "1", 1, ACCOUNT);
        AwsException error = assertThrows(AwsException.class, () -> put(1));
        assertEquals("ResourceConflictException", error.getErrorCode());
        assertEquals("Alias can't be used for Provisioned Concurrency configuration on an already Provisioned version",
                error.getMessage());
        function(ACCOUNT, REGION, "2");
        pool.put(REGION, "example", "2", 1, ACCOUNT);
        assertEquals(2, storage.scanAllAccounts().size());
    }

    @Test
    void lastModifiedUsesLambdasUtcOffsetForm() {
        pool = new ProvisionedConcurrencyPool(functions, aliases, storage,
                Clock.fixed(Instant.parse("2026-10-20T20:11:30.987Z"), ZoneOffset.UTC), 4, 8);
        assertEquals("2026-10-20T20:11:30+0000", put(1).lastModified());
    }

    private ProvisionedConcurrencyPool.Status get() {
        return pool.get(REGION, "example", "live", ACCOUNT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void storedConfigurationSurvivesFactoryRestartAndDeletedConfigurationStaysDeleted(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services().lambda().executor()).thenReturn("docker");
        when(config.storage().persistentPath()).thenReturn(storagePath.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60_000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("lambda")).thenReturn(mode);
        when(access.storageFlushInterval("lambda")).thenReturn(60_000L);
        for (int restart = 0; restart < 3; restart++) {
            StorageFactory factory = new StorageFactory(config, access);
            try {
                pool = new ProvisionedConcurrencyPool(functions, aliases, factory, config, Clock.systemUTC());
                pool.start();
                if (restart == 0) {
                    put(2);
                } else if (restart == 1) {
                    assertEquals(2, get().requested());
                    assertEquals("IN_PROGRESS", get().status());
                    assertEquals(0, get().allocated());
                    pool.delete(REGION, "example", "live", ACCOUNT);
                } else {
                    assertThrows(AwsException.class, this::get);
                }
            } finally {
                factory.shutdownAll();
            }
        }
    }

    @Test
    void unsupportedExecutorRejectsPutWithoutPersistingButExistingConfigurationCanBeRemoved() {
        put(1);
        pool = new ProvisionedConcurrencyPool(functions, aliases, storage, Clock.systemUTC(), 4, 8, false);
        pool.start();
        assertEquals(1, get().requested());
        AwsException failure = assertThrows(AwsException.class, () -> put(2));
        assertEquals("Provisioned concurrency requires the non-ephemeral Docker Lambda executor", failure.getMessage());
        assertEquals(1, get().requested());
        pool.delete(REGION, "example", "live", ACCOUNT);
        assertTrue(storage.scanAllAccounts().isEmpty());
    }

    @Test
    void replacementAndRestoreKeepDesiredCapacityWithoutClaimingReadiness() {
        ProvisionedConcurrencyPool.Status initial = put(1);
        assertEquals(initial, put(1));
        assertEquals(2, put(2).requested());
        pool = newPool();
        pool.start();
        ProvisionedConcurrencyPool.Status restored = get();
        assertEquals(2, restored.requested());
        assertEquals(0, restored.allocated());
        assertEquals(0, restored.available());
        assertEquals("IN_PROGRESS", restored.status());
        pool.delete(REGION, "example", "live", ACCOUNT);
        assertTrue(storage.scanAllAccounts().isEmpty());
        assertThrows(AwsException.class, this::get);
    }

    @Test
    void reservationsAndQualifiersShareTheFunctionCeiling() {
        LambdaFunction latest = functions.getForAccount(ACCOUNT, REGION, "example").orElseThrow();
        latest.setReservedConcurrentExecutions(2);
        assertThrows(AwsException.class, () -> put(3));
        put(1);
        function(ACCOUNT, REGION, "2");
        pool.put(REGION, "example", "2", 1, ACCOUNT);
        assertThrows(AwsException.class, () -> pool.validateReservation(latest, 1));
        pool.delete(REGION, "example", "2", ACCOUNT);
        assertDoesNotThrow(() -> pool.validateReservation(latest, 1));
        assertEquals(1, get().requested());
    }

    @Test
    void quotaErrorPrecedesVersionConflictAndRejectedReplacementPreservesTheRequest() {
        put(4);
        AwsException failure = assertThrows(AwsException.class,
                () -> pool.put(REGION, "example", "1", 1, ACCOUNT));
        assertEquals("InvalidParameterValueException", failure.getErrorCode());
        assertThrows(AwsException.class, () -> put(5));
        assertEquals(4, get().requested());
    }

    @Test
    void deletionAndResetRespectAccountAndRegionOwnership() {
        put(1);
        for (String account : List.of(ACCOUNT, "222222222222")) {
            function(account, "eu-west-1", "$LATEST");
            function(account, "eu-west-1", "1");
            alias(account, "eu-west-1", "live", "1", Map.of());
            pool.put("eu-west-1", "example", "live", 1, account);
        }
        pool.deleteFunction(version);
        assertThrows(AwsException.class, this::get);
        assertEquals(2, storage.scanAllAccounts().size());
        for (String account : List.of(ACCOUNT, "222222222222")) {
            assertEquals(1, pool.get("eu-west-1", "example", "live", account).requested());
        }
        pool.clear();
        assertTrue(storage.scanAllAccounts().isEmpty());
        pool.start();
        assertThrows(AwsException.class, () -> pool.get("eu-west-1", "example", "live", ACCOUNT));
    }

    @Test
    void weightedAliasLookupAndDeletionDoNotReapplyPutEligibility() {
        put(1);
        alias(ACCOUNT, REGION, "live", "1", Map.of("2", 0.1));
        assertEquals(1, get().requested());
        assertThrows(AwsException.class, () -> put(2));
        pool.delete(REGION, "example", "live", ACCOUNT);
        assertTrue(storage.scanAllAccounts().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredAliasCannotRetargetToCapacityOwnedByAnotherQualifier(boolean otherAlias) {
        function(ACCOUNT, REGION, "2");
        alias(ACCOUNT, REGION, "peer", "2", Map.of());
        put(1);
        String destination = otherAlias ? "peer" : "2";
        pool.put(REGION, "example", destination, 1, ACCOUNT);
        AwsException conflict = assertThrows(AwsException.class,
                () -> pool.validateAliasUpdate(version, "live", "2"));
        assertEquals("ResourceConflictException", conflict.getErrorCode());
        assertEquals(409, conflict.getHttpStatus());
        assertEquals("Alias can't be used for Provisioned Concurrency configuration on an already Provisioned version",
                conflict.getMessage());
        assertEquals(1, get().requested());
        assertEquals(2, storage.scanAllAccounts().size());
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "live", null));
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "live", "1"));
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "unconfigured", "2"));
        pool.delete(REGION, "example", destination, ACCOUNT);
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "live", "2"));
    }

    @Test
    void aliasCannotMoveToLatestWhileItHasRequestedCapacity() {
        put(1);
        assertThrows(AwsException.class, () -> pool.validateAliasUpdate(version, "live", "$LATEST"));
        pool.deleteQualifier(version, "live");
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "live", "$LATEST"));
    }

    @Test
    void failedStorageMutationPreservesTheLastVisibleConfigurationForRetry() {
        AccountAwareStorageBackend<ProvisionedConcurrencyPool.Configuration> backend = spy(storage);
        pool = new ProvisionedConcurrencyPool(functions, aliases, backend, Clock.systemUTC(), 4, 8);
        put(1);
        IllegalStateException outage = new IllegalStateException("storage unavailable");
        doThrow(outage).when(backend)
                .putForAccount(any(), any(), any());
        assertSame(outage, assertThrows(IllegalStateException.class, () -> put(2)));
        assertEquals(1, get().requested());
        doThrow(outage).when(backend)
                .deleteForAccount(any(), any());
        assertSame(outage, assertThrows(IllegalStateException.class,
                () -> pool.delete(REGION, "example", "live", ACCOUNT)));
        assertEquals(1, get().requested());
        doCallRealMethod().when(backend)
                .deleteForAccount(any(), any());
        pool.delete(REGION, "example", "live", ACCOUNT);
        assertThrows(AwsException.class, this::get);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void putRechecksDeletionAndReservationAfterAcquiringTheSharedLock(boolean deleted) throws Exception {
        AtomicReference<Thread> requester = new AtomicReference<>();
        try (ExecutorService tasks = Executors.newSingleThreadExecutor()) {
            Future<?> put;
            synchronized (functions.lockForConcurrencyOp(functions.getForAccount(ACCOUNT, REGION, "example").orElseThrow().getFunctionArn())) {
                put = tasks.submit(() -> {
                    requester.set(Thread.currentThread());
                    assertThrows(AwsException.class, () -> put(1));
                });
                awaitBlockedOrDone(requester, put);
                assertFalse(put.isDone());
                if (deleted) {
                    functions.delete(REGION, "example");
                } else {
                    functions.getForAccount(ACCOUNT, REGION, "example").orElseThrow()
                            .setReservedConcurrentExecutions(0);
                }
            }
            put.get(5, TimeUnit.SECONDS);
        }
        assertTrue(storage.scanAllAccounts().isEmpty());
    }
}
