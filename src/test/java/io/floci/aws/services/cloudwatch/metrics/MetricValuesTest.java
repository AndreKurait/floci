package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricValuesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void querySamplesPreserveZeroWeightsAndDefaultCounts() {
        MetricDatum template = MetricDatum.of("Latency", "Milliseconds", 0, List.of(), 123);
        List<MetricDatum> packed = MetricValues.fromQuery(Map.of(
                "Values.member.1", List.of("7"), "Values.member.2", List.of("11"),
                "Counts.member.1", List.of("0"), "Counts.member.2", List.of("2")), "", template);
        assertEquals(1, packed.size());
        List<MetricDatum> weighted = MetricValues.expand(packed.getFirst()).toList();
        assertEquals(2, weighted.size());
        assertTrue(weighted.getFirst().hasSampleCount());
        assertEquals(0, weighted.getFirst().getSampleCount());
        assertEquals(7, weighted.getFirst().getMinimum());
        assertEquals(22, weighted.getLast().getSum());
        assertEquals(123, weighted.getLast().getTimestamp());
        List<MetricDatum> unweighted = MetricValues.fromQuery(
                Map.of("Values.member.1", List.of("7")), "", template);
        assertEquals(1, unweighted.getFirst().getSampleCount());
        assertEquals(7, unweighted.getFirst().getSum());
    }

    @Test
    void queryRejectsMissingIndicesBeforeReturningSamples() {
        assertThrows(AwsException.class, () -> MetricValues.fromQuery(
                Map.of("Values.member.2", List.of("7")), "", new MetricDatum()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"Values\":[]}", "{\"Values\":[1],\"Counts\":[]}",
            "{\"Values\":[1],\"Counts\":[-1]}", "{\"Values\":[1],\"Counts\":[1,2]}",
            "{\"Values\":[\"1\"]}", "{\"Values\":[1e200]}",
            "{\"Counts\":[1]}", "{\"Values\":[1],\"Value\":1}"
    })
    void invalidSamplesAreRejected(String json) {
        assertThrows(AwsException.class, () -> MetricValues.fromJson(MAPPER.readTree(json), new MetricDatum()));
    }
}
