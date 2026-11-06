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
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmMetricDataQuery;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class CloudWatchMetricMathTest {
    private static final String REGION = "us-east-1";
    private static final String NAMESPACE = "Example/MetricMath";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Instant bucket = Instant.ofEpochSecond(Instant.now().getEpochSecond() / 60 * 60 - 60);
    private final CloudWatchMetricsService service = new CloudWatchMetricsService(
            new InMemoryStorage<>(), new InMemoryStorage<String, Alarm>(),
            new RegionResolver(REGION, "123456789012"));
    private final CloudWatchMetricsJsonHandler json = new CloudWatchMetricsJsonHandler(
            service, mock(CloudWatchDashboardsService.class), mock(CloudWatchMetricStreamsService.class), MAPPER);
    private final CloudWatchMetricsQueryHandler query = new CloudWatchMetricsQueryHandler(
            service, mock(CloudWatchDashboardsService.class), mock(CloudWatchMetricStreamsService.class));

    @Test
    void bothProtocolsCalculateMathAndHonorAccountAndPeriodSelection() throws Exception {
        put("Errors", 5);
        put("Requests", 20);
        ObjectNode request = MAPPER.createObjectNode()
                .put("StartTime", bucket.getEpochSecond()).put("EndTime", bucket.plusSeconds(60).getEpochSecond());
        request.set("MetricDataQueries", MAPPER.valueToTree(alarm("100*e/r").getMetrics()));
        JsonNode result = (JsonNode) json.handle("GetMetricData", request, REGION).getEntity();
        assertEquals(25.0, result.path("MetricDataResults").get(0).path("Values").get(0).asDouble());
        ((ObjectNode) request.path("MetricDataQueries").get(0)).put("Period", 300);
        assertThrows(AwsException.class, () -> json.handle("GetMetricData", request, REGION));
        ((ObjectNode) request.path("MetricDataQueries").get(0)).put("Period", 60);
        ((ObjectNode) request.path("MetricDataQueries").get(1)).put("AccountId", "999999999999");
        assertThrows(AwsException.class, () -> json.handle("GetMetricData", request, REGION));

        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.putSingle("StartTime", bucket.toString());
        parameters.putSingle("EndTime", bucket.plusSeconds(60).toString());
        parameters.putSingle("MetricDataQueries.member.1.Id", "answer");
        parameters.putSingle("MetricDataQueries.member.1.Expression", "100*e/r");
        parameters.putSingle("MetricDataQueries.member.1.ReturnData", "true");
        int index = 2;
        for (String name : List.of("Errors", "Requests")) {
            String prefix = "MetricDataQueries.member." + index++;
            parameters.putSingle(prefix + ".Id", name.equals("Errors") ? "e" : "r");
            parameters.putSingle(prefix + ".ReturnData", "false");
            parameters.putSingle(prefix + ".MetricStat.Metric.Namespace", NAMESPACE);
            parameters.putSingle(prefix + ".MetricStat.Metric.MetricName", name);
            parameters.putSingle(prefix + ".MetricStat.Period", "60");
            parameters.putSingle(prefix + ".MetricStat.Stat", "Sum");
        }
        Document xml = XmlParser.parseDocument(
                (String) query.handle("GetMetricData", parameters, REGION).getEntity());
        assertEquals("answer", xml.getElementsByTagName("Id").item(0).getTextContent());
        assertEquals("25.0", xml.getElementsByTagName("Values").item(0).getTextContent().strip());
        parameters.putSingle("MetricDataQueries.member.1.Period", "300");
        assertThrows(AwsException.class, () -> query.handle("GetMetricData", parameters, REGION));
        parameters.putSingle("MetricDataQueries.member.1.Period", "60");
        parameters.putSingle("MetricDataQueries.member.2.AccountId", "999999999999");
        assertThrows(AwsException.class, () -> query.handle("GetMetricData", parameters, REGION));
    }

    @Test
    void rejectsAnInvalidUpdateWithoutReplacingThePreviousAlarm() {
        service.putMetricAlarm(alarm("100*e/r"), REGION);
        service.setAlarmState("math", "ALARM", "preserved", null, REGION);
        List<AlarmMetricDataQuery> before = stored().getMetrics();

        assertThrows(AwsException.class, () -> service.putMetricAlarm(alarm("unknown+1"), REGION));
        assertEquals(before, stored().getMetrics());
        assertEquals("ALARM", stored().getStateValue());
        assertThrows(AwsException.class, () -> service.putMetricAlarm(alarm("SERVICE_QUOTA(e)"), REGION));
        assertEquals(before, stored().getMetrics());
    }

    @Test
    @SuppressWarnings("unchecked")
    void realSamplesDriveErrorRatioAlarmAndRecovery() {
        service.putMetricAlarm(alarm("100*e/r"), REGION);
        put("Errors", 30);
        put("Requests", 100);
        AlarmEvaluator evaluator = new AlarmEvaluator(service, mock(Instance.class));
        try {
            evaluator.evaluate(stored());
            assertEquals("ALARM", stored().getStateValue());
            put("Requests", 900);
            evaluator.evaluate(stored());
            assertEquals("OK", stored().getStateValue());
        } finally {
            evaluator.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({"missing,INSUFFICIENT_DATA", "notBreaching,OK", "breaching,ALARM", "ignore,ALARM"})
    @SuppressWarnings("unchecked")
    void divisionByZeroUsesTheAlarmMissingDataPolicy(String policy, String expected) {
        MetricAlarm alarm = alarm("100*e/r");
        alarm.setTreatMissingData(policy);
        service.putMetricAlarm(alarm, REGION);
        service.setAlarmState("math", "ALARM", "previous", null, REGION);
        put("Errors", 10);
        put("Requests", 0);
        AlarmEvaluator evaluator = new AlarmEvaluator(service, mock(Instance.class));
        try {
            evaluator.evaluate(stored());
            assertEquals(expected, stored().getStateValue());
        } finally {
            evaluator.stop();
        }
    }

    @Test
    void getMetricDataExcludesTheEndBoundary() {
        put("Errors", 5);
        put("Requests", 20);
        service.putMetricData(NAMESPACE, List.of(
                MetricDatum.of("Errors", "Count", 100, List.of(), bucket.plusSeconds(60).getEpochSecond())), REGION);
        ObjectNode request = MAPPER.createObjectNode()
                .put("StartTime", bucket.getEpochSecond()).put("EndTime", bucket.plusSeconds(60).getEpochSecond());
        request.set("MetricDataQueries", MAPPER.valueToTree(alarm("e+r").getMetrics()));
        JsonNode result = (JsonNode) json.handle("GetMetricData", request, REGION).getEntity();
        assertEquals(1, result.path("MetricDataResults").get(0).path("Values").size());
        assertEquals(25.0, result.path("MetricDataResults").get(0).path("Values").get(0).asDouble());
    }

    private void put(String name, double value) {
        service.putMetricData(NAMESPACE,
                List.of(MetricDatum.of(name, "Count", value, List.of(), bucket.getEpochSecond())), REGION);
    }

    private MetricAlarm stored() {
        return service.describeAlarms(List.of("math"), null, REGION).getFirst();
    }

    private static MetricAlarm alarm(String expression) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("math");
        alarm.setMetrics(List.of(
                new AlarmMetricDataQuery("answer", expression, null, true, null, null, null),
                metric("e", "Errors"), metric("r", "Requests")));
        alarm.setEvaluationPeriods(1);
        alarm.setThreshold(10.0);
        alarm.setComparisonOperator("GreaterThanThreshold");
        alarm.setTreatMissingData("notBreaching");
        alarm.setActionsEnabled(false);
        return alarm;
    }

    private static AlarmMetricDataQuery metric(String id, String name) {
        return new AlarmMetricDataQuery(id, null, null, false, null, null,
                new AlarmMetricDataQuery.MetricStat(
                        new AlarmMetricDataQuery.Metric(NAMESPACE, name, List.of()), 60, "Sum", null));
    }
}
