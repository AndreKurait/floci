package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentSummary;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.appconfig.model.Monitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppConfigServiceTest {
    private AccountAwareStorageBackend<Application> applicationStore;
    private AccountAwareStorageBackend<Environment> environmentStore;
    private AccountAwareStorageBackend<Deployment> deploymentStore;
    private AppConfigService service;

    @BeforeEach
    void setUp() {
        applicationStore = mock(AccountAwareStorageBackend.class);
        environmentStore = mock(AccountAwareStorageBackend.class);
        deploymentStore = mock(AccountAwareStorageBackend.class);
        Map<String, Deployment> deployments = new HashMap<>();
        doAnswer(invocation -> {
            deployments.put(invocation.getArgument(0, String.class), invocation.getArgument(1, Deployment.class));
            return null;
        }).when(deploymentStore).put(anyString(), any(Deployment.class));
        when(deploymentStore.scan(any())).thenAnswer(invocation -> List.copyOf(deployments.values()));
        StorageFactory storageFactory = mock(StorageFactory.class);
        doAnswer(invocation -> switch (invocation.getArgument(1, String.class)) {
            case "appconfig-applications.json" -> applicationStore;
            case "appconfig-environments.json" -> environmentStore;
            case "appconfig-deployments.json" -> deploymentStore;
            default -> mock(AccountAwareStorageBackend.class);
        }).when(storageFactory).create(anyString(), anyString(), any(TypeReference.class));

        Application application = new Application();
        application.setId("app");
        applicationStore.put("app", application);
        when(applicationStore.get("app")).thenReturn(Optional.of(application));
        Environment environment = new Environment();
        environment.setId("env");
        environment.setApplicationId("app");
        environmentStore.put("env", environment);
        when(environmentStore.get("env")).thenReturn(Optional.of(environment));
        service = new AppConfigService(storageFactory, mock(EmulatorConfig.class));
    }

    @Test
    void listDeploymentsReturnsDescendingPages() {
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        deploymentStore.put("app::env::2", deployment("app", "env", 2));

        AppConfigService.DeploymentPage page = service.listDeployments("app", "env", 1, null);

        assertEquals(List.of(2), page.items().stream().map(DeploymentSummary::getDeploymentNumber).toList());
        assertNotNull(page.nextToken());
    }

    @Test
    void listDeploymentsCursorSurvivesNewDeployment() {
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        deploymentStore.put("app::env::2", deployment("app", "env", 2));
        deploymentStore.put("app::env::3", deployment("app", "env", 3));

        AppConfigService.DeploymentPage firstPage = service.listDeployments("app", "env", 1, null);
        deploymentStore.put("app::env::4", deployment("app", "env", 4));

        AppConfigService.DeploymentPage secondPage = service.listDeployments(
                "app", "env", 1, firstPage.nextToken());

        assertEquals(List.of(2), secondPage.items().stream()
                .map(DeploymentSummary::getDeploymentNumber).toList());
    }

    @Test
    void listDeploymentsFiltersApplicationAndEnvironment() {
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        deploymentStore.put("other::env::2", deployment("other", "env", 2));
        deploymentStore.put("app::other-env::3", deployment("app", "other-env", 3));

        AppConfigService.DeploymentPage page = service.listDeployments("app", "env", null, null);

        assertEquals(List.of(1), page.items().stream().map(DeploymentSummary::getDeploymentNumber).toList());
    }

    @Test
    void listDeploymentsRejectsInvalidPageSize() {
        assertThrows(RuntimeException.class, () -> service.listDeployments("app", "env", 0, null));
        assertThrows(RuntimeException.class, () -> service.listDeployments("app", "env", 51, null));
    }

    @Test
    void listDeploymentsRejectsUnknownToken() {
        assertThrows(RuntimeException.class, () -> service.listDeployments("app", "env", 1, "unknown"));
    }

    @Test
    void environmentRetainsAnImmutableMonitorSnapshot() {
        Map<String, Object> monitor = new HashMap<>(Map.of(
                "AlarmArn", "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration",
                "AlarmRoleArn", "arn:aws:iam::000000000000:role/configuration-monitor"));
        List<Map<String, Object>> input = new ArrayList<>(List.of(monitor));
        Environment created = service.createEnvironment("app", Map.of("Name", "configuration", "Monitors", input));
        Monitor retained = created.getMonitors().getFirst();
        input.clear();
        monitor.put("AlarmArn", "changed");
        assertEquals(List.of(retained), created.getMonitors());
        assertEquals("arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration", retained.alarmArn());
        assertThrows(UnsupportedOperationException.class, () -> created.getMonitors().clear());
        assertEquals(List.of(), service.createEnvironment("app", Map.of("Name", "unmonitored")).getMonitors());
    }

    @Test
    void invalidMonitorsRefuseBeforeWritingEnvironment() {
        clearInvocations(environmentStore);
        List<Object> invalid = List.of("not-a-list", List.of("not-a-monitor"), List.of(Map.of()),
                List.of(Map.of("AlarmArn", "")), List.of(Map.of("AlarmArn", "alarm", "AlarmRoleArn", 1)),
                List.of(Map.of("AlarmArn", "alarm"), Map.of("AlarmArn", "alarm"), Map.of("AlarmArn", "alarm"),
                        Map.of("AlarmArn", "alarm"), Map.of("AlarmArn", "alarm"), Map.of("AlarmArn", "alarm")));
        for (Object value : invalid) {
            assertThrows(AwsException.class,
                    () -> service.createEnvironment("app", Map.of("Name", "invalid", "Monitors", value)));
        }
        verify(environmentStore, never()).put(anyString(), any());
    }

    @Test
    void environmentDeletionRefusesAnotherApplicationBeforeRemovingState() {
        assertThrows(AwsException.class, () -> service.deleteEnvironment("other", "env"));
        verify(environmentStore, never()).delete(anyString());
        verify(deploymentStore, never()).delete(anyString());
    }

    private static Deployment deployment(String applicationId, String environmentId, int number) {
        Deployment deployment = new Deployment();
        deployment.setApplicationId(applicationId);
        deployment.setEnvironmentId(environmentId);
        deployment.setDeploymentNumber(number);
        deployment.setConfigurationProfileId("profile");
        deployment.setConfigurationVersion("version");
        deployment.setDeploymentStrategyId("AppConfig.AllAtOnce");
        deployment.setState("COMPLETE");
        return deployment;
    }
}
