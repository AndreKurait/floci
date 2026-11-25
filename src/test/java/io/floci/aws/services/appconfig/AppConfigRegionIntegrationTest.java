package io.floci.aws.services.appconfig;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class AppConfigRegionIntegrationTest {
    @Inject
    AppConfigService service;

    @Inject
    AppConfigDataService data;

    @Inject
    RegionResolver regions;

    @Inject
    AppConfigResourceArns arns;

    @Test
    void identicalNamesResolveOnlyWithinTheirAccountAndRegion() {
        String name = "regional-" + UUID.randomUUID();
        Map<Scope, String> applications = new LinkedHashMap<>();
        try {
            for (Scope scope : List.of(
                    new Scope("111111111111", regions.getDefaultRegion()),
                    new Scope("111111111111", "us-west-2"),
                    new Scope("222222222222", regions.getDefaultRegion()),
                    new Scope("111111111111", "cn-north-1"))) {
                applications.put(scope, RequestScopes.callAs(scope.account(), scope.region(),
                        () -> service.createApplication(Map.of("Name", name)).getId()));
            }
            applications.forEach((scope, id) -> RequestScopes.runAs(scope.account(), scope.region(), () -> {
                assertEquals(id, service.resolveApplication(name).getId());
                assertEquals(List.of(id), service.listApplications().stream()
                        .filter(application -> name.equals(application.getName()))
                        .map(application -> application.getId()).toList());
                applications.values().stream().filter(other -> !other.equals(id)).forEach(other ->
                        assertEquals("ResourceNotFoundException",
                                assertThrows(AwsException.class, () -> service.getApplication(other)).getErrorCode()));
            }));
        } finally {
            applications.forEach((scope, id) -> RequestScopes.runAs(scope.account(), scope.region(),
                    () -> service.deleteApplication(id)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1", "us-gov-west-1"})
    void foreignAccountOrRegionCannotAuthorizeOrConsumeAConfigurationSession(String region) {
        String account = "333333333333";
        RequestScopes.runAs(account, region, () -> {
            String app = service.createApplication(Map.of("Name", "session-" + UUID.randomUUID())).getId();
            String env = service.createEnvironment(app, Map.of("Name", "environment")).getId();
            String profile = service.createConfigurationProfile(app,
                    Map.of("Name", "profile", "LocationUri", "hosted")).getId();
            try {
                String token = data.startConfigurationSession(Map.of(
                        "ApplicationIdentifier", app, "EnvironmentIdentifier", env,
                        "ConfigurationProfileIdentifier", profile));
                String configuration = regions.buildArn("appconfig", region, "application/" + app
                        + "/environment/" + env + "/configuration/" + profile);
                assertEquals(List.of(configuration), arns.through("ConfigurationToken", token, region));
                for (Scope scope : List.of(new Scope(account, "us-west-2"), new Scope("444455556666", region))) {
                    RequestScopes.runAs(scope.account(), scope.region(), () -> {
                        assertEquals("BadRequestException", assertThrows(AwsException.class,
                                () -> arns.through("ConfigurationToken", token, scope.region())).getErrorCode());
                        assertEquals("BadRequestException",
                                assertThrows(AwsException.class, () -> data.getLatestConfiguration(token)).getErrorCode());
                    });
                }
                assertEquals(List.of(configuration), arns.through("ConfigurationToken", token, region));
                assertEquals(0, data.getLatestConfiguration(token).content().length);
            } finally {
                service.deleteEnvironment(app, env);
                service.deleteConfigurationProfile(app, profile);
                service.deleteApplication(app);
            }
        });
    }

    private record Scope(String account, String region) {}
}
