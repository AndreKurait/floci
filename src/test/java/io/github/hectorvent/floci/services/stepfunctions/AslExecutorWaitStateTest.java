package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wait states must honor {@code Seconds}, {@code SecondsPath}, {@code Timestamp}, and
 * {@code TimestampPath}. Timestamp support was missing: the state fell through with no pause and
 * ran early. With no {@code TimeoutSeconds}, the execution deadline is unbounded, so the injected
 * {@link AslExecutor.Sleeper} is the only thing that records the requested pause.
 */
class AslExecutorWaitStateTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void secondsAboveThirtyAreHonoredWhenTheCapIsRaised() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);

        Execution execution = run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","Seconds":45,"End":true}}}
                """, "{}");

        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals(TimeUnit.SECONDS.toNanos(45), sleeper.singleSleep());
    }

    @Test
    void secondsAreCappedAtTheConfiguredMaximum() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 10);

        run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","Seconds":100,"End":true}}}
                """, "{}");

        assertEquals(TimeUnit.SECONDS.toNanos(10), sleeper.singleSleep());
    }

    @Test
    void absoluteTimestampWaitsUntilThatInstant() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);
        Instant wake = NOW.plusSeconds(45);

        run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","Timestamp":"%s","End":true}}}
                """.formatted(wake), "{}");

        assertEquals(TimeUnit.SECONDS.toNanos(45), sleeper.singleSleep());
    }

    @Test
    void timestampPathResolvesTheWakeInstant() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);
        Instant wake = NOW.plusSeconds(20);

        run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","TimestampPath":"$.wake","End":true}}}
                """, "{\"wake\":\"" + wake + "\"}");

        assertEquals(TimeUnit.SECONDS.toNanos(20), sleeper.singleSleep());
    }

    /**
     * SecondsPath is a Reference Path, so {@code $$} reads the Context Object. InputPath narrows the
     * state input to a subtree without the delay, so a resolver that sees only the input finds
     * nothing and never sleeps.
     */
    @Test
    void secondsPathReadsTheContextObject() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);

        Execution execution = run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","InputPath":"$.payload",
                  "SecondsPath":"$$.Execution.Input.delay","End":true}}}
                """, "{\"delay\":45,\"payload\":{\"kept\":true}}");

        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals(TimeUnit.SECONDS.toNanos(45), sleeper.singleSleep());
    }

    @Test
    void timestampPathReadsTheContextObject() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);
        Instant wake = NOW.plusSeconds(20);

        Execution execution = run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","InputPath":"$.payload",
                  "TimestampPath":"$$.Execution.Input.until","End":true}}}
                """, "{\"until\":\"" + wake + "\",\"payload\":{\"kept\":true}}");

        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals(TimeUnit.SECONDS.toNanos(20), sleeper.singleSleep());
    }

    @Test
    void jsonataLiteralTimestampWaitsUntilThatInstant() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);
        Instant wake = NOW.plusSeconds(45);

        run(executor, """
                {"QueryLanguage":"JSONata","StartAt":"W","States":{"W":{"Type":"Wait","Timestamp":"%s","End":true}}}
                """.formatted(wake), "{}");

        assertEquals(TimeUnit.SECONDS.toNanos(45), sleeper.singleSleep());
    }

    @Test
    void jsonataTimestampExpressionResolvesTheWakeInstant() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);
        Instant wake = NOW.plusSeconds(20);

        run(executor, """
                {"QueryLanguage":"JSONata","StartAt":"W","States":{"W":{"Type":"Wait","Timestamp":"{% $states.input.wake %}","End":true}}}
                """, "{\"wake\":\"" + wake + "\"}");

        assertEquals(TimeUnit.SECONDS.toNanos(20), sleeper.singleSleep());
    }

    @Test
    void timestampInThePastDoesNotSleep() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);
        Instant past = NOW.minusSeconds(10);

        Execution execution = run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","Timestamp":"%s","End":true}}}
                """.formatted(past), "{}");

        assertEquals("SUCCEEDED", execution.getStatus());
        assertTrue(sleeper.sleeps().isEmpty());
    }

    @Test
    void timestampExactlyNowDoesNotSleep() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);

        run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","Timestamp":"%s","End":true}}}
                """.formatted(NOW), "{}");

        assertTrue(sleeper.sleeps().isEmpty());
    }

    @Test
    void invalidTimestampFailsTheExecution() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);

        Execution execution = run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","Timestamp":"not-a-timestamp","End":true}}}
                """, "{}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertTrue(sleeper.sleeps().isEmpty());
    }

    @Test
    void millisecondCeilingAppliesToEveryWaitForm() {
        for (String state : List.of(
                "\"Seconds\":10",
                "\"SecondsPath\":\"$.seconds\"",
                "\"Timestamp\":\"2026-01-01T00:00:10Z\"",
                "\"TimestampPath\":\"$.wake\"",
                "\"QueryLanguage\":\"JSONata\",\"Seconds\":\"{% $states.input.seconds %}\"",
                "\"QueryLanguage\":\"JSONata\",\"Timestamp\":\"{% $states.input.wake %}\"")) {
            RecordingSleeper sleeper = new RecordingSleeper();
            AslExecutor executor = configuredExecutor(sleeper, 30, 100);
            Execution execution = run(executor,
                    "{\"StartAt\":\"W\",\"States\":{\"W\":{\"Type\":\"Wait\",\"End\":true," + state + "}}}",
                    "{\"seconds\":10,\"wake\":\"2026-01-01T00:00:10Z\"}");

            assertEquals("SUCCEEDED", execution.getStatus(), state);
            assertEquals(TimeUnit.MILLISECONDS.toNanos(100), sleeper.singleSleep(), state);
        }
    }

    @Test
    void nonPositiveMillisecondsKeepTheSecondsCeiling() {
        for (long millis : new long[] {0, -1, Long.MIN_VALUE}) {
            RecordingSleeper sleeper = new RecordingSleeper();
            run(configuredExecutor(sleeper, 2, millis),
                    "{\"StartAt\":\"W\",\"States\":{\"W\":{\"Type\":\"Wait\",\"Seconds\":10,\"End\":true}}}", "{}");
            assertEquals(TimeUnit.SECONDS.toNanos(2), sleeper.singleSleep());
        }
    }

    @Test
    void positiveMillisecondsOverrideTheSecondsSetting() {
        RecordingSleeper sleeper = new RecordingSleeper();
        run(configuredExecutor(sleeper, 1, 2000),
                "{\"StartAt\":\"W\",\"States\":{\"W\":{\"Type\":\"Wait\",\"Seconds\":10,\"End\":true}}}", "{}");
        assertEquals(TimeUnit.SECONDS.toNanos(2), sleeper.singleSleep());
    }

    @Test
    void largeMillisecondCeilingDoesNotOverflowIntoAnImmediateWait() {
        RecordingSleeper sleeper = new RecordingSleeper();
        run(configuredExecutor(sleeper, 1, Long.MAX_VALUE),
                "{\"StartAt\":\"W\",\"States\":{\"W\":{\"Type\":\"Wait\",\"Seconds\":2,\"End\":true}}}", "{}");
        assertEquals(TimeUnit.SECONDS.toNanos(2), sleeper.singleSleep());
    }

    @Test
    void shorterAbsoluteWaitIsNotLengthenedToTheMillisecondCeiling() {
        RecordingSleeper sleeper = new RecordingSleeper();
        run(configuredExecutor(sleeper, 30, 100), """
                {"StartAt":"W","States":{"W":{"Type":"Wait",
                  "Timestamp":"2026-01-01T00:00:00.050Z","End":true}}}
                """, "{}");
        assertEquals(TimeUnit.MILLISECONDS.toNanos(50), sleeper.singleSleep());
    }

    @Test
    void subsecondWaitsStillExhaustTheOriginalExecutionBudget() {
        AtomicLong elapsed = new AtomicLong();
        AslExecutor executor = AslExecutorFixture.builder()
                .config(waitConfig(30, 100))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .nanoTime(elapsed::get)
                .sleeper(elapsed::addAndGet)
                .build();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode definition = mapper.createObjectNode().put("StartAt", "W0").put("TimeoutSeconds", 1);
        ObjectNode states = definition.putObject("States");
        for (int index = 0; index < 12; index++) {
            ObjectNode state = states.putObject("W" + index).put("Type", "Wait").put("Seconds", 10);
            if (index == 11) {
                state.put("End", true);
            } else {
                state.put("Next", "W" + (index + 1));
            }
        }

        Execution execution = run(executor, definition.toString(), "{}");

        assertEquals("TIMED_OUT", execution.getStatus());
        assertEquals(TimeUnit.SECONDS.toNanos(1), elapsed.get());
    }

    private static AslExecutor configuredExecutor(RecordingSleeper sleeper, int seconds, long millis) {
        return AslExecutorFixture.builder()
                .config(waitConfig(seconds, millis))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .sleeper(sleeper)
                .build();
    }

    private static EmulatorConfig waitConfig(int seconds, long millis) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().stepfunctions().maxWaitSeconds()).thenReturn(seconds);
        when(config.services().stepfunctions().maxWaitMillis()).thenReturn(millis);
        return config;
    }

    private static AslExecutor newExecutor(RecordingSleeper sleeper, int maxWaitSeconds) {
        ObjectMapper objectMapper = new ObjectMapper();
        return AslExecutorFixture.builder()
                .objectMapper(objectMapper)
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .sleeper(sleeper)
                .maxWaitSeconds(maxWaitSeconds)
                .build();
    }

    private static Execution run(AslExecutor executor, String definition, String input) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("wait-state-test");
        stateMachine.setStateMachineArn(
                "arn:aws:states:us-east-1:000000000000:stateMachine:wait-state-test");
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("wait-exec");
        execution.setExecutionArn(
                "arn:aws:states:us-east-1:000000000000:execution:wait-state-test:wait-exec");
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }

    private static final class RecordingSleeper implements AslExecutor.Sleeper {

        private final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long nanos) {
            sleeps.add(nanos);
        }

        List<Long> sleeps() {
            return sleeps;
        }

        long singleSleep() {
            assertEquals(1, sleeps.size(), "expected exactly one recorded sleep");
            return sleeps.getFirst();
        }
    }
}
