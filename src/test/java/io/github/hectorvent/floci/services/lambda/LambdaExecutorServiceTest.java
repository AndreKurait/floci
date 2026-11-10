package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.FunctionEventInvokeConfig;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.PendingInvocation;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import io.github.hectorvent.floci.testing.MutableClock;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LambdaExecutorServiceTest {

    /** The default of floci.services.lambda.async-retry-delay-seconds, which is also what AWS waits. */
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    /** How long a retry that found the function's concurrency in use waits before asking again. */
    private static final Duration THROTTLED_RETRY_POLL = Duration.ofSeconds(1);
    /** Extra time a runtime gets to take an invocation, on top of the function's timeout. */
    private static final Duration DISPATCH_GRACE = Duration.ofSeconds(2);
    private static final Duration TICK = Duration.ofMillis(1);

    @Mock WarmPool warmPool;
    @Mock LambdaConcurrencyLimiter concurrencyLimiter;

    private final TestTime time = TestTime.create();
    private final List<LambdaExecutorService> executors = new ArrayList<>();
    private LambdaExecutorService executor;
    private LambdaFunction fn;

    @BeforeEach
    void setUp() {
        executor = registerExecutor(new LambdaExecutorService(warmPool, new ObjectMapper(), concurrencyLimiter));

        fn = new LambdaFunction();
        fn.setFunctionName("test-fn");
        fn.setFunctionArn("arn:aws:lambda:us-east-1:000000000000:function:test-fn");
        fn.setTimeout(1);

        when(concurrencyLimiter.acquire(any())).thenReturn(() -> {});
    }

    @AfterEach
    void shutDownExecutors() {
        for (LambdaExecutorService owned : executors) {
            owned.shutdown();
        }
    }

    private LambdaExecutorService registerExecutor(LambdaExecutorService owned) {
        executors.add(owned);
        return owned;
    }

    @Test
    void asyncInvocationPastTheChainBound_isDroppedWithoutRunningTheFunction() {
        // AWS stops the next invocation in a chain of requests once the same originating event has
        // caused about this many, which is what keeps a destination cycle from running forever.
        InvokeResult result = executor.invoke(fn, "{}".getBytes(), InvocationType.Event,
                LambdaInvocationChain.MAX_DEPTH);

        assertEquals(202, result.getStatusCode());
        assertNotNull(result.getRequestId());
        verify(concurrencyLimiter, never()).acquire(any());
        verify(warmPool, never()).acquire(any());
    }

    @Test
    void aVersionTakesItsPermitAgainstTheFunctionsReservation() {
        // A version's snapshot carries no reservation; reserved concurrency is function-wide.
        fn.setReservedConcurrentExecutions(0);
        LambdaFunction version = new LambdaFunction();
        version.setFunctionName("test-fn");
        version.setVersion("1");
        version.setFunctionArn(fn.getFunctionArn() + ":1");
        LambdaService lambdaService = mock(LambdaService.class);
        when(lambdaService.findLatest(version)).thenReturn(Optional.of(fn));
        LambdaExecutorService versionExecutor = registerExecutor(new LambdaExecutorService(warmPool, new ObjectMapper(),
                new LambdaConcurrencyLimiter(), null, lambdaService));

        AwsException ex = assertThrows(AwsException.class,
                () -> versionExecutor.invoke(version, "{}".getBytes(), InvocationType.RequestResponse));

        assertEquals("TooManyRequestsException", ex.getErrorCode());
        verify(warmPool, never()).acquire(any());
    }

    @Test
    void aProvisionedRuntimeDoesNotSpendUnreservedCapacityButColdSpilloverDoes() {
        fn.setVersion("1");
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(1, 0);
        limiter.validateAndSetProvisioned(fn.getFunctionArn(), "live", 1);
        RuntimeApiServer runtime = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-provisioned", "test-fn", runtime, ContainerState.WARM);
        when(warmPool.acquireProvisioned(fn, "live")).thenReturn(handle, null);
        doAnswer(invocation -> {
            PendingInvocation pending = invocation.getArgument(0);
            pending.getResultFuture().complete(new InvokeResult(200, null, "{}".getBytes(), null, pending.getRequestId()));
            return pending.getResultFuture();
        }).when(runtime).enqueue(any());
        LambdaExecutorService provisionedExecutor = new LambdaExecutorService(warmPool, new ObjectMapper(), limiter);
        executors.add(provisionedExecutor);

        assertEquals(200, provisionedExecutor.invoke(fn, "{}".getBytes(),
                InvocationType.RequestResponse, 0, "live").getStatusCode());
        verify(warmPool).release(handle);
        assertEquals("TooManyRequestsException", assertThrows(AwsException.class,
                () -> provisionedExecutor.invoke(fn, "{}".getBytes(),
                        InvocationType.RequestResponse, 0, "live")).getErrorCode());
        verify(warmPool, never()).acquire(any());
    }

    @Test
    void aRejectedProvisionedInvocationReturnsItsCheckedOutRuntime() {
        fn.setVersion("1");
        fn.setReservedConcurrentExecutions(1);
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        ContainerHandle handle = new ContainerHandle("cid-provisioned", "test-fn",
                mock(RuntimeApiServer.class), ContainerState.WARM);
        when(warmPool.acquireProvisioned(fn, "live")).thenReturn(handle);
        LambdaExecutorService provisionedExecutor = new LambdaExecutorService(
                warmPool, new ObjectMapper(), limiter);
        executors.add(provisionedExecutor);

        try (LambdaConcurrencyLimiter.Permit ignored = limiter.acquire(fn)) {
            assertThrows(AwsException.class, () -> provisionedExecutor.invoke(fn, "{}".getBytes(),
                    InvocationType.RequestResponse, 0, "live"));
        }
        verify(warmPool).release(handle);
        verify(handle.getRuntimeApiServer(), never()).enqueue(any());
    }

    @Test
    void anAsyncStoreFailureDoesNotCheckOutProvisionedCapacity() {
        LambdaService lambdaService = mock(LambdaService.class);
        when(lambdaService.isLive(fn)).thenThrow(new IllegalStateException("store unavailable"));
        LambdaExecutorService provisionedExecutor = new LambdaExecutorService(
                warmPool, new ObjectMapper(), concurrencyLimiter, null, lambdaService);
        executors.add(provisionedExecutor);

        assertThrows(IllegalStateException.class, () -> provisionedExecutor.invoke(fn, "{}".getBytes(),
                InvocationType.Event, 0, "live"));
        verify(warmPool, never()).acquireProvisioned(any(), any());
        verify(concurrencyLimiter, never()).acquire(any());
    }

    @Test
    void anAsyncInvocationRejectedDuringShutdownReturnsItsProvisionedRuntimeAndPermit() {
        fn.setVersion("1");
        ContainerHandle handle = new ContainerHandle("cid-provisioned", "test-fn",
                mock(RuntimeApiServer.class), ContainerState.WARM);
        LambdaConcurrencyLimiter.Permit permit = mock(LambdaConcurrencyLimiter.Permit.class);
        when(warmPool.acquireProvisioned(fn, "live")).thenReturn(handle);
        when(concurrencyLimiter.acquireProvisioned(fn)).thenReturn(permit);
        executor.shutdown();

        assertThrows(RejectedExecutionException.class, () -> executor.invoke(fn, "{}".getBytes(),
                InvocationType.Event, 0, "live"));
        verify(warmPool).release(handle);
        verify(permit).close();
        verify(handle.getRuntimeApiServer(), never()).enqueue(any());
    }

    @Test
    void asyncInvocationShortOfTheChainBound_stillRuns() {
        executor.invoke(fn, "{}".getBytes(), InvocationType.Event, LambdaInvocationChain.MAX_DEPTH - 1);

        verify(warmPool, timeout(5000).atLeastOnce()).acquire(fn);
    }

    @Test
    void asyncInvocationPassesInvokedAliasToDestinationRouter() {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        executor.shutdown();
        executor = registerExecutor(new LambdaExecutorService(warmPool, new ObjectMapper(), concurrencyLimiter, router));
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-alias", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(fn)).thenReturn(handle);
        doAnswer(inv -> {
            PendingInvocation invocation = inv.getArgument(0);
            invocation.getResultFuture().complete(
                    new InvokeResult(200, null, "{}".getBytes(), null, invocation.getRequestId()));
            return invocation.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        byte[] payload = "{}".getBytes();
        executor.invoke(fn, payload, InvocationType.Event, 0, "prod");

        verify(router, timeout(5000)).route(eq(fn), eq(payload), any(InvokeResult.class), eq(1), eq(0), eq("prod"));
    }

    @Test
    void syncInvocationPastTheChainBound_isNotAffected() {
        // Only the asynchronous path can be re-entered by a destination chain, and refusing a
        // RequestResponse invoke would need an error shape AWS does not define here.
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-bound", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        when(rtas.enqueue(any())).thenReturn(new CompletableFuture<>());

        timedExecutor(null, null, RETRY_DELAY)
                .invoke(fn, "{}".getBytes(), InvocationType.RequestResponse, LambdaInvocationChain.MAX_DEPTH);

        verify(warmPool).acquire(fn);
    }

    @Test
    void timeoutInvocation_destroysHandle_doesNotRelease() {
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-1", "test-fn", rtas, ContainerState.WARM);

        when(warmPool.acquire(any())).thenReturn(handle);
        when(rtas.enqueue(any())).thenReturn(new CompletableFuture<>());

        InvokeResult result = timedExecutor(null, null, RETRY_DELAY)
                .invoke(fn, "{}".getBytes(), InvocationType.RequestResponse);

        verify(warmPool).destroyHandle(handle);
        verify(warmPool, never()).release(handle);
        assertEquals(200, result.getStatusCode());
        assertEquals("Unhandled", result.getFunctionError());
        String payload = new String(result.getPayload());
        assertTrue(payload.contains("Function.TimedOut"), "payload should contain error type");
        assertTrue(payload.contains("1 seconds"), "payload should contain timeout value");
        assertEquals(Duration.ofSeconds(1).plus(DISPATCH_GRACE), elapsed(),
                "a runtime that never takes the invocation is given the timeout plus the dispatch grace");
    }

    @Test
    void syncInvoke_clientContext_isPlacedOnTheEnqueuedInvocation() {
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-ctx", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(inv -> {
            PendingInvocation pi = inv.getArgument(0);
            seen.set(pi.getClientContext());
            pi.getResultFuture().complete(new InvokeResult(200, null, "{}".getBytes(), null, "req-ctx"));
            return pi.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        String clientContext = "{\"custom\":{\"traceparent\":\"00-abc-def-01\"}}";
        executor.invoke(fn, "{}".getBytes(), InvocationType.RequestResponse, 0, null, clientContext);

        assertEquals(clientContext, seen.get());
    }

    @ParameterizedTest
    @CsvSource({
            "RequestResponse, 1, live, live, false",
            "Event, 1, live, live, false",
            "RequestResponse, 1, live, live, true",
            "Event, 1, live, live, true",
            "RequestResponse, 1, 1, 1, false",
            "Event, 1, 1, 1, false",
            "RequestResponse, $LATEST, $LATEST, $LATEST, false",
            "Event, $LATEST, $LATEST, $LATEST, false",
            "RequestResponse, $LATEST, , '', false",
            "Event, $LATEST, , '', false"
    })
    void runtimeReceivesTheInvokedQualifierInsteadOfTheResolvedVersion(
            InvocationType type, String version, String qualifier, String expectedQualifier,
            boolean provisioned) throws Exception {
        String baseArn = fn.getFunctionArn();
        fn.setVersion(version);
        if (!"$LATEST".equals(version)) {
            fn.setFunctionArn(baseArn + ":" + version);
        }
        RuntimeApiServer runtime = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-context", "test-fn", runtime, ContainerState.WARM);
        when(warmPool.acquire(fn)).thenReturn(handle);
        if (provisioned) {
            when(warmPool.acquireProvisioned(fn, qualifier)).thenReturn(handle);
            when(concurrencyLimiter.acquireProvisioned(fn)).thenReturn(() -> {});
        }
        CompletableFuture<String> observedArn = new CompletableFuture<>();
        doAnswer(call -> {
            PendingInvocation invocation = call.getArgument(0);
            observedArn.complete(invocation.getFunctionArn());
            invocation.getResultFuture().complete(
                    new InvokeResult(200, null, "{}".getBytes(), null, invocation.getRequestId()));
            return invocation.getResultFuture();
        }).when(runtime).enqueue(any(PendingInvocation.class));

        InvokeResult result = executor.invoke(fn, "{}".getBytes(), type, 0, qualifier);

        assertEquals(type == InvocationType.Event ? 202 : 200, result.getStatusCode());
        String expectedArn = expectedQualifier.isEmpty() ? baseArn : baseArn + ":" + expectedQualifier;
        assertEquals(expectedArn, observedArn.get(5, TimeUnit.SECONDS));
        assertEquals(version, fn.getVersion());
        verify(warmPool, timeout(5000)).release(handle);
    }

    @Test
    void successfulInvocation_releasesHandle_doesNotDestroy() {
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-2", "test-fn", rtas, ContainerState.WARM);

        when(warmPool.acquire(any())).thenReturn(handle);
        InvokeResult expected = new InvokeResult(200, null, "{\"ok\":true}".getBytes(), null, "req-1");
        doAnswer(inv -> {
            PendingInvocation pi = inv.getArgument(0);
            pi.getResultFuture().complete(expected);
            return pi.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        InvokeResult result = executor.invoke(fn, "{}".getBytes(), InvocationType.RequestResponse);

        verify(warmPool).release(handle);
        verify(warmPool, never()).destroyHandle(handle);
        assertEquals(200, result.getStatusCode());
        assertNull(result.getFunctionError());
    }

    @Test
    void dispatchWait_coldRuntimeGetsTheInitBudget() {
        LambdaExecutorService withBudget =
                registerExecutor(new LambdaExecutorService(warmPool, new ObjectMapper(), concurrencyLimiter, 10));
        try {
            assertEquals(10_000, withBudget.dispatchWaitMs(1, true, 50_000, 50_000));
        } finally {
            withBudget.shutdown();
        }
    }

    @Test
    void dispatchWait_warmRuntimeGetsTheTimeoutPlusGraceNotTheInitBudget() {
        LambdaExecutorService withBudget =
                registerExecutor(new LambdaExecutorService(warmPool, new ObjectMapper(), concurrencyLimiter, 10));
        try {
            assertEquals(3_000, withBudget.dispatchWaitMs(1, false, 50_000, 50_000));
        } finally {
            withBudget.shutdown();
        }
    }

    @Test
    void dispatchWait_lateStartedRuntimeGetsWhatIsLeftOfTheInitBudget() {
        LambdaExecutorService withBudget =
                registerExecutor(new LambdaExecutorService(warmPool, new ObjectMapper(), concurrencyLimiter, 10));
        try {
            // The launcher spent 4 of the 10 seconds after the start (extensions, log attach).
            assertEquals(6_000, withBudget.dispatchWaitMs(1, true, 50_000, 54_000));
            // Once less is left than Timeout plus grace, that wait applies instead.
            assertEquals(3_000, withBudget.dispatchWaitMs(1, true, 50_000, 59_000));
        } finally {
            withBudget.shutdown();
        }
    }

    @Test
    void resultAfterConfiguredDeadline_timesOutAndDestroysHandle() {
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-late", "test-fn", rtas, ContainerState.WARM);

        when(warmPool.acquire(any())).thenReturn(handle);
        dispatchAndCompleteAfter(rtas, Duration.ofMillis(1200), "req-late");

        InvokeResult result = timedExecutor(null, null, RETRY_DELAY)
                .invoke(fn, "{}".getBytes(), InvocationType.RequestResponse);

        verify(warmPool).destroyHandle(handle);
        verify(warmPool, never()).release(handle);
        assertEquals("Unhandled", result.getFunctionError());
        assertTrue(new String(result.getPayload()).contains("Function.TimedOut"));
        assertEquals(Duration.ofSeconds(1), elapsed(), "the invocation is cut off at the function's timeout");
    }

    @Test
    void resultJustInsideConfiguredDeadline_isReturnedAndReleasesHandle() {
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-in-time", "test-fn", rtas, ContainerState.WARM);

        when(warmPool.acquire(any())).thenReturn(handle);
        dispatchAndCompleteAfter(rtas, Duration.ofSeconds(1).minus(TICK), "req-in-time");

        InvokeResult result = timedExecutor(null, null, RETRY_DELAY)
                .invoke(fn, "{}".getBytes(), InvocationType.RequestResponse);

        verify(warmPool).release(handle);
        verify(warmPool, never()).destroyHandle(handle);
        assertNull(result.getFunctionError());
        assertEquals("req-in-time", result.getRequestId());
        assertEquals(Duration.ofSeconds(1).minus(TICK), elapsed());
    }

    @Test
    void timeoutResponse_containsCorrectErrorPayload() {
        fn.setTimeout(2);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-3", "test-fn", rtas, ContainerState.WARM);

        when(warmPool.acquire(any())).thenReturn(handle);
        when(rtas.enqueue(any())).thenReturn(new CompletableFuture<>());

        InvokeResult result = timedExecutor(null, null, RETRY_DELAY)
                .invoke(fn, "{}".getBytes(), InvocationType.RequestResponse);

        assertNotNull(result.getPayload());
        String payload = new String(result.getPayload());
        assertTrue(payload.contains("\"errorType\":\"Function.TimedOut\""));
        assertTrue(payload.contains("Task timed out after 2 seconds"));
        assertNotNull(result.getRequestId());
        assertEquals(Duration.ofSeconds(2).plus(DISPATCH_GRACE), elapsed());
    }

    @Test
    void dryRunInvocation_doesNotAcquireContainer() {
        InvokeResult result = executor.invoke(fn, "{}".getBytes(), InvocationType.DryRun);

        verify(warmPool, never()).acquire(any());
        verify(warmPool, never()).release(any());
        verify(warmPool, never()).destroyHandle(any());
        assertEquals(204, result.getStatusCode());
    }

    @Test
    void interruptedInvocation_destroysHandle_doesNotRelease() throws Exception {
        fn.setTimeout(30);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-int", "test-fn", rtas, ContainerState.WARM);

        when(warmPool.acquire(any())).thenReturn(handle);
        CountDownLatch enqueued = new CountDownLatch(1);
        doAnswer(inv -> {
            PendingInvocation pi = inv.getArgument(0);
            enqueued.countDown();
            return pi.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        AtomicReference<InvokeResult> resultRef = new AtomicReference<>();
        Thread worker = new Thread(() -> resultRef.set(
                executor.invoke(fn, "{}".getBytes(), InvocationType.RequestResponse)));
        worker.start();

        assertTrue(enqueued.await(5, TimeUnit.SECONDS), "enqueue never called");
        worker.interrupt();
        worker.join(5_000);

        InvokeResult result = resultRef.get();
        assertNotNull(result, "invoke did not return");
        verify(warmPool).destroyHandle(handle);
        verify(warmPool, never()).release(handle);
        assertEquals(200, result.getStatusCode());
        assertEquals("Unhandled", result.getFunctionError());
        assertTrue(new String(result.getPayload()).contains("Interrupted"));
    }

    @Test
    void eventInvocation_answers202AndRoutesTheResultToDestinations() throws Exception {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaExecutorService routingExecutor =
                registerExecutor(new LambdaExecutorService(warmPool, new ObjectMapper(), concurrencyLimiter, router));

        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-async", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        InvokeResult expected = new InvokeResult(200, null, "{\"ok\":true}".getBytes(), null, "req-async");
        doAnswer(inv -> {
            PendingInvocation pi = inv.getArgument(0);
            pi.getResultFuture().complete(expected);
            return pi.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        CountDownLatch routed = new CountDownLatch(1);
        doAnswer(inv -> {
            routed.countDown();
            return null;
        }).when(router).route(any(), any(), any(), anyInt(), anyInt(), isNull());

        byte[] payload = "{}".getBytes();
        InvokeResult result = routingExecutor.invoke(fn, payload, InvocationType.Event);

        assertEquals(202, result.getStatusCode());
        assertTrue(routed.await(5, TimeUnit.SECONDS), "destination routing never ran");
        verify(router).route(fn, payload, expected, 1, 0, null);
    }

    @Test
    void eventInvocation_retriesFailedInvocationUpToMaximumRetryAttempts() throws Exception {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaService lambdaService = mock(LambdaService.class);
        FunctionEventInvokeConfig config = new FunctionEventInvokeConfig();
        config.setMaximumRetryAttempts(2);
        config.setMaximumEventAgeInSeconds(21600);
        when(lambdaService.findEventInvokeConfig(fn, null)).thenReturn(Optional.of(config));
        when(lambdaService.isLive(fn)).thenReturn(true);

        LambdaExecutorService retryExecutor =
                registerExecutor(new LambdaExecutorService(warmPool, new ObjectMapper(),
                        concurrencyLimiter, router, lambdaService));
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-retry", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        InvokeResult failureResult = new InvokeResult(200, "Unhandled",
                "{\"errorMessage\":\"fails\"}".getBytes(), null, "req-retry");
        doAnswer(invocation -> {
            PendingInvocation pendingInvocation = invocation.getArgument(0);
            pendingInvocation.getResultFuture().complete(failureResult);
            return pendingInvocation.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        CountDownLatch routed = new CountDownLatch(1);
        doAnswer(invocation -> {
            routed.countDown();
            return null;
        }).when(router).route(any(), any(), any(), anyInt(), anyInt(), any());

        byte[] payload = "{}".getBytes();
        InvokeResult result = retryExecutor.invoke(fn, payload, InvocationType.Event);

        assertEquals(202, result.getStatusCode());
        assertTrue(routed.await(5, TimeUnit.SECONDS), "destination routing never ran");
        verify(rtas, times(3)).enqueue(any(PendingInvocation.class));
        verify(router).route(eq(fn), eq(payload), eq(failureResult), eq(3), eq(0), isNull());
    }

    @Test
    void eventInvocation_stopsRetryingWhenMaxEventAgeExceeded() throws Exception {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaService lambdaService = mock(LambdaService.class);
        FunctionEventInvokeConfig config = new FunctionEventInvokeConfig();
        config.setMaximumRetryAttempts(2);
        config.setMaximumEventAgeInSeconds(0);
        when(lambdaService.findEventInvokeConfig(fn, null)).thenReturn(Optional.of(config));
        when(lambdaService.isLive(fn)).thenReturn(true);
        LambdaExecutorService retryExecutor =
                registerExecutor(new LambdaExecutorService(warmPool, new ObjectMapper(),
                        concurrencyLimiter, router, lambdaService));

        CountDownLatch routed = new CountDownLatch(1);
        doAnswer(invocation -> {
            routed.countDown();
            return null;
        }).when(router).route(any(), any(), any(), anyInt(), anyInt(), any());

        byte[] payload = "{}".getBytes();
        InvokeResult result = retryExecutor.invoke(fn, payload, InvocationType.Event);

        assertEquals(202, result.getStatusCode());
        assertTrue(routed.await(5, TimeUnit.SECONDS), "destination routing never ran");
        verify(warmPool, never()).acquire(any());
        ArgumentCaptor<InvokeResult> resultCaptor = ArgumentCaptor.forClass(InvokeResult.class);
        verify(router).route(eq(fn), eq(payload), resultCaptor.capture(), eq(0), eq(0), isNull());
        assertTrue(new String(resultCaptor.getValue().getPayload()).contains("EventAgeExceeded"),
                "an event that expires before execution needs an expiration result");
    }

    @Test
    void eventInvocation_preservesLastAttemptWhenAgeExpiresBetweenAttempts() throws Exception {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaService lambdaService = mock(LambdaService.class);
        FunctionEventInvokeConfig config = new FunctionEventInvokeConfig();
        config.setMaximumRetryAttempts(2);
        config.setMaximumEventAgeInSeconds(1);
        when(lambdaService.findEventInvokeConfig(fn, null)).thenReturn(Optional.of(config));
        when(lambdaService.isLive(fn)).thenReturn(true);
        MutableClock clock = new MutableClock();
        LambdaExecutorService retryExecutor = registerExecutor(new LambdaExecutorService(warmPool, new ObjectMapper(),
                concurrencyLimiter, router, lambdaService, clock, Duration.ZERO));

        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-age-after-failure", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        InvokeResult failedAttempt = new InvokeResult(200, "Unhandled",
                "{\"errorMessage\":\"fails\"}".getBytes(), null, "req-age");
        doAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(1));
            PendingInvocation pendingInvocation = invocation.getArgument(0);
            pendingInvocation.getResultFuture().complete(failedAttempt);
            return pendingInvocation.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        CountDownLatch routed = new CountDownLatch(1);
        doAnswer(invocation -> {
            routed.countDown();
            return null;
        }).when(router).route(any(), any(), any(), anyInt(), anyInt(), any());

        byte[] payload = "{}".getBytes();
        InvokeResult result = retryExecutor.invoke(fn, payload, InvocationType.Event);

        assertEquals(202, result.getStatusCode());
        assertTrue(routed.await(5, TimeUnit.SECONDS), "destination routing never ran");
        ArgumentCaptor<InvokeResult> resultCaptor = ArgumentCaptor.forClass(InvokeResult.class);
        verify(router).route(eq(fn), eq(payload), resultCaptor.capture(), eq(1), eq(0), isNull());
        assertSame(failedAttempt, resultCaptor.getValue(),
                "age expiration should preserve the last invocation result");
    }

    @Test
    void eventInvocation_waitsLongerBeforeEachRetry() {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaExecutorService retryExecutor =
                retryingExecutor(router, eventInvokeConfig(2, 21600), RETRY_DELAY);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        InvokeResult failure = failedAttempt("req-wait");
        List<Instant> enqueuedAt = failEveryAttempt(rtas, failure);
        Instant start = time.now();

        byte[] payload = "{}".getBytes();
        retryExecutor.invoke(fn, payload, InvocationType.Event);
        time.runDue();
        assertEquals(List.of(start), enqueuedAt);

        time.advance(RETRY_DELAY.minus(TICK));
        assertEquals(1, enqueuedAt.size(), "the first retry waits the whole delay");
        time.advance(TICK);
        assertEquals(2, enqueuedAt.size());

        time.advance(RETRY_DELAY.multipliedBy(2).minus(TICK));
        assertEquals(2, enqueuedAt.size(), "the second retry waits twice the delay");
        verify(router, never()).route(any(), any(), any(), anyInt(), anyInt(), any());
        time.advance(TICK);

        assertEquals(List.of(start, start.plus(RETRY_DELAY), start.plus(RETRY_DELAY.multipliedBy(3))), enqueuedAt);
        verify(router).route(eq(fn), eq(payload), eq(failure), eq(3), eq(0), isNull());
    }

    @Test
    void eventInvocation_releasesTheConcurrencyPermitBetweenAttempts() {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaExecutorService retryExecutor = retryingExecutor(router, eventInvokeConfig(2, 21600), RETRY_DELAY);
        LambdaConcurrencyLimiter.Permit permit = mock(LambdaConcurrencyLimiter.Permit.class);
        when(concurrencyLimiter.acquire(fn)).thenReturn(permit);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        failEveryAttempt(rtas, failedAttempt("req-permit"));

        retryExecutor.invoke(fn, "{}".getBytes(), InvocationType.Event);
        time.advance(RETRY_DELAY.multipliedBy(3));

        verify(router).route(any(), any(), any(), eq(3), anyInt(), any());
        InOrder inOrder = inOrder(concurrencyLimiter, permit);
        for (int attempt = 1; attempt <= 3; attempt++) {
            inOrder.verify(concurrencyLimiter).acquire(fn);
            inOrder.verify(permit).close();
        }
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void eventInvocation_throttledRetryPollsAgainWithoutSpendingAnAttempt() {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaExecutorService retryExecutor = retryingExecutor(router, eventInvokeConfig(1, 21600), RETRY_DELAY);
        LambdaConcurrencyLimiter.Permit permit = mock(LambdaConcurrencyLimiter.Permit.class);
        when(concurrencyLimiter.acquire(fn))
                .thenReturn(permit)
                .thenThrow(new AwsException("TooManyRequestsException", "Rate exceeded", 429))
                .thenReturn(permit);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        List<Instant> enqueuedAt = failEveryAttempt(rtas, failedAttempt("req-throttled"));

        byte[] payload = "{}".getBytes();
        retryExecutor.invoke(fn, payload, InvocationType.Event);
        // The first attempt fails and the retry, a minute later, finds the concurrency in use
        time.advance(RETRY_DELAY);
        verify(concurrencyLimiter, times(2)).acquire(fn);

        time.advance(THROTTLED_RETRY_POLL.minus(TICK));
        verify(concurrencyLimiter, times(2)).acquire(fn);
        assertEquals(1, enqueuedAt.size());
        verify(router, never()).route(any(), any(), any(), anyInt(), anyInt(), any());

        time.advance(TICK);
        assertEquals(2, enqueuedAt.size());
        verify(concurrencyLimiter, times(3)).acquire(fn);
        verify(router).route(eq(fn), eq(payload), any(InvokeResult.class), eq(2), eq(0), isNull());
    }

    @Test
    void eventInvocation_expiryDuringTheRetryWaitRoutesTheLastAttempt() {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        Duration maximumEventAge = Duration.ofSeconds(1);
        LambdaExecutorService retryExecutor = retryingExecutor(router,
                eventInvokeConfig(2, (int) maximumEventAge.toSeconds()), RETRY_DELAY);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        InvokeResult failure = failedAttempt("req-expiry");
        List<Instant> enqueuedAt = failEveryAttempt(rtas, failure);

        byte[] payload = "{}".getBytes();
        retryExecutor.invoke(fn, payload, InvocationType.Event);
        time.advance(maximumEventAge.minus(TICK));
        verify(router, never()).route(any(), any(), any(), anyInt(), anyInt(), any());

        // The retry wait ends when the event expires, not after the full delay
        time.advance(TICK);
        verify(router).route(eq(fn), eq(payload), eq(failure), eq(1), eq(0), isNull());

        time.advance(RETRY_DELAY);
        assertEquals(1, enqueuedAt.size());
        assertEquals(0, time.pendingTasks());
    }

    @Test
    void eventInvocation_resetDropsAPendingRetry() {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaExecutorService retryExecutor =
                retryingExecutor(router, eventInvokeConfig(2, 21600), RETRY_DELAY);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-reset", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        InvokeResult failure = failedAttempt("req-reset");
        doAnswer(invocation -> {
            retryExecutor.clear();
            PendingInvocation pendingInvocation = invocation.getArgument(0);
            pendingInvocation.getResultFuture().complete(failure);
            return pendingInvocation.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        retryExecutor.invoke(fn, "{}".getBytes(), InvocationType.Event);
        time.runDue();
        assertEquals(1, time.pendingTasks(), "the retry is waiting for its delay");

        time.advance(RETRY_DELAY);

        assertEquals(0, time.pendingTasks());
        verify(router, never()).route(any(), any(), any(), anyInt(), anyInt(), any());
        verify(rtas, times(1)).enqueue(any(PendingInvocation.class));
    }

    @Test
    void eventInvocation_resetDropsAPendingRetryBeforeStorageIsWiped() {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaExecutorService retryExecutor =
                retryingExecutor(router, eventInvokeConfig(2, 21600), RETRY_DELAY);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-before-reset", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        InvokeResult failure = failedAttempt("req-before-reset");
        doAnswer(invocation -> {
            retryExecutor.beforeReset();
            PendingInvocation pendingInvocation = invocation.getArgument(0);
            pendingInvocation.getResultFuture().complete(failure);
            return pendingInvocation.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        retryExecutor.invoke(fn, "{}".getBytes(), InvocationType.Event);
        time.runDue();
        assertEquals(1, time.pendingTasks(), "the retry is waiting for its delay");

        time.advance(RETRY_DELAY);

        assertEquals(0, time.pendingTasks());
        verify(router, never()).route(any(), any(), any(), anyInt(), anyInt(), any());
        verify(rtas, times(1)).enqueue(any(PendingInvocation.class));
    }

    @Test
    void eventInvocation_dropsThePendingRetryOfADeletedFunction() {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaExecutorService retryExecutor = retryingExecutor(router, eventInvokeConfig(2, 21600), RETRY_DELAY);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-deleted", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        InvokeResult failure = failedAttempt("req-deleted");
        doAnswer(invocation -> {
            retryExecutor.dropPending(fn);
            PendingInvocation pendingInvocation = invocation.getArgument(0);
            pendingInvocation.getResultFuture().complete(failure);
            return pendingInvocation.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        retryExecutor.invoke(fn, "{}".getBytes(), InvocationType.Event);
        time.runDue();
        assertEquals(1, time.pendingTasks(), "the retry is waiting for its delay");

        time.advance(RETRY_DELAY);

        assertEquals(0, time.pendingTasks());
        verify(router, never()).route(any(), any(), any(), anyInt(), anyInt(), any());
        verify(rtas, times(1)).enqueue(any(PendingInvocation.class));
    }

    @Test
    void eventInvocation_dropsTheExpiringEventOfADeletedFunction() {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        Duration maximumEventAge = Duration.ofSeconds(1);
        LambdaExecutorService retryExecutor = retryingExecutor(router,
                eventInvokeConfig(2, (int) maximumEventAge.toSeconds()), RETRY_DELAY);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-deleted-expiry", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        InvokeResult failure = failedAttempt("req-deleted-expiry");
        doAnswer(invocation -> {
            retryExecutor.dropPending(fn);
            PendingInvocation pendingInvocation = invocation.getArgument(0);
            pendingInvocation.getResultFuture().complete(failure);
            return pendingInvocation.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        retryExecutor.invoke(fn, "{}".getBytes(), InvocationType.Event);
        time.runDue();
        assertEquals(1, time.pendingTasks(), "the event is waiting to expire");

        time.advance(maximumEventAge);

        assertEquals(0, time.pendingTasks());
        verify(router, never()).route(any(), any(), any(), anyInt(), anyInt(), any());
        verify(rtas, times(1)).enqueue(any(PendingInvocation.class));
    }

    @Test
    void eventInvocation_ofAFunctionDeletedAfterItWasResolved_isDroppedWithoutAnAttempt() {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaService lambdaService = eventInvokeConfig(2, 21600);
        LambdaExecutorService retryExecutor = retryingExecutor(router, lambdaService, Duration.ZERO);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        failEveryAttempt(rtas, failedAttempt("req-deleted-before-invoke"));
        // DeleteFunction ran between the caller resolving fn and this invoke: the function has left
        // the store and its deletion count has already moved.
        when(lambdaService.isLive(fn)).thenReturn(false);
        retryExecutor.dropPending(fn);

        InvokeResult result = retryExecutor.invoke(fn, "{}".getBytes(), InvocationType.Event);

        assertEquals(202, result.getStatusCode());
        assertEquals(0, time.pendingTasks(), "no attempt may be handed to the executor");
        verify(warmPool, never()).acquire(any());
        verify(router, never()).route(any(), any(), any(), anyInt(), anyInt(), any());
        verify(concurrencyLimiter, never()).acquire(any());
    }

    @Test
    void exceptionDuringInvocation_destroysHandle_doesNotRelease() {
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-exc", "test-fn", rtas, ContainerState.WARM);

        when(warmPool.acquire(any())).thenReturn(handle);
        doAnswer(inv -> {
            PendingInvocation pi = inv.getArgument(0);
            pi.getResultFuture().completeExceptionally(new RuntimeException("runtime crash"));
            return pi.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        InvokeResult result = executor.invoke(fn, "{}".getBytes(), InvocationType.RequestResponse);

        verify(warmPool).destroyHandle(handle);
        verify(warmPool, never()).release(handle);
        assertEquals(200, result.getStatusCode());
        assertEquals("Unhandled", result.getFunctionError());
        assertTrue(new String(result.getPayload()).contains("InvocationError"));
    }

    private LambdaExecutorService retryingExecutor(AsyncInvokeDestinationRouter router, LambdaService lambdaService,
                                                   Duration asyncRetryDelay) {
        return timedExecutor(router, lambdaService, asyncRetryDelay);
    }

    /**
     * An executor on the test's timeline: an Event invocation's first attempt waits for the timeline to
     * run it, and timeouts and retry delays pass only as the timeline advances.
     */
    private LambdaExecutorService timedExecutor(AsyncInvokeDestinationRouter router, LambdaService lambdaService,
                                                Duration asyncRetryDelay) {
        return registerExecutor(new LambdaExecutorService(warmPool, new ObjectMapper(), concurrencyLimiter, router,
                lambdaService, time.clock(), asyncRetryDelay, time.newScheduler(), new TimelineInvocationTimer(time)));
    }

    private Duration elapsed() {
        return Duration.between(TestTime.DEFAULT_START, time.now());
    }

    /** Has the runtime take each invocation at once and answer it {@code delay} later. */
    private void dispatchAndCompleteAfter(RuntimeApiServer rtas, Duration delay, String requestId) {
        doAnswer(inv -> {
            PendingInvocation pi = inv.getArgument(0);
            pi.prepareForDispatch();
            pi.markDispatched();
            time.scheduler().schedule(() -> pi.getResultFuture().complete(
                    new InvokeResult(200, null, "{\"ok\":true}".getBytes(), null, requestId)),
                    delay.toMillis(), TimeUnit.MILLISECONDS);
            return pi.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));
    }

    private LambdaService eventInvokeConfig(int maximumRetryAttempts, int maximumEventAgeInSeconds) {
        FunctionEventInvokeConfig config = new FunctionEventInvokeConfig();
        config.setMaximumRetryAttempts(maximumRetryAttempts);
        config.setMaximumEventAgeInSeconds(maximumEventAgeInSeconds);
        LambdaService lambdaService = mock(LambdaService.class);
        when(lambdaService.findEventInvokeConfig(fn, null)).thenReturn(Optional.of(config));
        when(lambdaService.isLive(fn)).thenReturn(true);
        return lambdaService;
    }

    private static InvokeResult failedAttempt(String requestId) {
        return new InvokeResult(200, "Unhandled", "{\"errorMessage\":\"fails\"}".getBytes(), null, requestId);
    }

    /** Makes every attempt fail with {@code failure}; the list records when each one reached the runtime. */
    private List<Instant> failEveryAttempt(RuntimeApiServer rtas, InvokeResult failure) {
        ContainerHandle handle = new ContainerHandle("cid-failing", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        List<Instant> enqueuedAt = new ArrayList<>();
        doAnswer(invocation -> {
            enqueuedAt.add(time.now());
            PendingInvocation pendingInvocation = invocation.getArgument(0);
            pendingInvocation.getResultFuture().complete(failure);
            return pendingInvocation.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));
        return enqueuedAt;
    }
}
