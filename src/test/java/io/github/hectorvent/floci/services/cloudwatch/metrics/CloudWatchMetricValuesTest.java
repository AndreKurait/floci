package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WriteProfile;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudWatchMetricValuesTest {
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
        legacy.putMetricData(NAMESPACE, MetricValues.fromJson(weighted(), template), REGION);
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
                factory, new RegionResolver(REGION, "123456789012"), null, null, config);
        MetricDatum template = MetricDatum.of("Latency", "Milliseconds", 0, List.of(), START.getEpochSecond());
        configured.putMetricData(NAMESPACE, MetricValues.fromJson(weighted(), template), REGION);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void weightedValuesRetainTheirDistributionAndStatistics(boolean useQuery) {
        publish(useQuery, weighted());
        CloudWatchMetricsService.Datapoint point = statistics("SampleCount").getFirst();
        assertEquals(5, point.sampleCount());
        assertEquals(43, point.sum());
        assertEquals(7, point.minimum());
        assertEquals(11, point.maximum());
        assertEquals(Map.of("p50", 7.0, "p90", 11.0), statistics("p50", "p90").getFirst().extendedStatistics());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentCountsMeanOneOccurrenceAndMixWithScalarData(boolean useQuery) {
        ObjectNode datum = weighted();
        datum.remove("Counts");
        publish(useQuery, datum, datum().put("Value", 13));
        CloudWatchMetricsService.Datapoint point = statistics("Sum").getFirst();
        assertEquals(3, point.sampleCount());
        assertEquals(31, point.sum());
        assertEquals(11.0, statistics("p50").getFirst().extendedStatistics().get("p50"), 0.0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void zeroWeightsRetainExtremaWithoutInventingSamples(boolean useQuery) {
        ObjectNode datum = weighted();
        datum.putArray("Counts").add(0).add(1);
        publish(useQuery, datum);
        CloudWatchMetricsService.Datapoint point = statistics("Sum").getFirst();
        assertEquals(1, point.sampleCount());
        assertEquals(11, point.sum());
        assertEquals(7, point.minimum());
        assertEquals(11, point.maximum());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allZeroWeightsAggregateWithLaterScalarSamples(boolean useQuery) {
        ObjectNode datum = weighted();
        datum.putArray("Counts").add(0).add(0);
        publish(useQuery, datum);
        CloudWatchMetricsService.Datapoint empty = statistics("Sum").getFirst();
        assertEquals(0, empty.sampleCount());
        assertEquals(0, empty.sum());
        assertEquals(7, empty.minimum());
        assertEquals(11, empty.maximum());
        publish(useQuery, datum().put("Value", 9));
        CloudWatchMetricsService.Datapoint combined = statistics("Sum").getFirst();
        assertEquals(1, combined.sampleCount());
        assertEquals(9, combined.sum());
        assertEquals(7, combined.minimum());
        assertEquals(11, combined.maximum());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void zeroWeightedExtremaRemainAvailableAsEndpointPercentiles(boolean useQuery) {
        ObjectNode datum = weighted();
        datum.putArray("Counts").add(0).add(1);
        publish(useQuery, datum);
        assertEquals(Map.of("p0", 7.0, "p100", 11.0), statistics("p0", "p100").getFirst().extendedStatistics());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void zeroWeightedMaximumIsNotReplacedByTheLastPositiveSample(boolean useQuery) {
        ObjectNode datum = weighted();
        datum.putArray("Counts").add(1).add(0);
        publish(useQuery, datum);
        assertEquals(Map.of("p0", 7.0, "p100", 11.0), statistics("p0", "p100").getFirst().extendedStatistics());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allZeroWeightsExposeEndpointsWithoutInventingInteriorPercentiles(boolean useQuery) {
        ObjectNode datum = weighted();
        datum.putArray("Counts").add(0).add(0);
        publish(useQuery, datum);
        assertEquals(Map.of("p0", 7.0, "p100", 11.0),
                statistics("p0", "p90", "p100").getFirst().extendedStatistics());
        assertTrue(statistics("p90").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitZeroStatisticSetsRemainDistinctFromScalarZero(boolean useQuery) {
        ObjectNode datum = datum();
        datum.putObject("StatisticValues").put("SampleCount", 0).put("Sum", 0)
                .put("Minimum", 7).put("Maximum", 11);
        publish(useQuery, datum);
        CloudWatchMetricsService.Datapoint point = statistics("Sum").getFirst();
        assertEquals(0, point.sampleCount());
        assertEquals(0, point.sum());
        assertEquals(7, point.minimum());
        assertEquals(11, point.maximum());
        publish(useQuery, datum().put("Value", 0));
        CloudWatchMetricsService.Datapoint combined = statistics("Sum").getFirst();
        assertEquals(1, combined.sampleCount());
        assertEquals(0, combined.sum());
        assertEquals(0, combined.minimum());
        assertEquals(11, combined.maximum());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 0.5})
    void statisticPresenceSurvivesStoredDatumRoundTrip(double count) throws Exception {
        MetricDatum datum = MetricDatum.of("Latency", "Milliseconds", 7, List.of(), START.getEpochSecond());
        datum.setSampleCount(count);
        datum.setSum(7 * count);
        datum.setMinimum(7);
        datum.setMaximum(7);
        String stored = MAPPER.writeValueAsString(datum);
        assertTrue(!stored.contains("sampleCountSet") && !stored.contains("hasSampleCount"));
        MetricDatum restored = MAPPER.readValue(stored, MetricDatum.class);
        service.putMetricData(NAMESPACE, List.of(restored), REGION);
        CloudWatchMetricsService.Datapoint point = statistics("Sum").getFirst();
        assertEquals(count, point.sampleCount());
        assertEquals(7 * count, point.sum());
        assertEquals(7, point.minimum());
        assertEquals(7, point.maximum());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedCountsDoNotExpandIntoIndividualSamples(boolean useQuery) {
        ObjectNode datum = weighted();
        datum.putArray("Counts").add(1_000_000_000).add(1);
        publish(useQuery, datum);
        assertEquals(1_000_000_001, statistics("SampleCount").getFirst().sampleCount());
        assertEquals(7.0, statistics("p99").getFirst().extendedStatistics().get("p99"), 0.0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidWeightsDoNotPublishEarlierDatumsInTheSameRequest(boolean useQuery) {
        ObjectNode datum = weighted();
        datum.putArray("Counts").add(3);
        assertThrows(AwsException.class, () -> publish(useQuery, datum().put("Value", 99), datum));
        assertTrue(statistics("SampleCount").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void countsWithoutValuesAreRejected(boolean useQuery) {
        ObjectNode datum = datum();
        datum.putArray("Counts").add(2);
        assertThrows(AwsException.class, () -> publish(useQuery, datum));
        assertTrue(statistics("Sum").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void valuesCannotSilentlyOverrideAScalarValue(boolean useQuery) {
        ObjectNode scalar = weighted().put("Value", 17);
        assertThrows(AwsException.class, () -> publish(useQuery, scalar));
        assertTrue(statistics("Sum").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void statisticValuesBesideValuesAreAcceptedAndTheDistributionIsCountedOnce(boolean useQuery) {
        // A distribution's summary sent with its own Values and Counts, as the CloudWatch agent publishes it.
        ObjectNode datum = weighted();
        datum.putObject("StatisticValues").put("SampleCount", 5).put("Sum", 43)
                .put("Minimum", 7).put("Maximum", 11);
        publish(useQuery, datum);
        CloudWatchMetricsService.Datapoint point = statistics("SampleCount").getFirst();
        assertEquals(5, point.sampleCount());
        assertEquals(43, point.sum());
        assertEquals(7, point.minimum());
        assertEquals(11, point.maximum());
        assertEquals(Map.of("p50", 7.0, "p90", 11.0), statistics("p50", "p90").getFirst().extendedStatistics());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneHundredFiftyValuesAreAcceptedAndTheNextIsRejected(boolean useQuery) {
        ObjectNode datum = datum();
        ArrayNode values = datum.putArray("Values");
        for (int value = 0; value < 150; value++) {
            values.add(value);
        }
        publish(useQuery, datum);
        assertEquals(150, statistics("SampleCount").getFirst().sampleCount());
        values.add(150);
        assertThrows(AwsException.class, () -> publish(useQuery, datum));
        assertEquals(150, statistics("SampleCount").getFirst().sampleCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidNumbersAndCountsAreRejectedBeforePublishing(boolean useQuery) {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Math.scalb(1.0, 361)}) {
            ObjectNode datum = datum();
            datum.putArray("Values").add(value);
            assertThrows(AwsException.class, () -> publish(useQuery, datum));
        }
        for (double count : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            ObjectNode datum = datum();
            datum.putArray("Values").add(7);
            datum.putArray("Counts").add(count);
            assertThrows(AwsException.class, () -> publish(useQuery, datum));
        }
        assertTrue(statistics("Sum").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void negativeValuesKeepTheirOrdinaryStatisticsWithoutInventingPercentiles(boolean useQuery) {
        ObjectNode datum = weighted();
        datum.putArray("Values").add(-7).add(11);
        publish(useQuery, datum);
        assertEquals(1, statistics("Sum").getFirst().sum());
        assertTrue(statistics("p90").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void entityMetricDataUsesTheSameWeightedParser(boolean useQuery) {
        ObjectNode request = MAPPER.createObjectNode().put("Namespace", NAMESPACE)
                .put("StrictEntityValidation", true);
        ObjectNode entity = request.putArray("EntityMetricData").addObject();
        entity.putObject("Entity").putObject("KeyAttributes")
                .put("Type", "Service").put("Name", "example").put("Environment", "test");
        entity.putArray("MetricData").add(weighted());
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        request.properties().forEach(field -> flatten(params, field.getKey(), field.getValue()));
        try (Response response = useQuery ? query.handle("PutMetricData", params, REGION)
                : json.handle("PutMetricData", request, REGION)) {
            assertEquals(200, response.getStatus());
        }
        assertEquals(5, statistics("SampleCount").getFirst().sampleCount());
        assertEquals(43, statistics("Sum").getFirst().sum());
    }
}
