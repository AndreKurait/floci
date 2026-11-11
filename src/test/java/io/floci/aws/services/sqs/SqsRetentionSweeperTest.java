package io.floci.aws.services.sqs;

import io.floci.aws.config.EmulatorConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqsRetentionSweeperTest {

    @Test
    void aFailingSweepDoesNotEscapeTheScheduledTask() {
        SqsService sqsService = mock(SqsService.class);
        doThrow(new IllegalStateException("sweep failed")).when(sqsService).deleteExpiredMessages();
        EmulatorConfig config = mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        SqsRetentionSweeper sweeper = new SqsRetentionSweeper(sqsService, config);

        assertDoesNotThrow(sweeper::sweep);
        verify(sqsService).deleteExpiredMessages();
    }


    @Test
    void aSweepThatEndsInAnErrorDoesNotEscapeTheScheduledTask() {
        SqsService failing = mock(SqsService.class);
        doThrow(new StackOverflowError("sweep failed")).when(failing).deleteExpiredMessages();
        SqsRetentionSweeper sweeper = new SqsRetentionSweeper(failing, true, scheduler);

        assertDoesNotThrow(sweeper::sweep);
        verify(failing).deleteExpiredMessages();
    }
}
