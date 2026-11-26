package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.core.storage.WriteProfile;
import io.floci.aws.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import io.floci.aws.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudWatchPackedValuesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "eu-west-1";
    private static final String NAMESPACE = "Example/Weighted";
    private static final Instant START = Instant.now().minusSeconds(60);
    private final InMemoryStorage<String, MetricDatum> metricStore = new InMemoryStorage<>();
    private final CloudWatchMetricsService service = new CloudWatchMetricsService(
            metricStore, new InMemoryStorage<>(), new RegionResolver(REGION, "123456789012"), true);
    private final CloudWatchMetricsJsonHandler json = new CloudWatchMetricsJsonHandler(
            service, mock(CloudWatchDashboardsService.class), mock(CloudWatchMetricStreamsService.class), MAPPER);
    private final CloudWatchMetricsQueryHandler query = new CloudWatchMetricsQueryHandler(
            service, mock(CloudWatchDashboardsService.class), mock(CloudWatchMetricStreamsService.class));

    private ObjectNode datum() {
        return MAPPER.createObjectNode().put("MetricName", "Latency").put("Unit", "Milliseconds")
                .put("Timestamp", START.toString());
    }

    private ObjectNode weighted() {
        ObjectNode datum = datum();
        datum.putArray("Values").add(7).add(11);
        datum.putArray("Counts").add(3).add(2);
        return datum;
    }

    private void publish(boolean useQuery, ObjectNode... datums) {
        ObjectNode request = MAPPER.createObjectNode().put("Namespace", NAMESPACE);
        ArrayNode data = request.putArray("MetricData");
        for (ObjectNode datum : datums) {
            data.add(datum);
        }
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        request.properties().forEach(field -> flatten(params, field.getKey(), field.getValue()));
        try (Response response = useQuery ? query.handle("PutMetricData", params, REGION)
                : json.handle("PutMetricData", request, REGION)) {
            assertEquals(200, response.getStatus());
        }
    }

    private static void flatten(MultivaluedMap<String, String> params, String key, JsonNode value) {
        if (value.isObject()) {
            value.properties().forEach(field -> flatten(params, key + "." + field.getKey(), field.getValue()));
        } else if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) {
                flatten(params, key + ".member." + (i + 1), value.get(i));
            }
        } else {
            params.putSingle(key, value.asText());
        }
    }

    private List<CloudWatchMetricsService.Datapoint> statistics(String... statistics) {
        return service.getMetricStatistics(NAMESPACE, "Latency", List.of(), START.minusSeconds(1),
                START.plusSeconds(1), 60, List.of(statistics), "Milliseconds", REGION);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void weightedArrayUsesOneStoredRecordAndSurvivesSerialization(boolean useQuery) throws Exception {
        ObjectNode datum = datum();
        ArrayNode values = datum.putArray("Values");
        ArrayNode counts = datum.putArray("Counts");
        for (int index = 1; index <= 150; index++) {
            values.add(index);
            counts.add(2);
        }
        publish(useQuery, datum);
        List<MetricDatum> stored = metricStore.scan(key -> true);
        assertEquals(1, stored.size());
        MetricDatum restored = MAPPER.readValue(MAPPER.writeValueAsString(stored.getFirst()), MetricDatum.class);
        assertEquals(150, restored.getSampleValues().length);
        assertEquals(150, restored.getSampleWeights().length);
        CloudWatchMetricsService reloaded = new CloudWatchMetricsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, "123456789012"));
        reloaded.putMetricData(NAMESPACE, List.of(restored), REGION);
        assertEquals(statistics("Sum", "p0", "p50", "p100"), reloaded.getMetricStatistics(
                NAMESPACE, "Latency", List.of(), START.minusSeconds(1), START.plusSeconds(1),
                60, List.of("Sum", "p0", "p50", "p100"), "Milliseconds", REGION));
        assertEquals(300, statistics("Sum").getFirst().sampleCount());
        assertEquals(22650, statistics("Sum").getFirst().sum());
    }

    @Test
    void packedSampleArraysDoNotAliasCallers() {
        MetricDatum datum = new MetricDatum();
        double[] values = {7};
        double[] weights = {3};
        datum.setSampleValues(values);
        datum.setSampleWeights(weights);
        values[0] = 99;
        weights[0] = 99;
        datum.getSampleValues()[0] = 88;
        datum.getSampleWeights()[0] = 88;
        assertEquals(7, datum.getSampleValues()[0]);
        assertEquals(3, datum.getSampleWeights()[0]);
    }

    @Test
    void defaultStorageKeepsLegacyPerValueRecords() {
        InMemoryStorage<String, MetricDatum> legacyStore = new InMemoryStorage<>();
        CloudWatchMetricsService legacy = new CloudWatchMetricsService(
                legacyStore, new InMemoryStorage<>(), new RegionResolver(REGION, "123456789012"));
        MetricDatum template = MetricDatum.of("Latency", "Milliseconds", 0, List.of(), START.getEpochSecond());
        legacy.putMetricData(NAMESPACE, MetricValues.fromJson(weighted(), "MetricData.member.1", template), REGION);
        assertEquals(2, legacyStore.keys().size());
        for (MetricDatum stored : legacyStore.scan(key -> true)) {
            assertFalse(stored.hasPackedSamples());
            JsonNode serialized = MAPPER.valueToTree(stored);
            assertFalse(serialized.has("sampleValues"));
            assertFalse(serialized.has("sampleWeights"));
        }
        publish(false, weighted());
        assertEquals(statistics("Sum", "p50", "p90"), legacy.getMetricStatistics(
                NAMESPACE, "Latency", List.of(), START.minusSeconds(1), START.plusSeconds(1),
                60, List.of("Sum", "p50", "p90"), "Milliseconds", REGION));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredServiceUsesPackedWritesOnlyWhenEnabled(boolean enabled) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudwatchmetrics().packWeightedValues()).thenReturn(enabled);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.<MetricDatum>create(eq("cloudwatchmetrics"), eq("cwmetrics.json"),
                any(), eq(WriteProfile.APPEND_HEAVY)))
                .thenReturn(new AccountAwareStorageBackend<>(metricStore, null, "123456789012"));
        CloudWatchMetricsService configured = new CloudWatchMetricsService(
                factory, new RegionResolver(REGION, "123456789012"), null, config);
        MetricDatum template = MetricDatum.of("Latency", "Milliseconds", 0, List.of(), START.getEpochSecond());
        configured.putMetricData(NAMESPACE, MetricValues.fromJson(weighted(), "MetricData.member.1", template), REGION);
        assertEquals(enabled ? 1 : 2, metricStore.keys().size());
        assertEquals(enabled, metricStore.scan(key -> true).getFirst().hasPackedSamples());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneWeightedValueKeepsTheLegacyRecordWithoutArrays(boolean useQuery) {
        ObjectNode datum = datum();
        datum.putArray("Values").add(7);
        datum.putArray("Counts").add(0.5);
        publish(useQuery, datum);
        assertEquals(1, metricStore.keys().size());
        MetricDatum stored = metricStore.scan(key -> true).getFirst();
        assertFalse(stored.hasPackedSamples());
        assertEquals(0.5, stored.getSampleCount());
        assertEquals(3.5, stored.getSum());
        assertEquals(7, stored.getValue());
    }

    @Test
    void packedValuesRetainGetMetricDataStatistics() {
        publish(false, weighted());
        CloudWatchMetricsService.MetricStat sum = new CloudWatchMetricsService.MetricStat(
                NAMESPACE, "Latency", List.of(), 60, "Sum", "Milliseconds");
        CloudWatchMetricsService.MetricStat count = new CloudWatchMetricsService.MetricStat(
                NAMESPACE, "Latency", List.of(), 60, "SampleCount", "Milliseconds");
        List<CloudWatchMetricsService.MetricDataResult> results = service.getMetricData(List.of(
                new CloudWatchMetricsService.MetricDataQuery("sum", sum, null, null, true),
                new CloudWatchMetricsService.MetricDataQuery("count", count, null, null, true)),
                START.minusSeconds(1), START.plusSeconds(1), REGION);
        assertEquals(List.of(43.0), results.getFirst().values());
        assertEquals(List.of(5.0), results.getLast().values());
    }

    @Test
    void packedValuesRetainGetMetricDataPercentiles() {
        publish(false, weighted());
        CloudWatchMetricsService.MetricStat percentile = new CloudWatchMetricsService.MetricStat(
                NAMESPACE, "Latency", List.of(), 60, "p90", "Milliseconds");
        List<CloudWatchMetricsService.MetricDataResult> results = service.getMetricData(List.of(
                new CloudWatchMetricsService.MetricDataQuery("p", percentile, null, null, true)),
                START.minusSeconds(1), START.plusSeconds(1), REGION);
        assertEquals(List.of(11.0), results.getFirst().values());
    }

    @Test
    void packedValuesRetainGetMetricDataPercentilesAndMath() {
        publish(false, weighted());
        assertEquals(1, metricStore.keys().size());
        CloudWatchMetricsService.MetricStat percentile = new CloudWatchMetricsService.MetricStat(
                NAMESPACE, "Latency", List.of(), 60, "p90", "Milliseconds");
        List<CloudWatchMetricsService.MetricDataResult> results = service.getMetricData(List.of(
                new CloudWatchMetricsService.MetricDataQuery("p", percentile, null, null, true),
                new CloudWatchMetricsService.MetricDataQuery("d", null, "p*2", null, true)),
                START.minusSeconds(1), START.plusSeconds(1), REGION);
        assertEquals(List.of(11.0), results.getFirst().values());
        assertEquals(List.of(22.0), results.get(1).values());
    }

}
