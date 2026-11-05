package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.appconfig.model.Monitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigServicePersistenceTest {

    private static final String ALARM_ARN = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration-errors";
    private static final String SECOND_ALARM_ARN = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration-latency";
    private static final String ALARM_ROLE_ARN = "arn:aws:iam::000000000000:role/appconfig-alarm-monitor";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    void stateStoredWithoutMonitorsOrDeploymentLocationsStillLoads() throws IOException {
        // Written the way earlier builds persisted these records: no Monitors, no ConfigurationLocationUri.
        Files.writeString(dir.resolve("appconfig-applications.json"), """
                {"000000000000/app1": {"Id": "app1", "Name": "stored-app", "Description": null, "tags": {}}}
                """);
        Files.writeString(dir.resolve("appconfig-environments.json"), """
                {"000000000000/env1": {"Id": "env1", "ApplicationId": "app1", "Name": "stored-env",
                  "Description": null, "State": "READY"}}
                """);
        Files.writeString(dir.resolve("appconfig-profiles.json"), """
                {"000000000000/prof1": {"Id": "prof1", "ApplicationId": "app1", "Name": "stored-profile",
                  "Description": null, "LocationUri": "hosted", "Type": null}}
                """);
        Files.writeString(dir.resolve("appconfig-deployments.json"), """
                {"000000000000/app1::env1::1": {"ApplicationId": "app1", "EnvironmentId": "env1",
                  "ConfigurationProfileId": "prof1", "DeploymentNumber": 1, "ConfigurationName": null,
                  "ConfigurationVersion": "1", "DeploymentStrategyId": "AppConfig.AllAtOnce",
                  "State": "COMPLETE", "Description": null}}
                """);

        AppConfigService service = new AppConfigService(newFactory(), mock(EmulatorConfig.class));

        Environment environment = service.getEnvironment("app1", "env1");
        assertEquals("stored-env", environment.getName());
        assertNull(environment.getMonitors());
        assertFalse(MAPPER.valueToTree(environment).has("Monitors"));

        Deployment deployment = service.getDeployment("app1", "env1", 1);
        assertEquals("prof1", deployment.getConfigurationProfileId());
        assertEquals("COMPLETE", deployment.getState());
        assertNull(deployment.getConfigurationLocationUri());
        assertFalse(MAPPER.valueToTree(deployment).has("ConfigurationLocationUri"));

        assertNull(service.getConfigurationProfile("app1", "prof1").getType());
    }

    @Test
    void monitorsAndDeploymentLocationsSurviveARestart() {
        StorageFactory first = newFactory();
        AppConfigService service = new AppConfigService(first, mock(EmulatorConfig.class));
        String appId = service.createApplication(Map.of("Name", "restarted-app")).getId();
        String monitoredId = service.createEnvironment(appId, Map.of("Name", "monitored", "Monitors", List.of(
                Map.of("AlarmArn", ALARM_ARN, "AlarmRoleArn", ALARM_ROLE_ARN),
                Map.of("AlarmArn", SECOND_ALARM_ARN)))).getId();
        String unmonitoredId = service.createEnvironment(appId, Map.of("Name", "unmonitored")).getId();
        String profileId = service.createConfigurationProfile(appId, Map.of(
                "Name", "service-settings", "LocationUri", "ssm-parameter://service-settings")).getId();
        int deploymentNumber = service.startDeployment(appId, monitoredId, Map.of(
                "ConfigurationProfileId", profileId,
                "ConfigurationVersion", "1",
                "DeploymentStrategyId", "AppConfig.AllAtOnce")).getDeploymentNumber();
        first.shutdownAll();

        AppConfigService restarted = new AppConfigService(newFactory(), mock(EmulatorConfig.class));

        assertEquals(List.of(new Monitor(ALARM_ARN, ALARM_ROLE_ARN), new Monitor(SECOND_ALARM_ARN, null)),
                restarted.getEnvironment(appId, monitoredId).getMonitors());
        assertNull(restarted.getEnvironment(appId, unmonitoredId).getMonitors());
        Deployment deployment = restarted.getDeployment(appId, monitoredId, deploymentNumber);
        assertEquals("service-settings", deployment.getConfigurationName());
        assertEquals("ssm-parameter://service-settings", deployment.getConfigurationLocationUri());
    }

    private StorageFactory newFactory() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(dir.toString());
        ServiceConfigAccess serviceConfigAccess = mock(ServiceConfigAccess.class);
        when(serviceConfigAccess.storageMode(anyString())).thenReturn("persistent");
        return new StorageFactory(config, serviceConfigAccess);
    }
}
