package io.floci.aws.services.ec2;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Counts synchronous service work without holding its monitor across storage or network calls. */
final class Ec2OperationAdmission {

    private final ThreadLocal<Boolean> admitted = new ThreadLocal<>();
    private boolean paused;
    private int active;

    <T> T call(Supplier<T> operation) {
        if (Boolean.TRUE.equals(admitted.get())) {
            return operation.get();
        }
        synchronized (this) {
            if (paused) {
                throw new IllegalStateException("EC2 service admission is paused for reset");
            }
            active++;
        }
        admitted.set(true);
        try {
            return operation.get();
        } finally {
            admitted.remove();
            synchronized (this) {
                active--;
                notifyAll();
            }
        }
    }

    synchronized void pauseAndDrain(Duration timeout) throws InterruptedException {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("EC2 service drain timeout must not be negative");
        }
        if (Boolean.TRUE.equals(admitted.get())) {
            throw new IllegalStateException("An EC2 operation cannot drain itself");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        paused = true;
        while (active != 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("EC2 service operations did not finish before the reset deadline");
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    synchronized void resume() {
        if (active != 0) {
            throw new IllegalStateException("EC2 service operations are still draining for reset");
        }
        paused = false;
    }
}
