package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.config.EmulatorConfig.SqsServiceConfig.EndpointStrategy;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The sweep that removes expired messages from storage nobody is reading, on a timeline the test
 * moves: it runs every 60 seconds, the first time 60 seconds after startup.
 */
class SqsRetentionSweeperTest {

    private static final String REGION = "us-east-1";
    private static final String STORAGE_KEY = REGION + "::/000000000000/swept";
    private static final Duration ONE_NANO = Duration.ofNanos(1);

    private final TestTime time = TestTime.create();
    private final ScheduledExecutorService scheduler = time.newScheduler();
    private final InMemoryStorage<String, List<Message>> messageStore = new InMemoryStorage<>();
    private final SqsService sqsService = new SqsService(new InMemoryStorage<>(), messageStore, null, null, 30,
            1048576, "http://localhost:4566", new RegionResolver(REGION, "000000000000"), false, null, time.clock(),
            ReceiptHandle.DEFAULT_SECRET, null, null, EndpointStrategy.FLOCI, new TimelineSqsTiming(time),
            new JoinableMoveTasks());

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
        sqsService.stop();
    }

    @Test
    void aFailingSweepDoesNotEscapeTheScheduledTask() {
        SqsService failing = mock(SqsService.class);
        doThrow(new IllegalStateException("sweep failed")).when(failing).deleteExpiredMessages();
        SqsRetentionSweeper sweeper = new SqsRetentionSweeper(failing, true, scheduler);

        assertDoesNotThrow(sweeper::sweep);
        verify(failing).deleteExpiredMessages();
    }

    @Test
    void aFailingSweepDoesNotStopTheNextOne() {
        SqsService failing = mock(SqsService.class);
        doThrow(new IllegalStateException("sweep failed")).when(failing).deleteExpiredMessages();
        SqsRetentionSweeper sweeper = new SqsRetentionSweeper(failing, true, scheduler);
        sweeper.onStart(null);

        time.advance(Duration.ofSeconds(60));
        verify(failing, times(1)).deleteExpiredMessages();
        assertEquals(1, time.pendingTasks(), "the periodic sweep is still scheduled after it failed");

        time.advance(Duration.ofSeconds(60));
        verify(failing, times(2)).deleteExpiredMessages();
    }

    @Test
    void theSweepRunsEverySixtySecondsStartingSixtySecondsAfterStartup() {
        String url = sqsService.createQueue("swept", Map.of("MessageRetentionPeriod", "60"), REGION).getQueueUrl();
        sqsService.sendMessage(url, "first", 0, REGION);
        time.advance(Duration.ofSeconds(30));
        new SqsRetentionSweeper(sqsService, true, scheduler).onStart(null);

        // The message expired 60 s after it was sent; the first sweep is 60 s after startup, at 90 s.
        time.advance(Duration.ofSeconds(60).minus(ONE_NANO));
        assertEquals(1, stored(), "expired half a minute ago, and still stored until a sweep runs");
        time.advance(ONE_NANO);
        assertEquals(0, stored());

        // Sent at 100 s: live at the 150 s sweep, expired at 160 s, removed by the 210 s sweep.
        time.advance(Duration.ofSeconds(10));
        sqsService.sendMessage(url, "second", 0, REGION);
        time.advance(Duration.ofSeconds(50));
        assertEquals(1, stored(), "a sweep removes only what has expired");
        time.advance(Duration.ofSeconds(60).minus(ONE_NANO));
        assertEquals(1, stored());
        time.advance(ONE_NANO);
        assertEquals(0, stored());
    }

    @Test
    void aSweeperForADisabledServiceSchedulesNothing() {
        String url = sqsService.createQueue("swept", Map.of("MessageRetentionPeriod", "60"), REGION).getQueueUrl();
        sqsService.sendMessage(url, "kept", 0, REGION);

        new SqsRetentionSweeper(sqsService, false, scheduler).onStart(null);

        assertEquals(0, time.pendingTasks());
        time.advance(Duration.ofMinutes(10));
        assertEquals(1, stored());
    }

    @Test
    void shutdownEndsTheSweep() {
        String url = sqsService.createQueue("swept", Map.of("MessageRetentionPeriod", "60"), REGION).getQueueUrl();
        SqsRetentionSweeper sweeper = new SqsRetentionSweeper(sqsService, true, scheduler);
        sweeper.onStart(null);
        assertEquals(1, time.pendingTasks());

        sweeper.onStop(null);
        sqsService.sendMessage(url, "kept", 0, REGION);
        time.advance(Duration.ofMinutes(10));

        assertTrue(scheduler.isShutdown());
        assertEquals(0, time.pendingTasks());
        assertEquals(1, stored());
    }

    private int stored() {
        return messageStore.get(STORAGE_KEY).orElseThrow().size();
    }
}
