package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ContainerNetworkSettings;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.DnsClientVpcSource;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NativeSecurityGroupFirewallManagerTest {

    @Test
    void overlappingVpcDnsOriginsUseTheirControlAddressAndLiveVpcAttachment() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().vpcNetworks().isolateAddressSpaces()).thenReturn(true);
        when(config.port()).thenReturn(4650);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        SecurityGroupFirewallManager manager = spy(SecurityGroupDnsFixture.rawManager(SecurityGroupDnsFixture.policyDocker(docker),
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
                    "eni-" + index, "10.0.1.10", control, Set.of(), List.of(), List.of(), true, Set.of("10.0.1.10")),
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
                        "eni-duplicate", "10.0.1.10", "172.18.0.4", Set.of(), List.of(), List.of(), true, Set.of("10.0.1.10")),
                "duplicate", Map.of()));
        assertThrows(IllegalStateException.class, () -> manager.register(
                new SecurityGroupNftCompiler.Endpoint("333333333333", "us-east-1", "vpc-other",
                        "eni-control", "10.0.2.10", "172.18.0.2", Set.of(), List.of(), List.of(), true, Set.of("10.0.2.10")),
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
                "eni-a", "10.0.1.10", "172.18.0.2", Set.of(), List.of(), List.of(), true, Set.of("10.0.1.10")),
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
                "eni-a", "10.0.1.10", "172.18.0.2", Set.of(), List.of(), List.of(), true, Set.of("10.0.1.10")),
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
        SecurityGroupFirewallManager manager = spy(SecurityGroupDnsFixture.rawManager(SecurityGroupDnsFixture.policyDocker(mock(DockerClient.class)),
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
        SecurityGroupFirewallManager manager = spy(SecurityGroupDnsFixture.rawManager(SecurityGroupDnsFixture.policyDocker(dockerClient),
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
        SecurityGroupFirewallManager manager = spy(SecurityGroupDnsFixture.rawManager(SecurityGroupDnsFixture.policyDocker(dockerClient),
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

    @Test
    void emptyDnsRegistryDoesNotQueryDocker() {
        DockerClient dockerClient = mock(DockerClient.class);
        SecurityGroupFirewallManager manager = dnsManager(dockerClient);
        manager.retireDnsOrigins();
        assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
        verifyNoInteractions(dockerClient);
    }

    private static SecurityGroupFirewallManager dnsManager(DockerClient dockerClient) {
        SecurityGroupFirewallManager manager = spy(SecurityGroupDnsFixture.rawManager(SecurityGroupDnsFixture.policyDocker(dockerClient),
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());
        return manager;
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
        SecurityGroupFirewallManager manager = spy(SecurityGroupDnsFixture.rawManager(SecurityGroupDnsFixture.policyDocker(mock(DockerClient.class)),
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
    void compatibilityDnsOriginsRetainTheLiveIpv6ControlAddressAfterPolicyUpdate() {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        SecurityGroupFirewallManager manager = dnsManager(docker);
        manager.register(new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", "vpc-a",
                "eni-a", "10.0.1.10", List.of("2001:db8:1::10"), "172.18.0.2", "fd00::2",
                Set.of(), List.of()), "helper-a", Map.of());
        Container helper = dnsHelper("helper-a", "running", "172.18.0.2");
        when(helper.getNetworkSettings().getNetworks().get("transport").getGlobalIPv6Address())
                .thenReturn("fd00::2");
        when(docker.listContainersCmd().withShowAll(true).withIdFilter(any(List.class)).exec())
                .thenReturn(List.of(helper));
        manager.updateIpv6Addresses("eni-a", List.of("2001:db8:1::11"));
        manager.retireDnsOrigins();
        assertEquals(manager.vpcForClient("172.18.0.2"), manager.vpcForClient("fd00::2"));
        assertTrue(manager.vpcForClient("fd00::2").isPresent());
        when(helper.getNetworkSettings().getNetworks().get("transport").getGlobalIPv6Address())
                .thenReturn("");
        manager.retireDnsOrigins();
        assertTrue(manager.vpcForClient("fd00::2").isEmpty());
        assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
    }

    @Test
    void isolatedVpcsCannotShareAnIpv6ControlTransport() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().vpcNetworks().isolateAddressSpaces()).thenReturn(true);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        SecurityGroupFirewallManager manager = spy(SecurityGroupDnsFixture.rawManager(SecurityGroupDnsFixture.policyDocker(docker),
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), config,
                mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());
        String network = VpcNetworkManager.nativeNetworkName(config, "111111111111", "us-east-1", "vpc-a");
        when(docker.inspectContainerCmd("helper-a").exec().getNetworkSettings().getNetworks())
                .thenReturn(Map.of(network, new ContainerNetwork()));
        manager.register(new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", "vpc-a",
                "eni-a", "10.0.1.10", List.of("2001:db8:1::10"), "172.18.0.2", "fd00::2",
                Set.of(), List.of(), List.of(), true, Set.of("10.0.1.10", "2001:db8:1::10")),
                "helper-a", Map.of());
        assertThrows(IllegalStateException.class, () -> manager.register(
                new SecurityGroupNftCompiler.Endpoint("222222222222", "us-east-1", "vpc-b",
                        "eni-b", "10.0.1.10", List.of("2001:db8:1::10"), "172.18.0.3", "fd00::2",
                        Set.of(), List.of(), List.of(), true, Set.of("10.0.1.10", "2001:db8:1::10")),
                "helper-b", Map.of()));
        assertTrue(manager.vpcForClient("172.18.0.2").isPresent());
        assertTrue(manager.vpcForClient("172.18.0.3").isEmpty());
    }

    @Test
    void isolatedEndpointWithoutTheNativeAttachmentNeverPublishesDnsIdentity() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().vpcNetworks().isolateAddressSpaces()).thenReturn(true);
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        SecurityGroupFirewallManager manager = spy(SecurityGroupDnsFixture.rawManager(SecurityGroupDnsFixture.policyDocker(docker),
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), config,
                mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());
        when(docker.inspectContainerCmd("helper-a").exec().getNetworkSettings().getNetworks())
                .thenReturn(Map.of("transport", new ContainerNetwork()));
        assertThrows(IllegalStateException.class, () -> manager.register(
                new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", "vpc-a",
                        "eni-a", "10.0.1.10", "172.18.0.2", Set.of(), List.of(), List.of(), true,
                        Set.of("10.0.1.10")), "helper-a", Map.of()));
        assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
        assertTrue(manager.vpcForClient("10.0.1.10").isEmpty());
    }
}
