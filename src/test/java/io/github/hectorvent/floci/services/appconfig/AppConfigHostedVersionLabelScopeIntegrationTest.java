package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersionSummary;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Uses the injected request scope and identical resource IDs to isolate each cursor ownership dimension. */
@QuarkusTest
@Timeout(30)
class AppConfigHostedVersionLabelScopeIntegrationTest {
    @Inject
    AppConfigService service;
    @Inject
    StorageFactory factory;
    @Inject
    RegionResolver regions;

    @ParameterizedTest
    @ValueSource(strings = {"account", "region"})
    void identicalResourceIdsCannotTransferAFilteredCursorBetweenOwners(String dimension) {
        long number = ThreadLocalRandom.current().nextLong(770000000000L, 779999999998L);
        Scope source = new Scope(Long.toString(number), "us-east-1");
        Scope destination = "account".equals(dimension)
                ? new Scope(Long.toString(number + 1), source.region())
                : new Scope(source.account(), "us-west-2");
        String marker = UUID.randomUUID().toString();
        String appId = RequestScopes.callAs(source.account(), source.region(), () -> {
            assertScope(source);
            assertEquals(List.of(), service.listApplications());
            return service.createApplication(Map.of("Name", marker, "Description", marker)).getId();
        });
        String[] profileId = new String[1];
        try {
            Selection selection = RequestScopes.callAs(source.account(), source.region(), () -> {
                assertScope(source);
                ConfigurationProfile profile = service.createConfigurationProfile(appId,
                        Map.of("Name", marker, "Description", marker, "LocationUri", "hosted"));
                profileId[0] = profile.getId();
                Selection created = new Selection(service.getApplication(appId), profile);
                populate(created);
                return created;
            });
            String token = RequestScopes.callAs(source.account(), source.region(), () -> {
                assertScope(source);
                return service.listHostedConfigurationVersionsPage(
                        appId, profileId[0], 1, null, "release-*").nextToken();
            });
            assertNotNull(token);
            assertFalse(token.isEmpty());
            RequestScopes.runAs(destination.account(), destination.region(), () -> {
                assertScope(destination);
                assertEquals(List.of(), service.listApplications());
                new AppConfigStorage<Application>(factory, regions, "appconfig",
                        "appconfig-applications.json", new TypeReference<>() {})
                        .put(appId, selection.application());
                new AppConfigStorage<ConfigurationProfile>(factory, regions, "appconfig",
                        "appconfig-profiles.json", new TypeReference<>() {})
                        .put(profileId[0], selection.profile());
                populate(selection);
                AwsException failure = assertThrows(AwsException.class,
                        () -> service.listHostedConfigurationVersionsPage(
                                appId, profileId[0], 1, token, "release-*"));
                assertEquals("BadRequestException", failure.getErrorCode());
                assertEquals(400, failure.getHttpStatus());
                assertEquals(List.of(1, 2), service.listHostedConfigurationVersions(appId, profileId[0])
                        .stream().map(HostedConfigurationVersionSummary::getVersionNumber).toList());
            });
            RequestScopes.runAs(source.account(), source.region(), () -> {
                assertScope(source);
                assertEquals(List.of(2), service.listHostedConfigurationVersionsPage(
                        appId, profileId[0], 1, token, "release-*").items()
                        .stream().map(HostedConfigurationVersionSummary::getVersionNumber).toList());
            });
        } finally {
            List<Executable> cleanup = List.of(destination, source).stream().map(scope -> (Executable) () ->
                RequestScopes.runAs(scope.account(), scope.region(), () -> {
                    assertScope(scope);
                    if (service.findApplication(appId).isPresent()) {
                        assertEquals(marker, service.getApplication(appId).getDescription());
                        if (profileId[0] != null && service.findConfigurationProfile(profileId[0]).isPresent()) {
                            assertEquals(marker, service.getConfigurationProfile(appId, profileId[0]).getDescription());
                            service.deleteConfigurationProfile(appId, profileId[0]);
                            assertFalse(service.findConfigurationProfile(profileId[0]).isPresent());
                        }
                        service.deleteApplication(appId);
                        assertFalse(service.findApplication(appId).isPresent());
                    }
                    assertEquals(List.of(), service.listApplications());
                })).toList();
            assertAll("Both owned scopes are removed", cleanup);
        }
    }

    private void assertScope(Scope scope) {
        assertEquals(scope.account(), regions.getAccountId());
        assertEquals(scope.region(), regions.getRegion());
    }

    private void populate(Selection selection) {
        for (int version = 1; version <= 2; version++) {
            assertEquals(version, service.createHostedConfigurationVersion(
                    selection.application().getId(), selection.profile().getId(), new byte[]{0, -1},
                    "application/octet-stream", "scope-content", "release-" + version, version - 1)
                    .getVersionNumber());
        }
    }

    private record Scope(String account, String region) {}
    private record Selection(Application application, ConfigurationProfile profile) {}
}
