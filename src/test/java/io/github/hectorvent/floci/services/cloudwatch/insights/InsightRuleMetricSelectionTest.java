package io.github.hectorvent.floci.services.cloudwatch.insights;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.LogEventsIngested;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InsightRuleMetricSelectionTest {
    @Test
    void omittedEmptyAndExplicitMetricsNeverInventUnrequestedStatistics() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        CloudWatchInsightRulesService service = new CloudWatchInsightRulesService(new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "111122223333"), () -> start.plusSeconds(120).toEpochMilli());
        service.put("selection", "{\"Schema\":{\"Name\":\"CloudWatchLogRule\",\"Version\":1},"
                + "\"LogGroupNames\":[\"/example/selection\"],\"LogFormat\":\"JSON\","
                + "\"Contribution\":{\"Keys\":[\"$.client\"]},\"AggregateOn\":\"Count\"}",
                "ENABLED", false, Map.of(), "us-east-1");
        LogEvent event = new LogEvent();
        event.setTimestamp(start.plusSeconds(1).toEpochMilli());
        event.setMessage("{\"client\":\"alpha\"}");
        service.ingest(new LogEventsIngested("111122223333", "us-east-1", "/example/selection", "events",
                List.of(event)));
        InsightRuleReport omitted = service.report("selection", start, start.plusSeconds(120), 60,
                100, "Sum", null, "us-east-1");
        InsightRuleReport empty = service.report("selection", start, start.plusSeconds(120), 60,
                100, "Sum", List.of(), "us-east-1");
        assertEquals(1, omitted.aggregate());
        assertEquals(1, omitted.unique());
        assertEquals(List.of("alpha"), omitted.contributors().getFirst().keys());
        assertTrue(omitted.metrics().isEmpty());
        assertEquals(omitted, empty);
        for (String metric : List.of("UniqueContributors", "MaxContributorValue", "SampleCount", "Sum",
                "Minimum", "Maximum", "Average")) {
            InsightRuleReport selected = service.report("selection", start, start.plusSeconds(120), 60,
                    100, "Sum", List.of(metric), "us-east-1");
            assertEquals(omitted.labels(), selected.labels());
            assertEquals(omitted.aggregate(), selected.aggregate());
            assertEquals(omitted.unique(), selected.unique());
            assertEquals(omitted.contributors(), selected.contributors());
            assertEquals(Map.of(metric, 1.0), selected.metrics().getFirst().values());
            assertTrue(selected.metrics().stream().allMatch(point -> point.values().keySet().equals(Set.of(metric))));
        }
        assertEquals(omitted, service.report("selection", start, start.plusSeconds(120), 60,
                100, "Sum", null, "us-east-1"));
    }
}
