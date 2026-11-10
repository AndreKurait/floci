package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rotations used to run on an unbounded thread pool, one thread per rotation that could not
 * reuse an idle one. They now share a fixed pool.
 */
class SecretsManagerRotationBoundsTest {

    private static final String REGION = "us-east-1";
    private static final String LAMBDA_ARN = "arn:aws:lambda:us-east-1:000000000000:function:rotate";

    @Test
    void rotationThreadCountDoesNotScaleWithConcurrentRotations() throws Exception {
        LambdaService mockLambda = Mockito.mock(LambdaService.class);
        AtomicInteger concurrentInvocations = new AtomicInteger();
        AtomicInteger peakConcurrentInvocations = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        Set<Thread> invokers = ConcurrentHashMap.newKeySet();
        Set<Thread> alreadyRunning = Thread.getAllStackTraces().keySet();

        Mockito.when(mockLambda.invoke(Mockito.anyString(), Mockito.anyString(),
                        Mockito.any(byte[].class), Mockito.any()))
                .thenAnswer(invocation -> {
                    invokers.add(Thread.currentThread());
                    int inFlight = concurrentInvocations.incrementAndGet();
                    peakConcurrentInvocations.updateAndGet(peak -> Math.max(peak, inFlight));
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } finally {
                        concurrentInvocations.decrementAndGet();
                    }
                    InvokeResult ok = new InvokeResult();
                    ok.setStatusCode(200);
                    return ok;
                });

        SecretsManagerService svc = new SecretsManagerService(new InMemoryStorage<String, Secret>(), 30,
                new RegionResolver(REGION, "000000000000"), mockLambda, new ObjectMapper());

        int secretCount = 300;
        for (int i = 0; i < secretCount; i++) {
            String name = "thread-bound-" + i;
            svc.createSecret(name, "v1", null, null, null, null, REGION);
            svc.rotateSecret(name, null, LAMBDA_ARN, null, true, REGION);
        }

        // An executor starts its threads as work is handed to it, so every rotation thread this
        // service will ever have exists by now. Once each of them is held inside its first Lambda
        // call, no thread is left that could raise the peak.
        List<Thread> rotationThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().matches("secretsmanager-rotation-\\d+")
                        && !alreadyRunning.contains(thread))
                .toList();
        assertFalse(rotationThreads.isEmpty(), "rotations must run on the service's named rotation threads");
        await("every rotation thread held in its Lambda call").atMost(Duration.ofSeconds(5))
                .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(1))
                .until(() -> concurrentInvocations.get() >= rotationThreads.size());

        int peak = peakConcurrentInvocations.get();
        release.countDown();
        svc.shutdown();

        assertTrue(rotationThreads.containsAll(invokers), "every rotation must run on a rotation thread");
        assertTrue(peak <= SecretsManagerService.ROTATION_EXECUTOR_POOL_SIZE,
                "rotation ran " + peak + " secrets concurrently out of " + secretCount
                        + " submitted; the rotation executor must be bounded by its pool size of "
                        + SecretsManagerService.ROTATION_EXECUTOR_POOL_SIZE + ", not one thread per secret");
    }
}
