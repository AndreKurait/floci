package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationContent;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentSummary;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersion;
import io.github.hectorvent.floci.services.appconfig.model.Monitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppConfigServicePersistenceTest {

    private static final String ALARM_ARN = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration-errors";
    private static final String SECOND_ALARM_ARN = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration-latency";
    private static final String ALARM_ROLE_ARN = "arn:aws:iam::000000000000:role/appconfig-alarm-monitor";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    void deletingTheHighestHostedVersionDoesNotReuseItsNumberAfterRestart() {
        StorageFactory first = newFactory();
        String app;
        String profile;
        try {
            AppConfigService service = new AppConfigService(first, new RegionResolver("us-east-1", "000000000000"), mock(AppConfigAlarmMonitor.class),
                    mock(AppConfigS3Source.class));
            app = service.createApplication(Map.of("Name", "versions")).getId();
            profile = service.createConfigurationProfile(app, Map.of("Name", "settings", "LocationUri", "hosted")).getId();
            assertEquals(1, service.createHostedConfigurationVersion(app, profile, new byte[]{1}, "text/plain", null)
                    .getVersionNumber());
            assertEquals(2, service.createHostedConfigurationVersion(app, profile, new byte[]{2}, "text/plain", null)
                    .getVersionNumber());
            service.deleteHostedConfigurationVersion(app, profile, 2);
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = newFactory();
        try {
            AppConfigService service = new AppConfigService(second, new RegionResolver("us-east-1", "000000000000"), mock(AppConfigAlarmMonitor.class),
                    mock(AppConfigS3Source.class));
            assertEquals(3, service.createHostedConfigurationVersion(app, profile, new byte[]{3}, "text/plain", null)
                    .getVersionNumber());
            assertArrayEquals(new byte[]{1}, service.getHostedConfigurationVersion(app, profile, 1).getContent());
        } finally {
            second.shutdownAll();
        }
    }

    @Test
    void concurrentHostedVersionCreatesKeepEveryWritersContent() throws Exception {
        StorageFactory storage = newFactory();
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            AppConfigService service = new AppConfigService(storage, new RegionResolver("us-east-1", "000000000000"), mock(AppConfigAlarmMonitor.class),
                    mock(AppConfigS3Source.class));
            String app = service.createApplication(Map.of("Name", "versions")).getId();
            String profile = service.createConfigurationProfile(app, Map.of("Name", "settings", "LocationUri", "hosted"))
                    .getId();
            CountDownLatch ready = new CountDownLatch(16);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HostedConfigurationVersion>> responses = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                byte[] content = new byte[]{(byte) i};
                responses.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return service.createHostedConfigurationVersion(app, profile, content, "text/plain", null);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            HashSet<Integer> versions = new HashSet<>();
            for (int i = 0; i < responses.size(); i++) {
                HostedConfigurationVersion version = responses.get(i).get(5, TimeUnit.SECONDS);
                assertTrue(versions.add(version.getVersionNumber()), "Version number was allocated twice");
                assertArrayEquals(new byte[]{(byte) i},
                        service.getHostedConfigurationVersion(app, profile, version.getVersionNumber()).getContent());
            }
            assertEquals(16, service.listHostedConfigurationVersions(app, profile).size());
        } finally {
            storage.shutdownAll();
        }
    }

    @Test
    void stateStoredWithoutMonitorsDeploymentLocationsOrVersionCountersStillLoads() throws IOException {
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
        Files.writeString(dir.resolve("appconfig-versions.json"), """
                {"000000000000/app1::prof1::7": {"ApplicationId": "app1", "ConfigurationProfileId": "prof1",
                  "VersionNumber": 7, "Content": "Bw==", "ContentType": "text/plain"}}
                """);
        Files.writeString(dir.resolve("appconfig-deployments.json"), """
                {"000000000000/app1::env1::1": {"ApplicationId": "app1", "EnvironmentId": "env1",
                  "ConfigurationProfileId": "prof1", "DeploymentNumber": 1, "ConfigurationName": null,
                  "ConfigurationVersion": "1", "DeploymentStrategyId": "AppConfig.AllAtOnce",
                  "State": "COMPLETE", "Description": null}}
                """);

        AppConfigService service = new AppConfigService(newFactory(), new RegionResolver("us-east-1", "000000000000"), mock(AppConfigAlarmMonitor.class),
                mock(AppConfigS3Source.class));

        Environment environment = service.getEnvironment("app1", "env1");
        assertEquals("stored-env", environment.getName());
        assertNull(environment.getMonitors());
        assertFalse(MAPPER.valueToTree(environment).has("Monitors"));

        Deployment deployment = service.getDeployment("app1", "env1", 1);
        assertEquals("prof1", deployment.getConfigurationProfileId());
        assertEquals("COMPLETE", deployment.getState());
        assertNull(deployment.getConfigurationLocationUri());
        JsonNode deploymentJson = MAPPER.valueToTree(deployment);
        for (String unrecorded : List.of("ConfigurationLocationUri", "DeploymentDurationInMinutes",
                "FinalBakeTimeInMinutes", "GrowthFactor", "GrowthType")) {
            assertFalse(deploymentJson.has(unrecorded), unrecorded);
        }

        DeploymentSummary summary = service.listDeployments("app1", "env1", null, null).items().get(0);
        assertEquals("stored-profile", summary.getConfigurationName());
        assertEquals(0, summary.getDeploymentDurationInMinutes());
        assertEquals(10, summary.getFinalBakeTimeInMinutes());
        assertEquals(100f, summary.getGrowthFactor());
        assertEquals("LINEAR", summary.getGrowthType());

        assertNull(service.getConfigurationProfile("app1", "prof1").getType());
        assertArrayEquals(new byte[]{7}, service.getHostedConfigurationVersion("app1", "prof1", 7).getContent());
        assertEquals(8, service.createHostedConfigurationVersion("app1", "prof1", new byte[]{8}, "text/plain", null)
                .getVersionNumber());
    }

    @Test
    void deploymentStrategySettingsSurviveARestart() {
        StorageFactory first = newFactory();
        AppConfigService service = new AppConfigService(first, new RegionResolver("us-east-1", "000000000000"), mock(AppConfigAlarmMonitor.class),
                mock(AppConfigS3Source.class));
        String appId = service.createApplication(Map.of("Name", "restarted-app")).getId();
        String environmentId = service.createEnvironment(appId, Map.of("Name", "restarted-env")).getId();
        String profileId = service.createConfigurationProfile(appId, Map.of(
                "Name", "service-settings", "LocationUri", "hosted")).getId();
        String strategyId = service.createDeploymentStrategy(Map.of(
                "Name", "gradual", "DeploymentDurationInMinutes", 15, "FinalBakeTimeInMinutes", 5,
                "GrowthFactor", 25, "GrowthType", "EXPONENTIAL")).getId();
        service.startDeployment(appId, environmentId, Map.of(
                "ConfigurationProfileId", profileId,
                "ConfigurationVersion", "1",
                "DeploymentStrategyId", strategyId));
        first.shutdownAll();

        AppConfigService restarted = new AppConfigService(newFactory(), new RegionResolver("us-east-1", "000000000000"), mock(AppConfigAlarmMonitor.class),
                mock(AppConfigS3Source.class));
        // With the strategy gone, the settings can only come from the stored deployment.
        restarted.deleteDeploymentStrategy(strategyId);

        DeploymentSummary summary = restarted.listDeployments(appId, environmentId, null, null).items().get(0);
        assertEquals(15, summary.getDeploymentDurationInMinutes());
        assertEquals(5, summary.getFinalBakeTimeInMinutes());
        assertEquals(25f, summary.getGrowthFactor());
        assertEquals("EXPONENTIAL", summary.getGrowthType());
    }

    @Test
    void monitorsAndDeploymentLocationsSurviveARestart() {
        StorageFactory first = newFactory();
        AppConfigService service = new AppConfigService(first, new RegionResolver("us-east-1", "000000000000"), mock(AppConfigAlarmMonitor.class),
                mock(AppConfigS3Source.class));
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

        AppConfigService restarted = new AppConfigService(newFactory(), new RegionResolver("us-east-1", "000000000000"), mock(AppConfigAlarmMonitor.class),
                mock(AppConfigS3Source.class));

        assertEquals(List.of(new Monitor(ALARM_ARN, ALARM_ROLE_ARN), new Monitor(SECOND_ALARM_ARN, null)),
                restarted.getEnvironment(appId, monitoredId).getMonitors());
        assertNull(restarted.getEnvironment(appId, unmonitoredId).getMonitors());
        Deployment deployment = restarted.getDeployment(appId, monitoredId, deploymentNumber);
        assertEquals("service-settings", deployment.getConfigurationName());
        assertEquals("ssm-parameter://service-settings", deployment.getConfigurationLocationUri());
    }

    @Test
    void s3RollbackAndRestartUseTheContentAdmittedBeforeTheSourceFailed() {
        StorageFactory first = newFactory();
        RegionResolver regions = new RegionResolver("us-east-1", "000000000000");
        AppConfigS3Source source = mock(AppConfigS3Source.class);
        byte[] content = "{\"limit\":10}".getBytes(StandardCharsets.UTF_8);
        when(source.retrieve(any(), eq("source-one"))).thenReturn(new ConfigurationContent(content, "application/json"));
        when(source.retrieve(any(), eq("source-two"))).thenReturn(new ConfigurationContent(new byte[]{2}, "text/plain"));
        when(source.retrieve(any(), eq("missing"))).thenThrow(new AwsException("BadRequestException", "Denied", 400));
        String app;
        String profile;
        String environment;
        try {
            AppConfigService service = new AppConfigService(first, regions, mock(AppConfigAlarmMonitor.class), source);
            app = service.createApplication(Map.of("Name", "s3-configuration")).getId();
            environment = service.createEnvironment(app, Map.of("Name", "test")).getId();
            profile = service.createConfigurationProfile(app, Map.of("Name", "settings",
                    "LocationUri", "s3://configuration-source/settings.json",
                    "RetrievalRoleArn", ALARM_ROLE_ARN)).getId();
            String strategy = service.createDeploymentStrategy(Map.of("Name", "immediate",
                    "DeploymentDurationInMinutes", 0, "FinalBakeTimeInMinutes", 0,
                    "GrowthFactor", 100, "ReplicateTo", "NONE")).getId();
            service.startDeployment(app, environment, Map.of("ConfigurationProfileId", profile,
                    "ConfigurationVersion", "source-one", "DeploymentStrategyId", strategy));
            Deployment second = service.startDeployment(app, environment, Map.of("ConfigurationProfileId", profile,
                    "ConfigurationVersion", "source-two", "DeploymentStrategyId", strategy));
            assertThrows(AwsException.class, () -> service.startDeployment(app, environment,
                    Map.of("ConfigurationProfileId", profile, "ConfigurationVersion", "missing",
                            "DeploymentStrategyId", strategy)));
            assertEquals("source-two", service.getActiveVersion(environment, profile));
            assertEquals(2, service.listDeployments(app, environment, null, null).items().size());
            service.stopDeployment(app, environment, second.getDeploymentNumber(), true);
            assertEquals("source-one", service.getActiveVersion(environment, profile));
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = newFactory();
        AppConfigS3Source unavailable = mock(AppConfigS3Source.class);
        try {
            AppConfigService restarted = new AppConfigService(
                    second, regions, mock(AppConfigAlarmMonitor.class), unavailable);
            assertEquals("source-one", restarted.getActiveVersion(environment, profile));
            assertEquals(ALARM_ROLE_ARN, restarted.getConfigurationProfile(app, profile).getRetrievalRoleArn());
            assertArrayEquals(content, restarted.getS3ConfigurationContent(app, profile, "source-one").content());
            verifyNoInteractions(unavailable);
            restarted.deleteConfigurationProfile(app, profile);
            assertThrows(AwsException.class, () -> restarted.getS3ConfigurationContent(app, profile, "source-one"));
        } finally {
            second.shutdownAll();
        }
    }

    @Test
    void restartedProfilesStillEnforceTheirStoredSchemas() {
        String schema = "{\"type\":\"integer\"}";
        RegionResolver regions = new RegionResolver("us-east-1", "000000000000");
        StorageFactory first = newFactory();
        String app;
        String profile;
        String environment;
        String strategy;
        try {
            AppConfigService service = new AppConfigService(first, regions, mock(AppConfigAlarmMonitor.class),
                    mock(AppConfigS3Source.class));
            app = service.createApplication(Map.of("Name", "validated")).getId();
            environment = service.createEnvironment(app, Map.of("Name", "test")).getId();
            profile = service.createConfigurationProfile(app, Map.of("Name", "settings", "LocationUri", "hosted",
                    "Validators", List.of(Map.of("Type", "JSON_SCHEMA", "Content", schema)))).getId();
            strategy = service.createDeploymentStrategy(Map.of("Name", "immediate",
                    "DeploymentDurationInMinutes", 0, "FinalBakeTimeInMinutes", 0,
                    "GrowthFactor", 100, "ReplicateTo", "NONE")).getId();
            service.createHostedConfigurationVersion(app, profile, new byte[]{'1'}, "application/json", null);
            service.createHostedConfigurationVersion(app, profile, new byte[]{'"', 'x', '"'}, "application/json", null);
            service.startDeployment(app, environment, Map.of("ConfigurationProfileId", profile,
                    "ConfigurationVersion", "1", "DeploymentStrategyId", strategy));
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = newFactory();
        try {
            AppConfigService restarted = new AppConfigService(second, regions, mock(AppConfigAlarmMonitor.class),
                    mock(AppConfigS3Source.class));
            assertEquals(schema, restarted.getConfigurationProfile(app, profile).getValidators().getFirst().content());
            AwsException failure = assertThrows(AwsException.class, () -> restarted.startDeployment(app, environment,
                    Map.of("ConfigurationProfileId", profile, "ConfigurationVersion", "2",
                            "DeploymentStrategyId", strategy)));
            assertEquals("BadRequestException", failure.getErrorCode());
            assertEquals("1", restarted.getActiveVersion(environment, profile));
            assertEquals(1, restarted.listDeployments(app, environment, null, null).items().size());
            restarted.validateConfiguration(app, profile, "1");
        } finally {
            second.shutdownAll();
        }
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
