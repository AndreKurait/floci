package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.floci.aws.services.lambda.runtime.RuntimeApiServer;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtensionLogCallbackTest {
    @Test
    void cleanupRetriesKeepTheFirstExtensionDeadline() {
        ContainerHandle handle = new ContainerHandle("container", "function", null, null);
        assertEquals(100L, handle.beginExtensionShutdown(100L));
        assertEquals(100L, handle.beginExtensionShutdown(2_000_000_100L));
    }

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
    void invokeOnlyAndUnregisteredExtensionsDoNotBlockShutdown() {
        ContainerHandle handle = handle(Map.of("invoke-only", false, "no-events", false));
        handle.setExtensionExits(Map.of(
                "invoke-only", new CompletableFuture<>(),
                "no-events", new CompletableFuture<>(),
                "not-registered", new CompletableFuture<>()));
        assertTrue(handle.extensionShutdownCompletion().isDone());
    }

    @Test
    void shutdownWaitTracksOnlySubscribedExternalExtensions() {
        ContainerHandle handle = handle(Map.of("shutdown", true, "invoke-only", false, "internal", true));
        CompletableFuture<Void> exit = new CompletableFuture<>();
        handle.setExtensionExits(Map.of("shutdown", exit, "invoke-only", new CompletableFuture<>()));
        CompletableFuture<Void> shutdown = handle.extensionShutdownCompletion();
        assertFalse(shutdown.isDone());
        exit.complete(null);
        assertTrue(shutdown.isDone());
    }

    @Test
    void registrationAliasRetainsUnmatchedExternalProcessGrace() {
        ContainerHandle handle = handle(Map.of("custom-name", true, "invoke-only", false));
        CompletableFuture<Void> exit = new CompletableFuture<>();
        handle.setExtensionExits(Map.of("binary-name", exit, "invoke-only", new CompletableFuture<>()));
        CompletableFuture<Void> shutdown = handle.extensionShutdownCompletion();
        assertFalse(shutdown.isDone());
        exit.complete(null);
        assertTrue(shutdown.isDone());
    }

    @Test
    void expiredBudgetDoesNotFabricateProcessCompletion() throws Exception {
        ContainerHandle handle = handle(Map.of("shutdown", true));
        CompletableFuture<Void> exit = new CompletableFuture<>();
        handle.setExtensionExits(Map.of("shutdown", exit));

        ContainerLauncher.awaitExtensionShutdown(handle, 0);

        assertFalse(exit.isDone());
        assertFalse(handle.extensionShutdownCompletion().isDone());
    }

    @Test
    void failedTransportRetainsProcessGraceUntilTheDeadline() throws Exception {
        ContainerHandle handle = handle(Map.of("shutdown", true));
        handle.setExtensionExits(Map.of("shutdown",
                CompletableFuture.failedFuture(new IOException("exec disconnected"))));

        assertFalse(handle.extensionShutdownCompletion().isDone());
        ContainerLauncher.awaitExtensionShutdown(handle, 0);
        assertFalse(handle.extensionShutdownCompletion().isDone());
    }

    private static ContainerHandle handle(Map<String, Boolean> subscriptions) {
        RuntimeApiServer server = mock(RuntimeApiServer.class);
        when(server.extensionShutdownSubscriptions()).thenReturn(subscriptions);
        return new ContainerHandle("id", "fn", server, null);
    }
}
