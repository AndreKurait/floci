package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CloudWatchPercentileAlarmTest {
    private static final String REGION = "us-east-1";
    private static final String NAMESPACE = "Example/Latency";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");
    private final InMemoryStorage<String, Alarm> alarms = new InMemoryStorage<>();
    private final CloudWatchMetricsService service = new CloudWatchMetricsService(
            new InMemoryStorage<>(), alarms, new RegionResolver(REGION, "123456789012"));
    private final CloudWatchMetricsJsonHandler json = new CloudWatchMetricsJsonHandler(
            service, mock(CloudWatchDashboardsService.class), mock(CloudWatchMetricStreamsService.class), MAPPER);
    private final CloudWatchMetricsQueryHandler query = new CloudWatchMetricsQueryHandler(
            service, mock(CloudWatchDashboardsService.class), mock(CloudWatchMetricStreamsService.class));

    private static MetricDatum scalar(double value, long timestamp) {
        return MetricDatum.of("Latency", "Milliseconds", value, List.of(), timestamp);
    }

    private static MetricDatum repeated(double value, double count) {
        MetricDatum sample = scalar(value, START.getEpochSecond());
        sample.setMinimum(value);
        sample.setMaximum(value);
        sample.setSampleCount(count);
        sample.setSum(value * count);
        return sample;
    }

    private List<CloudWatchMetricsService.Datapoint> statistics(String... selected) {
        return service.getMetricStatistics(NAMESPACE, "Latency", List.of(), START,
                START.plusSeconds(59), 60, List.of(selected), null, REGION);
    }

    @Test
    void percentilesIgnoreRareOutliersAndRetainBothEndpoints() {
        service.putMetricData(NAMESPACE, List.of(repeated(10, 999), repeated(1000, 1)), REGION);
        CloudWatchMetricsService.Datapoint result = statistics("p0", "p50", "p90.5", "p100").getFirst();
        assertEquals(Map.of("p0", 10.0, "p50", 10.0, "p90.5", 10.0, "p100", 1000.0), result.extendedStatistics());
        assertEquals(1000, result.sampleCount());
        assertEquals(1000, result.maximum());
        assertThrows(UnsupportedOperationException.class, () -> result.extendedStatistics().put("p1", 1.0));
    }

    @Test
    void repeatedSamplesDoNotRequireExpandingTheirCountInMemory() {
        service.putMetricData(NAMESPACE, List.of(repeated(10, 1_000_000_000), repeated(1000, 1)), REGION);
        assertEquals(10.0, statistics("p99").getFirst().extendedStatistics().get("p99"), 0.0);
    }

    @Test
    void aScalarIsRecoverableAndKeepsItsPercentileInGetMetricData() {
        service.putMetricData(NAMESPACE, List.of(scalar(42, START.getEpochSecond())), REGION);
        CloudWatchMetricsService.MetricStat stat = new CloudWatchMetricsService.MetricStat(
                NAMESPACE, "Latency", List.of(), 60, "p50", null);
        CloudWatchMetricsService.MetricDataQuery request = new CloudWatchMetricsService.MetricDataQuery(
                "latency", stat, null, null, true);
        assertEquals(List.of(42.0), service.getMetricData(
                List.of(request), START, START.plusSeconds(59), REGION).getFirst().values());
    }

    @Test
    void negativeValuesDoNotBecomePercentilesButRemainAvailableAsOrdinaryStatistics() {
        service.putMetricData(NAMESPACE, List.of(repeated(-1, 1), repeated(10, 1)), REGION);
        assertTrue(statistics("p90").isEmpty());
        assertEquals(10, statistics("Maximum").getFirst().maximum());
    }

    @Test
    void aStatisticSetWithDifferentExtremesCannotRecoverItsDistribution() {
        MetricDatum sample = repeated(10, 3);
        sample.setMinimum(1);
        sample.setMaximum(20);
        service.putMetricData(NAMESPACE, List.of(sample), REGION);
        assertTrue(statistics("p50").isEmpty());
        assertEquals(10, statistics("Average").getFirst().average());
    }

    @Test
    void periodsAndRegionsHaveIndependentDistributions() {
        service.putMetricData(NAMESPACE, List.of(repeated(10, 20)), REGION);
        service.putMetricData(NAMESPACE, List.of(scalar(1000, START.plusSeconds(60).getEpochSecond())), REGION);
        service.putMetricData(NAMESPACE, List.of(repeated(2000, 100)), "eu-west-1");
        assertEquals(10.0, statistics("p90").getFirst().extendedStatistics().get("p90"), 0.0);
        List<CloudWatchMetricsService.Datapoint> all = service.getMetricStatistics(NAMESPACE, "Latency",
                List.of(), START, START.plusSeconds(119), 60, List.of("p90"), null, REGION);
        assertEquals(List.of(10.0, 1000.0), all.stream().map(point -> point.extendedStatistics().get("p90")).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"p-1", "p101", "pNaN", "pInfinity", "p1e2"})
    void invalidPercentileFailsEvenWhenNoMetricDataExists(String percentile) {
        assertThrows(AwsException.class, () -> statistics(percentile));
    }

    @Test
    void jsonAndQueryRoundTripPercentileSelectionWithoutInventingAnOrdinaryStatistic() throws Exception {
        ObjectNode request = alarmRequest();
        request.put("EvaluateLowSampleCountPercentile", "evaluate");
        json.handle("PutMetricAlarm", request, REGION);
        JsonNode result = jsonAlarm();
        assertEquals("p90", result.path("ExtendedStatistic").asText());
        assertEquals("evaluate", result.path("EvaluateLowSampleCountPercentile").asText());
        assertFalse(result.has("Statistic"));

        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        request.fields().forEachRemaining(field -> parameters.putSingle(field.getKey(), field.getValue().asText()));
        parameters.putSingle("ExtendedStatistic", "p50");
        query.handle("PutMetricAlarm", parameters, REGION);
        Document xml = XmlParser.parseDocument(
                (String) query.handle("DescribeAlarms", new MultivaluedHashMap<>(), REGION).getEntity());
        assertEquals("p50", xml.getElementsByTagName("ExtendedStatistic").item(0).getTextContent());
        assertEquals(0, xml.getElementsByTagName("Statistic").getLength());
        assertEquals("p50", jsonAlarm().path("ExtendedStatistic").asText());

        MetricAlarm original = service.describeAlarms(List.of("latency"), null, REGION).getFirst();
        MetricAlarm loaded = MAPPER.readValue(MAPPER.writeValueAsBytes(original), MetricAlarm.class);
        assertEquals("p50", loaded.getExtendedStatistic());
        assertNull(loaded.getStatistic());
    }

    @Test
    void bothProtocolFormatsReturnPercentileMapAndRejectMixedSelection() throws Exception {
        service.putMetricData(NAMESPACE, List.of(repeated(10, 19), repeated(1000, 1)), REGION);
        ObjectNode request = MAPPER.createObjectNode().put("Namespace", NAMESPACE).put("MetricName", "Latency")
                .put("Period", 60).put("StartTime", START.getEpochSecond()).put("EndTime", START.plusSeconds(59).getEpochSecond());
        request.putArray("ExtendedStatistics").add("p90");
        JsonNode points = (JsonNode) json.handle("GetMetricStatistics", request, REGION).getEntity();
        assertEquals(10, points.path("Datapoints").get(0).path("ExtendedStatistics").path("p90").asDouble());
        assertFalse(points.path("Datapoints").get(0).has("Maximum"));

        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.putSingle("Namespace", NAMESPACE);
        parameters.putSingle("MetricName", "Latency");
        parameters.putSingle("StartTime", START.toString());
        parameters.putSingle("EndTime", START.plusSeconds(59).toString());
        parameters.putSingle("Period", "60");
        parameters.putSingle("ExtendedStatistics.member.1", "p90");
        Document xml = XmlParser.parseDocument(
                (String) query.handle("GetMetricStatistics", parameters, REGION).getEntity());
        assertEquals("p90", xml.getElementsByTagName("key").item(0).getTextContent());
        assertEquals("10.0", xml.getElementsByTagName("value").item(0).getTextContent());
        assertEquals(0, xml.getElementsByTagName("Maximum").getLength());
        request.putArray("Statistics").add("Maximum");
        parameters.putSingle("Statistics.member.1", "Maximum");
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> json.handle("GetMetricStatistics", request, REGION)).getErrorCode());
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> query.handle("GetMetricStatistics", parameters, REGION)).getErrorCode());
    }

    @Test
    void invalidAlarmUpdateDoesNotOverwriteItsPriorStatistic() {
        json.handle("PutMetricAlarm", alarmRequest(), REGION);
        ObjectNode mixed = alarmRequest().put("Statistic", "Average");
        assertThrows(AwsException.class, () -> json.handle("PutMetricAlarm", mixed, REGION));
        ObjectNode unsupported = alarmRequest().put("EvaluateLowSampleCountPercentile", "sometimes");
        assertThrows(AwsException.class, () -> json.handle("PutMetricAlarm", unsupported, REGION));
        assertEquals("p90", jsonAlarm().path("ExtendedStatistic").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"evaluate", "ignore"})
    void bothProtocolsAcceptEachDocumentedLowSampleCountPolicy(String policy) {
        json.handle("PutMetricAlarm", alarmRequest().put("EvaluateLowSampleCountPercentile", policy), REGION);
        assertEquals(policy, jsonAlarm().path("EvaluateLowSampleCountPercentile").asText());

        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        alarmRequest().put("AlarmName", "latency-query").put("EvaluateLowSampleCountPercentile", policy)
                .fields().forEachRemaining(field -> parameters.putSingle(field.getKey(), field.getValue().asText()));
        query.handle("PutMetricAlarm", parameters, REGION);
        assertEquals(policy, service.describeAlarms(List.of("latency-query"), null, REGION)
                .getFirst().getEvaluateLowSampleCountPercentile());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sometimes", "Ignore", "EVALUATE", ""})
    void bothProtocolsRejectAnUndocumentedLowSampleCountPolicy(String policy) {
        ObjectNode request = alarmRequest().put("EvaluateLowSampleCountPercentile", policy);
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> json.handle("PutMetricAlarm", request, REGION)).getErrorCode());
        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        request.fields().forEachRemaining(field -> parameters.putSingle(field.getKey(), field.getValue().asText()));
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> query.handle("PutMetricAlarm", parameters, REGION)).getErrorCode());
        assertTrue(service.describeAlarms(List.of("latency"), null, REGION).isEmpty());
    }

    /**
     * AWS applies the low-sample policy below 10/(1-p) samples for p in [0.5, 1) and below 10/p
     * for p in (0, 0.5); p0 and p100 have no floor. One sample short of the floor holds the state
     * under {@code ignore}; reaching the floor lets the alarm transition.
     */
    @ParameterizedTest
    @CsvSource({"p50,20", "p90,100", "p99,1000", "p99.9,10000", "p25,40", "p10,100", "p0,1", "p100,1"})
    @SuppressWarnings("unchecked")
    void ignoreFloorFollowsTheDocumentedFormula(String percentile, int floor) {
        json.handle("PutMetricAlarm", alarmRequest().put("ExtendedStatistic", percentile)
                .put("EvaluateLowSampleCountPercentile", "ignore"), REGION);
        MetricAlarm alarm = service.describeAlarms(List.of("latency"), null, REGION).getFirst();
        alarm.setActionsEnabled(false);
        AlarmEvaluator evaluator = new AlarmEvaluator(service, mock(Instance.class));
        try {
            service.setAlarmState("latency", "OK", "initial", null, REGION);
            long timestamp = Instant.now().getEpochSecond() / 60 * 60 - 30;
            if (floor > 1) {
                service.putMetricData(NAMESPACE, samples("Latency", 1000, floor - 1, timestamp), REGION);
                evaluator.evaluate(alarm);
                assertEquals("OK", alarm.getStateValue(), percentile + " below its floor");
            }
            service.putMetricData(NAMESPACE, samples("Latency", 1000, 1, timestamp), REGION);
            evaluator.evaluate(alarm);
            assertEquals("ALARM", alarm.getStateValue(), percentile + " at its floor");
        } finally {
            evaluator.stop();
        }
    }

    /**
     * With {@code ignore}, a breach seen in too few samples must not move the alarm; the same
     * samples under {@code evaluate} do. Once the sample count reaches the floor the alarm is
     * evaluated again, and a low-sample recovery likewise leaves an ALARM in place.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ignoreHoldsTheStateWhileTooFewSamplesAndEvaluateDoesNot() {
        json.handle("PutMetricAlarm", alarmRequest().put("EvaluateLowSampleCountPercentile", "ignore"), REGION);
        json.handle("PutMetricAlarm", alarmRequest().put("AlarmName", "latency-evaluate")
                .put("MetricName", "LatencyEvaluate").put("EvaluateLowSampleCountPercentile", "evaluate"), REGION);
        MetricAlarm ignored = service.describeAlarms(List.of("latency"), null, REGION).getFirst();
        MetricAlarm evaluated = service.describeAlarms(List.of("latency-evaluate"), null, REGION).getFirst();
        ignored.setActionsEnabled(false);
        evaluated.setActionsEnabled(false);
        AlarmEvaluator evaluator = new AlarmEvaluator(service, mock(Instance.class));
        try {
            service.setAlarmState("latency", "OK", "initial", null, REGION);
            service.setAlarmState("latency-evaluate", "OK", "initial", null, REGION);
            long timestamp = Instant.now().getEpochSecond() / 60 * 60 - 30;
            service.putMetricData(NAMESPACE, samples("Latency", 1000, 99, timestamp), REGION);
            service.putMetricData(NAMESPACE, samples("LatencyEvaluate", 1000, 99, timestamp), REGION);

            evaluator.evaluate(ignored);
            evaluator.evaluate(evaluated);
            assertEquals("OK", ignored.getStateValue());
            assertEquals("initial", ignored.getStateReason());
            assertEquals("ALARM", evaluated.getStateValue());

            service.putMetricData(NAMESPACE, samples("Latency", 1000, 1, timestamp), REGION);
            evaluator.evaluate(ignored);
            assertEquals("ALARM", ignored.getStateValue());

            json.handle("PutMetricAlarm", alarmRequest().put("AlarmName", "latency-recover")
                    .put("MetricName", "LatencyRecover").put("EvaluateLowSampleCountPercentile", "ignore"), REGION);
            MetricAlarm recovering = service.describeAlarms(List.of("latency-recover"), null, REGION).getFirst();
            recovering.setActionsEnabled(false);
            service.setAlarmState("latency-recover", "ALARM", "breached earlier", null, REGION);
            service.putMetricData(NAMESPACE, samples("LatencyRecover", 1, 5, timestamp), REGION);
            evaluator.evaluate(recovering);
            assertEquals("ALARM", recovering.getStateValue());
        } finally {
            evaluator.stop();
        }
    }

    /** Only the datapoints that decide the state are checked; an older thin period does not hold it. */
    @Test
    @SuppressWarnings("unchecked")
    void ignoreLooksOnlyAtTheDatapointsThatDecideTheState() {
        json.handle("PutMetricAlarm", alarmRequest().put("EvaluateLowSampleCountPercentile", "ignore"), REGION);
        MetricAlarm alarm = service.describeAlarms(List.of("latency"), null, REGION).getFirst();
        alarm.setActionsEnabled(false);
        AlarmEvaluator evaluator = new AlarmEvaluator(service, mock(Instance.class));
        try {
            service.setAlarmState("latency", "OK", "initial", null, REGION);
            long timestamp = Instant.now().getEpochSecond() / 60 * 60 - 30;
            service.putMetricData(NAMESPACE, samples("Latency", 1000, 5, timestamp - 60), REGION);
            service.putMetricData(NAMESPACE, samples("Latency", 1000, 100, timestamp), REGION);
            evaluator.evaluate(alarm);
            assertEquals("ALARM", alarm.getStateValue());
        } finally {
            evaluator.stop();
        }
    }

    /** No samples at all is missing data, not a low sample count: TreatMissingData still decides. */
    @Test
    @SuppressWarnings("unchecked")
    void ignoreLeavesMissingDataToTreatMissingData() {
        json.handle("PutMetricAlarm", alarmRequest().put("EvaluateLowSampleCountPercentile", "ignore"), REGION);
        MetricAlarm alarm = service.describeAlarms(List.of("latency"), null, REGION).getFirst();
        alarm.setActionsEnabled(false);
        AlarmEvaluator evaluator = new AlarmEvaluator(service, mock(Instance.class));
        try {
            service.setAlarmState("latency", "ALARM", "initial", null, REGION);
            evaluator.evaluate(alarm);
            assertEquals("OK", alarm.getStateValue());
        } finally {
            evaluator.stop();
        }
    }

    private static List<MetricDatum> samples(String metricName, double value, int count, long timestamp) {
        List<MetricDatum> samples = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            samples.add(MetricDatum.of(metricName, "Milliseconds", value, List.of(), timestamp));
        }
        return samples;
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualPercentileTransitionsAlarmAndMissingDataKeepsItsConfiguredMeaning() {
        json.handle("PutMetricAlarm", alarmRequest(), REGION);
        MetricAlarm alarm = service.describeAlarms(List.of("latency"), null, REGION).getFirst();
        alarm.setActionsEnabled(false);
        AlarmEvaluator evaluator = new AlarmEvaluator(service, mock(Instance.class));
        try {
            service.setAlarmState("latency", "ALARM", "initial", null, REGION);
            evaluator.evaluate(alarm);
            assertEquals("OK", alarm.getStateValue());
            long timestamp = Instant.now().getEpochSecond() / 60 * 60 - 30;
            List<MetricDatum> samples = new ArrayList<>();
            for (int index = 0; index < 19; index++) {
                samples.add(scalar(10, timestamp));
            }
            samples.add(scalar(1000, timestamp));
            service.putMetricData(NAMESPACE, samples, REGION);
            service.setAlarmState("latency", "ALARM", "force reevaluation", null, REGION);
            evaluator.evaluate(alarm);
            assertEquals("OK", alarm.getStateValue());
            service.putMetricData(NAMESPACE, List.of(scalar(1000, timestamp), scalar(1000, timestamp)), REGION);
            evaluator.evaluate(alarm);
            assertEquals("ALARM", alarm.getStateValue());
            alarm.setExtendedStatistic(null);
            assertDoesNotThrow(() -> evaluator.evaluate(
                    service.describeAlarms(List.of("latency"), null, REGION).getFirst()));
        } finally {
            evaluator.stop();
        }
    }

    private ObjectNode alarmRequest() {
        return MAPPER.createObjectNode().put("AlarmName", "latency").put("Namespace", NAMESPACE)
                .put("MetricName", "Latency").put("ExtendedStatistic", "p90").put("Period", 60)
                .put("EvaluationPeriods", 1).put("Threshold", 100).put("ComparisonOperator", "GreaterThanThreshold")
                .put("TreatMissingData", "notBreaching");
    }

    private JsonNode jsonAlarm() {
        try (Response response = json.handle("DescribeAlarms", MAPPER.createObjectNode(), REGION)) {
            return ((JsonNode) response.getEntity()).path("MetricAlarms").get(0);
        }
    }
}
