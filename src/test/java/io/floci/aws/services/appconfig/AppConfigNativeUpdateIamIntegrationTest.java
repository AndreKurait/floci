package io.floci.aws.services.appconfig;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class AppConfigNativeUpdateIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void environmentPatchNeedsItsActionAndBothConcreteParentAndChildResources() {
        try (AppConfigNativeUpdateFixture fixture = new AppConfigNativeUpdateFixture(endpoint, Region.US_EAST_1)) {
            String app = fixture.application;
            String env = fixture.environments.getFirst();
            try (AppConfigClient caller = caller(fixture, "")) {
                denied(() -> caller.updateEnvironment(r -> r.applicationId(app).environmentId(env).description("denied")));
                denied(() -> caller.updateDeploymentStrategy(r -> r.deploymentStrategyId(fixture.strategies.getFirst())
                        .growthFactor(30f)));
            }
            for (String resource : List.of(fixture.applicationArn(), fixture.environmentArn(0))) {
                try (AppConfigClient caller = caller(fixture, statement("Allow", "appconfig:UpdateEnvironment", resource))) {
                    denied(() -> caller.updateEnvironment(r -> r.applicationId(app).environmentId(env).description("denied")));
                }
            }
            String taggedAllow = "{\"Effect\":\"Allow\",\"Action\":\"appconfig:UpdateEnvironment\","
                    + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/Owner\":\"team\"}}}";
            try (AppConfigClient caller = caller(fixture, taggedAllow)) {
                denied(() -> caller.updateEnvironment(r -> r.applicationId(app).environmentId(env).description("denied")));
            }
            String scoped = statement("Allow", "appconfig:UpdateEnvironment", fixture.applicationArn()) + ","
                    + statement("Allow", "appconfig:UpdateEnvironment", fixture.environmentArn(0));
            try (AppConfigClient caller = caller(fixture, scoped)) {
                caller.updateEnvironment(r -> r.applicationId(app).environmentId(env).description("allowed"));
                denied(() -> caller.updateEnvironment(r -> r.applicationId(app).environmentId(fixture.environments.get(1))
                        .description("denied")));
                denied(() -> caller.updateDeploymentStrategy(r -> r.deploymentStrategyId(fixture.strategies.getFirst()).growthFactor(30f)));
            }
            try (AppConfigClient caller = caller(fixture, statement("Allow", "appconfig:*", "*") + ","
                    + statement("Deny", "appconfig:UpdateEnvironment", fixture.environmentArn(0)))) {
                denied(() -> caller.updateEnvironment(r -> r.applicationId(app).environmentId(env).description("denied")));
                caller.updateEnvironment(r -> r.applicationId(app).environmentId(fixture.environments.get(1)).description("other"));
            }
            assertEquals("allowed", fixture.admin.getEnvironment(r -> r.applicationId(app).environmentId(env)).description());
        }
    }

    @Test
    void strategyPatchHonorsActionResourceAndExplicitDenial() {
        try (AppConfigNativeUpdateFixture fixture = new AppConfigNativeUpdateFixture(endpoint, Region.US_EAST_1)) {
            String target = fixture.strategies.getFirst();
            String other = fixture.strategies.get(1);
            try (AppConfigClient caller = caller(fixture,
                    statement("Allow", "appconfig:UpdateDeploymentStrategy", fixture.strategyArn(0)))) {
                assertEquals(40f, caller.updateDeploymentStrategy(r -> r.deploymentStrategyId(target).growthFactor(40f)).growthFactor());
                denied(() -> caller.updateDeploymentStrategy(r -> r.deploymentStrategyId(other).growthFactor(40f)));
                denied(() -> caller.updateEnvironment(r -> r.applicationId(fixture.application)
                        .environmentId(fixture.environments.getFirst()).description("denied")));
            }
            try (AppConfigClient caller = caller(fixture, statement("Allow", "appconfig:*", "*") + ","
                    + statement("Deny", "appconfig:UpdateDeploymentStrategy", fixture.strategyArn(0)))) {
                denied(() -> caller.updateDeploymentStrategy(r -> r.deploymentStrategyId(target).growthFactor(50f)));
                caller.updateDeploymentStrategy(r -> r.deploymentStrategyId(other).growthFactor(50f));
            }
            assertEquals(40f, fixture.admin.getDeploymentStrategy(r -> r.deploymentStrategyId(target)).growthFactor());
            assertEquals(50f, fixture.admin.getDeploymentStrategy(r -> r.deploymentStrategyId(other)).growthFactor());
        }
    }

    @Test
    void readPermissionsCannotAuthorizePatchThroughAConflictingTargetHeader() {
        try (AppConfigNativeUpdateFixture fixture = new AppConfigNativeUpdateFixture(endpoint, Region.US_EAST_1);
                AppConfigClient caller = caller(fixture, statement("Allow", "appconfig:GetEnvironment", "*") + ","
                        + statement("Allow", "appconfig:GetDeploymentStrategy", "*"))) {
            String app = fixture.application;
            String env = fixture.environments.getFirst();
            String strategy = fixture.strategies.getFirst();
            String environmentBefore = fixture.admin.getEnvironment(r -> r.applicationId(app).environmentId(env)).description();
            String strategyBefore = fixture.admin.getDeploymentStrategy(r -> r.deploymentStrategyId(strategy)).description();
            denied(() -> caller.updateEnvironment(r -> r.applicationId(app).environmentId(env).description("denied")
                    .overrideConfiguration(c -> c.putHeader("X-Amz-Target", "AppConfig.GetEnvironment"))));
            denied(() -> caller.updateDeploymentStrategy(r -> r.deploymentStrategyId(strategy).description("denied")
                    .overrideConfiguration(c -> c.putHeader("X-Amz-Target", "AppConfig.GetDeploymentStrategy"))));
            assertEquals(environmentBefore, fixture.admin.getEnvironment(r -> r.applicationId(app).environmentId(env)).description());
            assertEquals(strategyBefore, fixture.admin.getDeploymentStrategy(r -> r.deploymentStrategyId(strategy)).description());
        }
    }

    @Test
    void suppliedMonitorRolesNeedPassRoleButOmittedAndEmptyMonitorsDoNot() {
        try (AppConfigNativeUpdateFixture fixture = new AppConfigNativeUpdateFixture(endpoint, Region.US_EAST_1)) {
            String app = fixture.application;
            String env = fixture.environments.getFirst();
            String update = statement("Allow", "appconfig:UpdateEnvironment", fixture.applicationArn()) + ","
                    + statement("Allow", "appconfig:UpdateEnvironment", fixture.environmentArn(0));
            try (AppConfigClient caller = caller(fixture, update)) {
                caller.updateEnvironment(r -> r.applicationId(app).environmentId(env).description("omitted"));
                denied(() -> caller.updateEnvironment(r -> r.applicationId(app).environmentId(env).description("must-not-store")
                        .monitors(fixture.monitors(1))));
                assertEquals("omitted", fixture.admin.getEnvironment(r -> r.applicationId(app).environmentId(env)).description());
                assertEquals(fixture.monitors(0), fixture.admin.getEnvironment(r -> r.applicationId(app).environmentId(env)).monitors());
                caller.updateEnvironment(r -> r.applicationId(app).environmentId(env).monitors(List.of()));
            }
            String pass = "{\"Effect\":\"Allow\",\"Action\":\"iam:PassRole\",\"Resource\":\"" + fixture.roles.get(0)
                    + "\",\"Condition\":{\"StringEquals\":{\"iam:PassedToService\":\"appconfig.amazonaws.com\"}}}";
            try (AppConfigClient caller = caller(fixture, update + "," + pass)) {
                caller.updateEnvironment(r -> r.applicationId(app).environmentId(env).monitors(fixture.monitors(0)));
                denied(() -> caller.updateEnvironment(r -> r.applicationId(app).environmentId(env)
                        .monitors(fixture.monitors(0).getFirst(), fixture.monitors(1).getFirst())));
                assertEquals(fixture.monitors(0), fixture.admin.getEnvironment(r -> r.applicationId(app).environmentId(env)).monitors());
            }
        }
    }

    private AppConfigClient caller(AppConfigNativeUpdateFixture fixture, String statements) {
        String name = "patch-user-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth("test", "iam"), name);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("test", "iam"), name, "patch",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
        return fixture.client(credentials.accessKeyId(), credentials.secretAccessKey());
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static void denied(Runnable operation) {
        assertEquals(403, assertThrows(AppConfigException.class, operation::run).statusCode());
    }
}
