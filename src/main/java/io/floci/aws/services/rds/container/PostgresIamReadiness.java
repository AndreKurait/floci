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

    static void await(DockerClient docker, String containerId, String[] command, Semaphore startupSignal) {
        await(containerId, timeout -> {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            ContainerExec.Result result = ContainerExec.run(docker, containerId, command, timeout, stdout, stderr);
            return new ContainerExec.Result(result.exitCode(), stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8) + result.stderr(), result.timedOut());
        }, System::nanoTime, nanos -> startupSignal.tryAcquire(nanos, TimeUnit.NANOSECONDS));
    }

    static void await(String containerId, Probe probe, LongSupplier clock, Waiter waiter) {
        long deadline = clock.getAsLong() + BUDGET_NANOS;
        String lastOutput = "(no output)";
        while (true) {
            checkInterrupted(containerId);
            long remaining = deadline - clock.getAsLong();
            if (remaining <= 0) {
                throw failure(containerId, lastOutput);
            }
            ContainerExec.Result result;
            try {
                result = probe.run(Duration.ofNanos(Math.min(EXEC_NANOS, remaining)));
                lastOutput = result.summary();
            } catch (RuntimeException error) {
                checkInterrupted(containerId);
                // A broken exec connection may leave SQL running. Retrying could
                // overlap role creation, so let launch cleanup stop the container.
                throw new IllegalStateException("Could not initialize PostgreSQL IAM role in " + containerId, error);
            }
            checkInterrupted(containerId);
            if (result.timedOut()) {
                // The exec may still be running; launch cleanup must stop it rather than overlap another SQL command.
                throw failure(containerId, lastOutput);
            }
            if (result.exitCode() < 0) {
                throw new IllegalStateException("Unknown PostgreSQL IAM initialization outcome in "
                        + containerId + ": " + lastOutput);
            }
            remaining = deadline - clock.getAsLong();
            if (remaining <= 0) {
                throw failure(containerId, lastOutput);
            }
            if (result.exitCode() == 0) {
                return;
            }
            try {
                waiter.await(Math.min(POLL_NANOS, remaining));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted initializing PostgreSQL IAM role in " + containerId, error);
            }
        }
    }

    private static void checkInterrupted(String containerId) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Interrupted initializing PostgreSQL IAM role in " + containerId);
        }
    }

    private static IllegalStateException failure(String containerId, String output) {
        return new IllegalStateException("Timed out initializing PostgreSQL IAM role in " + containerId + ": " + output);
    }
}
