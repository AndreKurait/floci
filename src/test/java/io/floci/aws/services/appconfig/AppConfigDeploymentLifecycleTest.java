package io.floci.aws.services.appconfig;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.appconfig.model.Deployment;
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
    @CsvSource({"0.001,1800", "0.01,18000", "1.0,1800000"})
    void explicitTimeScalePreservesGrowthBakeAndRequestedMinutes(double scale, long rolloutMillis) {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        AppConfigService service = new AppConfigService(factory(),
                new RegionResolver("us-east-1", "000000000000"), clock, mock(AppConfigAlarmMonitor.class), scale);
        Scope scope = scope(service);
        String strategy = service.createDeploymentStrategy(Map.of(
                "Name", "scaled", "DeploymentDurationInMinutes", 30,
                "FinalBakeTimeInMinutes", 30, "GrowthFactor", 20)).getId();
        Deployment started = deploy(service, scope, "1", strategy);
        int number = started.getDeploymentNumber();
        when(clock.instant()).thenReturn(START.plusMillis(rolloutMillis / 5 - 1));
        assertEquals(0.0f, service.getDeployment(scope.app(), scope.env(), number).getPercentageComplete());
        when(clock.instant()).thenReturn(START.plusMillis(rolloutMillis / 5));
        assertEquals(20.0f, service.getDeployment(scope.app(), scope.env(), number).getPercentageComplete());
        when(clock.instant()).thenReturn(START.plusMillis(rolloutMillis));
        Deployment baking = service.getDeployment(scope.app(), scope.env(), number);
        assertEquals("BAKING", baking.getState());
        assertEquals(30, baking.getDeploymentDurationInMinutes());
        assertEquals(30, baking.getFinalBakeTimeInMinutes());
        when(clock.instant()).thenReturn(START.plusMillis(rolloutMillis * 2));
        Deployment completed = service.getDeployment(scope.app(), scope.env(), number);
        assertEquals("COMPLETE", completed.getState());
        assertEquals(START.plusMillis(rolloutMillis * 2).toString(), completed.getCompletedAt());
    }

    @Test
    void acceleratedBakeStillRollsBackWhenItsMonitorTrips() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        AppConfigAlarmMonitor alarms = mock(AppConfigAlarmMonitor.class);
        AppConfigService service = new AppConfigService(factory(),
                new RegionResolver("us-east-1", "000000000000"), clock, alarms, 0.001);
        Scope scope = scope(service);
        String strategy = service.createDeploymentStrategy(Map.of(
                "Name", "scaled", "DeploymentDurationInMinutes", 30,
                "FinalBakeTimeInMinutes", 30, "GrowthFactor", 20)).getId();
        Deployment started = deploy(service, scope, "1", strategy);
        when(clock.instant()).thenReturn(START.plusMillis(1800));
        assertEquals("BAKING", service.getDeployment(
                scope.app(), scope.env(), started.getDeploymentNumber()).getState());
        when(alarms.rollbackCause(null)).thenReturn("monitor is ALARM");
        assertEquals("ROLLED_BACK", service.getDeployment(
                scope.app(), scope.env(), started.getDeploymentNumber()).getState());
    }

    @Test
    void tinyPositiveScaleKeepsBothPositivePhases() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        AppConfigService service = new AppConfigService(factory(),
                new RegionResolver("us-east-1", "000000000000"), clock, mock(AppConfigAlarmMonitor.class), Double.MIN_VALUE);
        Scope scope = scope(service);
        String strategy = service.createDeploymentStrategy(Map.of(
                "Name", "scaled", "DeploymentDurationInMinutes", 1,
                "FinalBakeTimeInMinutes", 1, "GrowthFactor", 20)).getId();
        Deployment started = deploy(service, scope, "1", strategy);
        assertEquals("DEPLOYING", started.getState());
        when(clock.instant()).thenReturn(START.plusMillis(1));
        assertEquals("BAKING", service.getDeployment(
                scope.app(), scope.env(), started.getDeploymentNumber()).getState());
        when(clock.instant()).thenReturn(START.plusMillis(2));
        assertEquals("COMPLETE", service.getDeployment(
                scope.app(), scope.env(), started.getDeploymentNumber()).getState());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, 1.1, Double.NaN, Double.POSITIVE_INFINITY})
    void invalidTimeScaleIsRejectedBeforeOpeningStorage(double scale) {
        assertThrows(IllegalArgumentException.class, () -> new AppConfigService(mock(StorageFactory.class),
                new RegionResolver("us-east-1", "000000000000"), Clock.systemUTC(),
                mock(AppConfigAlarmMonitor.class), scale));
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
        assertThrows(AwsException.class, () -> service.stopDeployment(
                scope.app(), scope.env(), first.getDeploymentNumber(), true));
        assertEquals("2", service.getActiveVersion(scope.env(), scope.profile()));
        assertThrows(AwsException.class, () -> service.stopDeployment(
                scope.app(), scope.env(), second.getDeploymentNumber(), false));
        assertEquals("REVERTED", service.stopDeployment(
                scope.app(), scope.env(), second.getDeploymentNumber(), true).getState());
        assertEquals("1", service.getActiveVersion(scope.env(), scope.profile()));
        close(storage);
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
        AppConfigService service = new AppConfigService(
                storage, new RegionResolver("us-east-1", "000000000000"), clock);
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
        return new AppConfigService(storage, new RegionResolver("us-east-1", "000000000000"),
                Clock.fixed(time, ZoneOffset.UTC));
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
