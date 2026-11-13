package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmHistoryItem;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.testing.ManualClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Alarm history is kept for thirty days and pruned at most once an hour, both measured on the
 * clock the service is built with; the same clock dates a datum that carries no timestamp and the
 * configuration and state changes of an alarm.
 */
class CloudWatchAlarmHistoryRetentionTest {

    private static final String REGION = "us-east-1";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final Duration PRUNE_INTERVAL = Duration.ofHours(1);

    private final ManualClock clock = new ManualClock(START);
    private final InMemoryStorage<String, AlarmHistoryItem> historyStore = new InMemoryStorage<>();
    private final CloudWatchMetricsService service = new CloudWatchMetricsService(new InMemoryStorage<>(),
            new InMemoryStorage<>(), historyStore, new RegionResolver(REGION, "000000000000"), null, clock);

    @Test
    void anAlarmAndItsHistoryAreDatedOnTheServiceClock() {
        putAlarm("dated");
        clock.advance(Duration.ofSeconds(90));
        service.setAlarmState("dated", "ALARM", "breached", null, REGION);

        MetricAlarm alarm = service.describeAlarms(List.of("dated"), null, REGION).getFirst();
        assertEquals(START.getEpochSecond(), alarm.getAlarmConfigurationUpdatedTimestamp());
        assertEquals(START.plusSeconds(90).getEpochSecond(), alarm.getStateUpdatedTimestamp());
        assertEquals(START.plusSeconds(90).getEpochSecond(), alarm.getStateTransitionedTimestamp());
        assertEquals(List.of(START.plusSeconds(90).toEpochMilli(), START.toEpochMilli()),
                history("dated").stream().map(AlarmHistoryItem::timestamp).toList());
    }

    @Test
    void aDatumWithoutATimestampIsDatedOnTheServiceClock() {
        clock.advance(Duration.ofSeconds(90));
        MetricDatum datum = new MetricDatum();
        datum.setMetricName("Requests");
        datum.setValue(1);

        service.putMetricData("App", List.of(datum), REGION);

        assertEquals(START.plusSeconds(90).getEpochSecond(), datum.getTimestamp());
    }

    @Test
    void historyIsReadableUntilThirtyDaysAfterItWasRecorded() {
        putAlarm("kept");

        clock.advance(RETENTION);
        assertEquals(1, history("kept").size(), "thirty days old: still inside the retention");

        clock.advance(Duration.ofMillis(1));
        assertEquals(0, history("kept").size(), "one millisecond past thirty days");
    }

    @Test
    void aWritePrunesHistoryOlderThanThirtyDaysAtMostOnceAnHour() {
        putAlarm("first");
        assertEquals(1, historyStore.keys().size());

        // Thirty days on, the first entry is exactly as old as the retention: a prune keeps it.
        clock.advance(RETENTION);
        putAlarm("second");
        assertEquals(2, historyStore.keys().size());

        // The first entry is past the retention now, but the last prune was less than an hour ago.
        clock.advance(PRUNE_INTERVAL.minusMillis(1));
        putAlarm("third");
        assertEquals(3, historyStore.keys().size());

        clock.advance(Duration.ofMillis(1));
        putAlarm("fourth");
        assertEquals(3, historyStore.keys().size(), "the hour is up: the write drops the first entry");
        assertEquals(0, history("first").size());
        assertEquals(1, history("second").size());
    }

    private void putAlarm(String name) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName(name);
        service.putMetricAlarm(alarm, REGION);
    }

    private List<AlarmHistoryItem> history(String name) {
        return service.describeAlarmHistory(name, null, null, null, null, null, null, null, REGION).items();
    }
}
