package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.services.lambda.TimelineInvocationTimer;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExtensionLogCallbackTest {
    private static final Duration EXTENSION_GRACE = Duration.ofSeconds(2);

    @Test
    void completionFollowsFinalLogDelivery() throws Exception {
        ResultCallback<Frame> delegate = mock(ResultCallback.class);
        ExtensionLogCallback callback = new ExtensionLogCallback(delegate);
        Closeable stream = mock(Closeable.class);
        Frame frame = mock(Frame.class);
        doAnswer(invocation -> {
            assertFalse(callback.completion().isDone());
            return null;
        }).when(delegate).onComplete();

        callback.onStart(stream);
        callback.onNext(frame);
        assertFalse(callback.completion().isDone());
        callback.onComplete();
        callback.completion().join();
        callback.close();

        verify(delegate).onStart(stream);
        verify(delegate).onNext(frame);
        verify(delegate).onComplete();
        verify(delegate).close();
    }

    @Test
    void transportFailureSettlesCompletion() {
        ResultCallback<Frame> delegate = mock(ResultCallback.class);
        ExtensionLogCallback callback = new ExtensionLogCallback(delegate);
        IOException failure = new IOException("lost exec stream");

        callback.onError(failure);

        assertSame(failure, assertThrows(CompletionException.class, callback.completion()::join).getCause());
        verify(delegate).onError(failure);
    }

    @Test
    void noExtensionsRetainsRuntimeFallbackWithoutWaiting() {
        ContainerHandle handle = new ContainerHandle("id", "fn", null, null);
        handle.setExtensionExit(new CompletableFuture<>());
        assertEquals(1, ContainerLauncher.shutdownTimeout(handle, false));
    }

    @Test
    void completedExtensionsDoNotConsumeGrace() {
        ContainerHandle handle = new ContainerHandle("id", "fn", null, null);
        assertEquals(2, ContainerLauncher.shutdownTimeout(handle, true));
    }

    @Test
    void unresponsiveExtensionCannotAddAnotherStopGracePeriod() {
        TestTime time = TestTime.create();
        ContainerHandle handle = new ContainerHandle("id", "fn", null, null);
        handle.setExtensionExit(new CompletableFuture<>());

        assertEquals(0, ContainerLauncher.shutdownTimeout(handle, true, new TimelineInvocationTimer(time)));

        assertEquals(TestTime.DEFAULT_START.plus(EXTENSION_GRACE), time.now());
        assertFalse(handle.extensionExit().isDone(), "A shutdown timeout must not fabricate extension completion");
    }

    @Test
    void extensionExitingDuringTheGraceLeavesTheWholeSecondsThatRemain() {
        TestTime time = TestTime.create();
        ContainerHandle handle = new ContainerHandle("id", "fn", null, null);
        CompletableFuture<Void> exit = new CompletableFuture<>();
        handle.setExtensionExit(exit);
        time.scheduler().schedule(() -> exit.complete(null), 1000, TimeUnit.MILLISECONDS);

        assertEquals(1, ContainerLauncher.shutdownTimeout(handle, true, new TimelineInvocationTimer(time)));

        assertEquals(TestTime.DEFAULT_START.plusMillis(1000), time.now());
    }

    @Test
    void extensionExitingPastAWholeSecondOfTheGraceLeavesNoStopTimeout() {
        TestTime time = TestTime.create();
        ContainerHandle handle = new ContainerHandle("id", "fn", null, null);
        CompletableFuture<Void> exit = new CompletableFuture<>();
        handle.setExtensionExit(exit);
        time.scheduler().schedule(() -> exit.complete(null), 1001, TimeUnit.MILLISECONDS);

        assertEquals(0, ContainerLauncher.shutdownTimeout(handle, true, new TimelineInvocationTimer(time)));

        assertEquals(TestTime.DEFAULT_START.plusMillis(1001), time.now());
    }

    @Test
    void interruptedWaitForAnExtensionReturnsWithoutAStopTimeout() {
        // Real-time wiring of the system wait: an interrupt ends it at once, so nothing is waited out
        ContainerHandle handle = new ContainerHandle("id", "fn", null, null);
        handle.setExtensionExit(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertEquals(0, ContainerLauncher.shutdownTimeout(handle, true));
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt must be restored for the caller");
        }
    }
}
