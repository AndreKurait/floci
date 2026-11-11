package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.floci.aws.services.lambda.launcher.ExtensionShutdownWait.ExtensionExec;
import io.floci.aws.services.lambda.runtime.RuntimeApiServer;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

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
    /** The daemon is only asked about a process whose output stream failed. */
    private static final Predicate<String> NEVER_ASKED = execId -> {
        throw new AssertionError("Unexpected daemon inspection of " + execId);
    };

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
        handle.setExtensionExecs(Map.of(
                "invoke-only", running("invoke-only"),
                "no-events", running("no-events"),
                "not-registered", running("not-registered")));
        assertTrue(handle.extensionShutdownWait(NEVER_ASKED).isComplete());
    }

    @Test
    void shutdownWaitTracksOnlySubscribedExternalExtensions() {
        ContainerHandle handle = handle(Map.of("shutdown", true, "invoke-only", false, "internal", true));
        CompletableFuture<Void> exit = new CompletableFuture<>();
        handle.setExtensionExecs(Map.of(
                "shutdown", new ExtensionExec("exec-shutdown", exit),
                "invoke-only", running("invoke-only")));
        ExtensionShutdownWait shutdown = handle.extensionShutdownWait(NEVER_ASKED);
        assertFalse(shutdown.isComplete());
        exit.complete(null);
        assertTrue(shutdown.isComplete());
    }

    @Test
    void registrationAliasRetainsUnmatchedExternalProcessGrace() {
        ContainerHandle handle = handle(Map.of("custom-name", true, "invoke-only", false));
        CompletableFuture<Void> exit = new CompletableFuture<>();
        handle.setExtensionExecs(Map.of(
                "binary-name", new ExtensionExec("exec-binary-name", exit),
                "invoke-only", running("invoke-only")));
        ExtensionShutdownWait shutdown = handle.extensionShutdownWait(NEVER_ASKED);
        assertFalse(shutdown.isComplete());
        exit.complete(null);
        assertTrue(shutdown.isComplete());
    }

    @Test
    void expiredBudgetDoesNotFabricateProcessCompletion() throws Exception {
        ContainerHandle handle = handle(Map.of("shutdown", true));
        CompletableFuture<Void> exit = new CompletableFuture<>();
        handle.setExtensionExecs(Map.of("shutdown", new ExtensionExec("exec-shutdown", exit)));
        ExtensionShutdownWait shutdown = handle.extensionShutdownWait(NEVER_ASKED);

        assertFalse(shutdown.await(0));

        assertFalse(exit.isDone());
        assertFalse(shutdown.isComplete());
    }

    @Test
    void failedTransportRetainsProcessGraceWhileTheDaemonReportsTheProcessRunning() throws Exception {
        ContainerHandle handle = handle(Map.of("shutdown", true));
        handle.setExtensionExecs(Map.of("shutdown", new ExtensionExec("exec-shutdown",
                CompletableFuture.failedFuture(new IOException("exec disconnected")))));
        AtomicBoolean exited = new AtomicBoolean();
        ExtensionShutdownWait shutdown = handle.extensionShutdownWait(execId -> exited.get());

        assertFalse(shutdown.isComplete());
        assertFalse(shutdown.await(0));
        exited.set(true);
        assertTrue(shutdown.isComplete());
    }

    @Test
    void cleanupRetriesKeepTheFirstExtensionSelection() {
        RuntimeApiServer server = mock(RuntimeApiServer.class);
        // A quiesced server may report differently; the retry must not select again.
        when(server.extensionShutdownSubscriptions()).thenReturn(Map.of("shutdown", true), Map.of());
        ContainerHandle handle = new ContainerHandle("id", "fn", server, null);
        handle.setExtensionExecs(Map.of("shutdown", running("shutdown")));

        ExtensionShutdownWait first = handle.extensionShutdownWait(NEVER_ASKED);

        assertSame(first, handle.extensionShutdownWait(NEVER_ASKED));
        assertFalse(first.isComplete());
        verify(server).extensionShutdownSubscriptions();
    }

    private static ExtensionExec running(String name) {
        return new ExtensionExec("exec-" + name, new CompletableFuture<>());
    }

    private static ContainerHandle handle(Map<String, Boolean> subscriptions) {
        RuntimeApiServer server = mock(RuntimeApiServer.class);
        when(server.extensionShutdownSubscriptions()).thenReturn(subscriptions);
        return new ContainerHandle("id", "fn", server, null);
    }
}
