package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentStrategy;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.testing.StorageFactories;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigNativeUpdatePersistenceTest {
    private static final String ACCOUNT = "741686000092";
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void patchesSurviveReloadAndCannotUpdateAnotherRegionOrAccount(String mode) {
        AtomicReference<String> current = new AtomicReference<>("us-east-1");
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getDefaultRegion()).thenReturn("us-east-1");
        when(regions.getRegion()).thenAnswer(invocation -> current.get());
        when(regions.getAccountId()).thenReturn(ACCOUNT);
        Map<String, Selection> selections = new LinkedHashMap<>();
        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = new AppConfigService(first, regions,
                    mock(AppConfigAlarmMonitor.class), mock(AppConfigS3Source.class));
            for (String region : List.of("us-east-1", "us-west-2", "cn-north-1")) {
                current.set(region);
                String app = service.createApplication(Map.of("Name", "same-app")).getId();
                String env = service.createEnvironment(app, Map.of("Name", "same-env", "Description", "old")).getId();
                String strategy = service.createDeploymentStrategy(Map.of("Name", "same-strategy", "GrowthFactor", 20,
                        "DeploymentDurationInMinutes", 10, "FinalBakeTimeInMinutes", 2)).getId();
                service.updateEnvironment(app, env, Map.of("Description", region, "Monitors", List.of()));
                service.updateDeploymentStrategy(strategy, Map.of("Description", region, "FinalBakeTimeInMinutes", 0));
                selections.put(region, new Selection(app, env, strategy));
            }
        } finally {
            first.shutdownAll();
        }
        StorageFactory foreign = StorageFactories.open(directory, mode, "741686000093");
        try {
            AppConfigService service = new AppConfigService(foreign, regions,
                    mock(AppConfigAlarmMonitor.class), mock(AppConfigS3Source.class));
            selections.forEach((region, selected) -> {
                current.set(region);
                assertEquals(404, assertThrows(AwsException.class,
                        () -> service.updateEnvironment(selected.app(), selected.env(), Map.of("Name", "foreign")))
                        .getHttpStatus());
                assertEquals(404, assertThrows(AwsException.class,
                        () -> service.updateDeploymentStrategy(selected.strategy(), Map.of("GrowthFactor", 50)))
                        .getHttpStatus());
            });
        } finally {
            foreign.shutdownAll();
        }
        StorageFactory restarted = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = new AppConfigService(restarted, regions,
                    mock(AppConfigAlarmMonitor.class), mock(AppConfigS3Source.class));
            selections.forEach((region, selected) -> {
                current.set(region);
                Environment environment = service.getEnvironment(selected.app(), selected.env());
                assertEquals("same-env", environment.getName());
                assertEquals(region, environment.getDescription());
                assertEquals(List.of(), environment.getMonitors());
                DeploymentStrategy strategy = service.getDeploymentStrategy(selected.strategy());
                assertEquals(region, strategy.getDescription());
                assertEquals(10, strategy.getDeploymentDurationInMinutes());
                assertEquals(20f, strategy.getGrowthFactor());
                assertEquals(0, strategy.getFinalBakeTimeInMinutes());
                selections.forEach((otherRegion, other) -> {
                    if (!region.equals(otherRegion)) {
                        assertEquals(404, assertThrows(AwsException.class,
                                () -> service.updateEnvironment(other.app(), other.env(), Map.of("Name", "foreign")))
                                .getHttpStatus());
                        assertEquals(404, assertThrows(AwsException.class,
                                () -> service.updateDeploymentStrategy(other.strategy(), Map.of("GrowthFactor", 50)))
                                .getHttpStatus());
                    }
                });
                service.deleteEnvironment(selected.app(), selected.env());
                service.deleteApplication(selected.app());
                service.deleteDeploymentStrategy(selected.strategy());
            });
        } finally {
            restarted.shutdownAll();
        }
    }

    private record Selection(String app, String env, String strategy) {}
}
