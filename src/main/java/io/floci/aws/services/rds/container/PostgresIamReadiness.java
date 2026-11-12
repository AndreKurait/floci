package io.floci.aws.services.rds.container;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.ContainerExec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Waits for successful IAM-role SQL against PostgreSQL's final TCP listener. */
final class PostgresIamReadiness {
    private static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final long EXEC_NANOS = TimeUnit.SECONDS.toNanos(5);
    // An exec given less than this cannot finish; it would only replace the last psql error with a timeout.
    private static final long MIN_EXEC_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long POLL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private PostgresIamReadiness() {
    }

    @FunctionalInterface
    interface Probe {
        ContainerExec.Result run(Duration timeout);
    }

    @FunctionalInterface
    interface Waiter {
        void await(long nanos) throws InterruptedException;
    }

    static void await(DockerClient docker, String containerName, String containerId, String[] command,
                      Semaphore startupSignal) {
        await(containerName, timeout -> {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            ContainerExec.Result result = ContainerExec.run(docker, containerId, command, timeout, stdout, stderr);
            return new ContainerExec.Result(result.exitCode(), stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8) + result.stderr(), result.timedOut());
        }, System::nanoTime, nanos -> startupSignal.tryAcquire(nanos, TimeUnit.NANOSECONDS));
    }

    static void await(String containerName, Probe probe, LongSupplier clock, Waiter waiter) {
        long deadline = clock.getAsLong() + BUDGET_NANOS;
        String lastOutput = "(no output)";
        int execTimeouts = 0;
        while (true) {
            checkInterrupted(containerName);
            long remaining = deadline - clock.getAsLong();
            if (remaining < MIN_EXEC_NANOS) {
                throw failure(containerName, lastOutput, execTimeouts);
            }
            ContainerExec.Result result;
            try {
                result = probe.run(Duration.ofNanos(Math.min(EXEC_NANOS, remaining)));
            } catch (RuntimeException error) {
                checkInterrupted(containerName);
                // An exec that cannot be started or read usually means the container or Docker is
                // gone, which waiting does not fix. Fail now so launch cleanup removes the container.
                throw new IllegalStateException("Could not initialize PostgreSQL IAM role in " + containerName, error);
            }
            checkInterrupted(containerName);
            if (result.timedOut()) {
                // Usually a slow host, not hung SQL. Retrying is safe because the SQL is idempotent:
                // if the abandoned exec still creates the role, the retry finds it and exits 0.
                execTimeouts++;
            } else if (result.exitCode() == 0) {
                // A completed success stands even when exec overhead ran past the deadline.
                return;
            } else {
                // Covers a missing exit status (-1) too: the exec has ended, so it is retried like any failure.
                lastOutput = result.summary();
            }
            remaining = deadline - clock.getAsLong();
            if (remaining < MIN_EXEC_NANOS) {
                throw failure(containerName, lastOutput, execTimeouts);
            }
            try {
                waiter.await(Math.min(POLL_NANOS, remaining));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted initializing PostgreSQL IAM role in " + containerName,
                        error);
            }
        }
    }

    private static void checkInterrupted(String containerName) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Interrupted initializing PostgreSQL IAM role in " + containerName);
        }
    }

    private static IllegalStateException failure(String containerName, String lastOutput, int execTimeouts) {
        // Exec timeouts point at a slow host rather than at PostgreSQL never becoming ready.
        String detail = execTimeouts == 0 ? lastOutput
                : execTimeouts + " exec(s) did not finish in time; last output: " + lastOutput;
        return new IllegalStateException("Timed out initializing PostgreSQL IAM role in " + containerName + ": "
                + detail);
    }
}
