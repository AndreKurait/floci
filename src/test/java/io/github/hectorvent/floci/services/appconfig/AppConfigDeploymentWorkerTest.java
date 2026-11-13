package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the real monitor schedule and the real deployment logic on a controlled timeline: nothing
 * reads a deployment through the API here, so every state change is the worker's own tick.
 */
@Timeout(30)
class AppConfigDeploymentWorkerTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    private final TestTime time = TestTime.create();
    private final Instant start = time.now();
    private final RegionResolver regions = new RegionResolver(REGION, ACCOUNT);
    private final InMemoryStorageFactory storage = new InMemoryStorageFactory(ACCOUNT);
    private final AtomicReference<String> alarmCause = new AtomicReference<>();
    private final AppConfigAlarmMonitor alarms = mock(AppConfigAlarmMonitor.class);
    private final AppConfigService service = new AppConfigService(
            storage, regions, time.clock(), alarms, mock(AppConfigS3Source.class));
    private final AppConfigStorage<Deployment> deployments = new AppConfigStorage<>(
            storage, regions, "appconfig", "appconfig-deployments.json", new TypeReference<>() {});
    private final AppConfigDeploymentWorker worker = new AppConfigDeploymentWorker(service, time.newScheduler());

    private String app;
    private String env;
    private String profile;

    @BeforeEach
    void startWorker() {
        when(alarms.rollbackCause(any())).thenAnswer(call -> alarmCause.get());
        app = service.createApplication(Map.of("Name", "app")).getId();
        env = service.createEnvironment(app, Map.of("Name", "environment")).getId();
        profile = service.createConfigurationProfile(app, Map.of("Name", "profile", "LocationUri", "hosted")).getId();
        for (String content : new String[] {"one", "two"}) {
            service.createHostedConfigurationVersion(
                    app, profile, content.getBytes(StandardCharsets.UTF_8), "text/plain", null);
        }
        worker.start();
    }

    @AfterEach
    void stopWorker() {
        worker.stop();
    }

    @Test
    void anUnpolledDeploymentBakesAndCompletesOnItsConfiguredSchedule() {
        Deployment deployment = deploy("1", 1, 1);
        assertEquals("DEPLOYING", deployment.getState());

        time.advance(Duration.ofSeconds(59));
        assertEquals("DEPLOYING", stored(deployment).getState());

        time.advance(Duration.ofSeconds(1));
        assertEquals("BAKING", stored(deployment).getState());
        assertEquals(100.0f, stored(deployment).getPercentageComplete());
        assertNull(stored(deployment).getCompletedAt());

        time.advance(Duration.ofSeconds(59));
        assertEquals("BAKING", stored(deployment).getState());

        time.advance(Duration.ofSeconds(1));
        assertEquals("COMPLETE", stored(deployment).getState());
        assertEquals(start.plusSeconds(120).toString(), stored(deployment).getCompletedAt());
        assertEquals("1", service.getActiveVersion(env, profile));
    }

    @Test
    void anUnpolledRolloutReportsEachGrowthStepWhenItIsReached() {
        Deployment deployment = deploy("1", 5, 0);

        time.advance(Duration.ofSeconds(59));
        assertEquals(0.0f, stored(deployment).getPercentageComplete());

        time.advance(Duration.ofSeconds(1));
        assertEquals(20.0f, stored(deployment).getPercentageComplete());

        time.advance(Duration.ofSeconds(179));
        assertEquals(60.0f, stored(deployment).getPercentageComplete());

        time.advance(Duration.ofSeconds(1));
        assertEquals(80.0f, stored(deployment).getPercentageComplete());
        assertEquals("DEPLOYING", stored(deployment).getState());

        time.advance(Duration.ofSeconds(60));
        assertEquals("COMPLETE", stored(deployment).getState());
    }

    @Test
    void anAlarmRaisedDuringTheBakeRollsBackOnTheNextTick() {
        deploy("1", 0, 0);
        Deployment candidate = deploy("2", 1, 5);
        time.advance(Duration.ofSeconds(90));
        assertEquals("BAKING", stored(candidate).getState());
        assertEquals("2", service.getActiveVersion(env, profile));

        alarmCause.set("alarm:health is in ALARM");
        time.advance(Duration.ofMillis(999));
        assertEquals("BAKING", stored(candidate).getState());

        time.advance(Duration.ofMillis(1));
        assertEquals("ROLLED_BACK", stored(candidate).getState());
        assertEquals(start.plusSeconds(91).toString(), stored(candidate).getCompletedAt());
        assertEquals("1", service.getActiveVersion(env, profile));
    }

    @Test
    void aFailedScanDoesNotStopLaterTicks() {
        Deployment deployment = deploy("1", 1, 0);
        when(alarms.rollbackCause(any())).thenThrow(new IllegalStateException("monitor unavailable"))
                .thenAnswer(call -> alarmCause.get());

        time.advance(Duration.ofSeconds(60));

        assertEquals("COMPLETE", stored(deployment).getState());
    }

    @Test
    void aStoppedWorkerLeavesTheTimelineIdle() {
        Deployment deployment = deploy("1", 1, 0);

        worker.stop();
        time.advance(Duration.ofHours(1));

        assertEquals("DEPLOYING", stored(deployment).getState());
        assertEquals(0, time.pendingTasks());
    }

    private Deployment deploy(String version, int rolloutMinutes, int bakeMinutes) {
        String strategy = service.createDeploymentStrategy(Map.of(
                "Name", "strategy-" + version, "DeploymentDurationInMinutes", rolloutMinutes,
                "FinalBakeTimeInMinutes", bakeMinutes, "GrowthFactor", 20)).getId();
        return service.startDeployment(app, env, Map.of("ConfigurationProfileId", profile,
                "ConfigurationVersion", version, "DeploymentStrategyId", strategy));
    }

    /** The saved record, read without the refresh an API read performs. */
    private Deployment stored(Deployment deployment) {
        return deployments.get(app + "::" + env + "::" + deployment.getDeploymentNumber()).orElseThrow();
    }
}
