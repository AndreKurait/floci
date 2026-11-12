package io.floci.aws.services.sqs;

import io.floci.aws.testing.TestTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The sweep that removes expired messages from storage nobody is reading, on a timeline the test
 * moves: it runs every 60 seconds, the first time 60 seconds after startup.
 */
class SqsRetentionSweeperTest {

    private static final Duration ONE_NANO = Duration.ofNanos(1);

    private final TestTime time = TestTime.create();
    private final ScheduledExecutorService scheduler = time.newScheduler();
    private final SqsService sqsService = mock(SqsService.class);

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
    }

    @Test
    void aFailingSweepDoesNotEscapeTheScheduledTask() {
        doThrow(new IllegalStateException("sweep failed")).when(sqsService).deleteExpiredMessages();
        SqsRetentionSweeper sweeper = new SqsRetentionSweeper(sqsService, true, scheduler);

        assertDoesNotThrow(sweeper::sweep);
        verify(sqsService).deleteExpiredMessages();
    }

    @Test
    void aFailingSweepDoesNotStopTheNextOne() {
        doThrow(new IllegalStateException("sweep failed")).when(sqsService).deleteExpiredMessages();
        SqsRetentionSweeper sweeper = new SqsRetentionSweeper(sqsService, true, scheduler);
        sweeper.onStart(null);

        time.advance(Duration.ofSeconds(60));
        verify(sqsService, times(1)).deleteExpiredMessages();
        assertEquals(1, time.pendingTasks(), "the periodic sweep is still scheduled after it failed");

        time.advance(Duration.ofSeconds(60));
        verify(sqsService, times(2)).deleteExpiredMessages();
    }

    @Test
    void theSweepRunsEverySixtySecondsStartingSixtySecondsAfterStartup() {
        new SqsRetentionSweeper(sqsService, true, scheduler).onStart(null);

        time.advance(Duration.ofSeconds(SqsRetentionSweeper.SWEEP_INTERVAL_SECONDS).minus(ONE_NANO));
        verify(sqsService, never()).deleteExpiredMessages();
        time.advance(ONE_NANO);
        verify(sqsService, times(1)).deleteExpiredMessages();

        time.advance(Duration.ofSeconds(SqsRetentionSweeper.SWEEP_INTERVAL_SECONDS).minus(ONE_NANO));
        verify(sqsService, times(1)).deleteExpiredMessages();
        time.advance(ONE_NANO);
        verify(sqsService, times(2)).deleteExpiredMessages();
    }

    @Test
    void aSweeperForADisabledServiceSchedulesNothing() {
        new SqsRetentionSweeper(sqsService, false, scheduler).onStart(null);

        assertEquals(0, time.pendingTasks());
        time.advance(Duration.ofMinutes(10));
        verify(sqsService, never()).deleteExpiredMessages();
    }

    @Test
    void shutdownEndsTheSweep() {
        SqsRetentionSweeper sweeper = new SqsRetentionSweeper(sqsService, true, scheduler);
        sweeper.onStart(null);
        assertEquals(1, time.pendingTasks());

        sweeper.onStop(null);
        time.advance(Duration.ofMinutes(10));

        assertTrue(scheduler.isShutdown());
        assertEquals(0, time.pendingTasks());
        verify(sqsService, never()).deleteExpiredMessages();
    }
}
