package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.GetDeploymentResponse;
import software.amazon.awssdk.services.appconfig.model.StartDeploymentResponse;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(AppConfigDeploymentTimeScaleIntegrationTest.FastTiming.class)
class AppConfigDeploymentTimeScaleIntegrationTest {

    @Test
    void configuredTimeScaleCompletesNativeMinuteStrategyAndServesItsContent() {
        try (AppConfigClient control = AwsTestClients.create(AppConfigClient.builder(), "test", Region.US_EAST_1);
             AppConfigDataClient data = AwsTestClients.create(AppConfigDataClient.builder(), "test", Region.US_EAST_1)) {
            List<Executable> cleanup = new ArrayList<>();
            try {
                String application = control.createApplication(r -> r.name("timing-" + UUID.randomUUID())).id();
                cleanup.add(() -> control.deleteApplication(r -> r.applicationId(application)));
                String environment = control.createEnvironment(r -> r.applicationId(application).name("local")).id();
                cleanup.add(() -> control.deleteEnvironment(r -> r.applicationId(application)
                        .environmentId(environment).deletionProtectionCheck("BYPASS")));
                String profile = control.createConfigurationProfile(r -> r.applicationId(application)
                        .name("settings").locationUri("hosted")).id();
                cleanup.add(() -> control.deleteConfigurationProfile(r -> r.applicationId(application)
                        .configurationProfileId(profile).deletionProtectionCheck("BYPASS")));
                String content = "{\"enabled\":true}";
                int version = control.createHostedConfigurationVersion(r -> r.applicationId(application)
                        .configurationProfileId(profile).contentType("application/json")
                        .content(SdkBytes.fromUtf8String(content))).versionNumber();
                cleanup.add(() -> control.deleteHostedConfigurationVersion(r -> r.applicationId(application)
                        .configurationProfileId(profile).versionNumber(version)));
                String strategy = control.createDeploymentStrategy(r -> r.name("minutes-" + UUID.randomUUID())
                        .deploymentDurationInMinutes(1).finalBakeTimeInMinutes(1).growthFactor(20f)
                        .growthType("LINEAR").replicateTo("NONE")).id();
                cleanup.add(() -> control.deleteDeploymentStrategy(r -> r.deploymentStrategyId(strategy)));
                StartDeploymentResponse started = control.startDeployment(r -> r.applicationId(application)
                        .environmentId(environment).configurationProfileId(profile)
                        .configurationVersion(Integer.toString(version)).deploymentStrategyId(strategy));
                cleanup.add(() -> control.stopDeployment(r -> r.applicationId(application)
                        .environmentId(environment).deploymentNumber(started.deploymentNumber()).allowRevert(true)));
                assertEquals("DEPLOYING", started.stateAsString());
                assertEquals(1, started.deploymentDurationInMinutes());
                assertEquals(1, started.finalBakeTimeInMinutes());
                await().atMost(Duration.ofSeconds(12)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                    GetDeploymentResponse completed = control.getDeployment(r -> r.applicationId(application)
                            .environmentId(environment).deploymentNumber(started.deploymentNumber()));
                    assertEquals("COMPLETE", completed.stateAsString());
                    assertEquals(1, completed.deploymentDurationInMinutes());
                    assertEquals(1, completed.finalBakeTimeInMinutes());
                    assertNotNull(completed.completedAt());
                    assertTrue(Duration.between(completed.startedAt(), completed.completedAt())
                            .compareTo(Duration.ofSeconds(2)) < 0);
                });
                String token = data.startConfigurationSession(r -> r.applicationIdentifier(application)
                        .environmentIdentifier(environment).configurationProfileIdentifier(profile))
                        .initialConfigurationToken();
                assertEquals(content, data.getLatestConfiguration(r -> r.configurationToken(token))
                        .configuration().asUtf8String());
            } finally {
                assertAll(cleanup.reversed());
            }
        }
    }

    public static class FastTiming implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.appconfig.deployment-time-scale", "0.01");
        }
    }
}
