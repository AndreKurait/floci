package io.github.hectorvent.floci.services.elbv2;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ElbV2PlainListenerReadinessTest {

    @Test
    void connectionResetDuringProtocolReplacementIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        ElbV2HttpsDataPlaneIntegrationTest.awaitPlainResponse(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new SocketException("Connection reset by peer (connect failed)");
            }
        }, Duration.ofSeconds(2));
        assertEquals(2, attempts.get());
    }

    @Test
    void connectionRefusalDuringReplacementRemainsRetryable() {
        AtomicInteger attempts = new AtomicInteger();
        ElbV2HttpsDataPlaneIntegrationTest.awaitPlainResponse(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new ConnectException("Connection refused");
            }
        }, Duration.ofSeconds(2));
        assertEquals(2, attempts.get());
    }

    @Test
    void permanentlyResettingListenerFailsWithinDeadline() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(ConditionTimeoutException.class, () ->
                ElbV2HttpsDataPlaneIntegrationTest.awaitPlainResponse(() -> {
                    attempts.incrementAndGet();
                    throw new SocketException("Connection reset");
                }, Duration.ofMillis(350)));
        assertTrue(attempts.get() > 1);
    }

    @Test
    void permanentlyStoppedListenerStillFails() {
        assertThrows(ConditionTimeoutException.class, () ->
                ElbV2HttpsDataPlaneIntegrationTest.awaitPlainResponse(() -> {
                    throw new ConnectException("Connection refused");
                }, Duration.ofMillis(350)));
    }

    @Test
    void wrongResponseCannotSatisfyReadiness() {
        assertThrows(ConditionTimeoutException.class, () ->
                ElbV2HttpsDataPlaneIntegrationTest.awaitPlainResponse(() ->
                        assertEquals("expected", "wrong listener"), Duration.ofMillis(350)));
    }

    @Test
    void unrelatedIoFailureIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        IOException failure = new IOException("Unexpected response framing");
        IOException actual = assertThrows(IOException.class, () ->
                ElbV2HttpsDataPlaneIntegrationTest.awaitPlainResponse(() -> {
                    attempts.incrementAndGet();
                    throw failure;
                }, Duration.ofSeconds(2)));
        assertSame(failure, actual);
        assertEquals(1, attempts.get());
    }
}
