package io.floci.aws.services.batch;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.batch.model.BatchJob;
import io.floci.aws.services.batch.model.BatchNodeExecution;
import io.floci.aws.services.batch.model.BatchRunResult;
import io.floci.aws.services.batch.model.BatchTimeout;
import io.floci.aws.testing.TestTime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchDockerRunnerTest {

    @Test
    void resolveEndpointHostnameUsesHostGatewayWhenFlociRunsNatively() {
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(false);

        assertEquals("host.docker.internal", runner(config(Optional.empty()), detector).resolveEndpointHostname());
    }

    @Test
    void resolveEndpointHostnameUsesConfiguredHostnameWhenFlociRunsInContainer() {
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(true);

        assertEquals("floci.internal", runner(config(Optional.of("floci.internal")), detector).resolveEndpointHostname());
    }

    @Test
    void resolveEndpointHostnameUsesEmbeddedDnsSuffixWhenContainerizedWithoutHostname() {
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(true);

        assertEquals(EmbeddedDnsServer.DEFAULT_SUFFIX, runner(config(Optional.empty()), detector).resolveEndpointHostname());
    }

    @Test
    void runLabelsContainerWithResourceIdentity() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.dockerNetwork()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        when(config.hostname()).thenReturn(Optional.empty());

        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(false);

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));
        DockerClient dockerClient =
                mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getRunning()).thenReturn(false);
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getExitCodeLong()).thenReturn(0L);

        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));

        BatchDockerRunner runner = new BatchDockerRunner(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), config, detector);

        BatchJob job = new BatchJob();
        job.setJobId("job-1");
        job.setJobName("my-job");
        job.setJobQueueName("my-queue");
        job.setJobDefinitionName("my-def");
        job.setContainerImage("busybox:stable");
        job.setRegion("us-east-1");
        job.setAccountId("000000000000");

        runner.run(job, 1);

        verify(builder).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "batch",
                "io.floci.resource-id", "job-1",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1"));
        // A job's JVMs trust Floci's CA as well.
        verify(builder).withJavaTrustStore();
    }

    @Test
    void stopJobStopsAnInFlightContainer() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.dockerNetwork()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        when(config.hostname()).thenReturn(Optional.empty());

        CountDownLatch containerStarted = new CountDownLatch(1);
        AtomicBoolean stopped = new AtomicBoolean();
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenAnswer(invocation -> {
            containerStarted.countDown();
            return new ContainerLifecycleManager.ContainerInfo("container-id", Map.of());
        });
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getRunning())
                .thenAnswer(invocation -> !stopped.get());
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getExitCodeLong()).thenReturn(137L);
        doAnswer(invocation -> {
            stopped.set(true);
            return null;
        }).when(lifecycleManager).stopAndRemove(eq("container-id"), isNull());

        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        BatchDockerRunner runner = new BatchDockerRunner(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), config, mock(ContainerDetector.class));
        BatchJob job = job();
        AtomicReference<BatchRunResult> result = new AtomicReference<>();

        Thread runThread = Thread.startVirtualThread(() -> result.set(runner.run(job, 1)));
        assertTrue(containerStarted.await(2, TimeUnit.SECONDS));
        runner.requestStop(job.getJobId());
        runner.stopJob(job.getJobId());
        runThread.join(2000);

        assertFalse(runThread.isAlive());
        assertEquals(137, result.get().exitCode());
        verify(lifecycleManager).stopAndRemove("container-id", null);
    }

    @Test
    void stopJobAfterRequestIsClearedDoesNotRecreateStopMarker() {
        EmulatorConfig config = config(Optional.empty());
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.dockerNetwork()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getRunning()).thenReturn(false);
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getExitCodeLong()).thenReturn(0L);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        BatchDockerRunner runner = new BatchDockerRunner(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), config, mock(ContainerDetector.class));

        runner.requestStop("job-1");
        runner.clearStopRequest("job-1");
        runner.stopJob("job-1");
        BatchRunResult result = runner.run(job(), 1);

        assertEquals(0, result.exitCode());
        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void stopJobStopsEveryInFlightNodeContainer() throws Exception {
        EmulatorConfig config = config(Optional.empty());
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.dockerNetwork()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);

        CountDownLatch containersStarted = new CountDownLatch(2);
        AtomicInteger containerSequence = new AtomicInteger();
        Set<String> stoppedContainers = ConcurrentHashMap.newKeySet();
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenAnswer(invocation -> {
            String containerId = "container-" + containerSequence.getAndIncrement();
            containersStarted.countDown();
            return new ContainerLifecycleManager.ContainerInfo(containerId, Map.of());
        });
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        for (int i = 0; i < 2; i++) {
            String containerId = "container-" + i;
            when(dockerClient.inspectContainerCmd(containerId).exec().getState().getRunning())
                    .thenAnswer(invocation -> !stoppedContainers.contains(containerId));
            when(dockerClient.inspectContainerCmd(containerId).exec().getState().getExitCodeLong()).thenReturn(137L);
            doAnswer(invocation -> {
                stoppedContainers.add(containerId);
                return null;
            }).when(lifecycleManager).stopAndRemove(eq(containerId), isNull());
        }
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        BatchDockerRunner runner = new BatchDockerRunner(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), config, mock(ContainerDetector.class));
        BatchJob job = job();
        BatchNodeExecution firstNode = node(0);
        BatchNodeExecution secondNode = node(1);
        AtomicReference<BatchRunResult> firstResult = new AtomicReference<>();
        AtomicReference<BatchRunResult> secondResult = new AtomicReference<>();

        Thread firstThread = Thread.startVirtualThread(() -> firstResult.set(runner.run(job, 1, firstNode)));
        Thread secondThread = Thread.startVirtualThread(() -> secondResult.set(runner.run(job, 1, secondNode)));
        assertTrue(containersStarted.await(2, TimeUnit.SECONDS));
        runner.requestStop(job.getJobId());
        runner.stopJob(job.getJobId());
        firstThread.join(2000);
        secondThread.join(2000);

        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());
        assertEquals(137, firstResult.get().exitCode());
        assertEquals(137, secondResult.get().exitCode());
        verify(lifecycleManager).stopAndRemove("container-0", null);
        verify(lifecycleManager).stopAndRemove("container-1", null);
    }

    @Test
    void anAttemptStillRunningPastItsTimeoutIsPolledAtTheLimitAndThenTimedOut() {
        TestTime time = TestTime.create();
        Instant started = time.now();
        AtomicInteger polls = new AtomicInteger();
        AtomicReference<Instant> lastPoll = new AtomicReference<>();
        BatchDockerRunner runner = timedRunner(new TimelineRunTimer(time), () -> {
            polls.incrementAndGet();
            lastPoll.set(time.now());
            return true;
        });

        BatchRunResult result = runner.run(jobWithTimeout(60), 1);

        assertTrue(result.timedOut());
        assertEquals(137, result.exitCode());
        assertEquals("Job timed out", result.reason());
        // The container is looked at every 250 ms, the last time at the 60 s limit itself.
        assertEquals(started.plusSeconds(60), lastPoll.get());
        assertEquals(241, polls.get());
        assertEquals(started.toEpochMilli(), result.startedAt());
        assertEquals(started.plusSeconds(60).plusMillis(250).toEpochMilli(), result.stoppedAt());
    }

    @Test
    void aContainerThatExitsAtItsTimeoutLimitIsNotTimedOut() {
        TestTime time = TestTime.create();
        Instant limit = time.now().plusSeconds(60);
        BatchDockerRunner runner = timedRunner(new TimelineRunTimer(time), () -> time.now().isBefore(limit));

        BatchRunResult result = runner.run(jobWithTimeout(60), 1);

        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertEquals(limit.toEpochMilli(), result.stoppedAt());
    }

    @Test
    void steppingTheWallClockDuringAnAttemptDoesNotShortenItsTimeout() {
        TestTime time = TestTime.create();
        Instant started = time.now();
        TimelineRunTimer timer = new TimelineRunTimer(time);
        BatchDockerRunner runner = timedRunner(timer, () -> {
            if (!time.now().isBefore(started.plusSeconds(1))) {
                // The host's clock is set an hour ahead one second into the attempt.
                timer.wallClockAhead = Duration.ofHours(1);
            }
            return time.now().isBefore(started.plusSeconds(30));
        });

        BatchRunResult result = runner.run(jobWithTimeout(60), 1);

        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertEquals(started.plusSeconds(30), time.now());
    }

    /** A runner on {@code timer} whose one container is running for as long as {@code running} says. */
    private BatchDockerRunner timedRunner(BatchDockerRunner.RunTimer timer, BooleanSupplier running) {
        EmulatorConfig config = config(Optional.empty());
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.dockerNetwork()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getRunning())
                .thenAnswer(invocation -> running.getAsBoolean());
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getExitCodeLong()).thenReturn(0L);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        return new BatchDockerRunner(containerBuilder, lifecycleManager, mock(ContainerLogStreamer.class),
                config, mock(ContainerDetector.class), timer);
    }

    private BatchJob jobWithTimeout(int attemptDurationSeconds) {
        BatchJob job = job();
        BatchTimeout timeout = new BatchTimeout();
        timeout.setAttemptDurationSeconds(attemptDurationSeconds);
        job.setTimeout(timeout);
        return job;
    }

    /**
     * A run timer on a {@link TestTime} timeline for a run on the test's own thread: each sleep moves
     * the timeline on. The wall clock can be set ahead of the timeline, as a host's clock can be.
     */
    private static final class TimelineRunTimer implements BatchDockerRunner.RunTimer {
        private final TestTime time;
        private volatile Duration wallClockAhead = Duration.ZERO;

        TimelineRunTimer(TestTime time) {
            this.time = time;
        }

        @Override
        public long currentTimeMillis() {
            return time.currentTimeMillis() + wallClockAhead.toMillis();
        }

        @Override
        public long nanoTime() {
            return time.nanoTime();
        }

        @Override
        public void sleepMillis(long millis) {
            time.sleepAdvancing(Duration.ofMillis(millis));
        }
    }

    private BatchJob job() {
        BatchJob job = new BatchJob();
        job.setJobId("job-1");
        job.setJobName("my-job");
        job.setJobQueueName("my-queue");
        job.setJobDefinitionName("my-def");
        job.setContainerImage("busybox:stable");
        job.setRegion("us-east-1");
        job.setAccountId("000000000000");
        return job;
    }

    private BatchNodeExecution node(int nodeIndex) {
        BatchNodeExecution node = new BatchNodeExecution();
        node.setNodeIndex(nodeIndex);
        node.setContainerImage("busybox:stable");
        return node;
    }

    private BatchDockerRunner runner(EmulatorConfig config, ContainerDetector detector) {
        return new BatchDockerRunner(
                mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class),
                config,
                detector);
    }

    private EmulatorConfig config(Optional<String> hostname) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.hostname()).thenReturn(hostname);
        return config;
    }
}
