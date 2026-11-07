package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CloudWatchMetricValuesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "eu-west-1";
    private static final String NAMESPACE = "Example/Weighted";
    private static final Instant START = Instant.now().minusSeconds(60);
    private final CloudWatchMetricsService service = new CloudWatchMetricsService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, "123456789012"));
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
    void valuesCannotSilentlyOverrideScalarOrStatisticSets(boolean useQuery) {
        ObjectNode scalar = weighted().put("Value", 17);
        assertThrows(AwsException.class, () -> publish(useQuery, scalar));
        ObjectNode stats = weighted();
        stats.putObject("StatisticValues").put("SampleCount", 1).put("Sum", 7)
                .put("Minimum", 7).put("Maximum", 7);
        assertThrows(AwsException.class, () -> publish(useQuery, stats));
        assertTrue(statistics("Sum").isEmpty());
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
