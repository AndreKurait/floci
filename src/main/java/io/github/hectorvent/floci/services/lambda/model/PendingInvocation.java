package io.github.hectorvent.floci.services.lambda.model;

import java.util.concurrent.CompletableFuture;

public class PendingInvocation {

    private final String requestId;
    private final byte[] payload;
    private final long timeoutMs;
    private volatile long deadlineMs;
    private final String functionArn;
    private final CompletableFuture<InvokeResult> resultFuture;
    private final CompletableFuture<Void> dispatchedFuture = new CompletableFuture<>();
    private final InvocationTimer timer;
    private volatile String clientContext;

    public PendingInvocation(String requestId, byte[] payload, long deadlineMs,
                              String functionArn, CompletableFuture<InvokeResult> resultFuture) {
        this(requestId, payload, deadlineMs, functionArn, resultFuture, InvocationTimer.SYSTEM);
    }

    /** As above, with the deadline measured and enforced on {@code timer}, so tests can control it. */
    public PendingInvocation(String requestId, byte[] payload, long deadlineMs,
                              String functionArn, CompletableFuture<InvokeResult> resultFuture,
                              InvocationTimer timer) {
        this.requestId = requestId;
        this.payload = payload;
        this.deadlineMs = deadlineMs;
        this.timer = timer;
        this.timeoutMs = Math.max(0, deadlineMs - timer.currentTimeMillis());
        this.functionArn = functionArn;
        this.resultFuture = resultFuture;
    }

    public void prepareForDispatch() {
        deadlineMs = timer.currentTimeMillis() + timeoutMs;
    }

    public void markDispatched() {
        timer.failAfter(resultFuture, Math.max(0, deadlineMs - timer.currentTimeMillis()));
        dispatchedFuture.complete(null);
    }

    public String getRequestId() { return requestId; }
    public byte[] getPayload() { return payload; }
    public long getDeadlineMs() { return deadlineMs; }
    public String getFunctionArn() { return functionArn; }
    public String getClientContext() { return clientContext; }
    public void setClientContext(String clientContext) { this.clientContext = clientContext; }
    public CompletableFuture<InvokeResult> getResultFuture() { return resultFuture; }
    public CompletableFuture<Void> getDispatchedFuture() { return dispatchedFuture; }
}
