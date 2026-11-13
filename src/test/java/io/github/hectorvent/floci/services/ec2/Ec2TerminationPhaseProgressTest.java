package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real captured operations and scheduler/shutdown entry points; Docker and peripheral effects are scripted. */
class Ec2TerminationPhaseProgressTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final List<String> PHASES = List.of("unpublish", "workload", "ssh",
            "metadata-bridge", "metadata-imds", "metadata-instance", "private-ip");
    private static final String REGION = "us-west-2";
    private static final String SUBNET = "subnet-phase-progress";
    private static final String PRIVATE_IP = "10.0.2.10";
    private static final String BRIDGE_IP = "172.18.0.3";
    private static final String IMDS_IP = "192.168.215.8";

    @ParameterizedTest
    @ValueSource(strings = {"unpublish", "ssh", "metadata-imds", "metadata-instance", "private-ip"})
    void retriesOnlyTheUnsuccessfulPhaseAndRemainingSteps(String failedPhase) throws Exception {
        try (Harness harness = new Harness("phase-" + failedPhase)) {
            harness.failOnce = failedPhase;
            harness.terminate(null);
            harness.drain("after-first-failure");
            assertEquals("shutting-down", harness.instance.getState().getName());
            assertEquals(0, harness.instance.getTerminatedAt());
            int failedIndex = PHASES.indexOf(failedPhase);
            for (int index = 0; index < PHASES.size(); index++) {
                assertEquals(index <= failedIndex ? 1 : 0, harness.count(PHASES.get(index)));
            }
            assertEquals("unpublish".equals(failedPhase) ? 0 : 1, harness.callbacks.get());
            Map<String, Integer> before = harness.counts();
            harness.time.advance(Duration.ofMillis(999));
            harness.drain("before-retry-deadline");
            assertEquals(before, harness.counts());
            harness.time.advance(Duration.ofMillis(1));
            harness.drain("after-scheduled-retry");
            harness.assertCompleted(1);
            for (String phase : PHASES) {
                assertEquals(phase.equals(failedPhase) ? 2 : 1, harness.count(phase), phase);
            }
        }
    }

    @Test
    void synchronousShutdownWaitsForTheSameActiveOperationAndCompletedCallsStayIdempotent() throws Exception {
        try (Harness harness = new Harness("concurrent-shutdown")) {
            harness.blockBridge = true;
            harness.terminate(null);
            assertTrue(harness.bridgeEntered.await(2, TimeUnit.SECONDS));
            FutureTask<Void> shutdown = new FutureTask<>(() -> {
                harness.manager.terminateForShutdown(harness.instance);
                return null;
            });
            Thread thread = new Thread(shutdown, "phase-progress-owned-shutdown");
            thread.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertEquals(Thread.State.BLOCKED, thread.getState(),
                        "Shutdown must wait for the exact active operation's phase monitor");
                harness.facts.put("shutdownObservedBlocked", true);
                harness.snapshot("shutdown-blocked");
                assertEquals(1, harness.count("workload"));
                assertEquals(1, harness.count("ssh"));
                assertEquals(1, harness.count("metadata-bridge"));
                assertEquals(0, harness.count("metadata-imds"));
            } finally {
                harness.finishBridge.countDown();
                thread.join(2000);
            }
            shutdown.get(2, TimeUnit.SECONDS);
            assertFalse(thread.isAlive());
            harness.drain("both-callers-complete");
            harness.assertCompleted(1);
            Map<String, Integer> complete = harness.counts();
            JsonNode completedInstance = JSON.valueToTree(harness.instance);
            harness.manager.terminateForShutdown(harness.instance);
            harness.terminate(() -> harness.facts.put("unexpectedReplacementCallback", true));
            harness.drain("repeated-completed-calls");
            assertEquals(complete, harness.counts());
            assertEquals(completedInstance, JSON.valueToTree(harness.instance));
            assertEquals(1, harness.callbacks.get());
            assertFalse(harness.facts.containsKey("unexpectedReplacementCallback"));
            PHASES.forEach(phase -> assertEquals(1, harness.count(phase), phase));
        }
    }

    @Test
    void standaloneShutdownFailureKeepsSuccessfulProgressForItsNextCall() throws Exception {
        try (Harness harness = new Harness("standalone-shutdown")) {
            harness.failOnce = "metadata-imds";
            assertThrows(IllegalStateException.class, () -> harness.manager.terminateForShutdown(harness.instance));
            harness.snapshot("shutdown-failed");
            assertEquals("shutting-down", harness.instance.getState().getName());
            assertEquals(0, harness.instance.getTerminatedAt());
            assertEquals(1, harness.count("ssh"));
            assertEquals(1, harness.count("metadata-bridge"));
            assertEquals(0, harness.count("metadata-instance"));
            assertEquals(0, harness.callbacks.get());
            harness.manager.terminateForShutdown(harness.instance);
            harness.snapshot("shutdown-retried");
            harness.assertCompleted(0);
            for (String phase : PHASES) {
                assertEquals("metadata-imds".equals(phase) ? 2 : 1, harness.count(phase), phase);
            }
        }
    }

    @Test
    void originalCallbackCanWaitForShutdownOnAnotherThreadWithoutHoldingThePhaseMonitor() throws Exception {
        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
        try (Harness harness = new Harness("callback-cross-thread")) {
            AtomicReference<Exception> callbackFailure = new AtomicReference<>();
            harness.terminate(() -> {
                try {
                    callbackExecutor.submit(() -> harness.manager.terminateForShutdown(harness.instance))
                            .get(2, TimeUnit.SECONDS);
                    harness.facts.put("callbackCrossThreadShutdownCompleted", true);
                } catch (Exception failure) {
                    callbackFailure.set(failure);
                }
            });
            harness.drain("callback-returned");
            assertNull(callbackFailure.get());
            assertEquals(true, harness.facts.get("callbackCrossThreadShutdownCompleted"));
            harness.assertCompleted(1);
            PHASES.forEach(phase -> assertEquals(1, harness.count(phase), phase));
        } finally {
            callbackExecutor.shutdownNow();
            assertTrue(callbackExecutor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static final class Harness implements AutoCloseable {
        private final String label;
        private final TestTime time = TestTime.create();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Instance instance = new Instance();
        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> snapshots = new CopyOnWriteArrayList<>();
        private final Map<String, Object> facts = new ConcurrentHashMap<>();
        private final AtomicInteger callbacks = new AtomicInteger();
        private final CountDownLatch bridgeEntered = new CountDownLatch(1);
        private final CountDownLatch finishBridge = new CountDownLatch(1);
        private final Ec2ContainerManager manager;
        private final JsonNode initialInstance;
        private String failOnce;
        private boolean blockBridge;

        private Harness(String label) {
            this.label = label;
            instance.setInstanceId("i-progress-" + label);
            instance.setOwnerId("111122223333");
            instance.setState(InstanceState.running());
            instance.setRegion(REGION);
            instance.setVpcId("vpc-phase-progress");
            instance.setSubnetId(SUBNET);
            instance.setPrivateIpAddress(PRIVATE_IP);
            instance.setDockerContainerId("progress-container-" + label);
            instance.setContainerBridgeIp(BRIDGE_IP);
            instance.setImdsSourceIp(IMDS_IP);
            instance.setSshHostPort(2202);
            initialInstance = JSON.valueToTree(instance);

            DockerClient docker = mock(DockerClient.class);
            InspectContainerCmd inspect = mock(InspectContainerCmd.class);
            when(docker.inspectContainerCmd(instance.getDockerContainerId())).thenReturn(inspect);
            when(inspect.exec()).thenReturn(mock(InspectContainerResponse.class));
            RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
            when(docker.removeContainerCmd(instance.getDockerContainerId())).thenReturn(remove);
            doAnswer(invocation -> {
                step("workload");
                return null;
            }).when(remove).exec();
            PortAllocator ports = mock(PortAllocator.class);
            doAnswer(invocation -> {
                step("ssh");
                return null;
            }).when(ports).release(2202);
            Ec2MetadataServer metadata = mock(Ec2MetadataServer.class);
            doAnswer(invocation -> {
                step("metadata-bridge");
                return null;
            }).when(metadata).unregisterContainer(eq(BRIDGE_IP), same(instance));
            doAnswer(invocation -> {
                step("metadata-imds");
                return null;
            }).when(metadata).unregisterContainer(eq(IMDS_IP), same(instance));
            doAnswer(invocation -> {
                step("metadata-instance");
                return null;
            }).when(metadata).unregisterInstance(same(instance));
            VpcNetworkManager network = mock(VpcNetworkManager.class);
            doAnswer(invocation -> {
                step("private-ip");
                return null;
            }).when(network).releasePrivateIp(REGION, SUBNET, PRIVATE_IP);
            Ec2PortForwardManager forwards = mock(Ec2PortForwardManager.class);
            doAnswer(invocation -> {
                step("unpublish");
                return null;
            }).when(forwards).unpublishAll(same(instance));
            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.ec2()).thenReturn(ec2);
            RegionResolver region = mock(RegionResolver.class);
            when(region.getAccountId()).thenReturn(instance.getOwnerId());
            manager = new Ec2ContainerManager(mock(ContainerBuilder.class),
                    mock(ContainerLifecycleManager.class), mock(ContainerLogStreamer.class),
                    mock(ContainerDetector.class), mock(DockerHostResolver.class), docker, ports, config,
                    metadata, forwards, region, mock(ContainerNetworkReachability.class), network,
                    mock(ContainerReachableEndpoint.class), new Ec2InstanceTypeCatalog(), executor,
                    Ec2ContainerManager.createServiceNetworkRetryExecutor(), Duration.ofSeconds(1),
                    millis -> {
                        if (Thread.interrupted()) {
                            throw new InterruptedException();
                        }
                        time.sleepAdvancing(Duration.ofMillis(millis));
                    }, time.newScheduler(), time::nanoTime, time.clock());
            snapshot("initial");
        }

        private void terminate(Runnable hook) {
            manager.terminate(instance, () -> {
                callbacks.incrementAndGet();
                event("callback", 1);
                if (hook != null) {
                    hook.run();
                }
            });
        }

        private void step(String phase) throws InterruptedException {
            int count = attempts.computeIfAbsent(phase, ignored -> new AtomicInteger()).incrementAndGet();
            event(phase + "-attempt", count);
            if (blockBridge && "metadata-bridge".equals(phase) && count == 1) {
                bridgeEntered.countDown();
                assertTrue(finishBridge.await(3, TimeUnit.SECONDS));
            }
            if (phase.equals(failOnce) && count == 1) {
                event(phase + "-failed", count);
                throw new IllegalStateException("scripted unsuccessful phase: " + phase);
            }
            event(phase + "-complete", count);
        }

        private int count(String phase) {
            AtomicInteger value = attempts.get(phase);
            return value == null ? 0 : value.get();
        }

        private Map<String, Integer> counts() {
            Map<String, Integer> values = new LinkedHashMap<>();
            PHASES.forEach(phase -> values.put(phase, count(phase)));
            return values;
        }

        private void event(String kind, int attempt) {
            events.add(Map.of("kind", kind, "attempt", attempt, "instanceId", instance.getInstanceId(),
                    "nanoTime", time.nanoTime(), "thread", Thread.currentThread().getName()));
        }

        private void snapshot(String stage) {
            snapshots.add(Map.of("stage", stage, "nanoTime", time.nanoTime(),
                    "instance", JSON.valueToTree(instance), "attempts", counts(), "callbacks", callbacks.get()));
        }

        private void drain(String stage) throws Exception {
            assertFalse(executor.isShutdown());
            executor.submit(() -> { }).get(3, TimeUnit.SECONDS);
            snapshot(stage);
        }

        private void assertCompleted(int expectedCallbacks) {
            assertEquals("terminated", instance.getState().getName());
            assertTrue(instance.getTerminatedAt() > 0);
            ObjectNode expected = initialInstance.deepCopy();
            expected.set("state", JSON.valueToTree(InstanceState.terminated()));
            expected.put("terminatedAt", instance.getTerminatedAt());
            assertEquals(expected, JSON.valueToTree(instance));
            assertEquals(expectedCallbacks, callbacks.get());
            PHASES.forEach(phase -> assertEquals(1,
                    events.stream().filter(event -> (phase + "-complete").equals(event.get("kind"))).count(), phase));
        }

        @Override
        public void close() throws Exception {
            try {
                finishBridge.countDown();
                snapshot("final-before-test-cleanup");
                Path directory = Path.of("target", "ec2-phase-retry-receipts");
                Files.createDirectories(directory);
                Path receipt = directory.resolve(label + ".json");
                assertFalse(Files.exists(receipt));
                JSON.writerWithDefaultPrettyPrinter().writeValue(receipt.toFile(), Map.of(
                        "label", label, "scope", "Actual dev captured operation and scheduler/shutdown",
                        "events", events, "snapshots", snapshots, "facts", facts,
                        "failOnce", failOnce == null ? "" : failOnce));
            } finally {
                manager.stop();
            }
        }
    }
}
