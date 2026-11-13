package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.cloudwatch.metrics.model.AlarmMetricDataQuery;
import io.floci.aws.services.cloudwatch.metrics.model.MetricAlarm;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudWatchMetricMathScopeIntegrationTest {
    @Inject
    CloudWatchMetricsService service;

    @Inject
    AlarmEvaluator evaluator;

    @Inject
    RegionResolver regions;

    @Test
    void periodicMathEvaluationUsesEachAlarmOwnerAndRestoresTheCallingScope() {
        String name = "owned-math-" + UUID.randomUUID();
        long timestamp = Instant.now().getEpochSecond() / 60 * 60 - 60;
        List<Owner> owners = List.of(
                new Owner("111111111111", "us-east-1", 30, "ALARM"),
                new Owner("111111111111", "us-west-2", 0, "OK"),
                new Owner("222222222222", "cn-north-1", 90, "ALARM"));
        List<Executable> cleanup = new ArrayList<>();
        try {
            for (Owner owner : owners) {
                RequestScopes.runAs(owner.account(), owner.region(), () -> {
                    service.putMetricData(name, List.of(
                            MetricDatum.of("Errors", "Count", owner.errors(), List.of(), timestamp),
                            MetricDatum.of("Requests", "Count", 100, List.of(), timestamp)), owner.region());
                    MetricAlarm alarm = new MetricAlarm();
                    alarm.setAlarmName(name);
                    alarm.setMetrics(List.of(
                            new AlarmMetricDataQuery("ratio", "100*errors/requests", null, true, null, null, null),
                            metric(name, "errors", "Errors"), metric(name, "requests", "Requests")));
                    alarm.setEvaluationPeriods(1);
                    alarm.setThreshold(10.0);
                    alarm.setComparisonOperator("GreaterThanThreshold");
                    alarm.setTreatMissingData("notBreaching");
                    service.putMetricAlarm(alarm, owner.region());
                    cleanup.add(() -> RequestScopes.runAs(owner.account(), owner.region(),
                            () -> service.deleteAlarms(List.of(name), owner.region())));
                });
            }
            RequestScopes.runAs("333333333333", "eu-west-1", () -> {
                evaluator.evaluateAll();
                assertEquals("333333333333", regions.getAccountId());
                assertEquals("eu-west-1", regions.getRegion());
            });
            for (Owner owner : owners) {
                RequestScopes.runAs(owner.account(), owner.region(), () -> assertEquals(owner.expected(),
                        service.describeAlarms(List.of(name), null, owner.region()).getFirst().getStateValue()));
            }
        } finally {
            assertAll(cleanup.reversed());
        }
    }

    private static AlarmMetricDataQuery metric(String namespace, String id, String name) {
        return new AlarmMetricDataQuery(id, null, null, false, null, null,
                new AlarmMetricDataQuery.MetricStat(
                        new AlarmMetricDataQuery.Metric(namespace, name, List.of()), 60, "Sum", null));
    }

    private record Owner(String account, String region, double errors, String expected) {}
}
