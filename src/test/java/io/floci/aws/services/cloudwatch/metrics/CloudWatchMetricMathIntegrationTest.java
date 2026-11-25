package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudWatchMetricMathIntegrationTest {
    @Test
    void sdkArithmeticReadsTheActualServiceQuotaForItsMetric() {
        Instant timestamp = Instant.now().minusSeconds(60);
        double quota = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "ServiceQuotasV20190624.GetServiceQuota")
                .body("{\"ServiceCode\":\"lambda\",\"QuotaCode\":\"L-B99A9384\"}".getBytes(StandardCharsets.UTF_8))
                .when().post("/").then().statusCode(200).extract().jsonPath().getDouble("Quota.Value");
        try (CloudWatchClient metrics = AwsTestClients.create(CloudWatchClient.builder(), "test", Region.US_EAST_1)) {
            metrics.putMetricData(request -> request.namespace("AWS/Lambda").metricData(MetricDatum.builder()
                    .metricName("ConcurrentExecutions").timestamp(timestamp).value(125.0).build()));
            List<MetricDataResult> result = metrics.getMetricData(request -> request
                    .startTime(timestamp.minusSeconds(1)).endTime(timestamp.plusSeconds(1)).metricDataQueries(
                            MetricDataQuery.builder().id("utilization")
                                    .expression("100 * usage / SERVICE_QUOTA(usage)").returnData(true).build(),
                            MetricDataQuery.builder().id("usage").returnData(false).metricStat(MetricStat.builder()
                                    .metric(Metric.builder().namespace("AWS/Lambda")
                                            .metricName("ConcurrentExecutions").build())
                                    .period(60).stat("Maximum").build()).build()))
                    .metricDataResults();
            assertEquals(1, result.size());
            assertEquals("utilization", result.getFirst().id());
            assertEquals(1, result.getFirst().values().size());
            assertEquals(100 * 125 / quota, result.getFirst().values().getFirst(), 0.000001);
        }
    }
}
