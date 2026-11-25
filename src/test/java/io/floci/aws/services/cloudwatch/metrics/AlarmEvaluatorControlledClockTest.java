package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudwatch.metrics.model.MetricAlarm;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import io.floci.aws.testing.ManualClock;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Independent complete-period boundaries using the existing small clock fixture. */
class AlarmEvaluatorControlledClockTest {
    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");
    private static final String REGION = "us-east-1";
    private static final String NAME = "controlled-complete-period";
    private static final String NAMESPACE = "ClockPrerequisite";
    private static final String ACTION = "record-owned-action";

    private final ManualClock clock = new ManualClock(START);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final CloudWatchMetricsService metrics = new CloudWatchMetricsService(new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"),
            null, clock);
    private final List<Double> dispatched = new ArrayList<>();
    private final AlarmEvaluator evaluator = new AlarmEvaluator(metrics, handlers(), clock, scheduler);

    @AfterEach
    void stop() {
        evaluator.stop();
    }

    @Test
    void injectedSchedulerRetainsTheFiveSecondInitialDelayAndTenSecondCadence() {
        alarm(60, 1, null);
        datum(80, START.minusSeconds(30));
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);

        evaluator.start();

        verify(scheduler).scheduleAtFixedRate(tick.capture(), eq(5L), eq(10L), eq(TimeUnit.SECONDS));
        tick.getValue().run();
        assertEquals("ALARM", state());
        assertEquals(List.of(80.0), dispatched);
        tick.getValue().run();
        assertEquals(List.of(80.0, 80.0), dispatched);
        evaluator.stop();
        verify(scheduler).shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(ints = {60, 300})
    void aReadingEntersTheEvaluationOnlyWhenItsWholePeriodCompletes(int period) {
        alarm(period, 1, null);
        datum(80, START.plusSeconds(period / 2));

        clock.advance(Duration.ofSeconds(period).minusNanos(1));
        evaluator.evaluateAll();
        assertEquals("INSUFFICIENT_DATA", state());
        assertEquals(List.of(), dispatched);

        clock.advance(Duration.ofNanos(1));
        evaluator.evaluateAll();
        assertEquals("ALARM", state());
        assertEquals(List.of(80.0), dispatched);
        assertEquals(clock.instant().getEpochSecond(),
                metrics.describeAlarms(List.of(NAME), null, REGION).getFirst().getStateUpdatedTimestamp());
    }

    @Test
    void twoEvaluationPeriodsNeedTheSecondCompleteBreachingMinute() {
        alarm(60, 2, null);
        datum(80, START.plusSeconds(30));
        datum(90, START.plusSeconds(90));

        clock.advance(Duration.ofSeconds(120).minusNanos(1));
        evaluator.evaluateAll();
        assertEquals("OK", state());
        assertEquals(List.of(), dispatched);

        clock.advance(Duration.ofNanos(1));
        evaluator.evaluateAll();
        assertEquals("ALARM", state());
        assertEquals(List.of(90.0), dispatched);
    }

    @ParameterizedTest
    @CsvSource({"missing,INSUFFICIENT_DATA", "notBreaching,OK", "breaching,ALARM", "ignore,ALARM"})
    void theLastReadingLeavesTheRangeAtTheWholeMinuteBoundary(String missing, String expected) {
        alarm(60, 1, missing);
        datum(80, START.plusSeconds(30));

        clock.advance(Duration.ofSeconds(240).minusNanos(1));
        evaluator.evaluateAll();
        assertEquals("ALARM", state());
        assertEquals(List.of(80.0), dispatched);

        clock.advance(Duration.ofNanos(1));
        evaluator.evaluateAll();
        assertEquals(expected, state());
    }

    @Test
    void aFailedOwnedEnumerationDoesNotDisableTheCapturedScheduledCallback() {
        CloudWatchMetricsService failing = mock(CloudWatchMetricsService.class);
        when(failing.allOwnedAlarms()).thenThrow(new IllegalStateException("controlled storage failure"))
                .thenReturn(List.of());
        AlarmEvaluator subject = new AlarmEvaluator(failing, handlers(), clock, scheduler);
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        subject.start();
        verify(scheduler).scheduleAtFixedRate(tick.capture(), eq(5L), eq(10L), eq(TimeUnit.SECONDS));

        tick.getValue().run();
        tick.getValue().run();

        verify(failing, times(2)).allOwnedAlarms();
    }

    private void alarm(int period, int count, String missing) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName(NAME);
        alarm.setNamespace(NAMESPACE);
        alarm.setMetricName("Reading");
        alarm.setStatistic("Average");
        alarm.setPeriod(period);
        alarm.setEvaluationPeriods(count);
        alarm.setThreshold(50);
        alarm.setComparisonOperator("GreaterThanThreshold");
        alarm.setTreatMissingData(missing);
        alarm.setActionsEnabled(true);
        alarm.setAlarmActions(List.of(ACTION));
        metrics.putMetricAlarm(alarm, REGION);
    }

    private void datum(double value, Instant at) {
        metrics.putMetricData(NAMESPACE,
                List.of(MetricDatum.of("Reading", "Count", value, List.of(), at.getEpochSecond())), REGION);
    }

    private String state() {
        return metrics.describeAlarms(List.of(NAME), null, REGION).getFirst().getStateValue();
    }

    @SuppressWarnings("unchecked")
    private Instance<AlarmActionHandler> handlers() {
        AlarmActionHandler recorder = new AlarmActionHandler() {
            @Override
            public boolean supports(String actionArn) {
                return ACTION.equals(actionArn);
            }

            @Override
            public void handle(String actionArn, MetricAlarm alarm, double metricValue, String region) {
                dispatched.add(metricValue);
            }
        };
        Instance<AlarmActionHandler> handlers = mock(Instance.class);
        when(handlers.iterator()).thenAnswer(ignored -> List.of(recorder).iterator());
        return handlers;
    }
}
