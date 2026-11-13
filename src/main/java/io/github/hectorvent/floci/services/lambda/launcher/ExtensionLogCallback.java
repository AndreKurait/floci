package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** Signals extension completion after its exec stream has delivered the final logs. */
final class ExtensionLogCallback extends ResultCallback.Adapter<Frame> {

    private final ResultCallback<Frame> delegate;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    ExtensionLogCallback(ResultCallback<Frame> delegate) {
        this.delegate = delegate;
    }

    CompletableFuture<Void> completion() {
        return completion;
    }

    @Override
    public void onStart(Closeable stream) {
        delegate.onStart(stream);
    }

    @Override
    public void onNext(Frame frame) {
        delegate.onNext(frame);
    }

    @Override
    public void onError(Throwable failure) {
        try {
            delegate.onError(failure);
        } finally {
            completion.completeExceptionally(failure);
        }
    }

    @Override
    public void onComplete() {
        try {
            delegate.onComplete();
            completion.complete(null);
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
