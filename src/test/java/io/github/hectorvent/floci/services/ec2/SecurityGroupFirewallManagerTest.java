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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SecurityGroupFirewallManagerTest {

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

        manager.retireDnsOrigins();

        assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-live")),
                manager.vpcForClient("172.18.0.2"));
        assertTrue(manager.vpcForClient("172.18.0.3").isEmpty(), "a stopped helper no longer owns queries");
        assertTrue(manager.vpcForClient("172.18.0.4").isEmpty(), "IP reuse does not inherit another VPC");
        assertTrue(manager.vpcForClient("172.18.0.5").isEmpty(), "a detached address no longer belongs to the helper");
        assertTrue(manager.vpcForClient("172.19.0.5").isEmpty(), "Docker changes cannot admit a new origin");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dnsRetirementCannotRemoveAConcurrentReplacement(boolean reuseIdentity) {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listing);
        SecurityGroupFirewallManager manager = dnsManager(dockerClient);
        registerOrigin(manager, "eni-old", "helper-old", "172.18.0.2", "vpc-old");
        String replacement = reuseIdentity ? "old" : "new";
        when(listing.exec()).thenAnswer(ignored -> {
            manager.unregister("eni-old");
            registerOrigin(manager, "eni-" + replacement, "helper-" + replacement,
                    "172.18.0.2", "vpc-" + replacement);
            return List.of();
        });

        manager.retireDnsOrigins();

        assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc(
                        "111111111111", "us-east-1", "vpc-" + replacement)),
                manager.vpcForClient("172.18.0.2"));
    }

    @Test
    void dnsRetirementDoesNotWaitForTheFirewallMonitor() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listing);
        when(listing.exec()).thenReturn(List.of());
        SecurityGroupFirewallManager manager = dnsManager(dockerClient);
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            synchronized (manager) {
                executor.submit(manager::retireDnsOrigins).get(2, TimeUnit.SECONDS);
                assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
            }
        }
    }

    @Test
    void unregisterRetiresDnsBeforeDockerRemovalCompletes() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = spy(new SecurityGroupFirewallManager(mock(DockerClient.class),
                mock(ContainerBuilder.class), lifecycle, mock(EmulatorConfig.class), mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        registerOrigin(manager, "eni-a", "helper-a", "172.18.0.2", "vpc-a");
        doAnswer(ignored -> {
            assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
            return null;
        }).when(lifecycle).removeIfExists("helper-a");

        manager.unregister("eni-a");

        verify(lifecycle).removeIfExists("helper-a");
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
        SecurityGroupFirewallManager manager = spy(new SecurityGroupFirewallManager(dockerClient,
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), mock(EmulatorConfig.class),
                mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
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
        SecurityGroupFirewallManager manager = spy(new SecurityGroupFirewallManager(mock(DockerClient.class),
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), mock(EmulatorConfig.class),
                mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
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
    void dnsLookupDoesNotWaitForTheReconciliationMonitor() throws Exception {
        SecurityGroupFirewallManager manager = dnsManager(mock(ContainerLifecycleManager.class));
        registerDnsEndpoint(manager, "eni-0", "vpc-a", "172.18.0.2");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> holder = executor.submit(() -> {
                synchronized (manager) {
                    held.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
                return null;
            });
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                Future<Optional<DnsClientVpcSource.ClientVpc>> lookup = executor.submit(
                        () -> manager.vpcForClient("172.18.0.2"));
                assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-a")),
                        lookup.get(1, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                holder.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void retirementRemovesDnsOriginBeforeSlowHelperRemoval() throws Exception {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = dnsManager(lifecycle);
        registerDnsEndpoint(manager, "eni-0", "vpc-a", "172.18.0.2");
        CountDownLatch removing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            removing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(lifecycle).removeIfExists("helper-eni-0");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> retirement = executor.submit(() -> manager.unregister("eni-0"));
            try {
                assertTrue(removing.await(5, TimeUnit.SECONDS));
                Future<Optional<DnsClientVpcSource.ClientVpc>> lookup = executor.submit(
                        () -> manager.vpcForClient("172.18.0.2"));
                assertTrue(lookup.get(1, TimeUnit.SECONDS).isEmpty());
            } finally {
                release.countDown();
                retirement.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void failedRegistrationRestoresThePreviousDnsScope() {
        SecurityGroupFirewallManager manager = dnsManager(mock(ContainerLifecycleManager.class));
        registerDnsEndpoint(manager, "eni-0", "vpc-a", "172.18.0.2");
        doThrow(new IllegalStateException("reconciliation failed")).when(manager).reconcileAll();

        assertThrows(IllegalStateException.class,
                () -> registerDnsEndpoint(manager, "eni-0", "vpc-b", "172.18.0.3"));

        assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-a")),
                manager.vpcForClient("172.18.0.2"));
        assertTrue(manager.vpcForClient("172.18.0.3").isEmpty());
    }

    @Test
    void registerRejectsSecondEniOnHeldTransportAddress() {
        SecurityGroupFirewallManager manager = dnsManager(mock(ContainerLifecycleManager.class));
        registerDnsEndpoint(manager, "eni-0", "vpc-a", "172.18.0.2");

        assertThrows(IllegalStateException.class,
                () -> registerDnsEndpoint(manager, "eni-1", "vpc-b", "172.18.0.2"));

        assertEquals(Optional.of(new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-a")),
                manager.vpcForClient("172.18.0.2"));
    }

    @Test
    void reconciliationRemovesOriginsOfStoppedHelpers() {
        SecurityGroupFirewallManager manager = dnsManager(mock(ContainerLifecycleManager.class));
        registerDnsEndpoint(manager, "eni-0", "vpc-a", "172.18.0.2");
        doCallRealMethod().when(manager).reconcileAll();

        manager.reconcileAll();

        assertTrue(manager.vpcForClient("172.18.0.2").isEmpty());
    }

    @Test
    void pendingReplacementDoesNotExposeUncommittedDnsOrigin() throws Exception {
        SecurityGroupFirewallManager manager = dnsManager(mock(ContainerLifecycleManager.class));
        registerDnsEndpoint(manager, "eni-0", "vpc-a", "172.18.0.2");
        CountDownLatch reconciling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            reconciling.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(manager).reconcileAll();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> replacement = executor.submit(
                    () -> registerDnsEndpoint(manager, "eni-0", "vpc-b", "172.18.0.3"));
            try {
                assertTrue(reconciling.await(5, TimeUnit.SECONDS));
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

    private static SecurityGroupFirewallManager dnsManager(ContainerLifecycleManager lifecycle) {
        SecurityGroupFirewallManager manager = spy(new SecurityGroupFirewallManager(
                mock(DockerClient.class, RETURNS_DEEP_STUBS), mock(ContainerBuilder.class), lifecycle,
                mock(EmulatorConfig.class), mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        return manager;
    }

    private static void registerDnsEndpoint(SecurityGroupFirewallManager manager, String eniId,
                                            String vpcId, String address) {
        manager.register(new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", vpcId,
                eniId, "10.0.1.10", address, Set.of(), List.of()), "helper-" + eniId, Map.of());
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
