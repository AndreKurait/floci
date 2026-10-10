package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CompositeAlarmServiceTest {
    private static final String REGION = "cn-north-1";
    private final CloudWatchMetricsService service = new CloudWatchMetricsService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, "123456789012"));

    @Test
    void metricTransitionsPropagateThroughNamesArnsAndNestedComposites() {
        MetricAlarm metric = new MetricAlarm();
        metric.setAlarmName("latency");
        service.putMetricAlarm(metric, REGION);
        put("combined", "ALARM(\"" + metric.getAlarmArn() + "\") AND NOT ALARM(\"maintenance\")");
        put("outer", "ALARM(combined) OR (TRUE AND FALSE)");
        assertEquals("OK", state("outer"));
        service.setAlarmState("latency", "ALARM", "high latency", null, REGION);
        assertEquals("ALARM", state("combined"));
        assertEquals("ALARM", state("outer"));
        service.setAlarmState("latency", "OK", "recovered", null, REGION);
        assertEquals("OK", state("outer"));
        assertEquals(List.of(metric), service.describeAlarms(null, null, REGION));
        assertTrue(service.describeCompositeAlarms(null, null, "us-east-1").isEmpty());
        assertTrue(service.describeCompositeAlarms(List.of("combined"), null, REGION)
                .getFirst().getAlarmArn().startsWith("arn:aws-cn:cloudwatch:"));
    }

    @Test
    void updatesPreserveStateAndTagsUntilTheNextDependencyChange() {
        put("source", "FALSE");
        CompositeAlarm alarm = put("aggregate", "ALARM(source)");
        service.tagResource(alarm.getAlarmArn(), Map.of("owner", "retained"), REGION);
        service.setAlarmState("aggregate", "ALARM", "manual", null, REGION);
        CompositeAlarm replacement = put("aggregate", "OK(source)");
        assertEquals("ALARM", replacement.getStateValue());
        assertEquals(Map.of("owner", "retained"), replacement.getTags());
        service.setAlarmState("source", "ALARM", "dependency changed", null, REGION);
        assertEquals("OK", state("aggregate"));
    }

    @Test
    void cyclesStopEvaluationEvenBehindShortCircuitAndMustBeBrokenBeforeDelete() {
        put("a", "FALSE AND ALARM(b)");
        put("b", "ALARM(a)");
        service.setAlarmState("b", "ALARM", "manual", null, REGION);
        assertThrows(AwsException.class, () -> service.deleteAlarms(List.of("a"), REGION));
        assertEquals(2, service.describeCompositeAlarms(null, null, REGION).size());
        put("a", "FALSE");
        service.deleteAlarms(List.of("b"), REGION);
        assertEquals("OK", state("a"));
        service.deleteAlarms(List.of("a"), REGION);
        assertTrue(service.describeCompositeAlarms(null, null, REGION).isEmpty());
    }

    @Test
    void manuallyChangingOneMemberDoesNotResumeACyclicDependency() {
        put("a", "ALARM(b)");
        put("b", "ALARM(a)");
        assertEquals("OK", state("a"));
        service.setAlarmState("b", "ALARM", "manual", null, REGION);
        assertEquals("OK", state("a"));
        put("b", "TRUE");
        service.setAlarmState("b", "ALARM", "cycle broken", null, REGION);
        assertEquals("ALARM", state("a"));
    }

    @Test
    void parentQuotaTreatsAChildNameAndItsArnAsTheSameAlarm() {
        CompositeAlarm child = put("child", "FALSE");
        for (int index = 0; index < 150; index++) {
            put("parent-" + index, "ALARM(child)");
        }
        assertThrows(AwsException.class, () -> put("overflow", "ALARM(\"" + child.getAlarmArn() + "\")"));
        service.deleteAlarms(List.of("parent-0"), REGION);
        assertEquals("OK", put("replacement", "ALARM(\"" + child.getAlarmArn() + "\")").getStateValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ALARM()", "TRUE junk", "TRUE AND", "ALARM(\"unterminated)",
            "(TRUE", "ALARM(x) XOR FALSE", "ALARM(\"x\\z\")"})
    void invalidRulesDoNotReplaceAnExistingAlarm(String invalid) {
        put("saved", "TRUE");
        assertThrows(AwsException.class, () -> put("saved", invalid));
        assertEquals("TRUE", service.describeCompositeAlarms(List.of("saved"), null, REGION).getFirst().getAlarmRule());
        assertEquals("ALARM", state("saved"));
    }

    @Test
    void quotasAndAtomicCreatePreventExcessReferencesAndForeignNameReplacement() {
        String many = IntStream.range(0, 101).mapToObj(i -> "ALARM(a" + i + ")")
                .collect(Collectors.joining(" OR "));
        assertThrows(AwsException.class, () -> put("too-many", many));
        put("owned", "TRUE");
        CompositeAlarm foreign = new CompositeAlarm();
        foreign.setAlarmName("owned");
        foreign.setAlarmRule("FALSE");
        assertThrows(AwsException.class, () -> service.putCompositeAlarm(foreign, REGION, false));
        assertEquals("ALARM", state("owned"));
        assertThrows(AwsException.class, () -> put("deep", "(".repeat(250) + "TRUE" + ")".repeat(250)));
        assertEquals("ALARM", put("many-not", "NOT ".repeat(502) + "TRUE").getStateValue());
        assertThrows(AwsException.class, () -> put("elements", "FALSE OR ".repeat(500) + "TRUE"));
    }

    @Test
    void mixedPersistenceLoadsLegacyMetricsAndRoundTripsComposites() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TypeReference<Map<String, Alarm>> type = new TypeReference<>() {};
        Map<String, Alarm> legacy = mapper.readValue(
                "{\"legacy\":{\"alarmName\":\"old\",\"metricName\":\"CPU\",\"stateValue\":\"OK\"}}", type);
        assertInstanceOf(MetricAlarm.class, legacy.get("legacy"));
        CompositeAlarm alarm = put("new", "FALSE");
        Map<String, Alarm> restored = mapper.readValue(mapper.writeValueAsBytes(Map.of("new", alarm)), type);
        assertEquals("FALSE", assertInstanceOf(CompositeAlarm.class, restored.get("new")).getAlarmRule());
        assertEquals("OK", restored.get("new").getStateValue());
    }

    private CompositeAlarm put(String name, String rule) {
        CompositeAlarm alarm = new CompositeAlarm();
        alarm.setAlarmName(name);
        alarm.setAlarmRule(rule);
        alarm.setActionsEnabled(true);
        service.putCompositeAlarm(alarm, REGION);
        return alarm;
    }

    private String state(String name) {
        return service.describeCompositeAlarms(List.of(name), null, REGION).getFirst().getStateValue();
    }
}
