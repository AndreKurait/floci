package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ContainerNetworkSettings;
import com.github.dockerjava.api.model.Info;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.DnsClientVpcSource;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SecurityGroupFirewallManagerTest {

    @Test
    void overlappingVpcDnsOriginsUseTheirControlAddressAndLiveVpcAttachment() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().vpcNetworks().isolateAddressSpaces()).thenReturn(true);
        when(config.port()).thenReturn(4650);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        SecurityGroupFirewallManager manager = spy(new SecurityGroupFirewallManager(docker,
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), config,
                mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());
        String[] accounts = {"111111111111", "222222222222"};
        Container[] observed = new Container[2];
        for (int index = 0; index < accounts.length; index++) {
            String helper = "helper-" + index;
            String control = "172.18.0." + (index + 2);
            String network = VpcNetworkManager.nativeNetworkName(config, accounts[index],
                    "us-east-1", "vpc-default");
            observed[index] = dnsHelper(helper, "running", control);
            Map<String, ContainerNetwork> networks = new HashMap<>(
                    observed[index].getNetworkSettings().getNetworks());
            networks.put(network, new ContainerNetwork());
            when(observed[index].getNetworkSettings().getNetworks()).thenReturn(networks);
            when(docker.inspectContainerCmd(helper).exec().getNetworkSettings().getNetworks()).thenReturn(networks);
            manager.register(new SecurityGroupNftCompiler.Endpoint(accounts[index], "us-east-1", "vpc-default",
                    "eni-" + index, "10.0.1.10", control, Set.of(), List.of(), Set.of("10.0.1.10")),
                    helper, Map.of());
        }
        when(docker.listContainersCmd().withShowAll(true).withIdFilter(any(List.class)).exec())
                .thenReturn(List.of(observed));
        manager.retireDnsOrigins();
        assertTrue(manager.vpcForClient("10.0.1.10").isEmpty(), "repeated private addresses cannot identify DNS callers");
        for (int index = 0; index < accounts.length; index++) {
            assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc(accounts[index], "us-east-1", "vpc-default")),
                    manager.vpcForClient("172.18.0." + (index + 2)));
        }
        assertThrows(IllegalStateException.class, () -> manager.register(
                new SecurityGroupNftCompiler.Endpoint(accounts[0], "us-east-1", "vpc-default",
                        "eni-duplicate", "10.0.1.10", "172.18.0.4", Set.of(), List.of(), Set.of("10.0.1.10")),
                "duplicate", Map.of()));
        assertThrows(IllegalStateException.class, () -> manager.register(
                new SecurityGroupNftCompiler.Endpoint("333333333333", "us-east-1", "vpc-other",
                        "eni-control", "10.0.2.10", "172.18.0.2", Set.of(), List.of(), Set.of("10.0.2.10")),
                "collision", Map.of()));

        Container detached = dnsHelper("helper-0", "running", "172.18.0.2");
        when(docker.listContainersCmd().withShowAll(true).withIdFilter(any(List.class)).exec())
                .thenReturn(List.of(detached, observed[1]));
        manager.retireDnsOrigins();
        assertTrue(manager.vpcForClient("172.18.0.2").isEmpty(), "a detached VPC must lose its private DNS identity");
        assertTrue(manager.vpcForClient("172.18.0.3").isPresent(), "the other account keeps its DNS identity");
    }

    @ParameterizedTest
    @ValueSource(strings = {"172.18.0.2", "10.0.1.10"})
    void losingEitherInterfaceWithdrawsBothDnsOriginsBeforeWaitingForTheFirewall(String survivingAddress)
            throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listing);
        Container observed = dnsHelper("helper-a", "running", survivingAddress);
        when(listing.exec()).thenReturn(List.of(observed));
        SecurityGroupFirewallManager manager = dnsManager(dockerClient);
        manager.register(new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", "vpc-a",
                "eni-a", "10.0.1.10", "172.18.0.2", Set.of(), List.of(), Set.of("10.0.1.10")),
                "helper-a", Map.of());
        assertTrue(manager.vpcForClient("172.18.0.2").isPresent());
        assertTrue(manager.vpcForClient("10.0.1.10").isPresent());

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> retirement;
            synchronized (manager) {
                retirement = executor.submit(manager::retireDnsOrigins);
                await().atMost(2, TimeUnit.SECONDS).until(() -> manager.vpcForClient("172.18.0.2").isEmpty()
                        && manager.vpcForClient("10.0.1.10").isEmpty());
            }
            retirement.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void aNewPrimaryAddressCannotTakeAnExistingVpcInterfaceIdentity() {
        SecurityGroupFirewallManager manager = dnsManager(mock(DockerClient.class));
        manager.register(new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", "vpc-a",
                "eni-a", "10.0.1.10", "172.18.0.2", Set.of(), List.of(), Set.of("10.0.1.10")),
                "helper-a", Map.of());

        assertThrows(IllegalStateException.class, () -> registerOrigin(
                manager, "eni-b", "helper-b", "10.0.1.10", "vpc-b"));
        assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-a")),
                manager.vpcForClient("10.0.1.10"));
        assertTrue(manager.vpcForClient("172.18.0.2").isPresent());
    }

    @Test
    void dnsLookupDoesNotWaitForTheFirewallMonitor() throws Exception {
        SecurityGroupFirewallManager manager = dnsManager(mock(DockerClient.class));
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            synchronized (manager) {
                assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-a")),
                        executor.submit(() -> manager.vpcForClient("172.18.0.2")).get(2, TimeUnit.SECONDS),
                        "DNS must not wait for the monitor held during Docker nft execution");
            }
        }
    }

    @Test
    void dnsRetirementObservesHelperStateAndItsActualTransportAddress() {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listing);
        SecurityGroupFirewallManager manager = dnsManager(dockerClient);
        registerOrigin(manager, "eni-live", "helper-live", "172.18.0.2", "vpc-live");
        registerOrigin(manager, "eni-stopped", "helper-stopped", "172.18.0.3", "vpc-stopped");
        registerOrigin(manager, "eni-removed", "helper-removed", "172.18.0.4", "vpc-removed");
        registerOrigin(manager, "eni-detached", "helper-detached", "172.18.0.5", "vpc-detached");
        List<Container> observed = List.of(
                dnsHelper("helper-live", "running", "172.18.0.2"),
                dnsHelper("helper-stopped", "exited", "172.18.0.3"),
                dnsHelper("helper-detached", "running", "172.19.0.5"),
                dnsHelper("unmanaged-replacement", "running", "172.18.0.4"));
        when(listing.exec()).thenReturn(observed);
        clearInvocations(manager);

        manager.retireDnsOrigins();

        verify(manager).reconcileAll();
        assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-live")),
                manager.vpcForClient("172.18.0.2"));
        assertTrue(manager.vpcForClient("172.18.0.3").isEmpty(), "a stopped helper no longer owns queries");
        assertTrue(manager.vpcForClient("172.18.0.4").isEmpty(), "IP reuse does not inherit another VPC");
        assertTrue(manager.vpcForClient("172.18.0.5").isEmpty(), "a detached address no longer belongs to the helper");
        assertTrue(manager.vpcForClient("172.19.0.5").isEmpty(), "Docker changes cannot admit a new origin");
        registerOrigin(manager, "eni-replacement", "helper-replacement", "172.18.0.4", "vpc-replacement");
        assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1",
                "vpc-replacement")), manager.vpcForClient("172.18.0.4"),
                "the retired endpoint must not prevent a newly admitted ENI from using the address");
    }

    @Test
    void dnsRetirementCannotRemoveAConcurrentReplacement() {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listing);
        SecurityGroupFirewallManager manager = dnsManager(dockerClient);
        registerOrigin(manager, "eni-old", "helper-old", "172.18.0.2", "vpc-old");
        when(listing.exec()).thenAnswer(ignored -> {
            manager.unregister("eni-old");
            registerOrigin(manager, "eni-new", "helper-new", "172.18.0.2", "vpc-new");
            return List.of();
        });

        manager.retireDnsOrigins();

        assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-new")),
                manager.vpcForClient("172.18.0.2"));
    }

    @Test
    void dnsRetirementRemainsVisibleWhilePeerCleanupWaitsForTheFirewallMonitor() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listing);
        when(listing.exec()).thenReturn(List.of());
        SecurityGroupFirewallManager manager = dnsManager(dockerClient);
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> retirement;
            synchronized (manager) {
                retirement = executor.submit(manager::retireDnsOrigins);
                await().atMost(2, TimeUnit.SECONDS).until(() -> manager.vpcForClient("172.18.0.2").isEmpty());
                assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
            }
            retirement.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void retirementCannotRemoveAConcurrentRegistrationWithTheSameIdentity() {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listing);
        SecurityGroupFirewallManager manager = dnsManager(dockerClient);
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        when(listing.exec()).thenAnswer(ignored -> {
            registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
            return List.of();
        });
        clearInvocations(manager);

        manager.retireDnsOrigins();

        assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-a")),
                manager.vpcForClient("172.18.0.2"));
        verify(manager).admit("eni-a", true);
    }

    @Test
    void retirementRetriesFailedPeerPoliciesAfterTheLastDnsOriginIsGone() {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listing);
        when(listing.exec()).thenReturn(List.of());
        SecurityGroupFirewallManager manager = dnsManager(dockerClient);
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        clearInvocations(manager);
        doThrow(new IllegalStateException("nft installation failed")).doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());

        assertThrows(IllegalStateException.class, manager::retireDnsOrigins);
        assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
        clearInvocations(listing);
        manager.retireDnsOrigins();
        manager.retireDnsOrigins();

        verify(manager, times(2)).reconcileAll();
        verifyNoInteractions(listing);
    }

    @Test
    void unregisterRetiresDnsBeforeDockerRemovalCompletes() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = spy(new SecurityGroupFirewallManager(mock(DockerClient.class),
                mock(ContainerBuilder.class), lifecycle, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        doAnswer(ignored -> {
            assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
            return null;
        }).when(lifecycle).removeIfExistsStrict("helper-a");

        manager.unregister("eni-a");

        verify(lifecycle).removeIfExistsStrict("helper-a");
    }

    @Test
    void retiredPeerCleanupRetriesAfterDockerRemovalFails() {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listing);
        when(listing.exec()).thenReturn(List.of());
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = spy(new SecurityGroupFirewallManager(dockerClient,
                mock(ContainerBuilder.class), lifecycle, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        manager.retireDnsOrigins();
        assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
        doThrow(new IllegalStateException("Docker removal failed")).doNothing()
                .when(lifecycle).removeIfExistsStrict("helper-a");

        assertThrows(IllegalStateException.class, () -> manager.unregister("eni-a"));
        manager.unregister("eni-a");
        manager.unregister("eni-a");

        verify(lifecycle, times(2)).removeIfExistsStrict("helper-a");
    }

    @Test
    void registrationTransfersRetiredHelperCleanupToItsNewOwner() {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listing);
        when(listing.exec()).thenReturn(List.of());
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = spy(new SecurityGroupFirewallManager(dockerClient,
                mock(ContainerBuilder.class), lifecycle, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());
        registerOrigin(manager, "eni-old", "helper-a", "172.18.0.2", "vpc-old");
        manager.retireDnsOrigins();
        registerOrigin(manager, "eni-new", "helper-a", "172.18.0.2", "vpc-new");

        manager.unregister("eni-old");

        verify(lifecycle, never()).removeIfExistsStrict("helper-a");
        assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-new")),
                manager.vpcForClient("172.18.0.2"));
        manager.unregister("eni-new");
        verify(lifecycle).removeIfExistsStrict("helper-a");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedCleanupPreservesAReplacementRegistration(boolean reuseHelper) {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = cleanupManager(mock(DockerClient.class, RETURNS_DEEP_STUBS), lifecycle);
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        Runnable cleanup = manager.captureUnregister("eni-a");
        registerOrigin(manager, "eni-a", reuseHelper ? "helper-a" : "helper-b", "172.18.0.3", "vpc-b");

        cleanup.run();
        cleanup.run();

        assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-b")),
                manager.vpcForClient("172.18.0.3"));
        verify(lifecycle, times(reuseHelper ? 0 : 1)).removeIfExistsStrict("helper-a");
        verify(lifecycle, never()).removeIfExistsStrict("helper-b");
    }

    @Test
    void strictRemovalIncludesRetiredHelpersAndRetainsFailedCleanup() {
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = cleanupManager(dockerClient, lifecycle);
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        manager.retireDnsOrigins();
        doThrow(new IllegalStateException("removal failed")).doNothing()
                .when(lifecycle).removeIfExistsStrict("helper-a");

        assertThrows(IllegalStateException.class, () -> manager.removeNamespaceStrict("eni-a"));
        manager.removeNamespaceStrict("eni-a");
        manager.removeNamespaceStrict("eni-a");

        verify(lifecycle, times(2)).removeIfExistsStrict("helper-a");
    }

    @Test
    void observerRetriesCleanupAfterOwningServiceUnregisterFails() {
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = cleanupManager(dockerClient, lifecycle);
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        doThrow(new IllegalStateException("removal failed")).doNothing()
                .when(lifecycle).removeIfExistsStrict("helper-a");

        assertThrows(IllegalStateException.class, () -> manager.unregister("eni-a"));
        manager.retireDnsOrigins();
        manager.retireDnsOrigins();

        verify(lifecycle, times(2)).removeIfExistsStrict("helper-a");
    }

    @Test
    void observerRetainsOldHelperWhenReplacementWinsTheMonitor() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = cleanupManager(dockerClient, lifecycle);
        registerOrigin(manager, "eni-a", "helper-old", "172.18.0.2", "vpc-a");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> retirement;
            synchronized (manager) {
                retirement = executor.submit(manager::retireDnsOrigins);
                await().atMost(2, TimeUnit.SECONDS).until(() -> manager.vpcForClient("172.18.0.2").isEmpty());
                registerOrigin(manager, "eni-a", "helper-new", "172.18.0.3", "vpc-a");
            }
            retirement.get(2, TimeUnit.SECONDS);
        }
        manager.unregister("eni-a");

        verify(lifecycle).removeIfExistsStrict("helper-old");
        verify(lifecycle).removeIfExistsStrict("helper-new");
    }

    @Test
    void unregisterQuarantinesDepartingHelperBeforePeerFailure() {
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = cleanupManager(dockerClient, lifecycle);
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        doAnswer(ignored -> {
            verify(dockerClient).copyArchiveToContainerCmd("helper-a");
            throw new IllegalStateException("peer failure");
        }).when(manager).reconcileAll();

        assertThrows(IllegalStateException.class, () -> manager.unregister("eni-a"));
        assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
    }

    @Test
    void replacementRetainsOldHelperWithoutObserverInterleaving() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = cleanupManager(mock(DockerClient.class, RETURNS_DEEP_STUBS), lifecycle);
        registerOrigin(manager, "eni-a", "helper-old", "172.18.0.2", "vpc-a");
        registerOrigin(manager, "eni-a", "helper-new", "172.18.0.3", "vpc-a");

        manager.unregister("eni-a");

        verify(lifecycle).removeIfExistsStrict("helper-old");
        verify(lifecycle).removeIfExistsStrict("helper-new");
    }

    @Test
    void observerRetriesRequestedRemovalDespitePeerPolicyFailure() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = cleanupManager(mock(DockerClient.class, RETURNS_DEEP_STUBS), lifecycle);
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        doThrow(new IllegalStateException("peer failure")).when(manager).reconcileAll();
        assertThrows(IllegalStateException.class, () -> manager.unregister("eni-a"));

        assertThrows(IllegalStateException.class, manager::retireDnsOrigins);

        verify(lifecycle).removeIfExistsStrict("helper-a");
    }

    @Test
    void removalRejectsHelperReadmissionWithoutBlockingAnotherNamespace() throws Exception {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = cleanupManager(mock(DockerClient.class, RETURNS_DEEP_STUBS), lifecycle);
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        CountDownLatch removing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(ignored -> {
            removing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(lifecycle).removeIfExistsStrict("helper-a");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> removal = executor.submit(() -> manager.unregister("eni-a"));
            try {
                assertTrue(removing.await(5, TimeUnit.SECONDS));
                executor.submit(() -> registerOrigin(manager, "eni-b", "helper-b", "172.18.0.3", "vpc-b"))
                        .get(2, TimeUnit.SECONDS);
                executor.submit(() -> assertThrows(IllegalStateException.class,
                        () -> registerOrigin(manager, "eni-c", "helper-a", "172.18.0.4", "vpc-c")))
                        .get(2, TimeUnit.SECONDS);
                assertTrue(manager.vpcForClient("172.18.0.4").isEmpty());
            } finally {
                release.countDown();
                removal.get(5, TimeUnit.SECONDS);
            }
        }
        verify(lifecycle).removeIfExistsStrict("helper-a");
        assertTrue(manager.vpcForClient("172.18.0.3").isPresent());
    }

    @Test
    void concurrentStrictRemovalWaitsForTheExistingCleanup() throws Exception {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = cleanupManager(mock(DockerClient.class, RETURNS_DEEP_STUBS), lifecycle);
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        CountDownLatch removing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondCaller = new CountDownLatch(1);
        doAnswer(ignored -> {
            removing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(lifecycle).removeIfExistsStrict("helper-a");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> manager.removeNamespaceStrict("eni-a"));
            Future<?> second = null;
            try {
                assertTrue(removing.await(5, TimeUnit.SECONDS));
                second = executor.submit(() -> {
                    secondCaller.countDown();
                    manager.removeNamespaceStrict("eni-a");
                });
                assertTrue(secondCaller.await(5, TimeUnit.SECONDS));
                Future<?> waiting = second;
                assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
                first.get(5, TimeUnit.SECONDS);
                if (second != null) {
                    second.get(5, TimeUnit.SECONDS);
                }
            }
        }
        verify(lifecycle).removeIfExistsStrict("helper-a");
    }

    private static SecurityGroupFirewallManager cleanupManager(DockerClient dockerClient,
                                                               ContainerLifecycleManager lifecycle) {
        SecurityGroupFirewallManager manager = spy(new SecurityGroupFirewallManager(dockerClient,
                mock(ContainerBuilder.class), lifecycle, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());
        return manager;
    }

    @Test
    void emptyDnsRegistryDoesNotQueryDocker() {
        DockerClient dockerClient = mock(DockerClient.class);
        SecurityGroupFirewallManager manager = dnsManager(dockerClient);
        manager.retireDnsOrigins();
        assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
        verifyNoInteractions(dockerClient);
    }

    private static SecurityGroupFirewallManager dnsManager(DockerClient dockerClient) {
        return SecurityGroupDnsFixture.manager(dockerClient, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS));
    }

    private static void registerOrigin(SecurityGroupFirewallManager manager, String eni, String helper,
                                       String address, String vpc) {
        manager.register(new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", vpc,
                eni, "10.0.1.10", address, Set.of(), List.of()), helper, Map.of());
    }

    private static Container dnsHelper(String id, String state, String address) {
        Container helper = mock(Container.class);
        when(helper.getId()).thenReturn(id);
        when(helper.getState()).thenReturn(state);
        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn(address);
        ContainerNetworkSettings settings = mock(ContainerNetworkSettings.class);
        when(settings.getNetworks()).thenReturn(Map.of("transport", network));
        when(helper.getNetworkSettings()).thenReturn(settings);
        return helper;
    }

    @Test
    void dnsOriginsUseRegisteredTransportAddressesAndTheirCompleteVpcScope() {
        SecurityGroupFirewallManager manager = spy(new SecurityGroupFirewallManager(mock(DockerClient.class),
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());
        DnsClientVpcSource origins = assertInstanceOf(DnsClientVpcSource.class, manager);
        List<DnsClientVpcSource.ClientVpc> scopes = List.of(
                new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-a"),
                new DnsClientVpcSource.ClientVpc("222222222222", "us-east-1", "vpc-a"),
                new DnsClientVpcSource.ClientVpc("111111111111", "us-west-2", "vpc-a"),
                new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-b"));
        for (int index = 0; index < scopes.size(); index++) {
            DnsClientVpcSource.ClientVpc scope = scopes.get(index);
            String address = "172.18.0." + (index + 2);
            manager.register(new SecurityGroupNftCompiler.Endpoint(scope.accountId(), scope.region(), scope.vpcId(),
                    "eni-" + index, "10.0.1.10", address, Set.of(), List.of()), "helper-" + index, Map.of());
            assertEquals(Optional.of(scope), origins.vpcForClient(address));
        }
        assertTrue(origins.vpcForClient("10.0.1.10").isEmpty(), "logical addresses can repeat across VPCs");
        assertTrue(origins.vpcForClient("172.18.0.99").isEmpty());
        assertTrue(origins.vpcForClient(null).isEmpty());
        assertTrue(origins.vpcForClient(" ").isEmpty());
        manager.unregister("eni-0");
        assertTrue(origins.vpcForClient("172.18.0.2").isEmpty(), "retired namespaces no longer own DNS queries");
        DnsClientVpcSource.ClientVpc replacement = scopes.getLast();
        manager.register(new SecurityGroupNftCompiler.Endpoint(replacement.accountId(), replacement.region(),
                replacement.vpcId(), "eni-replacement", "10.0.1.10", "172.18.0.2", Set.of(), List.of()),
                "helper-replacement", Map.of());
        assertEquals(Optional.of(replacement), origins.vpcForClient("172.18.0.2"));
    }

    @Test
    void failedRegistrationRestoresThePreviousDnsScope() {
        SecurityGroupFirewallManager manager = dnsManager(mock(DockerClient.class));
        doNothing().when(manager).refreshPeers();
        registerOrigin(manager, "eni-0", "helper-eni-0", "172.18.0.2", "vpc-a");
        doThrow(new IllegalStateException("admission failed")).when(manager).admit(anyString(), anyBoolean());

        assertThrows(IllegalStateException.class,
                () -> registerOrigin(manager, "eni-0", "helper-eni-0", "172.18.0.3", "vpc-b"));

        assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-a")),
                manager.vpcForClient("172.18.0.2"));
        assertTrue(manager.vpcForClient("172.18.0.3").isEmpty());
    }

    @Test
    void reconciliationRemovesOriginsOfStoppedHelpers() {
        SecurityGroupFirewallManager manager = dnsManager(mock(DockerClient.class, RETURNS_DEEP_STUBS));
        registerOrigin(manager, "eni-0", "helper-eni-0", "172.18.0.2", "vpc-a");
        doCallRealMethod().when(manager).reconcileAll();

        manager.reconcileAll();

        assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
    }

    @Test
    void pendingReplacementDoesNotExposeUncommittedDnsOrigin() throws Exception {
        SecurityGroupFirewallManager manager = dnsManager(mock(DockerClient.class));
        registerOrigin(manager, "eni-0", "helper-eni-0", "172.18.0.2", "vpc-a");
        CountDownLatch admitting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            admitting.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(manager).admit(anyString(), anyBoolean());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> replacement = executor.submit(
                    () -> registerOrigin(manager, "eni-0", "helper-eni-0", "172.18.0.3", "vpc-b"));
            try {
                assertTrue(admitting.await(5, TimeUnit.SECONDS));
                Future<?> lookup = executor.submit(() -> {
                    assertTrue(manager.vpcForClient("172.18.0.3").isEmpty());
                    assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc(
                                    "111111111111", "us-east-1", "vpc-a")),
                            manager.vpcForClient("172.18.0.2"));
                });
                lookup.get(1, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                replacement.get(5, TimeUnit.SECONDS);
            }
        }
        assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
        assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-b")),
                manager.vpcForClient("172.18.0.3"));
    }

    @Test
    void helperCarriesTheResolverAndHostsItsWorkloadsUse() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.network().securityGroupEnforcement().helperImage()).thenReturn("example/network-helper:test");
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        Info linux = mock(Info.class);
        when(linux.getOsType()).thenReturn("linux");
        when(linux.getArchitecture()).thenReturn("amd64");
        when(dockerClient.infoCmd().exec()).thenReturn(linux);
        InspectImageResponse image = mock(InspectImageResponse.class);
        when(image.getOs()).thenReturn("linux");
        when(image.getArch()).thenReturn("amd64");
        when(dockerClient.inspectImageCmd(anyString()).exec()).thenReturn(image);
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.isLinuxHost()).thenReturn(true);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenThrow(new IllegalStateException("stop after the spec"));
        SecurityGroupFirewallManager manager = new SecurityGroupFirewallManager(dockerClient,
                new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer), lifecycleManager, config,
                embeddedDnsServer);

        assertThrows(IllegalStateException.class, () -> manager.createNamespace("ec2", "i-1", "000000000000",
                "us-east-1", Optional.empty(), Map.of(8080, 0), Map.of(), "127.0.0.1"));

        ArgumentCaptor<ContainerSpec> helper = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(helper.capture());
        assertEquals(Map.of(8080, 0), helper.getValue().portBindings());
        assertEquals(Map.of(8080, "127.0.0.1"), helper.getValue().portBindingHostIps());
        assertTrue(helper.getValue().dnsServers().contains("172.18.0.2"), "the helper uses Floci's DNS");
        assertTrue(helper.getValue().extraHosts().contains("host.docker.internal:host-gateway"),
                "the helper resolves host.docker.internal");
    }

    @Test
    void helperCarriesExactlyTheHostsItsServiceChooses() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.network().securityGroupEnforcement().helperImage()).thenReturn("example/network-helper:test");
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        Info linux = mock(Info.class);
        when(linux.getOsType()).thenReturn("linux");
        when(linux.getArchitecture()).thenReturn("amd64");
        when(dockerClient.infoCmd().exec()).thenReturn(linux);
        InspectImageResponse image = mock(InspectImageResponse.class);
        when(image.getOs()).thenReturn("linux");
        when(image.getArch()).thenReturn("amd64");
        when(dockerClient.inspectImageCmd(anyString()).exec()).thenReturn(image);
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.isLinuxHost()).thenReturn(true);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenThrow(new IllegalStateException("stop after the spec"));
        SecurityGroupFirewallManager manager = new SecurityGroupFirewallManager(dockerClient,
                new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer), lifecycleManager, config,
                embeddedDnsServer);

        assertThrows(IllegalStateException.class, () -> manager.createNamespace("ec2", "i-1", "000000000000",
                "us-east-1", Optional.empty(), Map.of(8080, 0), Map.of(), "127.0.0.1",
                List.of("db.internal:10.0.0.5")));

        ArgumentCaptor<ContainerSpec> helper = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(helper.capture());
        assertEquals(List.of("db.internal:10.0.0.5"), helper.getValue().extraHosts(),
                "workloads get only the entries their service passes, not host.docker.internal");
    }

    @Test
    void onlyFlocisDnsIsExemptFromTheSecurityGroups() {
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.dns().containerFallbackServers()).thenReturn(List.of("8.8.8.8", "8.8.4.4"));
        SecurityGroupFirewallManager manager = new SecurityGroupFirewallManager(mock(DockerClient.class),
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), config, embeddedDnsServer);

        assertEquals(List.of("172.18.0.2"), manager.vpcResolvers());

        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        assertEquals(List.of(), manager.vpcResolvers());
    }
    @Test
    void survivingEcsHelpersAreFoundUnderTheNewAndLegacyLabelsAndRemovedOnlyWhenTheirOwnersAgree() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of());
        ListContainersCmd byNewKeys = mock(ListContainersCmd.class);
        ListContainersCmd byLegacyKeys = mock(ListContainersCmd.class);
        when(listCmd.withLabelFilter(Map.of("io.floci.security-group.helper", "true"))).thenReturn(byNewKeys);
        when(listCmd.withLabelFilter(Map.of("floci.security-group-helper", "true"))).thenReturn(byLegacyKeys);
        Container newOnly = exitedEcsHelper("new-only",
                Map.of("io.floci.security-group.helper", "true", "io.floci.owner", "4566"));
        Container legacyOnly = exitedEcsHelper("legacy-only",
                Map.of("floci.security-group-helper", "true", "floci_owner_port", "4566"));
        Container both = exitedEcsHelper("both", Map.of(
                "io.floci.security-group.helper", "true", "io.floci.owner", "4566",
                "floci.security-group-helper", "true", "floci_owner_port", "4566"));
        Container disagreeing = exitedEcsHelper("disagreeing", Map.of(
                "io.floci.security-group.helper", "true", "io.floci.owner", "4566",
                "floci.security-group-helper", "true", "floci_owner_port", "4567"));
        when(byNewKeys.exec()).thenReturn(List.of(newOnly, both, disagreeing));
        when(byLegacyKeys.exec()).thenReturn(List.of(legacyOnly, both, disagreeing));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = new SecurityGroupFirewallManager(dockerClient,
                mock(ContainerBuilder.class), lifecycleManager, config, mock(EmbeddedDnsServer.class));

        manager.quarantineSurvivingNamespaces();

        verify(lifecycleManager).removeIfExists("new-only");
        verify(lifecycleManager).removeIfExists("legacy-only");
        verify(lifecycleManager).removeIfExists("both");
        verify(lifecycleManager, never()).removeIfExists("disagreeing");
    }

    private static Container exitedEcsHelper(String id, Map<String, String> ownerLabels) {
        Map<String, String> labels = new HashMap<>(ownerLabels);
        labels.put("io.floci.service", "ecs");
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getState()).thenReturn("exited");
        when(container.getLabels()).thenReturn(labels);
        return container;
    }
}
