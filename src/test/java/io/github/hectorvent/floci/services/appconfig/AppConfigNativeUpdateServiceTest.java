package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentStrategy;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.ParkingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AppConfigNativeUpdateServiceTest {
    private static final String ACCOUNT = "741686000091";
    private static final String ALARM = "arn:aws:cloudwatch:us-east-1:" + ACCOUNT + ":alarm:owned";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/owned";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AppConfigService service = service(null, null);

    @Test
    void environmentPatchesPreserveOmittedFieldsAndEarlierSnapshots() {
        String application = service.createApplication(Map.of("Name", "application")).getId();
        Environment original = environment(service, application);
        JsonNode before = snapshot(original);
        Environment sibling = service.createEnvironment(application, Map.of("Name", "sibling"));
        JsonNode siblingBefore = snapshot(sibling);
        Environment changed = service.updateEnvironment(application, original.getId(), Map.of("Name", "renamed"));
        assertEquals("renamed", changed.getName());
        assertEquals("initial", changed.getDescription());
        assertEquals(original.getMonitors(), changed.getMonitors());
        assertEquals(original.getState(), changed.getState());
        assertEquals(before, snapshot(original));
        service.updateEnvironment(application, original.getId(), Map.of("Description", "", "Monitors", List.of()));
        changed = service.getEnvironment(application, original.getId());
        assertEquals("renamed", changed.getName());
        assertEquals("", changed.getDescription());
        assertEquals(List.of(), changed.getMonitors());
        assertEquals(siblingBefore, snapshot(service.getEnvironment(application, sibling.getId())));
        assertEquals(snapshot(changed), snapshot(service.updateEnvironment(application, original.getId(), Map.of())));
    }

    @Test
    void strategyPatchesKeepOtherSettingsAndTheImmutableFields() {
        DeploymentStrategy original = strategy(service);
        JsonNode before = snapshot(original);
        DeploymentStrategy sibling = strategy(service);
        JsonNode siblingBefore = snapshot(sibling);
        service.updateDeploymentStrategy(original.getId(), Map.of("GrowthFactor", 40));
        service.updateDeploymentStrategy(original.getId(), Map.of("DeploymentDurationInMinutes", 3));
        service.updateDeploymentStrategy(original.getId(), Map.of("FinalBakeTimeInMinutes", 0));
        service.updateDeploymentStrategy(original.getId(), Map.of("GrowthType", "EXPONENTIAL", "Description", ""));
        DeploymentStrategy changed = service.getDeploymentStrategy(original.getId());
        assertEquals(40f, changed.getGrowthFactor());
        assertEquals(3, changed.getDeploymentDurationInMinutes());
        assertEquals(0, changed.getFinalBakeTimeInMinutes());
        assertEquals("EXPONENTIAL", changed.getGrowthType());
        assertEquals("", changed.getDescription());
        assertEquals(original.getName(), changed.getName());
        assertEquals(original.getReplicateTo(), changed.getReplicateTo());
        assertEquals(before, snapshot(original));
        assertEquals(siblingBefore, snapshot(service.getDeploymentStrategy(sibling.getId())));
        assertEquals(snapshot(changed), snapshot(service.updateDeploymentStrategy(original.getId(), Map.of())));
        assertEquals(snapshot(changed), snapshot(service.listDeploymentStrategies().stream()
                .filter(value -> value.getId().equals(original.getId())).findFirst().orElseThrow()));
    }

    @ParameterizedTest
    @MethodSource("invalidEnvironmentPatches")
    void invalidEnvironmentMembersCannotPartiallyChangeTheRecord(Map<String, Object> invalid) {
        String application = service.createApplication(Map.of("Name", "application")).getId();
        Environment environment = environment(service, application);
        JsonNode before = snapshot(environment);
        Map<String, Object> request = new LinkedHashMap<>(Map.of("Description", "must-not-store"));
        request.putAll(invalid);
        AwsException error = assertThrows(AwsException.class,
                () -> service.updateEnvironment(application, environment.getId(), request));
        assertEquals(400, error.getHttpStatus());
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(before, snapshot(service.getEnvironment(application, environment.getId())));
    }

    static Stream<Map<String, Object>> invalidEnvironmentPatches() {
        return Stream.of(Map.of("Name", ""), Map.of("Name", "x".repeat(65)), Map.of("Name", 12),
                Map.of("Description", "x".repeat(1025)), Map.of("Description", List.of()),
                Map.of("Monitors", Map.of()), Map.of("Monitors", List.of(Map.of())),
                Map.of("Monitors", List.of(Map.of("AlarmArn", ""))),
                Map.of("Monitors", List.of(Map.of("AlarmArn", ALARM, "AlarmRoleArn", "not-an-arn"))),
                Map.of("Monitors", Collections.nCopies(6, Map.of("AlarmArn", ALARM))));
    }

    @ParameterizedTest
    @MethodSource("invalidStrategyPatches")
    void invalidStrategyMembersCannotPartiallyChangeTheRecord(Map<String, Object> invalid) {
        DeploymentStrategy strategy = strategy(service);
        JsonNode before = snapshot(strategy);
        Map<String, Object> request = new LinkedHashMap<>(Map.of("Description", "must-not-store"));
        request.putAll(invalid);
        AwsException error = assertThrows(AwsException.class,
                () -> service.updateDeploymentStrategy(strategy.getId(), request));
        assertEquals(400, error.getHttpStatus());
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(before, snapshot(service.getDeploymentStrategy(strategy.getId())));
    }

    static Stream<Map<String, Object>> invalidStrategyPatches() {
        return Stream.of(Map.of("GrowthFactor", 0), Map.of("GrowthFactor", 101), Map.of("GrowthFactor", Double.NaN),
                Map.of("GrowthFactor", Double.POSITIVE_INFINITY), Map.of("GrowthFactor", "40"),
                Map.of("DeploymentDurationInMinutes", -1), Map.of("DeploymentDurationInMinutes", 1441),
                Map.of("DeploymentDurationInMinutes", 1.5), Map.of("FinalBakeTimeInMinutes", -1),
                Map.of("FinalBakeTimeInMinutes", 1441), Map.of("FinalBakeTimeInMinutes", "0"),
                Map.of("GrowthType", "linear"), Map.of("GrowthType", 12),
                Map.of("Description", "x".repeat(1025)));
    }

    @Test
    void wrongParentDeletedResourcesAndPredefinedStrategiesAreRefused() {
        String application = service.createApplication(Map.of("Name", "application")).getId();
        String other = service.createApplication(Map.of("Name", "other")).getId();
        Environment environment = environment(service, application);
        JsonNode before = snapshot(environment);
        assertEquals(404, assertThrows(AwsException.class,
                () -> service.updateEnvironment(other, environment.getId(), Map.of("Name", "no"))).getHttpStatus());
        assertEquals(before, snapshot(service.getEnvironment(application, environment.getId())));
        service.deleteApplication(application);
        assertEquals(404, assertThrows(AwsException.class,
                () -> service.updateEnvironment(application, environment.getId(), Map.of())).getHttpStatus());
        service.deleteEnvironmentById(environment.getId());
        DeploymentStrategy strategy = strategy(service);
        service.deleteDeploymentStrategy(strategy.getId());
        assertEquals(404, assertThrows(AwsException.class,
                () -> service.updateDeploymentStrategy(strategy.getId(), Map.of())).getHttpStatus());
        JsonNode builtin = snapshot(service.getDeploymentStrategy("AppConfig.AllAtOnce"));
        assertEquals(400, assertThrows(AwsException.class,
                () -> service.updateDeploymentStrategy("AppConfig.AllAtOnce", Map.of("GrowthFactor", 20))).getHttpStatus());
        assertEquals(builtin, snapshot(service.getDeploymentStrategy("AppConfig.AllAtOnce")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aFailedBackendWriteLeavesThePreviouslyPublishedObjectIntact(boolean environment) {
        FailingStore<Object> store = new FailingStore<>();
        AppConfigService failing = service(environment ? "appconfig-environments.json" : "appconfig-strategies.json", store);
        String application = failing.createApplication(Map.of("Name", "application")).getId();
        String id = environment ? environment(failing, application).getId() : strategy(failing).getId();
        Object original = environment ? failing.getEnvironment(application, id) : failing.getDeploymentStrategy(id);
        JsonNode before = snapshot(original);
        store.fail = true;
        assertThrows(IllegalStateException.class, () -> update(failing, environment, application, id, Map.of("Description", "failed")));
        assertEquals(before, snapshot(original));
        assertEquals(before, snapshot(environment ? failing.getEnvironment(application, id) : failing.getDeploymentStrategy(id)));
        update(failing, environment, application, id, Map.of("Description", "accepted"));
        assertEquals("accepted", snapshot(environment ? failing.getEnvironment(application, id)
                : failing.getDeploymentStrategy(id)).path("Description").asText());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentPatchesComposeAfterTheFirstAtomicReplacement(boolean environment) throws Exception {
        ParkingStore<Object> store = new ParkingStore<>();
        AppConfigService concurrent = service(environment ? "appconfig-environments.json" : "appconfig-strategies.json", store);
        String application = concurrent.createApplication(Map.of("Name", "application")).getId();
        String id = environment ? environment(concurrent, application).getId() : strategy(concurrent).getId();
        Object original = environment ? concurrent.getEnvironment(application, id) : concurrent.getDeploymentStrategy(id);
        JsonNode before = snapshot(original);
        store.parkNextWrite();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(() -> update(concurrent, environment, application, id,
                    Map.of("Description", "first")));
            assertTrue(store.awaitParked());
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            Future<?> second = threads.submit(() -> {
                secondThread.set(Thread.currentThread());
                update(concurrent, environment, application, id,
                        environment ? Map.of("Name", "second") : Map.of("GrowthFactor", 40));
            });
            awaitBlockedOrDone(secondThread, second);
            assertFalse(second.isDone());
            assertEquals(Thread.State.BLOCKED, secondThread.get().getState());
            assertEquals(before, snapshot(original));
            store.release();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            JsonNode after = snapshot(environment ? concurrent.getEnvironment(application, id)
                    : concurrent.getDeploymentStrategy(id));
            assertEquals("first", after.path("Description").asText());
            if (environment) {
                assertEquals("second", after.path("Name").asText());
            } else {
                assertEquals(40, after.path("GrowthFactor").asInt());
            }
        } finally {
            store.release();
            threads.shutdownNow();
        }
    }

    private static void update(AppConfigService service, boolean environment, String application, String id,
                               Map<String, Object> request) {
        if (environment) {
            service.updateEnvironment(application, id, request);
        } else {
            service.updateDeploymentStrategy(id, request);
        }
    }

    private static Environment environment(AppConfigService service, String application) {
        return service.createEnvironment(application, Map.of("Name", "environment", "Description", "initial",
                "Monitors", List.of(Map.of("AlarmArn", ALARM, "AlarmRoleArn", ROLE))));
    }

    private static DeploymentStrategy strategy(AppConfigService service) {
        return service.createDeploymentStrategy(Map.of("Name", "strategy", "Description", "initial",
                "DeploymentDurationInMinutes", 10, "FinalBakeTimeInMinutes", 2, "GrowthFactor", 20,
                "GrowthType", "LINEAR", "ReplicateTo", "NONE"));
    }

    private static JsonNode snapshot(Object value) {
        return MAPPER.valueToTree(value);
    }

    private static AppConfigService service(String file, StorageBackend<String, Object> store) {
        InMemoryStorageFactory factory = new InMemoryStorageFactory(ACCOUNT);
        if (store != null) {
            factory.withStore(file, store);
        }
        return new AppConfigService(factory, new RegionResolver("us-east-1", ACCOUNT),
                mock(AppConfigAlarmMonitor.class), mock(AppConfigS3Source.class));
    }

    private static final class FailingStore<V> extends InMemoryStorage<String, V> {
        private boolean fail;

        @Override
        public void putAllAtomically(Map<String, V> entries) {
            if (fail) {
                fail = false;
                throw new IllegalStateException("Injected failure before backend publication");
            }
            super.putAllAtomically(entries);
        }
    }
}
