package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.WaitContainerCondition;
import com.github.dockerjava.api.model.WaitResponse;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Closeable;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LambdaExitWatcherTest {

    private static final String CONTAINER = "lambda-container";
    private static final long LONG_LIVED_NANOS = LambdaExitWatcher.QUICK_FAILURE.toNanos() + 1;

    private final List<WaitContainerResultCallback> armed = new ArrayList<>();
    private final List<Integer> exits = new ArrayList<>();
    private final List<Duration> delays = new ArrayList<>();
    private long now;
    private DockerClient docker;
    private InspectContainerCmd inspect;
    private WaitContainerCmd wait;
    private Closeable transport;

    @BeforeEach
    void setUp() {
        docker = mock(DockerClient.class);
        wait = mock(WaitContainerCmd.class, RETURNS_SELF);
        transport = mock(Closeable.class);
        when(docker.waitContainerCmd(CONTAINER)).thenReturn(wait);
        when(wait.exec(any())).thenAnswer(invocation -> {
            WaitContainerResultCallback callback = invocation.getArgument(0);
            armed.add(callback);
            callback.onStart(transport);
            return callback;
        });
        inspect = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(CONTAINER)).thenReturn(inspect);
    }

    /** A watch whose clock is {@link #now} and whose scheduler records each delay and runs the task at once. */
    private void watch() {
        new LambdaExitWatcher(docker, CONTAINER, exits::add, (delay, task) -> {
            delays.add(delay);
            task.run();
        }, () -> now).arm();
    }

    private InspectContainerResponse state(Boolean running, Long exitCode) {
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getRunning()).thenReturn(running);
        when(state.getExitCodeLong()).thenReturn(exitCode);
        when(response.getState()).thenReturn(state);
        return response;
    }

    private void containerState(Boolean running, Long exitCode) {
        InspectContainerResponse response = state(running, exitCode);
        when(inspect.exec()).thenReturn(response);
    }

    private static WaitResponse exited(int status) {
        WaitResponse response = mock(WaitResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        return response;
    }

    private void failLatestStream(String message) {
        armed.get(armed.size() - 1).onError(new SocketTimeoutException(message));
    }

    private static List<Duration> seconds(long... values) {
        List<Duration> result = new ArrayList<>();
        for (long value : values) {
            result.add(Duration.ofSeconds(value));
        }
        return result;
    }

    // Catches: a watch that stops when its stream times out on a quiet container, so a crash after
    // the Docker client's response timeout is reported as Function.TimedOut.
    @Test
    void aStreamThatTimesOutOnARunningContainerIsReArmedAndStillReportsTheExit() {
        containerState(true, null);
        watch();

        now += LONG_LIVED_NANOS;
        failLatestStream("Read timed out");
        assertEquals(2, armed.size());
        assertEquals(List.of(), delays);
        assertEquals(List.of(), exits);

        armed.get(1).onNext(exited(137));
        assertEquals(List.of(137), exits);
    }

    @Test
    void aStreamThatFailsAfterTheContainerExitedReportsTheInspectedExitCode() {
        containerState(false, 2L);
        watch();

        failLatestStream("Read timed out");

        assertEquals(1, armed.size());
        assertEquals(List.of(2), exits);
    }

    @Test
    void aStreamThatFailsAfterAutoRemovalReportsAnUnknownExitInsteadOfTimingOut() {
        when(inspect.exec()).thenThrow(new NotFoundException("No such container"));
        watch();

        failLatestStream("Read timed out");

        assertEquals(1, armed.size());
        assertEquals(List.of(-1), exits);
    }

    @Test
    void prearmedNextExitKeepsTheExitStatusEvenAfterAutoRemoval() {
        LambdaExitWatcher watcher = LambdaExitWatcher.watchBeforeStart(
                docker, CONTAINER, exits::add, Duration.ZERO);
        verify(wait).withCondition(WaitContainerCondition.NEXT_EXIT);
        assertEquals(List.of(), exits, "Created is not an exit");

        armed.getFirst().onNext(exited(37));
        armed.getFirst().onComplete();
        watcher.containerStarted();
        armed.getFirst().onError(new NotFoundException("Already auto-removed"));

        assertEquals(List.of(37), exits);
        verify(docker, never()).inspectContainerCmd(CONTAINER);
    }

    @Test
    void missingOrFailedWaitRegistrationRefusesLaunchWithoutInventingAnExit() {
        doThrow(new NotFoundException("Captured container is absent")).when(wait).exec(any());

        assertThrows(IllegalStateException.class, () -> LambdaExitWatcher.watchBeforeStart(
                docker, CONTAINER, exits::add, Duration.ZERO));

        assertEquals(List.of(), exits);
        verify(docker, never()).inspectContainerCmd(CONTAINER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitWithoutHealthyResponseHeadersCannotAuthorizeStart(boolean error) throws Exception {
        List<WaitContainerResultCallback> callbacks = new ArrayList<>();
        doAnswer(call -> {
            WaitContainerResultCallback callback = call.getArgument(0);
            callbacks.add(callback);
            if (error) {
                callback.onError(new SocketTimeoutException("Failed before headers"));
            }
            return callback;
        }).when(wait).exec(any());
        assertThrows(IllegalStateException.class, () -> LambdaExitWatcher.watchBeforeStart(
                docker, CONTAINER, exits::add, Duration.ZERO));
        Closeable lateTransport = mock(Closeable.class);
        callbacks.getFirst().onStart(lateTransport);

        verify(lateTransport).close();
        assertEquals(List.of(), exits);
        verify(docker, never()).inspectContainerCmd(CONTAINER);
    }

    @Test
    void errorAfterWaitAcknowledgmentStillRefusesLaunch() {
        doAnswer(call -> {
            WaitContainerResultCallback callback = call.getArgument(0);
            callback.onStart(transport);
            callback.onError(new SocketTimeoutException("Failed after response headers"));
            return callback;
        }).when(wait).exec(any());

        assertThrows(IllegalStateException.class, () -> LambdaExitWatcher.watchBeforeStart(
                docker, CONTAINER, exits::add, Duration.ZERO));
        assertEquals(List.of(), exits);
        verify(docker, never()).inspectContainerCmd(CONTAINER);
    }

    @Test
    void intentionalCancellationClosesItsCapturedStreamAndSuppressesLateExitAndError() throws Exception {
        LambdaExitWatcher watcher = LambdaExitWatcher.watchBeforeStart(
                docker, CONTAINER, exits::add, Duration.ZERO);
        watcher.containerStarted();
        watcher.cancel();
        armed.getFirst().onNext(exited(143));
        armed.getFirst().onError(new NotFoundException("Intentional removal"));

        assertEquals(List.of(), exits);
        verify(transport).close();
        verify(docker, never()).inspectContainerCmd(CONTAINER);
    }

    @Test
    void failureDuringStartIsHandedOverOnceEvenIfTheRuntimeAlreadyDisappeared() {
        when(inspect.exec()).thenThrow(new NotFoundException("Auto-removed during start"));
        LambdaExitWatcher watcher = LambdaExitWatcher.watchBeforeStart(
                docker, CONTAINER, exits::add, Duration.ZERO);
        armed.getFirst().onError(new SocketTimeoutException("Failed while start was in flight"));
        assertEquals(List.of(), exits, "A created container must not be reported as an exited runtime");

        watcher.containerStarted();
        watcher.containerStarted();

        assertEquals(List.of(-1), exits);
        verify(docker).inspectContainerCmd(CONTAINER);
    }

    @Test
    void anEmptyTerminalWaitAfterStartDoesNotSilentlyLoseAnAutoRemovedExit() {
        when(inspect.exec()).thenThrow(new NotFoundException("Auto-removed runtime"));
        LambdaExitWatcher watcher = LambdaExitWatcher.watchBeforeStart(
                docker, CONTAINER, exits::add, Duration.ZERO);
        watcher.containerStarted();

        armed.getFirst().onComplete();

        assertEquals(List.of(-1), exits);
        verify(docker).inspectContainerCmd(CONTAINER);
    }

    // Catches: quick re-arms spent back to back, which exhaust the budget within milliseconds of the
    // daemon going away instead of riding out a short outage.
    @Test
    void quickFailuresReArmAfterADoublingDelayThenStop() {
        containerState(true, null);
        watch();

        for (int i = 0; i <= LambdaExitWatcher.MAX_QUICK_FAILURES; i++) {
            failLatestStream("Connection reset");
        }

        assertEquals(seconds(1, 2, 4, 8, 16), delays);
        assertEquals(LambdaExitWatcher.MAX_QUICK_FAILURES + 1, armed.size());
        assertEquals(List.of(), exits);
    }

    // Catches: a failed inspect ending the watch for good, so a daemon restart (which stops the
    // container) loses the Runtime.ExitError the next inspect would report.
    @Test
    void anInspectThatFailsIsRetriedAndThenReportsTheExit() {
        InspectContainerResponse stopped = state(false, 137L);
        when(inspect.exec())
                .thenThrow(new RuntimeException("Cannot connect to the Docker daemon"))
                .thenReturn(stopped);
        watch();

        failLatestStream("Connection reset");
        assertEquals(seconds(1), delays);
        assertEquals(2, armed.size());

        failLatestStream("Connection reset");
        assertEquals(List.of(137), exits);
    }

    // Catches: the quick-failure count surviving a stream that lived past the threshold, so a few
    // unrelated blips spread over a long-running container would end the watch.
    @Test
    void aLongLivedStreamResetsTheQuickFailureCount() {
        containerState(true, null);
        watch();

        failLatestStream("Connection reset");
        failLatestStream("Connection reset");
        assertEquals(seconds(1, 2), delays);

        now += LONG_LIVED_NANOS;
        failLatestStream("Read timed out");
        assertEquals(seconds(1, 2), delays);

        failLatestStream("Connection reset");
        assertEquals(seconds(1, 2, 1), delays);
        assertEquals(5, armed.size());
    }
}
