package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudWatchServiceQuotaSdkIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1"})
    void usageQuotaCatalogAndAlarmWorkThroughSdk(String regionName) {
        Region region = Region.of(regionName);
        String account = "000000000355";
        String alarmName = "sdk-usage-quota-" + UUID.randomUUID();
        try (CloudWatchClient cloudWatch = AwsTestClients.create(CloudWatchClient.builder(), account, region);
                ServiceQuotasClient quotas = AwsTestClients.create(ServiceQuotasClient.builder(), account, region)) {
            ServiceQuota quota = quotas.getServiceQuota(request -> request.serviceCode("monitoring")
                    .quotaCode("L-8BC498D4")).quota();
            assertEquals(500.0, quota.value());
            assertEquals("AWS/Usage", quota.usageMetric().metricNamespace());
            assertEquals("CallCount", quota.usageMetric().metricName());
            assertEquals(Map.of("Service", "CloudWatch", "Type", "API", "Resource", "PutMetricData"),
                    quota.usageMetric().metricDimensions());
            List<Dimension> dimensions = List.of(
                    Dimension.builder().name("Service").value("CloudWatch").build(),
                    Dimension.builder().name("Type").value("API").build(),
                    Dimension.builder().name("Resource").value("PutMetricData").build());
            MetricDataQuery usage = MetricDataQuery.builder().id("usage").returnData(false)
                    .metricStat(MetricStat.builder().metric(Metric.builder().namespace("AWS/Usage")
                            .metricName("CallCount").dimensions(dimensions).build()).period(60).stat("Sum").build())
                    .build();
            MetricDataQuery limit = MetricDataQuery.builder().id("quota")
                    .expression("SERVICE_QUOTA(usage)").returnData(true).build();
            Instant end = Instant.ofEpochSecond(Instant.now().getEpochSecond() / 60 * 60);
            assertEquals(List.of(500.0), cloudWatch.getMetricData(request -> request.startTime(end.minusSeconds(60))
                    .endTime(end).metricDataQueries(limit, usage)).metricDataResults().getFirst().values());
            try {
                cloudWatch.putMetricAlarm(request -> request.alarmName(alarmName).actionsEnabled(false)
                        .comparisonOperator("GreaterThanThreshold").threshold(80.0).evaluationPeriods(1)
                        .metrics(MetricDataQuery.builder().id("utilization")
                                .expression("usage/SERVICE_QUOTA(usage)*100").returnData(true).build(), usage));
                assertEquals(alarmName, cloudWatch.describeAlarms(request -> request.alarmNames(alarmName))
                        .metricAlarms().getFirst().alarmName());
            } finally {
                cloudWatch.deleteAlarms(request -> request.alarmNames(alarmName));
            }
            assertTrue(cloudWatch.describeAlarms(request -> request.alarmNames(alarmName)).metricAlarms().isEmpty());
        }
    }
}
