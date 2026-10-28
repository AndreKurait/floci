package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigDeploymentLifecycleTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path directory;

    @Test
    void bakingDeadlineAndRollbackSurviveRestart() {
        StorageFactory first = factory();
        AppConfigService service = service(first, START);
        Scope scope = scope(service);
        deploy(service, scope, "1", scope.fast());
        Deployment baking = deploy(service, scope, "2", scope.bake());
        assertEquals("BAKING", baking.getState());
        assertEquals(100.0f, baking.getPercentageComplete());
        first.shutdownAll();

        StorageFactory second = factory();
        service = service(second, START.plusSeconds(30));
        assertEquals("BAKING", service.getDeployment(scope.app(), scope.env(), baking.getDeploymentNumber()).getState());
        assertEquals("2", service.getActiveVersion(scope.env(), scope.profile()));
        assertEquals("ROLLED_BACK", service.stopDeployment(
                scope.app(), scope.env(), baking.getDeploymentNumber(), true).getState());
        assertEquals("1", service.getActiveVersion(scope.env(), scope.profile()));
        second.shutdownAll();

        StorageFactory third = factory();
        service = service(third, START.plusSeconds(120));
        assertEquals("ROLLED_BACK", service.getDeployment(scope.app(), scope.env(), baking.getDeploymentNumber()).getState());
        assertEquals("1", service.getActiveVersion(scope.env(), scope.profile()));
        third.shutdownAll();
    }

    @Test
    void completionUsesTheDeadlineAndExpiredRevertDoesNotChangeConfiguration() {
        StorageFactory first = factory();
        AppConfigService service = service(first, START);
        Scope scope = scope(service);
        Deployment baking = deploy(service, scope, "1", scope.bake());
        first.shutdownAll();

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
        second.shutdownAll();
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
        storage.shutdownAll();
    }

    private Scope scope(AppConfigService service) {
        String app = service.createApplication(Map.of("Name", "deployment-lifecycle")).getId();
        String env = service.createEnvironment(app, Map.of("Name", "environment")).getId();
        String profile = service.createConfigurationProfile(app, Map.of("Name", "profile", "LocationUri", "hosted")).getId();
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
        return new AppConfigService(storage, mock(EmulatorConfig.class), Clock.fixed(time, ZoneOffset.UTC));
    }

    private StorageFactory factory() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn("persistent");
        return new StorageFactory(config, access);
    }

    private record Scope(String app, String env, String profile, String fast, String bake) {}
}
