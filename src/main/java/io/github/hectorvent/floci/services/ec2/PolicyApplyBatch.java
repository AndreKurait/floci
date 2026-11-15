package io.github.hectorvent.floci.services.ec2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded Docker operations that must all finish before failure handling can quarantine helpers. */
final class PolicyApplyBatch {
    private static final int MAX_WORKERS = 4;

    private PolicyApplyBatch() {}

    static void run(List<Runnable> operations) {
        AtomicInteger next = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>(MAX_WORKERS);
        try {
            for (int index = 0; index < Math.min(MAX_WORKERS, operations.size()); index++) {
                Thread worker = Thread.ofVirtual().name("security-group-policy-apply").unstarted(() -> {
                    while (failure.get() == null) {
                        int item = next.getAndIncrement();
                        if (item >= operations.size()) {
                            return;
                        }
                        try {
                            operations.get(item).run();
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                        }
                    }
                });
                workers.add(worker);
                worker.start();
            }
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        }
        boolean interrupted = false;
        for (Thread worker : workers) {
            boolean joined = false;
            while (!joined) {
                try {
                    worker.join();
                    joined = true;
                } catch (InterruptedException error) {
                    interrupted = true;
                    failure.compareAndSet(null, error);
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        Throwable error = failure.get();
        if (error instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (error != null) {
            throw new IllegalStateException("Could not apply security-group policies", error);
        }
    }
}
