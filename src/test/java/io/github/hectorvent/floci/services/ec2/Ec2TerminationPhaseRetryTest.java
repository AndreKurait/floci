package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.exception.DockerException;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** Actual development termination and scheduler paths, with no Docker daemon or sockets. */
class Ec2TerminationPhaseRetryTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String REGION = "us-west-2";
    private static final String SUBNET = "subnet-phase-retry";
    private static final String PRIVATE_IP = "10.0.1.10";
    private static final String BRIDGE_IP = "172.18.0.2";
    private static final String IMDS_IP = "192.168.215.7";
    private static final int PORT = 2201;

    @Test
    void completedSshReleaseIsNotRepeatedAfterLaterMetadataFailure() throws Exception {
        try (Harness harness = new Harness("completed-ssh", false, false, true)) {
            harness.terminate();
            harness.drain("after-first-attempt");
            harness.assertLateFailure();
            harness.advanceRetry();
            harness.assertCompleted();
            assertEquals(1, harness.sshReleases.get(), "A completed original lease release must not run again");
        }
    }

    @Test
    void aReallocatedSshReservationSurvivesTheOldOwnersLatePhaseRetry() throws Exception {
        try (Harness harness = new Harness("reallocated-ssh", true, false, true)) {
            harness.terminate();
            harness.drain("after-first-attempt");
            harness.assertLateFailure();
            assertEquals(PORT, harness.ports.allocate(PORT, PORT), "The completed old release allows reuse");
            harness.event("replacement-reserved", Map.of("port", PORT));
            assertThrows(RuntimeException.class, () -> harness.ports.allocate(PORT, PORT));
            harness.snapshot("replacement-reservation-held");
            harness.advanceRetry();
            harness.assertCompleted();
            assertThrows(RuntimeException.class, () -> {
                int allocated = harness.ports.allocate(PORT, PORT);
                harness.event("post-retry-allocation-accepted", Map.of("port", allocated));
            }, "The old owner must not release a reservation made after its completed release");
        }
    }

    @Test
    void uncertainWorkloadKeepsLeasesAndCallbackUntilItsOwnedRetrySucceeds() throws Exception {
        try (Harness harness = new Harness("uncertain-workload", false, true, false)) {
            JsonNode initial = JSON.valueToTree(harness.instance);
            harness.terminate();
            harness.drain("after-first-attempt");
            assertEquals("shutting-down", harness.instance.getState().getName());
            assertEquals(0, harness.instance.getTerminatedAt());
            assertEquals(0, harness.sshReleases.get());
            assertEquals(0, harness.metadataAttempts.get());
            assertEquals(0, harness.privateIpReleases.get());
            assertEquals(0, harness.callbacks.get());
            JsonNode expected = initial.deepCopy();
            ((ObjectNode) expected)
                    .set("state", JSON.valueToTree(InstanceState.shuttingDown()));
            assertEquals(expected, JSON.valueToTree(harness.instance));
            harness.advanceRetry();
            harness.assertCompleted();
            assertEquals(1, harness.sshReleases.get());
            assertEquals(2, harness.removals.get());
        }
    }

    private static final class Harness implements AutoCloseable {
        private final String label;
        private final boolean realAllocator;
        private final TestTime time = TestTime.create();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Instance instance = new Instance();
        private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> snapshots = new CopyOnWriteArrayList<>();
        private final AtomicInteger removals = new AtomicInteger();
        private final AtomicInteger sshReleases = new AtomicInteger();
        private final AtomicInteger metadataAttempts = new AtomicInteger();
        private final AtomicInteger privateIpReleases = new AtomicInteger();
        private final AtomicInteger callbacks = new AtomicInteger();
        private final PortAllocator ports;
        private final Ec2ContainerManager manager;
        private final String container;

        private Harness(String label, boolean realAllocator, boolean failWorkload, boolean failMetadata) {
            this.label = label;
            this.realAllocator = realAllocator;
            container = "phase-retry-container-" + label;
            instance.setInstanceId("i-phase-retry-" + label);
            instance.setOwnerId("111122223333");
            instance.setState(InstanceState.running());
            instance.setRegion(REGION);
            instance.setVpcId("vpc-phase-retry");
            instance.setSubnetId(SUBNET);
            instance.setPrivateIpAddress(PRIVATE_IP);
            instance.setDockerContainerId(container);
            instance.setContainerBridgeIp(BRIDGE_IP);
            instance.setImdsSourceIp(IMDS_IP);
            instance.setSshHostPort(PORT);

            DockerClient docker = mock(DockerClient.class);
            InspectContainerCmd inspect = mock(InspectContainerCmd.class);
            when(docker.inspectContainerCmd(container)).thenReturn(inspect);
            when(inspect.exec()).thenReturn(mock(InspectContainerResponse.class));
            RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
            when(docker.removeContainerCmd(container)).thenReturn(remove);
            doAnswer(invocation -> {
                int attempt = removals.incrementAndGet();
                event("workload-remove-attempt", Map.of("container", container, "attempt", attempt));
                if (failWorkload && attempt == 1) {
                    event("workload-uncertain", Map.of("container", container));
                    throw new DockerException("scripted original workload removal uncertain", 500);
                }
                event("workload-gone", Map.of("container", container));
                return null;
            }).when(remove).exec();

            ports = realAllocator ? spy(new PortAllocator()) : mock(PortAllocator.class);
            if (realAllocator) {
                doReturn(true).when(ports).isPortFree(PORT);
                assertEquals(PORT, ports.allocate(PORT, PORT));
            }
            doAnswer(invocation -> {
                sshReleases.incrementAndGet();
                if (realAllocator) {
                    invocation.callRealMethod();
                }
                event("ssh-release", Map.of("port", PORT));
                return null;
            }).when(ports).release(PORT);

            Ec2MetadataServer metadata = mock(Ec2MetadataServer.class);
            doAnswer(invocation -> {
                int attempt = metadataAttempts.incrementAndGet();
                event("metadata-bridge-attempt", Map.of("ip", BRIDGE_IP, "attempt", attempt));
                if (failMetadata && attempt == 1) {
                    event("metadata-bridge-failed", Map.of("ip", BRIDGE_IP));
                    throw new IllegalStateException("scripted metadata failure after completed SSH release");
                }
                event("metadata-bridge-complete", Map.of("ip", BRIDGE_IP));
                return null;
            }).when(metadata).unregisterContainer(eq(BRIDGE_IP), same(instance));
            doAnswer(invocation -> {
                event("metadata-imds-complete", Map.of("ip", IMDS_IP));
                return null;
            }).when(metadata).unregisterContainer(eq(IMDS_IP), same(instance));
            doAnswer(invocation -> {
                event("metadata-instance-complete", Map.of("instanceId", instance.getInstanceId()));
                return null;
            }).when(metadata).unregisterInstance(same(instance));
            VpcNetworkManager network = mock(VpcNetworkManager.class);
            doAnswer(invocation -> {
                privateIpReleases.incrementAndGet();
                event("private-ip-release", Map.of("region", REGION, "subnet", SUBNET, "ip", PRIVATE_IP));
                return null;
            }).when(network).releasePrivateIp(REGION, SUBNET, PRIVATE_IP);
            Ec2PortForwardManager forwards = mock(Ec2PortForwardManager.class);
            doAnswer(invocation -> {
                event("unpublish", Map.of("instanceId", instance.getInstanceId()));
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

        private void terminate() {
            manager.terminate(instance, () -> {
                callbacks.incrementAndGet();
                event("callback", Map.of("instanceId", instance.getInstanceId()));
            });
        }

        private void drain(String stage) throws Exception {
            assertFalse(executor.isShutdown(), "Keep development's actual retry executor open");
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            snapshot(stage);
        }

        private void advanceRetry() throws Exception {
            int attempts = removals.get();
            time.advance(Duration.ofMillis(999));
            drain("before-retry-deadline");
            assertEquals(attempts, removals.get());
            time.advance(Duration.ofMillis(1));
            drain("after-scheduled-retry");
        }

        private void assertLateFailure() {
            assertEquals("shutting-down", instance.getState().getName());
            assertEquals(0, instance.getTerminatedAt());
            assertEquals(1, removals.get());
            assertEquals(1, sshReleases.get());
            assertEquals(1, metadataAttempts.get());
            assertEquals(0, privateIpReleases.get());
            assertEquals(1, callbacks.get(), "Development allows this once workload absence is established");
        }

        private void assertCompleted() {
            assertEquals("terminated", instance.getState().getName());
            assertTrue(instance.getTerminatedAt() > 0);
            assertEquals(container, instance.getDockerContainerId());
            assertEquals("111122223333", instance.getOwnerId());
            assertEquals(REGION, instance.getRegion());
            assertEquals(PRIVATE_IP, instance.getPrivateIpAddress());
            assertEquals(1, privateIpReleases.get());
            assertEquals(1, callbacks.get());
            for (String kind : List.of("metadata-bridge-complete", "metadata-imds-complete",
                    "metadata-instance-complete")) {
                assertEquals(1, events.stream().filter(event -> kind.equals(event.get("kind"))).count());
            }
        }

        private void event(String kind, Map<String, Object> detail) {
            Map<String, Object> event = new LinkedHashMap<>(detail);
            event.put("kind", kind);
            event.put("instanceId", instance.getInstanceId());
            event.put("nanoTime", time.nanoTime());
            events.add(event);
        }

        private void snapshot(String stage) {
            snapshots.add(Map.of("stage", stage, "nanoTime", time.nanoTime(),
                    "instance", JSON.valueToTree(instance), "workloadAttempts", removals.get(),
                    "sshReleases", sshReleases.get(), "metadataBridgeAttempts", metadataAttempts.get(),
                    "privateIpReleases", privateIpReleases.get(), "callbacks", callbacks.get()));
        }

        @Override
        public void close() throws Exception {
            try {
                snapshot("final-before-test-cleanup");
                Path directory = Path.of("target", "ec2-phase-retry-receipts");
                Files.createDirectories(directory);
                Path receipt = directory.resolve(label + ".json");
                assertFalse(Files.exists(receipt), "Never overwrite a prior test receipt");
                JSON.writerWithDefaultPrettyPrinter().writeValue(receipt.toFile(), Map.of(
                        "label", label, "scope", "Actual dev caller and scheduler; mocked Docker/peripherals",
                        "realAllocator", realAllocator, "events", events, "snapshots", snapshots));
            } finally {
                manager.stop();
                if (realAllocator) {
                    ports.release(PORT);
                }
            }
        }
    }
}
