package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.AppConfigDataService.ConfigurationData;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentSummary;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersion;
import io.github.hectorvent.floci.testing.StorageFactories;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Timeout(30)
class AppConfigDeployedLabelStorageTest {
    private static final String ACCOUNT = "000000000000";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:000000000000:function:validator";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] ORIGINAL = "{\"release\":1}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CANDIDATE = "{\"release\":2}".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void capturedDeploymentLabelSurvivesRestartAndLabelReuse(String mode) {
        Selection selection;
        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = service(first);
            selection = selection(service);
            assertEquals(1, version(service, selection, "release-a", ORIGINAL));
            assertEquals(2, version(service, selection, "release-b", CANDIDATE));
            assertDeployment(start(service, selection, "release-a"), "1", "release-a");
            assertDeployment(start(service, selection, "release-b"), "2", "release-b");
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = service(second);
            assertDeployment(service.getDeployment(selection.app(), selection.environment(), 1), "1", "release-a");
            assertDeployment(service.getDeployment(selection.app(), selection.environment(), 2), "2", "release-b");
            service.deleteHostedConfigurationVersion(selection.app(), selection.profile(), 1);
            assertEquals(3, version(service, selection, "release-a", CANDIDATE));
            assertDeployment(service.getDeployment(selection.app(), selection.environment(), 1), "1", "release-a");
            assertDeployment(start(service, selection, "release-a"), "3", "release-a");
            assertEquals("3", service.getActiveVersion(selection.environment(), selection.profile()));
            Map<Integer, DeploymentSummary> summaries = service.listDeployments(
                    selection.app(), selection.environment(), null, null).items().stream()
                    .collect(Collectors.toMap(DeploymentSummary::getDeploymentNumber, value -> value));
            assertEquals("1", summaries.get(1).getConfigurationVersion());
            assertEquals("release-a", summaries.get(1).getVersionLabel());
            assertEquals("3", summaries.get(3).getConfigurationVersion());
            assertEquals("release-a", summaries.get(3).getVersionLabel());
            remove(service, selection);
        } finally {
            second.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void rollbackAfterRestartRestoresTheNumericVersionAndItsLabel(String mode) {
        Selection selection;
        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = service(first);
            selection = selection(service);
            version(service, selection, "release-a", ORIGINAL);
            version(service, selection, "release-b", CANDIDATE);
            start(service, selection, "release-a");
            start(service, selection, "release-b");
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = service(second);
            Deployment stopped = service.stopDeployment(selection.app(), selection.environment(), 2, true);
            assertDeployment(stopped, "2", "release-b");
            assertEquals("REVERTED", stopped.getState());
            assertEquals("1", service.getActiveVersion(selection.environment(), selection.profile()));
            AppConfigDataService data = data(second, service);
            ConfigurationData restored = data.getLatestConfiguration(session(data, selection));
            assertAll(() -> assertEquals("1", restored.configurationVersion()),
                    () -> assertEquals("release-a", restored.versionLabel()),
                    () -> assertArrayEquals(ORIGINAL, restored.content()),
                    () -> assertEquals("application/json", restored.contentType()));
            assertDeployment(service.getDeployment(selection.app(), selection.environment(), 1), "1", "release-a");
            remove(service, selection);
        } finally {
            second.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void legacyStoredDeploymentOmitsAnUnrecordedLabel(String mode) throws Exception {
        seed(mode, "appconfig-applications", """
                {"000000000000/app": {"Id":"app","Name":"legacy"}}
                """);
        seed(mode, "appconfig-environments", """
                {"000000000000/env": {"Id":"env","ApplicationId":"app","Name":"legacy","State":"READY"}}
                """);
        seed(mode, "appconfig-profiles", """
                {"000000000000/profile": {"Id":"profile","ApplicationId":"app","Name":"legacy","LocationUri":"hosted"}}
                """);
        seed(mode, "appconfig-versions", """
                {"000000000000/app::profile::1": {"ApplicationId":"app","ConfigurationProfileId":"profile",
                 "VersionNumber":1,"VersionLabel":"release-a","Content":"e30=","ContentType":"application/json"}}
                """);
        seed(mode, "appconfig-deployments", """
                {"000000000000/app::env::1": {"ApplicationId":"app","EnvironmentId":"env",
                 "ConfigurationProfileId":"profile","DeploymentNumber":1,"ConfigurationVersion":"1",
                 "DeploymentStrategyId":"AppConfig.AllAtOnce","State":"COMPLETE"}}
                """);
        StorageFactory factory = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = service(factory);
            assertEquals("release-a", service.getHostedConfigurationVersion("app", "profile", 1).getVersionLabel());
            Deployment stored = service.getDeployment("app", "env", 1);
            DeploymentSummary summary = service.listDeployments("app", "env", null, null).items().getFirst();
            JsonNode storedJson = MAPPER.valueToTree(stored);
            JsonNode summaryJson = MAPPER.valueToTree(summary);
            assertAll(() -> assertEquals("1", stored.getConfigurationVersion()),
                    () -> assertNull(stored.getVersionLabel()),
                    () -> assertNull(summary.getVersionLabel()),
                    () -> assertFalse(storedJson.has("VersionLabel")),
                    () -> assertFalse(summaryJson.has("VersionLabel")));
        } finally {
            factory.shutdownAll();
        }
    }

    @Test
    void emptyLabeledContentIsNewContentAndOnlyTheNextPollOmitsItsLabel() {
        StorageFactory factory = StorageFactories.open(directory, "memory", ACCOUNT);
        try {
            AppConfigService service = service(factory);
            Selection selection = selection(service);
            version(service, selection, "empty-release", new byte[0]);
            assertDeployment(start(service, selection, "empty-release"), "1", "empty-release");
            AppConfigDataService data = data(factory, service);
            AppConfigDataController controller = new AppConfigDataController(data, MAPPER);
            try (Response first = controller.getLatestConfiguration(session(data, selection), null)) {
                assertArrayEquals(new byte[0], (byte[]) first.getEntity());
                assertEquals("empty-release", first.getHeaderString("Version-Label"));
                assertEquals("application/json", first.getHeaderString("Content-Type"));
                String token = first.getHeaderString("Next-Poll-Configuration-Token");
                try (Response unchanged = controller.getLatestConfiguration(token, null)) {
                    assertArrayEquals(new byte[0], (byte[]) unchanged.getEntity());
                    assertNull(unchanged.getHeaderString("Version-Label"));
                    assertEquals("application/json", unchanged.getHeaderString("Content-Type"));
                }
            }
            remove(service, selection);
        } finally {
            factory.shutdownAll();
        }
    }

    @Test
    void selectedLabelReachesLambdaValidationBeforeDeploymentPublication() {
        StorageFactory factory = StorageFactories.open(directory, "memory", ACCOUNT);
        try {
            AppConfigAlarmMonitor alarms = mock(AppConfigAlarmMonitor.class);
            AppConfigLambdaValidator lambda = mock(AppConfigLambdaValidator.class);
            AppConfigService service = service(factory, alarms, lambda);
            Selection selection = selection(service);
            version(service, selection, "release-a", ORIGINAL);
            version(service, selection, "release-b", CANDIDATE);
            service.updateConfigurationProfile(selection.app(), selection.profile(),
                    Map.of("Validators", List.of(Map.of("Type", "LAMBDA", "Content", FUNCTION))));
            service.validateConfiguration(selection.app(), selection.profile(), "release-b");
            ArgumentCaptor<HostedConfigurationVersion> selected = ArgumentCaptor.forClass(HostedConfigurationVersion.class);
            verify(lambda).validate(eq(FUNCTION), any(ConfigurationProfile.class), selected.capture());
            assertEquals(2, selected.getValue().getVersionNumber());
            assertArrayEquals(CANDIDATE, selected.getValue().getContent());
            clearInvocations(lambda, alarms);
            doAnswer(invocation -> {
                assertEquals(List.of(), service.listDeployments(
                        selection.app(), selection.environment(), null, null).items());
                assertNull(service.getActiveVersion(selection.environment(), selection.profile()));
                return null;
            }).when(lambda).validate(eq(FUNCTION), any(ConfigurationProfile.class), any(HostedConfigurationVersion.class));
            assertDeployment(start(service, selection, "release-a"), "1", "release-a");
            InOrder order = inOrder(alarms, lambda);
            order.verify(alarms).validateMonitors(null);
            order.verify(lambda).validate(eq(FUNCTION), any(ConfigurationProfile.class), selected.capture());
            assertEquals(1, selected.getValue().getVersionNumber());
            assertArrayEquals(ORIGINAL, selected.getValue().getContent());
            assertEquals(1, service.listDeployments(selection.app(), selection.environment(), null, null).items().size());
            remove(service, selection);
        } finally {
            factory.shutdownAll();
        }
    }

    @Test
    void schemaFailureFromTheSelectedLabelLeavesDeploymentNumberAndActiveVersionUnchanged() {
        StorageFactory factory = StorageFactories.open(directory, "memory", ACCOUNT);
        try {
            AppConfigService service = service(factory);
            Selection selection = selection(service);
            version(service, selection, "release-a", ORIGINAL);
            version(service, selection, "invalid", "{\"release\":\"wrong\"}".getBytes(StandardCharsets.UTF_8));
            service.updateConfigurationProfile(selection.app(), selection.profile(), Map.of("Validators", List.of(Map.of(
                    "Type", "JSON_SCHEMA", "Content", "{\"type\":\"object\",\"properties\":{\"release\":{\"type\":\"integer\"}}}"))));
            assertDeployment(start(service, selection, "release-a"), "1", "release-a");
            JsonNode before = MAPPER.valueToTree(service.listDeployments(
                    selection.app(), selection.environment(), null, null).items());
            AwsException failure = assertThrows(AwsException.class, () -> start(service, selection, "invalid"));
            assertEquals("BadRequestException", failure.getErrorCode());
            assertEquals(400, failure.getHttpStatus());
            assertEquals(before, MAPPER.valueToTree(service.listDeployments(
                    selection.app(), selection.environment(), null, null).items()));
            assertEquals("1", service.getActiveVersion(selection.environment(), selection.profile()));
            assertEquals(2, start(service, selection, "release-a").getDeploymentNumber());
            remove(service, selection);
        } finally {
            factory.shutdownAll();
        }
    }

    private void seed(String mode, String name, String body) throws Exception {
        Files.writeString(directory.resolve(name + ("wal".equals(mode) ? "-snapshot.json" : ".json")), body);
    }

    private static AppConfigService service(StorageFactory factory) {
        return service(factory, mock(AppConfigAlarmMonitor.class), null);
    }

    private static AppConfigService service(StorageFactory factory, AppConfigAlarmMonitor alarms,
                                            AppConfigLambdaValidator lambda) {
        return new AppConfigService(factory, new RegionResolver("us-east-1", ACCOUNT),
                Clock.systemUTC(), alarms, null, lambda);
    }

    private static AppConfigDataService data(StorageFactory factory, AppConfigService service) {
        return new AppConfigDataService(factory, service, MAPPER, new RegionResolver("us-east-1", ACCOUNT));
    }

    private static Selection selection(AppConfigService service) {
        String app = service.createApplication(Map.of("Name", "labels")).getId();
        String environment = service.createEnvironment(app, Map.of("Name", "labels")).getId();
        String profile = service.createConfigurationProfile(app,
                Map.of("Name", "labels", "LocationUri", "hosted", "Type", "AWS.Freeform")).getId();
        String strategy = service.createDeploymentStrategy(Map.of("Name", "labels", "DeploymentDurationInMinutes", 0,
                "FinalBakeTimeInMinutes", 0, "GrowthFactor", 100, "GrowthType", "LINEAR", "ReplicateTo", "NONE")).getId();
        return new Selection(app, environment, profile, strategy);
    }

    private static int version(AppConfigService service, Selection selection, String label, byte[] content) {
        return service.createHostedConfigurationVersion(selection.app(), selection.profile(), content,
                "application/json", "label fixture", label, null).getVersionNumber();
    }

    private static Deployment start(AppConfigService service, Selection selection, String selector) {
        return service.startDeployment(selection.app(), selection.environment(), Map.of(
                "ConfigurationProfileId", selection.profile(), "ConfigurationVersion", selector,
                "DeploymentStrategyId", selection.strategy()));
    }

    private static String session(AppConfigDataService data, Selection selection) {
        return data.startConfigurationSession(Map.of("ApplicationIdentifier", selection.app(),
                "EnvironmentIdentifier", selection.environment(), "ConfigurationProfileIdentifier", selection.profile(),
                "RequiredMinimumPollIntervalInSeconds", 15));
    }

    private static void assertDeployment(Deployment deployment, String version, String label) {
        assertEquals(version, deployment.getConfigurationVersion());
        assertEquals(label, deployment.getVersionLabel());
    }

    private static void remove(AppConfigService service, Selection selection) {
        service.deleteEnvironment(selection.app(), selection.environment());
        service.deleteConfigurationProfile(selection.app(), selection.profile());
        service.deleteDeploymentStrategy(selection.strategy());
        service.deleteApplication(selection.app());
        assertEquals(List.of(), service.listApplications());
    }

    private record Selection(String app, String environment, String profile, String strategy) {}
}
