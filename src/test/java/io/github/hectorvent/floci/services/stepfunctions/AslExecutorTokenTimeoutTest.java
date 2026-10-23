package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(10)
class AslExecutorTokenTimeoutTest {

    private static final String OWNER = "111111111111";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver resolver = mock(RegionResolver.class);
    private final StepFunctionsService service = new StepFunctionsService(mock(StorageFactory.class),
            resolver, mock(AslExecutor.class), mapper, mock(SfnMockLoader.class),
            mock(StepFunctionsEventPublisher.class));

    @ParameterizedTest
    @ValueSource(strings = {"execution", "task", "heartbeat"})
    void timeoutClosesTokenBeforeCancellingFuture(String clock) throws Exception {
        when(resolver.getAccountId()).thenReturn(OWNER);
        CompletableFuture<JsonNode> future = service.registerPendingToken("expired", OWNER);
        long now = service.lastTaskHeartbeatNanos("expired") + TimeUnit.SECONDS.toNanos(2);
        List<String> callbackResults = new ArrayList<>();
        future.whenComplete((result, error) -> {
            callbackResults.add(Thread.holdsLock(service) ? "locked" : "unlocked");
            callbackResults.add(callbackResult(() -> service.sendTaskSuccess("expired", "{}")));
            callbackResults.add(callbackResult(() -> service.sendTaskFailure("expired", "cause", "error")));
            callbackResults.add(callbackResult(() -> service.sendTaskHeartbeat("expired")));
        });
        long executionDeadline = "execution".equals(clock) ? now : Long.MAX_VALUE;
        long taskDeadline = "task".equals(clock) ? now : Long.MAX_VALUE;
        JsonNode definition = mapper.readTree("heartbeat".equals(clock) ? "{\"HeartbeatSeconds\":1}" : "{}");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> executor(() -> now).awaitToken(future, definition, "expired",
                        executionDeadline, taskDeadline));

        if ("execution".equals(clock)) {
            assertTrue(failure instanceof AslExecutor.ExecutionTimedOutException);
        } else {
            assertTrue(failure instanceof AslExecutor.TaskTimedOutException);
        }
        assertTrue(future.isCancelled());
        assertEquals(List.of("unlocked", "TaskTimedOut", "TaskTimedOut", "TaskTimedOut"), callbackResults);
    }

    @Test
    void callbackClaimedBeforeTimeoutDecisionKeepsItsResult() throws Exception {
        when(resolver.getAccountId()).thenReturn(OWNER);
        CompletableFuture<JsonNode> future = service.registerPendingToken("winner", OWNER);
        AtomicInteger clockReads = new AtomicInteger();
        AslExecutor executor = executor(() -> {
            if (clockReads.incrementAndGet() == 2) {
                service.sendTaskSuccess("winner", "{\"accepted\":true}");
            }
            return 1;
        });

        JsonNode result = executor.awaitToken(future, mapper.readTree("{}"), "winner", Long.MAX_VALUE, 1);

        assertTrue(result.path("accepted").asBoolean());
        assertFalse(future.isCancelled());
    }

    @Test
    void heartbeatBeforeTimeoutDecisionExtendsTheWait() throws Exception {
        when(resolver.getAccountId()).thenReturn(OWNER);
        CompletableFuture<JsonNode> future = service.registerPendingToken("heartbeat", OWNER);
        long now = service.lastTaskHeartbeatNanos("heartbeat") + TimeUnit.SECONDS.toNanos(1);
        AtomicInteger clockReads = new AtomicInteger();
        AslExecutor executor = executor(() -> {
            int read = clockReads.incrementAndGet();
            if (read == 2) {
                service.sendTaskHeartbeat("heartbeat");
            } else if (read == 3) {
                service.sendTaskSuccess("heartbeat", "{\"accepted\":true}");
            }
            return now;
        });

        JsonNode result = executor.awaitToken(future, mapper.readTree("{\"HeartbeatSeconds\":1}"),
                "heartbeat", Long.MAX_VALUE, Long.MAX_VALUE);

        assertTrue(result.path("accepted").asBoolean());
        assertFalse(future.isCancelled());
    }

    private String callbackResult(Runnable callback) {
        try {
            callback.run();
            return "accepted";
        } catch (AwsException error) {
            return error.getErrorCode();
        }
    }

    @SuppressWarnings("unchecked")
    private AslExecutor executor(LongSupplier clock) {
        Instance<StepFunctionsService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(service);
        return AslExecutorFixture.builder().objectMapper(mapper).sfnService(instance).nanoTime(clock).build();
    }
}
