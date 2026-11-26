package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CloudWatchPackedValuesIntegrationTest.PackedProfile.class)
class CloudWatchPackedValuesIntegrationTest {
    @Inject
    EmulatorConfig config;

    public static class PackedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.cloudwatchmetrics.pack-weighted-values", "true");
        }
    }

    @Test
    void sdkWeightedBatchRetainsStatisticsWithPackingEnabled() {
        assertTrue(config.services().cloudwatchmetrics().packWeightedValues());
        String namespace = "PackedWeighted/" + UUID.randomUUID();
        Instant timestamp = Instant.now().minusSeconds(60);
        List<Double> values = IntStream.rangeClosed(1, 150).mapToObj(index -> (double) index).toList();
        List<Double> counts = IntStream.rangeClosed(1, 150).mapToObj(index -> 2.0).toList();
        try (CloudWatchClient client = AwsTestClients.create(CloudWatchClient.builder(), "test", Region.US_EAST_1)) {
            client.putMetricData(request -> request.namespace(namespace).metricData(MetricDatum.builder()
                    .metricName("Latency").timestamp(timestamp).values(values).counts(counts).build()));
            Datapoint point = client.getMetricStatistics(request -> request.namespace(namespace)
                    .metricName("Latency").startTime(timestamp.minusSeconds(1)).endTime(timestamp.plusSeconds(1))
                    .period(60).statistics(Statistic.SAMPLE_COUNT, Statistic.SUM, Statistic.MINIMUM,
                            Statistic.MAXIMUM)).datapoints().getFirst();
            assertEquals(300.0, point.sampleCount());
            assertEquals(22650.0, point.sum());
            assertEquals(1.0, point.minimum());
            assertEquals(150.0, point.maximum());
            Map<String, Double> percentiles = client.getMetricStatistics(request -> request.namespace(namespace)
                    .metricName("Latency").startTime(timestamp.minusSeconds(1)).endTime(timestamp.plusSeconds(1))
                    .period(60).extendedStatistics("p0", "p50", "p100"))
                    .datapoints().getFirst().extendedStatistics();
            assertEquals(Map.of("p0", 1.0, "p50", 75.0, "p100", 150.0), percentiles);
        }
    }
}
