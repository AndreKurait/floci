package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.testing.TestTime;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When an alarm changes state, on a timeline the test moves: the evaluator's own tick (5 s after
 * start, then every 10 s) and the period arithmetic (a datapoint counts once its period is complete
 * and stops counting once it leaves the evaluation range), each asserted just before the instant
 * and at it, with real period lengths and the real service behind the evaluator.
 */
class AlarmEvaluatorTimingTest {

    private static final String REGION = "us-east-1";
    private static final String NAMESPACE = "Example/Load";
    private static final String METRIC = "Utilization";
    private static final String NAME = "load";
    private static final String ACTION = "action-under-test";
    private static final Instant NOON = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration ONE_NANO = Duration.ofNanos(1);

    private final TestTime time = TestTime.startingAt(NOON);
    private final CloudWatchMetricsService service = new CloudWatchMetricsService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, "123456789012"));
    private final List<Double> dispatched = new ArrayList<>();
    private final ScheduledExecutorService scheduler = time.newScheduler();
    private final AlarmEvaluator evaluator = new AlarmEvaluator(service, handlers(), time.clock(), scheduler);

    @AfterEach
    void stopEvaluator() {
        evaluator.stop();
    }

    @Test
    void theFirstTickRunsFiveSecondsAfterStartAndThenEveryTenSeconds() {
        putAlarm(60, 1, null, null);
        breachAt(NOON.minusSeconds(30));
        evaluator.start();

        time.advance(Duration.ofSeconds(5).minus(ONE_NANO));
        assertEquals("INSUFFICIENT_DATA", state());
        assertEquals(List.of(), dispatched);

        time.advance(ONE_NANO);
        assertEquals("ALARM", state());
        assertEquals(List.of(80.0), dispatched);

        time.advance(Duration.ofSeconds(10).minus(ONE_NANO));
        assertEquals(List.of(80.0), dispatched);

        time.advance(ONE_NANO);
        assertEquals(List.of(80.0, 80.0), dispatched, "an alarm still in ALARM dispatches again on the next tick");

        time.advance(Duration.ofSeconds(30));
        assertEquals(5, dispatched.size());
    }

    @Test
    void aTickThatFailsDoesNotStopTheNextOne() {
        CloudWatchMetricsService failing = mock(CloudWatchMetricsService.class);
        when(failing.allOwnedAlarms())
                .thenThrow(new IllegalStateException("storage hiccup"))
                .thenAnswer(invocation -> service.allOwnedAlarms());
        ScheduledExecutorService ownScheduler = time.newScheduler();
        AlarmEvaluator subject = new AlarmEvaluator(failing, handlers(), time.clock(), ownScheduler);
        subject.start();
        try {
            time.advance(Duration.ofSeconds(5));
            assertEquals(1, time.pendingTasks(), "the periodic tick is still scheduled after it failed");

            time.advance(Duration.ofSeconds(10));
            verify(failing, times(2)).allOwnedAlarms();
        } finally {
            subject.stop();
        }
    }

    @Test
    void stopEndsTheTick() {
        putAlarm(60, 1, null, null);
        breachAt(NOON.minusSeconds(30));
        evaluator.start();
        time.advance(Duration.ofSeconds(5));
        assertEquals(1, dispatched.size());

        evaluator.stop();
        time.advance(Duration.ofMinutes(1));

        assertEquals(1, dispatched.size());
        assertTrue(scheduler.isShutdown());
        assertEquals(0, time.pendingTasks());
    }

    /** One minute, and CloudWatch's default five. */
    @ParameterizedTest
    @ValueSource(ints = {60, 300})
    void aDatapointCountsOnceItsPeriodIsComplete(int period) {
        putAlarm(period, 1, null, null);
        breachAt(NOON.plusSeconds(period / 2));

        time.advanceTo(NOON.plusSeconds(period).minusNanos(1));
        evaluate();
        assertEquals("INSUFFICIENT_DATA", state(), "the period holding the datapoint is still accumulating");

        time.advance(ONE_NANO);
        evaluate();
        assertEquals("ALARM", state());
    }

    @Test
    void theScheduledTickSeesACompletedPeriodOnItsFirstRunAfterTheBoundary() {
        putAlarm(60, 1, null, null);
        breachAt(NOON.plusSeconds(30));
        evaluator.start();

        time.advanceTo(NOON.plusSeconds(65).minusNanos(1));
        assertEquals("INSUFFICIENT_DATA", state(), "ticks at 12:00:05 through 12:00:55 ran inside the open period");

        time.advance(ONE_NANO);
        assertEquals("ALARM", state());
    }

    @Test
    void threeEvaluationPeriodsAlarmWhenTheThirdBreachingPeriodCloses() {
        putAlarm(60, 3, null, null);
        service.setAlarmState(NAME, "OK", "initial", null, REGION);
        breachAt(NOON.plusSeconds(30));
        breachAt(NOON.plusSeconds(90));
        breachAt(NOON.plusSeconds(150));

        time.advanceTo(NOON.plusSeconds(180).minusNanos(1));
        evaluate();
        assertEquals("OK", state(), "two complete breaching periods of the three required");

        time.advance(ONE_NANO);
        evaluate();
        assertEquals("ALARM", state());
    }

    @Test
    void twoOfThreeDatapointsAlarmWhenTheSecondBreachingPeriodCloses() {
        putAlarm(60, 3, 2, null);
        service.setAlarmState(NAME, "OK", "initial", null, REGION);
        breachAt(NOON.plusSeconds(30));
        put(20, NOON.plusSeconds(90));
        breachAt(NOON.plusSeconds(150));

        time.advanceTo(NOON.plusSeconds(180).minusNanos(1));
        evaluate();
        assertEquals("OK", state(), "one breaching datapoint of the two required");

        time.advance(ONE_NANO);
        evaluate();
        assertEquals("ALARM", state());
    }

    @Test
    void anAlarmClearsWhenANonBreachingPeriodCompletes() {
        putAlarm(60, 1, null, null);
        breachAt(NOON.plusSeconds(30));
        put(20, NOON.plusSeconds(90));

        time.advanceTo(NOON.plusSeconds(120).minusNanos(1));
        evaluate();
        assertEquals("ALARM", state(), "12:00 is the last complete period");

        time.advance(ONE_NANO);
        evaluate();
        assertEquals("OK", state());
    }

    /**
     * One evaluation period reads a range of three, so a lone breaching datapoint in the 12:00 period
     * decides the state until 12:04, when that period leaves the range and TreatMissingData takes over.
     */
    @ParameterizedTest
    @CsvSource({"missing,INSUFFICIENT_DATA", "notBreaching,OK", "breaching,ALARM", "ignore,ALARM"})
    void missingDataIsConsultedWhenTheLastDatapointLeavesTheEvaluationRange(String treatMissingData,
                                                                           String expected) {
        putAlarm(60, 1, null, treatMissingData);
        breachAt(NOON.plusSeconds(30));

        time.advanceTo(NOON.plusSeconds(240).minusNanos(1));
        evaluate();
        assertEquals("ALARM", state(), "the 12:00 datapoint is the oldest of the three periods read");

        time.advance(ONE_NANO);
        evaluate();
        assertEquals(expected, state());
    }

    private void putAlarm(int period, int evaluationPeriods, Integer datapointsToAlarm, String treatMissingData) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName(NAME);
        alarm.setNamespace(NAMESPACE);
        alarm.setMetricName(METRIC);
        alarm.setStatistic("Average");
        alarm.setPeriod(period);
        alarm.setEvaluationPeriods(evaluationPeriods);
        alarm.setDatapointsToAlarm(datapointsToAlarm);
        alarm.setTreatMissingData(treatMissingData);
        alarm.setThreshold(50.0);
        alarm.setComparisonOperator("GreaterThanThreshold");
        alarm.setActionsEnabled(true);
        alarm.setAlarmActions(List.of(ACTION));
        service.putMetricAlarm(alarm, REGION);
    }

    private void breachAt(Instant timestamp) {
        put(80, timestamp);
    }

    private void put(double value, Instant timestamp) {
        service.putMetricData(NAMESPACE,
                List.of(MetricDatum.of(METRIC, "Percent", value, List.of(), timestamp.getEpochSecond())), REGION);
    }

    private void evaluate() {
        evaluator.evaluateAll();
    }

    private String state() {
        return service.describeAlarms(List.of(NAME), null, REGION).getFirst().getStateValue();
    }

    @SuppressWarnings("unchecked")
    private Instance<AlarmActionHandler> handlers() {
        AlarmActionHandler recording = new AlarmActionHandler() {
            @Override
            public boolean supports(String actionArn) {
                return ACTION.equals(actionArn);
            }

            @Override
            public void handle(String actionArn, MetricAlarm alarm, double metricValue, String region) {
                dispatched.add(metricValue);
            }
        };
        Instance<AlarmActionHandler> instance = mock(Instance.class);
        when(instance.iterator()).thenAnswer(invocation -> List.of(recording).iterator());
        return instance;
    }
}
