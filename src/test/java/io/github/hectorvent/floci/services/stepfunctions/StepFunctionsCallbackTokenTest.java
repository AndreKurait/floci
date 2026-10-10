package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepFunctionsCallbackTokenTest {
    private static final String OWNER = "111111111111";
    private final RegionResolver resolver = mock(RegionResolver.class);
    private final StepFunctionsService service = new StepFunctionsService(mock(StorageFactory.class),
            resolver, mock(AslExecutor.class), new ObjectMapper(), mock(SfnMockLoader.class));

    @Test
    void foreignCallbacksCannotClaimOrRefreshAnOwnedToken() throws Exception {
        CompletableFuture<JsonNode> future = service.registerPendingToken("owned", OWNER);
        long heartbeat = service.lastTaskHeartbeatNanos("owned");
        when(resolver.getAccountId()).thenReturn("222222222222");
        refuses("InvalidToken", () -> service.sendTaskSuccess("owned", "{}"));
        refuses("InvalidToken", () -> service.sendTaskFailure("owned", "cause", "error"));
        refuses("InvalidToken", () -> service.sendTaskHeartbeat("owned"));
        assertFalse(future.isDone());
        assertEquals(heartbeat, service.lastTaskHeartbeatNanos("owned"));
        when(resolver.getAccountId()).thenReturn(OWNER);
        service.sendTaskHeartbeat("owned");
        assertTrue(service.lastTaskHeartbeatNanos("owned") >= heartbeat);
        service.sendTaskSuccess("owned", "{\"first\":true}");
        assertTrue(future.get(1, TimeUnit.SECONDS).path("first").asBoolean());
        refuses("TaskTimedOut", () -> service.sendTaskSuccess("owned", "{}"));
        refuses("TaskTimedOut", () -> service.sendTaskFailure("owned", "cause", "error"));
        refuses("TaskTimedOut", () -> service.sendTaskHeartbeat("owned"));
        when(resolver.getAccountId()).thenReturn("222222222222");
        refuses("InvalidToken", () -> service.sendTaskHeartbeat("owned"));
    }

    @Test
    void failureAndDiscardCloseTokensAndUnknownTokensRefuse() {
        when(resolver.getAccountId()).thenReturn(OWNER);
        CompletableFuture<JsonNode> failed = service.registerPendingToken("failed", OWNER);
        service.sendTaskFailure("failed", "cause", "error");
        assertTrue(failed.isCompletedExceptionally());
        refuses("TaskTimedOut", () -> service.sendTaskSuccess("failed", "{}"));
        service.registerPendingToken("expired", OWNER);
        service.discardPendingToken("expired");
        refuses("TaskTimedOut", () -> service.sendTaskHeartbeat("expired"));
        refuses("InvalidToken", () -> service.sendTaskHeartbeat(null));
        refuses("InvalidToken", () -> service.sendTaskSuccess("unknown", "{}"));
        refuses("InvalidToken", () -> service.sendTaskFailure("unknown", "cause", "error"));
    }

    @Test
    void racingCallbacksCompleteExactlyOnce() throws Exception {
        when(resolver.getAccountId()).thenReturn(OWNER);
        CompletableFuture<JsonNode> future = service.registerPendingToken("race", OWNER);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        future.whenComplete((value, error) -> completions.incrementAndGet());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Runnable callback = () -> {
                ready.countDown();
                try {
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    service.sendTaskSuccess("race", "{\"once\":true}");
                    accepted.incrementAndGet();
                } catch (AwsException error) {
                    assertEquals("TaskTimedOut", error.getErrorCode());
                    refused.incrementAndGet();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            };
            Future<?> first = executor.submit(callback);
            Future<?> second = executor.submit(callback);
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }
        assertEquals(1, accepted.get());
        assertEquals(1, refused.get());
        assertEquals(1, completions.get());
        assertTrue(future.get().path("once").asBoolean());
    }

    @Test
    void closedTokenClassificationIsBoundedAndClearForgetsIt() {
        when(resolver.getAccountId()).thenReturn(OWNER);
        for (int index = 0; index <= 1024; index++) {
            String token = "closed-" + index;
            service.registerPendingToken(token, OWNER);
            service.discardPendingToken(token);
        }
        refuses("InvalidToken", () -> service.sendTaskHeartbeat("closed-0"));
        refuses("TaskTimedOut", () -> service.sendTaskHeartbeat("closed-1024"));
        service.clear();
        refuses("InvalidToken", () -> service.sendTaskHeartbeat("closed-1024"));
    }

    private static void refuses(String code, Runnable callback) {
        AwsException error = assertThrows(AwsException.class, callback::run);
        assertEquals(code, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }
}
