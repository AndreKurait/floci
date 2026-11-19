package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService.MetricDataQuery;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService.MetricDataResult;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService.MetricStat;
import io.floci.aws.services.cloudwatch.metrics.model.AlarmMetricDataQuery;
import io.floci.aws.services.cloudwatch.metrics.model.Dimension;
import io.floci.aws.services.cloudwatch.metrics.model.MetricAlarm;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import io.floci.aws.services.servicequotas.ServiceQuotasService;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code SERVICE_QUOTA(metric)} "Returns the service quota for the given usage metric" (CloudWatch
 * User Guide, "Using metric math"): the quota whose Service Quotas UsageMetric is the metric. The
 * CloudWatch quota "Rate of PutMetricData requests" (L-8BC498D4, 500 per second) has the AWS/Usage
 * CallCount usage metric with "Service": "CloudWatch", "Type": "API" and "Resource": "PutMetricData"
 * (CloudWatch User Guide, "CloudWatch usage metrics"); a Class dimension of None may accompany them.
 */
class CloudWatchServiceQuotaTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "123456789012";
    private static final List<Dimension> PUT_METRIC_DATA = List.of(new Dimension("Resource", "PutMetricData"),
            new Dimension("Service", "CloudWatch"), new Dimension("Type", "API"));

    private final Instant bucket = Instant.ofEpochSecond(Instant.now().getEpochSecond() / 60 * 60 - 60);
    private final ServiceQuotasService serviceQuotas =
            new ServiceQuotasService(new InMemoryStorageFactory(ACCOUNT), new ObjectMapper(), config());
    private final CloudWatchMetricsService service = new CloudWatchMetricsService(
            new InMemoryStorageFactory(ACCOUNT), new RegionResolver(REGION, ACCOUNT), null, serviceQuotas);

    private static EmulatorConfig config() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().servicequotas().standardOnDemandVcpus()).thenReturn(5);
        return config;
    }

    private static AlarmMetricDataQuery usage(String id, String namespace, String name, List<Dimension> dimensions) {
        return new AlarmMetricDataQuery(id, null, null, false, null, null, new AlarmMetricDataQuery.MetricStat(
                new AlarmMetricDataQuery.Metric(namespace, name, dimensions.stream()
                        .map(d -> new AlarmMetricDataQuery.MetricDimension(d.name(), d.value())).toList()),
                60, "Sum", null));
    }

    /** A usage alarm in the shape the Service Quotas console creates: usage as a percentage of the quota. */
    private static MetricAlarm quotaAlarm(AlarmMetricDataQuery usage) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("quota");
        alarm.setMetrics(List.of(
                new AlarmMetricDataQuery("pct_utilization", "(" + usage.id() + "/SERVICE_QUOTA(" + usage.id()
                        + "))*100", "% Utilization", true, null, null, null), usage));
        alarm.setEvaluationPeriods(1);
        alarm.setThreshold(80.0);
        alarm.setComparisonOperator("GreaterThanThreshold");
        alarm.setTreatMissingData("notBreaching");
        alarm.setActionsEnabled(false);
        return alarm;
    }

    private List<MetricDataResult> quotaData(String namespace, String name, List<Dimension> dimensions) {
        return service.getMetricData(List.of(
                new MetricDataQuery("quota", null, "SERVICE_QUOTA(usage)", null, true),
                new MetricDataQuery("usage", new MetricStat(namespace, name, dimensions, 60, "Sum", null),
                        null, null, false)), bucket, bucket.plusSeconds(60), REGION);
    }

    @Test
    void anAlarmOnThePutMetricDataUsageMetricIsAccepted() {
        service.putMetricAlarm(quotaAlarm(usage("m1", "AWS/Usage", "CallCount", PUT_METRIC_DATA)), REGION);
        assertEquals(List.of("quota"), service.describeAlarms(List.of("quota"), null, REGION).stream()
                .map(MetricAlarm::getAlarmName).toList());
        List<Dimension> withClass = new ArrayList<>(PUT_METRIC_DATA);
        withClass.add(new Dimension("Class", "None"));
        service.putMetricAlarm(quotaAlarm(usage("m1", "AWS/Usage", "CallCount", withClass)), REGION);
        assertEquals(List.of(500.0), quotaData("AWS/Usage", "CallCount", withClass).getFirst().values());
    }

    @Test
    void serviceQuotaReturnsTheQuotaOfTheMatchingUsageMetric() {
        assertEquals(List.of(500.0), quotaData("AWS/Usage", "CallCount", PUT_METRIC_DATA).getFirst().values());
        assertEquals(List.of(5000.0), quotaData("AWS/Lambda", "ConcurrentExecutions", List.of()).getFirst().values());
    }

    @Test
    void utilizationDividesReportedUsageByTheQuota() {
        service.putMetricData("AWS/Usage", List.of(MetricDatum.of("CallCount", "None", 125, PUT_METRIC_DATA,
                bucket.getEpochSecond())), REGION);
        MetricDataResult utilization = service.getMetricData(List.of(
                new MetricDataQuery("pct", null, "(m1/SERVICE_QUOTA(m1))*100", null, true),
                new MetricDataQuery("m1", new MetricStat("AWS/Usage", "CallCount", PUT_METRIC_DATA, 60, "Sum", null),
                        null, null, false)), bucket, bucket.plusSeconds(60), REGION).getFirst();
        assertEquals(List.of(25.0), utilization.values());
    }

    @Test
    void aMetricThatNoQuotaTracksIsRefused() {
        List<Dimension> listMetrics = List.of(new Dimension("Resource", "ListMetrics"),
                new Dimension("Service", "CloudWatch"), new Dimension("Type", "API"));
        List<Dimension> otherClass = new ArrayList<>(PUT_METRIC_DATA);
        otherClass.add(new Dimension("Class", "Standard"));
        for (AlarmMetricDataQuery metric : List.of(
                usage("m1", "AWS/Usage", "CallCount", listMetrics),
                usage("m1", "AWS/Usage", "CallCount", PUT_METRIC_DATA.subList(1, 3)),
                usage("m1", "AWS/Usage", "CallCount", otherClass),
                usage("m1", "Example/App", "Requests", List.of()),
                usage("m1", "AWS/Lambda", "ConcurrentExecutions", List.of(new Dimension("FunctionName", "f"))))) {
            AwsException refused = assertThrows(AwsException.class,
                    () -> service.putMetricAlarm(quotaAlarm(metric), REGION));
            assertEquals("InvalidParameterValue", refused.getErrorCode());
        }
        assertThrows(AwsException.class, () -> quotaData("AWS/Usage", "CallCount", listMetrics));
    }

    @Test
    void theCatalogQuotaCarriesItsUsageMetric() {
        JsonNode quota = serviceQuotas.getServiceQuota("monitoring", "L-8BC498D4", REGION, ACCOUNT).path("Quota");
        assertEquals("Rate of PutMetricData requests", quota.path("QuotaName").asText());
        assertEquals(500.0, quota.path("Value").asDouble());
        JsonNode metric = quota.path("UsageMetric");
        assertEquals("AWS/Usage", metric.path("MetricNamespace").asText());
        assertEquals("CallCount", metric.path("MetricName").asText());
        assertEquals(Map.of("Resource", "PutMetricData", "Service", "CloudWatch", "Type", "API"),
                new ObjectMapper().convertValue(metric.path("MetricDimensions"), Map.class));
        assertEquals("Sum", metric.path("MetricStatisticRecommendation").asText());
        assertTrue(serviceQuotas.listServiceQuotas("monitoring", null, null, null, null, REGION, ACCOUNT)
                .path("Quotas").findValuesAsText("QuotaCode").contains("L-8BC498D4"));
    }
}
