package io.floci.aws.testing;

import io.restassured.builder.ResponseBuilder;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.AssertionFailedError;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class EmrRequestsTest {

    @Test
    void missingLogsDoNotMaskTheFailedStateOrStartupReason() {
        Response step = step("FAILED");
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> assertCompleted(step, () -> {
                    throw NoSuchKeyException.builder().message("stdout.gz missing").build();
                }));

        assertEquals("COMPLETED", failure.getExpected().getValue());
        assertEquals("FAILED", failure.getActual().getValue());
        assertTrue(failure.getMessage().contains("registry pull refused"), failure.getMessage());
        assertTrue(failure.getMessage().contains("stdout.gz missing"), failure.getMessage());
    }

    @Test
    void unreadableCompressedLogsRetainTheStateAssertion() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> assertCompleted(step("FAILED"), () -> {
                    throw new UncheckedIOException(new IOException("invalid gzip"));
                }));

        assertEquals("FAILED", failure.getActual().getValue());
        assertTrue(failure.getMessage().contains("invalid gzip"), failure.getMessage());
    }

    @Test
    void availableLogsAreRetainedAlongsideTheStepResponse() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> assertCompleted(step("FAILED"), () -> "driver stdout\ncoordinator stderr"));

        assertTrue(failure.getMessage().contains("registry pull refused"), failure.getMessage());
        assertTrue(failure.getMessage().contains("driver stdout\ncoordinator stderr"), failure.getMessage());
    }

    @Test
    void successfulStateDoesNotReadDiagnosticLogs() {
        AtomicInteger reads = new AtomicInteger();
        assertCompleted(step("COMPLETED"), () -> {
            reads.incrementAndGet();
            throw NoSuchKeyException.builder().message("not uploaded").build();
        });
        assertEquals(0, reads.get());
    }

    @Test
    void assertionErrorsFromLogReadersAreNotSwallowed() {
        AssertionError original = new AssertionError("reader invariant");
        assertSame(original, assertThrows(AssertionError.class,
                () -> EmrRequests.stepFailureMessage(step("FAILED"), () -> {
                    throw original;
                })));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "CANCEL_PENDING"})
    void endedOrCancellingStepsFailBeforeCheckingTheDriver(String state) {
        AtomicInteger driverChecks = new AtomicInteger();
        AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> {
            EmrRequests.requireUnfinishedStep(step(state));
            driverChecks.incrementAndGet();
        });

        assertEquals(0, driverChecks.get());
        assertTrue(failure.getMessage().contains(state), failure.getMessage());
        assertTrue(failure.getMessage().contains("registry pull refused"), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING"})
    void unfinishedStepsCanContinueWaitingForTheDriver(String state) {
        assertDoesNotThrow(() -> EmrRequests.requireUnfinishedStep(step(state)));
    }

    @Test
    void terminalStepEscapesTheReadinessWaitOnItsFirstPoll() {
        AtomicInteger describes = new AtomicInteger();
        AtomicInteger driverChecks = new AtomicInteger();

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> await().pollDelay(Duration.ZERO).atMost(Duration.ofSeconds(1)).until(() -> {
                    describes.incrementAndGet();
                    EmrRequests.requireUnfinishedStep(step("FAILED"));
                    driverChecks.incrementAndGet();
                    return true;
                }));

        assertEquals(1, describes.get());
        assertEquals(0, driverChecks.get());
        assertTrue(failure.getMessage().contains("registry pull refused"), failure.getMessage());
    }

    private static void assertCompleted(Response step, Supplier<String> logs) {
        assertEquals("COMPLETED", step.jsonPath().getString("Step.Status.State"),
                () -> EmrRequests.stepFailureMessage(step, logs));
    }

    private static Response step(String state) {
        return new ResponseBuilder().setStatusCode(200).setContentType("application/json").setBody("""
                {"Step":{"Id":"s-fixture","Status":{"State":"%s",
                "FailureDetails":{"Reason":"registry pull refused"}}}}
                """.formatted(state)).build();
    }
}
