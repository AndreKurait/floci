package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService.MetricDataQuery;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService.MetricDataResult;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService.MetricStat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetricMathTest {
    private static final Instant START = Instant.ofEpochSecond(60);
    private static final Instant END = Instant.ofEpochSecond(180);

    @ParameterizedTest
    @CsvSource({"a+b,12", "a-b,8", "a*b,20", "a/b,5", "-a^2,-100", "a^-2,0.01",
            "a*0+2^3^2,512", "1e2*b/a,20", "a/PERIOD(a),0.16666666666666666",
            "PERIOD(a)/a,6", "-(-a)/2,5"})
    void evaluatesArithmeticWithNormalPrecedence(String expression, double expected) {
        MetricMath plan = new MetricMath(List.of(expression("answer", expression, true),
                metric("a", 60), metric("b", 60)));
        MetricDataResult result = plan.evaluate(START, END,
                metric -> Map.of(START, metric.metricName().equals("a") ? 10.0 : 2.0),
                metric -> 100).getFirst();

        assertEquals(List.of(START), result.timestamps());
        assertEquals(expected, result.values().getFirst(), 1e-12);
        assertEquals(60, plan.alarmPeriod());
    }

    @Test
    void resolvesHiddenForwardReferencesAndUsesZeroOnlyForMissingOperands() {
        MetricMath plan = new MetricMath(List.of(expression("answer", "ratio + a*0", true),
                expression("ratio", "a/b", false), metric("a", 60), metric("b", 60)));
        MetricDataResult result = plan.evaluate(START, END, metric -> metric.metricName().equals("a")
                ? Map.of(START, 10.0)
                : Map.of(START.plusSeconds(60), 2.0), metric -> 100).getFirst();

        // a/b drops the first point; the outer addition aligns with a and fills that missing value with zero.
        assertEquals(List.of(START, START.plusSeconds(60)), result.timestamps());
        assertEquals(List.of(0.0, 0.0), result.values());
        MetricMath ratio = new MetricMath(List.of(expression("ratio", "a/b", true),
                metric("a", 60), metric("b", 60)));
        MetricDataResult divided = ratio.evaluate(START, END, metric -> metric.metricName().equals("a")
                ? Map.of(START, 10.0)
                : Map.of(START.plusSeconds(60), 2.0), metric -> 100).getFirst();
        assertEquals(List.of(START.plusSeconds(60)), divided.timestamps());
        assertEquals(List.of(0.0), divided.values());
    }

    @Test
    void quotaProducesAContinuousSeriesWithoutNeedingReportedUsage() {
        MetricMath plan = new MetricMath(List.of(expression("quota", "SERVICE_QUOTA(a)", true), metric("a", 60)));
        MetricDataResult result = plan.evaluate(START, END, metric -> {
            throw new AssertionError("A service quota does not depend on reported usage samples");
        }, metric -> 200).getFirst();

        assertEquals(List.of(START, START.plusSeconds(60)), result.timestamps());
        assertEquals(List.of(200.0, 200.0), result.values());
        assertEquals(List.of(metric("a", 60).metricStat()), plan.quotaMetrics());
    }

    @Test
    void quotaIncludesTheLastBucketWhenEndTimeHasAFractionalSecond() {
        MetricMath plan = new MetricMath(List.of(expression("quota", "SERVICE_QUOTA(a)", true), metric("a", 60)));
        MetricDataResult result = plan.evaluate(START, END.plusNanos(1),
                metric -> Map.of(), metric -> 200).getFirst();

        assertEquals(List.of(START, START.plusSeconds(60), END), result.timestamps());
        assertEquals(List.of(200.0, 200.0, 200.0), result.values());
    }

    @Test
    void omitsInfiniteComputedValues() {
        MetricMath plan = new MetricMath(List.of(expression("answer", "a * (1/0)", true), metric("a", 60)));
        assertEquals(List.of(), plan.evaluate(START, END,
                metric -> Map.of(START, 10.0), metric -> 100).getFirst().values());
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent+1", "a+", "2", "SUM(a)", "PERIOD(answer)",
            "SERVICE_QUOTA(answer)", "a/(1e999)", "a**2", "a;exit()", "1.2.3*a"})
    void rejectsMalformedUnsupportedAndNonTimeSeriesResults(String expression) {
        AwsException failure = assertThrows(AwsException.class,
                () -> new MetricMath(List.of(expression("answer", expression, true), metric("a", 60))));
        assertEquals("InvalidParameterValue", failure.getErrorCode());
    }

    @Test
    void rejectsCyclesDuplicateIdsAndAmbiguousQuerySources() {
        assertThrows(AwsException.class, () -> new MetricMath(List.of(
                expression("first", "second+1", true), expression("second", "first+1", false))));
        assertThrows(AwsException.class, () -> new MetricMath(List.of(metric("a", 60), metric("a", 60))));
        assertThrows(AwsException.class, () -> new MetricMath(List.of(new MetricDataQuery(
                "a", metric("a", 60).metricStat(), "a+1", null, true))));
        assertThrows(AwsException.class, () -> new MetricMath(List.of(
                new MetricDataQuery("a", null, null, null, true))));
    }

    @Test
    void rejectsUnimplementedResamplingAndInvalidAlarmSelection() {
        assertThrows(AwsException.class, () -> new MetricMath(List.of(expression("answer", "a+b", true),
                metric("a", 60), metric("b", 300))));
        assertThrows(AwsException.class, () -> new MetricMath(List.of(new MetricDataQuery(
                "a", metric("a", 60).metricStat(), null, null, true, 300, null))));
        MetricMath noResult = new MetricMath(List.of(metric("a", 60)));
        assertThrows(AwsException.class, noResult::alarmPeriod);
        MetricMath twoResults = new MetricMath(List.of(expression("first", "a+1", true),
                expression("second", "a+2", true), metric("a", 60)));
        assertThrows(AwsException.class, twoResults::alarmPeriod);
    }

    @Test
    void rejectsDeepSyntaxAndDeepCachedDependencyChainsWithProtocolErrors() {
        assertThrows(AwsException.class, () -> new MetricMath(List.of(
                expression("answer", "(".repeat(140) + "a" + ")".repeat(140), true), metric("a", 60))));
        List<MetricDataQuery> queries = new ArrayList<>();
        queries.add(metric("a", 60));
        String previous = "a";
        for (int i = 0; i < 140; i++) {
            String id = "e" + i;
            queries.add(expression(id, previous + "+1", i == 139));
            previous = id;
        }
        assertThrows(AwsException.class, () -> new MetricMath(queries));
    }

    @Test
    void boundsGeneratedQuotaSamplesAndRejectsUnavailableValues() {
        MetricMath plan = new MetricMath(List.of(expression("quota", "SERVICE_QUOTA(a)", true), metric("a", 60)));
        assertThrows(AwsException.class, () -> plan.evaluate(START, START.plusSeconds(60L * 100802),
                metric -> Map.of(), metric -> 100));
        assertThrows(AwsException.class, () -> plan.evaluate(START, END, metric -> Map.of(), metric -> Double.NaN));
        assertThrows(AwsException.class, () -> plan.evaluate(START, END, metric -> Map.of(), metric -> 0));
    }

    private static MetricDataQuery metric(String id, int period) {
        return new MetricDataQuery(id, new MetricStat("MathTest", id, List.of(), period, "Sum", null),
                null, null, false);
    }

    private static MetricDataQuery expression(String id, String expression, boolean output) {
        return new MetricDataQuery(id, null, expression, null, output);
    }
}
