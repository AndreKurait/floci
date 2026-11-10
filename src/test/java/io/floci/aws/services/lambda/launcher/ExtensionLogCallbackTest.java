package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExtensionLogCallbackTest {

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
        ContainerHandle handle = new ContainerHandle("id", "fn", null, null);
        handle.setExtensionExit(new CompletableFuture<>());
        long start = System.nanoTime();

        assertEquals(0, ContainerLauncher.shutdownTimeout(handle, true));

        assertTrue(System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(2));
        assertFalse(handle.extensionExit().isDone(), "A shutdown timeout must not fabricate extension completion");
    }
}
