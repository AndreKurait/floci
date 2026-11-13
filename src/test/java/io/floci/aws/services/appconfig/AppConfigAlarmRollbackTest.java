package io.floci.aws.services.appconfig;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceRoleCallAuthorizer;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.appconfig.model.Deployment;
import io.floci.aws.services.appconfig.model.DeploymentEvent;
import io.floci.aws.services.appconfig.model.Monitor;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.CompositeAlarm;
import io.floci.aws.services.cloudwatch.metrics.model.MetricAlarm;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppConfigAlarmRollbackTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String ROLE = "arn:aws:iam::000000000000:role/monitor";

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"5,ALARM", "0,ALARM", "5,INSUFFICIENT_DATA", "0,INSUFFICIENT_DATA"})
    void enabledAlarmsRestoreContentDuringRolloutAndBake(int rollout, String state) {
        Fixture fixture = fixture(new InMemoryStorageFactory(ACCOUNT));
        Scope scope = scope(fixture);
        Deployment next = deploy(fixture, scope, "2", rollout, 5);
        assertEquals(rollout == 0 ? "BAKING" : "DEPLOYING", next.getState());
        assertEquals("2", fixture.service().getActiveVersion(scope.env(), scope.profile()));
        fixture.cloudWatch().setAlarmState("health", state, "test", null, REGION);

        fixture.service().advanceDeployments();
        assertEquals("1", fixture.service().getActiveVersion(scope.env(), scope.profile()));
        Deployment stopped = fixture.service().getDeployment(scope.app(), scope.env(), next.getDeploymentNumber());
        assertEquals("ROLLED_BACK", stopped.getState());
        assertEquals(List.of("ROLLBACK_COMPLETED", "ROLLBACK_STARTED"),
                stopped.getEventLog().stream().map(DeploymentEvent::eventType).toList());
        assertTrue(stopped.getEventLog().stream().allMatch(event ->
                event.triggeredBy().equals("CLOUDWATCH_ALARM")
                        && event.description().contains("alarm:health") && event.description().contains(state)));
        assertEquals(rollout == 0 ? "BAKING" : "DEPLOYING", next.getState());
        when(fixture.clock().instant()).thenReturn(START.plusSeconds(7200));
        fixture.service().advanceDeployments();
        assertEquals("ROLLED_BACK",
                fixture.service().getDeployment(scope.app(), scope.env(), next.getDeploymentNumber()).getState());
        assertEquals("1", fixture.service().getActiveVersion(scope.env(), scope.profile()));
    }

    @Test
    void disabledAlarmActionsSuppressRollbackAndAHealthyDeploymentCompletes() {
        Fixture fixture = fixture(new InMemoryStorageFactory(ACCOUNT));
        Scope scope = scope(fixture);
        Deployment next = deploy(fixture, scope, "2", 1, 1);
        fixture.cloudWatch().setAlarmState("health", "ALARM", "test", null, REGION);
        actionsEnabled(fixture, false);
        fixture.service().advanceDeployments();
        assertEquals("2", fixture.service().getActiveVersion(scope.env(), scope.profile()));
        when(fixture.clock().instant()).thenReturn(START.plusSeconds(120));
        fixture.service().advanceDeployments();
        assertEquals("COMPLETE",
                fixture.service().getDeployment(scope.app(), scope.env(), next.getDeploymentNumber()).getState());
        actionsEnabled(fixture, true);
        fixture.service().advanceDeployments();
        assertEquals("2", fixture.service().getActiveVersion(scope.env(), scope.profile()));
    }

    @Test
    void unreadableMonitorCannotCreateADeploymentAndExplicitRevertBypassesIt() {
        Fixture fixture = fixture(new InMemoryStorageFactory(ACCOUNT));
        Scope scope = scope(fixture);
        Deployment next = deploy(fixture, scope, "2", 5, 5);
        when(fixture.authorizer().authorizeWithoutSourceContext(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(Decision.DENY);
        assertEquals("BadRequestException", assertThrows(AwsException.class,
                () -> fixture.service().getDeployment(scope.app(), scope.env(), next.getDeploymentNumber()))
                .getErrorCode());
        assertThrows(AwsException.class, () -> deploy(fixture, scope, "3", 5, 5));
        assertEquals("2", fixture.service().getActiveVersion(scope.env(), scope.profile()));

        fixture.service().stopDeployment(scope.app(), scope.env(), next.getDeploymentNumber(), true);
        assertEquals("1", fixture.service().getActiveVersion(scope.env(), scope.profile()));
        assertEquals("ROLLED_BACK",
                fixture.service().getDeployment(scope.app(), scope.env(), next.getDeploymentNumber()).getState());
    }

    @Test
    void missingPreviousContentLeavesTheDeploymentAndActiveConfigurationIntact() {
        Fixture fixture = fixture(new InMemoryStorageFactory(ACCOUNT));
        Scope scope = scope(fixture);
        Deployment next = deploy(fixture, scope, "2", 5, 5);
        fixture.service().deleteHostedConfigurationVersion(scope.app(), scope.profile(), 1);
        fixture.cloudWatch().setAlarmState("health", "ALARM", "test", null, REGION);
        assertThrows(AwsException.class,
                () -> fixture.service().getDeployment(scope.app(), scope.env(), next.getDeploymentNumber()));
        assertEquals("2", fixture.service().getActiveVersion(scope.env(), scope.profile()));
        assertEquals("DEPLOYING", next.getState());
    }

    @Test
    void anOlderDeploymentCannotRollbackANewerConfiguration() {
        Fixture fixture = fixture(new InMemoryStorageFactory(ACCOUNT));
        Scope scope = scope(fixture);
        deploy(fixture, scope, "2", 5, 5);
        Deployment newer = deploy(fixture, scope, "3", 0, 0);
        fixture.cloudWatch().setAlarmState("health", "ALARM", "test", null, REGION);
        fixture.service().advanceDeployments();
        assertEquals("3", fixture.service().getActiveVersion(scope.env(), scope.profile()));
        assertEquals("COMPLETE",
                fixture.service().getDeployment(scope.app(), scope.env(), newer.getDeploymentNumber()).getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void monitoredDeploymentAndAlarmEventsSurviveStorageReload(String mode) {
        Scope scope;
        int number;
        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            Fixture fixture = fixture(first);
            scope = scope(fixture);
            number = deploy(fixture, scope, "2", 5, 5).getDeploymentNumber();
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            Fixture restarted = fixture(second);
            restarted.cloudWatch().setAlarmState("health", "ALARM", "test", null, REGION);
            restarted.service().advanceDeployments();
            assertEquals("1", restarted.service().getActiveVersion(scope.env(), scope.profile()));
        } finally {
            second.shutdownAll();
        }
        StorageFactory third = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            Deployment restored = fixture(third).service().getDeployment(scope.app(), scope.env(), number);
            assertEquals("ROLLED_BACK", restored.getState());
            assertEquals("CLOUDWATCH_ALARM", restored.getEventLog().getLast().triggeredBy());
        } finally {
            third.shutdownAll();
        }
    }

    @Test
    void compositeMonitorRequiresWildcardDescribePermission() {
        Fixture fixture = fixture(new InMemoryStorageFactory(ACCOUNT));
        CompositeAlarm alarm = new CompositeAlarm();
        alarm.setAlarmName("aggregate");
        alarm.setActionsEnabled(true);
        alarm.setAlarmRule("TRUE");
        fixture.cloudWatch().putCompositeAlarm(alarm, REGION);
        AppConfigAlarmMonitor monitor = new AppConfigAlarmMonitor(
                fixture.cloudWatch(), fixture.authorizer(), fixture.regions());
        String arn = fixture.regions().buildArn("cloudwatch", REGION, "alarm:aggregate");
        assertTrue(monitor.rollbackCause(List.of(new Monitor(arn, ROLE))).contains("ALARM"));
        verify(fixture.authorizer()).authorizeUnscopedWithoutSourceContext(
                "appconfig", ROLE, "cloudwatch:DescribeAlarms", ACCOUNT, REGION);
        when(fixture.authorizer().authorizeUnscopedWithoutSourceContext(
                anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(Decision.DENY);
        assertThrows(AwsException.class, () -> monitor.rollbackCause(List.of(new Monitor(arn, ROLE))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:cloudwatch:us-west-2:000000000000:alarm:health",
            "arn:aws:cloudwatch:us-east-1:222222222222:alarm:health",
            "arn:aws-cn:cloudwatch:us-east-1:000000000000:alarm:health",
            "arn:aws:sns:us-east-1:000000000000:health",
            "not-an-arn"
    })
    void monitorCannotReadAForeignAccountRegionPartitionOrService(String arn) {
        Fixture fixture = fixture(new InMemoryStorageFactory(ACCOUNT));
        AppConfigAlarmMonitor monitor = new AppConfigAlarmMonitor(
                fixture.cloudWatch(), fixture.authorizer(), fixture.regions());
        assertThrows(AwsException.class, () -> monitor.rollbackCause(List.of(new Monitor(arn, ROLE))));
    }

    @Test
    void metricMonitorUsesAlarmResourceTagsAndAdmissionChecksEveryConfiguredRole() {
        Fixture fixture = fixture(new InMemoryStorageFactory(ACCOUNT));
        scope(fixture);
        String arn = fixture.regions().buildArn("cloudwatch", REGION, "alarm:health");
        fixture.cloudWatch().setAlarmState("health", "ALARM", "test", null, REGION);
        AppConfigAlarmMonitor monitor = new AppConfigAlarmMonitor(
                fixture.cloudWatch(), fixture.authorizer(), fixture.regions());
        assertTrue(monitor.rollbackCause(List.of(new Monitor(arn, ROLE))).contains(arn));
        verify(fixture.authorizer(), atLeastOnce()).authorizeWithoutSourceContext(
                eq("appconfig"), eq(ROLE), eq("cloudwatch:DescribeAlarms"), eq(arn),
                eq(ACCOUNT), eq("aws"), eq(Map.of("aws:ResourceTag/team", List.of("service"))));
        assertTrue(monitor.rollbackCause(List.of(
                new Monitor(arn, ROLE), new Monitor(arn + "-missing", ROLE))).contains(arn));
        assertThrows(AwsException.class, () -> monitor.validateMonitors(List.of(
                new Monitor(arn, ROLE), new Monitor(arn + "-missing", ROLE))));
    }

    @ParameterizedTest
    @CsvSource({"true,ALARM", "false,ALARM", "true,INSUFFICIENT_DATA", "false,INSUFFICIENT_DATA"})
    void confirmedAlarmRollsBackDespiteAnotherUnreadableMonitor(boolean unreadableFirst, String state) {
        Fixture fixture = fixture(new InMemoryStorageFactory(ACCOUNT));
        Scope scope = scope(fixture);
        String arn = fixture.regions().buildArn("cloudwatch", REGION, "alarm:health");
        String otherRole = ROLE + "-other";
        Map<String, String> healthy = Map.of("AlarmArn", arn, "AlarmRoleArn", ROLE);
        Map<String, String> unreadable = Map.of("AlarmArn", arn, "AlarmRoleArn", otherRole);
        fixture.service().updateEnvironment(scope.app(), scope.env(), "environment", null,
                unreadableFirst ? List.of(unreadable, healthy) : List.of(healthy, unreadable));
        Deployment next = deploy(fixture, scope, "2", 5, 5);
        fixture.cloudWatch().setAlarmState("health", state, "test", null, REGION);
        when(fixture.authorizer().authorizeWithoutSourceContext(
                eq("appconfig"), eq(otherRole), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(Decision.DENY);

        fixture.service().advanceDeployments();

        Deployment stored = fixture.service().getDeployment(scope.app(), scope.env(), next.getDeploymentNumber());
        assertEquals("ROLLED_BACK", stored.getState());
        assertEquals("1", fixture.service().getActiveVersion(scope.env(), scope.profile()));
        assertTrue(stored.getEventLog().getLast().description().contains(arn + " is " + state));
        assertThrows(AwsException.class, () -> deploy(fixture, scope, "3", 5, 5));
        assertEquals("1", fixture.service().getActiveVersion(scope.env(), scope.profile()));
    }

    @Test
    void schedulerContinuesAfterAScanFailure() {
        AppConfigService service = mock(AppConfigService.class);
        doThrow(new IllegalStateException("transient")).doNothing()
                .when(service).advanceDeployments();
        AppConfigDeploymentWorker worker = new AppConfigDeploymentWorker(service);
        try {
            worker.tick();
            worker.tick();
            verify(service, times(2)).advanceDeployments();
        } finally {
            worker.stop();
        }
    }

    @Test
    void environmentsWithoutMonitorsMakeNoAlarmOrRoleCalls() {
        CloudWatchMetricsService cloudWatch = mock(CloudWatchMetricsService.class);
        ServiceRoleCallAuthorizer authorizer = mock(ServiceRoleCallAuthorizer.class);
        AppConfigAlarmMonitor monitor = new AppConfigAlarmMonitor(
                cloudWatch, authorizer, new RegionResolver(REGION, ACCOUNT));
        assertNull(monitor.rollbackCause(null));
        assertNull(monitor.rollbackCause(List.of()));
        verifyNoInteractions(cloudWatch, authorizer);
    }

    private static void actionsEnabled(Fixture fixture, boolean enabled) {
        MetricAlarm alarm = fixture.cloudWatch().describeAlarms(List.of("health"), null, REGION).getFirst();
        alarm.setActionsEnabled(enabled);
        fixture.cloudWatch().putMetricAlarm(alarm, REGION);
    }

    private static Fixture fixture(StorageFactory storage) {
        RegionResolver regions = new RegionResolver(REGION, ACCOUNT);
        ServiceRoleCallAuthorizer authorizer = mock(ServiceRoleCallAuthorizer.class);
        when(authorizer.authorizeWithoutSourceContext(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(Decision.ALLOW);
        when(authorizer.authorizeUnscopedWithoutSourceContext(
                anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(Decision.ALLOW);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        CloudWatchMetricsService cloudWatch = new CloudWatchMetricsService(storage, regions);
        return new Fixture(new AppConfigService(storage, regions, clock,
                new AppConfigAlarmMonitor(cloudWatch, authorizer, regions)), cloudWatch, authorizer, regions, clock);
    }

    private static Scope scope(Fixture fixture) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("health");
        alarm.setNamespace("test");
        alarm.setMetricName("Errors");
        alarm.setStatistic("Sum");
        alarm.setPeriod(60);
        alarm.setEvaluationPeriods(1);
        alarm.setThreshold(10);
        alarm.setComparisonOperator("GreaterThanThreshold");
        alarm.setActionsEnabled(true);
        alarm.setTags(Map.of("team", "service"));
        fixture.cloudWatch().putMetricAlarm(alarm, REGION);
        fixture.cloudWatch().setAlarmState("health", "OK", "healthy", null, REGION);
        AppConfigService service = fixture.service();
        String app = service.createApplication(Map.of("Name", "app")).getId();
        String profile = service.createConfigurationProfile(app,
                Map.of("Name", "profile", "LocationUri", "hosted")).getId();
        for (String value : List.of("baseline", "candidate", "newer")) {
            service.createHostedConfigurationVersion(
                    app, profile, value.getBytes(StandardCharsets.UTF_8), "text/plain", null);
        }
        String env = service.createEnvironment(app, Map.of("Name", "environment", "Monitors",
                List.of(Map.of("AlarmArn", fixture.regions().buildArn("cloudwatch", REGION, "alarm:health"),
                        "AlarmRoleArn", ROLE)))).getId();
        Scope scope = new Scope(app, env, profile);
        deploy(fixture, scope, "1", 0, 0);
        return scope;
    }

    private static Deployment deploy(Fixture fixture, Scope scope, String version, int rollout, int bake) {
        AppConfigService service = fixture.service();
        String strategy = service.createDeploymentStrategy(Map.of("Name", "test", "DeploymentDurationInMinutes", rollout,
                "FinalBakeTimeInMinutes", bake, "GrowthFactor", 100)).getId();
        return service.startDeployment(scope.app(), scope.env(), Map.of("ConfigurationProfileId", scope.profile(),
                "ConfigurationVersion", version, "DeploymentStrategyId", strategy));
    }

    private record Scope(String app, String env, String profile) {}

    private record Fixture(AppConfigService service, CloudWatchMetricsService cloudWatch,
                           ServiceRoleCallAuthorizer authorizer, RegionResolver regions, Clock clock) {}
}
