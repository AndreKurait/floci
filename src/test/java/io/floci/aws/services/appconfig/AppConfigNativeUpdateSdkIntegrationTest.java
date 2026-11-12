package io.floci.aws.services.appconfig;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.GetDeploymentStrategyResponse;
import software.amazon.awssdk.services.appconfig.model.GetEnvironmentResponse;
import software.amazon.awssdk.services.appconfig.model.Monitor;
import software.amazon.awssdk.services.appconfig.model.UpdateDeploymentStrategyResponse;
import software.amazon.awssdk.services.appconfig.model.UpdateEnvironmentResponse;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class AppConfigNativeUpdateSdkIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1"})
    void partialPatchesHaveStoredNativeReadbacksAndPreserveSiblings(String region) {
        try (AppConfigNativeUpdateFixture fixture = new AppConfigNativeUpdateFixture(endpoint, Region.of(region))) {
            AppConfigClient client = fixture.admin;
            String app = fixture.application;
            String env = fixture.environments.getFirst();
            String sibling = fixture.environments.get(1);
            EnvironmentView initial = environment(client.getEnvironment(r -> r.applicationId(app).environmentId(env)));
            EnvironmentView other = environment(client.getEnvironment(r -> r.applicationId(app).environmentId(sibling)));
            EnvironmentView renamed = new EnvironmentView(app, env, "renamed", initial.description(), initial.state(), initial.monitors());
            assertEquals(renamed, environment(client.updateEnvironment(r -> r.applicationId(app).environmentId(env).name("renamed"))));
            assertEquals(renamed, environment(client.getEnvironment(r -> r.applicationId(app).environmentId(env))));
            EnvironmentView empty = new EnvironmentView(app, env, "renamed", "", initial.state(), List.of());
            assertEquals(empty, environment(client.updateEnvironment(r -> r.applicationId(app).environmentId(env)
                    .description("").monitors(List.of()))));
            assertEquals(empty, environment(client.getEnvironment(r -> r.applicationId(app).environmentId(env))));
            assertEquals(other, environment(client.getEnvironment(r -> r.applicationId(app).environmentId(sibling))));
            invalid(() -> client.updateEnvironment(r -> r.applicationId(app).environmentId(env).name("").description("must-not-store")));
            assertEquals(empty, environment(client.getEnvironment(r -> r.applicationId(app).environmentId(env))));

            String strategy = fixture.strategies.getFirst();
            String otherStrategy = fixture.strategies.get(1);
            StrategyView original = strategy(client.getDeploymentStrategy(r -> r.deploymentStrategyId(strategy)));
            StrategyView siblingStrategy = strategy(client.getDeploymentStrategy(r -> r.deploymentStrategyId(otherStrategy)));
            StrategyView growth = new StrategyView(strategy, original.name(), original.description(), 10, 2, 40f, "LINEAR", "NONE");
            assertEquals(growth, strategy(client.updateDeploymentStrategy(r -> r.deploymentStrategyId(strategy).growthFactor(40f))));
            assertEquals(growth, strategy(client.getDeploymentStrategy(r -> r.deploymentStrategyId(strategy))));
            StrategyView zero = new StrategyView(strategy, original.name(), "", 10, 0, 40f, "LINEAR", "NONE");
            assertEquals(zero, strategy(client.updateDeploymentStrategy(r -> r.deploymentStrategyId(strategy)
                    .description("").finalBakeTimeInMinutes(0))));
            assertEquals(zero, strategy(client.getDeploymentStrategy(r -> r.deploymentStrategyId(strategy))));
            assertEquals(siblingStrategy, strategy(client.getDeploymentStrategy(r -> r.deploymentStrategyId(otherStrategy))));
            invalid(() -> client.updateDeploymentStrategy(r -> r.deploymentStrategyId(strategy).growthFactor(101f)
                    .description("must-not-store")));
            assertEquals(zero, strategy(client.getDeploymentStrategy(r -> r.deploymentStrategyId(strategy))));
            assertEquals(0, client.listDeploymentStrategies(r -> {}).items().stream().filter(row -> row.id().equals(strategy))
                    .findFirst().orElseThrow().finalBakeTimeInMinutes());
        }
    }

    @Test
    void wrongParentForeignScopeAndPredefinedStrategyRefusalsLeaveOwnedStateIntact() {
        try (AppConfigNativeUpdateFixture fixture = new AppConfigNativeUpdateFixture(endpoint, Region.US_EAST_1);
             AppConfigClient foreign = fixture.client("741686000094", "test");
             AppConfigNativeUpdateFixture west = new AppConfigNativeUpdateFixture(endpoint, Region.US_WEST_2)) {
            String app = fixture.application;
            String env = fixture.environments.getFirst();
            String strategy = fixture.strategies.getFirst();
            EnvironmentView before = environment(fixture.admin.getEnvironment(r -> r.applicationId(app).environmentId(env)));
            String otherApp = fixture.admin.createApplication(r -> r.name(fixture.prefix + "-other")).id();
            try {
                missing(() -> fixture.admin.updateEnvironment(r -> r.applicationId(otherApp).environmentId(env).description("no")));
            } finally {
                fixture.admin.deleteApplication(r -> r.applicationId(otherApp));
            }
            missing(() -> foreign.updateEnvironment(r -> r.applicationId(app).environmentId(env).description("no")));
            missing(() -> foreign.updateDeploymentStrategy(r -> r.deploymentStrategyId(strategy).growthFactor(50f)));
            missing(() -> west.admin.updateEnvironment(r -> r.applicationId(app).environmentId(env).description("no")));
            missing(() -> west.admin.updateDeploymentStrategy(r -> r.deploymentStrategyId(strategy).growthFactor(50f)));
            invalid(() -> fixture.admin.updateDeploymentStrategy(r -> r.deploymentStrategyId("AppConfig.AllAtOnce").growthFactor(20f)));
            assertEquals(before, environment(fixture.admin.getEnvironment(r -> r.applicationId(app).environmentId(env))));
            assertEquals(20f, fixture.admin.getDeploymentStrategy(r -> r.deploymentStrategyId(strategy)).growthFactor());
        }
    }

    private static EnvironmentView environment(GetEnvironmentResponse value) {
        return new EnvironmentView(value.applicationId(), value.id(), value.name(), value.description(),
                value.stateAsString(), value.monitors());
    }

    private static EnvironmentView environment(UpdateEnvironmentResponse value) {
        return new EnvironmentView(value.applicationId(), value.id(), value.name(), value.description(),
                value.stateAsString(), value.monitors());
    }

    private static StrategyView strategy(GetDeploymentStrategyResponse value) {
        return new StrategyView(value.id(), value.name(), value.description(), value.deploymentDurationInMinutes(),
                value.finalBakeTimeInMinutes(), value.growthFactor(), value.growthTypeAsString(), value.replicateToAsString());
    }

    private static StrategyView strategy(UpdateDeploymentStrategyResponse value) {
        return new StrategyView(value.id(), value.name(), value.description(), value.deploymentDurationInMinutes(),
                value.finalBakeTimeInMinutes(), value.growthFactor(), value.growthTypeAsString(), value.replicateToAsString());
    }

    private static void invalid(Runnable operation) {
        assertEquals(400, assertThrows(AppConfigException.class, operation::run).statusCode());
    }

    private static void missing(Runnable operation) {
        assertEquals(404, assertThrows(AppConfigException.class, operation::run).statusCode());
    }

    private record EnvironmentView(String application, String id, String name, String description,
                                    String state, List<Monitor> monitors) {}
    private record StrategyView(String id, String name, String description, int duration, int bake,
                                float growth, String growthType, String replicateTo) {}
}
