package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LambdaProvisionedConcurrencyQuotaTest {
    private static final String ARN = "arn:aws:lambda:us-east-1:111111111111:function:first";
    private static final String OTHER = "arn:aws:lambda:us-east-1:111111111111:function:second";

    @Test
    void provisioningAndReservedConcurrencyShareTheAccountCommitment() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(10, 2);
        limiter.validateAndSetProvisioned(ARN, "1", 3);
        assertEquals(7, limiter.unreservedCapacity("111111111111", "us-east-1"));
        assertThrows(AwsException.class, () -> limiter.validateAndSetReserved(ARN, 2));
        assertEquals(7, limiter.unreservedCapacity("111111111111", "us-east-1"));
        limiter.validateAndSetReserved(ARN, 4);
        assertEquals(6, limiter.unreservedCapacity("111111111111", "us-east-1"));
        limiter.clearReserved(ARN);
        assertEquals(7, limiter.unreservedCapacity("111111111111", "us-east-1"));
        limiter.validateAndSetProvisioned(ARN, "1", 2);
        assertEquals(8, limiter.unreservedCapacity("111111111111", "us-east-1"));
        limiter.clearProvisioned(ARN, "1");
        assertEquals(10, limiter.unreservedCapacity("111111111111", "us-east-1"));
    }

    @Test
    void overcommittedRestoredStateAllowsReductionsAndRollbackDoesNotOverwriteNewerWrites() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(5, 2);
        limiter.setProvisioned(ARN, "1", 6);
        limiter.validateAndSetProvisioned(ARN, "1", 4);
        limiter.validateAndSetProvisioned(ARN, "1", 3);
        limiter.rollbackProvisionedIfExpected(ARN, "1", 4, 6);
        assertEquals(2, limiter.unreservedCapacity("111111111111", "us-east-1"));
        limiter.clear();
        assertEquals(5, limiter.unreservedCapacity("111111111111", "us-east-1"));
    }

    @Test
    void independentAccountsAndRegionsDoNotShareProvisionedBudgets() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(5, 2);
        limiter.validateAndSetProvisioned(ARN, "1", 3);
        limiter.validateAndSetProvisioned(ARN.replace("111111111111", "222222222222"), "1", 3);
        limiter.validateAndSetProvisioned(ARN.replace("us-east-1", "us-east-2"), "1", 3);
        assertThrows(AwsException.class, () -> limiter.validateAndSetReserved(OTHER, 1));
        limiter.clearProvisioned(ARN.replace("111111111111", "222222222222"), "1");
        assertEquals(2, limiter.unreservedCapacity("111111111111", "us-east-1"));
        assertEquals(5, limiter.unreservedCapacity("222222222222", "us-east-1"));
        assertEquals(2, limiter.unreservedCapacity("111111111111", "us-east-2"));
    }

    @Test
    void provisionedPermitsDoNotDoubleChargeUnreservedInvocationsAndSpilloverRemainsBounded() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(4, 0);
        limiter.validateAndSetProvisioned(ARN, "1", 2);
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionArn(ARN);
        List<LambdaConcurrencyLimiter.Permit> held = new ArrayList<>();
        try {
            held.add(limiter.acquireProvisioned(fn));
            held.add(limiter.acquireProvisioned(fn));
            assertEquals(2, limiter.availableUnreserved("111111111111", "us-east-1"));
            held.add(limiter.acquire(fn));
            held.add(limiter.acquire(fn));
            assertThrows(AwsException.class, () -> limiter.acquire(fn));
            assertEquals(0, limiter.availableUnreserved("111111111111", "us-east-1"));
        } finally {
            held.forEach(LambdaConcurrencyLimiter.Permit::close);
        }
        assertEquals(2, limiter.availableUnreserved("111111111111", "us-east-1"));
    }

    @Test
    void racingProvisionedAndReservedPutsCannotBothSpendTheRemainingBudget() throws Exception {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(5, 2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                try {
                    limiter.validateAndSetProvisioned(ARN, "1", 3);
                    return true;
                } catch (AwsException denied) {
                    return false;
                }
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                try {
                    limiter.validateAndSetReserved(OTHER, 3);
                    return true;
                } catch (AwsException denied) {
                    return false;
                }
            });
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }
        assertEquals(2, limiter.unreservedCapacity("111111111111", "us-east-1"));
    }
}
