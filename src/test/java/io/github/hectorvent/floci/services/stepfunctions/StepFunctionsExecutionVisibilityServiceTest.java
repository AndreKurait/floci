package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.ExecutionConfiguration;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.RoutingConfiguration;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachineVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/** Public visibility must follow the actual stored machine incarnation. */
class StepFunctionsExecutionVisibilityServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String MACHINE = "arn:aws:states:us-east-1:000000000000:stateMachine:visibility";
    private static final String ROLE = "arn:aws:iam::000000000000:role/execution";
    private static final String DEFINITION =
            "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"End\":true}}}";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void foreignReaderCannotMigrateLegacyExecutionOrMachine(boolean configurationReader) {
        String caller = "111111111111";
        InMemoryStorage<String, Execution> executions = new InMemoryStorage<>();
        InMemoryStorage<String, StateMachine> machines = new InMemoryStorage<>();
        Fixture fixture = legacyFixture(caller, executions, machines);
        String arn = "arn:aws:states:us-east-1:000000000000:execution:visibility:legacy";
        AwsException failure = assertThrows(AwsException.class, () -> {
            if (configurationReader) {
                fixture.service.describeStateMachineForExecution(arn);
            } else {
                fixture.service.describeExecutionForApi(arn);
            }
        });
        assertAll(
                () -> assertEquals("ExecutionDoesNotExist", failure.getErrorCode()),
                () -> assertTrue(executions.get(arn).isPresent()),
                () -> assertTrue(machines.get(MACHINE).isPresent()),
                () -> assertTrue(executions.get(caller + "/" + arn).isEmpty()),
                () -> assertTrue(machines.get(caller + "/" + MACHINE).isEmpty()));
    }

    @Test
    void owningReaderCanStillMigrateLegacyExecutionAndMachine() {
        InMemoryStorage<String, Execution> executions = new InMemoryStorage<>();
        InMemoryStorage<String, StateMachine> machines = new InMemoryStorage<>();
        Fixture fixture = legacyFixture(ACCOUNT, executions, machines);
        String arn = "arn:aws:states:us-east-1:000000000000:execution:visibility:legacy";
        assertEquals(arn, fixture.service.describeExecutionForApi(arn).getExecutionArn());
        AwsException failure = assertThrows(AwsException.class, () ->
                fixture.service.describeStateMachineForExecution(arn));
        assertAll(
                () -> assertEquals("UnsupportedOperation", failure.getErrorCode()),
                () -> assertTrue(executions.get(arn).isEmpty()),
                () -> assertTrue(machines.get(MACHINE).isEmpty()),
                () -> assertTrue(executions.get(ACCOUNT + "/" + arn).isPresent()),
                () -> assertTrue(machines.get(ACCOUNT + "/" + MACHINE).isPresent()));
    }

    private static Fixture legacyFixture(String caller, InMemoryStorage<String, Execution> executions,
                                         InMemoryStorage<String, StateMachine> machines) {
        StateMachine machine = new StateMachine();
        machine.setStateMachineArn(MACHINE);
        machine.setType("STANDARD");
        machines.put(MACHINE, machine);
        Execution execution = new Execution();
        execution.setExecutionArn("arn:aws:states:us-east-1:000000000000:execution:visibility:legacy");
        execution.setStateMachineArn(MACHINE);
        execution.setStatus("SUCCEEDED");
        executions.put(execution.getExecutionArn(), execution);
        TestStorage storage = new TestStorage(null);
        storage.stores.put("sfn-state-machines.json", new AccountAwareStorageBackend<>(machines, null, caller));
        storage.stores.put("sfn-executions.json", new AccountAwareStorageBackend<>(executions, null, caller));
        return new Fixture(storage, caller);
    }

    @Test
    void sameNameAndCreationTimeCannotMakeAnOldExecutionVisible() {
        Fixture fixture = new Fixture(new TestStorage(null));
        StateMachine original = fixture.create();
        fixture.storage.machines().get(MACHINE).orElseThrow().setCreationDate(1700000000.125);
        Execution oldExecution = fixture.start("old");
        fixture.service.deleteStateMachine(MACHINE);
        fixture.assertHidden(oldExecution);
        StateMachine replacement = fixture.create();
        fixture.storage.machines().get(MACHINE).orElseThrow().setCreationDate(1700000000.125);
        assertNotNull(original.getIncarnationId());
        assertNotEquals(original.getIncarnationId(), replacement.getIncarnationId());
        assertEquals(1700000000.125, fixture.service.describeStateMachine(MACHINE).getCreationDate());
        fixture.assertHidden(oldExecution);
        Execution fresh = fixture.start("fresh");
        assertEquals(replacement.getIncarnationId(), fresh.getStateMachineIncarnationId());
        assertSame(fresh, fixture.service.describeExecutionForApi(fresh.getExecutionArn()));
        assertEquals(DEFINITION, fixture.service.describeStateMachineForExecution(fresh.getExecutionArn()).definition());
        assertSame(oldExecution, fixture.service.describeExecution(oldExecution.getExecutionArn()),
                "The internal retained-row reader is unchanged.");
    }

    @Test
    void updatesTagsIdempotentCreateAndVersionAliasViewsPreserveTheOwner() {
        Fixture fixture = new Fixture(new TestStorage(null));
        StateMachine created = fixture.create();
        String incarnation = created.getIncarnationId();
        StateMachine repeated = fixture.service.createStateMachine(
                "visibility", DEFINITION, ROLE, "STANDARD", REGION, Map.of(),
                null, null, null, false, null).stateMachine();
        assertEquals(incarnation, repeated.getIncarnationId());
        fixture.service.updateStateMachine(MACHINE, new StepFunctionsService.UpdateStateMachineRequest(
                DEFINITION, ROLE + "-updated", null, false, null, false, null, false, false, null));
        fixture.service.tagResource(MACHINE, Map.of("owner", "test"));
        fixture.service.untagResource(MACHINE, List.of("owner"));
        fixture.service.replaceStateMachineTags(MACHINE, Map.of("replacement", "test"));
        StateMachineVersion version = fixture.service.publishStateMachineVersion(MACHINE);
        String alias = fixture.service.createStateMachineAlias("LIVE", null,
                List.of(new RoutingConfiguration(version.getStateMachineVersionArn(), 100)))
                .getStateMachineAliasArn();
        assertEquals(incarnation, fixture.service.describeStateMachine(MACHINE).getIncarnationId());
        assertEquals(incarnation, fixture.service.describeStateMachine(version.getStateMachineVersionArn())
                .getIncarnationId());
        for (String selected : List.of(MACHINE, version.getStateMachineVersionArn(), alias)) {
            Execution execution = fixture.service.startExecution(selected, "view-" + fixture.callbacks.size(), "{}", REGION);
            assertEquals(incarnation, execution.getStateMachineIncarnationId());
            assertEquals(MACHINE, fixture.service.describeStateMachineForExecution(execution.getExecutionArn())
                    .stateMachineArn());
        }
    }

    @Test
    void delayedOldStartRetainsTheResolvedIdentityAfterReplacement() throws Exception {
        TestStorage storage = new TestStorage(null);
        AccountAwareStorageBackend<StateMachine> ordinary = storage.machines();
        AccountAwareStorageBackend<StateMachine> delayed = spy(ordinary);
        storage.stores.put("sfn-state-machines.json", delayed);
        Fixture fixture = new Fixture(storage);
        StateMachine original = fixture.create();
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Optional<StateMachine> observed = ordinary.get(MACHINE);
            if (first.compareAndSet(true, false)) {
                captured.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return observed;
        }).when(delayed).get(MACHINE);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Execution> pending = executor.submit(() -> fixture.start("late-start"));
            assertTrue(captured.await(10, TimeUnit.SECONDS));
            fixture.service.deleteStateMachine(MACHINE);
            StateMachine replacement = fixture.create();
            release.countDown();
            Execution oldExecution = pending.get(10, TimeUnit.SECONDS);
            assertNotEquals(original.getIncarnationId(), replacement.getIncarnationId());
            assertEquals(original.getIncarnationId(), oldExecution.getStateMachineIncarnationId());
            assertEquals(original.getIncarnationId(), storage.executions().get(oldExecution.getExecutionArn())
                    .orElseThrow().getStateMachineIncarnationId());
            fixture.assertHidden(oldExecution);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void oldCheckpointAndCompletionWritesCannotAdoptTheReplacement() {
        Fixture fixture = new Fixture(new TestStorage(null));
        fixture.create();
        Execution oldExecution = fixture.start("late-writes");
        String oldIdentity = oldExecution.getStateMachineIncarnationId();
        fixture.service.deleteStateMachine(MACHINE);
        StateMachine replacement = fixture.create();
        fixture.storage.executions().delete(oldExecution.getExecutionArn());
        assertTrue(fixture.storage.executions().get(oldExecution.getExecutionArn()).isEmpty());
        while (oldExecution.getHistory().size() < 100) {
            HistoryEvent event = new HistoryEvent();
            event.setId((long) oldExecution.getHistory().size() + 1);
            oldExecution.getHistory().add(event);
        }
        assertEquals(oldIdentity, fixture.storage.executions().get(oldExecution.getExecutionArn())
                .orElseThrow().getStateMachineIncarnationId());
        fixture.assertHidden(oldExecution);
        fixture.storage.executions().delete(oldExecution.getExecutionArn());
        assertTrue(fixture.storage.executions().get(oldExecution.getExecutionArn()).isEmpty());
        oldExecution.setStatus("SUCCEEDED");
        fixture.callbacks.get(oldExecution.getExecutionArn()).accept(oldExecution, oldExecution.getHistory());
        assertEquals("SUCCEEDED", fixture.storage.executions().get(oldExecution.getExecutionArn())
                .orElseThrow().getStatus());
        assertEquals(oldIdentity, fixture.storage.executions().get(oldExecution.getExecutionArn())
                .orElseThrow().getStateMachineIncarnationId());
        assertNotEquals(oldIdentity, replacement.getIncarnationId());
        fixture.assertHidden(oldExecution);
    }

    @Test
    void persistentReloadRetainsBothIdentityAndDefensiveConfiguration() {
        Fixture first = new Fixture(new TestStorage(directory));
        StateMachine machine = first.create();
        Execution execution = first.start("persisted");
        first.complete(execution);
        ExecutionConfiguration original = first.service.describeStateMachineForExecution(execution.getExecutionArn());
        first.storage.flushAll();
        Fixture restored = new Fixture(new TestStorage(directory));
        assertEquals(machine.getIncarnationId(), restored.service.describeStateMachine(MACHINE).getIncarnationId());
        assertEquals(machine.getIncarnationId(), restored.service.describeExecutionForApi(execution.getExecutionArn())
                .getStateMachineIncarnationId());
        assertEquals(original, restored.service.describeStateMachineForExecution(execution.getExecutionArn()));
        ExecutionConfiguration copy = restored.service.describeStateMachineForExecution(execution.getExecutionArn());
        ((ObjectNode) copy.tracingConfiguration()).put("enabled", true);
        assertFalse(restored.service.describeStateMachineForExecution(execution.getExecutionArn())
                .tracingConfiguration().path("enabled").asBoolean());
        restored.service.deleteStateMachine(MACHINE);
        StateMachine replacement = restored.create();
        restored.storage.flushAll();
        Fixture reloadedReplacement = new Fixture(new TestStorage(directory));
        assertEquals(replacement.getIncarnationId(),
                reloadedReplacement.service.describeStateMachine(MACHINE).getIncarnationId());
        reloadedReplacement.assertHidden(execution);
        assertEquals(original, reloadedReplacement.service.describeExecution(execution.getExecutionArn())
                .getStateMachineConfiguration());
    }

    @Test
    void versionAndAliasConfigurationDatesAndRevisionsSurviveAStoredReload() {
        Fixture first = new Fixture(new TestStorage(directory));
        StateMachine initial = first.create();
        StateMachineVersion originalVersion = first.service.publishStateMachineVersion(MACHINE);
        String alias = first.service.createStateMachineAlias("ORIGINAL", null,
                List.of(new RoutingConfiguration(originalVersion.getStateMachineVersionArn(), 100)))
                .getStateMachineAliasArn();
        StepFunctionsService.UpdateStateMachineResult updated = first.service.updateStateMachine(MACHINE,
                new StepFunctionsService.UpdateStateMachineRequest(DEFINITION, ROLE + "-updated",
                        null, false, null, false, null, false, false, null));
        StateMachineVersion updatedVersion = first.service.publishStateMachineVersion(MACHINE);
        first.storage.flushAll();
        Fixture restored = new Fixture(new TestStorage(directory));
        for (String selected : List.of(originalVersion.getStateMachineVersionArn(), alias)) {
            Execution execution = restored.service.startExecution(selected,
                    "original-" + restored.callbacks.size(), "{}", REGION);
            ExecutionConfiguration captured = restored.service.describeStateMachineForExecution(execution.getExecutionArn());
            assertEquals(initial.getCreationDate(), captured.updateDate());
            assertNull(captured.revisionId());
            assertEquals(ROLE, captured.roleArn());
            assertEquals(initial.getIncarnationId(), execution.getStateMachineIncarnationId());
        }
        Execution current = restored.service.startExecution(updatedVersion.getStateMachineVersionArn(),
                "updated-version", "{}", REGION);
        ExecutionConfiguration captured = restored.service.describeStateMachineForExecution(current.getExecutionArn());
        assertEquals(updated.stateMachine().getUpdateDate().doubleValue(), captured.updateDate());
        assertEquals(updated.stateMachine().getRevisionId(), captured.revisionId());
        assertEquals(ROLE + "-updated", captured.roleArn());
        assertEquals(initial.getIncarnationId(), current.getStateMachineIncarnationId());
    }

    @Test
    void legacyStoredPairsRemainVisibleUntilTheirOwnerIsDeletedOrRecreated() throws Exception {
        Fixture first = new Fixture(new TestStorage(directory));
        first.create();
        Execution execution = first.start("legacy");
        first.complete(execution);
        ObjectNode machineJson = MAPPER.valueToTree(first.storage.machines().get(MACHINE).orElseThrow());
        machineJson.remove("incarnationId");
        ObjectNode executionJson = MAPPER.valueToTree(execution);
        executionJson.remove("stateMachineIncarnationId");
        first.storage.machines().put(MACHINE, MAPPER.treeToValue(machineJson, StateMachine.class));
        first.storage.executions().put(execution.getExecutionArn(), MAPPER.treeToValue(executionJson, Execution.class));
        first.storage.flushAll();
        Fixture restored = new Fixture(new TestStorage(directory));
        assertNull(restored.service.describeStateMachine(MACHINE).getIncarnationId());
        assertNull(restored.service.describeExecutionForApi(execution.getExecutionArn()).getStateMachineIncarnationId());
        assertEquals(DEFINITION, restored.service.describeStateMachineForExecution(execution.getExecutionArn()).definition());
        restored.service.deleteStateMachine(MACHINE);
        restored.assertHidden(execution);
        restored.create();
        restored.assertHidden(execution);
    }

    @Test
    void legacyMissingConfigurationDoesNotGuessTheCurrentDefinition() {
        Fixture fixture = new Fixture(new TestStorage(null));
        fixture.create();
        Execution execution = fixture.start("legacy-no-capture");
        fixture.storage.machines().get(MACHINE).orElseThrow().setIncarnationId(null);
        execution.setStateMachineIncarnationId(null);
        execution.setStateMachineConfiguration(null);
        assertSame(execution, fixture.service.describeExecutionForApi(execution.getExecutionArn()));
        AwsException failure = assertThrows(AwsException.class, () ->
                fixture.service.describeStateMachineForExecution(execution.getExecutionArn()));
        assertEquals("UnsupportedOperation", failure.getErrorCode());
        fixture.service.deleteStateMachine(MACHINE);
        fixture.assertHidden(execution);
    }

    @Test
    void executionWithKnownIdentityCannotAdoptAnUnidentifiedLegacyOwner() {
        Fixture fixture = new Fixture(new TestStorage(null));
        fixture.create();
        Execution execution = fixture.start("known-identity");
        assertNotNull(execution.getStateMachineIncarnationId());
        fixture.storage.machines().get(MACHINE).orElseThrow().setIncarnationId(null);
        fixture.assertHidden(execution);
        assertSame(execution, fixture.service.describeExecution(execution.getExecutionArn()));
    }

    @Test
    void legacyVersionReloadKeepsTheRecordedPublicationDateFallback() throws Exception {
        Fixture first = new Fixture(new TestStorage(directory));
        first.create();
        first.service.updateStateMachine(MACHINE, new StepFunctionsService.UpdateStateMachineRequest(
                DEFINITION, ROLE + "-updated", null, false, null, false, null, false, false, null));
        StateMachineVersion version = first.service.publishStateMachineVersion(MACHINE);
        ObjectNode legacy = MAPPER.valueToTree(version);
        legacy.remove(List.of("configurationUpdateDate", "configurationRevisionId"));
        first.storage.machines().get(MACHINE).orElseThrow().setVersions(
                List.of(MAPPER.treeToValue(legacy, StateMachineVersion.class)));
        first.storage.flushAll();
        Fixture restored = new Fixture(new TestStorage(directory));
        Execution execution = restored.service.startExecution(version.getStateMachineVersionArn(),
                "legacy-version", "{}", REGION);
        ExecutionConfiguration captured = restored.service.describeStateMachineForExecution(execution.getExecutionArn());
        assertEquals(version.getCreationDate(), captured.updateDate());
        assertEquals(version.getRevisionId(), captured.revisionId());
        assertEquals(ROLE + "-updated", captured.roleArn());
    }

    private static final class Fixture {
        private final TestStorage storage;
        private final StepFunctionsService service;
        private final Map<String, BiConsumer<Execution, List<HistoryEvent>>> callbacks = new HashMap<>();

        private Fixture(TestStorage storage) {
            this(storage, ACCOUNT);
        }

        private Fixture(TestStorage storage, String caller) {
            this.storage = storage;
            AslExecutor executor = mock(AslExecutor.class);
            doAnswer(invocation -> {
                Execution execution = invocation.getArgument(1);
                callbacks.put(execution.getExecutionArn(), invocation.getArgument(4));
                return null;
            }).when(executor).executeAsync(any(StateMachine.class), any(Execution.class), anyList(), any(), any());
            service = new StepFunctionsService(storage, new RegionResolver(REGION, caller), executor,
                    MAPPER, mock(SfnMockLoader.class), mock(StepFunctionsEventPublisher.class));
        }

        private StateMachine create() {
            return service.createStateMachine("visibility", DEFINITION, ROLE, "STANDARD", REGION, Map.of());
        }

        private Execution start(String name) {
            return service.startExecution(MACHINE, name, "{}", REGION);
        }

        private void complete(Execution execution) {
            execution.setStatus("SUCCEEDED");
            callbacks.get(execution.getExecutionArn()).accept(execution, execution.getHistory());
        }

        private void assertHidden(Execution execution) {
            AwsException described = assertThrows(AwsException.class, () ->
                    service.describeExecutionForApi(execution.getExecutionArn()));
            AwsException configured = assertThrows(AwsException.class, () ->
                    service.describeStateMachineForExecution(execution.getExecutionArn()));
            assertEquals("ExecutionDoesNotExist", described.getErrorCode());
            assertEquals("ExecutionDoesNotExist", configured.getErrorCode());
        }
    }

    private static final class TestStorage extends StorageFactory {
        private final Path directory;
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private TestStorage(Path directory) {
            super(null, null);
            this.directory = directory;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String service, String file,
                                                        TypeReference<Map<String, V>> type) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(file, ignored -> {
                if (directory == null) {
                    return AccountAwareStorageBackend.inMemory(ACCOUNT);
                }
                PersistentStorage<String, V> backend = new PersistentStorage<>(directory.resolve(file), type);
                backend.load();
                return new AccountAwareStorageBackend<>(backend, null, ACCOUNT);
            });
        }

        private AccountAwareStorageBackend<StateMachine> machines() {
            return create("stepfunctions", "sfn-state-machines.json", new TypeReference<Map<String, StateMachine>>() {});
        }

        private AccountAwareStorageBackend<Execution> executions() {
            return create("stepfunctions", "sfn-executions.json", new TypeReference<Map<String, Execution>>() {});
        }

        @Override
        public void flushAll() {
            stores.values().forEach(StorageBackend::flush);
        }
    }
}
