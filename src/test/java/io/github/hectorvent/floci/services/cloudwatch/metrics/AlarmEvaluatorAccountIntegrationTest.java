package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AlarmEvaluatorAccountIntegrationTest {

    private static final String REGION = "eu-west-1";
    private static final String NAME = "owned-background-evaluation";
    private static final String NAMESPACE = "AccountEvaluation";
    @Inject
    CloudWatchMetricsService metrics;
    @Inject
    AlarmEvaluator evaluator;
    @Inject
    RegionResolver regions;

    @Test
    void sameNameAlarmsUseTheirStoredAccountAndRestoreTheCallersScope() {
        List<String> accounts = List.of("222222222222", "333333333333");
        try {
            for (int index = 0; index < accounts.size(); index++) {
                int value = index == 0 ? 90 : 10;
                RequestScopes.runAs(accounts.get(index), REGION, () -> {
                    MetricAlarm alarm = new MetricAlarm();
                    alarm.setAlarmName(NAME);
                    alarm.setNamespace(NAMESPACE);
                    alarm.setMetricName("Utilization");
                    alarm.setStatistic("Average");
                    alarm.setPeriod(60);
                    alarm.setEvaluationPeriods(3);
                    alarm.setThreshold(50);
                    alarm.setComparisonOperator("GreaterThanThreshold");
                    metrics.putMetricAlarm(alarm, REGION);
                    List<MetricDatum> data = new ArrayList<>();
                    for (int minute = 1; minute <= 6; minute++) {
                        MetricDatum datum = new MetricDatum();
                        datum.setMetricName("Utilization");
                        datum.setTimestamp(Instant.now().getEpochSecond() - 60L * minute);
                        datum.setSum(value);
                        datum.setMinimum(value);
                        datum.setMaximum(value);
                        datum.setSampleCount(1);
                        data.add(datum);
                    }
                    metrics.putMetricData(NAMESPACE, data, REGION);
                });
            }
            RequestScopes.runAs("444444444444", "us-west-2", () -> {
                evaluator.evaluateAll();
                assertEquals("444444444444", regions.getAccountId());
                assertEquals("us-west-2", regions.getRegion());
            });
            assertEquals("ALARM", state(accounts.getFirst()));
            assertEquals("OK", state(accounts.getLast()));
        } finally {
            for (String account : accounts) {
                RequestScopes.runAs(account, REGION, () -> metrics.deleteAlarms(List.of(NAME), REGION));
            }
        }
    }

    private String state(String account) {
        return RequestScopes.callAs(account, REGION,
                () -> metrics.describeAlarms(List.of(NAME), null, REGION).getFirst().getStateValue());
    }
}
