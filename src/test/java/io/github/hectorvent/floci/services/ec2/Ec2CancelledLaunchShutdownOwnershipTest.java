package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** One actual cancellation, synchronous shutdown and scheduler lifetime with scripted owned cleanup effects. */
class Ec2CancelledLaunchShutdownOwnershipTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "us-west-2";
    private static final String VPC = "vpc-cancelled-shutdown";
    private static final String SUBNET = "subnet-cancelled-shutdown";
    private static final String WORKLOAD = "cancelled-shutdown-original-workload";
    private static final String PRIVATE_IP = "10.0.3.10";
    private static final String BRIDGE_IP = "172.18.0.4";
    private static final String IMDS_IP = "192.168.215.9";
    private static final int SSH_PORT = 2203;

    @Test
    void shutdownMustRetainAndDrainTheOriginalCancelledLaunchOwner() throws Exception {
        try (Harness harness = new Harness()) {
            IllegalStateException cancellation = assertThrows(IllegalStateException.class,
                    () -> harness.manager.cancelLaunch(harness.instance));
            assertEquals("original workload removal remains uncertain", cancellation.getMessage());
            harness.snapshot("cancelled-owner-pending");
            JsonNode pending = JSON.valueToTree(harness.instance);
            assertEquals("terminated", harness.instance.getState().getName());
            assertTrue(harness.instance.getTerminatedAt() > 0);
            assertNull(harness.instance.getDockerContainerId());
            assertEquals(0, harness.instance.getSshHostPort());
            assertNull(harness.instance.getContainerBridgeIp());
            assertEquals(1, harness.strictRemovals.get());
            assertEquals(0, harness.sshReleases.get());
            assertEquals(0, harness.privateIpReleases.get());

            RuntimeException shutdownFailure = harness.shutdown();
            int attemptsAfterShutdown = harness.strictRemovals.get();
            int leasesAfterShutdown = harness.privateIpReleases.get();
            int metadataAfterShutdown = harness.metadataEvents.get();
            harness.facts.put("shutdownFailure", shutdownFailure == null ? "" : shutdownFailure.toString());
            harness.facts.put("strictAttemptsAfterShutdown", attemptsAfterShutdown);
            harness.facts.put("privateIpReleasesAfterShutdown", leasesAfterShutdown);
            harness.facts.put("metadataEventsAfterShutdown", metadataAfterShutdown);
            harness.snapshot("shutdown-while-workload-uncertain");

            harness.time.advance(Duration.ofMillis(999));
            harness.snapshot("before-owned-retry-deadline");
            assertEquals(attemptsAfterShutdown, harness.strictRemovals.get());
            harness.recovered.set(true);
            harness.time.advance(Duration.ofMillis(1));
            assertTrue(harness.ownedMetadataCleanup.await(3, TimeUnit.SECONDS));
            harness.serviceExecutor.submit(() -> { }).get(3, TimeUnit.SECONDS);
            harness.snapshot("original-owner-recovered");
            RuntimeException completedShutdownFailure = harness.shutdown();
            harness.snapshot("shutdown-after-owned-recovery");

            assertAll("The terminal instance marker must not hide the original pending cleanup owner",
                    () -> assertTrue(shutdownFailure instanceof IllegalStateException,
                            "Shutdown must report the still-uncertain original workload"),
                    () -> assertEquals(2, attemptsAfterShutdown,
                            "Shutdown must retry the original pending owner using its captured workload ID"),
                    () -> assertEquals(0, leasesAfterShutdown,
                            "No private address release before owned workload absence"),
                    () -> assertEquals(0, metadataAfterShutdown,
                            "No metadata retirement before owned workload absence"),
                    () -> assertEquals(1, harness.privateIpReleases.get(),
                            "The original private lease is released once"),
                    () -> assertEquals(1, harness.sshReleases.get(),
                            "The original captured SSH lease is released once"),
                    () -> assertTrue(harness.events.stream()
                            .filter(event -> "private-ip-release".equals(event.get("kind")))
                            .allMatch(event -> Boolean.TRUE.equals(event.get("workloadGone")))),
                    () -> assertEquals(pending, JSON.valueToTree(harness.instance),
                            "Cleanup must not rewrite the already recorded cancellation outcome"),
                    () -> assertNull(completedShutdownFailure));
        }
    }

    private static final class Harness implements AutoCloseable {
        private final TestTime time = TestTime.create();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        // One owned worker makes the queued barrier prove the scheduled cleanup attempt has returned.
        private final ExecutorService serviceExecutor = Executors.newSingleThreadExecutor();
        private final Instance instance = new Instance();
        private final AtomicBoolean recovered = new AtomicBoolean();
        private final AtomicBoolean workloadGone = new AtomicBoolean();
        private final AtomicInteger strictRemovals = new AtomicInteger();
        private final AtomicInteger sshReleases = new AtomicInteger();
        private final AtomicInteger privateIpReleases = new AtomicInteger();
        private final AtomicInteger metadataEvents = new AtomicInteger();
        private final CountDownLatch ownedMetadataCleanup = new CountDownLatch(1);
        private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> snapshots = new CopyOnWriteArrayList<>();
        private final Map<String, Object> facts = new LinkedHashMap<>();
        private final Ec2ContainerManager manager;

        private Harness() {
            instance.setInstanceId("i-cancelled-shutdown-owner");
            instance.setOwnerId(ACCOUNT);
            instance.setState(InstanceState.pending());
            instance.setRegion(REGION);
            instance.setVpcId(VPC);
            instance.setSubnetId(SUBNET);
            instance.setPrivateIpAddress(PRIVATE_IP);
            instance.setDockerContainerId(WORKLOAD);
            instance.setContainerBridgeIp(BRIDGE_IP);
            instance.setImdsSourceIp(IMDS_IP);
            instance.setSshHostPort(SSH_PORT);

            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            doAnswer(invocation -> {
                strictRemovals.incrementAndGet();
                event("strict-workload-removal-attempt", Map.of("container", WORKLOAD));
                if (!recovered.get()) {
                    event("strict-workload-removal-uncertain", Map.of("container", WORKLOAD));
                    throw new IllegalStateException("original workload removal remains uncertain");
                }
                workloadGone.set(true);
                event("strict-workload-removal-complete", Map.of("container", WORKLOAD));
                return null;
            }).when(lifecycle).removeIfExistsStrict(WORKLOAD);
            PortAllocator ports = mock(PortAllocator.class);
            doAnswer(invocation -> {
                sshReleases.incrementAndGet();
                event("ssh-release", Map.of("port", SSH_PORT));
                return null;
            }).when(ports).release(SSH_PORT);
            VpcNetworkManager network = mock(VpcNetworkManager.class);
            doAnswer(invocation -> {
                privateIpReleases.incrementAndGet();
                event("private-ip-release", Map.of("region", REGION, "subnet", SUBNET, "ip", PRIVATE_IP));
                return null;
            }).when(network).releasePrivateIp(REGION, SUBNET, PRIVATE_IP);
            doAnswer(invocation -> {
                event("original-workload-detach", Map.of("region", REGION, "vpc", VPC, "container", WORKLOAD));
                return null;
            }).when(network).detach(REGION, VPC, WORKLOAD);
            Ec2MetadataServer metadata = mock(Ec2MetadataServer.class);
            doAnswer(invocation -> {
                metadataEvents.incrementAndGet();
                event("metadata-instance-unregister", Map.of());
                return null;
            }).when(metadata).unregisterInstance(same(instance));
            doAnswer(invocation -> {
                metadataEvents.incrementAndGet();
                Map<String, Object> detail = new LinkedHashMap<>();
                String ip = invocation.getArgument(0);
                detail.put("ip", ip);
                event("metadata-address-unregister", detail);
                if (BRIDGE_IP.equals(ip) && workloadGone.get()) {
                    ownedMetadataCleanup.countDown();
                }
                return null;
            }).when(metadata).unregisterContainer(any(), same(instance));
            Ec2PortForwardManager forwards = mock(Ec2PortForwardManager.class);
            doAnswer(invocation -> {
                event("unpublish", Map.of());
                return null;
            }).when(forwards).unpublishAll(same(instance));
            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.ec2()).thenReturn(ec2);
            RegionResolver region = mock(RegionResolver.class);
            when(region.getAccountId()).thenReturn(ACCOUNT);
            manager = new Ec2ContainerManager(mock(ContainerBuilder.class), lifecycle,
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class), mock(DockerHostResolver.class),
                    mock(DockerClient.class), ports, config, metadata, forwards, region,
                    mock(ContainerNetworkReachability.class), network, mock(ContainerReachableEndpoint.class),
                    new Ec2InstanceTypeCatalog(), executor, serviceExecutor, Duration.ofSeconds(1),
                    millis -> {
                        if (Thread.interrupted()) {
                            throw new InterruptedException();
                        }
                        time.sleepAdvancing(Duration.ofMillis(millis));
                    }, time.newScheduler(), time::nanoTime, time.clock());
            snapshot("initial");
        }

        private RuntimeException shutdown() {
            try {
                manager.terminateForShutdown(instance);
                event("shutdown-returned", Map.of());
                return null;
            } catch (RuntimeException failure) {
                event("shutdown-refused", Map.of("type", failure.getClass().getName(),
                        "message", failure.getMessage()));
                return failure;
            }
        }

        private void event(String kind, Map<String, Object> detail) {
            Map<String, Object> event = new LinkedHashMap<>(detail);
            event.put("kind", kind);
            event.put("instanceId", instance.getInstanceId());
            event.put("workloadGone", workloadGone.get());
            event.put("nanoTime", time.nanoTime());
            events.add(event);
        }

        private void snapshot(String stage) {
            snapshots.add(Map.of("stage", stage, "nanoTime", time.nanoTime(),
                    "instance", JSON.valueToTree(instance), "workloadGone", workloadGone.get(),
                    "strictRemovals", strictRemovals.get(), "sshReleases", sshReleases.get(),
                    "privateIpReleases", privateIpReleases.get(), "metadataEvents", metadataEvents.get()));
        }

        @Override
        public void close() throws Exception {
            try {
                snapshot("final-before-test-cleanup");
                Path directory = Path.of("target", "ec2-cancelled-shutdown-receipts");
                Files.createDirectories(directory);
                Path receipt = directory.resolve("cancelled-launch-owner.json");
                assertFalse(Files.exists(receipt));
                JSON.writerWithDefaultPrettyPrinter().writeValue(receipt.toFile(), Map.of(
                        "scope", "Actual cancellation, shutdown and captured FailedLaunchCleanup scheduler",
                        "originalOwner", Map.of("account", ACCOUNT, "region", REGION, "container", WORKLOAD,
                                "subnet", SUBNET, "privateIp", PRIVATE_IP, "bridgeIp", BRIDGE_IP, "sshPort", SSH_PORT),
                        "events", events, "snapshots", snapshots, "facts", facts));
            } finally {
                manager.stop();
            }
        }
    }
}
