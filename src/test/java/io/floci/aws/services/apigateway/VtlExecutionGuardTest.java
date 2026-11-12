package io.floci.aws.services.apigateway;

import io.floci.aws.testing.TestTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Isolated, non-CDI unit tests for {@link VtlExecutionGuard}, run directly against the thread's
 * deadline state without going through Velocity or Quarkus, for fast deterministic coverage of
 * the execution-time deadline mechanism itself.
 */
class VtlExecutionGuardTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final TestTime time = TestTime.create();

    @AfterEach
    void clearAnyLeftoverDeadline() {
        // Guard against a failed assertion mid-test leaking a deadline into later tests on the
        // same thread (JUnit5 reuses threads across test methods by default).
        VtlExecutionGuard.end();
    }

    @Test
    void checkDeadline_withNoActiveDeadline_doesNothing() {
        assertDoesNotThrow(VtlExecutionGuard::checkDeadline);
    }

    @Test
    void checkDeadline_beforeDeadlinePasses_doesNothing() {
        VtlExecutionGuard.begin(TIMEOUT);
        assertDoesNotThrow(VtlExecutionGuard::checkDeadline);
    }

    @Test
    void checkDeadline_untilTheDeadlineItself_doesNothing() {
        VtlExecutionGuard.begin(TIMEOUT, time.clock());
        time.advance(TIMEOUT);
        assertDoesNotThrow(VtlExecutionGuard::checkDeadline);
    }

    @Test
    void checkDeadline_afterDeadlinePasses_throws() {
        VtlExecutionGuard.begin(TIMEOUT, time.clock());
        time.advance(TIMEOUT.plusMillis(1));
        VtlLimitExceededException ex =
                assertThrows(VtlLimitExceededException.class, VtlExecutionGuard::checkDeadline);
        assertNotNullMessage(ex);
    }

    @Test
    void end_clearsDeadline_soCheckDeadlineNoLongerThrows() {
        VtlExecutionGuard.begin(TIMEOUT, time.clock());
        time.advance(TIMEOUT.plusMillis(1));
        VtlExecutionGuard.end();
        assertDoesNotThrow(VtlExecutionGuard::checkDeadline);
    }

    @Test
    void begin_replacesAnyPreviousDeadline() {
        VtlExecutionGuard.begin(TIMEOUT, time.clock());
        time.advance(TIMEOUT.plusMillis(1));
        // A fresh begin() must reset the deadline, not extend the expired one.
        VtlExecutionGuard.begin(TIMEOUT, time.clock());
        assertDoesNotThrow(VtlExecutionGuard::checkDeadline);
        time.advance(TIMEOUT);
        assertDoesNotThrow(VtlExecutionGuard::checkDeadline);
        time.advance(Duration.ofMillis(1));
        assertThrows(VtlLimitExceededException.class, VtlExecutionGuard::checkDeadline);
    }

    private static void assertNotNullMessage(VtlLimitExceededException ex) {
        if (ex.getMessage() == null || ex.getMessage().isBlank()) {
            throw new AssertionError("expected a descriptive message on VtlLimitExceededException");
        }
    }
}
