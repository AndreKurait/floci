package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;

import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Holds a newly started container out of both Lambda pools until its configured probe succeeds. */
final class LambdaContainerReadiness {

    private LambdaContainerReadiness() {
    }

    @FunctionalInterface
    interface Probe {
        ContainerExec.Result run(String[] arguments, int remainingSeconds);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep() throws InterruptedException;
    }

    static void await(DockerClient docker, String container, Optional<String> configured, int timeoutSeconds) {
        await(configured, timeoutSeconds,
                (arguments, remaining) -> ContainerExec.run(docker, container, arguments, remaining,
                        OutputStream.nullOutputStream(), OutputStream.nullOutputStream()),
                System::nanoTime, () -> Thread.sleep(250));
    }

    static void await(Optional<String> configured, int timeoutSeconds, Probe probe,
                      LongSupplier clock, Sleeper sleeper) {
        String command = configured == null ? "" : configured.orElse("");
        if (command.isBlank()) {
            return;
        }
        List<String> arguments = LambdaDockerFlags.tokenize(command);
        if (arguments.isEmpty() || timeoutSeconds < 1 || timeoutSeconds > 900) {
            throw new IllegalArgumentException("Lambda container readiness requires a command and a 1–900 second budget");
        }
        long deadline = clock.getAsLong() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Lambda container readiness was interrupted");
            }
            long remaining = TimeUnit.NANOSECONDS.toSeconds(deadline - clock.getAsLong());
            if (remaining < 1) {
                throw new IllegalStateException("Lambda container readiness timed out");
            }
            ContainerExec.Result result = probe.run(arguments.toArray(String[]::new), (int) remaining);
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Lambda container readiness was interrupted");
            }
            if (result.timedOut() || clock.getAsLong() >= deadline) {
                // A timed-out exec can still be running: fail launch so its existing cleanup stops the container.
                throw new IllegalStateException("Lambda container readiness timed out");
            }
            if (result.exitCode() == 0) {
                return;
            }
            if (result.exitCode() < 0 || result.exitCode() == 126 || result.exitCode() == 127) {
                throw new IllegalStateException("Lambda container readiness command could not execute");
            }
            try {
                sleeper.sleep();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Lambda container readiness was interrupted", error);
            }
        }
    }
}
