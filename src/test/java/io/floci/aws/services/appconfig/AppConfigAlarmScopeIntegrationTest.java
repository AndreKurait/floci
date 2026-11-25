package io.floci.aws.services.appconfig;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.MetricAlarm;
import io.floci.aws.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AppConfigAlarmScopeIntegrationTest {
    @Inject
    AppConfigService service;

    @Inject
    CloudWatchMetricsService cloudWatch;

    @Inject
    RegionResolver regions;

    @Inject
    IamService iam;

    @Test
    void backgroundScanUsesEachDeploymentOwnerAndRestoresTheCallingScope() {
        String alarmName = "owned-monitor-" + UUID.randomUUID();
        List<Executable> cleanup = new ArrayList<>();
        List<Selection> selections = new ArrayList<>();
        try {
            for (Owner owner : List.of(
                    new Owner("111111111111", "us-east-1", "ALARM"),
                    new Owner("111111111111", "us-west-2", "OK"),
                    new Owner("222222222222", "cn-north-1", "INSUFFICIENT_DATA"))) {
                selections.add(RequestScopes.callAs(owner.account(), owner.region(),
                        () -> prepare(owner, alarmName, cleanup)));
            }
            for (Selection selection : selections) {
                Owner owner = selection.owner();
                RequestScopes.runAs(owner.account(), owner.region(), () -> cloudWatch.setAlarmState(
                        alarmName, owner.alarmState(), "isolation", null, owner.region()));
            }
            RequestScopes.runAs("333333333333", "eu-west-1", () -> {
                service.advanceDeployments();
                assertEquals("333333333333", regions.getAccountId());
                assertEquals("eu-west-1", regions.getRegion());
            });
            for (Selection selection : selections) {
                Owner owner = selection.owner();
                RequestScopes.runAs(owner.account(), owner.region(), () -> assertEquals(
                        owner.alarmState().equals("OK") ? "2" : "1",
                        service.getActiveVersion(selection.environment(), selection.profile())));
            }
        } finally {
            assertAll(cleanup.reversed());
        }
    }

    private Selection prepare(Owner owner, String alarmName, List<Executable> cleanup) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName(alarmName);
        alarm.setNamespace("AppConfigScope");
        alarm.setMetricName("Errors");
        alarm.setStatistic("Sum");
        alarm.setPeriod(60);
        alarm.setEvaluationPeriods(1);
        alarm.setThreshold(10);
        alarm.setComparisonOperator("GreaterThanThreshold");
        alarm.setTreatMissingData("ignore");
        alarm.setActionsEnabled(true);
        cloudWatch.putMetricAlarm(alarm, owner.region());
        cleanup.add(() -> RequestScopes.runAs(owner.account(), owner.region(),
                () -> cloudWatch.deleteAlarms(List.of(alarmName), owner.region())));
        cloudWatch.setAlarmState(alarmName, "OK", "baseline", null, owner.region());
        String roleName = "scope-monitor-" + UUID.randomUUID();
        String roleArn = iam.createRole(roleName, "/", """
                {"Statement":{"Effect":"Allow","Principal":{"Service":"appconfig.amazonaws.com"},
                "Action":"sts:AssumeRole"}}""", "owned scope fixture", 3600, Map.of()).getArn();
        cleanup.add(() -> RequestScopes.runAs(owner.account(), owner.region(), () -> iam.deleteRole(roleName)));
        iam.putRolePolicy(roleName, "read", """
                {"Statement":{"Effect":"Allow","Action":"cloudwatch:DescribeAlarms","Resource":"*"}}""");
        cleanup.add(() -> RequestScopes.runAs(owner.account(), owner.region(), () -> iam.deleteRolePolicy(roleName, "read")));
        String app = service.createApplication(Map.of("Name", "same-application")).getId();
        cleanup.add(() -> RequestScopes.runAs(owner.account(), owner.region(), () -> service.deleteApplication(app)));
        String profile = service.createConfigurationProfile(
                app, Map.of("Name", "same-profile", "LocationUri", "hosted")).getId();
        cleanup.add(() -> RequestScopes.runAs(owner.account(), owner.region(),
                () -> service.deleteConfigurationProfile(app, profile)));
        for (String value : List.of("baseline", "candidate")) {
            service.createHostedConfigurationVersion(
                    app, profile, value.getBytes(StandardCharsets.UTF_8), "text/plain", null);
        }
        String fast = service.createDeploymentStrategy(Map.of(
                "Name", "fast", "DeploymentDurationInMinutes", 0, "FinalBakeTimeInMinutes", 0,
                "GrowthFactor", 100)).getId();
        cleanup.add(() -> RequestScopes.runAs(owner.account(), owner.region(),
                () -> service.deleteDeploymentStrategy(fast)));
        String slow = service.createDeploymentStrategy(Map.of(
                "Name", "slow", "DeploymentDurationInMinutes", 0, "FinalBakeTimeInMinutes", 5,
                "GrowthFactor", 100)).getId();
        cleanup.add(() -> RequestScopes.runAs(owner.account(), owner.region(),
                () -> service.deleteDeploymentStrategy(slow)));
        String environment = service.createEnvironment(app, Map.of(
                "Name", "same-environment", "Monitors", List.of(Map.of(
                        "AlarmArn", regions.buildArn("cloudwatch", owner.region(), "alarm:" + alarmName),
                        "AlarmRoleArn", roleArn)))).getId();
        cleanup.add(() -> RequestScopes.runAs(owner.account(), owner.region(),
                () -> service.deleteEnvironment(app, environment)));
        service.startDeployment(app, environment, Map.of(
                "ConfigurationProfileId", profile, "ConfigurationVersion", "1", "DeploymentStrategyId", fast));
        service.startDeployment(app, environment, Map.of(
                "ConfigurationProfileId", profile, "ConfigurationVersion", "2", "DeploymentStrategyId", slow));
        return new Selection(owner, environment, profile);
    }

    private record Owner(String account, String region, String alarmState) {}

    private record Selection(Owner owner, String environment, String profile) {}
}
