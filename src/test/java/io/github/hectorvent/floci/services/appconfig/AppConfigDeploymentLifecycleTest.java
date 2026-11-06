package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigDeploymentLifecycleTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final List<StorageFactory> openFactories = new ArrayList<>();

    @AfterEach
    void closeRemainingFactories() {
        List<Executable> cleanup = openFactories.reversed().stream()
                .<Executable>map(factory -> factory::shutdownAll).toList();
        openFactories.clear();
        assertAll(cleanup);
    }

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"30,30", "2,1", "1,0"})
    void configuredRolloutPrecedesFinalBake(int rolloutMinutes, int bakeMinutes) {
        StorageFactory storage = factory();
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        AppConfigService service = new AppConfigService(storage,
                new RegionResolver("us-east-1", "000000000000"), clock, mock(AppConfigAlarmMonitor.class));
        Scope scope = scope(service);
        String strategy = service.createDeploymentStrategy(Map.of(
                "Name", "gradual", "DeploymentDurationInMinutes", rolloutMinutes,
                "FinalBakeTimeInMinutes", bakeMinutes, "GrowthFactor", 20)).getId();

        Deployment started = deploy(service, scope, "1", strategy);
        assertEquals("DEPLOYING", started.getState());
        assertEquals(0.0f, started.getPercentageComplete());
        assertNull(started.getCompletedAt());
        when(clock.instant()).thenReturn(START.plus(Duration.ofMinutes(rolloutMinutes)).minusMillis(1));
        assertEquals("DEPLOYING", service.getDeployment(
                scope.app(), scope.env(), started.getDeploymentNumber()).getState());

        when(clock.instant()).thenReturn(START.plus(Duration.ofMinutes(rolloutMinutes)));
        Deployment rolledOut = service.getDeployment(scope.app(), scope.env(), started.getDeploymentNumber());
        assertEquals(bakeMinutes == 0 ? "COMPLETE" : "BAKING", rolledOut.getState());
        assertEquals(100.0f, rolledOut.getPercentageComplete());
        if (bakeMinutes > 0) {
            assertNull(rolledOut.getCompletedAt());
            when(clock.instant()).thenReturn(START.plus(Duration.ofMinutes(rolloutMinutes + bakeMinutes))
                    .minusMillis(1));
            assertEquals("BAKING", service.listDeployments(scope.app(), scope.env(), 50, null)
                    .items().getFirst().getState());
        }

        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(2)));
        Deployment completed = service.getDeployment(scope.app(), scope.env(), started.getDeploymentNumber());
        assertEquals("COMPLETE", completed.getState());
        assertEquals(START.plus(Duration.ofMinutes(rolloutMinutes + bakeMinutes)).toString(),
                completed.getCompletedAt());
        assertEquals("DEPLOYING", started.getState());
        assertEquals(0.0f, started.getPercentageComplete());
        assertNull(started.getCompletedAt());
    }

    @ParameterizedTest
    @CsvSource({
            "LINEAR,20,10,0,0", "LINEAR,20,10,119,0", "LINEAR,20,10,120,20",
            "LINEAR,20,10,599,80", "LINEAR,20,10,600,100",
            "LINEAR,30,10,150,30", "LINEAR,30,10,450,90", "LINEAR,30,10,600,100",
            "EXPONENTIAL,2,7,59,0", "EXPONENTIAL,2,7,60,2", "EXPONENTIAL,2,7,120,4",
            "EXPONENTIAL,2,7,300,32", "EXPONENTIAL,2,7,360,64", "EXPONENTIAL,2,7,420,100"
    })
    void reportsDiscreteGrowthOverTheConfiguredDuration(
            String growthType, float growth, int minutes, int elapsedSeconds, float percentage) {
        StorageFactory storage = factory();
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        AppConfigService service = new AppConfigService(storage,
                new RegionResolver("us-east-1", "000000000000"), clock, mock(AppConfigAlarmMonitor.class));
        Scope scope = scope(service);
        String strategy = service.createDeploymentStrategy(Map.of(
                "Name", "gradual", "DeploymentDurationInMinutes", minutes,
                "FinalBakeTimeInMinutes", 1, "GrowthFactor", growth, "GrowthType", growthType)).getId();
        Deployment started = deploy(service, scope, "1", strategy);
        when(clock.instant()).thenReturn(START.plusSeconds(elapsedSeconds));

        assertEquals(percentage, service.getDeployment(
                scope.app(), scope.env(), started.getDeploymentNumber()).getPercentageComplete());
        assertEquals(percentage, service.listDeployments(
                scope.app(), scope.env(), 50, null).items().getFirst().getPercentageComplete());
    }

    @Test
    void rolloutAndBakeUseSavedSettingsAfterRestartAndStrategyDeletion() {
        StorageFactory first = factory();
        AppConfigService service = service(first, START);
        Scope scope = scope(service);
        String strategy = service.createDeploymentStrategy(Map.of(
                "Name", "gradual", "DeploymentDurationInMinutes", 2,
                "FinalBakeTimeInMinutes", 1, "GrowthFactor", 20)).getId();
        Deployment started = deploy(service, scope, "1", strategy);
        service.deleteDeploymentStrategy(strategy);
        close(first);

        StorageFactory second = factory();
        service = service(second, START.plusSeconds(60));
        Deployment halfway = service.getDeployment(scope.app(), scope.env(), started.getDeploymentNumber());
        assertEquals("DEPLOYING", halfway.getState());
        assertEquals(40.0f, halfway.getPercentageComplete());
        close(second);

        StorageFactory third = factory();
        service = service(third, START.plusSeconds(180));
        Deployment completed = service.getDeployment(scope.app(), scope.env(), started.getDeploymentNumber());
        assertEquals("COMPLETE", completed.getState());
        assertEquals(START.plusSeconds(180).toString(), completed.getCompletedAt());
    }

    @Test
    void stoppingDuringRolloutRestoresThePreviousVersionAndDoesNotResumeLater() {
        StorageFactory storage = factory();
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        AppConfigService service = new AppConfigService(storage,
                new RegionResolver("us-east-1", "000000000000"), clock, mock(AppConfigAlarmMonitor.class));
        Scope scope = scope(service);
        deploy(service, scope, "1", scope.fast());
        String strategy = service.createDeploymentStrategy(Map.of(
                "Name", "gradual", "DeploymentDurationInMinutes", 2,
                "FinalBakeTimeInMinutes", 1, "GrowthFactor", 50)).getId();
        Deployment started = deploy(service, scope, "2", strategy);
        when(clock.instant()).thenReturn(START.plusSeconds(60));

        assertEquals("ROLLED_BACK", service.stopDeployment(
                scope.app(), scope.env(), started.getDeploymentNumber(), false).getState());
        assertEquals("1", service.getActiveVersion(scope.env(), scope.profile()));
        when(clock.instant()).thenReturn(START.plusSeconds(180));
        assertEquals("ROLLED_BACK", service.getDeployment(
                scope.app(), scope.env(), started.getDeploymentNumber()).getState());
        assertEquals("1", service.getActiveVersion(scope.env(), scope.profile()));
    }

    @Test
    void revertRecognizesAnEarlierRolloutThatCompletedWithoutBeingPolled() {
        StorageFactory storage = factory();
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        AppConfigService service = new AppConfigService(storage,
                new RegionResolver("us-east-1", "000000000000"), clock, mock(AppConfigAlarmMonitor.class));
        Scope scope = scope(service);
        String strategy = service.createDeploymentStrategy(Map.of(
                "Name", "gradual", "DeploymentDurationInMinutes", 1, "GrowthFactor", 50)).getId();
        deploy(service, scope, "1", strategy);
        when(clock.instant()).thenReturn(START.plusSeconds(60));
        Deployment second = deploy(service, scope, "2", scope.fast());

        service.stopDeployment(scope.app(), scope.env(), second.getDeploymentNumber(), true);

        assertEquals("1", service.getActiveVersion(scope.env(), scope.profile()));
    }

    @ParameterizedTest
    @CsvSource({"-1,0,20,LINEAR", "1441,0,20,LINEAR", "0,-1,20,LINEAR", "0,1441,20,LINEAR",
            "1,1,0,LINEAR", "1,1,101,LINEAR", "1,1,NaN,LINEAR", "1,1,20,unknown"})
    void invalidTimingCannotCreateOrChangeAStrategy(int rollout, int bake, float growth, String type) {
        StorageFactory storage = factory();
        AppConfigService service = service(storage, START);
        Scope scope = scope(service);
        AwsException creation = assertThrows(AwsException.class, () -> service.createDeploymentStrategy(Map.of(
                "Name", "invalid", "DeploymentDurationInMinutes", rollout, "FinalBakeTimeInMinutes", bake,
                "GrowthFactor", growth, "GrowthType", type)));
        assertEquals("BadRequestException", creation.getErrorCode());
        assertThrows(AwsException.class, () -> service.updateDeploymentStrategy(
                scope.fast(), "invalid", rollout, bake, growth, type));
        assertEquals(0, service.getDeploymentStrategy(scope.fast()).getDeploymentDurationInMinutes());
        assertEquals(0, service.getDeploymentStrategy(scope.fast()).getFinalBakeTimeInMinutes());
        assertEquals(100.0f, service.getDeploymentStrategy(scope.fast()).getGrowthFactor());
        assertEquals("LINEAR", service.getDeploymentStrategy(scope.fast()).getGrowthType());
    }

    @Test
    void bakingDeadlineAndRollbackSurviveRestart() {
        StorageFactory first = factory();
        AppConfigService service = service(first, START);
        Scope scope = scope(service);
        deploy(service, scope, "1", scope.fast());
        Deployment baking = deploy(service, scope, "2", scope.bake());
        assertEquals("BAKING", baking.getState());
        assertEquals(100.0f, baking.getPercentageComplete());
        close(first);

        StorageFactory second = factory();
        service = service(second, START.plusSeconds(30));
        assertEquals("BAKING", service.getDeployment(scope.app(), scope.env(), baking.getDeploymentNumber()).getState());
        assertEquals("2", service.getActiveVersion(scope.env(), scope.profile()));
        assertEquals("ROLLED_BACK", service.stopDeployment(
                scope.app(), scope.env(), baking.getDeploymentNumber(), true).getState());
        assertEquals("1", service.getActiveVersion(scope.env(), scope.profile()));
        close(second);

        StorageFactory third = factory();
        service = service(third, START.plusSeconds(120));
        assertEquals("ROLLED_BACK", service.getDeployment(scope.app(), scope.env(), baking.getDeploymentNumber()).getState());
        assertEquals("1", service.getActiveVersion(scope.env(), scope.profile()));
        close(third);
    }

    @Test
    void completionUsesTheDeadlineAndExpiredRevertDoesNotChangeConfiguration() {
        StorageFactory first = factory();
        AppConfigService service = service(first, START);
        Scope scope = scope(service);
        Deployment baking = deploy(service, scope, "1", scope.bake());
        close(first);

        StorageFactory second = factory();
        AppConfigService afterDeadline = service(second, START.plus(Duration.ofHours(74)));
        Deployment completed = afterDeadline.getDeployment(scope.app(), scope.env(), baking.getDeploymentNumber());
        assertEquals("COMPLETE", completed.getState());
        assertEquals(START.plusSeconds(60).toString(), completed.getCompletedAt());
        assertEquals("COMPLETE", afterDeadline.listDeployments(
                scope.app(), scope.env(), 50, null).items().getFirst().getState());
        AwsException failure = assertThrows(AwsException.class, () -> afterDeadline.stopDeployment(
                scope.app(), scope.env(), baking.getDeploymentNumber(), true));
        assertEquals("BadRequestException", failure.getErrorCode());
        assertEquals("1", afterDeadline.getActiveVersion(scope.env(), scope.profile()));
        close(second);
    }

    @Test
    void newerDeploymentProtectsItsConfigurationFromAnOlderRevert() {
        StorageFactory storage = factory();
        AppConfigService service = service(storage, START);
        Scope scope = scope(service);
        Deployment first = deploy(service, scope, "1", scope.fast());
        Deployment second = deploy(service, scope, "2", scope.fast());
        DeploymentStopException notLatest = assertThrows(DeploymentStopException.class, () -> service.stopDeployment(
                scope.app(), scope.env(), first.getDeploymentNumber(), true));
        assertEquals(DeploymentStopException.Reason.NOT_LATEST, notLatest.reason());
        assertEquals("2", service.getActiveVersion(scope.env(), scope.profile()));
        assertThrows(AwsException.class, () -> service.stopDeployment(
                scope.app(), scope.env(), second.getDeploymentNumber(), false));
        assertEquals("REVERTED", service.stopDeployment(
                scope.app(), scope.env(), second.getDeploymentNumber(), true).getState());
        assertEquals("1", service.getActiveVersion(scope.env(), scope.profile()));
        DeploymentStopException repeated = assertThrows(DeploymentStopException.class, () -> service.stopDeployment(
                scope.app(), scope.env(), second.getDeploymentNumber(), true));
        assertEquals(DeploymentStopException.Reason.ALREADY_REVERTED, repeated.reason());
        storage.shutdownAll();
    }

    @Test
    void anExpiredDeploymentHasASpecificStopRejectionAndRemainsActive() {
        StorageFactory storage = factory();
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        AppConfigService service = new AppConfigService(storage,
                new RegionResolver("us-east-1", "000000000000"), clock, mock(AppConfigAlarmMonitor.class));
        Scope scope = scope(service);
        Deployment deployment = deploy(service, scope, "1", scope.fast());
        when(clock.instant()).thenReturn(START.plus(Duration.ofHours(72)).plusNanos(1));

        DeploymentStopException expired = assertThrows(DeploymentStopException.class, () -> service.stopDeployment(
                scope.app(), scope.env(), deployment.getDeploymentNumber(), true));

        assertEquals(DeploymentStopException.Reason.TOO_OLD, expired.reason());
        assertEquals("BadRequestException", expired.getErrorCode());
        assertEquals(400, expired.getHttpStatus());
        assertEquals("1", service.getActiveVersion(scope.env(), scope.profile()));
        assertEquals("COMPLETE", service.getDeployment(
                scope.app(), scope.env(), deployment.getDeploymentNumber()).getState());
    }

    @Test
    void summaryPreservesBakingProgressAfterItsStrategyIsDeleted() {
        StorageFactory storage = factory();
        try {
            AppConfigService service = service(storage, START);
            Scope scope = scope(service);
            deploy(service, scope, "1", scope.bake());
            service.deleteDeploymentStrategy(scope.bake());

            DeploymentSummary summary = service.listDeployments(scope.app(), scope.env(), 50, null).items().getFirst();

            assertEquals("BAKING", summary.getState());
            assertEquals(100.0f, summary.getPercentageComplete());
            assertEquals(1, summary.getFinalBakeTimeInMinutes());
            assertEquals(START.toString(), summary.getStartedAt());
        } finally {
            storage.shutdownAll();
        }
    }

    @Test
    void summaryRetainsCompletionTimeWithSavedStrategySettingsAfterRestart() {
        StorageFactory first = factory();
        Scope scope;
        try {
            AppConfigService service = service(first, START);
            scope = scope(service);
            deploy(service, scope, "1", scope.bake());
            service.deleteDeploymentStrategy(scope.bake());
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = factory();
        try {
            AppConfigService service = service(second, START.plusSeconds(120));
            DeploymentSummary summary = service.listDeployments(scope.app(), scope.env(), 50, null).items().getFirst();

            assertEquals("COMPLETE", summary.getState());
            assertEquals(100.0f, summary.getPercentageComplete());
            assertEquals(1, summary.getFinalBakeTimeInMinutes());
            assertEquals(START.toString(), summary.getStartedAt());
            assertEquals(START.plusSeconds(60).toString(), summary.getCompletedAt());
        } finally {
            second.shutdownAll();
        }
    }

    @Test
    void legacyLifecycleRecordRetainsItsBakeTimeWithoutTheOtherStrategyFields() {
        StorageFactory storage = factory();
        try {
            AppConfigService service = service(storage, START);
            Scope scope = scope(service);
            Deployment deployment = deploy(service, scope, "1", scope.bake());
            deployment.setDeploymentDurationInMinutes(null);
            deployment.setGrowthFactor(null);
            deployment.setGrowthType(null);
            service.deleteDeploymentStrategy(scope.bake());

            DeploymentSummary summary = service.listDeployments(scope.app(), scope.env(), 50, null).items().getFirst();

            assertEquals(1, summary.getFinalBakeTimeInMinutes());
            assertEquals("BAKING", summary.getState());
            assertEquals(100.0f, summary.getPercentageComplete());
        } finally {
            storage.shutdownAll();
        }
    }

    @Test
    void deletedPreviousContentRejectsRevertWithoutChangingEitherStore() {
        StorageFactory storage = factory();
        AppConfigService service = service(storage, START);
        Scope scope = scope(service);
        deploy(service, scope, "1", scope.fast());
        Deployment current = deploy(service, scope, "2", scope.fast());
        service.deleteHostedConfigurationVersion(scope.app(), scope.profile(), 1);

        AwsException failure = assertThrows(AwsException.class, () -> service.stopDeployment(
                scope.app(), scope.env(), current.getDeploymentNumber(), true));
        assertEquals("BadRequestException", failure.getErrorCode());
        assertFalse(failure instanceof DeploymentStopException, "Missing content must not permit cleanup to pass.");
        assertEquals("2", service.getActiveVersion(scope.env(), scope.profile()));
        assertEquals("COMPLETE", service.getDeployment(scope.app(), scope.env(),
                current.getDeploymentNumber()).getState());
        assertEquals(START.toString(), current.getCompletedAt());
        close(storage);

        StorageFactory restarted = factory();
        AppConfigService restored = service(restarted, START);
        assertEquals("2", restored.getActiveVersion(scope.env(), scope.profile()));
        assertEquals("COMPLETE", restored.getDeployment(scope.app(), scope.env(),
                current.getDeploymentNumber()).getState());
    }

    @Test
    void rollbackDoesNotMutateAnEarlierDeploymentResponse() {
        StorageFactory storage = factory();
        AppConfigService service = service(storage, START);
        Scope scope = scope(service);
        deploy(service, scope, "1", scope.fast());
        Deployment baking = deploy(service, scope, "2", scope.bake());
        Deployment response = service.getDeployment(scope.app(), scope.env(), baking.getDeploymentNumber());
        Deployment stopped = service.stopDeployment(scope.app(), scope.env(), baking.getDeploymentNumber(), true);
        assertNotSame(response, stopped);
        assertEquals("BAKING", response.getState());
        assertEquals(100.0f, response.getPercentageComplete());
        assertNull(response.getCompletedAt());
        assertEquals("ROLLED_BACK", stopped.getState());
        assertEquals("1", service.getActiveVersion(scope.env(), scope.profile()));
    }

    @Test
    void completionDoesNotMutateAnEarlierResponseAndListUsesTheNewSnapshot() {
        StorageFactory storage = factory();
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        AppConfigService service = new AppConfigService(storage, new RegionResolver("us-east-1", "000000000000"), clock, mock(AppConfigAlarmMonitor.class));
        Scope scope = scope(service);
        Deployment baking = deploy(service, scope, "1", scope.bake());
        Deployment response = service.getDeployment(scope.app(), scope.env(), baking.getDeploymentNumber());
        when(clock.instant()).thenReturn(START.plusSeconds(60));

        assertEquals("COMPLETE", service.listDeployments(scope.app(), scope.env(), 50, null)
                .items().getFirst().getState());
        Deployment completed = service.getDeployment(scope.app(), scope.env(), baking.getDeploymentNumber());
        assertNotSame(response, completed);
        assertEquals("BAKING", response.getState());
        assertNull(response.getCompletedAt());
        assertEquals("COMPLETE", completed.getState());
        assertEquals(START.plusSeconds(60).toString(), completed.getCompletedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "0001", "+1"})
    void numericPreviousVersionUsesTheHostedStoreIdentity(String previousVersion) {
        StorageFactory storage = factory();
        AppConfigService service = service(storage, START);
        Scope scope = scope(service);
        deploy(service, scope, previousVersion, scope.fast());
        Deployment current = deploy(service, scope, "2", scope.fast());

        Deployment reverted = service.stopDeployment(scope.app(), scope.env(), current.getDeploymentNumber(), true);
        assertEquals("REVERTED", reverted.getState());
        assertEquals(previousVersion, service.getActiveVersion(scope.env(), scope.profile()));
        assertEquals("COMPLETE", current.getState());
        close(storage);

        StorageFactory restarted = factory();
        AppConfigService restored = service(restarted, START);
        assertEquals(previousVersion, restored.getActiveVersion(scope.env(), scope.profile()));
        assertEquals("REVERTED", restored.getDeployment(scope.app(), scope.env(),
                current.getDeploymentNumber()).getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "2147483648", "", "0003"})
    void unavailablePreviousVersionRejectsRevertWithoutChangingActiveState(String previousVersion) {
        StorageFactory storage = factory();
        AppConfigService service = service(storage, START);
        Scope scope = scope(service);
        deploy(service, scope, previousVersion, scope.fast());
        Deployment current = deploy(service, scope, "2", scope.fast());

        AwsException failure = assertThrows(AwsException.class, () -> service.stopDeployment(
                scope.app(), scope.env(), current.getDeploymentNumber(), true));
        assertEquals("BadRequestException", failure.getErrorCode());
        assertEquals("2", service.getActiveVersion(scope.env(), scope.profile()));
        assertEquals("COMPLETE", service.getDeployment(scope.app(), scope.env(),
                current.getDeploymentNumber()).getState());
        assertEquals("COMPLETE", current.getState());
    }

    private Scope scope(AppConfigService service) {
        String app = service.createApplication(Map.of("Name", "deployment-lifecycle")).getId();
        String env = service.createEnvironment(app, Map.of("Name", "environment")).getId();
        String profile = service.createConfigurationProfile(app, Map.of("Name", "profile", "LocationUri", "hosted")).getId();
        service.createHostedConfigurationVersion(app, profile, "one".getBytes(StandardCharsets.UTF_8),
                "text/plain", "first");
        service.createHostedConfigurationVersion(app, profile, "two".getBytes(StandardCharsets.UTF_8),
                "text/plain", "second");
        String fast = service.createDeploymentStrategy(Map.of("Name", "fast", "FinalBakeTimeInMinutes", 0)).getId();
        String bake = service.createDeploymentStrategy(Map.of("Name", "bake", "FinalBakeTimeInMinutes", 1)).getId();
        return new Scope(app, env, profile, fast, bake);
    }

    private Deployment deploy(AppConfigService service, Scope scope, String version, String strategy) {
        return service.startDeployment(scope.app(), scope.env(), Map.of(
                "ConfigurationProfileId", scope.profile(), "ConfigurationVersion", version,
                "DeploymentStrategyId", strategy));
    }

    private AppConfigService service(StorageFactory storage, Instant time) {
        return new AppConfigService(storage, new RegionResolver("us-east-1", "000000000000"), Clock.fixed(time, ZoneOffset.UTC), mock(AppConfigAlarmMonitor.class));
    }

    private StorageFactory factory() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn("persistent");
        StorageFactory storage = new StorageFactory(config, access);
        openFactories.add(storage);
        return storage;
    }

    private void close(StorageFactory storage) {
        try {
            storage.shutdownAll();
        } finally {
            openFactories.remove(storage);
        }
    }

    private record Scope(String app, String env, String profile, String fast, String bake) {}
}
