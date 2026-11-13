package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.appconfig.model.Deployment;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Reads stored rows directly, so the captured scheduled callback must produce every transition. */
class AppConfigDeploymentWorkerTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final Instant START = Instant.parse("2026-11-13T00:00:00Z");

    private final Clock clock = mock(Clock.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final AtomicReference<String> alarmCause = new AtomicReference<>();
    private final RegionResolver regions = new RegionResolver(REGION, ACCOUNT);
    private final InMemoryStorageFactory storage = new InMemoryStorageFactory(ACCOUNT);
    private final AppConfigAlarmMonitor alarms = mock(AppConfigAlarmMonitor.class);
    private final AppConfigService service = new AppConfigService(storage, regions, clock, alarms);
    private final AppConfigStorage<Deployment> deployments = new AppConfigStorage<>(
            storage, regions, "appconfig", "appconfig-deployments.json", new TypeReference<>() {});
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final AppConfigDeploymentWorker worker = new AppConfigDeploymentWorker(service, scheduler);
    private Runnable scheduled;
    private String app;
    private String env;
    private String profile;

    @BeforeEach
    void startWorker() {
        when(clock.instant()).thenAnswer(ignored -> now.get());
        when(alarms.rollbackCause(any())).thenAnswer(ignored -> alarmCause.get());
        app = service.createApplication(Map.of("Name", "app")).getId();
        env = service.createEnvironment(app, Map.of("Name", "environment")).getId();
        profile = service.createConfigurationProfile(app, Map.of("Name", "profile", "LocationUri", "hosted")).getId();
        for (String content : new String[] {"one", "two"}) {
            service.createHostedConfigurationVersion(
                    app, profile, content.getBytes(StandardCharsets.UTF_8), "text/plain", null);
        }
        worker.start();
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(callback.capture(), eq(1L), eq(1L), eq(TimeUnit.SECONDS));
        scheduled = callback.getValue();
    }

    @AfterEach
    void stopWorker() {
        worker.stop();
        verify(scheduler).shutdownNow();
    }

    @Test
    void theScheduledCallbackMovesAnUnpolledDeploymentThroughRolloutBakeAndCompletion() {
        Deployment deployment = deploy("1", 1, 1, "LINEAR");
        assertEquals("DEPLOYING", stored(deployment).getState());
        tickAt(59);
        assertEquals("DEPLOYING", stored(deployment).getState());
        tickAt(60);
        assertEquals("BAKING", stored(deployment).getState());
        assertEquals(100f, stored(deployment).getPercentageComplete());
        assertNull(stored(deployment).getCompletedAt());
        tickAt(119);
        assertEquals("BAKING", stored(deployment).getState());
        tickAt(120);
        assertEquals("COMPLETE", stored(deployment).getState());
        assertEquals(START.plusSeconds(120).toString(), stored(deployment).getCompletedAt());
        assertEquals("1", service.getActiveVersion(env, profile));
        assertEquals("DEPLOYING", deployment.getState());
        assertEquals(0f, deployment.getPercentageComplete());
    }

    @ParameterizedTest
    @CsvSource({"LINEAR,60,20", "LINEAR,240,80", "EXPONENTIAL,75,20", "EXPONENTIAL,225,80"})
    void growthUsesTheCapturedStrategyAndNeverMovesBackwards(String growth, long seconds, float percentage) {
        Deployment deployment = deploy("1", 5, 0, growth);
        tickAt(seconds);
        assertEquals(percentage, stored(deployment).getPercentageComplete());
        tickAt(0);
        assertEquals(percentage, stored(deployment).getPercentageComplete());
        tickAt(300);
        assertEquals("COMPLETE", stored(deployment).getState());
        assertEquals(START.plusSeconds(300).toString(), stored(deployment).getCompletedAt());
    }

    @Test
    void anAlarmDuringBakeRestoresBytesOnTheCapturedCallback() {
        deploy("1", 0, 0, "LINEAR");
        Deployment candidate = deploy("2", 1, 5, "LINEAR");
        tickAt(90);
        assertEquals("BAKING", stored(candidate).getState());
        assertEquals("2", service.getActiveVersion(env, profile));
        alarmCause.set("owned alarm is ALARM");
        tickAt(91);
        assertEquals("ROLLED_BACK", stored(candidate).getState());
        assertEquals(START.plusSeconds(91).toString(), stored(candidate).getCompletedAt());
        assertEquals("1", service.getActiveVersion(env, profile));
        assertEquals("DEPLOYING", candidate.getState());
    }

    @Test
    void aDelayedCallbackCompletesAnElapsedBakeBeforeConsultingAnAlarm() {
        deploy("1", 0, 0, "LINEAR");
        Deployment candidate = deploy("2", 1, 1, "LINEAR");
        alarmCause.set("alarm raised after the final deadline");
        clearInvocations(alarms);
        tickAt(121);
        assertEquals("COMPLETE", stored(candidate).getState());
        assertEquals(START.plusSeconds(120).toString(), stored(candidate).getCompletedAt());
        assertTrue(stored(candidate).getEventLog().isEmpty());
        assertEquals("2", service.getActiveVersion(env, profile));
        verifyNoInteractions(alarms);
    }

    @Test
    void aFailedMonitorIsRetriedByTheNextCallbackWhileItsBakeIsStillActive() {
        Deployment deployment = deploy("1", 0, 5, "LINEAR");
        clearInvocations(alarms);
        when(alarms.rollbackCause(any())).thenThrow(new IllegalStateException("monitor unavailable"))
                .thenReturn(null);
        tickAt(1);
        assertEquals("BAKING", stored(deployment).getState());
        tickAt(2);
        assertEquals("BAKING", stored(deployment).getState());
        verify(alarms, times(2)).rollbackCause(any());
        tickAt(300);
        assertEquals("COMPLETE", stored(deployment).getState());
    }

    @Test
    void aRemovedDeploymentIsNotRecreatedByTheNextCallback() {
        Deployment deployment = deploy("1", 0, 5, "LINEAR");
        deployments.delete(app + "::" + env + "::" + deployment.getDeploymentNumber());
        tickAt(10);
        assertTrue(deployments.keys().isEmpty());
        assertEquals("1", service.getActiveVersion(env, profile));
    }

    @ParameterizedTest
    @ValueSource(strings = {"started", "bake"})
    void legacyRowsWithoutARecordedDeadlineRemainUnchanged(String absent) {
        Deployment deployment = deploy("1", 0, 1, "LINEAR");
        if ("started".equals(absent)) {
            deployment.setStartedAt(null);
        } else {
            deployment.setFinalBakeTimeInMinutes(null);
        }
        deployments.put(app + "::" + env + "::" + deployment.getDeploymentNumber(), deployment);
        JsonNode before = new ObjectMapper().valueToTree(deployment);
        tickAt(3600);
        assertEquals(before, new ObjectMapper().valueToTree(stored(deployment)));
        assertEquals("1", service.getActiveVersion(env, profile));
    }

    @Test
    void aLegacyBakeWithoutRecordedRolloutUsesItsRecordedBakeDeadline() {
        Deployment deployment = deploy("1", 0, 1, "LINEAR");
        deployment.setDeploymentDurationInMinutes(null);
        deployments.put(app + "::" + env + "::" + deployment.getDeploymentNumber(), deployment);
        tickAt(60);
        assertEquals("COMPLETE", stored(deployment).getState());
        assertEquals(START.plusSeconds(60).toString(), stored(deployment).getCompletedAt());
        assertNull(stored(deployment).getDeploymentDurationInMinutes());
    }

    private Deployment deploy(String version, int rollout, int bake, String growth) {
        String strategy = service.createDeploymentStrategy(Map.of(
                "Name", "strategy-" + version, "DeploymentDurationInMinutes", rollout,
                "FinalBakeTimeInMinutes", bake, "GrowthFactor", 20, "GrowthType", growth)).getId();
        return service.startDeployment(app, env, Map.of("ConfigurationProfileId", profile,
                "ConfigurationVersion", version, "DeploymentStrategyId", strategy));
    }

    private void tickAt(long seconds) {
        now.set(START.plusSeconds(seconds));
        scheduled.run();
    }

    private Deployment stored(Deployment deployment) {
        return deployments.get(app + "::" + env + "::" + deployment.getDeploymentNumber()).orElseThrow();
    }
}
