package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.appconfig.AppConfigDataService.ConfigurationData;
import io.floci.aws.services.appconfig.model.Application;
import io.floci.aws.services.appconfig.model.ConfigurationProfile;
import io.floci.aws.services.appconfig.model.Deployment;
import io.floci.aws.services.appconfig.model.DeploymentStrategy;
import io.floci.aws.services.appconfig.model.Environment;
import io.floci.aws.services.appconfig.model.HostedConfigurationVersionSummary;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@Timeout(30)
class AppConfigDeployedLabelScopeIntegrationTest {
    @Inject
    AppConfigService service;
    @Inject
    AppConfigDataService data;
    @Inject
    StorageFactory factory;
    @Inject
    RegionResolver regions;

    @ParameterizedTest
    @ValueSource(strings = {"account", "region"})
    void identicalHierarchyIdsResolveLabelsOnlyWithinTheActualRequestOwner(String dimension) {
        long number = ThreadLocalRandom.current().nextLong(770000000000L, 779999999998L);
        Scope source = new Scope(Long.toString(number), "us-east-1");
        Scope destination = "account".equals(dimension)
                ? new Scope(Long.toString(number + 1), source.region())
                : new Scope(source.account(), "us-west-2");
        String marker = "label-scope-" + UUID.randomUUID();
        try {
            Selection selection = RequestScopes.callAs(source.account(), source.region(), () -> {
                assertScope(source);
                assertEquals(List.of(), service.listApplications());
                Application app = service.createApplication(Map.of("Name", marker, "Description", marker));
                ConfigurationProfile profile = service.createConfigurationProfile(app.getId(),
                        Map.of("Name", marker, "Description", marker, "LocationUri", "hosted"));
                Environment environment = service.createEnvironment(app.getId(),
                        Map.of("Name", marker, "Description", marker));
                DeploymentStrategy strategy = service.createDeploymentStrategy(Map.of("Name", marker, "Description", marker,
                        "DeploymentDurationInMinutes", 0, "FinalBakeTimeInMinutes", 0,
                        "GrowthFactor", 100, "GrowthType", "LINEAR", "ReplicateTo", "NONE"));
                Selection selected = new Selection(app, profile, environment, strategy);
                assertEquals(1, version(selected, "release-a", new byte[]{1}));
                return selected;
            });
            RequestScopes.runAs(destination.account(), destination.region(), () -> {
                assertScope(destination);
                assertEquals(List.of(), service.listApplications());
                new AppConfigStorage<Application>(factory, regions, "appconfig",
                        "appconfig-applications.json", new TypeReference<>() {})
                        .put(selection.app().getId(), selection.app());
                new AppConfigStorage<ConfigurationProfile>(factory, regions, "appconfig",
                        "appconfig-profiles.json", new TypeReference<>() {})
                        .put(selection.profile().getId(), selection.profile());
                new AppConfigStorage<Environment>(factory, regions, "appconfig",
                        "appconfig-environments.json", new TypeReference<>() {})
                        .put(selection.environment().getId(), selection.environment());
                new AppConfigStorage<DeploymentStrategy>(factory, regions, "appconfig",
                        "appconfig-strategies.json", new TypeReference<>() {})
                        .put(selection.strategy().getId(), selection.strategy());
                assertMissing(() -> service.validateConfiguration(
                        selection.app().getId(), selection.profile().getId(), "release-a"));
                assertMissing(() -> deploy(selection));
                assertEquals(List.of(), service.listDeployments(selection.app().getId(),
                        selection.environment().getId(), null, null).items());
                assertNull(service.getActiveVersion(selection.environment().getId(), selection.profile().getId()));
                assertEquals(1, version(selection, null, new byte[]{2}));
                assertEquals(2, version(selection, "release-a", new byte[]{3}));
                assertSelected(selection, "2", new byte[]{3});
            });
            RequestScopes.runAs(source.account(), source.region(), () -> {
                assertScope(source);
                assertSelected(selection, "1", new byte[]{1});
            });
        } finally {
            List<Executable> cleanup = List.of(destination, source).stream().map(scope -> (Executable) () ->
                RequestScopes.runAs(scope.account(), scope.region(), () -> {
                    assertScope(scope);
                    cleanupOwned(marker);
                    assertEquals(List.of(), service.listApplications());
                })).toList();
            assertAll("Both independently scoped hierarchies are removed", cleanup);
        }
    }

    private void cleanupOwned(String marker) {
        assertAll("Owned applications and strategies are discovered and attempted independently", () -> {
            List<Executable> applications = new ArrayList<>();
            for (Application app : service.listApplications()) {
                if (marker.equals(app.getName()) && marker.equals(app.getDescription())) {
                    applications.add(() -> cleanupApplication(app, marker));
                }
            }
            assertAll(applications);
        }, () -> {
            List<Executable> strategies = new ArrayList<>();
            for (DeploymentStrategy strategy : service.listDeploymentStrategies()) {
                if (marker.equals(strategy.getName()) && marker.equals(strategy.getDescription())) {
                    strategies.add(() -> {
                        service.deleteDeploymentStrategy(strategy.getId());
                        assertMissing(() -> service.getDeploymentStrategy(strategy.getId()));
                    });
                }
            }
            assertAll(strategies);
        });
    }

    private void cleanupApplication(Application app, String marker) {
        assertAll("Every child is discovered and attempted before removing its application", () -> {
            List<Executable> environments = new ArrayList<>();
            for (Environment environment : service.listEnvironments(app.getId())) {
                environments.add(() -> {
                    assertEquals(marker, environment.getName());
                    assertEquals(marker, environment.getDescription());
                    service.deleteEnvironment(app.getId(), environment.getId());
                    assertMissing(() -> service.getEnvironment(app.getId(), environment.getId()));
                });
            }
            assertAll(environments);
        }, () -> {
            List<Executable> profiles = new ArrayList<>();
            for (ConfigurationProfile profile : service.listConfigurationProfiles(app.getId())) {
                profiles.add(() -> cleanupProfile(app, profile, marker));
            }
            assertAll(profiles);
        });
        assertEquals(List.of(), service.listEnvironments(app.getId()));
        assertEquals(List.of(), service.listConfigurationProfiles(app.getId()));
        service.deleteApplication(app.getId());
        assertFalse(service.findApplication(app.getId()).isPresent());
    }

    private void cleanupProfile(Application app, ConfigurationProfile profile, String marker) {
        assertEquals(marker, profile.getName());
        assertEquals(marker, profile.getDescription());
        List<Executable> versions = new ArrayList<>();
        for (HostedConfigurationVersionSummary version :
                service.listHostedConfigurationVersions(app.getId(), profile.getId())) {
            versions.add(() -> {
                service.deleteHostedConfigurationVersion(app.getId(), profile.getId(), version.getVersionNumber());
                assertMissing(() -> service.getHostedConfigurationVersion(
                        app.getId(), profile.getId(), version.getVersionNumber()));
            });
        }
        assertAll("Each hosted version is removed before its profile", versions);
        assertEquals(List.of(), service.listHostedConfigurationVersions(app.getId(), profile.getId()));
        service.deleteConfigurationProfile(app.getId(), profile.getId());
        assertMissing(() -> service.getConfigurationProfile(app.getId(), profile.getId()));
    }

    private void assertSelected(Selection selection, String number, byte[] content) {
        service.validateConfiguration(selection.app().getId(), selection.profile().getId(), "release-a");
        Deployment deployment = deploy(selection);
        assertEquals(1, deployment.getDeploymentNumber());
        assertEquals(number, deployment.getConfigurationVersion());
        assertEquals("release-a", deployment.getVersionLabel());
        String token = data.startConfigurationSession(Map.of("ApplicationIdentifier", selection.app().getId(),
                "EnvironmentIdentifier", selection.environment().getId(),
                "ConfigurationProfileIdentifier", selection.profile().getId()));
        ConfigurationData response = data.getLatestConfiguration(token);
        assertArrayEquals(content, response.content());
        assertEquals(number, response.configurationVersion());
        assertEquals("release-a", response.versionLabel());
    }

    private Deployment deploy(Selection selection) {
        return service.startDeployment(selection.app().getId(), selection.environment().getId(), Map.of(
                "ConfigurationProfileId", selection.profile().getId(), "ConfigurationVersion", "release-a",
                "DeploymentStrategyId", selection.strategy().getId()));
    }

    private int version(Selection selection, String label, byte[] content) {
        return service.createHostedConfigurationVersion(selection.app().getId(), selection.profile().getId(),
                content, "application/octet-stream", "owner content", label, null).getVersionNumber();
    }

    private void assertScope(Scope scope) {
        assertEquals(scope.account(), regions.getAccountId());
        assertEquals(scope.region(), regions.getRegion());
    }

    private static void assertMissing(Executable request) {
        AwsException failure = assertThrows(AwsException.class, request);
        assertEquals("ResourceNotFoundException", failure.getErrorCode());
        assertEquals(404, failure.getHttpStatus());
    }

    private record Scope(String account, String region) {}
    private record Selection(Application app, ConfigurationProfile profile, Environment environment,
                             DeploymentStrategy strategy) {}
}
