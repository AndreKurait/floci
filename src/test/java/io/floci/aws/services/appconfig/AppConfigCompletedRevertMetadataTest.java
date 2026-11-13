package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.appconfig.model.Deployment;
import io.floci.aws.services.appconfig.model.DeploymentEvent;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigCompletedRevertMetadataTest {
    private static final String ACCOUNT = "000000000000";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant REVERT = START.plusSeconds(180);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<DeploymentEvent> HISTORY = List.of(
            new DeploymentEvent("DEPLOYMENT_COMPLETED", "Recorded completion",
                    START.plusSeconds(60).toString(), "INTERNAL_ERROR"),
            new DeploymentEvent("DEPLOYMENT_STARTED", "Recorded start", START.toString(), "USER"));

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"persistent,0", "persistent,1", "hybrid,0", "hybrid,1", "wal,0", "wal,1"})
    void completedRevertPreservesStoredTimestampsAndAllHistoryAcrossRestart(String mode, int bake) {
        Scope scope;
        int number;
        JsonNode older;
        Deployment completed;
        List<Executable> checks = new ArrayList<>();
        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = service(first, START, null);
            scope = scope(service);
            Deployment previous = deploy(service, scope, "1", 0, 0);
            older = JSON.valueToTree(previous);
            number = deploy(service, scope, "2", 0, bake).getDeploymentNumber();
            AppConfigService completion = service(first, START.plusSeconds(60), null);
            completed = completion.getDeployment(scope.app(), scope.env(), number);
            seed(first, completed);
        } finally {
            first.shutdownAll();
        }

        StorageFactory second = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = service(second, REVERT, null);
            Deployment before = service.getDeployment(scope.app(), scope.env(), number);
            Deployment reverted = service.stopDeployment(scope.app(), scope.env(), number, true);
            List<DeploymentEvent> expected = new ArrayList<>();
            expected.add(new DeploymentEvent("REVERT_COMPLETED", "Deployment reverted by user request",
                    REVERT.toString(), "USER"));
            expected.addAll(HISTORY);
            JsonNode previousAfter = JSON.valueToTree(service.getDeployment(scope.app(), scope.env(), 1));
            String activeAfter = service.getActiveVersion(scope.env(), scope.profile());
            String listedCompletedAt = service.listDeployments(scope.app(), scope.env(), 50, null).items().stream()
                    .filter(value -> value.getDeploymentNumber() == number).findFirst().orElseThrow().getCompletedAt();
            checks.addAll(List.of(
                    () -> assertEquals("REVERTED", reverted.getState()),
                    () -> assertEquals(0.0f, reverted.getPercentageComplete()),
                    () -> assertEquals(completed.getStartedAt(), reverted.getStartedAt()),
                    () -> assertEquals(completed.getCompletedAt(), reverted.getCompletedAt()),
                    () -> assertEquals(expected, reverted.getEventLog()),
                    () -> assertEquals(HISTORY, before.getEventLog()),
                    () -> assertEquals("COMPLETE", before.getState()),
                    () -> assertNotSame(before, reverted),
                    () -> assertEquals(older, previousAfter),
                    () -> assertEquals("1", activeAfter),
                    () -> assertEquals(completed.getCompletedAt(), listedCompletedAt)));
        } finally {
            second.shutdownAll();
        }

        StorageFactory third = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = service(third, REVERT.plusSeconds(3600), null);
            Deployment restored = service.getDeployment(scope.app(), scope.env(), number);
            JsonNode state = JSON.valueToTree(restored);
            String restoredActive = service.getActiveVersion(scope.env(), scope.profile());
            checks.addAll(List.of(
                    () -> assertEquals(completed.getStartedAt(), restored.getStartedAt()),
                    () -> assertEquals(completed.getCompletedAt(), restored.getCompletedAt()),
                    () -> assertEquals(3, restored.getEventLog().size()),
                    () -> assertEquals(REVERT.toString(), restored.getEventLog().getFirst().occurredAt()),
                    () -> assertEquals(HISTORY, restored.getEventLog().stream().skip(1).toList()),
                    () -> assertEquals("1", restoredActive)));
            assertThrows(AwsException.class, () -> service.stopDeployment(scope.app(), scope.env(), number, true));
            assertEquals(state, JSON.valueToTree(service.getDeployment(scope.app(), scope.env(), number)));
        } finally {
            third.shutdownAll();
        }
        assertAll(checks);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void activeManualRollbackRetainsItsCompletionTimestampAndRecordedHistory(int rollout) {
        StorageFactory storage = new InMemoryStorageFactory(ACCOUNT);
        AppConfigService start = service(storage, START, null);
        Scope scope = scope(start);
        deploy(start, scope, "1", 0, 0);
        Deployment active = deploy(start, scope, "2", rollout, 1);
        seed(storage, active);
        Instant stoppedAt = START.plusSeconds(30);
        AppConfigService stop = service(storage, stoppedAt, null);
        Deployment result = stop.stopDeployment(scope.app(), scope.env(), active.getDeploymentNumber(), true);
        assertAll(
                () -> assertEquals("ROLLED_BACK", result.getState()),
                () -> assertEquals(START.toString(), result.getStartedAt()),
                () -> assertEquals(stoppedAt.toString(), result.getCompletedAt()),
                () -> assertEquals(HISTORY, result.getEventLog()),
                () -> assertEquals("1", stop.getActiveVersion(scope.env(), scope.profile())));
    }

    @Test
    void alarmRollbackRetainsItsExistingEventOrderingAndRecordedHistory() {
        StorageFactory storage = new InMemoryStorageFactory(ACCOUNT);
        AppConfigAlarmMonitor alarms = mock(AppConfigAlarmMonitor.class);
        AppConfigService start = service(storage, START, alarms);
        Scope scope = scope(start);
        start.updateEnvironment(scope.app(), scope.env(), "environment", null, List.of(Map.of(
                "AlarmArn", "arn:aws:cloudwatch:us-east-1:000000000000:alarm:metadata",
                "AlarmRoleArn", "arn:aws:iam::000000000000:role/metadata")));
        deploy(start, scope, "1", 0, 0);
        Deployment active = deploy(start, scope, "2", 1, 1);
        seed(storage, active);
        Instant stoppedAt = START.plusSeconds(30);
        String cause = "Recorded monitored alarm";
        when(alarms.rollbackCause(anyList())).thenReturn(cause);
        AppConfigService stop = service(storage, stoppedAt, alarms);
        Deployment result = stop.getDeployment(scope.app(), scope.env(), active.getDeploymentNumber());
        List<DeploymentEvent> expected = new ArrayList<>();
        expected.add(new DeploymentEvent("ROLLBACK_COMPLETED", cause, stoppedAt.toString(), "CLOUDWATCH_ALARM"));
        expected.add(new DeploymentEvent("ROLLBACK_STARTED", cause, stoppedAt.toString(), "CLOUDWATCH_ALARM"));
        expected.addAll(HISTORY);
        assertAll(
                () -> assertEquals("ROLLED_BACK", result.getState()),
                () -> assertEquals(stoppedAt.toString(), result.getCompletedAt()),
                () -> assertEquals(expected, result.getEventLog()),
                () -> assertEquals("1", stop.getActiveVersion(scope.env(), scope.profile())));
    }

    private static Scope scope(AppConfigService service) {
        String app = service.createApplication(Map.of("Name", "completed-revert")).getId();
        String env = service.createEnvironment(app, Map.of("Name", "environment")).getId();
        String profile = service.createConfigurationProfile(app,
                Map.of("Name", "profile", "LocationUri", "hosted")).getId();
        service.createHostedConfigurationVersion(app, profile, "baseline".getBytes(StandardCharsets.UTF_8),
                "text/plain", "baseline");
        service.createHostedConfigurationVersion(app, profile, "candidate".getBytes(StandardCharsets.UTF_8),
                "text/plain", "candidate");
        return new Scope(app, env, profile);
    }

    private static Deployment deploy(AppConfigService service, Scope scope, String version, int rollout, int bake) {
        String strategy = service.createDeploymentStrategy(Map.of("Name", "strategy",
                "DeploymentDurationInMinutes", rollout, "FinalBakeTimeInMinutes", bake, "GrowthFactor", 100)).getId();
        return service.startDeployment(scope.app(), scope.env(), Map.of("ConfigurationProfileId", scope.profile(),
                "ConfigurationVersion", version, "DeploymentStrategyId", strategy));
    }

    private static void seed(StorageFactory storage, Deployment deployment) {
        Deployment saved = JSON.convertValue(deployment, Deployment.class);
        saved.setEventLog(HISTORY);
        AppConfigStorage<Deployment> store = new AppConfigStorage<>(storage, regions(), "appconfig",
                "appconfig-deployments.json", new TypeReference<>() {});
        store.put(saved.getApplicationId() + "::" + saved.getEnvironmentId() + "::" + saved.getDeploymentNumber(), saved);
    }

    private static RegionResolver regions() {
        return new RegionResolver("us-east-1", ACCOUNT);
    }

    private static AppConfigService service(StorageFactory storage, Instant now, AppConfigAlarmMonitor alarms) {
        return new AppConfigService(storage, regions(), Clock.fixed(now, ZoneOffset.UTC), alarms);
    }

    private record Scope(String app, String env, String profile) {}
}
