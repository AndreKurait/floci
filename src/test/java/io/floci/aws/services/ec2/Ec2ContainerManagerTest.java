package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.PingCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RenameContainerCmd;
import com.github.dockerjava.api.command.RestartContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.StreamType;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExecStubs;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLiveness;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.core.common.docker.UserDataPipeline;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceNetworkInterface;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.model.LaunchTemplateData;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.services.ec2instanceconnect.InstanceConnectKeys;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import io.floci.aws.testing.HelperShellFixture;
import io.floci.aws.testing.LockWaits;
import io.floci.aws.testing.ParallelLocks;
import io.floci.aws.testing.TestTime;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
@ResourceLock(ParallelLocks.CONFIG_MAPPING)
class Ec2ContainerManagerTest {

    @Test
    void ipv6UpdatesRespectEnforcementAndPropagateFirewallFailure() {
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        TestTime time = TestTime.create();
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                mock(DockerHostResolver.class), mock(DockerClient.class), mock(PortAllocator.class), config,
                mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class), firewall, new Ec2InstanceTypeCatalog(),
                timelinePause(time), time.newScheduler(), time::nanoTime, time.clock());
        List<String> addresses = List.of("2001:db8::20");
        manager.updateIpv6Addresses("eni-updated", addresses);
        verify(firewall, never()).updateIpv6Addresses(anyString(), anyList());

        when(firewall.enabled()).thenReturn(true);
        manager.updateIpv6Addresses("eni-updated", addresses);
        verify(firewall).updateIpv6Addresses("eni-updated", addresses);
        doThrow(new IllegalStateException("policy write failed"))
                .when(firewall).updateIpv6Addresses("eni-updated", List.of());
        assertThrows(IllegalStateException.class, () -> manager.updateIpv6Addresses("eni-updated", List.of()));
    }

    @Test
    void shutdownWaitsForInterruptedLaunchCleanupBeforeReturning() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch finishCleanup = new CountDownLatch(1);
        try {
            LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
            executor.submit(() -> {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    // Model a launch unwinding a Docker operation after shutdown interrupts it.
                    interrupted.countDown();
                    try {
                        finishCleanup.await();
                    } catch (InterruptedException secondInterrupt) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<?> stopping = stopper.submit(() -> {
                harness.manager().stop();
            });
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            // Shutdown is held in its wait for the launch workers, not merely slow to return.
            LockWaits.awaitTimedWaitIn(Ec2ContainerManager.class, "awaitShutdown", 1);
            assertFalse(stopping.isDone(), "shutdown returned while a launch was still cleaning up");
            finishCleanup.countDown();
            stopping.get(2, TimeUnit.SECONDS);
            assertTrue(executor.isTerminated());
        } finally {
            finishCleanup.countDown();
            executor.shutdownNow();
            stopper.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    void terminationRetiresFirewallIdentityBeforeRemovingItsHelper(
            boolean transientFailure, boolean peerRefreshFailure) throws Exception {
        DockerClient docker = mock(DockerClient.class);
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
        when(firewall.enabled()).thenReturn(true);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        TestTime time = TestTime.create();
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class), lifecycle,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                mock(DockerHostResolver.class), docker, mock(PortAllocator.class), config,
                mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class), firewall, new Ec2InstanceTypeCatalog(),
                timelinePause(time), time.newScheduler(), time::nanoTime, time.clock());
        Instance instance = instance("i-firewall-teardown");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setNetworkInterfaceId("eni-firewall-teardown");
        instance.setNetworkInterfaces(new ArrayList<>(List.of(eni)));
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse inspected = inspectResponse("172.18.0.12");
        when(inspected.getHostConfig()).thenReturn(
                HostConfig.newHostConfig().withNetworkMode("container:" + TEST_HELPER_ID));
        when(inspect.exec()).thenReturn(inspected);
        when(docker.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerCmd inspectHelper = mock(InspectContainerCmd.class);
        InspectContainerResponse helper = inspectResponse("172.18.0.12");
        when(helper.getConfig()).thenReturn(new ContainerConfig().withLabels(Map.of(
                "io.floci.security-group.helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", instance.getInstanceId(),
                "io.floci.owner", "4566")));
        when(inspectHelper.exec()).thenReturn(helper);
        when(docker.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspectHelper);
        AtomicInteger removals = new AtomicInteger();
        AtomicInteger peerUpdates = new AtomicInteger();
        AtomicBoolean retired = new AtomicBoolean();
        AtomicBoolean helperRemoved = new AtomicBoolean();
        Runnable firewallCleanup = mock(Runnable.class);
        when(firewall.captureUnregister("eni-firewall-teardown")).thenReturn(firewallCleanup);
        doAnswer(invocation -> {
            assertFalse(helperRemoved.get(), "Peer reconciliation still needs this registered namespace");
            if (peerUpdates.incrementAndGet() == 1 && peerRefreshFailure) {
                throw new IllegalStateException("peer policy refresh is still completing");
            }
            retired.set(true);
            return null;
        }).when(firewallCleanup).run();
        doAnswer(invocation -> {
            assertTrue(retired.get(), "A removed helper must no longer be visible to peer reconciliation");
            helperRemoved.set(true);
            return null;
        }).when(lifecycle).removeIfExistsStrict(TEST_HELPER_ID);
        doAnswer(invocation -> {
            // The service releases ENIs while container teardown is still in progress.
            instance.getNetworkInterfaces().clear();
            if (removals.incrementAndGet() == 1 && transientFailure) {
                throw new DockerException("container removal is still completing", 500);
            }
            return null;
        }).when(remove).exec();
        CountDownLatch completed = new CountDownLatch(1);
        try {
            manager.terminate(instance, completed::countDown);
            boolean retried = transientFailure || peerRefreshFailure;
            if (retried) {
                // The failed attempt arms the retry timer as its last act, on the teardown worker.
                awaitUntil(() -> time.pendingTasks() == 1, Duration.ofSeconds(5));
                // A removal that succeeded was followed by the half second the network teardown lags it.
                Instant failedAt = TestTime.DEFAULT_START.plusMillis(peerRefreshFailure ? 500 : 0);
                assertEquals(failedAt, time.now());
                time.advance(Duration.ofMillis(999));
                assertEquals(1, removals.get(), "The first retry waits a full second");
                assertEquals(1, completed.getCount());
                time.advance(Duration.ofMillis(1));
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS), "Container teardown must finish");
            assertEquals("terminated", instance.getState().getName());
            assertTrue(helperRemoved.get());
            verify(firewall).captureUnregister("eni-firewall-teardown");
            verify(firewallCleanup, times(peerRefreshFailure ? 2 : 1)).run();
            assertEquals(transientFailure ? 2 : 1, removals.get());
            // A retry retains successful workload removal and its completed half-second teardown pause.
            assertEquals(TestTime.DEFAULT_START.plusMillis(retried ? 1_500 : 500), time.now());
            assertEquals(time.clock().millis(), instance.getTerminatedAt());
        } finally {
            manager.stop();
        }
    }

    @Test
    void terminationWaitsForPeerRefreshBeforeReportingItsAddressReusable() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
        when(firewall.enabled()).thenReturn(true);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        VpcNetworkManager vpc = mock(VpcNetworkManager.class);
        TestTime time = TestTime.create();
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class), lifecycle,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                mock(DockerHostResolver.class), docker, mock(PortAllocator.class), config,
                mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class), vpc,
                mock(ContainerReachableEndpoint.class), firewall, new Ec2InstanceTypeCatalog(),
                timelinePause(time), time.newScheduler(), time::nanoTime, time.clock());
        Instance instance = leasedInstance("i-slow-peer-refresh");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setNetworkInterfaceId("eni-slow-peer-refresh");
        instance.setNetworkInterfaces(new ArrayList<>(List.of(eni)));
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse inspected = inspectResponse("172.18.0.12");
        when(inspected.getHostConfig()).thenReturn(
                HostConfig.newHostConfig().withNetworkMode("container:" + TEST_HELPER_ID));
        when(inspect.exec()).thenReturn(inspected);
        when(docker.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerCmd inspectHelper = mock(InspectContainerCmd.class);
        InspectContainerResponse helper = inspectResponse("172.18.0.12");
        when(helper.getConfig()).thenReturn(new ContainerConfig().withLabels(Map.of(
                "io.floci.security-group.helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", instance.getInstanceId(),
                "io.floci.owner", "4566")));
        when(inspectHelper.exec()).thenReturn(helper);
        when(docker.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspectHelper);
        CountDownLatch refreshing = new CountDownLatch(1);
        CountDownLatch finishRefresh = new CountDownLatch(1);
        when(firewall.captureUnregister("eni-slow-peer-refresh")).thenReturn(() -> {
            refreshing.countDown();
            try {
                assertTrue(finishRefresh.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        CountDownLatch completed = new CountDownLatch(1);
        try {
            manager.terminate(instance, completed::countDown);
            assertTrue(refreshing.await(5, TimeUnit.SECONDS), "The peer refresh must start");

            assertEquals("shutting-down", instance.getState().getName(),
                    "Termination must not report address reuse before peer cleanup finishes");
            assertEquals(0, instance.getTerminatedAt());
            verify(vpc, never()).releasePrivateIp(any(), any(), any(), any());
            verify(lifecycle, never()).removeIfExistsStrict(TEST_HELPER_ID);
            assertEquals(1, completed.getCount());

            finishRefresh.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS), "Container teardown must finish");
            verify(lifecycle).removeIfExistsStrict(TEST_HELPER_ID);
            verify(vpc).releasePrivateIp("000000000000", "us-west-2", "subnet-lease", "10.0.1.10");
            assertEquals("terminated", instance.getState().getName());
            assertEquals(time.clock().millis(), instance.getTerminatedAt());
        } finally {
            finishRefresh.countDown();
            manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void terminationCannotRemoveAReplacementRegisteredBeforeDockerCleanup(boolean replace) throws Exception {
        DockerClient docker = mock(DockerClient.class);
        ContainerLifecycleManager firewallLifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager firewall = spy(SecurityGroupDnsFixture.rawManager(
                SecurityGroupDnsFixture.policyDocker(mock(DockerClient.class, RETURNS_DEEP_STUBS)),
                mock(ContainerBuilder.class), firewallLifecycle,
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(EmbeddedDnsServer.class)));
        doNothing().when(firewall).reconcileAll();
        doNothing().when(firewall).admit(anyString(), anyBoolean());
        doReturn(true).when(firewall).enabled();
        firewall.register(new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", "vpc-old",
                "eni-firewall-teardown", "10.0.1.10", "172.18.0.2", Set.of(), List.of()), "old-helper", Map.of());
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        TestTime time = TestTime.create();
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                mock(DockerHostResolver.class), docker, mock(PortAllocator.class), config,
                mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class), firewall, new Ec2InstanceTypeCatalog(),
                timelinePause(time));
        Instance instance = instance("i-firewall-teardown");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setNetworkInterfaceId("eni-firewall-teardown");
        instance.setNetworkInterfaces(new ArrayList<>(List.of(eni)));
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse inspected = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(inspected);
        when(docker.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        doAnswer(invocation -> {
            // The service releases ENIs while container teardown is still in progress.
            instance.getNetworkInterfaces().clear();
            if (replace) {
                firewall.register(new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", "vpc-new",
                        "eni-firewall-teardown", "10.0.1.10", "172.18.0.3", Set.of(), List.of()),
                        "new-helper", Map.of());
            }
            return null;
        }).when(remove).exec();
        CountDownLatch completed = new CountDownLatch(1);
        try {
            manager.terminate(instance, completed::countDown);
            assertTrue(completed.await(5, TimeUnit.SECONDS), "Container teardown must finish");
            assertEquals("terminated", instance.getState().getName());
            verify(firewallLifecycle).removeIfExistsStrict("old-helper");
            verify(firewallLifecycle, never()).removeIfExistsStrict("new-helper");
            assertEquals(replace, firewall.vpcForClient("172.18.0.3").isPresent());
            assertEquals(TestTime.DEFAULT_START.plusMillis(500), time.now());
        } finally {
            manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledLaunchRetainsCleanupOwnershipUntilFirewallRetirementSucceeds(boolean transientFailure) {
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        PortAllocator ports = mock(PortAllocator.class);
        VpcNetworkManager network = mock(VpcNetworkManager.class);
        Ec2MetadataServer metadata = mock(Ec2MetadataServer.class);
        when(firewall.enabled()).thenReturn(true);
        Runnable cleanup = mock(Runnable.class);
        when(firewall.captureUnregister("eni-original")).thenReturn(cleanup);
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class), lifecycle, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), mock(DockerHostResolver.class), mock(DockerClient.class),
                ports, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), metadata, mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class), mock(ContainerNetworkReachability.class), network,
                mock(ContainerReachableEndpoint.class), firewall);
        Instance instance = instance("i-failed-launch-retirement");
        instance.setOwnerId("111122223333");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setSshHostPort(2201);
        instance.setContainerBridgeIp("172.18.0.12");
        instance.setPrivateIpAddress("10.0.0.12");
        instance.setRegion("us-east-1");
        instance.setVpcId("vpc-original");
        instance.setSubnetId("subnet-original");
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setNetworkInterfaceId("eni-original");
        instance.setNetworkInterfaces(new ArrayList<>(List.of(eni)));
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1 && transientFailure) {
                throw new IllegalStateException("peer retirement failed");
            }
            return null;
        }).when(cleanup).run();
        try {
            if (transientFailure) {
                assertThrows(IllegalStateException.class, () -> manager.cancelLaunch(instance));
                assertEquals("terminated", instance.getState().getName());
                verify(ports, never()).release(anyInt());
                verify(network, never()).releasePrivateIp(any(), any(), any(), any());
                verify(network, never()).detach(any(), any(), any(), any());
                verify(lifecycle).removeIfExistsStrict(TEST_CONTAINER_ID);
                verify(lifecycle, never()).removeIfExistsStrict(TEST_HELPER_ID);
                instance.getNetworkInterfaces().clear();
                instance.setPrivateIpAddress(null);
                instance.setSubnetId(null);
            }
            assertTrue(manager.cancelLaunch(instance));
            assertTrue(manager.cancelLaunch(instance));
            verify(firewall).captureUnregister("eni-original");
            verify(cleanup, times(transientFailure ? 2 : 1)).run();
            verify(ports, times(1)).release(2201);
            verify(network, times(1)).detach("111122223333", "us-east-1", "vpc-original", TEST_CONTAINER_ID);
            verify(network, times(1)).releasePrivateIp("111122223333", "us-east-1", "subnet-original", "10.0.0.12");
            verify(metadata, times(1)).unregisterContainer("172.18.0.12", instance);
            assertNull(instance.getDockerContainerId());
            assertEquals(0, instance.getSshHostPort());
        } finally {
            manager.stop();
        }
    }

    private static final List<String> TEST_SYSTEMD_INIT_COMMAND = List.of("/sbin/init", "systemd.mask=getty@.service", "systemd.mask=autovt@.service",
                "systemd.mask=console-getty.service", "systemd.mask=container-getty@.service",
                "systemd.mask=serial-getty@.service", "systemd.mask=getty-static.service");
    private static final String TEST_USER_DATA_OUTPUT = "test-output";
    private static final String TEST_CONTAINER_ID = "container-1";
    private static final String TEST_HELPER_ID = "metadata-helper-1";
    private static final String TEST_LOG_STREAM_NAME = "yyyy/MM/dd/user-data";
    private static final String TEST_SSH_PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest test@floci";

    @Test
    void exposeReachablePrivateAddressUpdatesInstanceAndAttachedNetworkInterfaces() {
        Instance instance = new Instance();
        instance.setPrivateIpAddress("10.82.32.10");
        instance.setPrivateDnsName("ip-10-82-32-10.ec2.internal");

        InstanceNetworkInterface networkInterface = new InstanceNetworkInterface();
        networkInterface.setPrivateIpAddress("10.82.32.10");
        networkInterface.setPrivateDnsName("ip-10-82-32-10.ec2.internal");
        instance.setNetworkInterfaces(List.of(networkInterface));

        Ec2ContainerManager.exposeReachablePrivateAddress(instance, "192.168.215.21");

        assertEquals("192.168.215.21", instance.getPrivateIpAddress());
        assertEquals("ip-192-168-215-21.ec2.internal", instance.getPrivateDnsName());
        assertEquals("192.168.215.21", networkInterface.getPrivateIpAddress());
        assertEquals("ip-192-168-215-21.ec2.internal", networkInterface.getPrivateDnsName());
    }

    @Test
    void exposeReachablePrivateAddressUsesInstanceRegion() {
        Instance instance = new Instance();
        instance.setRegion("us-west-2");
        InstanceNetworkInterface networkInterface = new InstanceNetworkInterface();
        instance.setNetworkInterfaces(List.of(networkInterface));

        Ec2ContainerManager.exposeReachablePrivateAddress(instance, "192.168.215.21");

        assertEquals("ip-192-168-215-21.us-west-2.compute.internal", instance.getPrivateDnsName());
        assertEquals(instance.getPrivateDnsName(), networkInterface.getPrivateDnsName());
    }

    @Test
    void exposeReachablePrivateAddressPreservesAllocatedIpWhenAwsFaithful() {
        // #1983: with awsFaithfulPrivateIp=true, the CFN/subnet-allocated private
        // IP set at launch is left untouched — the container bridge IP is not
        // reported (routing still uses it via containerBridgeIp, tracked elsewhere).
        Instance instance = new Instance();
        instance.setPrivateIpAddress("10.82.32.10");
        instance.setPrivateDnsName("ip-10-82-32-10.ec2.internal");

        InstanceNetworkInterface networkInterface = new InstanceNetworkInterface();
        networkInterface.setPrivateIpAddress("10.82.32.10");
        networkInterface.setPrivateDnsName("ip-10-82-32-10.ec2.internal");
        instance.setNetworkInterfaces(List.of(networkInterface));

        Ec2ContainerManager.exposeReachablePrivateAddress(instance, "192.168.215.21", true);

        assertEquals("10.82.32.10", instance.getPrivateIpAddress());
        assertEquals("ip-10-82-32-10.ec2.internal", instance.getPrivateDnsName());
        assertEquals("10.82.32.10", networkInterface.getPrivateIpAddress());
    }

    @Test
    void restoreMetadataRegistrationRegistersRunningPersistedContainer() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = inspectResponse("192.168.215.42");
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class),
                lifecycleManager,
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                mock(DockerHostResolver.class),
                dockerClient,
                mock(PortAllocator.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                metadataServer,
                mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class),
                mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class));

        Instance instance = new Instance();
        instance.setInstanceId("i-restored");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("192.168.215.7");

        assertTrue(manager.restoreMetadataRegistration(instance));

        assertEquals("192.168.215.42", instance.getContainerBridgeIp());
        assertEquals("192.168.215.42", instance.getPrivateIpAddress());
        verify(metadataServer).unregisterContainer("192.168.215.7", instance);
        verify(metadataServer).registerContainer("192.168.215.42", "i-restored", instance);
    }

    @Test
    void restoreMetadataRegistrationAlsoRegistersTheAddressImdsRequestsArriveFrom() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        InspectContainerResponse response = vpcAttachedInspectResponse("10.0.1.10", "172.17.0.4");
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = managerWith(lifecycleManager, dockerClient, metadataServer);

        Instance instance = new Instance();
        instance.setInstanceId("i-vpc-restored");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("10.0.1.10");
        instance.setImdsSourceIp("172.17.0.2");

        assertTrue(manager.restoreMetadataRegistration(instance));

        // The VPC address is what Floci reports, but the container's default route is the bridge,
        // so that is the source address Ec2MetadataServer resolves the instance by.
        verify(metadataServer).registerContainer("10.0.1.10", "i-vpc-restored", instance);
        verify(metadataServer).registerContainer("172.17.0.4", "i-vpc-restored", instance);
        verify(metadataServer).unregisterContainer("172.17.0.2", instance);
        assertEquals("172.17.0.4", instance.getImdsSourceIp());
    }

    @Test
    void restoreRegistersAllNetworksAndReplacesStaleSharedAddress() {
        for (boolean sharedFirst : List.of(true, false)) {
            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            when(lifecycle.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);
            DockerClient docker = mock(DockerClient.class);
            InspectContainerCmd command = mock(InspectContainerCmd.class);
            when(docker.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(command);
            InspectContainerResponse response = mock(InspectContainerResponse.class);
            NetworkSettings settings = mock(NetworkSettings.class);
            when(response.getNetworkSettings()).thenReturn(settings);
            Map<String, ContainerNetwork> networks = new LinkedHashMap<>();
            networks.put("bridge", new ContainerNetwork().withIpv4Address("172.17.0.4"));
            String first = sharedFirst ? "shared" : "vpc";
            String second = sharedFirst ? "vpc" : "shared";
            networks.put(first, new ContainerNetwork().withIpv4Address(sharedFirst ? "192.0.2.10" : "10.0.1.10"));
            networks.put(second, new ContainerNetwork().withIpv4Address(sharedFirst ? "10.0.1.10" : "192.0.2.10"));
            when(settings.getNetworks()).thenReturn(networks);
            when(command.exec()).thenReturn(response);
            Ec2MetadataServer server = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
            Ec2ContainerManager manager = managerWith(lifecycle, docker, server);
            Instance guest = instance("i-multinetwork");
            guest.setDockerContainerId(TEST_CONTAINER_ID);
            server.registerContainer("192.0.2.9", guest.getInstanceId(), guest);

            assertTrue(manager.restoreMetadataRegistration(guest));

            for (String address : List.of("172.17.0.4", "10.0.1.10", "192.0.2.10")) {
                assertEquals(guest, server.registeredContainer(address).orElseThrow());
            }
            assertTrue(server.registeredContainer("192.0.2.9").isEmpty());
        }
    }

    @Test
    void restoreMetadataRegistrationLeavesABridgeOnlyInstanceWithNoSeparateImdsSource() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        InspectContainerResponse response = inspectResponse("172.17.0.4");
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = managerWith(lifecycleManager, dockerClient, metadataServer);

        Instance instance = new Instance();
        instance.setInstanceId("i-bridge-only");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("172.17.0.4");

        assertTrue(manager.restoreMetadataRegistration(instance));

        assertNull(instance.getImdsSourceIp(), "one address means one registration");
        verify(metadataServer).registerContainer("172.17.0.4", "i-bridge-only", instance);
        verify(metadataServer, never()).unregisterContainer(anyString(), any(Instance.class));
    }

    @Test
    void restoreMetadataRegistrationKeepsTheImdsSourceWhenTheContainerCannotBeInspected() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new DockerException("dial unix /var/run/docker.sock: EOF", 500));

        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = managerWith(lifecycleManager, dockerClient, metadataServer);

        Instance instance = new Instance();
        instance.setInstanceId("i-inspect-failed");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("10.0.1.10");
        instance.setImdsSourceIp("172.17.0.4");

        assertTrue(manager.restoreMetadataRegistration(instance));

        // A failed inspect leaves the bridge attachment unknown, not known to be absent. Tearing
        // the registration down here would stop this healthy instance's metadata requests
        // resolving, with nothing registered in place of what was removed.
        verify(metadataServer, never()).unregisterContainer("172.17.0.4", instance);
        verify(metadataServer, never()).reconcileContainerAddresses(anySet(), any());
        assertEquals("172.17.0.4", instance.getImdsSourceIp(),
                "a transient Docker failure must not drop an existing IMDS source registration");
    }

    @Test
    void restartWaitsForPhysicalStopBeforeStartingTheContainer() throws InterruptedException {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);
        DockerClient docker = mock(DockerClient.class);
        when(docker.startContainerCmd(TEST_CONTAINER_ID)).thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = vpcAttachedInspectResponse("10.0.1.10", "172.17.0.9");
        when(inspect.exec()).thenReturn(response);
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        when(docker.stopContainerCmd(TEST_CONTAINER_ID)).thenReturn(stop);
        ExecutorService executor = mock(ExecutorService.class);
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tasks.add(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));
        Ec2ContainerManager manager = new Ec2ContainerManager(mock(ContainerBuilder.class), lifecycle,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), mock(DockerHostResolver.class),
                docker, mock(PortAllocator.class), mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class), executor, Duration.ofSeconds(5));
        Instance instance = new Instance();
        instance.setInstanceId("i-stop-restart");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        List<String> operations = new ArrayList<>();
        AtomicBoolean physicalRunning = new AtomicBoolean(true);
        when(lifecycle.isContainerRunning(TEST_CONTAINER_ID)).thenAnswer(invocation -> physicalRunning.get());
        doAnswer(invocation -> {
            operations.add("stop");
            physicalRunning.set(false);
            return null;
        }).when(stop).exec();
        StartContainerCmd start = docker.startContainerCmd(TEST_CONTAINER_ID);
        doAnswer(invocation -> {
            operations.add("start");
            physicalRunning.set(true);
            return null;
        }).when(start).exec();

        try {
            manager.stop(instance);
            manager.start(instance);
            assertEquals(1, tasks.size(), "start must wait until the physical stop completes");
            tasks.getFirst().run();
            assertEquals(List.of("stop"), operations);
            awaitUntil(() -> tasks.size() == 2, Duration.ofSeconds(2));
            tasks.get(1).run();

            assertEquals(List.of("stop", "start"), operations);
            assertTrue(physicalRunning.get());
            assertEquals("running", instance.getState().getName());
        } finally {
            manager.stop();
        }
    }

    @Test
    void startRegistersTheNewBridgeAddressDockerHandsOutOnRestart() throws Exception {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        List<String[]> execs = recordExecs(dockerClient);
        when(dockerClient.startContainerCmd(TEST_CONTAINER_ID))
                .thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
        // The VPC address is static, so it survives the stop; the bridge address does not.
        InspectContainerResponse response = vpcAttachedInspectResponse("10.0.1.10", "172.17.0.9");
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = managerWith(lifecycleManager, dockerClient, metadataServer);

        Instance instance = new Instance();
        instance.setInstanceId("i-vpc-started");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("10.0.1.10");
        instance.setImdsSourceIp("172.17.0.4");

        manager.start(instance);
        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(5));

        verify(metadataServer, timeout(2000)).registerContainer("172.17.0.9", "i-vpc-started", instance);
        verify(metadataServer, timeout(2000)).unregisterContainer("172.17.0.4", instance);
        verify(metadataServer, timeout(2000)).reconcileContainerAddresses(
                Set.of("10.0.1.10", "172.17.0.9"), instance);
        assertEquals("172.17.0.9", instance.getImdsSourceIp(),
                "the stale address would otherwise stay registered while IMDS requests arrive "
                        + "from an address nothing knows about");
        // The restarted container's namespace is new: the link-local IMDS endpoint is set up again.
        awaitUntil(() -> execs.stream().anyMatch(cmd -> Arrays.equals(cmd,
                Ec2ContainerManager.metadataProxyInstallCommand())), Duration.ofSeconds(2));
    }

    @Test
    void startSetsTheImdsEndpointUpOnlyAfterTheInstanceIsRunning() throws Exception {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        Instance instance = new Instance();
        instance.setInstanceId("i-imds-after-running");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("10.0.1.10");

        // Setup is best effort and can take minutes on a slow image; it must not hold the
        // instance pending or delay the post-start hook.
        List<String> events = new CopyOnWriteArrayList<>();
        DockerClient dockerClient = mock(DockerClient.class);
        recordExecs(dockerClient, () -> events.add("exec while " + instance.getState().getName()));
        when(dockerClient.startContainerCmd(TEST_CONTAINER_ID))
                .thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
        InspectContainerResponse response = vpcAttachedInspectResponse("10.0.1.10", "172.17.0.9");
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        Ec2ContainerManager manager = managerWith(lifecycleManager, dockerClient, mock(Ec2MetadataServer.class));

        manager.start(instance, () -> events.add("postStart"));

        awaitUntil(() -> events.stream().anyMatch(event -> event.startsWith("exec")), Duration.ofSeconds(5));
        assertEquals(List.of("postStart", "exec while running"), events.subList(0, 2), events.toString());
    }

    @Test
    void stopDuringASlowImdsSetupAfterStartDoesNotWaitForTheSetup() throws Exception {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        // The package install hangs, as it can on a slow mirror, until the test lets it fail.
        CountDownLatch setupEntered = new CountDownLatch(1);
        CountDownLatch releaseSetup = new CountDownLatch(1);
        ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(dockerClient.execCreateCmd(TEST_CONTAINER_ID)).thenReturn(execCreate);
        when(execCreate.exec()).thenAnswer(invocation -> {
            setupEntered.countDown();
            releaseSetup.await(10, TimeUnit.SECONDS);
            throw new IllegalStateException("no exec in this test");
        });
        when(dockerClient.startContainerCmd(TEST_CONTAINER_ID))
                .thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
        when(dockerClient.stopContainerCmd(TEST_CONTAINER_ID)).thenReturn(mock(StopContainerCmd.class, RETURNS_SELF));
        InspectContainerResponse response = vpcAttachedInspectResponse("10.0.1.10", "172.17.0.9");
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        Ec2ContainerManager manager = managerWith(lifecycleManager, dockerClient, mock(Ec2MetadataServer.class));

        Instance instance = new Instance();
        instance.setInstanceId("i-stop-during-imds-setup");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("10.0.1.10");
        try {
            manager.start(instance);
            assertTrue(setupEntered.await(5, TimeUnit.SECONDS), "the IMDS setup never began");

            manager.stop(instance);

            awaitUntil(() -> "stopped".equals(instance.getState().getName()), Duration.ofSeconds(3));
            verify(dockerClient).stopContainerCmd(TEST_CONTAINER_ID);
        } finally {
            releaseSetup.countDown();
        }
    }

    /**
     * The start runs on a launch-executor worker. Handing the IMDS setup to that same executor
     * while its queue is full blocks the worker in backpressure, and with every worker doing so
     * nothing drains the queue: here one worker and a one-slot queue stand in for eight and 64.
     */
    @Test
    void startHandsTheImdsSetupOffWithoutBlockingALaunchWorkerOnAFullQueue() throws Exception {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);
        DockerClient docker = mock(DockerClient.class);
        List<String[]> execs = recordExecs(docker);
        when(docker.startContainerCmd(TEST_CONTAINER_ID)).thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = vpcAttachedInspectResponse("10.0.1.10", "172.17.0.9");
        when(inspect.exec()).thenReturn(response);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), Ec2ContainerManager.BLOCKING_BACKPRESSURE);
        try {
            Ec2ContainerManager manager = new Ec2ContainerManager(mock(ContainerBuilder.class), lifecycle,
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class), mock(DockerHostResolver.class),
                    docker, mock(PortAllocator.class), mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                    mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), mock(RegionResolver.class),
                    mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                    mock(ContainerReachableEndpoint.class), executor, Duration.ofSeconds(5));
            Instance instance = new Instance();
            instance.setInstanceId("i-full-launch-queue");
            instance.setDockerContainerId(TEST_CONTAINER_ID);
            instance.setContainerBridgeIp("10.0.1.10");
            CountDownLatch postStartEntered = new CountDownLatch(1);
            CountDownLatch queueFull = new CountDownLatch(1);
            CountDownLatch queuedWorkRan = new CountDownLatch(1);

            manager.start(instance, () -> {
                postStartEntered.countDown();
                try {
                    queueFull.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(postStartEntered.await(5, TimeUnit.SECONDS), "the start never reached its post-start hook");
            executor.execute(queuedWorkRan::countDown);
            queueFull.countDown();

            assertTrue(queuedWorkRan.await(3, TimeUnit.SECONDS),
                    "the launch worker running the start never came back to drain its queue");
            awaitUntil(() -> execs.stream().anyMatch(cmd -> Arrays.equals(cmd,
                    Ec2ContainerManager.metadataProxyInstallCommand())), Duration.ofSeconds(3));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void startOfAProtectedInstanceDoesNotSetTheImdsEndpointUpAgain() throws Exception {
        // The helper container kept its network namespace across the stop, and the address with it.
        DockerClient dockerClient = mock(DockerClient.class);
        List<String[]> execs = recordExecs(dockerClient);
        Instance instance = startProtectedInstance(mock(Ec2MetadataServer.class), Map.of(
                "floci.security-group-helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "floci_owner_port", "4566"), dockerClient);

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(5));
        assertTrue(execs.stream().noneMatch(cmd -> Arrays.equals(cmd,
                Ec2ContainerManager.metadataProxyInstallCommand())), "no IMDS setup exec for a protected start");
    }

    @Test
    void startRunsTheImdsProxyAgainOnceItsDependenciesInstall() throws Exception {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);
        DockerClient dockerClient = mock(DockerClient.class);
        List<String[]> execs = recordExecs(dockerClient);
        // Unlike recordExecs alone, every exec here completes with exit 0, so the install
        // succeeds and the setup goes on to start the proxy.
        ExecCreateCmd execCreate = dockerClient.execCreateCmd(TEST_CONTAINER_ID);
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn("imds-exec");
        doReturn(created).when(execCreate).exec();
        ExecStartCmd execStart = mock(ExecStartCmd.class);
        when(dockerClient.execStartCmd("imds-exec")).thenReturn(execStart);
        when(execStart.exec(any())).thenAnswer(invocation -> {
            ResultCallback<?> callback = invocation.getArgument(0);
            callback.onComplete();
            return callback;
        });
        InspectExecCmd inspectExec = mock(InspectExecCmd.class);
        InspectExecResponse inspectExecResponse = mock(InspectExecResponse.class);
        when(inspectExecResponse.getExitCodeLong()).thenReturn(0L);
        when(inspectExec.exec()).thenReturn(inspectExecResponse);
        when(dockerClient.inspectExecCmd("imds-exec")).thenReturn(inspectExec);
        when(dockerClient.startContainerCmd(TEST_CONTAINER_ID))
                .thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = vpcAttachedInspectResponse("10.0.1.10", "172.17.0.9");
        when(inspect.exec()).thenReturn(response);
        DockerHostResolver hostResolver = mock(DockerHostResolver.class);
        when(hostResolver.resolve()).thenReturn("floci-imds-host");
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().imdsPort()).thenReturn(9169);
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class), lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), hostResolver, dockerClient, mock(PortAllocator.class), config,
                mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class));

        Instance instance = new Instance();
        instance.setInstanceId("i-imds-proxy-restart");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("10.0.1.10");

        manager.start(instance);

        String[] proxyStart = Ec2ContainerManager.metadataProxyStartCommand("floci-imds-host", 9169);
        awaitUntil(() -> execs.stream().anyMatch(cmd -> Arrays.equals(cmd, proxyStart)), Duration.ofSeconds(5));
    }

    /** Records every command exec'd in the test container; each exec then fails, which start tolerates. */
    private static List<String[]> recordExecs(DockerClient dockerClient) {
        return recordExecs(dockerClient, TEST_CONTAINER_ID, () -> { });
    }

    /** As {@link #recordExecs(DockerClient)}, running {@code onExec} as each exec is created. */
    private static List<String[]> recordExecs(DockerClient dockerClient, Runnable onExec) {
        return recordExecs(dockerClient, TEST_CONTAINER_ID, onExec);
    }

    @ParameterizedTest
    @CsvSource({"1,,127.0.0.1", "2,,", "2,54.1.2.3,127.0.0.1"})
    void startReplacesAReleasedAddressOnlyWhileTheInstanceHasOneInterface(int interfaces, String kept,
                                                                         String expected) throws Exception {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);
        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.startContainerCmd(TEST_CONTAINER_ID))
                .thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.17.0.9");
        when(inspect.exec()).thenReturn(response);
        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = managerWith(lifecycleManager, dockerClient, metadataServer);

        // A stopped instance whose launch asked for an address: the stop released the automatic
        // one, and an Elastic IP, when there is one, kept its address.
        Instance instance = new Instance();
        instance.setInstanceId("i-restarted");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setAssociatePublicIp(true);
        instance.setPublicIpAddress(kept);
        List<InstanceNetworkInterface> attached = new ArrayList<>();
        for (int i = 0; i < interfaces; i++) {
            attached.add(new InstanceNetworkInterface());
        }
        instance.setNetworkInterfaces(attached);

        manager.start(instance);
        verify(metadataServer, timeout(2000)).registerContainer("172.17.0.9", "i-restarted", instance);

        // As on AWS, a released address is not replaced while a second interface is attached.
        assertEquals(expected, instance.getPublicIpAddress());
    }

    @Test
    void startOfAProtectedInstanceReusesTheHelperTransportAddress() throws Exception {
        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Instance instance = startProtectedInstance(metadataServer, Map.of(
                "floci.security-group-helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "floci_owner_port", "4566"));

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(5));
        verify(metadataServer, timeout(2000)).registerContainer("172.17.0.7", "i-protected", instance);
        assertEquals("172.17.0.7", instance.getContainerBridgeIp(),
                "StartInstances must keep addressing the instance through its protected namespace");
    }

    @Test
    void startOfAnInstanceRestoresItsMetadataOnlyNamespace() throws Exception {
        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Instance instance = startProtectedInstance(metadataServer, Map.of(
                Ec2MetadataNamespace.HELPER_LABEL, "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "io.floci.owner", "4566"));

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(5));
        verify(metadataServer, atLeastOnce()).registerContainer("172.17.0.7", "i-protected", instance);
        assertEquals("172.17.0.7", instance.getContainerBridgeIp());
    }

    @Test
    void enablingSecurityGroupsRefusesAPreviouslyUnprotectedNamespace() throws Exception {
        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Instance instance = startProtectedInstance(metadataServer, Map.of(
                Ec2MetadataNamespace.HELPER_LABEL, "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "io.floci.owner", "4566"), true);

        awaitUntil(() -> "stopped".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(metadataServer, never()).registerContainer(anyString(), anyString(), any());
    }

    @Test
    void startOfAProtectedInstanceAcceptsAHelperLabelledWithTheNewKeys() throws Exception {
        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Instance instance = startProtectedInstance(metadataServer, Map.of(
                "io.floci.security-group.helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "io.floci.owner", "4566"));

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(5));
        verify(metadataServer, timeout(2000)).registerContainer("172.17.0.7", "i-protected", instance);
    }

    @Test
    void startOfAProtectedInstanceAcceptsAHelperWhoseNewAndLegacyKeysAgree() throws Exception {
        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Instance instance = startProtectedInstance(metadataServer, Map.of(
                "io.floci.security-group.helper", "true",
                "floci.security-group-helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "io.floci.owner", "4566",
                "floci_owner_port", "4566"));

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(5));
        verify(metadataServer, timeout(2000)).registerContainer("172.17.0.7", "i-protected", instance);
    }

    @Test
    void startOfAProtectedInstanceRefusesAHelperWhoseNewAndLegacyOwnersDisagree() throws Exception {
        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Instance instance = startProtectedInstance(metadataServer, Map.of(
                "io.floci.security-group.helper", "true",
                "floci.security-group-helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "io.floci.owner", "4566",
                "floci_owner_port", "4567"));

        awaitUntil(() -> "stopped".equals(instance.getState().getName()), Duration.ofSeconds(5));
        verify(metadataServer, never()).registerContainer(anyString(), anyString(), any());
    }

    private Instance startProtectedInstance(Ec2MetadataServer metadataServer, Map<String, String> helperLabels) {
        return startProtectedInstance(metadataServer, helperLabels, mock(DockerClient.class), false);
    }

    private Instance startProtectedInstance(Ec2MetadataServer metadataServer, Map<String, String> helperLabels,
                                            boolean enforceSecurityGroups) {
        return startProtectedInstance(metadataServer, helperLabels, mock(DockerClient.class), enforceSecurityGroups);
    }

    private Instance startProtectedInstance(Ec2MetadataServer metadataServer, Map<String, String> helperLabels,
                                            DockerClient dockerClient) {
        return startProtectedInstance(metadataServer, helperLabels, dockerClient, false);
    }

    private Instance startProtectedInstance(Ec2MetadataServer metadataServer, Map<String, String> helperLabels,
                                            DockerClient dockerClient, boolean enforceSecurityGroups) {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        when(dockerClient.startContainerCmd(TEST_CONTAINER_ID))
                .thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
        // A workload in the helper's namespace has no Docker attachment of its own, so there is
        // no bridge address on it to rediscover after the restart.
        InspectContainerResponse workload = mock(InspectContainerResponse.class);
        HostConfig hostConfig = mock(HostConfig.class);
        when(hostConfig.getNetworkMode()).thenReturn("container:helper-1");
        when(workload.getHostConfig()).thenReturn(hostConfig);
        InspectContainerCmd inspectWorkload = mock(InspectContainerCmd.class);
        when(inspectWorkload.exec()).thenReturn(workload);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspectWorkload);

        InspectContainerResponse helper = mock(InspectContainerResponse.class);
        ContainerConfig helperConfig = mock(ContainerConfig.class);
        when(helperConfig.getLabels()).thenReturn(helperLabels);
        when(helper.getConfig()).thenReturn(helperConfig);
        NetworkSettings helperNetworks = mock(NetworkSettings.class);
        when(helperNetworks.getNetworks())
                .thenReturn(Map.of("bridge", new ContainerNetwork().withIpv4Address("172.17.0.7")));
        when(helper.getNetworkSettings()).thenReturn(helperNetworks);
        InspectContainerCmd inspectHelper = mock(InspectContainerCmd.class);
        when(inspectHelper.exec()).thenReturn(helper);
        when(dockerClient.inspectContainerCmd("helper-1")).thenReturn(inspectHelper);

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.services().ec2().imdsPort()).thenReturn(9169);
        DockerHostResolver hostResolver = mock(DockerHostResolver.class);
        when(hostResolver.resolve()).thenReturn("floci");
        stubMetadataExecs(dockerClient, "helper-1");
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
        when(firewall.enabled()).thenReturn(enforceSecurityGroups);

        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class),
                lifecycleManager,
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                hostResolver,
                dockerClient,
                mock(PortAllocator.class),
                config,
                metadataServer,
                mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class),
                mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class),
                firewall);

        Instance instance = new Instance();
        instance.setInstanceId("i-protected");
        instance.setDockerContainerId(TEST_CONTAINER_ID);

        manager.start(instance);
        return instance;
    }

    private static Ec2ContainerManager managerWith(ContainerLifecycleManager lifecycleManager,
                                                   DockerClient dockerClient,
                                                   Ec2MetadataServer metadataServer) {
        return new Ec2ContainerManager(
                mock(ContainerBuilder.class),
                lifecycleManager,
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                mock(DockerHostResolver.class),
                dockerClient,
                mock(PortAllocator.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                metadataServer,
                mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class),
                mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class));
    }

    /** A container on both its VPC network and the default bridge, which is what launch leaves. */
    private static InspectContainerResponse vpcAttachedInspectResponse(String vpcIp, String bridgeIp) {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        NetworkSettings networkSettings = mock(NetworkSettings.class);
        when(inspect.getNetworkSettings()).thenReturn(networkSettings);
        when(networkSettings.getNetworks()).thenReturn(Map.of(
                "floci-vpc-4566-us-west-2-vpc-1", new ContainerNetwork().withIpv4Address(vpcIp),
                "bridge", new ContainerNetwork().withIpv4Address(bridgeIp)));
        return inspect;
    }

    @Test
    void userDataExecutionCommandRunsScriptDirectlySoShebangIsHonored() {
        assertArrayEquals(new String[]{"/var/lib/user-data.sh"}, Ec2ContainerManager.userDataExecutionCommand());
    }

    @Test
    void userDataOutputSummaryRetainsBoundedTailAndReportsTruncation() throws Exception {
        Ec2ContainerManager.BoundedOutput output = new Ec2ContainerManager.BoundedOutput(8);
        output.write("0123456789".getBytes(StandardCharsets.UTF_8));

        assertEquals("(output truncated; showing last 8 bytes)\n23456789",
                Ec2ContainerManager.summarizeUserDataOutput(output));
    }

    @Test
    void userDataOutputSummaryRetainsTailAcrossFrames() throws Exception {
        Ec2ContainerManager.BoundedOutput output = new Ec2ContainerManager.BoundedOutput(8);
        output.write("1234".getBytes(StandardCharsets.UTF_8));
        output.write("567890".getBytes(StandardCharsets.UTF_8));

        assertEquals("(output truncated; showing last 8 bytes)\n34567890",
                Ec2ContainerManager.summarizeUserDataOutput(output));
    }

    @Test
    void userDataOutputSummaryPreservesOutputWithinLimit() throws Exception {
        Ec2ContainerManager.BoundedOutput output = new Ec2ContainerManager.BoundedOutput(8);
        output.write("🙂".getBytes(StandardCharsets.UTF_8));

        assertEquals("🙂", Ec2ContainerManager.summarizeUserDataOutput(output));
    }

    @Test
    void userDataOutputSummaryStartsAtUtf8CharacterBoundary() throws Exception {
        Ec2ContainerManager.BoundedOutput output = new Ec2ContainerManager.BoundedOutput(4);
        output.write("🙂YZ".getBytes(StandardCharsets.UTF_8));

        assertEquals("(output truncated; showing last 4 bytes)\nYZ",
                Ec2ContainerManager.summarizeUserDataOutput(output));
    }

    @Test
    void userDataShellScriptsPreservesRawShellScript() {
        String script = "#!/bin/bash\nset -euo pipefail\necho ready\n";

        assertEquals(List.of(script), Ec2ContainerManager.userDataShellScripts(script));
    }

    @Test
    void userDataShellScriptsExtractsMimeShellscriptPartsInOrder() {
        String userData = """
                Content-Type: multipart/mixed; boundary="==SAMPLE-CLOUD-INIT=="
                MIME-Version: 1.0

                --==SAMPLE-CLOUD-INIT==
                Content-Type: text/cloud-config; charset="us-ascii"

                #cloud-config

                --==SAMPLE-CLOUD-INIT==
                Content-Type: text/x-shellscript; charset="us-ascii"

                #!/bin/bash
                echo first

                --==SAMPLE-CLOUD-INIT==
                Content-Type: text/x-shellscript

                #!/bin/sh
                echo second

                --==SAMPLE-CLOUD-INIT==--
                """;

        assertEquals(
                List.of(
                        "#!/bin/bash\necho first\n",
                        "#!/bin/sh\necho second\n"),
                Ec2ContainerManager.userDataShellScripts(userData));
    }

    private static String gzipBase64(String plain) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(plain.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static final String CLOUDINIT_MULTIPART = """
            Content-Type: multipart/mixed; boundary="MIMEBOUNDARY"
            MIME-Version: 1.0

            --MIMEBOUNDARY
            Content-Disposition: attachment; filename="bastion-default-cloud-init"
            Content-Transfer-Encoding: 7bit
            Content-Type: text/x-shellscript
            Mime-Version: 1.0

            #!/bin/bash
            apt-get install -y redis-tools

            --MIMEBOUNDARY--
            """;

    @Test
    void userDataShellScriptsDecodesBase64OfGzippedCloudInit() throws Exception {
        // What data.cloudinit_config { gzip = true, base64_encode = true } renders, as it
        // reaches the launch-configuration path that stores UserData still wire-encoded.
        assertEquals(
                List.of("#!/bin/bash\napt-get install -y redis-tools\n"),
                Ec2ContainerManager.userDataShellScripts(gzipBase64(CLOUDINIT_MULTIPART)));
    }

    @Test
    void userDataShellScriptsDiscardsGzipBombRatherThanExhaustingHeap() throws Exception {
        // A highly-compressible payload well past the 10 MB decompressed cap this guards
        // against: ~50 MB of a repeated character gzips down to a few KB on the wire, so an
        // Auto Scaling launch configuration can smuggle it through in a single UserData field.
        // Without the bound, GZIPInputStream#readAllBytes materializes the full expansion in
        // the shared emulator JVM. With it, decoding gives up and no scripts are extracted.
        String bomb = "A".repeat(50 * 1024 * 1024);

        assertEquals(List.of(), Ec2ContainerManager.userDataShellScripts(gzipBase64(bomb)));
    }

    @Test
    void ec2UserDataPathsShareAggregateConcurrentDecompressionBudget() throws Exception {
        // API decoding and guest execution share the same budget. The hook must see every
        // decompression, and no more than four may run at once across both entry points.
        int concurrentLaunches = 12;
        int budget = 4;
        AtomicInteger totalDecompressions = new AtomicInteger(0);
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger peakActive = new AtomicInteger(0);
        CountDownLatch finishDecompressing = new CountDownLatch(1);
        UserDataPipeline.userDataDecompressionTestHook = () -> {
            totalDecompressions.incrementAndGet();
            int now = active.incrementAndGet();
            peakActive.accumulateAndGet(now, Math::max);
            try {
                finishDecompressing.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        };

        List<List<String>> results = new CopyOnWriteArrayList<>();
        List<Thread> workers = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(concurrentLaunches, task -> {
            Thread worker = new Thread(task);
            workers.add(worker);
            return worker;
        });
        try {
            // ~9 MB of a repeated character: near the 10 MB per-payload cap, well within it.
            String nearCapPayload = "A".repeat(9 * 1024 * 1024);
            String gzipped = gzipBase64(nearCapPayload);

            List<Future<List<String>>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentLaunches; i++) {
                if (i % 2 == 0) {
                    futures.add(pool.submit(() -> Ec2ContainerManager.userDataShellScripts(gzipped)));
                } else {
                    futures.add(pool.submit(() -> List.of(Ec2UserDataDecoder.decode(gzipped))));
                }
            }
            // Hold the first decompressions open until every other caller has reached the budget:
            // all twelve are then either decompressing or queued for a permit, and none is in between.
            awaitUntil(() -> active.get() + queuedForDecompressionBudget(workers) == concurrentLaunches,
                    Duration.ofSeconds(30));
            assertEquals(budget, active.get(), "decompressions running while the other callers queue");
            assertEquals(budget, totalDecompressions.get());
            finishDecompressing.countDown();
            for (Future<List<String>> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            finishDecompressing.countDown();
            pool.shutdownNow();
            UserDataPipeline.userDataDecompressionTestHook = null;
        }

        assertEquals(concurrentLaunches, results.size());
        assertEquals(concurrentLaunches, totalDecompressions.get());
        assertTrue(peakActive.get() <= 4,
                "peak concurrent UserData decompressions was " + peakActive.get() + ", expected <= 4");
        assertTrue(peakActive.get() >= 2,
                "test did not exercise real concurrency; peak concurrent decompressions was only "
                        + peakActive.get());
    }

    private static long queuedForDecompressionBudget(List<Thread> workers) {
        return workers.stream()
                .filter(worker -> worker.getState() == Thread.State.WAITING)
                .filter(worker -> Arrays.stream(worker.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().equals(Semaphore.class.getName())
                                && frame.getMethodName().equals("acquire")))
                .count();
    }

    @Test
    void userDataShellScriptsDecodesPlainBase64ShellScript() {
        String script = "#!/bin/bash\necho ready\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of(script), Ec2ContainerManager.userDataShellScripts(encoded));
    }

    @Test
    void userDataShellScriptsLeavesAccidentallyBase64ShapedScriptAlone() {
        // Valid base64 by shape, but decodes to nothing cloud-init would recognise, so it must
        // be treated as the literal script it is rather than decoded into binary noise.
        String script = "#!/bin/sh\necho deadbeefdeadbeef\n";

        assertEquals(List.of(script), Ec2ContainerManager.userDataShellScripts(script));
    }

    @Test
    void userDataShellScriptsIgnoresGarbage() {
        assertTrue(Ec2ContainerManager.userDataShellScripts("not a script at all").isEmpty());
    }

    @Test
    void reachablePublicAddressReportsContainerIpWhenRoutable() {
        ContainerNetworkReachability reachability = mock(ContainerNetworkReachability.class);
        when(reachability.isContainerIpRoutable("192.168.215.2")).thenReturn(true);
        Ec2ContainerManager manager = managerWithReachability(reachability);

        Instance instance = new Instance();
        instance.setRegion("us-east-1");
        instance.setContainerBridgeIp("192.168.215.2");

        assertEquals("192.168.215.2", manager.reachablePublicAddress(instance));
        manager.exposeReachablePublicAddress(instance);
        assertEquals("192.168.215.2", instance.getPublicIpAddress());
        assertEquals("ec2-192-168-215-2.compute-1.amazonaws.com", instance.getPublicDnsName());
    }

    @Test
    void publicAddressUsesThePrimaryNetworkWhilePrivateAddressStaysOnTheVpc() {
        ContainerNetworkReachability reachability = mock(ContainerNetworkReachability.class);
        when(reachability.isContainerIpRoutable("10.0.1.10")).thenReturn(true);
        when(reachability.isContainerIpRoutable("172.17.0.4")).thenReturn(true);
        Ec2ContainerManager manager = managerWithReachability(reachability);
        Instance instance = new Instance();
        instance.setRegion("eu-west-1");
        instance.setPrivateIpAddress("10.0.1.10");
        instance.setContainerBridgeIp("10.0.1.10");
        instance.setImdsSourceIp("172.17.0.4");
        instance.setContainerPublicIp("172.17.0.4");

        manager.exposeReachablePublicAddress(instance);

        assertEquals("172.17.0.4", instance.getPublicIpAddress());
        assertEquals("ec2-172-17-0-4.eu-west-1.compute.amazonaws.com", instance.getPublicDnsName());
        assertEquals("10.0.1.10", instance.getPrivateIpAddress());
    }

    @Test
    void metadataSourceAloneDoesNotClaimPreparedPublicRouting() {
        ContainerNetworkReachability reachability = mock(ContainerNetworkReachability.class);
        when(reachability.isContainerIpRoutable("10.0.1.10")).thenReturn(true);
        Ec2ContainerManager manager = managerWithReachability(reachability);
        Instance instance = new Instance();
        instance.setContainerBridgeIp("10.0.1.10");
        instance.setImdsSourceIp("172.17.0.4");

        assertEquals("10.0.1.10", manager.reachablePublicAddress(instance));
    }

    @Test
    void reachablePublicAddressFallsBackToLoopbackWhenContainerNetworkIsUnroutable() {
        ContainerNetworkReachability reachability = mock(ContainerNetworkReachability.class);
        when(reachability.isContainerIpRoutable("192.168.215.2")).thenReturn(false);
        Ec2ContainerManager manager = managerWithReachability(reachability);

        Instance instance = new Instance();
        instance.setContainerBridgeIp("192.168.215.2");

        assertEquals("127.0.0.1", manager.reachablePublicAddress(instance));
        manager.exposeReachablePublicAddress(instance);
        assertEquals("127.0.0.1", instance.getPublicIpAddress());
        assertEquals("localhost", instance.getPublicDnsName());
    }

    @Test
    void reachablePublicAddressIsNullBeforeAContainerExists() {
        Ec2ContainerManager manager = managerWithReachability(mock(ContainerNetworkReachability.class));

        assertNull(manager.reachablePublicAddress(new Instance()));
        assertNull(manager.reachablePublicAddress(null));
    }

    @Test
    void reachablePublicAddressReportsTheServiceNetworkAddressWhenEnabledInAContainer() {
        DockerClient docker = guestDocker("172.20.0.9", null);
        Ec2ContainerManager manager = managerOnServiceNetwork(true, true, docker);
        Instance instance = serviceNetworkInstance();

        manager.refreshServiceNetworkIp(instance);

        assertEquals("172.20.0.9", instance.getServiceNetworkIp());
        assertEquals("172.20.0.9", manager.reachablePublicAddress(instance));
    }

    @Test
    void reachablePublicAddressReportsTheRecordedAddressWithoutAskingDocker() {
        DockerClient docker = guestDocker("172.20.0.9", null);
        Ec2ContainerManager manager = managerOnServiceNetwork(true, true, docker);
        Instance instance = serviceNetworkInstance();
        manager.refreshServiceNetworkIp(instance);

        manager.reachablePublicAddress(instance);
        manager.reachablePublicAddress(instance);

        verify(docker, times(1)).inspectContainerCmd(TEST_CONTAINER_ID);
    }

    @Test
    void aFailedLookupIsRetriedInTheBackgroundByTheNextAddressRead() {
        DockerClient docker = guestDocker("172.20.0.9", null);
        InspectContainerResponse guest = docker.inspectContainerCmd(TEST_CONTAINER_ID).exec();
        when(docker.inspectContainerCmd(TEST_CONTAINER_ID).exec())
                .thenThrow(new IllegalStateException("daemon busy")).thenReturn(guest);
        ExecutorService launchExecutor = mock(ExecutorService.class);
        ExecutorService retryExecutor = mock(ExecutorService.class);
        List<Runnable> tasks = new ArrayList<>();
        doAnswer(invocation -> tasks.add(invocation.getArgument(0))).when(retryExecutor).execute(any(Runnable.class));
        Ec2ContainerManager manager = managerOnServiceNetwork(true, true, docker, launchExecutor, retryExecutor);
        Instance instance = serviceNetworkInstance();
        manager.refreshServiceNetworkIp(instance);
        clearInvocations(docker);

        assertEquals("127.0.0.1", manager.reachablePublicAddress(instance));
        assertEquals("127.0.0.1", manager.reachablePublicAddress(instance));
        verify(docker, never()).inspectContainerCmd(anyString());
        assertEquals(1, tasks.size());

        tasks.getFirst().run();

        assertEquals("172.20.0.9", manager.reachablePublicAddress(instance));
        assertEquals(1, tasks.size());
        // Never on the launch pool, whose blocking backpressure could stall the workers behind it.
        verify(launchExecutor, never()).execute(any(Runnable.class));
        verify(launchExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    void aLookupThatOutlivesAContainerReplacementDoesNotOverwriteTheNewAddress() throws Exception {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        InspectContainerResponse oldGuest = inspectWithNetworks(
                Map.of("floci-net", new ContainerNetwork().withIpv4Address("172.20.0.9")));
        InspectContainerResponse newGuest = inspectWithNetworks(
                Map.of("floci-net", new ContainerNetwork().withIpv4Address("172.20.0.21")));
        PausedLookup stale = PausedLookup.onInspect(docker, "old-container", oldGuest, oldGuest);
        when(docker.inspectContainerCmd("new-container").exec()).thenReturn(newGuest);
        Ec2ContainerManager manager = managerOnServiceNetwork(true, true, docker);
        Instance instance = serviceNetworkInstance();
        instance.setDockerContainerId("old-container");

        stale.start(manager, instance);
        synchronized (instance) {
            instance.setDockerContainerId("new-container");
        }
        manager.refreshServiceNetworkIp(instance);
        stale.finish();

        assertEquals("172.20.0.21", instance.getServiceNetworkIp());
        assertEquals("172.20.0.21", manager.reachablePublicAddress(instance));
    }

    @Test
    void aLookupInFlightDuringTeardownCannotRestoreTheAddress() throws Exception {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        InspectContainerResponse guest = inspectWithNetworks(
                Map.of("floci-net", new ContainerNetwork().withIpv4Address("172.20.0.9")));
        PausedLookup stale = PausedLookup.onInspect(docker, TEST_CONTAINER_ID, guest, guest);
        Ec2ContainerManager manager = managerOnServiceNetwork(true, true, docker);
        Instance instance = serviceNetworkInstance();
        instance.setState(InstanceState.pending());

        stale.start(manager, instance);
        assertTrue(manager.cancelLaunch(instance));
        stale.finish();

        assertNull(instance.getDockerContainerId());
        assertNull(instance.getServiceNetworkIp());
    }

    @Test
    void aProtectedGuestReportsItsFirewallHelpersServiceNetworkAddress() {
        DockerClient docker = guestDocker(null, "172.20.0.11");
        Ec2ContainerManager manager = managerOnServiceNetwork(true, true, docker);
        Instance instance = serviceNetworkInstance();

        manager.refreshServiceNetworkIp(instance);

        assertEquals("172.20.0.11", manager.reachablePublicAddress(instance));
    }

    @Test
    void reachablePublicAddressIgnoresTheServiceNetworkWhenDisabled() {
        DockerClient docker = guestDocker("172.20.0.9", null);
        Ec2ContainerManager manager = managerOnServiceNetwork(false, true, docker);
        Instance instance = serviceNetworkInstance();

        manager.refreshServiceNetworkIp(instance);

        assertNull(instance.getServiceNetworkIp());
        assertEquals("127.0.0.1", manager.reachablePublicAddress(instance));
        verify(docker, never()).inspectContainerCmd(anyString());
    }

    @Test
    void reachablePublicAddressIgnoresTheServiceNetworkWhenFlociRunsOnTheHost() {
        DockerClient docker = guestDocker("172.20.0.9", null);
        Ec2ContainerManager manager = managerOnServiceNetwork(true, false, docker);
        Instance instance = serviceNetworkInstance();

        manager.refreshServiceNetworkIp(instance);

        assertEquals("127.0.0.1", manager.reachablePublicAddress(instance));
    }

    @Test
    void reachablePublicAddressFallsBackWhenTheInstanceIsNotOnTheServiceNetwork() {
        DockerClient docker = guestDocker(null, null);
        Ec2ContainerManager manager = managerOnServiceNetwork(true, true, docker);
        Instance instance = serviceNetworkInstance();

        manager.refreshServiceNetworkIp(instance);

        assertEquals("127.0.0.1", manager.reachablePublicAddress(instance));
    }

    private static Instance serviceNetworkInstance() {
        Instance instance = new Instance();
        instance.setInstanceId("i-service-network");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("172.17.0.4");
        return instance;
    }

    /**
     * A daemon whose guest container sits on the bridge, unroutable from the host, and on Floci's
     * own network {@code floci-net} at {@code guestIp} when that is not null. With a
     * {@code helperIp}, the guest instead joins a firewall helper's namespace, as a security-group
     * protected guest does, and the helper is on {@code floci-net} at that address.
     */
    private static DockerClient guestDocker(String guestIp, String helperIp) {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        Map<String, ContainerNetwork> guestNetworks = new LinkedHashMap<>();
        if (helperIp == null) {
            guestNetworks.put("bridge", new ContainerNetwork().withIpv4Address("172.17.0.4"));
        }
        if (guestIp != null) {
            guestNetworks.put("floci-net", new ContainerNetwork().withIpv4Address(guestIp));
        }
        InspectContainerResponse guest = inspectWithNetworks(guestNetworks);
        if (helperIp != null) {
            HostConfig hostConfig = mock(HostConfig.class);
            when(hostConfig.getNetworkMode()).thenReturn("container:helper-1");
            when(guest.getHostConfig()).thenReturn(hostConfig);
            InspectContainerResponse helper = inspectWithNetworks(Map.of(
                    "bridge", new ContainerNetwork().withIpv4Address("172.17.0.5"),
                    "floci-net", new ContainerNetwork().withIpv4Address(helperIp)));
            when(docker.inspectContainerCmd("helper-1").exec()).thenReturn(helper);
        }
        when(docker.inspectContainerCmd(TEST_CONTAINER_ID).exec()).thenReturn(guest);
        clearInvocations(docker);
        return docker;
    }

    private static InspectContainerResponse inspectWithNetworks(Map<String, ContainerNetwork> networks) {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        NetworkSettings settings = mock(NetworkSettings.class);
        when(inspect.getNetworkSettings()).thenReturn(settings);
        when(settings.getNetworks()).thenReturn(networks);
        return inspect;
    }

    private static Ec2ContainerManager managerOnServiceNetwork(boolean enabled, boolean inContainer,
                                                               DockerClient docker) {
        return managerOnServiceNetwork(enabled, inContainer, docker, mock(ExecutorService.class),
                mock(ExecutorService.class));
    }

    /** A manager on {@code docker} whose bridge addresses are unroutable from the host. */
    private static Ec2ContainerManager managerOnServiceNetwork(boolean enabled, boolean inContainer,
                                                               DockerClient docker, ExecutorService executor,
                                                               ExecutorService retryExecutor) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().publicIpFromServiceNetwork()).thenReturn(enabled);
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(inContainer);
        ContainerBuilder builder = mock(ContainerBuilder.class);
        when(builder.resolveDockerNetwork(Optional.empty())).thenReturn(Optional.of("floci-net"));

        return new Ec2ContainerManager(
                builder,
                mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class),
                detector,
                mock(DockerHostResolver.class),
                docker,
                mock(PortAllocator.class),
                config,
                mock(Ec2MetadataServer.class),
                mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class),
                mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class),
                new Ec2InstanceTypeCatalog(),
                executor,
                retryExecutor,
                Duration.ofSeconds(5));
    }

    @Test
    void readSshHostKeysReturnsTheGuestsHostKeys() {
        DockerClient docker = mock(DockerClient.class);
        String keys = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII0fBPBUZHaEOBc2mySfmI5btu4mkvFfNRujmF7RH2fj root@guest\n";
        ContainerExecStubs.completeEveryExec(docker, TEST_CONTAINER_ID, 0L, keys, "");

        Ec2ContainerManager manager = managerWith(mock(ContainerLifecycleManager.class), docker,
                mock(Ec2MetadataServer.class));

        assertEquals(keys, manager.readSshHostKeys(TEST_CONTAINER_ID));
    }

    @Test
    void readSshHostKeysIgnoresOutputBeyondWhatHostKeysCanTake() {
        DockerClient docker = mock(DockerClient.class);
        String planted = "x".repeat(Ec2ContainerManager.SSH_HOST_KEYS_MAX_BYTES + 1);
        ContainerExecStubs.completeEveryExec(docker, TEST_CONTAINER_ID, 0L, planted, "");

        Ec2ContainerManager manager = managerWith(mock(ContainerLifecycleManager.class), docker,
                mock(Ec2MetadataServer.class));

        assertEquals("", manager.readSshHostKeys(TEST_CONTAINER_ID));
    }

    private static Ec2ContainerManager managerWithReachability(ContainerNetworkReachability reachability) {
        return new Ec2ContainerManager(
                mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                mock(DockerHostResolver.class),
                mock(DockerClient.class),
                mock(PortAllocator.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(Ec2MetadataServer.class),
                mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class),
                reachability,
                mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class));
    }

    @Test
    void userDataShellScriptsIgnoresCloudConfigWithoutShellscript() {
        String userData = """
                Content-Type: multipart/mixed; boundary=cloudinit
                MIME-Version: 1.0

                --cloudinit
                Content-Type: text/cloud-config

                #cloud-config

                --cloudinit--
                """;

        assertTrue(Ec2ContainerManager.userDataShellScripts(userData).isEmpty());
    }

    @Test
    void launchReturnsTheLeaseWhenTheContainerNeverJoinsTheVpcNetwork() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 2;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerResponse noIp = inspectResponse(null);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(noIp);
        // attach() returns empty: nothing holds the address, and the instance is about to start
        // reporting its bridge address instead, so the lease is no longer findable from it.

        Instance instance = leasedInstance("i-noattach");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.vpcNetworkManager, timeout(2000).atLeastOnce())
                .releasePrivateIp(instance.getOwnerId(), "us-west-2", "subnet-lease", "10.0.1.10");
    }

    @Test
    void anInstanceWhoseLaunchFailedCarriesItsTerminationTime() throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.builder.build()).thenThrow(new IllegalStateException("daemon went away"));
        Instance instance = leasedInstance("i-failed-launch");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        // The retention of a terminated instance is counted from this stamp; without it the
        // instance is never old enough to be pruned.
        assertEquals(harness.time.clock().millis(), instance.getTerminatedAt());
    }

    @Test
    void launchReturnsTheLeaseWhenTheLaunchThrows() throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.builder.build()).thenThrow(new IllegalStateException("daemon went away"));

        Instance instance = leasedInstance("i-throws");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        // Detach before release: Docker holds the address while the endpoint stands, so handing it
        // to the next launch before disconnecting would have the daemon refuse it.
        verify(harness.vpcNetworkManager, timeout(2000)).detach("000000000000", "us-west-2", "vpc-lease", null);
        verify(harness.vpcNetworkManager, timeout(2000))
                .releasePrivateIp("000000000000", "us-west-2", "subnet-lease", "10.0.1.10");
    }

    private static Instance leasedInstance(String instanceId) {
        Instance instance = instance(instanceId);
        instance.setRegion("us-west-2");
        instance.setVpcId("vpc-lease");
        instance.setSubnetId("subnet-lease");
        instance.setPrivateIpAddress("10.0.1.10");
        return instance;
    }

    @Test
    void launchInstanceUserDataStreamToCloudWatch() throws Exception {
        LaunchHarness harness = launchHarness();
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        // manually set up container bridge IP
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = inspectResponse("192.168.215.42");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        String instanceId = "i-userdatacloudwatch";
        Instance instance = instance(instanceId);
        instance.setUserData("""
                #!/bin/bash
                echo test
                """);
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");
        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));

        verify(harness.logStreamer, timeout(2000)).streamToCloudWatchLogs(
            any(String.class), any(String.class), eq("us-west-2"), eq(TEST_USER_DATA_OUTPUT)
        );
        // The script's output holds its connection for as long as it runs, so it uses the streaming pool.
        verify(harness.streamingDockerClient).execStartCmd("userdata-exec");
        verify(harness.dockerClient, never()).execStartCmd("userdata-exec");
    }

    @Test
    void launchRunsItsStartedHookBeforeTheInstanceIsRunningAndBeforeUserData() throws Exception {
        LaunchHarness harness = launchHarness();
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = inspectResponse("192.168.215.42");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        Instance instance = instance("i-startedhook");
        instance.setUserData("""
                #!/bin/bash
                test -b /dev/sdf
                """);
        List<String> events = new CopyOnWriteArrayList<>();

        harness.manager.launch(instance, ResolvedAmiImage.minimal("ubuntu:24.04"), null, "us-west-2",
                Ec2PortForwardManager.DesiredPorts.NONE, List.of(), Map.of(), () -> events.add(
                        "hook while " + instance.getState().getName() + ", user data started: "
                                + mockingDetails(harness.streamingDockerClient).getInvocations().stream()
                                .anyMatch(call -> call.getMethod().getName().equals("execStartCmd"))));

        verify(harness.streamingDockerClient, timeout(2000)).execStartCmd("userdata-exec");
        assertEquals(List.of("hook while pending, user data started: false"), events);
    }

    @Test
    void launchCombinesPreparationAndBothHooksBeforeUserData() throws Exception {
        LaunchHarness harness = launchHarness();
        CountDownLatch userDataStarted = new CountDownLatch(1);
        harness.stubSuccessfulExecs(userDataStarted, new CountDownLatch(0));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("192.168.215.42");
        when(inspect.exec()).thenReturn(response);
        Instance instance = instance("i-combined-hooks");
        instance.setUserData("#!/bin/sh\necho ready\n");
        List<String> events = new CopyOnWriteArrayList<>();

        harness.manager.launch(instance, ResolvedAmiImage.minimal("ubuntu:24.04"), null, "us-west-2",
                Ec2PortForwardManager.DesiredPorts.NONE, List.of(), Map.of(), () -> {
                    events.add("prepare " + instance.getState().getName());
                    return List.of();
                }, () -> events.add("before " + instance.getState().getName()), () -> {
                    events.add("after " + instance.getState().getName());
                    assertEquals(1, userDataStarted.getCount());
                });

        assertTrue(userDataStarted.await(2, TimeUnit.SECONDS), "user data should start after both hooks");
        assertEquals(List.of("prepare pending", "before pending", "after running"), events);
    }

    @Test
    void launchLabelsGuestAndHelperWithResourceIdentity() throws Exception {
        LaunchHarness harness = launchHarness();
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = inspectResponse("192.168.215.42");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        Instance instance = instance("i-labeltest");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        verify(harness.builder, timeout(2000).times(2)).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-labeltest",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-west-2"));
    }

    @Test
    void metadataRoutingInstallCommandIncludesMandatoryNatDependencies() {
        String[] command = Ec2MetadataProxy.ec2InstallCommand();

        assertEquals("sh", command[0]);
        assertTrue(command[2].contains("iproute2"));
        assertTrue(command[2].contains("socat"));
        assertTrue(command[2].contains("curl"));
        assertTrue(command[2].contains("command -v iptables"));
        assertTrue(command[2].contains("ca-certificates iptables-nft >/dev/null"));
    }

    @Test
    void sshdInstallProbeAlsoInstallsTheSshClientPackage() {
        // Packer's default file transfer runs scp *on the instance*, so a guest with only
        // openssh-server fails the shell provisioner with "SCP failed to start. This usually
        // means that SCP is not properly installed on the remote system." Real AMIs ship the
        // client; installing only the server leaves sftp-server present but /usr/bin/scp
        // absent. Package names verified against the images themselves: openssh-clients on
        // rpm distributions, openssh-client on Debian, and apk's openssh already has both.
        String script = SSHD_INSTALL_PROBE_CMD[2];

        assertTrue(script.contains("openssh-server openssh-clients"), script);
        assertTrue(script.contains("openssh-server openssh-client >"), script);
        // The guard has to consider scp as well, or an image that already has sshd but no
        // client skips the install and provisioning still fails with nothing in the logs.
        assertTrue(script.contains("! command -v scp"), script);
        // ...and so does the trailing status check, on its own exit code. Ending the script on
        // "command -v sshd" alone would report a guest that has the server but whose client
        // install failed as a success, which is the state this guard was widened to catch.
        assertTrue(script.endsWith("command -v sshd >/dev/null 2>&1 || exit 1;"
                + "command -v scp >/dev/null 2>&1 || exit "
                + Ec2ContainerManager.SSH_CLIENT_MISSING_EXIT_CODE), script);
    }

    @Test
    void sshdInstallProbeHandlesYumForAmazonLinux2() {
        // public.ecr.aws/amazonlinux/amazonlinux:2 ships only yum -- no dnf, no apt-get, no
        // apk. It is NOT the default image (image-catalog.yaml pins that to amazonlinux:2023,
        // which has dnf); it is reached by explicitly requesting ami-amazonlinux2. Without a
        // yum branch the if/elif chain falls through, the trailing "command -v sshd" fails,
        // and startSshd() returns early, so such an instance is never reachable over SSH.
        //
        // Asserted against the production accessor, not the SSHD_INSTALL_PROBE_CMD copy below,
        // so removing the yum branch fails THIS test rather than only the withCmd verify.
        String script = Ec2ContainerManager.sshdInstallProbeCommand()[2];

        assertTrue(script.contains("command -v yum"), script);
        // The yum branch installs the client package alongside the server, exactly as the dnf
        // branch does -- an AL2 guest needs scp for the same reason every other guest does.
        assertTrue(script.contains("yum install -y openssh-server openssh-clients"), script);
        // dnf must still win where both exist (Amazon Linux 2023, modern Fedora/RHEL), where
        // yum is only a compatibility shim over dnf.
        assertTrue(script.indexOf("command -v dnf") < script.indexOf("command -v yum"),
                "dnf must be probed before yum");
    }

    @Test
    void sshdInstallProbeClearsApplicationPathsBeforeThePackageManagerRuns(@TempDir Path directory)
            throws Exception {
        // dnf and yum are Python programs, so an image's PYTHONHOME or LD_LIBRARY_PATH breaks the
        // install. The fake dnf "installs" sshd and scp only when it runs with those cleared.
        Path bin = Files.createDirectory(directory.resolve("bin"));
        Path record = directory.resolve("tools");
        HelperShellFixture.writeTool(bin, "dnf", HelperShellFixture.checkedToolEnvironment()
                + "for tool in sshd scp; do\n"
                + "  printf '#!/bin/sh\\nexit 0\\n' > \"$TEST_BIN/$tool\"\n"
                + "  /bin/chmod +x \"$TEST_BIN/$tool\"\n"
                + "done\n");

        assertEquals(0, HelperShellFixture.run(Ec2ContainerManager.sshdInstallProbeCommand(), directory,
                HelperShellFixture.applicationEnvironment(Map.of("PATH", bin.toString(),
                        "TEST_RECORD", record.toString(), "TEST_BIN", bin.toString()))),
                HelperShellFixture.output(directory));
        assertEquals("dnf\n", Files.readString(record));
    }

    @Test
    void metadataProxyInstallCommandHandlesYumForAmazonLinux2() {
        // The IMDS proxy dependency install had the same gap, and unlike the sshd probe it
        // ends in an explicit failure: on an ami-amazonlinux2 instance it reached the else
        // branch and exited 1 with "No supported package manager found for IMDS proxy
        // dependencies", leaving the instance without a link-local metadata endpoint.
        String script = Ec2ContainerManager.metadataProxyInstallCommand()[2];

        assertTrue(script.contains("command -v yum"), script);
        assertTrue(script.contains("yum install -y iproute socat curl ca-certificates"), script);
        assertTrue(script.contains("yum install -y iptables >/dev/null || true"), script);
    }

    @Test
    void metadataProxyInstallCommandLetsDnfReplaceCurlMinimalWithCurl() {
        // public.ecr.aws/amazonlinux/amazonlinux:2023 -- the fallback image AmiImageResolver
        // uses for any unrecognized AMI ID -- ships curl-minimal by default. Without
        // --allowerasing, "dnf install -y iproute socat curl ca-certificates" fails the whole
        // transaction on a curl/curl-minimal conflict (they both provide /usr/bin/curl), so
        // iproute and socat never install either, even though neither of them conflicts with
        // anything. Reproduced against the real image: dnf reported dozens of
        // "package curl-minimal-... conflicts with curl provided by curl-..." lines and the
        // instance was left with no link-local IMDS endpoint.
        String script = Ec2ContainerManager.metadataProxyInstallCommand()[2];

        assertTrue(script.contains("dnf install -y --allowerasing iproute socat curl ca-certificates"),
                script);
        assertTrue(script.contains("dnf install -y --allowerasing iptables-nft >/dev/null || true"), script);
    }

    @Test
    void metadataRoutingCommandRoutesAwsLinkLocalMetadataWithoutAListener() {
        String[] command = Ec2MetadataProxy.ec2RoutingCommand("floci", 9169);

        assertEquals("sh", command[0]);
        assertTrue(command[2].contains("169.254.169.254/32"));
        assertTrue(command[2].contains("-I OUTPUT 1 -d 169.254.169.254/32 -p tcp --dport 80"));
        assertTrue(command[2].contains("getent ahostsv4 'floci'"), "IPv4 DNAT must resolve an IPv4 target");
        assertFalse(command[2].contains("http://floci:9169"), "Resolution must not need a direct HTTP connection");
        assertTrue(command[2].contains("--to-destination \"$target_ip:9169\""));
        assertFalse(command[2].contains("TCP-LISTEN"));
        assertFalse(command[2].contains("ip addr add"));
        assertTrue(command[2].contains("http://169.254.169.254/latest/meta-data/instance-id"));
    }

    @Test
    void instanceProfileEnvironmentLetsTheSdkUseImds() {
        List<String> environment = Ec2ContainerManager.localAwsEnvironment(
                "us-west-2", "http://floci:4566", true);
        assertTrue(environment.contains("AWS_EC2_METADATA_SERVICE_ENDPOINT=http://169.254.169.254"));
        assertTrue(environment.contains("AWS_ENDPOINT_URL=http://floci:4566"));
        assertFalse(environment.stream().anyMatch(value -> value.startsWith("AWS_ACCESS_KEY_ID=")
                || value.startsWith("AWS_SECRET_ACCESS_KEY=") || value.startsWith("AWS_SESSION_TOKEN=")));
    }

    @ParameterizedTest
    @CsvSource({
            "true, true, true, false",
            "false, true, true, true",
            "true, false, true, true",
            "true, true, false, true"
    })
    void sdkEndpointOverrideRequiresAnUnroutedWorkload(boolean protectedNamespace, boolean routed,
                                                      boolean publicApis, boolean override) throws Exception {
        ContainerSpec spec = launchConfiguredGuest(ResolvedAmiImage.minimal("ubuntu:24.04"), Map.of(
                "FLOCI_NETWORK_VPC_ROUTING_ENABLED", Boolean.toString(routed),
                "FLOCI_NETWORK_VPC_ROUTING_PUBLIC_AWS_API", Boolean.toString(publicApis)),
                protectedNamespace, true, false);

        assertEquals(override, spec.env().stream().anyMatch(value -> value.startsWith("AWS_ENDPOINT_URL=")));
        assertTrue(spec.env().contains("AWS_EC2_METADATA_SERVICE_ENDPOINT=http://169.254.169.254"));
        assertTrue(spec.env().contains("AWS_REGION=us-west-2"));
        assertFalse(spec.env().stream().anyMatch(value -> value.startsWith("AWS_ACCESS_KEY_ID=")
                || value.startsWith("AWS_SECRET_ACCESS_KEY=") || value.startsWith("AWS_SESSION_TOKEN=")));
    }

    @Test
    void localAwsEnvironmentProvidesCliCredentialsAndFlociEndpoint() {
        assertEquals(
                List.of(
                        "AWS_EC2_METADATA_SERVICE_ENDPOINT=http://169.254.169.254",
                        "AWS_ENDPOINT_URL=http://floci:4566",
                        "AWS_DEFAULT_REGION=us-west-2",
                        "AWS_REGION=us-west-2",
                        "AWS_ACCESS_KEY_ID=test",
                        "AWS_SECRET_ACCESS_KEY=test",
                        "AWS_SESSION_TOKEN=test-session-token"),
                Ec2ContainerManager.localAwsEnvironment(
                        "us-west-2",
                        "http://floci:4566"));
    }

    @Test
    void launchPointsTheInstanceSdkAtTheSharedContainerEndpointAndLinkLocalMetadata() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.13");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-endpoint");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.builder).withEnv(List.of(
                "AWS_EC2_METADATA_SERVICE_ENDPOINT=http://169.254.169.254",
                "AWS_ENDPOINT_URL=http://localhost.floci.io:4680",
                "AWS_DEFAULT_REGION=us-west-2",
                "AWS_REGION=us-west-2",
                "AWS_ACCESS_KEY_ID=test",
                "AWS_SECRET_ACCESS_KEY=test",
                "AWS_SESSION_TOKEN=test-session-token"));
    }

    @Test
    void launchSystemdGuestWithoutCloudInitUsesInitAndCompatibilityUserData() throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.lifecycleManager.create(any(ContainerSpec.class), eq("linux/arm64")))
                .thenReturn(TEST_CONTAINER_ID);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.11");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-systemd");
        instance.setUserData("#!/bin/sh\necho ready\n");

        harness.manager.launch(instance,
                new ResolvedAmiImage("floci/ami-ubuntu:24.04-arm64", ResolvedAmiImage.SYSTEMD_RUNTIME, false,
                        "linux/arm64"),
                null,
                "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.builder).withCmd(TEST_SYSTEMD_INIT_COMMAND);
        verify(harness.builder).withCgroupnsMode("host");
        verify(harness.builder).withBind("/sys/fs/cgroup", "/sys/fs/cgroup");
        // User data runs after the sshd step, so once it has started that step is settled.
        awaitUntil(() -> commandIndex(harness.executedCommands, "/var/lib/user-data.sh") >= 0, Duration.ofSeconds(2));
        assertEquals(-1, commandIndex(harness.executedCommands, "/usr/sbin/sshd"),
                "a systemd guest's init starts sshd; Floci must not start a second one");
        verify(harness.lifecycleManager).create(any(ContainerSpec.class), eq("linux/arm64"));
    }

    @ParameterizedTest
    @CsvSource({"systemd,true", "image,true", "systemd,false", "image,false", "minimal,true", "minimal,false"})
    void launchDelegatesBootstrapOnlyToAnActiveGuestCloudInit(String runtime, boolean cloudInit) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        InspectContainerResponse withIp = inspectResponse("172.18.0.11");
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-cloud-init");
        String userData = "#!/bin/sh\necho run-once\n";
        instance.setUserData(userData);

        try {
            harness.manager.launch(instance, new ResolvedAmiImage("test/native-guest:1", runtime, cloudInit, null),
                    "ssh-ed25519 AAAAtest", "us-west-2");
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "launch worker should finish");
            assertEquals("running", instance.getState().getName());
            assertEquals(userData, instance.getUserData(), "metadata retains the original user data");
            boolean guestOwnsBootstrap = cloudInit && !"minimal".equals(runtime);
            verify(harness.builder, never()).withApparmorProfile(anyString());
            verify(harness.builder, never()).withStopSignal(anyString());
            if (guestOwnsBootstrap) {
                verify(harness.dockerClient, never()).execCreateCmd(TEST_CONTAINER_ID);
                verify(harness.dockerClient, never()).copyArchiveToContainerCmd(TEST_CONTAINER_ID);
            } else {
                assertTrue(commandIndex(harness.executedCommands, "/var/lib/user-data.sh") >= 0,
                        "guests without an active cloud-init keep compatibility bootstrap");
            }
            if ("image".equals(runtime)) {
                verify(harness.builder, never()).withCmd(List.of("tail", "-f", "/dev/null"));
                verify(harness.builder, never()).withCmd(TEST_SYSTEMD_INIT_COMMAND);
            } else if ("systemd".equals(runtime)) {
                verify(harness.builder).withCmd(TEST_SYSTEMD_INIT_COMMAND);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unconfined", "floci-native"})
    void nativeInitOwnsBootstrapWithoutCompatibilityUserDataOrSshInjection(String profile) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        when(harness.config.services().ec2().nativeInitApparmorProfile())
                .thenReturn("unconfined".equals(profile) ? Optional.empty() : Optional.of(profile));
        Instance instance = instance("i-native-init-bootstrap");
        List<List<String>> helperCommands = stubNativeInit(harness, instance);
        instance.setUserData("#!/bin/sh\necho native-only\n");
        try {
            harness.manager.launch(instance,
                    new ResolvedAmiImage("test/native-guest:1", ResolvedAmiImage.SYSTEMD_V1_RUNTIME,
                            true, null), "ssh-ed25519 AAAAtest", "us-west-2");
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals("running", instance.getState().getName());
            verify(harness.builder).withApparmorProfile(profile);
            verify(harness.builder).withStopSignal("SIGRTMIN+3");
            assertTrue(helperCommands.stream().anyMatch(command -> command.contains("prepare-init")));
            assertTrue(harness.executedCommands.stream().anyMatch(command ->
                    Arrays.equals(command, new String[]{"test", "-f", Ec2SystemdV1Runtime.READY})));
            assertEquals(-1, commandIndex(harness.executedCommands, "/var/lib/user-data.sh"));
            assertEquals(-1, commandIndex(harness.executedCommands, "/usr/sbin/sshd"));
            verify(harness.dockerClient, never()).copyArchiveToContainerCmd(TEST_CONTAINER_ID);
            assertEquals("#!/bin/sh\necho native-only\n", instance.getUserData());
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void failedNativeReleaseRetainsWorkloadCleanupUntilStrictRemovalSucceeds() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-native-release-cleanup");
        stubNativeInit(harness, instance);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID))
                .thenThrow(new IllegalStateException("Native release inspection unavailable"));
        doThrow(new IllegalStateException("Docker removal unavailable")).doNothing()
                .when(harness.lifecycleManager).removeIfExistsStrict(TEST_CONTAINER_ID);
        try {
            harness.manager.launch(instance,
                    new ResolvedAmiImage("test/native-guest:1", ResolvedAmiImage.SYSTEMD_V1_RUNTIME,
                            true, null), null, "us-west-2");
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals("terminated", instance.getState().getName());
            verify(harness.lifecycleManager).removeIfExistsStrict(TEST_CONTAINER_ID);
            verify(harness.lifecycleManager, never()).removeIfExists(TEST_CONTAINER_ID);
            verify(harness.lifecycleManager, never()).removeIfExistsStrict(TEST_INIT_HELPER_ID);
            verify(harness.portAllocator, never()).release(2201);

            assertTrue(harness.manager.cancelLaunch(instance));
            InOrder order = inOrder(harness.lifecycleManager);
            order.verify(harness.lifecycleManager, times(2)).removeIfExistsStrict(TEST_CONTAINER_ID);
            order.verify(harness.lifecycleManager).removeIfExistsStrict(TEST_INIT_HELPER_ID);
            verify(harness.portAllocator).release(2201);
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void failedLaunchRetainsNativeHelperUntilWorkloadRemovalCanBeConfirmed() {
        LaunchHarness harness = launchHarness();
        Instance instance = leasedInstance("i-native-cleanup-workload");
        stubPendingNativeCleanup(harness, instance);
        doThrow(new IllegalStateException("Docker removal unavailable")).doNothing()
                .when(harness.lifecycleManager).removeIfExistsStrict(TEST_CONTAINER_ID);
        try {
            assertThrows(IllegalStateException.class, () -> harness.manager.cancelLaunch(instance));
            assertEquals("terminated", instance.getState().getName());
            verify(harness.lifecycleManager, never()).removeIfExistsStrict(TEST_INIT_HELPER_ID);
            verify(harness.vpcNetworkManager, never()).releasePrivateIp(any(), any(), any(), any());
            verify(harness.portAllocator, never()).release(2201);

            assertTrue(harness.manager.cancelLaunch(instance));
            InOrder order = inOrder(harness.lifecycleManager, harness.vpcNetworkManager);
            order.verify(harness.lifecycleManager, times(2)).removeIfExistsStrict(TEST_CONTAINER_ID);
            order.verify(harness.lifecycleManager).removeIfExistsStrict(TEST_INIT_HELPER_ID);
            order.verify(harness.vpcNetworkManager)
                    .releasePrivateIp("000000000000", "us-west-2", "subnet-lease", "10.0.1.10");
            verify(harness.portAllocator).release(2201);
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void failedLaunchRetriesNativeHelperRetirementBeforeReleasingItsLease() {
        LaunchHarness harness = launchHarness();
        Instance instance = leasedInstance("i-native-cleanup-helper");
        stubPendingNativeCleanup(harness, instance);
        doThrow(new IllegalStateException("Helper removal unavailable")).doNothing()
                .when(harness.lifecycleManager).removeIfExistsStrict(TEST_INIT_HELPER_ID);
        try {
            assertThrows(IllegalStateException.class, () -> harness.manager.cancelLaunch(instance));
            verify(harness.lifecycleManager).removeIfExistsStrict(TEST_CONTAINER_ID);
            verify(harness.vpcNetworkManager, never()).releasePrivateIp(any(), any(), any(), any());
            verify(harness.portAllocator, never()).release(2201);

            assertTrue(harness.manager.cancelLaunch(instance));
            verify(harness.lifecycleManager, times(2)).removeIfExistsStrict(TEST_INIT_HELPER_ID);
            verify(harness.vpcNetworkManager)
                    .releasePrivateIp("000000000000", "us-west-2", "subnet-lease", "10.0.1.10");
            verify(harness.portAllocator).release(2201);
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void failedNativeLaunchAutomaticallyReclaimsItsResourcesWhenDockerRecovers() throws Exception {
        LaunchHarness harness = launchHarness();
        Instance instance = leasedInstance("i-native-cleanup-automatic");
        stubPendingNativeCleanup(harness, instance);
        AtomicBoolean dockerRecovered = new AtomicBoolean();
        CountDownLatch reclaimed = new CountDownLatch(1);
        doAnswer(ignored -> {
            if (!dockerRecovered.get()) {
                throw new IllegalStateException("Docker removal temporarily unavailable");
            }
            return null;
        }).when(harness.lifecycleManager).removeIfExistsStrict(TEST_CONTAINER_ID);
        doAnswer(ignored -> {
            reclaimed.countDown();
            return null;
        }).when(harness.metadataServer).unregisterContainer("172.18.0.12", instance);
        try {
            assertThrows(IllegalStateException.class, () -> harness.manager.cancelLaunch(instance));
            verify(harness.lifecycleManager, never()).removeIfExistsStrict(TEST_INIT_HELPER_ID);
            verify(harness.portAllocator, never()).release(2201);
            verify(harness.vpcNetworkManager, never()).releasePrivateIp(any(), any(), any(), any());

            dockerRecovered.set(true);
            harness.time().advance(Duration.ofMillis(999));
            verify(harness.lifecycleManager).removeIfExistsStrict(TEST_CONTAINER_ID);
            assertEquals(1, reclaimed.getCount(), "The retry sweep runs once a second");
            harness.time().advance(Duration.ofMillis(1));
            assertTrue(reclaimed.await(5, TimeUnit.SECONDS),
                    "Cleanup must resume after Docker recovers without another cancel request");
            verify(harness.lifecycleManager).removeIfExistsStrict(TEST_INIT_HELPER_ID);
            verify(harness.portAllocator).release(2201);
            verify(harness.vpcNetworkManager).releasePrivateIp("000000000000", "us-west-2", "subnet-lease", "10.0.1.10");
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void unknownContainerProbeDoesNotReportAnExitedEc2Guest() {
        LaunchHarness harness = launchHarness();
        Instance guest = instance("i-health-probe");
        guest.setDockerContainerId(TEST_CONTAINER_ID);
        guest.setState(InstanceState.running());
        when(harness.lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(false);
        doReturn(ContainerLiveness.UNKNOWN, ContainerLiveness.RUNNING, ContainerLiveness.NOT_RUNNING)
                .when(harness.lifecycleManager).probeContainer(TEST_CONTAINER_ID);
        try {
            assertThrows(IllegalStateException.class, () -> harness.manager.isContainerRunning(guest));
            assertTrue(harness.manager.isContainerRunning(guest));
            assertFalse(harness.manager.isContainerRunning(guest));
            assertEquals("running", guest.getState().getName());
        } finally {
            harness.manager.stop();
        }
    }

    private static final String TEST_INIT_HELPER_ID = "native-init-helper";

    @Test
    void restoredNativeOwnershipMismatchIsContainedAndNeverDeletesTheForeignGuest() {
        LaunchHarness harness = launchHarness();
        Instance instance = instance("i-native-foreign-restore");
        stubNativeInit(harness, instance);
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setState(InstanceState.running());
        InspectContainerResponse guest = harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID).exec();
        guest.getConfig().getLabels().put(ContainerStorageHelper.ACCOUNT_LABEL, "999999999999");
        try {
            assertFalse(harness.manager.restoreMetadataRegistration(instance));
            verify(harness.metadataServer, never()).registerContainer(anyString(), anyString(), any());
            assertThrows(IllegalStateException.class, () -> harness.manager.terminateForShutdown(instance));
            assertEquals("shutting-down", instance.getState().getName());
            verify(harness.dockerClient, never()).removeContainerCmd(TEST_CONTAINER_ID);
            verify(harness.lifecycleManager, never()).removeIfExistsStrict(TEST_INIT_HELPER_ID);
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void restoredExitedNativeOwnershipMismatchDoesNotAbortRecovery() {
        LaunchHarness harness = launchHarness();
        Instance instance = instance("i-native-foreign-reboot");
        stubNativeInit(harness, instance);
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setState(InstanceState.running());
        InspectContainerResponse guest = harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID).exec();
        guest.getConfig().getLabels().put(ContainerStorageHelper.ACCOUNT_LABEL, "999999999999");
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(guest.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(false);
        when(state.getStatus()).thenReturn("exited");
        when(state.getExitCodeLong()).thenReturn(129L);
        when(harness.lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(false);
        try {
            assertFalse(harness.manager.restoreMetadataRegistration(instance));
            verify(harness.metadataServer, never()).registerContainer(anyString(), anyString(), any());
            verify(harness.dockerClient, never()).startContainerCmd(TEST_CONTAINER_ID);
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void restoredRunningNativeGuestValidatesItsBootGate() {
        LaunchHarness harness = launchHarness();
        Instance instance = instance("i-native-restore-gate");
        stubNativeInit(harness, instance);
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setState(InstanceState.running());
        try {
            assertTrue(harness.manager.restoreMetadataRegistration(instance));
            assertTrue(harness.executedCommands.stream().anyMatch(command ->
                    Arrays.equals(command, new String[]{"test", "-f", Ec2SystemdV1Runtime.READY})));
            InOrder boot = inOrder(harness.metadataServer, harness.dockerClient);
            boot.verify(harness.metadataServer, atLeastOnce())
                    .registerContainer(anyString(), eq(instance.getInstanceId()), eq(instance));
            boot.verify(harness.dockerClient).execCreateCmd(TEST_CONTAINER_ID);
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void restoredRunningNativeGuestWithFailedBootIsNotHealthy() {
        LaunchHarness harness = launchHarness();
        Instance instance = instance("i-native-restore-failed-gate");
        stubNativeInit(harness, instance);
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setState(InstanceState.running());
        ContainerExecStubs.completeEveryExec(harness.dockerClient, TEST_CONTAINER_ID, 2, "", "gate inspection failed");
        try {
            assertFalse(harness.manager.restoreMetadataRegistration(instance));
            assertFalse(harness.manager.isContainerRunning(instance),
                    "A running bootstrap shell cannot hide a failed native boot");
            ContainerExecStubs.completeEveryExec(harness.dockerClient, TEST_CONTAINER_ID, 0, "", "");
            assertTrue(harness.manager.restoreMetadataRegistration(instance));
            assertTrue(harness.manager.isContainerRunning(instance),
                    "A verified ready gate must clear the earlier boot failure");
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void failedNativeApiRebootIsNotReportedHealthy() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-native-api-reboot-failed-gate");
        stubNativeInit(harness, instance);
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setState(InstanceState.running());
        RestartContainerCmd restart = mock(RestartContainerCmd.class);
        when(harness.dockerClient.restartContainerCmd(TEST_CONTAINER_ID)).thenReturn(restart);
        try {
            assertTrue(harness.manager.restoreMetadataRegistration(instance));
            ContainerExecStubs.completeEveryExec(harness.dockerClient, TEST_CONTAINER_ID, 2, "", "gate inspection failed");
            harness.manager.reboot(instance);
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            verify(restart).exec();
            assertFalse(harness.manager.isContainerRunning(instance),
                    "Failed native release after Docker restart must remain unhealthy");
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void pendingNativeRebootDoesNotWaitForAnOccupiedLaunchWorker() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-native-reboot-busy-launcher");
        stubNativeInit(harness, instance);
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setState(InstanceState.pending());
        CountDownLatch workerOccupied = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch restarted = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        InspectContainerResponse guest = harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID).exec();
        InspectContainerResponse.ContainerState dockerState = mock(InspectContainerResponse.ContainerState.class);
        when(guest.getState()).thenReturn(dockerState);
        when(dockerState.getRunning()).thenAnswer(ignored -> running.get());
        when(dockerState.getStatus()).thenReturn("exited");
        when(dockerState.getExitCodeLong()).thenReturn(129L);
        when(harness.lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenAnswer(ignored -> running.get());
        StartContainerCmd start = mock(StartContainerCmd.class);
        when(harness.dockerClient.startContainerCmd(TEST_CONTAINER_ID)).thenReturn(start);
        doAnswer(ignored -> {
            running.set(true);
            restarted.countDown();
            return null;
        }).when(start).exec();
        try {
            assertTrue(harness.manager.restoreMetadataRegistration(instance));
            executor.submit(() -> {
                workerOccupied.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerOccupied.await(5, TimeUnit.SECONDS));
            running.set(false);
            assertTrue(harness.manager.isContainerRunning(instance));
            assertTrue(restarted.await(2, TimeUnit.SECONDS),
                    "Pending guest recovery must run while the launch worker is still occupied");
            assertEquals("pending", instance.getState().getName());
        } finally {
            releaseWorker.countDown();
            harness.manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void terminationKeepsCapturedNativeHelperWhenTheInstanceIsReused(boolean replaceHelper) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = leasedInstance("i-captured-native");
        stubPendingNativeCleanup(harness, instance);
        Field runtimeField = Ec2ContainerManager.class.getDeclaredField("systemdV1");
        runtimeField.setAccessible(true);
        Ec2SystemdV1Runtime runtime = (Ec2SystemdV1Runtime) runtimeField.get(harness.manager);
        Field helpersField = Ec2SystemdV1Runtime.class.getDeclaredField("helpers");
        helpersField.setAccessible(true);
        Map<String, String> helpers = (Map<String, String>) helpersField.get(runtime);
        InspectContainerResponse replacement = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(replacement.getId()).thenReturn("replacement-init-helper");
        when(replacement.getState().getRunning()).thenReturn(true);
        Map<String, String> replacementLabels = new HashMap<>(
                harness.dockerClient.inspectContainerCmd(TEST_INIT_HELPER_ID).exec().getConfig().getLabels());
        replacementLabels.put(ContainerStorageHelper.ACCOUNT_LABEL, "444455556666");
        replacementLabels.put(ContainerStorageHelper.REGION_LABEL, "eu-west-1");
        when(replacement.getConfig().getLabels()).thenReturn(replacementLabels);
        InspectContainerCmd inspectReplacement = mock(InspectContainerCmd.class);
        when(inspectReplacement.exec()).thenReturn(replacement);
        when(harness.dockerClient.inspectContainerCmd("replacement-init-helper")).thenReturn(inspectReplacement);
        ContainerExecStubs.completeEveryExec(harness.dockerClient, "replacement-init-helper", 0, "", "");
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        doAnswer(invocation -> {
            instance.setOwnerId("444455556666");
            instance.setRegion("eu-west-1");
            if (replaceHelper) {
                helpers.put(instance.getInstanceId(), "replacement-init-helper");
            }
            return null;
        }).when(remove).exec();
        AtomicInteger callbacks = new AtomicInteger();
        try {
            harness.manager.terminate(instance, callbacks::incrementAndGet);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            verify(harness.lifecycleManager).removeIfExistsStrict(TEST_INIT_HELPER_ID);
            verify(harness.lifecycleManager, never()).removeIfExistsStrict("replacement-init-helper");
            assertEquals(replaceHelper ? "replacement-init-helper" : null, helpers.get(instance.getInstanceId()));
            verify(harness.vpcNetworkManager).releasePrivateIp(
                    "000000000000", "us-west-2", "subnet-lease", "10.0.1.10");
            assertEquals(1, callbacks.get());
        } finally {
            harness.manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void failedLaunchKeepsCapturedHelpersWhenTheInstanceIsReused(boolean replaceHelper) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = leasedInstance("i-captured-failed-native");
        stubPendingNativeCleanup(harness, instance);
        Field runtimeField = Ec2ContainerManager.class.getDeclaredField("systemdV1");
        runtimeField.setAccessible(true);
        Ec2SystemdV1Runtime runtime = (Ec2SystemdV1Runtime) runtimeField.get(harness.manager);
        Field helpersField = Ec2SystemdV1Runtime.class.getDeclaredField("helpers");
        helpersField.setAccessible(true);
        Map<String, String> helpers = (Map<String, String>) helpersField.get(runtime);
        Field networkField = Ec2ContainerManager.class.getDeclaredField("networkHelpers");
        networkField.setAccessible(true);
        Map<String, String> networkHelpers = (Map<String, String>) networkField.get(harness.manager);
        networkHelpers.put(instance.getInstanceId(), TEST_HELPER_ID);
        InspectContainerResponse replacement = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(replacement.getId()).thenReturn("replacement-init-helper");
        when(replacement.getState().getRunning()).thenReturn(true);
        Map<String, String> replacementLabels = new HashMap<>(
                harness.dockerClient.inspectContainerCmd(TEST_INIT_HELPER_ID).exec().getConfig().getLabels());
        replacementLabels.put(ContainerStorageHelper.ACCOUNT_LABEL, "444455556666");
        replacementLabels.put(ContainerStorageHelper.REGION_LABEL, "eu-west-1");
        when(replacement.getConfig().getLabels()).thenReturn(replacementLabels);
        InspectContainerCmd inspectReplacement = mock(InspectContainerCmd.class);
        when(inspectReplacement.exec()).thenReturn(replacement);
        when(harness.dockerClient.inspectContainerCmd("replacement-init-helper")).thenReturn(inspectReplacement);
        ContainerExecStubs.completeEveryExec(harness.dockerClient, "replacement-init-helper", 0, "", "");
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            instance.setOwnerId("444455556666");
            instance.setRegion("eu-west-1");
            if (replaceHelper) {
                helpers.put(instance.getInstanceId(), "replacement-init-helper");
                networkHelpers.put(instance.getInstanceId(), "replacement-network-helper");
            }
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("workload removal is still completing");
            }
            return null;
        }).when(harness.lifecycleManager).removeIfExistsStrict(TEST_CONTAINER_ID);
        try {
            assertThrows(IllegalStateException.class, () -> harness.manager.cancelLaunch(instance));
            verify(harness.portAllocator, never()).release(2201);
            assertTrue(harness.manager.cancelLaunch(instance));
            verify(harness.lifecycleManager).removeIfExistsStrict(TEST_INIT_HELPER_ID);
            verify(harness.lifecycleManager, never()).removeIfExistsStrict("replacement-init-helper");
            assertEquals(replaceHelper ? "replacement-init-helper" : null, helpers.get(instance.getInstanceId()));
            verify(harness.vpcNetworkManager).releasePrivateIp(
                    "000000000000", "us-west-2", "subnet-lease", "10.0.1.10");
            verify(harness.lifecycleManager).removeIfExistsStrict(TEST_HELPER_ID);
            verify(harness.lifecycleManager, never()).removeIfExistsStrict("replacement-network-helper");
            assertEquals(replaceHelper ? "replacement-network-helper" : null,
                    networkHelpers.get(instance.getInstanceId()));
            verify(harness.portAllocator).release(2201);
            assertEquals(2, attempts.get());
        } finally {
            harness.manager.stop();
        }
    }

    private static void stubPendingNativeCleanup(LaunchHarness harness, Instance instance) {
        stubNativeInit(harness, instance);
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setSshHostPort(2201);
        assertTrue(harness.manager.restoreMetadataRegistration(instance));
        instance.setPrivateIpAddress("10.0.1.10");
    }

    private static List<List<String>> stubNativeInit(LaunchHarness harness, Instance instance) {
        instance.setOwnerId("000000000000");
        instance.setRegion("us-west-2");
        InspectContainerResponse guest = inspectResponse("172.18.0.12");
        when(guest.getId()).thenReturn(TEST_CONTAINER_ID);
        ContainerConfig guestConfig = mock(ContainerConfig.class);
        Map<String, String> labels = new HashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                "ec2", instance.getInstanceId(), instance.getOwnerId(), instance.getRegion()));
        labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(harness.config));
        labels.put(Ec2SystemdV1Runtime.RUNTIME_LABEL, ResolvedAmiImage.SYSTEMD_V1_RUNTIME);
        when(guestConfig.getLabels()).thenReturn(labels);
        when(guest.getConfig()).thenReturn(guestConfig);
        InspectContainerCmd guestInspect = mock(InspectContainerCmd.class);
        when(guestInspect.exec()).thenReturn(guest);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(guestInspect);
        InspectContainerResponse helper = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(helper.getId()).thenReturn(TEST_INIT_HELPER_ID);
        when(helper.getState().getRunning()).thenReturn(true);
        Map<String, String> helperLabels = new HashMap<>(labels);
        helperLabels.put(ContainerStorageHelper.SERVICE_LABEL, Ec2SystemdV1Runtime.SERVICE);
        helperLabels.put(Ec2SystemdV1Runtime.SCOPE_LABEL, "floci-" + "a".repeat(32));
        when(helper.getConfig().getLabels()).thenReturn(helperLabels);
        InspectContainerCmd helperInspect = mock(InspectContainerCmd.class);
        when(helperInspect.exec()).thenReturn(helper);
        when(harness.dockerClient.inspectContainerCmd(TEST_INIT_HELPER_ID)).thenReturn(helperInspect);
        when(harness.dockerClient.inspectContainerCmd(ContainerStorageHelper.resourceName(
                harness.config, Ec2SystemdV1Runtime.SERVICE, null, instance.getInstanceId())))
                .thenReturn(helperInspect);
        return ContainerExecStubs.completeEveryExec(harness.dockerClient, TEST_INIT_HELPER_ID, 0, "", "");
    }

    @Test
    void preferredMetadataSourceIpUsesConfiguredNetworkBeforeBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.8");
        ContainerNetwork floci = new ContainerNetwork();
        floci.withIpv4Address("192.168.215.10");

        assertEquals(
                "192.168.215.10",
                Ec2ContainerManager.preferredMetadataSourceIp(Map.of(
                        "bridge", bridge,
                        "custom-floci-network", floci)).orElseThrow());
    }

    @Test
    void preferredMetadataSourceIpFallsBackToBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.8");

        assertEquals(
                "172.17.0.8",
                Ec2ContainerManager.preferredMetadataSourceIp(Map.of("bridge", bridge)).orElseThrow());
    }

    @Test
    void preferredMetadataSourceIpIsEmptyWithoutUsableAddress() {
        ContainerNetwork bridge = new ContainerNetwork();

        assertTrue(Ec2ContainerManager.preferredMetadataSourceIp(Map.of("bridge", bridge)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void publicRoutingUsesTheAttachedVpcAddressRegardlessOfNetworkOrder(boolean primaryFirst) throws Exception {
        LaunchHarness harness = launchHarness();
        Map<String, ContainerNetwork> networks = new LinkedHashMap<>();
        ContainerNetwork primary = new ContainerNetwork().withIpv4Address("172.18.0.3");
        ContainerNetwork vpc = new ContainerNetwork().withIpv4Address("10.0.1.10");
        networks.put(primaryFirst ? "control-plane" : "floci-vpc", primaryFirst ? primary : vpc);
        networks.put(primaryFirst ? "floci-vpc" : "control-plane", primaryFirst ? vpc : primary);
        InspectContainerResponse helper = mock(InspectContainerResponse.class);
        NetworkSettings settings = mock(NetworkSettings.class);
        when(helper.getNetworkSettings()).thenReturn(settings);
        when(settings.getNetworks()).thenReturn(networks);
        when(helper.getHostConfig()).thenReturn(HostConfig.newHostConfig().withNetworkMode("control-plane"));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(inspect.exec()).thenReturn(helper);
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(harness.vpcNetworkManager.attach("000000000000", "us-west-2", "vpc-lease", "subnet-lease", TEST_HELPER_ID, "10.0.1.10",
                false))
                .thenReturn(Optional.of("floci-vpc"));
        Instance guest = leasedInstance("i-public-routing-order");
        AtomicBoolean started = new AtomicBoolean();
        when(harness.lifecycleManager.startCreated(eq(TEST_CONTAINER_ID), any())).thenAnswer(invocation -> {
            assertEquals("10.0.1.10", guest.getContainerBridgeIp());
            assertEquals("172.18.0.3", guest.getContainerPublicIp());
            assertTrue(commandIndex(harness.executedCommands,
                    Ec2PublicAddressRouting.command("10.0.1.10", "172.18.0.3")) >= 0,
                    "Private-to-public translation must be ready before the guest starts");
            started.set(true);
            return null;
        });

        harness.manager.launch(guest, new ResolvedAmiImage("example/image:1", "image", false, null),
                null, "us-west-2");

        awaitUntil(started::get, Duration.ofSeconds(2));
    }

    @Test
    void imageCannotStartBeforeMetadataIsRegisteredAndRoutingIsReady() throws Exception {
        LaunchHarness harness = launchHarness();
        Instance instance = instance("i-image-metadata");
        AtomicBoolean started = new AtomicBoolean();
        when(harness.lifecycleManager.startCreated(eq(TEST_CONTAINER_ID), any())).thenAnswer(invocation -> {
            verify(harness.metadataServer).registerContainer("172.18.0.12", instance.getInstanceId(), instance);
            assertTrue(harness.executedCommands.stream().anyMatch(command ->
                    Arrays.equals(command, Ec2MetadataProxy.ec2NamespaceStartCommand("floci", 9169, true))));
            started.set(true);
            return null;
        });

        harness.manager.launch(instance, new ResolvedAmiImage("example/image:1", "image", false, null),
                null, "us-west-2");

        awaitUntil(started::get, Duration.ofSeconds(2));
        verify(harness.builder).withNetworkMode("container:" + TEST_HELPER_ID);
        verify(harness.lifecycleManager).createAndStart(any(ContainerSpec.class));
    }

    @Test
    void imageDataMountIsPreparedBeforeCreationWithoutChangingEntrypoint() throws Exception {
        LaunchHarness harness = launchHarness();
        Instance instance = instance("i-image-volume");
        Ec2ImageCatalog.VolumeMount mapping = new Ec2ImageCatalog.VolumeMount("sdf", "/data", "ext4");
        Ec2VolumeBlockDeviceManager.PreparedMount prepared =
                new Ec2VolumeBlockDeviceManager.PreparedMount("vol-abc", "/dev/sdf", "/dev/loop42",
                        "floci-volume", "/data");
        AtomicBoolean preparedBeforeCreation = new AtomicBoolean();
        when(harness.lifecycleManager.create(any(ContainerSpec.class))).thenAnswer(invocation -> {
            assertTrue(preparedBeforeCreation.get(), "The image must never start on an unmounted directory");
            return TEST_CONTAINER_ID;
        });

        harness.manager.launch(instance,
                new ResolvedAmiImage("example/image:1", "image", false, null, List.of(mapping)),
                null, "us-west-2", Ec2PortForwardManager.DesiredPorts.NONE, List.of(), Map.of(), () -> {
                    verify(harness.lifecycleManager, never()).create(any(ContainerSpec.class));
                    preparedBeforeCreation.set(true);
                    return List.of(prepared);
                }, null);

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.builder).withNamedVolume("floci-volume", "/data");
        verify(harness.builder).withDevice("/dev/loop42", "/dev/sdf", "rw");
        verify(harness.builder).withDevice("/dev/loop42", "/dev/loop42", "rw");
        verify(harness.builder).withCapAdd("SYS_RESOURCE");
        verify(harness.builder).withPrivileged(false);
        ArgumentCaptor<List<String>> commands = ArgumentCaptor.forClass(List.class);
        verify(harness.builder).withCmd(commands.capture());
        assertEquals(List.of("exec sleep 2147483647"), commands.getValue(),
                "Only the network helper receives a command override");
    }

    @Test
    void failedDataPreparationNeverCreatesTheGuest() throws Exception {
        LaunchHarness harness = launchHarness();
        Instance instance = instance("i-bad-volume");
        Ec2ImageCatalog.VolumeMount mapping = new Ec2ImageCatalog.VolumeMount("sdf", "/data", "ext4");
        harness.manager.launch(instance,
                new ResolvedAmiImage("example/image:1", "image", false, null, List.of(mapping)),
                null, "us-west-2", Ec2PortForwardManager.DesiredPorts.NONE, List.of(), Map.of(), () -> {
                    throw new IllegalStateException("backing file contains unknown data");
                }, null);

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.lifecycleManager, never()).create(any(ContainerSpec.class));
        assertNull(instance.getDockerContainerId());
    }

    @Test
    void failedMetadataPreparationRemovesHelperWithoutStartingTheGuest() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectExecResponse failed = mock(InspectExecResponse.class);
        when(failed.getExitCodeLong()).thenReturn(1L);
        when(harness.dockerClient.inspectExecCmd(anyString()).exec()).thenReturn(failed);
        Instance instance = leasedInstance("i-metadata-failed");

        harness.manager.launch(instance, "example/image:1", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.lifecycleManager, never()).create(any(ContainerSpec.class));
        verify(harness.lifecycleManager, never()).startCreated(anyString(), any());
        verify(harness.lifecycleManager).removeIfExistsStrict(TEST_HELPER_ID);
        verify(harness.portAllocator).release(2201);
    }

    @Test
    void failedHelperRemovalRetainsThePortAndSuppressesTeardownCallbackUntilRetry() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1), executor, null);
        try {
            Instance instance = instance("i-helper-removal");
            harness.manager.launch(instance, "example/image:1", null, "us-west-2");
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals("running", instance.getState().getName());
            when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID))
                    .thenReturn(mock(RemoveContainerCmd.class, RETURNS_SELF));
            doThrow(new IllegalStateException("helper busy")).doNothing()
                    .when(harness.lifecycleManager).removeIfExistsStrict(TEST_HELPER_ID);
            AtomicInteger completed = new AtomicInteger();

            harness.manager.terminate(instance, completed::incrementAndGet);
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);

            assertEquals("shutting-down", instance.getState().getName());
            assertEquals(0, completed.get());
            verify(harness.portAllocator, never()).release(2201);

            harness.time().advance(Duration.ofMillis(999));
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals("shutting-down", instance.getState().getName(), "The retry waits a full second");
            assertEquals(0, completed.get());
            verify(harness.lifecycleManager).removeIfExistsStrict(TEST_HELPER_ID);

            harness.time().advance(Duration.ofMillis(1));
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);

            assertEquals("terminated", instance.getState().getName());
            assertEquals(1, completed.get());
            verify(harness.portAllocator).release(2201);
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void ephemeralShutdownCompletesWithoutTheExecutorAndReportsFailedRemoval() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
            Instance instance = leasedInstance("i-ephemeral-shutdown");
            harness.manager.launch(instance, "example/image:1", null, "us-west-2");
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals("running", instance.getState().getName());
            RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
            when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
            doThrow(new IllegalStateException("daemon refused")).doNothing().when(remove).exec();
            executor.shutdownNow();

            assertThrows(IllegalStateException.class, () -> harness.manager.terminateForShutdown(instance));
            assertEquals("shutting-down", instance.getState().getName());
            verify(harness.portAllocator, never()).release(2201);
            verify(harness.lifecycleManager, never()).removeIfExistsStrict(TEST_HELPER_ID);

            harness.manager.terminateForShutdown(instance);

            assertEquals("terminated", instance.getState().getName());
            verify(remove, times(2)).exec();
            verify(harness.lifecycleManager).removeIfExistsStrict(TEST_HELPER_ID);
            verify(harness.portAllocator).release(2201);
            verify(harness.vpcNetworkManager).releasePrivateIp("000000000000", "us-west-2", "subnet-lease", "10.0.1.10");
            verify(harness.metadataServer).unregisterInstance(instance);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void launchRegistersAllGuestAddressesBeforeUserData() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        InspectContainerResponse response = vpcAttachedInspectResponse("10.0.1.10", "172.17.0.4");
        Map<String, ContainerNetwork> networks = new LinkedHashMap<>(response.getNetworkSettings().getNetworks());
        networks.put("shared", new ContainerNetwork().withIpv4Address("192.0.2.10"));
        when(response.getNetworkSettings().getNetworks()).thenReturn(networks);
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(command);
        when(command.exec()).thenReturn(response);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance guest = instance("i-three-networks");

        harness.manager.launch(guest, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(guest.getState().getName()), Duration.ofSeconds(2));
        verify(harness.metadataServer).reconcileContainerAddresses(
                Set.of("10.0.1.10", "172.17.0.4", "192.0.2.10"), guest);
    }

    @Test
    void launchPublishesTheReachableAddressForAnElasticIpTheInterfaceCarries() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.11");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        // Launched on an existing interface, so not due an automatic address, but the interface
        // carries an Elastic IP, which RunInstances records on the instance.
        Instance instance = instance("i-carried");
        instance.setAssociatePublicIp(false);
        instance.setPublicIpAddress("54.9.9.9");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "127.0.0.1".equals(instance.getPublicIpAddress()), Duration.ofSeconds(2));
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "true, true"})
    void launchRefreshesPublicRoutingAfterGuestStartupOnlyForAChangedAddress(
            boolean addressChanges, boolean metadataAlreadyUpdated) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Ec2ContainerManager manager = spy(harness.manager);
        Instance instance = instance("i-publication");
        instance.setAssociatePublicIp(true);
        when(harness.config.services().ec2().publicIpFromServiceNetwork()).thenReturn(true);
        List<String> publishedAddresses = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            assertEquals("pending", instance.getState().getName(),
                    "routes must be published before EC2 reports running");
            publishedAddresses.add(invocation.getArgument(1, String.class));
            return null;
        }).when(manager).refreshPublicRouting(eq(instance), anyString());
        doAnswer(invocation -> {
            if (addressChanges) {
                instance.setServiceNetworkIp("192.0.2.10");
            }
            if (metadataAlreadyUpdated) {
                instance.setPublicIpAddress("192.0.2.10");
                instance.setPublicDnsName("192.0.2.10");
            }
            return null;
        }).when(manager).refreshServiceNetworkIp(instance);

        try {
            manager.launch(instance,
                    new ResolvedAmiImage("test/native-guest:1", ResolvedAmiImage.IMAGE_RUNTIME, true, null),
                    null, "us-west-2");
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "launch worker should finish");
            assertEquals("running", instance.getState().getName());
            List<String> expectedAddresses = addressChanges
                    ? List.of("127.0.0.1", "192.0.2.10") : List.of("127.0.0.1");
            assertEquals(expectedAddresses, publishedAddresses,
                    "only addresses not yet routed should be published before EC2 reports running");
            assertEquals(expectedAddresses.getLast(), instance.getPublicIpAddress());
            InOrder order = inOrder(manager, harness.lifecycleManager);
            order.verify(manager).refreshPublicRouting(instance, "127.0.0.1");
            order.verify(harness.lifecycleManager).startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class));
            if (addressChanges) {
                order.verify(manager).refreshPublicRouting(instance, "192.0.2.10");
            }
        } finally {
            manager.stop();
        }
    }

    @Test
    void launchWaitsForContainerBridgeIpBeforeRegisteringImds() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse noIp = inspectResponse(null);
        InspectContainerResponse withIp = inspectResponse("172.18.0.9");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(noIp).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        Instance instance = instance("i-waitip");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        assertEquals(TEST_CONTAINER_ID, instance.getDockerContainerId());
        assertEquals("172.18.0.9", instance.getContainerBridgeIp());
        assertEquals("172.18.0.9", instance.getPrivateIpAddress());
        verify(inspect, atLeast(2)).exec();
        verify(harness.metadataServer).registerContainer("172.18.0.9", "i-waitip", instance);
    }

    @Test
    void launchTerminatesWhenContainerBridgeIpNeverAppears() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse noIp = inspectResponse(null);
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(noIp);

        Instance instance = instance("i-noip");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.lifecycleManager, timeout(2_000)).removeIfExistsStrict(TEST_HELPER_ID);
        verify(harness.lifecycleManager, never()).create(any(ContainerSpec.class));
        verify(harness.lifecycleManager, never()).startCreated(anyString(), any());
        verify(harness.portAllocator, timeout(2_000)).release(2201);
        assertNull(instance.getDockerContainerId());
        verify(harness.metadataServer, never()).registerContainer(anyString(), anyString(), any());
        // The address was looked for thirty times, half a second apart, before the launch gave up.
        assertEquals(TestTime.DEFAULT_START.plusSeconds(15), harness.time().now());
    }

    @Test
    void launchRetriesWithNextPortWhenDockerReportsHostPortCollision() throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.portAllocator.allocate(anyInt(), anyInt())).thenReturn(2201, 2202);
        when(harness.lifecycleManager.create(any(ContainerSpec.class)))
                .thenReturn("container-conflict", TEST_CONTAINER_ID);
        when(harness.lifecycleManager.startCreated(anyString(), any(ContainerSpec.class)))
                .thenThrow(new RuntimeException("Bind for 127.0.0.1:2201 failed: port is already allocated"))
                .thenReturn(null);

        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.12");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        Instance instance = instance("i-port-collision");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        assertEquals(2202, instance.getSshHostPort());
        verify(harness.lifecycleManager).removeIfExists("container-conflict");
        verify(harness.portAllocator).release(2201);
        verify(harness.portAllocator, never()).markReserved(anyInt());
        verify(harness.builder).withPortBinding(22, 2201, "127.0.0.1");
        verify(harness.builder).withPortBinding(22, 2202, "127.0.0.1");
    }

    @Test
    void launchCancelledDuringAPortRetryStaysTerminatedWhenNoPortIsLeft() throws Exception {
        ExecutorService launchExecutor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(launchExecutor, Duration.ofSeconds(1));
        Instance instance = instance("i-cancelled-during-retry");
        AtomicBoolean dockerGone = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        PingCmd ping = mock(PingCmd.class);
        when(harness.dockerClient.pingCmd()).thenReturn(ping);
        when(ping.exec()).thenAnswer(invocation -> {
            if (dockerGone.get()) {
                throw new RuntimeException("No such file or directory");
            }
            return null;
        });
        when(harness.portAllocator.allocate(anyInt(), anyInt()))
                .thenReturn(2201)
                .thenThrow(new RuntimeException("No free port available in range 2200-2299"));
        when(harness.lifecycleManager.startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class)))
                .thenThrow(new RuntimeException("Bind for 0.0.0.0:2201 failed: port is already allocated"));
        doAnswer(invocation -> {
            cancelled.set(harness.manager.cancelLaunch(instance));
            dockerGone.set(true);
            return null;
        }).when(harness.lifecycleManager).removeIfExists(TEST_CONTAINER_ID);

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");
        launchExecutor.shutdown();
        assertTrue(launchExecutor.awaitTermination(5, TimeUnit.SECONDS), "launch worker should finish");

        assertTrue(cancelled.get(), "the launch should be cancelled while the refused container is removed");
        assertEquals("terminated", instance.getState().getName());
    }

    @Test
    void launchReleasesPortAndCleansUpContainerAfterNonRetryableStartFailure() throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.lifecycleManager.startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class)))
                .thenThrow(new RuntimeException("Docker daemon unavailable"));

        Instance instance = instance("i-start-failure");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.lifecycleManager).removeIfExists(TEST_CONTAINER_ID);
        verify(harness.portAllocator).release(2201);
    }

    @Test
    void launchTerminatesBeforeCleanupFailure() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        when(harness.lifecycleManager.startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class)))
                .thenThrow(new RuntimeException("Docker daemon unavailable"));
        Instance instance = instance("i-cleanup-failure");
        AtomicReference<String> stateAtCleanup = new AtomicReference<>();
        doAnswer(invocation -> {
            stateAtCleanup.compareAndSet(null, instance.getState().getName());
            throw new RuntimeException("port-forward cleanup failed");
        }).when(harness.portForwardManager).unpublishAll(instance);
        try {
            harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "launch worker should finish");
            verify(harness.portForwardManager).unpublishAll(instance);
            assertEquals("terminated", stateAtCleanup.get(),
                    "the instance must become terminated before cleanup can fail");
            assertEquals("terminated", instance.getState().getName());
        } finally {
            executor.shutdownNow();
            harness.manager.stop();
        }
    }

    @Test
    void cancelledLaunchRemovesContainerThatStartsAfterCancellation() throws Exception {
        ExecutorService launchExecutor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(launchExecutor, Duration.ofMinutes(30));
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch allowStart = new CountDownLatch(1);
        when(harness.lifecycleManager.startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class))).thenAnswer(invocation -> {
            startEntered.countDown();
            assertTrue(allowStart.await(2, TimeUnit.SECONDS));
            return null;
        });

        Instance instance = instance("i-cancelled-launch");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        assertTrue(startEntered.await(2, TimeUnit.SECONDS), "container startup should begin");
        assertTrue(harness.manager.cancelLaunch(instance));
        verify(harness.lifecycleManager).removeIfExistsStrict(TEST_CONTAINER_ID);
        verify(harness.portAllocator).release(2201);

        allowStart.countDown();
        launchExecutor.shutdown();
        assertTrue(launchExecutor.awaitTermination(5, TimeUnit.SECONDS), "launch worker should finish");
        verify(harness.portAllocator, times(1)).release(2201);
    }

    @Test
    void cancelledLaunchRemovesContainerCreatedAfterCancellation() throws Exception {
        LaunchHarness harness = launchHarness();
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch allowCreate = new CountDownLatch(1);
        when(harness.lifecycleManager.create(any(ContainerSpec.class))).thenAnswer(invocation -> {
            createEntered.countDown();
            assertTrue(allowCreate.await(2, TimeUnit.SECONDS));
            return TEST_CONTAINER_ID;
        });

        Instance instance = instance("i-cancelled-during-create");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        assertTrue(createEntered.await(2, TimeUnit.SECONDS), "container creation should begin");
        assertTrue(harness.manager.cancelLaunch(instance));
        assertEquals("terminated", instance.getState().getName());

        allowCreate.countDown();
        verify(harness.lifecycleManager, timeout(2_000)).removeIfExists(TEST_CONTAINER_ID);
        verify(harness.portAllocator, timeout(2_000)).release(2201);
        verify(harness.lifecycleManager, never()).startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class));
        assertEquals("terminated", instance.getState().getName());
    }

    @Test
    void launchPublishesThePublicAddressBeforeTheInstanceReportsRunning() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse withIp = inspectResponse("172.18.0.10");
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        AtomicReference<String> addressWhenRunning = new AtomicReference<>("never running");
        Instance instance = new Instance() {
            @Override
            public void setState(InstanceState state) {
                if (state != null && "running".equals(state.getName())) {
                    addressWhenRunning.set(String.valueOf(getPublicIpAddress()));
                }
                super.setState(state);
            }
        };
        instance.setInstanceId("i-public-before-running");
        instance.setAssociatePublicIp(true);
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");
        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));

        // A poller that sees running, as Terraform's wait does, must already find the address.
        assertEquals("127.0.0.1", addressWhenRunning.get());
    }

    @Test
    void launchCancelledAfterItsAddressWasPublishedKeepsNoAddress() throws Exception {
        try (ExecutorService launchExecutor = Executors.newSingleThreadExecutor()) {
            LaunchHarness harness = launchHarness(launchExecutor, Duration.ofMinutes(30));
            InspectContainerCmd inspect = mock(InspectContainerCmd.class);
            when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
            InspectContainerResponse withIp = inspectResponse("172.18.0.10");
            when(inspect.exec()).thenReturn(withIp);
            harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
            Instance instance = instance("i-cancelled-with-address");
            instance.setAssociatePublicIp(true);
            // Cancellation precedes address publication; the worker must finish its cleanup.
            doAnswer(invocation -> {
                instance.setState(InstanceState.terminated());
                return null;
            }).when(harness.metadataServer).registerContainer(any(), any(), any());
            harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

            launchExecutor.shutdown();
            assertTrue(launchExecutor.awaitTermination(5, TimeUnit.SECONDS), "launch worker should finish");
            assertEquals("terminated", instance.getState().getName());
            assertNull(instance.getDockerContainerId());
            assertNull(instance.getPublicIpAddress());
            assertNull(instance.getPublicDnsName());
        }
    }

    @Test
    void launchMarksInstanceRunningBeforeUserDataCompletes() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.10");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        CountDownLatch userDataStarted = new CountDownLatch(1);
        CountDownLatch finishUserData = new CountDownLatch(1);
        harness.stubSuccessfulExecs(userDataStarted, finishUserData);
        Instance instance = instance("i-userdata");
        instance.setUserData("#!/bin/sh\necho ready\n");

        AtomicInteger postLaunchCalls = new AtomicInteger();
        harness.manager.launch(instance, ResolvedAmiImage.minimal("ubuntu:24.04"), null, "us-west-2",
                Ec2PortForwardManager.DesiredPorts.NONE, List.of(), Map.of(), List::of, () -> {
                    assertEquals("running", instance.getState().getName());
                    assertEquals(1, userDataStarted.getCount(), "storage must be attached before user data starts");
                    postLaunchCalls.incrementAndGet();
                });

        try {
            assertTrue(userDataStarted.await(2, TimeUnit.SECONDS), "user data should start");
            assertEquals("running", instance.getState().getName());
            assertEquals(1, postLaunchCalls.get());
            assertFalse(finishUserData.await(10, TimeUnit.MILLISECONDS), "user data should still be blocked");
        } finally {
            finishUserData.countDown();
        }
        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
    }

    @Test
    void failedPostLaunchStorageAttachmentRetiresTheGuestBeforeUserData() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        InspectContainerResponse withIp = inspectResponse("172.18.0.10");
        when(inspect.exec()).thenReturn(withIp);
        CountDownLatch userDataStarted = new CountDownLatch(1);
        harness.stubSuccessfulExecs(userDataStarted, new CountDownLatch(0));
        Instance instance = instance("i-storage-failure");
        instance.setUserData("#!/bin/sh\necho ready\n");
        harness.manager.launch(instance, ResolvedAmiImage.minimal("ubuntu:24.04"), null, "us-west-2",
                Ec2PortForwardManager.DesiredPorts.NONE, List.of(), Map.of(), List::of, () -> {
                    throw new IllegalStateException("attachment failed");
                });

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.lifecycleManager, timeout(2_000)).removeIfExistsStrict(TEST_CONTAINER_ID);
        verify(harness.portAllocator, timeout(2_000)).release(2201);
        assertEquals(1, userDataStarted.getCount());
    }

    @Test
    void userDataIsCopiedAndExecutedOutsideGuestTemporaryMounts() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        InspectContainerResponse withIp = inspectResponse("172.18.0.10");
        when(inspect.exec()).thenReturn(withIp);
        CountDownLatch userDataStarted = new CountDownLatch(1);
        harness.stubSuccessfulExecs(userDataStarted, new CountDownLatch(0));
        CopyArchiveToContainerCmd copy = harness.dockerClient.copyArchiveToContainerCmd(TEST_CONTAINER_ID);
        Instance instance = instance("i-userdata-persistent-path");
        instance.setUserData("#!/bin/sh\necho ready\n");

        harness.manager.launch(instance, "amazonlinux:2023", null, "us-west-2");

        assertTrue(userDataStarted.await(2, TimeUnit.SECONDS), "user data should start");
        verify(copy).withRemotePath("/var/lib");
        verify(copy, never()).withRemotePath("/tmp");
        assertTrue(harness.executedCommands.stream()
                .anyMatch(command -> Arrays.equals(command, new String[]{"/var/lib/user-data.sh"})));
    }

    @Test
    void launchAppliesBackpressureWhenDockerLaunchesAreSaturated() throws Exception {
        ThreadPoolExecutor launchExecutor = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                Ec2ContainerManager.BLOCKING_BACKPRESSURE);
        LaunchHarness harness = launchHarness(launchExecutor, Duration.ofSeconds(1));
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        when(harness.lifecycleManager().create(any(ContainerSpec.class))).thenAnswer(invocation -> {
            createEntered.countDown();
            releaseCreate.await(2, TimeUnit.SECONDS);
            throw new RuntimeException("test launch failure");
        });

        try {
            harness.manager().launch(instance("i-backpressure-1"), "ubuntu:24.04", null, "us-west-2");
            harness.manager().launch(instance("i-backpressure-2"), "ubuntu:24.04", null, "us-west-2");
            Future<?> waitingLaunch = callerExecutor.submit(() ->
                    harness.manager().launch(instance("i-backpressure-3"), "ubuntu:24.04", null, "us-west-2"));

            assertTrue(createEntered.await(2, TimeUnit.SECONDS), "worker launch should enter Docker launch");
            assertFalse(waitingLaunch.isDone(), "saturated launch should wait for queue capacity");

            releaseCreate.countDown();
            waitingLaunch.get(2, TimeUnit.SECONDS);
        } finally {
            releaseCreate.countDown();
            harness.manager().stop();
            callerExecutor.shutdownNow();
            launchExecutor.shutdownNow();
        }
    }

    @Test
    void userDataTimeoutClosesDockerExecStream() throws Exception {
        LaunchHarness harness = launchHarness(new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.CallerRunsPolicy()), Duration.ofMillis(50));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient().inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        InspectContainerResponse withIp = inspectResponse("172.18.0.30");
        when(inspect.exec()).thenReturn(withIp);
        Closeable stream = mock(Closeable.class);
        CountDownLatch userDataStarted = new CountDownLatch(1);
        stubUserDataCallback(harness, stream, userDataStarted);
        Instance instance = instance("i-userdata-timeout");
        instance.setUserData("#!/bin/sh\necho ready\n");

        try {
            harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");
            assertTrue(userDataStarted.await(2, TimeUnit.SECONDS), "user data should start");
            verify(stream, timeout(2_000)).close();
        } finally {
            harness.manager().stop();
        }
    }

    @Test
    void shutdownClosesActiveUserDataExecStream() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient().inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        InspectContainerResponse withIp = inspectResponse("172.18.0.31");
        when(inspect.exec()).thenReturn(withIp);
        Closeable stream = mock(Closeable.class);
        CountDownLatch userDataStarted = new CountDownLatch(1);
        stubUserDataCallback(harness, stream, userDataStarted);
        Instance instance = instance("i-userdata-shutdown");
        instance.setUserData("#!/bin/sh\necho ready\n");

        harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");
        assertTrue(userDataStarted.await(2, TimeUnit.SECONDS), "user data should start");

        harness.manager().stop();

        verify(stream, timeout(2_000)).close();
    }

    @Test
    void launchCreatesSshdPrivilegeSeparationDirectoryBeforeStartingSshd() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.12");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        harness.manager.launch(instance("i-sshd"), "ubuntu:24.04", TEST_SSH_PUBLIC_KEY, "us-west-2");

        awaitUntil(() -> commandIndex(harness.executedCommands, "/usr/sbin/sshd") >= 0, Duration.ofSeconds(2));

        int mkdirIndex = commandIndex(harness.executedCommands, "mkdir", "-p", "/run/sshd");
        int sshdIndex = commandIndex(harness.executedCommands, "/usr/sbin/sshd");
        assertTrue(mkdirIndex >= 0,
                "sshd startup should create the /run/sshd privilege-separation directory");
        assertTrue(mkdirIndex < sshdIndex,
                "/run/sshd must be created before sshd starts, otherwise sshd exits with "
                        + "\"Missing privilege separation directory\"");
    }

    @Test
    void anAttachStreamErrorOnOneSshdPathStillTriesTheNext() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.21");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        doAnswer(invocation -> {
            ExecStartCmd execStart = mock(ExecStartCmd.class);
            when(execStart.exec(any())).thenAnswer(startInvocation -> {
                ResultCallback<Frame> callback = startInvocation.getArgument(0);
                String[] command = harness.executedCommands.get(harness.executedCommands.size() - 1);
                if (Arrays.equals(command, new String[]{"/usr/sbin/sshd"})) {
                    callback.onError(new IllegalStateException("attach stream broke"));
                } else {
                    callback.onComplete();
                }
                return callback;
            });
            return execStart;
        }).when(harness.dockerClient).execStartCmd(anyString());

        harness.manager.launch(instance("i-sshd-retry"), "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> commandIndex(harness.executedCommands, "/usr/local/sbin/sshd") >= 0, Duration.ofSeconds(2));
        assertTrue(commandIndex(harness.executedCommands, "/usr/sbin/sshd")
                < commandIndex(harness.executedCommands, "/usr/local/sbin/sshd"));
    }

    @Test
    void anInterruptWhileStartingSshdStopsTryingFurtherSshdPaths() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.22");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        doAnswer(invocation -> {
            ExecStartCmd execStart = mock(ExecStartCmd.class);
            when(execStart.exec(any())).thenAnswer(startInvocation -> {
                ResultCallback<Frame> callback = startInvocation.getArgument(0);
                String[] command = harness.executedCommands.get(harness.executedCommands.size() - 1);
                if (Arrays.equals(command, new String[]{"/usr/sbin/sshd"})) {
                    Thread.currentThread().interrupt();
                } else {
                    callback.onComplete();
                }
                return callback;
            });
            return execStart;
        }).when(harness.dockerClient).execStartCmd(anyString());
        Instance instance = instance("i-sshd-interrupted");
        instance.setUserData("#!/bin/sh\necho ready\n");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        // UserData is the next step after sshd, so its exec marks sshd startup as finished.
        awaitUntil(() -> commandIndex(harness.executedCommands, "/var/lib/user-data.sh") >= 0, Duration.ofSeconds(2));
        assertEquals(-1, commandIndex(harness.executedCommands, "/usr/local/sbin/sshd"));
    }

    @Test
    void launchWithoutKeyPairStillStartsSshd() throws Exception {
        // Regression for #2184: sshd previously only started when a key pair was supplied, so
        // run-instances without --key-name left no daemon listening at all ("connection refused")
        // instead of matching real AWS AMIs, which run sshd as part of normal boot regardless of
        // whether a key pair is attached to the instance.
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.20");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-nokeypair");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        ExecCreateCmd execCreate = harness.dockerClient.execCreateCmd(TEST_CONTAINER_ID);
        verify(execCreate, timeout(2000)).withCmd(eq(new String[]{"/usr/sbin/sshd"}));
        // No key pair was supplied, so nothing should have been written to authorized_keys.
        verify(harness.dockerClient, never()).copyArchiveToContainerCmd(TEST_CONTAINER_ID);
    }

    private static final String[] SSHD_INSTALL_PROBE_CMD = {"sh", "-c",
            "unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME;"
            + "if ! command -v sshd >/dev/null 2>&1 || ! command -v scp >/dev/null 2>&1; then"
            + "  if command -v dnf >/dev/null 2>&1; then dnf install -y openssh-server openssh-clients >/dev/null 2>&1;"
            + "  elif command -v yum >/dev/null 2>&1; then yum install -y openssh-server openssh-clients >/dev/null 2>&1;"
            + "  elif command -v apt-get >/dev/null 2>&1; then DEBIAN_FRONTEND=noninteractive apt-get install -y openssh-server openssh-client >/dev/null 2>&1;"
            + "  elif command -v apk >/dev/null 2>&1; then apk add --no-cache openssh >/dev/null 2>&1;"
            + "  fi;"
            + "fi;"
            + "command -v sshd >/dev/null 2>&1 || exit 1;"
            + "command -v scp >/dev/null 2>&1 || exit 2"};

    @Test
    void launchWhenSshdInstallFails_doesNotAttemptKeygenOrStart() throws Exception {
        // Regression reported as a follow-up on #2245 (duytanisme): sshd not starting even when a
        // key pair IS provided. Root cause: execInContainer() discards exit codes entirely, so a
        // shell if/elif chain with no matching package manager - or whose install command itself
        // failed (no network yet, apt lock held, etc.) - still exits 0 by bash convention with no
        // final check, meaning startSshd() proceeded to ssh-keygen and sshd regardless and still
        // logged success. This forces the install probe to report failure and asserts the later
        // steps are never even attempted, rather than running against a daemon that was never
        // installed.
        ExecCreateCmd execCreate = launchWithSshdInstallProbeExitCode(1, "i-sshdinstallfail");

        verify(execCreate, timeout(2000)).withCmd(eq(SSHD_INSTALL_PROBE_CMD));
        verify(execCreate, never()).withCmd(eq(new String[]{"ssh-keygen", "-A"}));
        verify(execCreate, never()).withCmd(eq(new String[]{"/usr/sbin/sshd"}));
    }

    @Test
    void launchWhenOnlySshClientInstallFails_stillStartsSshd() throws Exception {
        // The probe's guard now also fires when scp is missing, so a guest that already has sshd
        // but whose client-package install fails must not be reported as a plain success - that is
        // exactly the state where Packer's default scp-based file transfer breaks. It is not fatal
        // either: sshd is present and the instance is worth making reachable. The script separates
        // the two with exit 2, which warns and carries on rather than returning early.
        ExecCreateCmd execCreate = launchWithSshdInstallProbeExitCode(
                Ec2ContainerManager.SSH_CLIENT_MISSING_EXIT_CODE, "i-scpinstallfail");

        verify(execCreate, timeout(2000)).withCmd(eq(SSHD_INSTALL_PROBE_CMD));
        verify(execCreate, timeout(2000)).withCmd(eq(new String[]{"ssh-keygen", "-A"}));
        verify(execCreate, timeout(2000)).withCmd(eq(new String[]{"/usr/sbin/sshd"}));
    }

    /**
     * Launches an instance where every exec succeeds except the sshd install probe, which reports
     * {@code probeExitCode}. Returns the {@link ExecCreateCmd} the launch issued its commands
     * through so callers can assert on which steps were attempted.
     */
    private ExecCreateCmd launchWithSshdInstallProbeExitCode(int probeExitCode, String instanceId)
            throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.21");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);

        ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(harness.dockerClient.execCreateCmd(TEST_CONTAINER_ID)).thenReturn(execCreate);
        AtomicReference<String[]> currentCommand = new AtomicReference<>();
        when(execCreate.withCmd(any(String[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            currentCommand.set(args.length == 1 && args[0] instanceof String[] cmd
                    ? cmd : Arrays.copyOf(args, args.length, String[].class));
            return execCreate;
        });
        ExecCreateCmdResponse execResponse = mock(ExecCreateCmdResponse.class);
        when(execResponse.getId()).thenReturn("exec-1");
        when(execCreate.exec()).thenReturn(execResponse);

        when(harness.dockerClient.execStartCmd(anyString())).thenAnswer(invocation -> {
            ExecStartCmd execStart = mock(ExecStartCmd.class);
            when(execStart.exec(any())).thenAnswer(startInvocation -> {
                @SuppressWarnings("unchecked")
                ResultCallback<Frame> callback = startInvocation.getArgument(0);
                callback.onComplete();
                return callback;
            });
            return execStart;
        });

        // Every exec succeeds except the sshd-install probe script, simulating an image with none
        // of dnf/apt-get/apk, or whose install command itself failed.
        InspectExecCmd inspectExec = mock(InspectExecCmd.class);
        when(harness.dockerClient.inspectExecCmd(anyString())).thenReturn(inspectExec);
        when(inspectExec.exec()).thenAnswer(invocation -> {
            InspectExecResponse response = mock(InspectExecResponse.class);
            boolean isSshdInstallProbe = Arrays.equals(currentCommand.get(), SSHD_INSTALL_PROBE_CMD);
            when(response.getExitCodeLong()).thenReturn(isSshdInstallProbe ? (long) probeExitCode : 0L);
            return response;
        });

        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(harness.dockerClient.copyArchiveToContainerCmd(TEST_CONTAINER_ID)).thenReturn(copy);
        when(copy.withTarInputStream(any(InputStream.class))).thenReturn(copy);

        Instance instance = instance(instanceId);

        harness.manager.launch(instance, "ubuntu:24.04", "ssh-ed25519 AAAAtest", "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        return execCreate;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void launchPreservesSecondaryInterfaceAddress(boolean faithful) throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.config.services().ec2().awsFaithfulPrivateIp()).thenReturn(faithful);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.9");
        when(inspect.exec()).thenReturn(response);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-secondary-launch");
        instance.setPrivateIpAddress("10.0.1.10");
        InstanceNetworkInterface primary = new InstanceNetworkInterface();
        primary.setDeviceIndex(0);
        primary.setPrivateIpAddress("10.0.1.10");
        InstanceNetworkInterface secondary = new InstanceNetworkInterface();
        secondary.setDeviceIndex(1);
        secondary.setPrivateIpAddress("10.0.1.20");
        secondary.setPrivateDnsName("secondary-dns");
        instance.setNetworkInterfaces(List.of(secondary, primary));
        CountDownLatch completed = new CountDownLatch(1);
        try {
            harness.manager.launch(instance, ResolvedAmiImage.minimal("ubuntu:24.04"), null, "us-west-2",
                    Ec2PortForwardManager.DesiredPorts.NONE, List.of(), Map.of(), List::of, completed::countDown);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals("running", instance.getState().getName());
            assertEquals(faithful ? "10.0.1.10" : "172.18.0.9", instance.getPrivateIpAddress());
            assertEquals(instance.getPrivateIpAddress(), primary.getPrivateIpAddress());
            assertEquals("10.0.1.20", secondary.getPrivateIpAddress());
            assertEquals("secondary-dns", secondary.getPrivateDnsName());
        } finally {
            harness.manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rebootPublishesAddressesOnlyAfterDockerSucceeds(boolean failRestart) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        RestartContainerCmd restart = mock(RestartContainerCmd.class);
        when(harness.dockerClient.restartContainerCmd(TEST_CONTAINER_ID)).thenReturn(restart);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(restart.exec()).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            if (failRestart) {
                throw new IllegalStateException("restart rejected");
            }
            return null;
        });
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.99");
        when(inspect.exec()).thenReturn(response);
        Instance guest = instance("i-reboot");
        guest.setState(InstanceState.running());
        guest.setDockerContainerId(TEST_CONTAINER_ID);
        guest.setPrivateIpAddress("172.18.0.10");
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<String> published = new AtomicReference<>();
        try {
            harness.manager.reboot(guest, () -> {
                published.set(guest.getPrivateIpAddress());
                callbacks.incrementAndGet();
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("172.18.0.10", guest.getPrivateIpAddress());
            assertEquals(0, callbacks.get());
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(failRestart ? "172.18.0.10" : "172.18.0.99", guest.getPrivateIpAddress());
            assertEquals(failRestart ? 0 : 1, callbacks.get());
            assertEquals(failRestart ? null : "172.18.0.99", published.get());
            if (failRestart) {
                verify(harness.dockerClient, never()).inspectContainerCmd(TEST_CONTAINER_ID);
            } else {
                verify(harness.metadataServer).registerContainer("172.18.0.99", "i-reboot", guest);
                verify(harness.metadataServer).reconcileContainerAddresses(Set.of("172.18.0.99"), guest);
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            harness.manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rebootPreservesLogicalAddressesWithoutWorkloadNetworkDetails(boolean protectedWorkload) {
        List<Runnable> tasks = new ArrayList<>();
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        RestartContainerCmd restart = mock(RestartContainerCmd.class);
        when(harness.dockerClient.restartContainerCmd(TEST_CONTAINER_ID)).thenReturn(restart);
        InspectContainerResponse workload = mock(InspectContainerResponse.class);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(workload);
        if (protectedWorkload) {
            HostConfig hostConfig = mock(HostConfig.class);
            when(hostConfig.getNetworkMode()).thenReturn("container:helper-reboot");
            when(workload.getHostConfig()).thenReturn(hostConfig);
            InspectContainerResponse helper = inspectResponse("172.17.0.7");
            ContainerConfig helperConfig = mock(ContainerConfig.class);
            when(helperConfig.getLabels()).thenReturn(Map.of(
                    "io.floci.security-group.helper", "true",
                    "io.floci.service", "ec2",
                    "io.floci.resource-id", "i-protected-reboot",
                    "io.floci.owner", "4566"));
            when(helper.getConfig()).thenReturn(helperConfig);
            InspectContainerCmd inspectHelper = mock(InspectContainerCmd.class);
            when(inspectHelper.exec()).thenReturn(helper);
            when(harness.dockerClient.inspectContainerCmd("helper-reboot")).thenReturn(inspectHelper);
            when(harness.config.port()).thenReturn(4566);
            EmulatorConfig.DockerConfig dockerConfig = mock(EmulatorConfig.DockerConfig.class);
            when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
            when(harness.config.docker()).thenReturn(dockerConfig);
        }
        Instance guest = instance("i-protected-reboot");
        guest.setState(InstanceState.running());
        guest.setDockerContainerId(TEST_CONTAINER_ID);
        // A protected workload answers on its helper's address, which a workload restart keeps.
        guest.setContainerBridgeIp(protectedWorkload ? "172.17.0.7" : "172.17.0.10");
        guest.setPrivateIpAddress("10.0.1.10");
        guest.setPrivateDnsName("logical-dns");
        AtomicInteger callbacks = new AtomicInteger();
        try {
            harness.manager.reboot(guest, callbacks::incrementAndGet);
            assertEquals(1, tasks.size());
            tasks.removeFirst().run();
            assertEquals(1, callbacks.get());
            assertEquals("10.0.1.10", guest.getPrivateIpAddress());
            assertEquals("logical-dns", guest.getPrivateDnsName());
            assertEquals(protectedWorkload ? "172.17.0.7" : "172.17.0.10", guest.getContainerBridgeIp());
            verify(harness.metadataServer, never()).registerContainer(anyString(), anyString(), any());
            verify(harness.metadataServer, never()).reconcileContainerAddresses(anySet(), any());
        } finally {
            harness.manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRebootingInstanceCountsAsRunningWhileDockerRestartsIt(boolean indeterminate) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        RestartContainerCmd restart = mock(RestartContainerCmd.class);
        when(harness.dockerClient.restartContainerCmd(TEST_CONTAINER_ID)).thenReturn(restart);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(restart.exec()).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        });
        // Between Docker's stop and start the container is not running.
        when(harness.lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(false);
        doReturn(indeterminate ? ContainerLiveness.UNKNOWN : ContainerLiveness.NOT_RUNNING)
                .when(harness.lifecycleManager).probeContainer(TEST_CONTAINER_ID);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.99");
        when(inspect.exec()).thenReturn(response);
        Instance guest = instance("i-reboot-window");
        guest.setState(InstanceState.running());
        guest.setDockerContainerId(TEST_CONTAINER_ID);
        try {
            harness.manager.reboot(guest);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(harness.manager.isContainerRunning(guest),
                    "Auto Scaling must not replace an instance while EC2 reboots it");
            verify(harness.lifecycleManager, never()).probeContainer(TEST_CONTAINER_ID);
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            if (indeterminate) {
                assertThrows(IllegalStateException.class, () -> harness.manager.isContainerRunning(guest),
                        "an unknown probe must remain indeterminate after the reboot");
            } else {
                assertFalse(harness.manager.isContainerRunning(guest), "Once the reboot is over Docker answers again");
            }
            assertEquals("running", guest.getState().getName());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            harness.manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRebootThatFailsAfterDockerStoppedTheGuestStartsItAgain(boolean stillRunning) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        RestartContainerCmd restart = mock(RestartContainerCmd.class);
        when(harness.dockerClient.restartContainerCmd(TEST_CONTAINER_ID)).thenReturn(restart);
        when(restart.exec()).thenThrow(new IllegalStateException("start failed after stop"));
        when(harness.lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(stillRunning);
        StartContainerCmd start = mock(StartContainerCmd.class);
        when(harness.dockerClient.startContainerCmd(TEST_CONTAINER_ID)).thenReturn(start);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.99");
        when(inspect.exec()).thenReturn(response);
        Instance guest = instance("i-reboot-failed");
        guest.setState(InstanceState.running());
        guest.setDockerContainerId(TEST_CONTAINER_ID);
        try {
            harness.manager.reboot(guest);
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            verify(start, stillRunning ? never() : times(1)).exec();
            assertEquals("running", guest.getState().getName());
            assertEquals(TEST_CONTAINER_ID, guest.getDockerContainerId());
        } finally {
            harness.manager.stop();
        }
    }

    private static final String REPLACED_CONTAINER_ID = "container-replaced";

    /** Stubs the guest a root volume replacement starts from: owned, inside helper-replace. */
    private static RenameContainerCmd stubReplaceableGuest(LaunchHarness harness, String instanceId, String owner) {
        when(harness.config.port()).thenReturn(4566);
        EmulatorConfig.DockerConfig dockerConfig = mock(EmulatorConfig.DockerConfig.class);
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        when(harness.config.docker()).thenReturn(dockerConfig);
        InspectContainerResponse old = mock(InspectContainerResponse.class);
        ContainerConfig oldConfig = mock(ContainerConfig.class);
        // Resolve the owner before stubbing: ownerIdentity reads the config mock, which must not happen mid-stub.
        String labelOwner = owner == null ? ContainerStorageHelper.ownerIdentity(harness.config) : owner;
        when(oldConfig.getLabels()).thenReturn(Map.of(
                Ec2ContainerManager.LABEL_OWNER, labelOwner,
                Ec2ContainerManager.LABEL_RESOURCE_ID, instanceId));
        when(old.getConfig()).thenReturn(oldConfig);
        HostConfig hostConfig = mock(HostConfig.class);
        when(hostConfig.getNetworkMode()).thenReturn("container:helper-replace");
        when(old.getHostConfig()).thenReturn(hostConfig);
        InspectContainerCmd inspectOld = mock(InspectContainerCmd.class);
        when(inspectOld.exec()).thenReturn(old);
        when(harness.dockerClient.inspectContainerCmd(REPLACED_CONTAINER_ID)).thenReturn(inspectOld);
        // The new workload shares the helper's namespace, so Docker reports no network of its own.
        InspectContainerCmd inspectNew = mock(InspectContainerCmd.class);
        when(inspectNew.exec()).thenReturn(mock(InspectContainerResponse.class));
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspectNew);
        RenameContainerCmd rename = mock(RenameContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(harness.dockerClient.renameContainerCmd(REPLACED_CONTAINER_ID)).thenReturn(rename);
        StopContainerCmd stop = mock(StopContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(harness.dockerClient.stopContainerCmd(REPLACED_CONTAINER_ID)).thenReturn(stop);
        return rename;
    }

    private static Instance replaceableInstance(String instanceId) {
        Instance guest = instance(instanceId);
        guest.setRegion("us-west-2");
        guest.setState(InstanceState.running());
        guest.setDockerContainerId(REPLACED_CONTAINER_ID);
        guest.setContainerBridgeIp("172.17.0.7");
        guest.setPrivateIpAddress("10.0.1.10");
        guest.setPrivateDnsName("logical-dns");
        guest.setSshHostPort(2201);
        return guest;
    }

    @Test
    void replaceRootRecreatesTheGuestFromTheNewImageInsideTheSameHelper() {
        List<Runnable> tasks = new ArrayList<>();
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        stubReplaceableGuest(harness, "i-replace", null);
        Instance guest = replaceableInstance("i-replace");
        AtomicInteger inProgress = new AtomicInteger();
        AtomicInteger replaced = new AtomicInteger();
        AtomicReference<String> failed = new AtomicReference<>();
        try {
            harness.manager.replaceRoot(guest, ResolvedAmiImage.minimal("ubuntu:26.04"), null,
                    inProgress::incrementAndGet, replaced::incrementAndGet, failed::set);
            assertEquals(1, tasks.size());
            assertTrue(harness.manager.isContainerRunning(guest),
                    "Auto Scaling must not replace an instance while its root volume is replaced");
            verify(harness.lifecycleManager, never()).probeContainer(REPLACED_CONTAINER_ID);

            tasks.removeFirst().run();

            assertNull(failed.get());
            assertEquals(1, inProgress.get());
            assertEquals(1, replaced.get());
            verify(harness.containerBuilder).newContainer("ubuntu:26.04");
            verify(harness.builder).withNetworkMode("container:helper-replace");
            verify(harness.dockerClient).stopContainerCmd(REPLACED_CONTAINER_ID);
            verify(harness.lifecycleManager).startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class));
            verify(harness.lifecycleManager).removeIfExists(REPLACED_CONTAINER_ID);
            assertEquals(TEST_CONTAINER_ID, guest.getDockerContainerId());
            assertEquals("running", guest.getState().getName());
            assertEquals("10.0.1.10", guest.getPrivateIpAddress());
            assertEquals("logical-dns", guest.getPrivateDnsName());
            assertEquals("172.17.0.7", guest.getContainerBridgeIp());
            assertEquals(2201, guest.getSshHostPort());
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void replaceRootThatFailsToStartTheNewGuestKeepsTheOldOneRunning() {
        List<Runnable> tasks = new ArrayList<>();
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        RenameContainerCmd rename = stubReplaceableGuest(harness, "i-replace-fails", null);
        doThrow(new IllegalStateException("start refused"))
                .when(harness.lifecycleManager).startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class));
        StartContainerCmd startOld = mock(StartContainerCmd.class);
        when(harness.dockerClient.startContainerCmd(REPLACED_CONTAINER_ID)).thenReturn(startOld);
        Instance guest = replaceableInstance("i-replace-fails");
        AtomicInteger replaced = new AtomicInteger();
        AtomicReference<String> failed = new AtomicReference<>();
        try {
            harness.manager.replaceRoot(guest, ResolvedAmiImage.minimal("ubuntu:26.04"), null,
                    () -> { }, replaced::incrementAndGet, failed::set);
            tasks.removeFirst().run();

            assertEquals(0, replaced.get());
            assertTrue(failed.get() != null && failed.get().contains("start refused"), String.valueOf(failed.get()));
            assertEquals(REPLACED_CONTAINER_ID, guest.getDockerContainerId());
            assertEquals("running", guest.getState().getName());
            assertEquals("10.0.1.10", guest.getPrivateIpAddress());
            verify(harness.lifecycleManager).removeIfExists(TEST_CONTAINER_ID);
            verify(harness.lifecycleManager, never()).removeIfExists(REPLACED_CONTAINER_ID);
            // Parked under another name, then given its own name back, then started again.
            verify(rename, times(2)).exec();
            verify(startOld).exec();
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void replaceRootRefusesAGuestThisEmulatorDoesNotOwn() {
        List<Runnable> tasks = new ArrayList<>();
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        RenameContainerCmd rename = stubReplaceableGuest(harness, "i-replace-foreign", "4620");
        Instance guest = replaceableInstance("i-replace-foreign");
        AtomicReference<String> failed = new AtomicReference<>();
        try {
            harness.manager.replaceRoot(guest, ResolvedAmiImage.minimal("ubuntu:26.04"), null,
                    () -> { }, () -> { }, failed::set);
            tasks.removeFirst().run();

            assertTrue(failed.get() != null && failed.get().contains("not owned"), String.valueOf(failed.get()));
            verify(rename, never()).exec();
            verify(harness.lifecycleManager, never()).create(any(ContainerSpec.class));
            assertEquals(REPLACED_CONTAINER_ID, guest.getDockerContainerId());
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void rebootWithoutAContainerDoesNotPublishAddresses() {
        LaunchHarness harness = launchHarness();
        Instance guest = instance("i-no-reboot-container");
        guest.setState(InstanceState.running());
        AtomicInteger callbacks = new AtomicInteger();
        try {
            harness.manager.reboot(guest, callbacks::incrementAndGet);
            assertEquals(0, callbacks.get());
            verify(harness.dockerClient, never()).restartContainerCmd(anyString());
        } finally {
            harness.manager.stop();
        }
    }

    private static LaunchHarness launchHarness() {
        return launchHarness(null, Duration.ofMinutes(30));
    }

    private static LaunchHarness launchHarness(ExecutorService executor, Duration userDataTimeout) {
        return launchHarness(executor, userDataTimeout, null, null);
    }

    private static LaunchHarness launchHarness(ExecutorService executor, Duration userDataTimeout,
                                               ExecutorService retryExecutor, Ec2ContainerPause pause) {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("ubuntu:24.04"));

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any(ContainerSpec.class))).thenReturn(TEST_CONTAINER_ID);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);
        lenient().when(lifecycleManager.probeContainer(TEST_CONTAINER_ID)).thenAnswer(ignored ->
                lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)
                        ? ContainerLiveness.RUNNING : ContainerLiveness.NOT_RUNNING);

        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.resolve()).thenReturn("floci");
        ContainerReachableEndpoint reachableEndpoint = mock(ContainerReachableEndpoint.class);
        when(reachableEndpoint.baseUrl()).thenReturn("http://localhost.floci.io:4680");
        when(reachableEndpoint.baseUrlWithTls(true)).thenReturn("http://localhost.floci.io:4680");
        PortAllocator portAllocator = mock(PortAllocator.class);
        when(portAllocator.allocate(anyInt(), anyInt())).thenReturn(2201);
        when(portAllocator.allocateAndStart(anyInt(), anyInt(), any())).thenCallRealMethod();

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.sshPortRangeStart()).thenReturn(2200);
        when(ec2.sshPortRangeEnd()).thenReturn(2299);
        when(ec2.imdsPort()).thenReturn(9169);
        when(ec2.metadataHelperImage()).thenReturn("test/network-helper:1");
        when(ec2.instanceResourceLimits()).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        DockerClient streamingDockerClient = mock(DockerClient.class);
        when(lifecycleManager.getStreamingDockerClient()).thenReturn(streamingDockerClient);
        InspectImageCmd inspectImage = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd(anyString())).thenReturn(inspectImage);
        stubDockerPing(dockerClient, true);
        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        Ec2PortForwardManager portForwardManager = mock(Ec2PortForwardManager.class);
        VpcNetworkManager vpcNetworkManager = mock(VpcNetworkManager.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        TestTime time = TestTime.create();
        Ec2ContainerManager manager = new Ec2ContainerManager(
                containerBuilder,
                lifecycleManager,
                logStreamer,
                mock(ContainerDetector.class),
                dockerHostResolver,
                dockerClient,
                portAllocator,
                config,
                metadataServer,
                portForwardManager,
                regionResolver,
                mock(ContainerNetworkReachability.class),
                vpcNetworkManager,
                reachableEndpoint,
                new Ec2InstanceTypeCatalog(),
                executor != null ? executor : Ec2ContainerManager.createLaunchExecutor(),
                retryExecutor != null ? retryExecutor : Ec2ContainerManager.createServiceNetworkRetryExecutor(),
                userDataTimeout,
                pause != null ? pause : timelinePause(time), time.newScheduler(), time::nanoTime, time.clock());
        ContainerLifecycleManager.ContainerInfo helper = new ContainerLifecycleManager.ContainerInfo(TEST_HELPER_ID, Map.of());
        when(lifecycleManager.createAndStart(any(ContainerSpec.class))).thenReturn(helper);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse inspected = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(inspected);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        LaunchHarness harness = new LaunchHarness(manager, lifecycleManager, dockerClient, streamingDockerClient, metadataServer,
                logStreamer, containerBuilder, builder,
                portAllocator, portForwardManager, config, vpcNetworkManager, new CopyOnWriteArrayList<>(), time);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        return harness;
    }

    // ── startup reconciliation of EC2 containers orphaned by a previous run ──────

    @Test
    void launchStampsTheOwnerPortLabelOnTheInstanceContainer() throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.config.port()).thenReturn(4680);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.12");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-owned");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        // Without this label the reconciler cannot tell one emulator's containers from another's
        // on a shared Docker daemon, so it could only ever be unsafe or a no-op.
        verify(harness.builder).withLabels(Map.of(Ec2ContainerManager.LABEL_OWNER, "4680"));
    }

    @Test
    void reconcileOrphanedContainersRemovesOnlyThisProcessesUnrecordedContainers() {
        LaunchHarness harness = reconcileHarness(true, 4680);
        stubContainerListing(harness.dockerClient,
                labelled("c-orphan", "4680", "us-east-1", "i-orphan"),
                labelled("c-live", "4680", "us-east-1", "i-live"),
                labelled("c-sibling", "4620", "us-east-1", "i-sibling"));

        // Only i-live still has a record. i-sibling belongs to the Floci on :4620 sharing this
        // daemon and must not be touched, however orphaned it looks from here.
        int removed = harness.manager.reconcileOrphanedContainers(
                (region, instanceId) -> "i-live".equals(instanceId));

        verify(harness.lifecycleManager, never()).removeIfExists("c-sibling");
        verify(harness.lifecycleManager, never()).removeIfExists("c-live");
        verify(harness.lifecycleManager).removeIfExists("c-orphan");
        assertEquals(1, removed);
    }

    @Test
    void reconcileOrphanedContainersLeavesStoppedInstancesAlone() {
        LaunchHarness harness = reconcileHarness(true, 4680);
        stubContainerListing(harness.dockerClient, labelled("c-stopped", "4680", "us-east-1", "i-stopped"));

        // Floci stops every running container on shutdown and keeps the id; the record comes
        // back as `stopped`, which stillDeclared answers true for. Sweeping these would break
        // stop/start across a restart.
        int removed = harness.manager.reconcileOrphanedContainers((region, instanceId) -> true);

        verify(harness.lifecycleManager, never()).removeIfExists(anyString());
        assertEquals(0, removed);
    }

    @Test
    void reconcileOrphanedContainersIgnoresContainersPredatingTheOwnerLabel() {
        LaunchHarness harness = reconcileHarness(true, 4680);
        Container unowned = mock(Container.class);
        when(unowned.getLabels()).thenReturn(Map.of(
                Ec2ContainerManager.LABEL_SERVICE, Ec2ContainerManager.SERVICE_VALUE,
                Ec2ContainerManager.LABEL_REGION, "us-east-1",
                Ec2ContainerManager.LABEL_RESOURCE_ID, "i-legacy"));
        stubContainerListing(harness.dockerClient, unowned);

        int removed = harness.manager.reconcileOrphanedContainers((region, instanceId) -> false);

        verify(harness.lifecycleManager, never()).removeIfExists(anyString());
        assertEquals(0, removed);
    }

    @Test
    void reconcileOrphanedContainersSparesAnotherNamespacesContainerOnTheSamePort() {
        // Two Flocis can share a daemon on the same internal port and are separated only by the
        // resource namespace. Keying ownership on the port alone made each reap the other's live
        // containers; the owner label carries the namespace so a sibling's container is not ours.
        LaunchHarness harness = reconcileHarness(true, 4566, "alpha");
        stubContainerListing(harness.dockerClient,
                labelled("c-ours", "alpha/4566", "us-east-1", "i-ours"),
                labelled("c-sibling", "beta/4566", "us-east-1", "i-sibling"));

        int removed = harness.manager.reconcileOrphanedContainers((region, instanceId) -> false);

        assertEquals(1, removed);
        verify(harness.lifecycleManager).removeIfExists("c-ours");
        verify(harness.lifecycleManager, never()).removeIfExists("c-sibling");
    }

    @Test
    void reconcileOrphanedContainersFindsOwnersUnderTheLegacyKeyOrBoth() {
        LaunchHarness harness = reconcileHarness(true, 4680);
        stubContainerListing(harness.dockerClient,
                withOwners("c-legacy", Map.of("floci_owner_port", "4680"), "i-legacy"),
                withOwners("c-both", Map.of("io.floci.owner", "4680", "floci_owner_port", "4680"), "i-both"),
                withOwners("c-legacy-sibling", Map.of("floci_owner_port", "4620"), "i-sibling"));

        int removed = harness.manager.reconcileOrphanedContainers((region, instanceId) -> false);

        assertEquals(2, removed);
        verify(harness.lifecycleManager).removeIfExists("c-legacy");
        verify(harness.lifecycleManager).removeIfExists("c-both");
        verify(harness.lifecycleManager, never()).removeIfExists("c-legacy-sibling");
    }

    @Test
    void reconcileOrphanedContainersLeavesAContainerWhoseOwnersDisagreeAlone() {
        LaunchHarness harness = reconcileHarness(true, 4680);
        stubContainerListing(harness.dockerClient,
                withOwners("c-new-ours", Map.of("io.floci.owner", "4680", "floci_owner_port", "4620"), "i-1"),
                withOwners("c-legacy-ours", Map.of("io.floci.owner", "4620", "floci_owner_port", "4680"), "i-2"));

        int removed = harness.manager.reconcileOrphanedContainers((region, instanceId) -> false);

        assertEquals(0, removed);
        verify(harness.lifecycleManager, never()).removeIfExists(anyString());
    }

    private static Container withOwners(String containerId, Map<String, String> owners, String instanceId) {
        Map<String, String> labels = new HashMap<>(owners);
        labels.put(Ec2ContainerManager.LABEL_SERVICE, Ec2ContainerManager.SERVICE_VALUE);
        labels.put(Ec2ContainerManager.LABEL_REGION, "us-east-1");
        labels.put(Ec2ContainerManager.LABEL_RESOURCE_ID, instanceId);
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(containerId);
        when(container.getLabels()).thenReturn(labels);
        return container;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableDockerDuringReconciliationDoesNotPreventStartupOrRetry(boolean helperListingFails) {
        LaunchHarness harness = reconcileHarness(true, 4680);
        ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
        when(harness.dockerClient.listContainersCmd()).thenReturn(listing);
        if (helperListingFails) {
            when(listing.exec()).thenReturn(List.of())
                    .thenThrow(new IllegalStateException("Docker is unavailable"))
                    .thenReturn(List.of());
        } else {
            when(listing.exec()).thenThrow(new IllegalStateException("Docker is unavailable"))
                    .thenReturn(List.of());
        }

        assertEquals(0, harness.manager.reconcileOrphanedContainers((region, instance) -> false));
        assertEquals(0, harness.manager.reconcileOrphanedContainers((region, instance) -> false));
        verify(listing, times(helperListingFails ? 4 : 3)).exec();
        verify(harness.lifecycleManager, never()).removeIfExists(anyString());
    }

    @Test
    void reconcileOrphanedContainersDoesNothingWhenDisabled() {
        LaunchHarness harness = reconcileHarness(false, 4680);

        assertEquals(0, harness.manager.reconcileOrphanedContainers((region, instanceId) -> false));

        verify(harness.dockerClient, never()).listContainersCmd();
    }

    private static Container labelled(String containerId, String ownerPort, String region, String instanceId) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(containerId);
        when(container.getLabels()).thenReturn(Map.of(
                Ec2ContainerManager.LABEL_SERVICE, Ec2ContainerManager.SERVICE_VALUE,
                Ec2ContainerManager.LABEL_OWNER, ownerPort,
                Ec2ContainerManager.LABEL_REGION, region,
                Ec2ContainerManager.LABEL_RESOURCE_ID, instanceId));
        return container;
    }

    private static void stubContainerListing(DockerClient dockerClient, Container... containers) {
        ListContainersCmd listCmd = mock(ListContainersCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(new ArrayList<>(Arrays.asList(containers)));
    }

    private static LaunchHarness reconcileHarness(boolean reconcileEnabled, int ownerPort) {
        LaunchHarness harness = launchHarness();
        when(harness.config.port()).thenReturn(ownerPort);
        when(harness.config.services().ec2().reconcileContainersOnStartup()).thenReturn(reconcileEnabled);
        return harness;
    }

    private static LaunchHarness reconcileHarness(boolean reconcileEnabled, int ownerPort, String namespace) {
        LaunchHarness harness = reconcileHarness(reconcileEnabled, ownerPort);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(docker.resourceNamespace()).thenReturn(Optional.ofNullable(namespace));
        when(harness.config.docker()).thenReturn(docker);
        return harness;
    }

    private static void stubUserDataCallback(LaunchHarness harness, Closeable stream,
                                             CountDownLatch userDataStarted) throws Exception {
        AtomicReference<String[]> currentCommand = new AtomicReference<>();
        ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        ExecCreateCmdResponse metadataExec = mock(ExecCreateCmdResponse.class);
        ExecCreateCmdResponse userDataExec = mock(ExecCreateCmdResponse.class);
        when(metadataExec.getId()).thenReturn("metadata-exec");
        when(userDataExec.getId()).thenReturn("userdata-exec");
        when(harness.dockerClient().execCreateCmd(TEST_CONTAINER_ID)).thenReturn(execCreate);
        when(execCreate.withCmd(any(String[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            currentCommand.set(args.length == 1 && args[0] instanceof String[] command
                    ? command : Arrays.copyOf(args, args.length, String[].class));
            return execCreate;
        });
        when(execCreate.exec()).thenAnswer(invocation -> {
            String[] command = currentCommand.get();
            return command != null && command.length == 1 && "/var/lib/user-data.sh".equals(command[0])
                    ? userDataExec : metadataExec;
        });

        Answer<ExecStartCmd> start = invocation -> {
            String execId = invocation.getArgument(0);
            ExecStartCmd execStart = mock(ExecStartCmd.class);
            when(execStart.exec(any())).thenAnswer(startInvocation -> {
                @SuppressWarnings("unchecked")
                ResultCallback<Frame> callback = startInvocation.getArgument(0);
                if ("userdata-exec".equals(execId)) {
                    callback.onStart(stream);
                    userDataStarted.countDown();
                } else {
                    callback.onComplete();
                }
                return callback;
            });
            return execStart;
        };
        when(harness.dockerClient().execStartCmd(anyString())).thenAnswer(start);
        when(harness.streamingDockerClient().execStartCmd(anyString())).thenAnswer(start);

        InspectExecCmd inspectExec = mock(InspectExecCmd.class);
        InspectExecResponse inspectExecResponse = mock(InspectExecResponse.class);
        when(inspectExecResponse.getExitCodeLong()).thenReturn(0L);
        when(inspectExec.exec()).thenReturn(inspectExecResponse);
        when(harness.dockerClient().inspectExecCmd(anyString())).thenReturn(inspectExec);

        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(harness.dockerClient().copyArchiveToContainerCmd(TEST_CONTAINER_ID)).thenReturn(copy);
        when(copy.withTarInputStream(any(InputStream.class))).thenReturn(copy);
        when(harness.logStreamer().generateLogStreamName(anyString())).thenReturn(TEST_LOG_STREAM_NAME);
    }

    /**
     * Index of the first exec matching {@code expected} exactly, or -1 when it was never run.
     */
    private static int commandIndex(List<String[]> executedCommands, String... expected) {
        for (int i = 0; i < executedCommands.size(); i++) {
            if (Arrays.equals(executedCommands.get(i), expected)) {
                return i;
            }
        }
        return -1;
    }

    private static Instance instance(String instanceId) {
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        instance.setOwnerId("000000000000");
        return instance;
    }

    private static InspectContainerResponse inspectResponse(String ipAddress) {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        NetworkSettings networkSettings = mock(NetworkSettings.class);
        when(inspect.getNetworkSettings()).thenReturn(networkSettings);
        if (ipAddress != null) {
            ContainerNetwork bridge = new ContainerNetwork().withIpv4Address(ipAddress);
            when(networkSettings.getNetworks()).thenReturn(Map.of("bridge", bridge));
        }
        return inspect;
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        await().atMost(timeout).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(5))
                .until(condition::getAsBoolean);
    }

    /**
     * The manager's pauses pass on the harness timeline instead of the wall clock, each for its
     * production length, and stay interruptible like the sleeps they stand in for.
     */
    private static Ec2ContainerPause timelinePause(TestTime time) {
        return millis -> {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            time.sleepAdvancing(Duration.ofMillis(millis));
        };
    }

    @Test
    void launchRunsInstanceAsMetadataOnlyWhenNoDockerDaemonIsReachable() throws Exception {
        LaunchHarness harness = launchHarness();
        stubDockerPing(harness.dockerClient(), false);

        Instance instance = instance("i-nodocker");

        harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");

        assertEquals("running", instance.getState().getName());
        assertNull(instance.getDockerContainerId());
        verify(harness.lifecycleManager(), never()).create(any(ContainerSpec.class));
        verify(harness.metadataServer(), never()).registerContainer(anyString(), anyString(), any());
    }

    @Test
    void launchDegradesToRunningWhenDockerDisappearsMidLaunch() throws Exception {
        LaunchHarness harness = launchHarness();
        PingCmd ping = mock(PingCmd.class);
        when(harness.dockerClient().pingCmd()).thenReturn(ping);
        doNothing().doThrow(new RuntimeException("No such file or directory")).when(ping).exec();
        when(harness.lifecycleManager().create(any(ContainerSpec.class)))
                .thenThrow(new RuntimeException("No such file or directory"));

        Instance instance = instance("i-dockergone");

        harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theTeardownHookRunsOnceTheContainerIsGone(boolean alreadyAbsent) throws Exception {
        // The hook exists so callers can do work that is only valid after the container has
        // released its Docker resources, so it must actually fire on the ordinary path.
        LaunchHarness harness = launchHarness();
        stubDockerPing(harness.dockerClient(), false);
        Instance instance = instance("i-teardown-hook");
        harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");
        if (alreadyAbsent) {
            instance.setDockerContainerId(TEST_CONTAINER_ID);
            when(harness.dockerClient().removeContainerCmd(TEST_CONTAINER_ID))
                    .thenThrow(new NotFoundException("already absent"));
        }
        AtomicBoolean ran = new AtomicBoolean(false);

        harness.manager().terminate(instance, () -> ran.set(true));

        awaitUntil(ran::get, Duration.ofSeconds(2));
    }

    @Test
    void failedTeardownIsRetriedWithABackoffThatDoublesFromOneSecondUpToThirty() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        // Share the queue so the barrier drains retries; Docker teardown pauses do not advance this clock.
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1), executor, millis -> { });
        try {
            stubDockerPing(harness.dockerClient(), false);
            Instance instance = leasedInstance("i-teardown-backoff");
            harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");
            instance.setDockerContainerId(TEST_CONTAINER_ID);
            RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
            when(harness.dockerClient().removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
            AtomicInteger attempts = new AtomicInteger();
            doAnswer(invocation -> {
                attempts.incrementAndGet();
                throw new DockerException("daemon is still refusing the removal", 500);
            }).when(remove).exec();

            harness.manager().terminate(instance);
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals(1, attempts.get());

            int expected = 1;
            for (int delaySeconds : new int[] {1, 2, 4, 8, 16, 30, 30}) {
                harness.time().advance(Duration.ofSeconds(delaySeconds).minusMillis(1));
                executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
                assertEquals(expected, attempts.get(),
                        "No retry before " + delaySeconds + " seconds have passed since the last failure");

                harness.time().advance(Duration.ofMillis(1));
                executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
                expected++;
                assertEquals(expected, attempts.get(), "A retry once " + delaySeconds + " seconds have passed");
            }
            assertEquals("terminated", instance.getState().getName());
        } finally {
            harness.manager().stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transientRemovalFailureCompletesWithoutAnotherTerminationRequest(boolean alreadyAbsent) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        // Share the queue so the barrier drains retries; Docker teardown pauses do not advance this clock.
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1), executor, millis -> { });
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch allowRemoval = new CountDownLatch(1);
        try {
            stubDockerPing(harness.dockerClient(), false);
            Instance instance = leasedInstance("i-teardown-recovery");
            harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");
            instance.setDockerContainerId(TEST_CONTAINER_ID);
            instance.setSshHostPort(2201);
            RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
            when(harness.dockerClient().removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
            AtomicInteger attempts = new AtomicInteger();
            doAnswer(invocation -> {
                if (attempts.incrementAndGet() == 1) {
                    failed.countDown();
                    throw new DockerException("tried to kill container, but did not receive an exit event", 500);
                }
                assertTrue(allowRemoval.await(2, TimeUnit.SECONDS));
                if (alreadyAbsent) {
                    throw new NotFoundException("container exited and was removed");
                }
                return null;
            }).when(remove).exec();
            AtomicInteger callbacks = new AtomicInteger();

            harness.manager().terminate(instance, callbacks::incrementAndGet);
            assertTrue(failed.await(2, TimeUnit.SECONDS));
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);

            assertEquals("terminated", instance.getState().getName());
            assertEquals(0, callbacks.get());
            verify(harness.portAllocator(), never()).release(2201);
            verify(harness.vpcNetworkManager(), never())
                    .releasePrivateIp("000000000000", "us-west-2", "subnet-lease", "10.0.1.10");
            allowRemoval.countDown();
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);

            harness.time().advance(Duration.ofMillis(999));
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals(1, attempts.get(), "The retry waits a full second");
            assertEquals(0, callbacks.get());

            harness.time().advance(Duration.ofMillis(1));
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);

            assertEquals(1, callbacks.get());
            assertEquals("terminated", instance.getState().getName());
            assertEquals(2, attempts.get());
            verify(harness.portAllocator()).release(2201);
            verify(harness.vpcNetworkManager())
                    .releasePrivateIp("000000000000", "us-west-2", "subnet-lease", "10.0.1.10");
        } finally {
            allowRemoval.countDown();
            harness.manager().stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedRemovalRetainsInstanceLeasesAndTheSameContainerCanBeRetried(boolean interrupted) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        try {
            stubDockerPing(harness.dockerClient(), false);
            Instance instance = leasedInstance("i-teardown-hook-refused");
            harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");
            instance.setDockerContainerId(TEST_CONTAINER_ID);
            instance.setContainerBridgeIp("172.18.0.2");
            instance.setImdsSourceIp("192.168.215.7");
            instance.setSshHostPort(2201);
            RemoveContainerCmd remove = mock(RemoveContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            when(harness.dockerClient().removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
            RuntimeException failure = interrupted
                    ? new RuntimeException(new ClosedByInterruptException()) : new RuntimeException("daemon refused");
            doThrow(failure).doNothing().when(remove).exec();
            AtomicInteger callbacks = new AtomicInteger();

            harness.manager().terminate(instance, callbacks::incrementAndGet);
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);

            assertEquals("terminated", instance.getState().getName());
            assertEquals(TEST_CONTAINER_ID, instance.getDockerContainerId());
            assertEquals(harness.time().clock().millis(), instance.getTerminatedAt());
            assertEquals(0, callbacks.get());
            verify(harness.portAllocator(), never()).release(2201);
            verify(harness.vpcNetworkManager(), never()).releasePrivateIp("000000000000", "us-west-2", "subnet-lease", "10.0.1.10");
            verify(harness.metadataServer(), never()).unregisterContainer(anyString(), eq(instance));
            verify(harness.metadataServer(), never()).unregisterInstance(instance);

            harness.manager().terminate(instance, callbacks::incrementAndGet);
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);

            assertEquals("terminated", instance.getState().getName());
            assertTrue(instance.getTerminatedAt() > 0);
            assertEquals(1, callbacks.get());
            verify(harness.dockerClient(), times(2)).removeContainerCmd(TEST_CONTAINER_ID);
            verify(remove, times(2)).exec();
            verify(harness.portAllocator()).release(2201);
            verify(harness.vpcNetworkManager()).releasePrivateIp("000000000000", "us-west-2", "subnet-lease", "10.0.1.10");
            verify(harness.metadataServer()).unregisterContainer("172.18.0.2", instance);
            verify(harness.metadataServer()).unregisterContainer("192.168.215.7", instance);
            verify(harness.metadataServer()).unregisterInstance(instance);
        } finally {
            harness.manager().stop();
        }
    }

    @Test
    void repeatedTerminationDoesNotRemoveTheSameContainerOrReleaseItsPortTwice() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        CountDownLatch removing = new CountDownLatch(1);
        CountDownLatch finishRemoval = new CountDownLatch(1);
        try {
            Instance instance = leasedInstance("i-repeated-termination");
            instance.setDockerContainerId(TEST_CONTAINER_ID);
            instance.setSshHostPort(2201);
            RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
            when(harness.dockerClient().removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
            doAnswer(invocation -> {
                removing.countDown();
                assertTrue(finishRemoval.await(2, TimeUnit.SECONDS));
                return null;
            }).when(remove).exec();
            AtomicInteger callbacks = new AtomicInteger();

            harness.manager().terminate(instance, callbacks::incrementAndGet);
            assertTrue(removing.await(2, TimeUnit.SECONDS));
            harness.manager().terminate(instance, callbacks::incrementAndGet);
            finishRemoval.countDown();
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            harness.manager().terminate(instance, callbacks::incrementAndGet);
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);

            assertEquals("terminated", instance.getState().getName());
            assertEquals(1, callbacks.get());
            verify(remove).exec();
            verify(harness.portAllocator()).release(2201);
            // One container removal, so the teardown pause passed once at its production length
            assertEquals(TestTime.DEFAULT_START.plusMillis(500), harness.time().now());
        } finally {
            finishRemoval.countDown();
            harness.manager().stop();
        }
    }

    @Test
    void shutdownCompletesPendingTerminationWithItsOriginalCallback() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        try {
            Instance instance = leasedInstance("i-pending-shutdown");
            instance.setDockerContainerId(TEST_CONTAINER_ID);
            instance.setSshHostPort(2201);
            RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
            when(harness.dockerClient().removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
            doThrow(new DockerException("removal is still completing", 500)).doNothing().when(remove).exec();
            AtomicInteger callbacks = new AtomicInteger();
            harness.manager().terminate(instance, callbacks::incrementAndGet);
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals("terminated", instance.getState().getName());
            assertEquals(0, callbacks.get());
            verify(harness.portAllocator(), never()).release(2201);
            harness.manager().stop();
            harness.manager().terminateForShutdown(instance);

            assertEquals("terminated", instance.getState().getName());
            assertEquals(1, callbacks.get());
            verify(remove, times(2)).exec();
            verify(harness.portAllocator()).release(2201);
        } finally {
            harness.manager().stop();
        }
    }

    @Test
    void metadataOnlyInstanceStopsStartsAndTerminatesWithoutAContainer() throws Exception {
        LaunchHarness harness = launchHarness();
        stubDockerPing(harness.dockerClient(), false);
        Instance instance = instance("i-lifecycle");

        harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");
        assertEquals("running", instance.getState().getName());

        harness.manager().stop(instance);
        assertEquals("stopped", instance.getState().getName());

        harness.manager().start(instance);
        assertEquals("running", instance.getState().getName());

        harness.manager().terminate(instance);
        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
    }

    @Test
    void launchKnownInstanceTypeAppliesResourceLimitsFromCatalog() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse inspectResponse = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(inspectResponse);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        Instance instance = instance("i-limits-known");
        instance.setInstanceType("t3.micro");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");
        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));

        verify(harness.builder).withMemoryMb(1024);
        verify(harness.builder).withCpuUnits(2048);
    }

    @Test
    void launchDifferentInstanceTypesProduceDifferentLimits() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(anyString())).thenReturn(inspect);
        InspectContainerResponse inspectResponse = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(inspectResponse);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        Instance inst1 = instance("i-t2-micro");
        inst1.setInstanceType("t2.micro");
        harness.manager.launch(inst1, "ubuntu:24.04", null, "us-west-2");
        awaitUntil(() -> "running".equals(inst1.getState().getName()), Duration.ofSeconds(2));

        verify(harness.builder).withMemoryMb(1024);
        verify(harness.builder).withCpuUnits(1024);

        Instance inst2 = instance("i-t3-small");
        inst2.setInstanceType("t3.small");
        harness.manager.launch(inst2, "ubuntu:24.04", null, "us-west-2");
        awaitUntil(() -> "running".equals(inst2.getState().getName()), Duration.ofSeconds(2));

        verify(harness.builder).withMemoryMb(2048);
        verify(harness.builder).withCpuUnits(2048);
    }

    @Test
    void launchUnknownInstanceTypeLaunchesUnboundedWithoutLimits() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse inspectResponse = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(inspectResponse);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        Instance instance = instance("i-unknown-type");
        instance.setInstanceType("custom.unknown.type");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");
        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));

        assertEquals("running", instance.getState().getName());
        verify(harness.builder, never()).withMemoryMb(anyInt());
        verify(harness.builder, never()).withCpuUnits(anyInt());
    }

    @Test
    void launchWithResourceLimitsDisabledProducesNoLimits() throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.config.services().ec2().instanceResourceLimits()).thenReturn(false);

        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse inspectResponse = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(inspectResponse);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        Instance instance = instance("i-disabled-limits");
        instance.setInstanceType("t3.micro");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");
        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));

        assertEquals("running", instance.getState().getName());
        verify(harness.builder, never()).withMemoryMb(anyInt());
        verify(harness.builder, never()).withCpuUnits(anyInt());
    }

    @Test
    void launchProducesContainerSpecWithCatalogMemoryAndCpuLimits() throws Exception {
        ContainerSpec spec = launchWithRealBuilder(ResolvedAmiImage.minimal("ubuntu:24.04"));

        assertEquals(1024L * 1024 * 1024, spec.memoryBytes());
        assertEquals(2_000_000_000L, spec.nanoCpus());
        assertEquals(List.of("tail", "-f", "/dev/null"), spec.cmd());
        assertNull(spec.entrypoint());
        assertTrue(spec.ulimits().isEmpty());
        assertEquals(8, spec.env().size());
    }

    @Test
    void launchImageRuntimePreservesImageStartupThroughDockerCreate() throws Exception {
        ContainerSpec spec = launchWithRealBuilder(new ResolvedAmiImage(
                "example/worker:1", ResolvedAmiImage.IMAGE_RUNTIME, false, "linux/arm64"));

        assertNull(spec.cmd());
        assertNull(spec.entrypoint());
        assertNull(spec.cgroupnsMode());
        assertTrue(spec.mounts().isEmpty());
        assertTrue(spec.binds().isEmpty());
        assertEquals(1024L * 1024 * 1024, spec.memoryBytes());
        assertEquals(2_000_000_000L, spec.nanoCpus());
        assertEquals("ec2", spec.labels().get(Ec2ContainerManager.LABEL_SERVICE));
        assertTrue(spec.env().contains("AWS_EC2_INSTANCE_ID=i-spec-limits"));

        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(dockerClient.infoCmd().exec().getNCPU()).thenReturn(8);
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd(spec.image())).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn(TEST_CONTAINER_ID);
        when(createCmd.exec()).thenReturn(response);
        ImageCacheService imageCache = mock(ImageCacheService.class);
        when(imageCache.ensureImageExists(spec.image(), "linux/arm64")).thenReturn(spec.image());
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        ContainerLifecycleManager lifecycle = new ContainerLifecycleManager(
                dockerClient, imageCache, mock(ContainerDetector.class), config);

        assertEquals(TEST_CONTAINER_ID, lifecycle.create(spec, "linux/arm64"));

        verify(createCmd).withPlatform("linux/arm64");
        verify(createCmd, never()).withCmd(anyList());
        verify(createCmd, never()).withCmd(any(String[].class));
        verify(createCmd, never()).withEntrypoint(anyList());
        verify(createCmd, never()).withEntrypoint(any(String[].class));
        verify(createCmd).exec();
    }

    @Test
    void launchSystemdRuntimeRetainsInitAndMountsInContainerSpec() throws Exception {
        ContainerSpec spec = launchWithRealBuilder(new ResolvedAmiImage(
                "example/cloud:1", ResolvedAmiImage.SYSTEMD_RUNTIME, true, "linux/arm64"));

        assertEquals(TEST_SYSTEMD_INIT_COMMAND, spec.cmd());
        assertNull(spec.entrypoint());
        assertEquals("private", spec.cgroupnsMode());
        assertEquals(Set.of("/run", "/run/lock"),
                spec.mounts().stream().map(Mount::getTarget).collect(Collectors.toSet()));
        Set<String> consoles = new HashSet<>();
        consoles.add("/dev/console");
        for (int tty = 0; tty < 64; tty++) {
            consoles.add("/dev/tty" + tty);
        }
        assertEquals(consoles, spec.binds().stream().map(bind -> bind.getVolume().getPath())
                .collect(Collectors.toSet()));
        assertTrue(spec.binds().stream().allMatch(bind -> "/dev/null".equals(bind.getPath())
                && bind.getAccessMode() == AccessMode.ro));
    }

    @ParameterizedTest
    @ValueSource(strings = {"minimal", "systemd", "image"})
    void configuredGuestOptionsReachAllRuntimeModes(String runtime) throws Exception {
        ContainerSpec spec = launchConfiguredGuest(new ResolvedAmiImage(
                "example/guest:1", runtime, "systemd".equals(runtime), "linux/arm64"), Map.of(
                "FLOCI_SERVICES_EC2_EXTRA_ENV", "CUSTOM_MODE=ready,TEXT=a\\,b=c,EMPTY=,AWS_CA_BUNDLE=/public/ca.pem",
                "FLOCI_SERVICES_EC2_ULIMITS", "memlock=-1,nofile=1024000,nproc=1024:2048",
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__SOURCE", "/host/input files,one/ca.pem",
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__TARGET", "/public/ca.pem",
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_1__SOURCE", "/host/literal\\resolver",
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_1__TARGET", "/etc/resolv.conf"),
                false, false, false);

        assertTrue(spec.env().containsAll(List.of(
                "CUSTOM_MODE=ready", "TEXT=a,b=c", "EMPTY=", "AWS_CA_BUNDLE=/public/ca.pem",
                "AWS_EC2_INSTANCE_ID=i-spec-limits", "AWS_REGION=us-west-2",
                "AWS_ENDPOINT_URL=http://172.30.0.2:4680",
                "AWS_EC2_METADATA_SERVICE_ENDPOINT=http://169.254.169.254", "AWS_ACCESS_KEY_ID=test")));
        assertEquals("systemd".equals(runtime), spec.env().contains("container=docker"));
        assertEquals("systemd".equals(runtime) ? 13 : 12, spec.env().size());
        assertEquals(Map.of(
                "memlock", new ContainerSpec.ResourceLimit(-1, -1),
                "nofile", new ContainerSpec.ResourceLimit(1024000, 1024000),
                "nproc", new ContainerSpec.ResourceLimit(1024, 2048)), spec.ulimits());
        List<Mount> binds = spec.mounts().stream().filter(mount -> mount.getType() == MountType.BIND).toList();
        assertEquals(List.of("/host/input files,one/ca.pem", "/host/literal\\resolver"),
                binds.stream().map(Mount::getSource).toList());
        assertEquals(List.of("/public/ca.pem", "/etc/resolv.conf"),
                binds.stream().map(Mount::getTarget).toList());
        assertTrue(binds.stream().allMatch(mount -> Boolean.TRUE.equals(mount.getReadOnly())));
        assertEquals(1024L * 1024 * 1024, spec.memoryBytes());
        assertEquals(2_000_000_000L, spec.nanoCpus());
        assertNull(spec.entrypoint());
        if ("image".equals(runtime)) {
            assertNull(spec.cmd());
        } else {
            assertEquals("systemd".equals(runtime) ? TEST_SYSTEMD_INIT_COMMAND
                    : List.of("tail", "-f", "/dev/null"), spec.cmd());
        }
        assertEquals("systemd".equals(runtime) ? "private" : null, spec.cgroupnsMode());
    }

    @ParameterizedTest
    @CsvSource({
            "/etc,http://172.30.0.2:4680",
            "/etc/resolv.conf,http://172.30.0.2:4680",
            "/etc/guest-ca.pem,http://localhost.floci.io:4680",
            "/etc/resolv.conf.backup,http://localhost.floci.io:4680"
    })
    void guestResolverMountSelectsAnEndpointIndependentOfEmbeddedDns(String target, String endpoint)
            throws Exception {
        ContainerSpec spec = launchConfiguredGuest(new ResolvedAmiImage(
                "example/guest:1", ResolvedAmiImage.IMAGE_RUNTIME, false, "linux/arm64"), Map.of(
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__SOURCE", "/host/input",
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__TARGET", target), false, true, false);

        assertTrue(spec.env().contains("AWS_ENDPOINT_URL=" + endpoint));
        assertEquals(target, spec.mounts().getFirst().getTarget());
        assertTrue(spec.mounts().getFirst().getReadOnly());
        assertFalse(spec.env().stream().anyMatch(value -> value.startsWith("AWS_ACCESS_KEY_ID=")));
        assertNull(spec.entrypoint());
        assertNull(spec.cmd());
    }

    @ParameterizedTest
    @CsvSource({
            "/etc/resolv.conf,https://172.30.0.2:4680",
            "/etc/guest-ca.pem,https://localhost.floci.io:4680"
    })
    void guestTlsEndpointGivesGuestsFlociHttpsEndpoint(String target, String endpoint) throws Exception {
        ContainerSpec spec = launchConfiguredGuest(new ResolvedAmiImage(
                "example/guest:1", ResolvedAmiImage.IMAGE_RUNTIME, false, "linux/arm64"), Map.of(
                "FLOCI_SERVICES_EC2_GUEST_TLS_ENDPOINT", "true",
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__SOURCE", "/host/input",
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__TARGET", target), false, true, false);

        // The guest's requests then carry aws:SecureTransport true, so a bucket policy that denies insecure
        // transport (aws:SecureTransport false) admits a guest's reads.
        assertTrue(spec.env().contains("AWS_ENDPOINT_URL=" + endpoint));
        assertEquals(1, spec.env().stream().filter(value -> value.startsWith("AWS_ENDPOINT_URL=")).count());
    }

    @Test
    void configuredGuestOptionsPreserveInstanceProfileAndProtectedNamespace() throws Exception {
        ContainerSpec spec = launchConfiguredGuest(ResolvedAmiImage.minimal("ubuntu:24.04"), Map.of(
                "FLOCI_SERVICES_EC2_EXTRA_ENV", "CUSTOM_MODE=ready",
                "FLOCI_SERVICES_EC2_ULIMITS", "memlock=-1,nofile=1024000"),
                true, true, false);

        assertEquals("container:firewall-helper", spec.networkMode());
        assertFalse(spec.privileged());
        assertTrue(spec.portBindings().isEmpty());
        assertTrue(spec.env().contains("CUSTOM_MODE=ready"));
        assertFalse(spec.env().stream().anyMatch(value -> value.startsWith("AWS_ACCESS_KEY_ID=")
                || value.startsWith("AWS_SECRET_ACCESS_KEY=") || value.startsWith("AWS_SESSION_TOKEN=")));
        assertEquals(new ContainerSpec.ResourceLimit(-1, -1), spec.ulimits().get("memlock"));
        assertEquals(new ContainerSpec.ResourceLimit(1024000, 1024000), spec.ulimits().get("nofile"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"minimal", "systemd", "image"})
    void configuredGuestPrivilegesReachDockerWithoutChangingNetworkHelpers(String runtime) throws Exception {
        ContainerSpec spec = launchConfiguredGuest(new ResolvedAmiImage(
                "example/guest:1", runtime, "systemd".equals(runtime), "linux/arm64"), Map.of(
                "FLOCI_SERVICES_EC2_PRIVILEGED", "false",
                "FLOCI_SERVICES_EC2_CAP_DROP", "NET_ADMIN,cap_net_raw,NET_ADMIN"),
                false, true, false);

        HostConfig host = createGuestHostConfig(spec);

        assertFalse(Boolean.TRUE.equals(host.getPrivileged()));
        assertFalse(Boolean.TRUE.equals(host.getInit()));
        assertEquals(Set.of(Capability.NET_ADMIN, Capability.NET_RAW), Set.of(host.getCapDrop()));
        assertEquals("container:" + TEST_HELPER_ID, host.getNetworkMode());
        assertFalse(spec.env().stream().anyMatch(value -> value.startsWith("AWS_ACCESS_KEY_ID=")));
        assertNull(spec.entrypoint());
        if ("image".equals(runtime)) {
            assertNull(spec.cmd());
        }
    }

    @Test
    void defaultGuestPrivilegesRetainExistingDockerBehavior() throws Exception {
        ContainerSpec spec = launchWithRealBuilder(new ResolvedAmiImage(
                "example/guest:1", ResolvedAmiImage.IMAGE_RUNTIME, false, "linux/arm64"));

        HostConfig host = createGuestHostConfig(spec);

        assertTrue(Boolean.TRUE.equals(host.getPrivileged()));
        assertNull(host.getCapDrop());
    }

    @ParameterizedTest
    @CsvSource({"false,,127.0.0.1", "true,,127.0.0.1",
            "false,192.0.2.10,192.0.2.10", "true,192.0.2.10,192.0.2.10",
            "false,' ',127.0.0.1", "true,' ',127.0.0.1"})
    void sshHostAddressReachesMetadataAndSecurityGroupHelpers(
            boolean protectedNamespace, String configuredAddress, String expectedAddress)
            throws Exception {
        Map<String, String> environment = configuredAddress == null ? Map.of()
                : Map.of("FLOCI_SERVICES_EC2_SSH_HOST_BIND_ADDRESS", configuredAddress);
        ContainerSpec guest = launchConfiguredGuest(ResolvedAmiImage.minimal("ubuntu:24.04"),
                environment, protectedNamespace, false, false, expectedAddress);

        assertTrue(guest.portBindings().isEmpty());
    }

    @Test
    void configuredPrivilegesCannotOverrideMandatorySecurityGroupContainment() throws Exception {
        ContainerSpec spec = launchConfiguredGuest(new ResolvedAmiImage(
                "example/guest:1", ResolvedAmiImage.IMAGE_RUNTIME, false, "linux/arm64"), Map.of(
                "FLOCI_SERVICES_EC2_PRIVILEGED", "true",
                "FLOCI_SERVICES_EC2_CAP_DROP", "SYS_PTRACE"),
                true, true, false);

        HostConfig host = createGuestHostConfig(spec);

        assertFalse(Boolean.TRUE.equals(host.getPrivileged()));
        assertEquals(Set.of(Capability.NET_ADMIN, Capability.NET_RAW, Capability.SYS_PTRACE),
                Set.of(host.getCapDrop()));
        assertEquals("container:firewall-helper", host.getNetworkMode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"minimal", "systemd", "image"})
    void configuredCapabilityAddsReachDocker(String runtime) throws Exception {
        ContainerSpec spec = launchConfiguredGuest(new ResolvedAmiImage(
                "example/guest:1", runtime, "systemd".equals(runtime), "linux/arm64"), Map.of(
                "FLOCI_SERVICES_EC2_PRIVILEGED", "false",
                "FLOCI_SERVICES_EC2_CAP_ADD", "SYSLOG,cap_sys_ptrace"),
                false, true, false);

        HostConfig host = createGuestHostConfig(spec);
        assertFalse(Boolean.TRUE.equals(host.getPrivileged()));
        assertEquals(Set.of(Capability.SYSLOG, Capability.SYS_PTRACE), Set.of(host.getCapAdd()));
        assertNull(host.getCapDrop());
    }

    @Test
    void configuredCapabilityAddsCannotOverrideMandatorySecurityGroupContainment() throws Exception {
        ContainerSpec spec = launchConfiguredGuest(new ResolvedAmiImage(
                "example/guest:1", ResolvedAmiImage.IMAGE_RUNTIME, false, "linux/arm64"), Map.of(
                "FLOCI_SERVICES_EC2_CAP_ADD", "NET_ADMIN,SYSLOG"),
                true, true, false);

        HostConfig host = createGuestHostConfig(spec);
        assertEquals(Set.of(Capability.SYSLOG), Set.of(host.getCapAdd()));
        assertEquals(Set.of(Capability.NET_ADMIN, Capability.NET_RAW), Set.of(host.getCapDrop()));
    }

    @Test
    void defaultGuestCapabilitiesAddNothing() throws Exception {
        ContainerSpec spec = launchWithRealBuilder(new ResolvedAmiImage(
                "example/guest:1", ResolvedAmiImage.IMAGE_RUNTIME, false, "linux/arm64"));

        assertNull(createGuestHostConfig(spec).getCapAdd());
    }

    @Test
    void capabilityBothAddedAndDroppedIsRefused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
                Ec2ContainerManager.requireDisjointCapabilities(
                        List.of("cap_syslog", "SYS_PTRACE"), List.of("SYSLOG", "NET_RAW")));
        assertEquals("EC2 guest capabilities both added and dropped: [SYSLOG]", refused.getMessage());
        Ec2ContainerManager.requireDisjointCapabilities(List.of("SYSLOG"), List.of("NET_RAW"));
    }

    private static HostConfig createGuestHostConfig(ContainerSpec spec) {
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(dockerClient.infoCmd().exec().getNCPU()).thenReturn(8);
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd(spec.image())).thenReturn(create);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn(TEST_CONTAINER_ID);
        when(create.exec()).thenReturn(response);
        ImageCacheService imageCache = mock(ImageCacheService.class);
        when(imageCache.ensureImageExists(spec.image(), "linux/arm64")).thenReturn(spec.image());
        ContainerLifecycleManager lifecycle = new ContainerLifecycleManager(
                dockerClient, dockerClient, imageCache, mock(ContainerDetector.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS));

        assertEquals(TEST_CONTAINER_ID, lifecycle.create(spec, "linux/arm64"));

        ArgumentCaptor<HostConfig> host = ArgumentCaptor.forClass(HostConfig.class);
        verify(create).withHostConfig(host.capture());
        verify(create).exec();
        return host.getValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INHERITED_WITHOUT_VALUE", "=value", "BAD KEY=value", "DUP=one,DUP=two",
            "AWS_EC2_INSTANCE_ID=other", "AWS_REGION=other", "AWS_DEFAULT_REGION=other",
            "AWS_ACCESS_KEY_ID=other", "AWS_SECRET_ACCESS_KEY=other", "AWS_SESSION_TOKEN=other",
            "AWS_ENDPOINT_URL=http://other", "AWS_ENDPOINT_URL_S3=http://other",
            "AWS_EC2_METADATA_SERVICE_ENDPOINT=http://other", "AWS_EC2_METADATA_DISABLED=true",
            "AWS_PROFILE=other", "AWS_SHARED_CREDENTIALS_FILE=/other",
            "AWS_WEB_IDENTITY_TOKEN_FILE=/other", "AWS_CONTAINER_CREDENTIALS_FULL_URI=http://other",
            "AWS_IGNORE_CONFIGURED_ENDPOINT_URLS=true"
    })
    void invalidEnvironmentRefusesBeforePortsHelpersOrWorkload(String entries) throws Exception {
        assertNull(launchConfiguredGuest(ResolvedAmiImage.minimal("ubuntu:24.04"),
                Map.of("FLOCI_SERVICES_EC2_EXTRA_ENV", entries), true, true, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "nofile", "nofile=-2", "nofile=3:2", "nofile=-1:2", "nofile=1,nofile=2",
            "nofile=1:2:3", "not-a-limit=1", "nofile=9223372036854775808"
    })
    void invalidProcessLimitsRefuseBeforePortsHelpersOrWorkload(String entries) throws Exception {
        assertNull(launchConfiguredGuest(ResolvedAmiImage.minimal("ubuntu:24.04"),
                Map.of("FLOCI_SERVICES_EC2_ULIMITS", entries), true, false, true));
    }

    @ParameterizedTest
    @CsvSource({
            "relative,/etc/ca.pem",
            "/host/../ca.pem,/etc/ca.pem",
            "/host//ca.pem,/etc/ca.pem",
            "/host/ca.pem,relative",
            "/host/ca.pem,/",
            "/host/ca.pem,/etc/./ca.pem",
            "/host/ca.pem,/etc/../ca.pem",
            "/host/ca.pem,/etc/ca.pem/"
    })
    void invalidMountPathsRefuseBeforePortsHelpersOrWorkload(String source, String target) throws Exception {
        assertNull(launchConfiguredGuest(new ResolvedAmiImage(
                "example/worker:1", ResolvedAmiImage.IMAGE_RUNTIME, false, "linux/arm64"),
                Map.of("FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__SOURCE", source,
                        "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__TARGET", target), true, false, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/run", "/run/lock", "/sys/fs/cgroup"})
    void systemdMountTargetsRefuseBeforePortsHelpersOrWorkload(String target) throws Exception {
        assertNull(launchConfiguredGuest(new ResolvedAmiImage(
                "example/guest:1", ResolvedAmiImage.SYSTEMD_RUNTIME, true, "linux/arm64"), Map.of(
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__SOURCE", "/host/input",
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__TARGET", target), true, false, true));
    }

    @ParameterizedTest
    @CsvSource({
            "minimal,/run", "minimal,/run/lock", "minimal,/sys/fs/cgroup",
            "image,/run", "image,/run/lock", "image,/sys/fs/cgroup",
            "systemd,/run/custom", "systemd,/run/lockfile", "systemd,/sys/fs/cgroup-extra",
            "minimal,/run/floci-init-ready", "minimal,/run/floci-init-binary-ready",
            "image,/run/floci-init-ready", "image,/run/floci-init-binary-ready",
            "systemd,/run/floci-init-ready", "systemd,/run/floci-init-binary-ready",
            "minimal,/run/floci-time-ready", "image,/run/floci-time-ready", "systemd,/run/floci-time-ready"
    })
    void otherRuntimeMountTargetsRemainAvailable(String runtime, String target) throws Exception {
        ContainerSpec spec = launchConfiguredGuest(new ResolvedAmiImage(
                "example/guest:1", runtime, "systemd".equals(runtime), "linux/arm64"), Map.of(
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__SOURCE", "/host/input",
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__TARGET", target), false, false, false);

        Mount bind = spec.mounts().stream().filter(mount -> mount.getType() == MountType.BIND)
                .findFirst().orElseThrow();
        assertEquals(target, bind.getTarget());
        assertTrue(bind.getReadOnly());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/run", "/run/lock", "/sys/fs/cgroup", "/sys/hypervisor",
            "/run/floci-init-ready", "/run/floci-init-binary-ready", "/run/floci-time-ready"
    })
    void nativeRuntimeMountsRefuseBeforeAllocatingGuestResources(String target) throws Exception {
        assertNull(launchConfiguredGuest(new ResolvedAmiImage(
                "example/guest:1", ResolvedAmiImage.SYSTEMD_V1_RUNTIME, true, "linux/arm64"), Map.of(
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__SOURCE", "/host/input",
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__TARGET", target), true, false, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYS_ADMIN", "CAP_SYS_ADMIN", "sys_boot", "cap_sys_boot", "ALL"})
    void nativeRuntimeRefusesMissingRequiredCapabilitiesBeforeAllocation(String capability) throws Exception {
        assertNull(launchConfiguredGuest(new ResolvedAmiImage(
                "example/guest:1", ResolvedAmiImage.SYSTEMD_V1_RUNTIME, true, "linux/arm64"),
                Map.of("FLOCI_SERVICES_EC2_CAP_DROP", capability), true, false, true));
    }

    @Test
    void duplicateMountTargetsRefuseBeforePortsHelpersOrWorkload() throws Exception {
        assertNull(launchConfiguredGuest(ResolvedAmiImage.minimal("ubuntu:24.04"), Map.of(
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__SOURCE", "/host/first.pem",
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_0__TARGET", "/etc/ca.pem",
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_1__SOURCE", "/host/second.pem",
                "FLOCI_SERVICES_EC2_READ_ONLY_BINDS_1__TARGET", "/etc/ca.pem"), false, false, true));
    }

    private static ContainerSpec launchWithRealBuilder(ResolvedAmiImage image) throws Exception {
        return launchConfiguredGuest(image, Map.of(), false, false, false);
    }

    private static ContainerSpec launchConfiguredGuest(ResolvedAmiImage image, Map<String, String> environment,
                                                       boolean protectedNamespace, boolean instanceProfile,
                                                       boolean refused) throws Exception {
        return launchConfiguredGuest(image, environment, protectedNamespace, instanceProfile, refused, "127.0.0.1");
    }

    private static ContainerSpec launchConfiguredGuest(ResolvedAmiImage image, Map<String, String> environment,
                                                        boolean protectedNamespace, boolean instanceProfile,
                                                        boolean refused, String sshHostAddress) throws Exception {
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.resolve()).thenReturn("floci");
        ContainerReachableEndpoint reachableEndpoint = mock(ContainerReachableEndpoint.class);
        when(reachableEndpoint.baseUrl()).thenReturn("http://localhost.floci.io:4680");
        when(reachableEndpoint.baseUrlWithTls(true)).thenReturn("http://localhost.floci.io:4680");
        when(reachableEndpoint.baseUrl(false)).thenReturn("http://172.30.0.2:4680");
        when(reachableEndpoint.baseUrlWithTls(false)).thenReturn("http://172.30.0.2:4680");
        lenient().when(reachableEndpoint.baseUrlWithTls(true)).thenReturn("https://localhost.floci.io:4680");
        lenient().when(reachableEndpoint.baseUrlWithTls(false)).thenReturn("https://172.30.0.2:4680");
        PortAllocator portAllocator = mock(PortAllocator.class);
        when(portAllocator.allocate(anyInt(), anyInt())).thenReturn(2201);
        when(portAllocator.allocateAndStart(anyInt(), anyInt(), any())).thenCallRealMethod();

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig mappedConfig = new SmallRyeConfigBuilder()
                .withSources(new EnvConfigSource(environment, 300))
                .withDefaultValue("floci.services.ec2.metadata-helper-image", "example/network-helper:test")
                .withMapping(EmulatorConfig.class).build()
                .getConfigMapping(EmulatorConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mappedConfig.services().ec2();
        lenient().when(config.network()).thenReturn(mappedConfig.network());
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(services.dockerNetwork()).thenReturn(Optional.empty());

        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(docker.logMaxSize()).thenReturn("10m");
        when(docker.logMaxFile()).thenReturn("3");
        when(docker.extraLabels()).thenReturn(List.of());
        when(docker.resourceNamespace()).thenReturn(Optional.empty());
        when(docker.imageRegistryBase()).thenReturn(Optional.empty());
        when(config.docker()).thenReturn(docker);

        ContainerBuilder realBuilder = new ContainerBuilder(
                config,
                dockerHostResolver,
                mock(EmbeddedDnsServer.class));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
        when(lifecycleManager.create(specCaptor.capture())).thenReturn(TEST_CONTAINER_ID);
        when(lifecycleManager.create(specCaptor.capture(), anyString())).thenReturn(TEST_CONTAINER_ID);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);
        when(lifecycleManager.createAndStart(any(ContainerSpec.class)))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo(TEST_HELPER_ID, Map.of()));

        DockerClient dockerClient = mock(DockerClient.class);
        InspectImageCmd inspectImage = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd(anyString())).thenReturn(inspectImage);
        stubMetadataExecs(dockerClient, TEST_HELPER_ID);
        stubMetadataExecs(dockerClient, "firewall-helper");
        stubDockerPing(dockerClient, true);
        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        Ec2PortForwardManager portForwardManager = mock(Ec2PortForwardManager.class);
        VpcNetworkManager vpcNetworkManager = mock(VpcNetworkManager.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
        when(firewall.enabled()).thenReturn(protectedNamespace);
        when(firewall.createNamespace(eq("ec2"), eq("i-spec-limits"), eq("000000000000"),
                eq("us-west-2"), eq(Optional.empty()), eq(Map.of(22, 2201)), eq(Map.of()), eq(sshHostAddress),
                isNull(), eq(false)))
                .thenReturn(new SecurityGroupFirewallManager.Namespace("firewall-helper", "172.18.0.12"));

        Ec2ContainerManager manager = new Ec2ContainerManager(
                realBuilder,
                lifecycleManager,
                logStreamer,
                mock(ContainerDetector.class),
                dockerHostResolver,
                dockerClient,
                portAllocator,
                config,
                metadataServer,
                portForwardManager,
                regionResolver,
                mock(ContainerNetworkReachability.class),
                vpcNetworkManager,
                reachableEndpoint,
                firewall,
                new Ec2InstanceTypeCatalog(),
                timelinePause(TestTime.create()));

        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(dockerClient.inspectContainerCmd("firewall-helper")).thenReturn(inspect);
        InspectContainerResponse inspectResponse = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(inspectResponse);

        Instance instance = instance("i-spec-limits");
        instance.setInstanceType("t3.micro");
        if (instanceProfile) {
            instance.setIamInstanceProfileArn("arn:aws:iam::000000000000:instance-profile/test");
        }
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setNetworkInterfaceId("eni-options");
        eni.setPrivateIpAddress("10.0.0.12");
        instance.setNetworkInterfaces(List.of(eni));

        try {
            manager.launch(instance, image, null, "us-west-2");
            awaitUntil(() -> (refused ? "terminated" : "running").equals(instance.getState().getName()),
                    Duration.ofSeconds(2));
            if (refused) {
                verify(portAllocator, never()).allocate(anyInt(), anyInt());
                verify(firewall, never()).createNamespace(anyString(), anyString(), anyString(), anyString(),
                        any(), any(), any(), any(), any(), anyBoolean());
                verify(lifecycleManager, never()).create(any());
                verify(lifecycleManager, never()).create(any(), anyString());
                return null;
            }
            if (protectedNamespace) {
                verify(firewall).createNamespace("ec2", "i-spec-limits", "000000000000",
                        "us-west-2", Optional.empty(), Map.of(22, 2201), Map.of(), sshHostAddress, null, false);
                if (image.dockerPlatform() == null) {
                    verify(lifecycleManager, times(1)).create(any());
                } else {
                    verify(lifecycleManager, times(1)).create(any(), anyString());
                }
            } else {
                ArgumentCaptor<ContainerSpec> helper = ArgumentCaptor.forClass(ContainerSpec.class);
                verify(lifecycleManager).createAndStart(helper.capture());
                assertEquals(List.of("NET_ADMIN"), helper.getValue().linuxOptions().capAdd());
                assertTrue(helper.getValue().linuxOptions().capDrop().isEmpty());
                assertFalse(helper.getValue().privileged());
                assertEquals(Map.of(22, sshHostAddress), helper.getValue().portBindingHostIps());
            }
            ContainerSpec unrelated = realBuilder.newContainer("example/unrelated-helper:1").build();
            assertTrue(unrelated.env().isEmpty());
            assertTrue(unrelated.ulimits().isEmpty());
            return specCaptor.getValue();
        } finally {
            manager.stop();
        }
    }

    private static void stubMetadataExecs(DockerClient dockerClient, String helperId) {
        ExecCreateCmd create = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn("prepare-" + helperId);
        when(create.exec()).thenReturn(created);
        when(dockerClient.execCreateCmd(helperId)).thenReturn(create);
        ExecStartCmd start = mock(ExecStartCmd.class);
        when(start.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onComplete();
            return callback;
        });
        when(dockerClient.execStartCmd("prepare-" + helperId)).thenReturn(start);
        InspectExecCmd inspect = mock(InspectExecCmd.class);
        InspectExecResponse result = mock(InspectExecResponse.class);
        when(result.getExitCodeLong()).thenReturn(0L);
        when(inspect.exec()).thenReturn(result);
        when(dockerClient.inspectExecCmd("prepare-" + helperId)).thenReturn(inspect);
    }

    /** Stubs the daemon reachability probe every launch makes before touching Docker. */
    private static void stubDockerPing(DockerClient dockerClient, boolean reachable) {
        PingCmd ping = mock(PingCmd.class);
        when(dockerClient.pingCmd()).thenReturn(ping);
        if (!reachable) {
            doThrow(new RuntimeException("No such file or directory")).when(ping).exec();
        }
    }

    private record LaunchHarness(Ec2ContainerManager manager,
                                 ContainerLifecycleManager lifecycleManager,
                                 DockerClient dockerClient,
                                 DockerClient streamingDockerClient,
                                 Ec2MetadataServer metadataServer,
                                 ContainerLogStreamer logStreamer,
                                 ContainerBuilder containerBuilder,
                                 ContainerBuilder.Builder builder,
                                 PortAllocator portAllocator,
                                 Ec2PortForwardManager portForwardManager,
                                 EmulatorConfig config,
                                 VpcNetworkManager vpcNetworkManager,
                                 List<String[]> executedCommands,
                                 TestTime time) {
        void stubSuccessfulExecs(CountDownLatch userDataStarted, CountDownLatch finishUserData) {
            AtomicReference<String[]> currentCommand = new AtomicReference<>();
            ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            ExecCreateCmdResponse metadataExec = mock(ExecCreateCmdResponse.class);
            ExecCreateCmdResponse userDataExec = mock(ExecCreateCmdResponse.class);
            when(metadataExec.getId()).thenReturn("metadata-exec");
            when(userDataExec.getId()).thenReturn("userdata-exec");
            when(dockerClient.execCreateCmd(TEST_CONTAINER_ID)).thenReturn(execCreate);
            when(dockerClient.execCreateCmd(TEST_HELPER_ID)).thenReturn(execCreate);
            when(execCreate.withCmd(any(String[].class))).thenAnswer(invocation -> {
                Object[] args = invocation.getArguments();
                if (args.length == 1 && args[0] instanceof String[] command) {
                    currentCommand.set(command);
                } else {
                    currentCommand.set(Arrays.copyOf(args, args.length, String[].class));
                }
                executedCommands.add(currentCommand.get());
                return execCreate;
            });
            when(execCreate.exec()).thenAnswer(invocation -> {
                String[] command = currentCommand.get();
                if (command != null && command.length == 1 && "/var/lib/user-data.sh".equals(command[0])) {
                    return userDataExec;
                }
                return metadataExec;
            });

            // Startable on either client, so a test can verify which pool each exec's output used.
            Answer<ExecStartCmd> start = invocation -> {
                String execId = invocation.getArgument(0);
                ExecStartCmd execStart = mock(ExecStartCmd.class);
                when(execStart.exec(any())).thenAnswer(startInvocation -> {
                    @SuppressWarnings("unchecked")
                    ResultCallback<Frame> callback = startInvocation.getArgument(0);
                    if ("userdata-exec".equals(execId)) {
                        userDataStarted.countDown();
                        // test docker api frame
                        Frame frame = new Frame(StreamType.STDOUT, TEST_USER_DATA_OUTPUT.getBytes(StandardCharsets.UTF_8));
                        callback.onNext(frame);
                        finishUserData.await(2, TimeUnit.SECONDS);
                    }
                    callback.onComplete();
                    return callback;
                });
                return execStart;
            };
            when(dockerClient.execStartCmd(anyString())).thenAnswer(start);
            when(streamingDockerClient.execStartCmd(anyString())).thenAnswer(start);

            InspectExecCmd inspectExec = mock(InspectExecCmd.class);
            InspectExecResponse inspectExecResponse = mock(InspectExecResponse.class);
            when(inspectExecResponse.getExitCodeLong()).thenReturn(0L);
            when(inspectExec.exec()).thenReturn(inspectExecResponse);
            when(dockerClient.inspectExecCmd(anyString())).thenReturn(inspectExec);

            CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            when(dockerClient.copyArchiveToContainerCmd(TEST_CONTAINER_ID)).thenReturn(copy);
            when(copy.withTarInputStream(any(InputStream.class))).thenReturn(copy);

            when(logStreamer.generateLogStreamName(anyString())).thenReturn(TEST_LOG_STREAM_NAME);
        }
    }


    @Test
    void cancelledContainerlessLaunchStillPersistsItsTerminalState() {
        LaunchHarness harness = launchHarness();
        Instance instance = instance("i-containerless-cancel");
        AtomicReference<String> stored = new AtomicReference<>();
        harness.manager.setPersister(value -> stored.set(value.getState().getName()));
        try {
            assertTrue(harness.manager.cancelLaunch(instance));
            assertEquals("terminated", stored.get());
            verifyNoInteractions(harness.lifecycleManager, harness.portAllocator, harness.vpcNetworkManager);
        } finally {
            harness.manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedLaunchRetainsLeasesUntilStrictWorkloadRemovalSucceeds(boolean refuseRemoval) {
        LaunchHarness harness = launchHarness();
        Instance instance = failedLaunchInstance("i-workload-removal");
        AtomicBoolean unavailable = new AtomicBoolean(refuseRemoval);
        doAnswer(invocation -> {
            if (unavailable.get()) {
                throw new IllegalStateException("workload still exists");
            }
            return null;
        }).when(harness.lifecycleManager).removeIfExistsStrict(TEST_CONTAINER_ID);
        try {
            if (refuseRemoval) {
                assertThrows(IllegalStateException.class, () -> harness.manager.cancelLaunch(instance));
                assertEquals("terminated", instance.getState().getName());
                verify(harness.portAllocator, never()).release(anyInt());
                verifyNoInteractions(harness.vpcNetworkManager);
                verify(harness.metadataServer, never()).unregisterInstance(instance);
                instance.setOwnerId("444455556666");
                instance.setRegion("eu-west-1");
                instance.setSubnetId(null);
                instance.setVpcId(null);
                instance.setPrivateIpAddress(null);
                unavailable.set(false);
            }
            assertTrue(harness.manager.cancelLaunch(instance));
            assertTrue(harness.manager.cancelLaunch(instance));
            verify(harness.lifecycleManager, times(refuseRemoval ? 2 : 1)).removeIfExistsStrict(TEST_CONTAINER_ID);
            verify(harness.portAllocator).release(2201);
            verify(harness.vpcNetworkManager).detach("111122223333", "us-west-2", "vpc-lease", TEST_CONTAINER_ID);
            verify(harness.vpcNetworkManager).releasePrivateIp("111122223333", "us-west-2", "subnet-lease", "10.0.1.10");
            verify(harness.metadataServer).unregisterContainer("172.18.0.12", instance);
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void failedLaunchAutomaticallyRetriesCapturedCleanupAfterDockerRecovers() throws Exception {
        LaunchHarness harness = launchHarness();
        Instance instance = failedLaunchInstance("i-automatic-cleanup");
        AtomicBoolean recovered = new AtomicBoolean();
        CountDownLatch reclaimed = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (!recovered.get()) {
                throw new IllegalStateException("Docker removal temporarily unavailable");
            }
            return null;
        }).when(harness.lifecycleManager).removeIfExistsStrict(TEST_CONTAINER_ID);
        doAnswer(invocation -> {
            reclaimed.countDown();
            return null;
        }).when(harness.metadataServer).unregisterContainer("172.18.0.12", instance);
        try {
            assertThrows(IllegalStateException.class, () -> harness.manager.cancelLaunch(instance));
            verify(harness.portAllocator, never()).release(anyInt());
            verifyNoInteractions(harness.vpcNetworkManager);
            instance.setOwnerId("444455556666");
            instance.setRegion("eu-west-1");
            instance.setSubnetId(null);
            instance.setVpcId(null);
            instance.setPrivateIpAddress(null);
            recovered.set(true);
            harness.time().advance(Duration.ofSeconds(1));
            assertTrue(reclaimed.await(5, TimeUnit.SECONDS), "cleanup must retry without another cancellation");
            verify(harness.portAllocator).release(2201);
            verify(harness.vpcNetworkManager).detach("111122223333", "us-west-2", "vpc-lease", TEST_CONTAINER_ID);
            verify(harness.vpcNetworkManager).releasePrivateIp("111122223333", "us-west-2", "subnet-lease", "10.0.1.10");
        } finally {
            harness.manager.stop();
        }
    }

    private static Instance failedLaunchInstance(String id) {
        Instance instance = instance(id);
        instance.setOwnerId("111122223333");
        instance.setRegion("us-west-2");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setSshHostPort(2201);
        instance.setContainerBridgeIp("172.18.0.12");
        instance.setVpcId("vpc-lease");
        instance.setSubnetId("subnet-lease");
        instance.setPrivateIpAddress("10.0.1.10");
        return instance;
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void failedLaunchCannotRetireAReplacementFirewallRegistration(boolean replace, boolean retry) {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        ContainerLifecycleManager firewallLifecycle = mock(ContainerLifecycleManager.class);
        DockerClient policyDocker = SecurityGroupDnsFixture.policyDocker(mock(DockerClient.class, RETURNS_DEEP_STUBS));
        SecurityGroupFirewallManager firewall = spy(SecurityGroupDnsFixture.rawManager(
                policyDocker, mock(ContainerBuilder.class), firewallLifecycle,
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(EmbeddedDnsServer.class)));
        doNothing().when(firewall).reconcileAll();
        doNothing().when(firewall).admit(anyString(), anyBoolean());
        doReturn(true).when(firewall).enabled();
        firewall.register(new SecurityGroupNftCompiler.Endpoint("111122223333", "us-west-2", "vpc-old",
                "eni-failed-launch", "10.0.1.10", "172.18.0.2", Set.of(), List.of()), "old-helper", Map.of());
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class), lifecycle, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), mock(DockerHostResolver.class), mock(DockerClient.class),
                mock(PortAllocator.class), mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class), firewall);
        Instance instance = failedLaunchInstance("i-captured-firewall");
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setNetworkInterfaceId("eni-failed-launch");
        instance.setNetworkInterfaces(new ArrayList<>(List.of(eni)));
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.getAndSet(false)) {
                if (replace) {
                    firewall.register(new SecurityGroupNftCompiler.Endpoint("111122223333", "us-west-2", "vpc-new",
                            "eni-failed-launch", "10.0.1.10", "172.18.0.3", Set.of(), List.of()),
                            "new-helper", Map.of());
                }
                instance.getNetworkInterfaces().clear();
                if (retry) {
                    throw new IllegalStateException("workload removal temporarily refused");
                }
            }
            return null;
        }).when(lifecycle).removeIfExistsStrict(TEST_CONTAINER_ID);
        try {
            if (retry) {
                assertThrows(IllegalStateException.class, () -> manager.cancelLaunch(instance));
                verify(firewallLifecycle, never()).removeIfExistsStrict(anyString());
            }
            assertTrue(manager.cancelLaunch(instance));
            assertTrue(manager.cancelLaunch(instance));
            verify(firewallLifecycle).removeIfExistsStrict("old-helper");
            verify(firewallLifecycle, never()).removeIfExistsStrict("new-helper");
            assertEquals(replace, firewall.vpcForClient("172.18.0.3").isPresent());
            assertEquals("terminated", instance.getState().getName());
        } finally {
            manager.stop();
        }
    }

    @Test
    void terminationRetainsFirewallIdentityAfterInterfacesAreReleased() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
        when(firewall.enabled()).thenReturn(true);
        Runnable firewallCleanup = mock(Runnable.class);
        when(firewall.captureUnregister("eni-firewall-teardown")).thenReturn(firewallCleanup);
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                mock(DockerHostResolver.class), docker, mock(PortAllocator.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class), mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class), firewall);
        Instance instance = instance("i-firewall-teardown");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setNetworkInterfaceId("eni-firewall-teardown");
        instance.setNetworkInterfaces(new ArrayList<>(List.of(eni)));
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        InspectContainerCmd inspection = mock(InspectContainerCmd.class);
        InspectContainerResponse inspected = inspectResponse("172.18.0.12");
        when(inspection.exec()).thenReturn(inspected);
        when(docker.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspection);
        doAnswer(invocation -> {
            // The service releases ENIs while container teardown is still in progress.
            instance.getNetworkInterfaces().clear();
            return null;
        }).when(remove).exec();
        CountDownLatch completed = new CountDownLatch(1);
        try {
            manager.terminate(instance, completed::countDown);
            assertTrue(completed.await(5, TimeUnit.SECONDS), "Container teardown must finish");
            assertEquals("terminated", instance.getState().getName());
            verify(firewall).captureUnregister("eni-firewall-teardown");
            verify(firewallCleanup).run();
        } finally {
            manager.stop();
        }
    }

    @AfterEach
    void resetBridgeIpPolling() {
        Ec2ContainerManager.containerBridgeIpAttempts = 30;
        Ec2ContainerManager.containerBridgeIpPollMillis = 500;
    }

    @Test
    void restartVerificationRefusalDoesNotStartDockerOrRunCompletion() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        DockerClient docker = mock(DockerClient.class);
        ExecutorService executor = mock(ExecutorService.class);
        List<Runnable> tasks = new ArrayList<>();
        doAnswer(invocation -> {
            tasks.add(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));
        Ec2MetadataServer metadata = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = new Ec2ContainerManager(mock(ContainerBuilder.class), lifecycle,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), mock(DockerHostResolver.class),
                docker, mock(PortAllocator.class), mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                metadata, mock(Ec2PortForwardManager.class), mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class), executor, Duration.ofSeconds(5));
        Instance instance = new Instance();
        instance.setInstanceId("i-mount-refusal");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setOwnerId("000000000000");
        instance.setRegion("us-east-1");
        Runnable completed = mock(Runnable.class);
        AtomicBoolean verified = new AtomicBoolean();

        manager.start(instance, () -> {
            verified.set(true);
            throw new IllegalStateException("retained mount missing");
        }, completed);
        assertEquals(1, tasks.size());
        tasks.getFirst().run();

        assertTrue(verified.get());
        assertEquals("stopped", instance.getState().getName());
        verify(docker, never()).startContainerCmd(anyString());
        verify(completed, never()).run();
        verifyNoInteractions(metadata);
    }

    @Test
    void containerlessRestartVerifiesBeforeRunningAndCompletion() {
        Ec2ContainerManager manager = managerWith(mock(ContainerLifecycleManager.class),
                mock(DockerClient.class), mock(Ec2MetadataServer.class));
        Instance instance = new Instance();
        instance.setState(InstanceState.stopped());
        List<String> events = new ArrayList<>();

        manager.start(instance, () -> events.add("verify " + instance.getState().getName()),
                () -> events.add("complete " + instance.getState().getName()));

        assertEquals(List.of("verify stopped", "complete running"), events);
    }

    @Test
    void containerlessRestartVerificationFailureKeepsStoppedState() {
        Ec2ContainerManager manager = managerWith(mock(ContainerLifecycleManager.class),
                mock(DockerClient.class), mock(Ec2MetadataServer.class));
        Instance instance = new Instance();
        instance.setState(InstanceState.stopped());
        Runnable completed = mock(Runnable.class);
        IllegalStateException failure = new IllegalStateException("mount missing");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.start(instance,
                () -> { throw failure; }, completed)));
        assertEquals("stopped", instance.getState().getName());
        verify(completed, never()).run();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restartPersistsRunningStateBeforePostStartHook(boolean failHook) throws Exception {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);
        DockerClient docker = mock(DockerClient.class);
        recordExecs(docker);
        when(docker.startContainerCmd(TEST_CONTAINER_ID)).thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = vpcAttachedInspectResponse("10.0.1.10", "172.17.0.9");
        when(inspect.exec()).thenReturn(response);
        Ec2ContainerManager manager = managerWith(lifecycle, docker, mock(Ec2MetadataServer.class));
        Instance instance = new Instance();
        instance.setInstanceId("i-persist-before-hook");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("10.0.1.10");
        AtomicReference<String> storedState = new AtomicReference<>("stopped");
        AtomicReference<String> stateAtHook = new AtomicReference<>();
        CountDownLatch hookEntered = new CountDownLatch(1);
        manager.setPersister(value -> storedState.set(value.getState().getName()));

        manager.start(instance, () -> {
            stateAtHook.set(storedState.get());
            hookEntered.countDown();
            if (failHook) {
                throw new IllegalStateException("attachment restoration refused");
            }
        });

        assertTrue(hookEntered.await(5, TimeUnit.SECONDS), "post-start hook did not run");
        assertEquals("running", stateAtHook.get(), "the hook must observe the persisted running state");
        assertEquals("running", storedState.get());
    }

    @Test
    void startOfAProtectedInstanceMigratesTheImdsEndpointInItsHelper() throws Exception {
        // The helper and its old TCP/80 listener survive a workload stop and an emulator upgrade.
        DockerClient dockerClient = mock(DockerClient.class);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, "helper-1", 0, "", "");
        List<List<String>> expected = List.of(List.of(Ec2MetadataProxy.ec2NamespaceInstallCommand()),
                List.of(Ec2MetadataProxy.ec2NamespaceStartCommand("floci-imds-host", 9169, true)));
        Instance instance = startProtectedInstance(mock(Ec2MetadataServer.class), Map.of(
                "floci.security-group-helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "floci_owner_port", "4566"), dockerClient, () -> assertEquals(expected, commands,
                        "the retained listener must be migrated before the workload starts"));

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(5));
        assertEquals(expected, commands);
        verify(dockerClient, never()).execCreateCmd(TEST_CONTAINER_ID);
    }

    @ParameterizedTest
    @CsvSource({"enabled", "disabled"})
    void metadataNamespaceRoutingCompletesBeforeRestart(String endpoint) throws Exception {
        DockerClient docker = mock(DockerClient.class);
        Ec2MetadataServer metadata = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, "helper-1", 0, "", "");
        AtomicReference<Instance> expected = new AtomicReference<>();
        Instance instance = startProtectedInstance(metadata, metadataHelperLabels(), docker, () -> {
            assertEquals(List.of(List.of(Ec2MetadataProxy.ec2NamespaceInstallCommand()),
                    List.of(Ec2MetadataProxy.ec2NamespaceStartCommand("floci-imds-host", 9169,
                            !"disabled".equals(endpoint)))), commands);
            assertSame(expected.get(), metadata.registeredContainer("172.17.0.7").orElseThrow());
        }, existing -> {
            LaunchTemplateData.MetadataOptions options = new LaunchTemplateData.MetadataOptions();
            options.setHttpEndpoint(endpoint);
            existing.setMetadataOptions(options);
            expected.set(existing);
        });

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(5));
        assertEquals("172.17.0.7", instance.getContainerBridgeIp());
        verify(docker, never()).execCreateCmd(TEST_CONTAINER_ID);
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void metadataNamespaceRoutingFailureNeverStartsGuest(boolean retained) throws Exception {
        DockerClient docker = mock(DockerClient.class);
        Ec2MetadataServer metadata = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, "helper-1",
                List.of(0L, 1L), "", "route failed");
        Instance instance = startProtectedInstance(metadata, metadataHelperLabels(), docker, () -> { }, existing -> {
            if (retained) {
                metadata.registerContainer("172.17.0.7", "i-protected", existing);
            }
        });

        awaitUntil(() -> "stopped".equals(instance.getState().getName())
                && retained == metadata.registeredContainer("172.17.0.7").isPresent(), Duration.ofSeconds(5));
        assertEquals(List.of(List.of(Ec2MetadataProxy.ec2NamespaceInstallCommand()),
                List.of(Ec2MetadataProxy.ec2NamespaceStartCommand("floci-imds-host", 9169, true))), commands);
        verify(docker.startContainerCmd(TEST_CONTAINER_ID), never()).exec();
        assertEquals(retained, metadata.registeredContainer("172.17.0.7").isPresent());
    }

    @Test
    void metadataNamespaceDockerFailureRollsBackOnlyNewRegistration() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        Ec2MetadataServer metadata = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, "helper-1", 0, "", "");
        Instance instance = startProtectedInstance(metadata, metadataHelperLabels(), docker, () -> {
            throw new IllegalStateException("Docker start failed");
        }, existing -> metadata.registerContainer("172.17.0.8", "i-protected", existing));

        awaitUntil(() -> "stopped".equals(instance.getState().getName())
                && metadata.registeredContainer("172.17.0.7").isEmpty(), Duration.ofSeconds(5));
        assertEquals(List.of(List.of(Ec2MetadataProxy.ec2NamespaceInstallCommand()),
                List.of(Ec2MetadataProxy.ec2NamespaceStartCommand("floci-imds-host", 9169, true))), commands);
        verify(docker.startContainerCmd(TEST_CONTAINER_ID)).exec();
        assertSame(instance, metadata.registeredContainer("172.17.0.8").orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({"io.floci.owner,4567", "io.floci.resource-id,i-other", "io.floci.service,lambda",
            "io.floci.ec2.metadata-helper,false", "floci_owner_port,4567"})
    void metadataNamespaceRejectsForeignOrInconsistentOwnership(String key, String value) throws Exception {
        DockerClient docker = mock(DockerClient.class);
        Ec2MetadataServer metadata = mock(Ec2MetadataServer.class);
        Map<String, String> labels = new HashMap<>(metadataHelperLabels());
        labels.put(key, value);
        Instance instance = startProtectedInstance(metadata, labels, docker);

        awaitUntil(() -> "stopped".equals(instance.getState().getName()), Duration.ofSeconds(5));
        verify(docker.startContainerCmd(TEST_CONTAINER_ID), never()).exec();
        verify(docker, never()).execCreateCmd(anyString());
        verify(metadata, never()).registerContainer(anyString(), anyString(), any());
    }

    private static Map<String, String> metadataHelperLabels() {
        return Map.of(Ec2MetadataNamespace.HELPER_LABEL, "true", "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected", "io.floci.owner", "4566");
    }

    @Test
    void failedProtectedStartRemovesNewMetadataRegistration() throws Exception {
        Ec2MetadataServer metadataServer = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        Instance instance = startProtectedInstance(metadataServer, Map.of(
                "floci.security-group-helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "floci_owner_port", "4566"), mock(DockerClient.class),
                () -> {
                    throw new IllegalStateException("Docker start failed");
                });

        awaitUntil(() -> "stopped".equals(instance.getState().getName())
                && metadataServer.registeredContainer("172.17.0.7").isEmpty(), Duration.ofSeconds(5));
        assertTrue(metadataServer.registeredContainer("172.17.0.7").isEmpty(),
                "a failed Docker start must not leave a new IMDS mapping to a stopped workload");
    }

    @Test
    void failedProtectedStartKeepsExistingMetadataRegistration() throws Exception {
        Ec2MetadataServer metadataServer = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        Instance instance = startProtectedInstance(metadataServer, Map.of(
                "floci.security-group-helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "floci_owner_port", "4566"), mock(DockerClient.class),
                () -> {
                    throw new IllegalStateException("Docker start failed");
                },
                existing -> metadataServer.registerContainer("172.17.0.7", "i-protected", existing));

        awaitUntil(() -> "stopped".equals(instance.getState().getName()), Duration.ofSeconds(5));
        assertEquals(instance, metadataServer.registeredContainer("172.17.0.7").orElseThrow(),
                "a failed restart must retain the mapping left by an earlier successful run");
    }

    @Test
    void failedProtectedStartDoesNotRestorePreviousInstanceAtReusedHelperAddress() throws Exception {
        Ec2MetadataServer metadataServer = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        Instance previous = new Instance();
        previous.setInstanceId("i-old-helper-owner");
        Instance instance = startProtectedInstance(metadataServer, Map.of(
                "floci.security-group-helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "floci_owner_port", "4566"), mock(DockerClient.class),
                () -> {
                    throw new IllegalStateException("Docker start failed");
                },
                ignored -> metadataServer.registerContainer("172.17.0.7", previous.getInstanceId(), previous));

        awaitUntil(() -> "stopped".equals(instance.getState().getName())
                && metadataServer.registeredContainer("172.17.0.7").isEmpty(), Duration.ofSeconds(5));
        assertTrue(metadataServer.registeredContainer("172.17.0.7").isEmpty(),
                "rollback must not resurrect a previous instance after Docker reuses its helper address");
    }

    @Test
    void failedProtectedStartKeepsOtherMetadataAddressesForTheInstance() throws Exception {
        Ec2MetadataServer metadataServer = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        Instance instance = startProtectedInstance(metadataServer, Map.of(
                "floci.security-group-helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "floci_owner_port", "4566"), mock(DockerClient.class),
                () -> {
                    throw new IllegalStateException("Docker start failed");
                },
                existing -> {
                    metadataServer.registerContainer("172.17.0.8", "i-protected", existing);
                    existing.setImdsSourceIp("172.17.0.8");
                });

        awaitUntil(() -> "stopped".equals(instance.getState().getName())
                && metadataServer.registeredContainer("172.17.0.7").isEmpty(), Duration.ofSeconds(5));
        assertEquals(instance, metadataServer.registeredContainer("172.17.0.8").orElseThrow(),
                "rolling back the new helper address must preserve other retained addresses");
        assertEquals("172.17.0.8", instance.getImdsSourceIp());
    }

    @Test
    void cancelledProtectedStartRemovesNewMetadataRegistration() throws Exception {
        Ec2MetadataServer metadataServer = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        AtomicReference<Instance> startingInstance = new AtomicReference<>();
        CountDownLatch dockerStarted = new CountDownLatch(1);
        Instance instance = startProtectedInstance(metadataServer, Map.of(
                "floci.security-group-helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "floci_owner_port", "4566"), mock(DockerClient.class),
                () -> {
                    startingInstance.get().setState(InstanceState.stopping());
                    dockerStarted.countDown();
                }, startingInstance::set);

        assertTrue(dockerStarted.await(5, TimeUnit.SECONDS));
        awaitUntil(() -> metadataServer.registeredContainer("172.17.0.7").isEmpty(), Duration.ofSeconds(5));
        assertEquals("stopping", instance.getState().getName());
    }

    private static List<String[]> recordExecs(DockerClient dockerClient, String containerId) {
        return recordExecs(dockerClient, containerId, () -> { });
    }

    private static List<String[]> recordExecs(DockerClient dockerClient, String containerId, Runnable onExec) {
        List<String[]> execs = new CopyOnWriteArrayList<>();
        ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(dockerClient.execCreateCmd(containerId)).thenReturn(execCreate);
        when(execCreate.withCmd(any(String[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            execs.add(args.length == 1 && args[0] instanceof String[] cmd
                    ? cmd : Arrays.copyOf(args, args.length, String[].class));
            onExec.run();
            return execCreate;
        });
        when(execCreate.exec()).thenThrow(new IllegalStateException("no exec in this test"));
        return execs;
    }

    private Instance startProtectedInstance(Ec2MetadataServer metadataServer, Map<String, String> helperLabels,
                                            DockerClient dockerClient, Runnable onStart) {
        return startProtectedInstance(metadataServer, helperLabels, dockerClient, onStart, ignored -> { });
    }

    private Instance startProtectedInstance(Ec2MetadataServer metadataServer, Map<String, String> helperLabels,
                                            DockerClient dockerClient, Runnable onStart, Consumer<Instance> beforeStart) {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        StartContainerCmd start = mock(StartContainerCmd.class, RETURNS_SELF);
        when(dockerClient.startContainerCmd(TEST_CONTAINER_ID)).thenReturn(start);
        doAnswer(invocation -> {
            onStart.run();
            return null;
        }).when(start).exec();
        // A workload in the helper's namespace has no Docker attachment of its own, so there is
        // no bridge address on it to rediscover after the restart.
        InspectContainerResponse workload = mock(InspectContainerResponse.class);
        HostConfig hostConfig = mock(HostConfig.class);
        when(hostConfig.getNetworkMode()).thenReturn("container:helper-1");
        when(workload.getHostConfig()).thenReturn(hostConfig);
        InspectContainerCmd inspectWorkload = mock(InspectContainerCmd.class);
        when(inspectWorkload.exec()).thenReturn(workload);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspectWorkload);

        InspectContainerResponse helper = mock(InspectContainerResponse.class);
        ContainerConfig helperConfig = mock(ContainerConfig.class);
        when(helperConfig.getLabels()).thenReturn(helperLabels);
        when(helper.getConfig()).thenReturn(helperConfig);
        NetworkSettings helperNetworks = mock(NetworkSettings.class);
        when(helperNetworks.getNetworks())
                .thenReturn(Map.of("bridge", new ContainerNetwork().withIpv4Address("172.17.0.7")));
        when(helper.getNetworkSettings()).thenReturn(helperNetworks);
        InspectContainerCmd inspectHelper = mock(InspectContainerCmd.class);
        when(inspectHelper.exec()).thenReturn(helper);
        when(dockerClient.inspectContainerCmd("helper-1")).thenReturn(inspectHelper);

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());

        when(config.services().ec2().imdsPort()).thenReturn(9169);
        DockerHostResolver hostResolver = mock(DockerHostResolver.class);
        when(hostResolver.resolve()).thenReturn("floci-imds-host");
        boolean hasMetadataExec = mockingDetails(dockerClient).getStubbings().stream()
                .anyMatch(stubbing -> stubbing.getInvocation().getMethod().getName().equals("execCreateCmd")
                        && Arrays.equals(stubbing.getInvocation().getArguments(), new Object[]{"helper-1"}));
        if (!hasMetadataExec) {
            stubMetadataExecs(dockerClient, "helper-1");
        }
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class),
                lifecycleManager,
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                hostResolver,
                dockerClient,
                mock(PortAllocator.class),
                config,
                metadataServer,
                mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class),
                mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class),
                mock(SecurityGroupFirewallManager.class));

        Instance instance = new Instance();
        instance.setInstanceId("i-protected");
        instance.setDockerContainerId(TEST_CONTAINER_ID);

        beforeStart.accept(instance);
        manager.start(instance);
        return instance;
    }

    @Test
    void aLookupThatOutlivesAStopAndStartDoesNotOverwriteTheNewAddress() throws Exception {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        // A start keeps the container, so both lookups inspect the same id.
        InspectContainerResponse beforeStop = inspectWithNetworks(
                Map.of("floci-net", new ContainerNetwork().withIpv4Address("172.20.0.9")));
        InspectContainerResponse afterStart = inspectWithNetworks(
                Map.of("floci-net", new ContainerNetwork().withIpv4Address("172.20.0.33")));
        PausedLookup stale = PausedLookup.onInspect(docker, TEST_CONTAINER_ID, beforeStop, afterStart);
        Ec2ContainerManager manager = managerOnServiceNetwork(true, true, docker);
        Instance instance = serviceNetworkInstance();

        stale.start(manager, instance);
        manager.refreshServiceNetworkIp(instance);
        stale.finish();

        assertEquals("172.20.0.33", instance.getServiceNetworkIp());
        assertEquals("172.20.0.33", manager.reachablePublicAddress(instance));
    }

    @Test
    void terminationDropsTheLookupStateAndALaterLookupLeavesNone() {
        DockerClient docker = guestDocker("172.20.0.9", null);
        Ec2ContainerManager manager = managerOnServiceNetwork(true, true, docker);
        Instance instance = serviceNetworkInstance();
        manager.refreshServiceNetworkIp(instance);
        assertTrue(manager.hasServiceNetworkLookupStateForTest(instance.getInstanceId()));

        manager.terminate(instance, null);
        clearInvocations(docker);
        // A retry queued before the teardown runs after it.
        manager.refreshServiceNetworkIp(instance);

        assertFalse(manager.hasServiceNetworkLookupStateForTest(instance.getInstanceId()));
        verify(docker, never()).inspectContainerCmd(anyString());
    }

    @Test
    void launchLabelsContainerWithResourceIdentity() throws Exception {
        LaunchHarness harness = launchHarness();
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = inspectResponse("192.168.215.42");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        Instance instance = instance("i-labeltest");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        verify(harness.builder, timeout(2000).times(2)).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-labeltest",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-west-2"));
    }

    @Test
    void metadataProxyStartCommandRoutesAwsLinkLocalMetadataWithoutReservingPort80() {
        String[] command = Ec2ContainerManager.metadataProxyStartCommand("floci", 9169);

        assertEquals("sh", command[0]);
        assertTrue(command[2].contains("169.254.169.254/32"));
        assertTrue(command[2].contains("TCP-LISTEN:42169,bind=169.254.169.254"));
        assertTrue(command[2].contains("-d 169.254.169.254/32 -p tcp --dport 80 -j DNAT"
                + " --to-destination 169.254.169.254:42169"));
        assertTrue(command[2].contains("if [ -z \"$rule_backend\" ]; then"));
        assertTrue(command[2].contains("nohup socat TCP-LISTEN:80,bind=169.254.169.254"));
        assertTrue(command[2].contains("TCP:floci:9169"));
        assertTrue(command[2].contains("http://169.254.169.254/latest/meta-data/instance-id"));
    }

    @Test
    void launchPointsTheInstanceSdkAtTheSharedContainerEndpointAndKeepsImdsOnTheHostAddress() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 1;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.13");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-endpoint");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.builder).withEnv(List.of(
                "AWS_EC2_METADATA_SERVICE_ENDPOINT=http://169.254.169.254",
                "AWS_ENDPOINT_URL=http://localhost.floci.io:4680",
                "AWS_DEFAULT_REGION=us-west-2",
                "AWS_REGION=us-west-2",
                "AWS_ACCESS_KEY_ID=test",
                "AWS_SECRET_ACCESS_KEY=test",
                "AWS_SESSION_TOKEN=test-session-token"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableDockerRefusesManagedMountsButPreservesMetadataOnlyLaunch(boolean managedMounts) {
        LaunchHarness harness = launchHarness();
        PingCmd ping = mock(PingCmd.class);
        when(harness.dockerClient.pingCmd()).thenReturn(ping);
        doThrow(new IllegalStateException("daemon unavailable")).when(ping).exec();
        Instance instance = leasedInstance("i-managed-unavailable");
        List<Ec2ImageCatalog.VolumeMount> mounts = managedMounts
                ? List.of(new Ec2ImageCatalog.VolumeMount("/dev/sdf", "/data", "ext4")) : List.of();
        ResolvedAmiImage image = new ResolvedAmiImage("example/image:1", "image", false, null, mounts);

        harness.manager.launch(instance, image, null, "us-west-2");

        assertEquals(managedMounts ? "terminated" : "running", instance.getState().getName());
        assertNull(instance.getDockerContainerId());
        verify(harness.lifecycleManager, never()).create(any(ContainerSpec.class));
        verify(harness.lifecycleManager, never()).createAndStart(any(ContainerSpec.class));
        verify(harness.dockerClient, never()).startContainerCmd(anyString());
        if (managedMounts) {
            assertNull(instance.getPublicIpAddress());
            verify(harness.vpcNetworkManager).releasePrivateIp(instance.getOwnerId(), "us-west-2", "subnet-lease", "10.0.1.10");
        }
    }

    @ParameterizedTest
    @CsvSource({"false,image", "true,image", "false,systemd"})
    void launchUsesOneImmutablePreparedMountSnapshotBeforeAllocation(boolean sameDevice, String runtime)
            throws Exception {
        LaunchHarness harness = launchHarness();
        try {
            harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
            Instance instance = instance("i-prepared-mount");
            InfoCmd infoCmd = mock(InfoCmd.class);
            Info info = mock(Info.class);
            when(harness.dockerClient.infoCmd()).thenReturn(infoCmd);
            when(infoCmd.exec()).thenReturn(info);
            when(info.getKernelVersion()).thenReturn("6.8.0-test");
            String device = sameDevice ? "/dev/loop42" : "/dev/sdf";
            Ec2ImageCatalog.VolumeMount mapping = new Ec2ImageCatalog.VolumeMount(device, "/data", "ext4");
            List<Ec2VolumeBlockDeviceManager.PreparedMount> mounts = new ArrayList<>(List.of(
                    new Ec2VolumeBlockDeviceManager.PreparedMount("vol-data", device, "/dev/loop42",
                            "floci-volume", "/data")));
            AtomicInteger preparations = new AtomicInteger();
            CountDownLatch started = new CountDownLatch(1);
            when(harness.portAllocator.allocate(anyInt(), anyInt())).thenAnswer(invocation -> {
                assertEquals(1, preparations.get());
                mounts.clear();
                return 2201;
            });

            harness.manager.launch(instance,
                    new ResolvedAmiImage("example/image:1", runtime, "systemd".equals(runtime), null, List.of(mapping)),
                    null, "us-west-2", Ec2PortForwardManager.DesiredPorts.NONE, List.of(), Map.of(), () -> {
                        verify(harness.portAllocator, never()).allocate(anyInt(), anyInt());
                        verify(harness.lifecycleManager, never()).create(any(ContainerSpec.class));
                        preparations.incrementAndGet();
                        return mounts;
                    }, started::countDown);

            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(1, preparations.get());
            assertEquals("running", instance.getState().getName());
            verify(harness.builder).withNamedVolume("floci-volume", "/data");
            verify(harness.builder).withDevice("/dev/loop42", device, "rw");
            if (!sameDevice) {
                verify(harness.builder).withDevice("/dev/loop42", "/dev/loop42", "rw");
            }
            verify(harness.builder).withCapAdd("SYS_RESOURCE");
            verify(harness.builder).withPrivileged(false);
            if ("systemd".equals(runtime)) {
                verify(harness.builder).withCmd(TEST_SYSTEMD_INIT_COMMAND);
                verify(harness.builder).withCgroupnsMode("host");
                verify(harness.builder).withBind("/sys/fs/cgroup", "/sys/fs/cgroup");
                verify(harness.builder).withReadOnlyBind("/lib/modules/6.8.0-test", "/lib/modules/6.8.0-test");
                verify(harness.builder, never()).withReadOnlyBind(eq("/lib/modules"), anyString());
            }
            verify(harness.builder, never()).withCmd(List.of("tail", "-f", "/dev/null"));
        } finally {
            harness.manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYS_RESOURCE", "CAP_SYS_RESOURCE", "ALL", "/data", "/data/child", "/"})
    void managedMountOptionsRefuseBeforePreparingVolumes(String option) throws Exception {
        LaunchHarness harness = launchHarness();
        try {
            EmulatorConfig.Ec2ServiceConfig ec2 = harness.config.services().ec2();
            if (option.startsWith("/")) {
                EmulatorConfig.Ec2ServiceConfig.GuestReadOnlyBind bind =
                        mock(EmulatorConfig.Ec2ServiceConfig.GuestReadOnlyBind.class);
                when(bind.source()).thenReturn("/host/input");
                when(bind.target()).thenReturn(option);
                when(ec2.readOnlyBinds()).thenReturn(List.of(bind));
            } else {
                when(ec2.capDrop()).thenReturn(Optional.of(List.of(option)));
            }
            Instance instance = instance("i-refused-options");
            AtomicInteger preparations = new AtomicInteger();
            Ec2ImageCatalog.VolumeMount mapping = new Ec2ImageCatalog.VolumeMount("/dev/sdf", "/data", "ext4");

            harness.manager.launch(instance,
                    new ResolvedAmiImage("example/image:1", "image", false, null, List.of(mapping)),
                    null, "us-west-2", Ec2PortForwardManager.DesiredPorts.NONE, List.of(), Map.of(), () -> {
                        preparations.incrementAndGet();
                        return List.of();
                    }, null);

            awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(5));
            assertEquals(0, preparations.get());
            verify(harness.portAllocator, never()).allocate(anyInt(), anyInt());
            verify(harness.lifecycleManager, never()).create(any(ContainerSpec.class));
            verify(harness.lifecycleManager, never()).createAndStart(any(ContainerSpec.class));
        } finally {
            harness.manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"failure", "missing", "null"})
    void invalidPreparedMountsRefuseBeforePortsAndContainers(String mode) throws Exception {
        LaunchHarness harness = launchHarness();
        try {
            Instance instance = instance("i-invalid-prepared");
            Ec2ImageCatalog.VolumeMount mapping = new Ec2ImageCatalog.VolumeMount("sdf", "/data", "ext4");
            AtomicInteger preparations = new AtomicInteger();
            AtomicBoolean hookCalled = new AtomicBoolean();

            harness.manager.launch(instance,
                    new ResolvedAmiImage("example/image:1", "image", false, null, List.of(mapping)),
                    null, "us-west-2", Ec2PortForwardManager.DesiredPorts.NONE, List.of(), Map.of(), () -> {
                        preparations.incrementAndGet();
                        return switch (mode) {
                            case "failure" -> throw new IllegalStateException("backing file contains unknown data");
                            case "missing" -> List.of();
                            default -> null;
                        };
                    }, () -> hookCalled.set(true));

            awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(5));
            assertEquals(1, preparations.get());
            assertFalse(hookCalled.get());
            assertNull(instance.getDockerContainerId());
            verify(harness.portAllocator, never()).allocate(anyInt(), anyInt());
            verify(harness.lifecycleManager, never()).create(any(ContainerSpec.class));
            verify(harness.lifecycleManager, never()).createAndStart(any(ContainerSpec.class));
        } finally {
            harness.manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void launchPostStartHookObservesPersistedRunningState(boolean failHook) throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.config.services().ec2().privileged()).thenReturn(true);
        try {
            harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
            Instance instance = instance("i-launch-hook");
            AtomicReference<String> stored = new AtomicReference<>();
            AtomicReference<String> stateAtHook = new AtomicReference<>();
            AtomicInteger preparations = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch removed = new CountDownLatch(1);
            doAnswer(invocation -> {
                removed.countDown();
                return null;
            }).when(harness.lifecycleManager).removeIfExistsStrict(TEST_CONTAINER_ID);
            harness.manager.setPersister(value -> stored.set(value.getState().getName()));

            harness.manager.launch(instance, ResolvedAmiImage.minimal("ubuntu:24.04"), null, "us-west-2",
                    Ec2PortForwardManager.DesiredPorts.NONE, List.of(), Map.of(), () -> {
                        preparations.incrementAndGet();
                        return List.of();
                    }, () -> {
                        stateAtHook.set(stored.get());
                        entered.countDown();
                        if (failHook) {
                            throw new IllegalStateException("attachment callback refused");
                        }
                    });

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, preparations.get());
            assertEquals("running", stateAtHook.get());
            if (failHook) {
                awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(5));
                assertTrue(removed.await(5, TimeUnit.SECONDS), "failed guest cleanup did not run");
            } else {
                assertEquals("running", instance.getState().getName());
            }
            verify(harness.builder, never()).withNamedVolume(anyString(), anyString());
            verify(harness.builder).withPrivileged(true);
            verify(harness.builder).withCmd(List.of("tail", "-f", "/dev/null"));
        } finally {
            harness.manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void launchSystemdGuestUsesInitInsteadOfTail(boolean cloudInit) throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 1;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        when(harness.config.services().ec2().privileged()).thenReturn(true);
        InfoCmd infoCmd = mock(InfoCmd.class);
        Info info = mock(Info.class);
        when(harness.dockerClient.infoCmd()).thenReturn(infoCmd);
        when(infoCmd.exec()).thenReturn(info);
        when(info.getKernelVersion()).thenReturn("6.8.0-test");
        when(harness.lifecycleManager.create(any(ContainerSpec.class), eq("linux/arm64")))
                .thenReturn(TEST_CONTAINER_ID);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.11");
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-systemd");
        instance.setUserData("#!/bin/sh\necho ready\n");

        harness.manager.launch(instance,
                new ResolvedAmiImage("floci/ami-ubuntu:24.04-arm64", ResolvedAmiImage.SYSTEMD_RUNTIME, cloudInit,
                        "linux/arm64"),
                null,
                "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.builder).withCmd(argThat((List<String> command) -> "/sbin/init".equals(command.getFirst())));
        verify(harness.builder).withCgroupnsMode("private");
        verify(harness.builder, never()).withBind("/sys/fs/cgroup", "/sys/fs/cgroup");
        verify(harness.builder).withReadOnlyBind("/lib/modules/6.8.0-test", "/lib/modules/6.8.0-test");
        verify(harness.builder, never()).withReadOnlyBind(eq("/lib/modules"), anyString());
        harness.manager.pauseAndDrainLaunches(Duration.ofSeconds(2));
        assertEquals(!cloudInit, commandIndex(harness.executedCommands, "/var/lib/user-data.sh") >= 0,
                "cloud-init owns user data; only compatibility images need Floci to execute it");
        assertEquals(-1, commandIndex(harness.executedCommands, "/usr/sbin/sshd"),
                "a systemd guest's init starts sshd; Floci must not start a second one");
        verify(harness.lifecycleManager).create(any(ContainerSpec.class), eq("linux/arm64"));
    }

    @Test
    void systemdGuestMasksHostConsolesAndKeepsPseudoTerminals() throws Exception {
        ContainerSpec spec = launchSpecForImage(new ResolvedAmiImage(
                "floci/ami-ubuntu:24.04", ResolvedAmiImage.SYSTEMD_RUNTIME, false, null));

        Set<String> consolePaths = new HashSet<>();
        consolePaths.add("/dev/console");
        for (int tty = 0; tty < 64; tty++) {
            consolePaths.add("/dev/tty" + tty);
        }
        Set<String> maskedPaths = new HashSet<>();
        for (Bind bind : spec.binds()) {
            String path = bind.getVolume().getPath();
            if (path.startsWith("/dev/")) {
                assertEquals("/dev/null", bind.getPath(), path);
                assertEquals(AccessMode.ro, bind.getAccessMode(), path);
                maskedPaths.add(path);
            }
        }
        assertEquals(consolePaths, maskedPaths);
        assertTrue(spec.env().contains("container=docker"));
        assertEquals(List.of("/sbin/init", "systemd.mask=getty@.service", "systemd.mask=autovt@.service",
                "systemd.mask=console-getty.service", "systemd.mask=container-getty@.service",
                "systemd.mask=serial-getty@.service", "systemd.mask=getty-static.service"), spec.cmd());
    }

    @Test
    void nonSystemdGuestKeepsTheTailCommandWithoutConsoleMasks() throws Exception {
        ContainerSpec spec = launchSpecForImage(ResolvedAmiImage.minimal("ubuntu:24.04"));

        assertEquals(List.of("tail", "-f", "/dev/null"), spec.cmd());
        assertTrue(spec.binds().isEmpty());
        assertFalse(spec.env().contains("container=docker"));
    }

    private ContainerSpec launchSpecForImage(ResolvedAmiImage image) throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 1;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(harness.config.docker()).thenReturn(docker);
        when(docker.logMaxSize()).thenReturn("10m");
        when(docker.logMaxFile()).thenReturn("3");
        ContainerBuilder actualBuilder = new ContainerBuilder(harness.config, mock(DockerHostResolver.class),
                mock(EmbeddedDnsServer.class));
        ContainerBuilder.Builder builder = actualBuilder.newContainer(image.dockerImage());
        when(harness.containerBuilder.newContainer(image.dockerImage())).thenReturn(builder);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.11");
        when(inspect.exec()).thenReturn(response);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-console-settings");

        try {
            harness.manager.launch(instance, image, null, "us-west-2");

            awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
            ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
            verify(harness.lifecycleManager).create(spec.capture());
            return spec.getValue();
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void privilegedSystemdGuestGetsAPrivateCgroupNamespaceAndNoHostTree() {
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        Ec2ContainerManager.applySystemdCgroup(spec, true);
        verify(spec).withCgroupnsMode("private");
        verify(spec, never()).withBind(anyString(), anyString());
    }

    @Test
    void unprivilegedSystemdGuestUnderSecurityGroupEnforcementKeepsAWritableCgroupTree() {
        // Docker mounts an unprivileged container's private cgroupfs read-only, and systemd
        // cannot boot on it.
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        Ec2ContainerManager.applySystemdCgroup(spec, false);
        verify(spec).withCgroupnsMode("host");
        verify(spec).withBind("/sys/fs/cgroup", "/sys/fs/cgroup");
    }

    @ParameterizedTest
    @CsvSource({"unchanged,false", "cleared,false", "replaced,false",
            "unchanged,true", "cleared,true", "replaced,true"})
    void terminationReleasesTheCapturedPrivateAddress(String mutation, boolean refuseRemoval) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-captured-termination-lease");
        instance.setOwnerId("111122223333");
        instance.setRegion("us-west-2");
        instance.setSubnetId("subnet-original");
        instance.setPrivateIpAddress("10.0.1.10");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(response);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        AtomicBoolean refuse = new AtomicBoolean(refuseRemoval);
        doAnswer(invocation -> {
            if (!"unchanged".equals(mutation)) {
                boolean cleared = "cleared".equals(mutation);
                instance.setOwnerId(cleared ? null : "444455556666");
                instance.setRegion(cleared ? null : "eu-west-1");
                instance.setSubnetId(cleared ? null : "subnet-replacement");
                instance.setPrivateIpAddress(cleared ? null : "10.0.2.20");
            }
            if (refuse.get()) {
                throw new IllegalStateException("workload removal refused");
            }
            return null;
        }).when(remove).exec();
        AtomicInteger completed = new AtomicInteger();
        try {
            harness.manager.terminate(instance, completed::incrementAndGet);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

            assertEquals("terminated", instance.getState().getName());
            assertEquals(refuseRemoval ? 0 : 1, completed.get());
            if (refuseRemoval) {
                verify(harness.vpcNetworkManager, never()).releasePrivateIp(any(), any(), any(), any());
                refuse.set(false);
                harness.manager.terminate(instance, completed::incrementAndGet);
                executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            }
            verify(harness.vpcNetworkManager).releasePrivateIp(
                    "111122223333", "us-west-2", "subnet-original", "10.0.1.10");
            verify(harness.vpcNetworkManager, times(1)).releasePrivateIp(any(), any(), any(), any());
            assertEquals("terminated", instance.getState().getName());
            assertEquals(1, completed.get());
            verify(remove, times(refuseRemoval ? 2 : 1)).exec();
        } finally {
            harness.manager.stop();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false,false", "true,true,false", "true,false,true", "true,true,true",
            "false,false,false", "false,true,false", "false,false,true", "false,true,true"})
    @SuppressWarnings("unchecked")
    void terminationRemovesOnlyTheCapturedNetworkHelper(boolean tracked, boolean replace,
                                                       boolean refuseRemoval) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-captured-helper");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        Field helpersField = Ec2ContainerManager.class.getDeclaredField("networkHelpers");
        helpersField.setAccessible(true);
        Map<String, String> helpers = (Map<String, String>) helpersField.get(harness.manager);
        if (tracked) {
            helpers.put(instance.getInstanceId(), TEST_HELPER_ID);
        }
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(response);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        doAnswer(invocation -> {
            if (replace) {
                helpers.put(instance.getInstanceId(), "replacement-helper");
            }
            if (refuseRemoval) {
                throw new IllegalStateException("workload removal refused");
            }
            return null;
        }).when(remove).exec();
        AtomicInteger completed = new AtomicInteger();
        try {
            harness.manager.terminate(instance, completed::incrementAndGet);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

            verify(harness.lifecycleManager, never()).removeIfExistsStrict("replacement-helper");
            verify(harness.lifecycleManager, times(tracked && !refuseRemoval ? 1 : 0)).removeIfExistsStrict(TEST_HELPER_ID);
            assertEquals(replace ? "replacement-helper" : tracked && refuseRemoval ? TEST_HELPER_ID : null,
                    helpers.get(instance.getInstanceId()));
            assertEquals(tracked && refuseRemoval ? "shutting-down" : "terminated", instance.getState().getName());
            assertEquals(refuseRemoval ? 0 : 1, completed.get());
        } finally {
            harness.manager.stop();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void terminationRetryRetainsSuccessfulReleasesAndCallback(boolean refuseWorkload,
                                                            boolean callbackThrows) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-retry-completion");
        instance.setOwnerId("111122223333");
        instance.setRegion("us-west-2");
        instance.setSubnetId("subnet-original");
        instance.setPrivateIpAddress("10.0.1.10");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setSshHostPort(2201);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(response);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        AtomicBoolean refuse = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (refuseWorkload && refuse.get()) {
                throw new IllegalStateException("workload busy");
            }
            return null;
        }).when(remove).exec();
        doAnswer(invocation -> {
            if (!refuseWorkload && refuse.get()) {
                throw new IllegalStateException("address release unavailable");
            }
            return null;
        }).when(harness.vpcNetworkManager).releasePrivateIp(
                "111122223333", "us-west-2", "subnet-original", "10.0.1.10");
        AtomicInteger callbacks = new AtomicInteger();
        Runnable callback = () -> {
            callbacks.incrementAndGet();
            if (callbackThrows) {
                throw new IllegalStateException("callback failed after its side effect");
            }
        };
        try {
            harness.manager.terminate(instance, callback);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertEquals("terminated", instance.getState().getName());
            assertEquals(refuseWorkload ? 0 : 1, callbacks.get());
            verify(harness.portAllocator, times(refuseWorkload ? 0 : 1)).release(2201);
            // A retry must keep the original lease, even when the mutable instance is reused.
            instance.setOwnerId("444455556666");
            instance.setRegion("eu-west-1");
            instance.setSubnetId("subnet-replacement");
            instance.setPrivateIpAddress("10.0.2.20");
            refuse.set(false);
            harness.manager.terminate(instance, callback);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

            assertEquals("terminated", instance.getState().getName());
            assertEquals(1, callbacks.get());
            verify(harness.portAllocator, times(1)).release(2201);
            verify(harness.metadataServer, times(1)).unregisterInstance(instance);
            verify(remove, times(refuseWorkload ? 2 : 1)).exec();
            verify(harness.vpcNetworkManager, times(refuseWorkload ? 1 : 2)).releasePrivateIp(
                    "111122223333", "us-west-2", "subnet-original", "10.0.1.10");
            verify(harness.vpcNetworkManager, never()).releasePrivateIp(
                    "444455556666", "eu-west-1", "subnet-replacement", "10.0.2.20");
        } finally {
            harness.manager.stop();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shutdownTerminationWaitsForLaunchSupplierAndCallback(boolean blockCallback) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(response);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        Instance instance = instance("i-shutdown-active-launch");
        instance.setOwnerId("111122223333");
        instance.setRegion("us-west-2");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable pause = () -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS), "test must release the launch");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        };
        try {
            harness.manager.launch(instance, ResolvedAmiImage.minimal("ubuntu:24.04"), null, "us-west-2",
                    null, List.of(), Map.of(), () -> {
                        if (!blockCallback) {
                            pause.run();
                        }
                        return List.of();
                    }, () -> {
                        if (blockCallback) {
                            pause.run();
                        }
                    });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> harness.manager.terminateForShutdown(instance));
            assertTrue(failure.getMessage().contains("launch has not completed"));
            verify(harness.portForwardManager, never()).unpublishAll(instance);
            release.countDown();
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            // The completed launch no longer blocks teardown. Model a workload already removed externally.
            instance.setDockerContainerId(null);
            harness.manager.terminateForShutdown(instance);
            assertEquals("terminated", instance.getState().getName());
        } finally {
            release.countDown();
            harness.manager.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void shutdownTerminationWaitsForQueuedLaunch() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        CountDownLatch release = new CountDownLatch(1);
        Future<?> parked = executor.submit(() -> {
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        Instance instance = instance("i-shutdown-queued-launch");
        try {
            harness.manager.launch(instance, ResolvedAmiImage.minimal("ubuntu:24.04"), null, "us-west-2",
                    null, List.of(), Map.of(), () -> {
                        throw new IllegalStateException("mount preparation refused");
                    }, null);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> harness.manager.terminateForShutdown(instance));
            assertTrue(failure.getMessage().contains("launch has not completed"));
            assertEquals("pending", instance.getState().getName());
            release.countDown();
            parked.get(5, TimeUnit.SECONDS);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            harness.manager.terminateForShutdown(instance);
            assertEquals("terminated", instance.getState().getName());
        } finally {
            release.countDown();
            harness.manager.stop();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shutdownAccountsForDiscardedLaunches(boolean cleanupFailsOnce) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> parked = executor.submit(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException expected) {
                // shutdownNow interrupts the test-owned worker so its queued launches are returned.
                Thread.currentThread().interrupt();
            }
        });
        Instance first = instance("i-discarded-first");
        Instance second = instance("i-discarded-second");
        AtomicInteger prepared = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        if (cleanupFailsOnce) {
            doThrow(new IllegalStateException("cleanup temporarily unavailable")).doNothing()
                    .when(harness.metadataServer).unregisterInstance(first);
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (Instance instance : List.of(first, second)) {
                instance.setOwnerId("111122223333");
                instance.setRegion("us-west-2");
                harness.manager.launch(instance, ResolvedAmiImage.minimal("ubuntu:24.04"), null, "us-west-2",
                        null, List.of(), Map.of(), () -> {
                            prepared.incrementAndGet();
                            return List.of();
                        }, callbacks::incrementAndGet);
            }
            harness.manager.stop();
            parked.get(5, TimeUnit.SECONDS);
            assertEquals("terminated", first.getState().getName());
            assertEquals("terminated", second.getState().getName());
            verify(harness.metadataServer, times(cleanupFailsOnce ? 2 : 1)).unregisterInstance(first);
            verify(harness.metadataServer).unregisterInstance(second);
            assertEquals(0, prepared.get());
            assertEquals(0, callbacks.get());
            harness.manager.terminateForShutdown(first);
            harness.manager.terminateForShutdown(second);
            assertEquals("terminated", first.getState().getName());
            assertEquals("terminated", second.getState().getName());
        } finally {
            release.countDown();
            harness.manager.stop();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shutdownDoesNotDiscardRunningLaunchOwnership() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        Instance instance = instance("i-shutdown-running-launch");
        try {
            harness.manager.launch(instance, ResolvedAmiImage.minimal("ubuntu:24.04"), null, "us-west-2",
                    null, List.of(), Map.of(), () -> {
                        entered.countDown();
                        release.join();
                        throw new IllegalStateException("test mount preparation refused after release");
                    }, null);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            harness.manager.stop(Duration.ZERO);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> harness.manager.terminateForShutdown(instance));
            assertTrue(failure.getMessage().contains("launch has not completed"));
            release.complete(null);
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            harness.manager.terminateForShutdown(instance);
            assertEquals("terminated", instance.getState().getName());
        } finally {
            release.complete(null);
            harness.manager.stop();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "stop", "reboot"})
    void resetRefusesLifecycleOperationsBeforeStateChanges(String operation) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-reset-lifecycle");
        instance.setState(InstanceState.stopped());
        Runnable callback = mock(Runnable.class);
        try {
            harness.manager.pauseAndDrainLaunches(Duration.ZERO);
            assertThrows(IllegalStateException.class, () -> {
                switch (operation) {
                    case "start" -> harness.manager.start(instance, callback, callback);
                    case "stop" -> harness.manager.stop(instance);
                    case "reboot" -> harness.manager.reboot(instance);
                    default -> throw new AssertionError(operation);
                }
            });
            assertEquals("stopped", instance.getState().getName());
            verifyNoInteractions(callback);
            harness.manager.resumeLaunchesAfterReset();
            harness.manager.start(instance, callback, callback);
            verify(callback, times(2)).run();
            harness.manager.pauseAndDrainLaunches(Duration.ZERO);
        } finally {
            harness.manager.stop();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resetWaitsForContainerlessPostStartEvenWhenCallbackFails(boolean fail) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        Instance instance = instance("i-reset-post-start");
        try {
            Future<?> callback = executor.submit(() -> {
                Runnable start = () -> harness.manager.start(instance, () -> {
                    entered.countDown();
                    release.join();
                    if (fail) {
                        throw new IllegalStateException("callback refused");
                    }
                });
                if (fail) {
                    assertThrows(IllegalStateException.class, start::run);
                } else {
                    start.run();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> harness.manager.pauseAndDrainLaunches(Duration.ZERO));
            assertThrows(IllegalStateException.class, harness.manager::resumeLaunchesAfterReset);
            release.complete(null);
            callback.get(5, TimeUnit.SECONDS);
            harness.manager.pauseAndDrainLaunches(Duration.ofSeconds(5));
            harness.manager.resumeLaunchesAfterReset();
        } finally {
            release.complete(null);
            harness.manager.stop();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void resetWaitsForQueuedStopAndItsStartSuccessor() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> tasks.add(invocation.getArgument(0))).when(executor).execute(any(Runnable.class));
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-reset-chain");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        when(harness.dockerClient.stopContainerCmd(TEST_CONTAINER_ID)).thenReturn(stop);
        harness.manager.stop(instance);
        harness.manager.start(instance, () -> { throw new IllegalStateException("test refuses restart"); }, null);
        assertEquals(1, tasks.size());
        assertThrows(IllegalStateException.class, () -> harness.manager.pauseAndDrainLaunches(Duration.ZERO));
        tasks.getFirst().run();
        awaitUntil(() -> tasks.size() == 2, Duration.ofSeconds(5));
        assertEquals(2, tasks.size());
        assertThrows(IllegalStateException.class, () -> harness.manager.pauseAndDrainLaunches(Duration.ZERO));
        tasks.get(1).run();
        harness.manager.pauseAndDrainLaunches(Duration.ZERO);
        harness.manager.resumeLaunchesAfterReset();
        verify(stop).exec();
        verify(harness.dockerClient, never()).startContainerCmd(anyString());
    }

    @Test
    void resetWaitsForQueuedRebootAndDoesNotReplayIt() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        List<Runnable> tasks = new ArrayList<>();
        doAnswer(invocation -> tasks.add(invocation.getArgument(0))).when(executor).execute(any(Runnable.class));
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-reset-reboot");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setState(InstanceState.running());
        RestartContainerCmd restart = mock(RestartContainerCmd.class);
        when(harness.dockerClient.restartContainerCmd(TEST_CONTAINER_ID)).thenReturn(restart);
        harness.manager.reboot(instance);
        assertEquals(1, tasks.size());
        assertThrows(IllegalStateException.class, () -> harness.manager.pauseAndDrainLaunches(Duration.ZERO));
        tasks.getFirst().run();
        tasks.getFirst().run();
        harness.manager.pauseAndDrainLaunches(Duration.ZERO);
        harness.manager.resumeLaunchesAfterReset();
        verify(harness.dockerClient).restartContainerCmd(TEST_CONTAINER_ID);
        verify(restart).exec();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownReleasesDiscardedLifecycleCallbackOwnership(boolean reboot) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        Future<?> blocker = executor.submit(() -> {
            entered.countDown();
            release.join();
        });
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-discarded-lifecycle");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        Runnable callback = mock(Runnable.class);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (reboot) {
                harness.manager.reboot(instance);
            } else {
                harness.manager.start(instance, callback, callback);
            }
            harness.manager.stop(Duration.ZERO);
            harness.manager.pauseAndDrainLaunches(Duration.ZERO);
            verifyNoInteractions(callback);
            verify(harness.dockerClient, never()).restartContainerCmd(anyString());
            verify(harness.dockerClient, never()).startContainerCmd(anyString());
        } finally {
            release.complete(null);
            blocker.get(5, TimeUnit.SECONDS);
            harness.manager.stop();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void resetWaitsForBackgroundServiceNetworkLookup() throws Exception {
        DockerClient docker = guestDocker("172.20.0.9", null);
        InspectContainerResponse guest = docker.inspectContainerCmd(TEST_CONTAINER_ID).exec();
        when(docker.inspectContainerCmd(TEST_CONTAINER_ID).exec())
                .thenThrow(new IllegalStateException("daemon busy")).thenReturn(guest);
        ExecutorService retryExecutor = mock(ExecutorService.class);
        List<Runnable> tasks = new ArrayList<>();
        doAnswer(invocation -> tasks.add(invocation.getArgument(0))).when(retryExecutor).execute(any(Runnable.class));
        Ec2ContainerManager manager = managerOnServiceNetwork(true, true, docker,
                mock(ExecutorService.class), retryExecutor);
        Instance instance = serviceNetworkInstance();
        manager.refreshServiceNetworkIp(instance);
        manager.reachablePublicAddress(instance);
        assertEquals(1, tasks.size());
        assertThrows(IllegalStateException.class, () -> manager.pauseAndDrainLaunches(Duration.ZERO));
        tasks.getFirst().run();
        manager.pauseAndDrainLaunches(Duration.ZERO);
        assertEquals("172.20.0.9", instance.getServiceNetworkIp());
        manager.resumeLaunchesAfterReset();
    }

    @Test
    void resetDefersNewServiceNetworkRetriesUntilAdmissionResumes() throws Exception {
        DockerClient docker = guestDocker("172.20.0.9", null);
        InspectContainerResponse guest = docker.inspectContainerCmd(TEST_CONTAINER_ID).exec();
        when(docker.inspectContainerCmd(TEST_CONTAINER_ID).exec())
                .thenThrow(new IllegalStateException("daemon busy")).thenReturn(guest);
        ExecutorService retryExecutor = mock(ExecutorService.class);
        List<Runnable> tasks = new ArrayList<>();
        doAnswer(invocation -> tasks.add(invocation.getArgument(0))).when(retryExecutor).execute(any(Runnable.class));
        Ec2ContainerManager manager = managerOnServiceNetwork(true, true, docker,
                mock(ExecutorService.class), retryExecutor);
        Instance instance = serviceNetworkInstance();
        manager.refreshServiceNetworkIp(instance);
        manager.pauseAndDrainLaunches(Duration.ZERO);
        manager.reachablePublicAddress(instance);
        assertTrue(tasks.isEmpty());
        manager.resumeLaunchesAfterReset();
        manager.reachablePublicAddress(instance);
        assertEquals(1, tasks.size());
        tasks.getFirst().run();
        manager.pauseAndDrainLaunches(Duration.ZERO);
    }

    @Test
    void resetWaitsForImdsSetupAfterRestartCallbackCompletes() throws Exception {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);
        DockerClient docker = mock(DockerClient.class);
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        recordExecs(docker, () -> {
            entered.countDown();
            release.join();
        });
        when(docker.startContainerCmd(TEST_CONTAINER_ID)).thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = vpcAttachedInspectResponse("10.0.1.10", "172.17.0.9");
        when(inspect.exec()).thenReturn(response);
        Ec2ContainerManager manager = managerWith(lifecycle, docker, mock(Ec2MetadataServer.class));
        Instance instance = instance("i-reset-imds");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        try {
            manager.start(instance);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("running", instance.getState().getName());
            assertThrows(IllegalStateException.class, () -> manager.pauseAndDrainLaunches(Duration.ZERO));
            release.complete(null);
            manager.pauseAndDrainLaunches(Duration.ofSeconds(5));
            manager.resumeLaunchesAfterReset();
        } finally {
            release.complete(null);
            manager.stop();
        }
    }

    @Test
    void resetLaunchDrainKeepsExecutorReusableAndAdmissionClosed() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        stubDockerPing(harness.dockerClient, false);
        Instance rejected = instance("i-reset-rejected");
        rejected.setState(InstanceState.stopped());
        try {
            harness.manager.pauseAndDrainLaunches(Duration.ZERO);
            assertThrows(IllegalStateException.class,
                    () -> harness.manager.launch(rejected, "ubuntu:24.04", null, "us-west-2"));
            assertEquals("stopped", rejected.getState().getName());
            assertFalse(executor.isShutdown());
            harness.manager.resumeLaunchesAfterReset();
            harness.manager.launch(rejected, "ubuntu:24.04", null, "us-west-2");
            assertEquals("running", rejected.getState().getName());
            harness.manager.pauseAndDrainLaunches(Duration.ZERO);
            assertThrows(IllegalStateException.class,
                    () -> harness.manager.launch(instance("i-reset-second"), "ubuntu:24.04", null, "us-west-2"));
        } finally {
            harness.manager.stop();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void resetLaunchDrainWaitsForAdmittedWorkAndKeepsFailedGateClosed(boolean interrupt) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        Instance running = instance("i-reset-running");
        try {
            harness.manager.launch(running, ResolvedAmiImage.minimal("ubuntu:24.04"), null, "us-west-2",
                    null, List.of(), Map.of(), () -> {
                        entered.countDown();
                        release.join();
                        throw new IllegalStateException("test mount preparation refused after release");
                    }, null);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (interrupt) {
                Thread.currentThread().interrupt();
                assertThrows(InterruptedException.class,
                        () -> harness.manager.pauseAndDrainLaunches(Duration.ofSeconds(5)));
            } else {
                assertThrows(IllegalStateException.class,
                        () -> harness.manager.pauseAndDrainLaunches(Duration.ZERO));
            }
            assertThrows(IllegalStateException.class, harness.manager::resumeLaunchesAfterReset);
            assertThrows(IllegalStateException.class,
                    () -> harness.manager.launch(instance("i-reset-while-draining"), "ubuntu:24.04", null, "us-west-2"));
            assertFalse(executor.isShutdown());
            release.complete(null);
            harness.manager.pauseAndDrainLaunches(Duration.ofSeconds(5));
            assertEquals("terminated", running.getState().getName());
            assertThrows(IllegalStateException.class,
                    () -> harness.manager.launch(instance("i-reset-after-drain"), "ubuntu:24.04", null, "us-west-2"));
            harness.manager.resumeLaunchesAfterReset();
            stubDockerPing(harness.dockerClient, false);
            Instance next = instance("i-reset-next");
            harness.manager.launch(next, "ubuntu:24.04", null, "us-west-2");
            assertEquals("running", next.getState().getName());
        } finally {
            Thread.interrupted();
            release.complete(null);
            harness.manager.stop();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectedLaunchDoesNotRetainActiveWork() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-rejected-launch-cleanup");
        try {
            executor.shutdown();
            harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");
            harness.manager.terminateForShutdown(instance);
            assertEquals("terminated", instance.getState().getName());
        } finally {
            harness.manager.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void containerlessLaunchDoesNotRetainActiveWork() {
        LaunchHarness harness = launchHarness();
        Instance instance = instance("i-containerless-launch-cleanup");
        stubDockerPing(harness.dockerClient, false);
        try {
            harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");
            harness.manager.terminateForShutdown(instance);
            assertEquals("terminated", instance.getState().getName());
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void completedShutdownCannotBeFollowedByAnotherLaunch() {
        LaunchHarness harness = launchHarness();
        Instance instance = instance("i-shutdown-no-relaunch");
        stubDockerPing(harness.dockerClient, false);
        try {
            harness.manager.terminateForShutdown(instance);
            harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");
            assertEquals("terminated", instance.getState().getName());
            verify(harness.lifecycleManager, never()).create(any(ContainerSpec.class));
        } finally {
            harness.manager.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shutdownTerminationChecksPhysicalCompletionAndRetainsCapturedReleases(boolean refuseWorkload)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-shutdown-physical");
        instance.setOwnerId("111122223333");
        instance.setRegion("us-west-2");
        instance.setSubnetId("subnet-original");
        instance.setPrivateIpAddress("10.0.1.10");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setSshHostPort(2201);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(response);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        AtomicBoolean refuse = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (refuseWorkload && refuse.get()) {
                throw new IllegalStateException("workload busy");
            }
            return null;
        }).when(remove).exec();
        doAnswer(invocation -> {
            if (!refuseWorkload && refuse.get()) {
                throw new IllegalStateException("address release unavailable");
            }
            return null;
        }).when(harness.vpcNetworkManager).releasePrivateIp(
                "111122223333", "us-west-2", "subnet-original", "10.0.1.10");
        try {
            executor.shutdown();
            assertThrows(IllegalStateException.class, () -> harness.manager.terminateForShutdown(instance));
            assertEquals("terminated", instance.getState().getName());
            verify(harness.portAllocator, times(refuseWorkload ? 0 : 1)).release(2201);
            instance.setOwnerId("444455556666");
            instance.setRegion("eu-west-1");
            instance.setSubnetId("subnet-replacement");
            instance.setPrivateIpAddress("10.0.2.20");
            refuse.set(false);
            harness.manager.terminateForShutdown(instance);
            verify(harness.portAllocator, times(1)).release(2201);
            verify(harness.metadataServer, times(1)).unregisterInstance(instance);
            verify(remove, times(refuseWorkload ? 2 : 1)).exec();
            verify(harness.vpcNetworkManager, never()).releasePrivateIp(
                    "444455556666", "eu-west-1", "subnet-replacement", "10.0.2.20");
        } finally {
            refuse.set(false);
            harness.manager.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void shutdownTerminationReusesQueuedCallbackAfterExecutorStops() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> blocker = executor.submit(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
        });
        Instance instance = instance("i-shutdown-callback");
        AtomicInteger callbacks = new AtomicInteger();
        try {
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            harness.manager.terminate(instance, callbacks::incrementAndGet);
            executor.shutdownNow();
            blocker.get(5, TimeUnit.SECONDS);
            harness.manager.terminateForShutdown(instance);
            assertEquals("terminated", instance.getState().getName());
            assertEquals(1, callbacks.get());
            verify(harness.metadataServer, times(1)).unregisterInstance(instance);
        } finally {
            release.countDown();
            harness.manager.stop();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownTerminationDoesNotRaceAnActiveRemoval(boolean blockCallback) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-shutdown-active");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(response);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        AtomicReference<Thread> removalThread = new AtomicReference<>();
        AtomicReference<Thread> shutdownThread = new AtomicReference<>();
        CountDownLatch removing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (!blockCallback) {
                removalThread.set(Thread.currentThread());
                removing.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return null;
        }).when(remove).exec();
        AtomicInteger callbacks = new AtomicInteger();
        try {
            harness.manager.terminate(instance, () -> {
                if (blockCallback) {
                    removing.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
                callbacks.incrementAndGet();
            });
            assertTrue(removing.await(5, TimeUnit.SECONDS));
            Future<?> shutdown = stopper.submit(() -> {
                shutdownThread.set(Thread.currentThread());
                harness.manager.terminateForShutdown(instance);
            });
            if (blockCallback) {
                // Physical cleanup is complete; the external callback must remain outside its monitor.
                shutdown.get(5, TimeUnit.SECONDS);
            } else {
                LockWaits.awaitBlockedBehind(shutdownThread, removalThread, shutdown);
            }
            verify(remove, times(1)).exec();
            assertEquals(0, callbacks.get());
            release.countDown();
            shutdown.get(5, TimeUnit.SECONDS);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertEquals(1, callbacks.get());
            verify(remove, times(1)).exec();
        } finally {
            release.countDown();
            harness.manager.stop();
            executor.shutdownNow();
            stopper.shutdownNow();
            assertTrue(stopper.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void terminationRetriesPhysicalRemovalAfterApiStateIsTerminal() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-terminal-retry");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(response);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        AtomicBoolean refuse = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (refuse.get()) {
                throw new IllegalStateException("workload busy");
            }
            return null;
        }).when(remove).exec();
        CountDownLatch completed = new CountDownLatch(1);
        try {
            harness.manager.terminate(instance, completed::countDown);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertEquals("terminated", instance.getState().getName());
            assertEquals(1, completed.getCount());
            refuse.set(false);
            harness.time().advance(Duration.ofSeconds(1));
            assertTrue(completed.await(5, TimeUnit.SECONDS), "physical cleanup must retry a terminal instance");
            verify(harness.metadataServer, times(1)).unregisterInstance(instance);
        } finally {
            harness.manager.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentTerminationRetriesClaimTheSamePhysicalCleanup() throws Exception {
        CountDownLatch firstTaskCompleted = new CountDownLatch(1);
        ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2)) {
            @Override
            protected void afterExecute(Runnable task, Throwable failure) {
                super.afterExecute(task, failure);
                firstTaskCompleted.countDown();
            }
        };
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-singleflight-termination");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setSshHostPort(2201);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(response);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        AtomicBoolean refuse = new AtomicBoolean(true);
        CountDownLatch retryEntered = new CountDownLatch(1);
        CountDownLatch releaseRetry = new CountDownLatch(1);
        CountDownLatch terminalSaved = new CountDownLatch(1);
        harness.manager.setPersister(value -> terminalSaved.countDown());
        doAnswer(invocation -> {
            if (refuse.get()) {
                throw new IllegalStateException("workload busy");
            }
            retryEntered.countDown();
            assertTrue(releaseRetry.await(5, TimeUnit.SECONDS));
            return null;
        }).when(remove).exec();
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch cleanupCompleted = new CountDownLatch(1);
        try {
            harness.manager.terminate(instance, () -> {
                callbacks.incrementAndGet();
                cleanupCompleted.countDown();
            });
            assertTrue(terminalSaved.await(5, TimeUnit.SECONDS));
            assertEquals("terminated", instance.getState().getName());
            assertTrue(firstTaskCompleted.await(5, TimeUnit.SECONDS));
            refuse.set(false);
            harness.manager.terminate(instance, callbacks::incrementAndGet);
            assertTrue(retryEntered.await(5, TimeUnit.SECONDS));
            harness.manager.terminate(instance, callbacks::incrementAndGet);
            releaseRetry.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            assertTrue(cleanupCompleted.await(5, TimeUnit.SECONDS));
            verify(remove, times(2)).exec();
            verify(harness.portAllocator, times(1)).release(2201);
            verify(harness.metadataServer, times(1)).unregisterInstance(instance);
            assertEquals(1, callbacks.get());
        } finally {
            releaseRetry.countDown();
            harness.manager.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void physicalRetryDoesNotRepublishAnExpiredTerminalRecord() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-pruned-terminal-retry");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(response);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        AtomicBoolean refuse = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (refuse.get()) {
                throw new IllegalStateException("workload busy");
            }
            return null;
        }).when(remove).exec();
        AtomicReference<Instance> stored = new AtomicReference<>();
        harness.manager.setPersister(stored::set);
        AtomicInteger callbacks = new AtomicInteger();
        try {
            harness.manager.terminate(instance, callbacks::incrementAndGet);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertEquals("terminated", stored.get().getState().getName());
            stored.set(null);
            refuse.set(false);
            harness.manager.terminate(instance, callbacks::incrementAndGet);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

            assertEquals(1, callbacks.get());
            assertNull(stored.get(), "physical cleanup must not recreate the pruned API record");
        } finally {
            harness.manager.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void shutdownDrainsTerminationDiscardedFromTheLaunchQueue() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        executor.execute(() -> {
            occupied.countDown();
            try {
                hold.await();
            } catch (InterruptedException expected) {
                // Shutdown interrupts this unrelated worker before draining queued cleanup.
                Thread.currentThread().interrupt();
            }
        });
        Instance instance = instance("i-shutdown-queued");
        AtomicInteger callbacks = new AtomicInteger();
        try {
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            harness.manager.terminate(instance, callbacks::incrementAndGet);
            harness.manager.stop(Duration.ofSeconds(2));

            assertEquals("terminated", instance.getState().getName());
            assertEquals(1, callbacks.get());
            verify(harness.metadataServer, times(1)).unregisterInstance(instance);
            harness.manager.stop(Duration.ofMillis(50));
            assertEquals(1, callbacks.get());
        } finally {
            hold.countDown();
            harness.manager.stop(Duration.ofMillis(50));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void duplicateTerminationDoesNotBlockAWorkerBehindActiveRemoval() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-nonblocking-termination");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(response);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(remove).exec();
        AtomicInteger callbacks = new AtomicInteger();
        try {
            harness.manager.terminate(instance, callbacks::incrementAndGet);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            harness.manager.terminate(instance, callbacks::incrementAndGet);
            executor.submit(() -> { }).get(1, TimeUnit.SECONDS);
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            verify(remove, times(1)).exec();
            assertEquals(1, callbacks.get());
        } finally {
            release.countDown();
            harness.manager.stop(Duration.ofSeconds(2));
            executor.shutdownNow();
        }
    }

    @Test
    void shutdownDeadlineDoesNotWaitForAnUninterruptibleRemoval() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService shutdown = Executors.newSingleThreadExecutor();
        LaunchHarness harness = launchHarness(executor, Duration.ofSeconds(1));
        Instance instance = instance("i-shutdown-stalled");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(response);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(harness.dockerClient.removeContainerCmd(TEST_CONTAINER_ID)).thenReturn(remove);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            boolean finished = false;
            while (!finished) {
                try {
                    finished = release.await(5, TimeUnit.SECONDS);
                    assertTrue(finished, "test must release the stalled removal");
                } catch (InterruptedException expected) {
                    // Model a Docker call that does not complete when shutdown interrupts it.
                }
            }
            return null;
        }).when(remove).exec();
        AtomicInteger callbacks = new AtomicInteger();
        try {
            harness.manager.terminate(instance, callbacks::incrementAndGet);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            shutdown.submit(() -> harness.manager.stop(Duration.ofMillis(50))).get(2, TimeUnit.SECONDS);
            assertEquals(0, callbacks.get());
            release.countDown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            verify(remove, times(1)).exec();
            assertEquals(1, callbacks.get());
        } finally {
            release.countDown();
            harness.manager.stop(Duration.ofMillis(50));
            shutdown.shutdownNow();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void theTeardownHookRunsOnceTheContainerIsGone() throws Exception {
        // The hook exists so callers can do work that is only valid after the container has
        // released its Docker resources, so it must actually fire on the ordinary path.
        LaunchHarness harness = launchHarness();
        stubDockerPing(harness.dockerClient(), false);
        Instance instance = instance("i-teardown-hook");
        harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");
        AtomicBoolean ran = new AtomicBoolean(false);

        harness.manager().terminate(instance, () -> ran.set(true));

        awaitUntil(ran::get, Duration.ofSeconds(2));
    }

    @Test
    void theTeardownHookIsSkippedWhenTheContainerCouldNotBeRemoved() throws Exception {
        // API termination must not run a callback whose Docker resource is still in use.
        LaunchHarness harness = launchHarness();
        stubDockerPing(harness.dockerClient(), false);
        Instance instance = instance("i-teardown-hook-refused");
        harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");
        instance.setDockerContainerId("c-stuck");
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse inspected = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(inspected);
        when(harness.dockerClient().inspectContainerCmd("c-stuck")).thenReturn(inspect);
        when(harness.dockerClient().removeContainerCmd("c-stuck"))
                .thenThrow(new RuntimeException("daemon refused"));
        AtomicBoolean ran = new AtomicBoolean(false);

        try {
            harness.manager().terminate(instance, () -> ran.set(true));
            awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
            assertFalse(ran.get(), "the hook must not run when the container is still present");
        } finally {
            harness.manager().stop();
        }
    }

    private static LaunchHarness metadataLaunchHarness(ExecutorService executor, Duration timeout) throws Exception {
        LaunchHarness harness = launchHarness(executor, timeout);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = inspectResponse("172.18.0.12");
        when(inspect.exec()).thenReturn(response);
        when(harness.dockerClient.inspectContainerCmd(TEST_HELPER_ID)).thenReturn(inspect);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        ExecCreateCmd guestExec = harness.dockerClient.execCreateCmd(TEST_CONTAINER_ID);
        when(harness.dockerClient.execCreateCmd(TEST_HELPER_ID)).thenReturn(guestExec);
        return harness;
    }

    private static final class PausedLookup {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private Thread lookup;

        static PausedLookup onInspect(DockerClient docker, String containerId,
                                      InspectContainerResponse first, InspectContainerResponse later) {
            PausedLookup paused = new PausedLookup();
            AtomicBoolean firstInspect = new AtomicBoolean(true);
            when(docker.inspectContainerCmd(containerId).exec()).thenAnswer(invocation -> {
                if (firstInspect.getAndSet(false)) {
                    paused.started.countDown();
                    paused.release.await(5, TimeUnit.SECONDS);
                    return first;
                }
                return later;
            });
            return paused;
        }

        /** Starts the lookup and returns once it is held inside the inspect. */
        void start(Ec2ContainerManager manager, Instance instance) throws InterruptedException {
            lookup = Thread.ofVirtual().start(() -> manager.refreshServiceNetworkIp(instance));
            assertTrue(started.await(5, TimeUnit.SECONDS));
        }

        /** Lets the held inspect answer and waits for the lookup to finish. */
        void finish() throws InterruptedException {
            release.countDown();
            lookup.join(Duration.ofSeconds(5));
            assertFalse(lookup.isAlive());
        }
    }
}
