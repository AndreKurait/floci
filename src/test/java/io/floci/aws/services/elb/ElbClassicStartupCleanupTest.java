package io.floci.aws.services.elb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import io.floci.aws.services.elb.model.ClassicLoadBalancer;
import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElbClassicStartupCleanupTest {
    @TempDir
    Path directory;
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private Supplier<StorageFactory> factories;
    private AccountAwareStorageBackend<ElbClassicFrontendManager.Ownership> store;
    private static final String REGION = "us-east-1";
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
    private final ElbClassicFrontendManager manager;
    private final Supplier<ElbClassicFrontendManager> newManager;
    private final ClassicLoadBalancer loadBalancer = new ClassicLoadBalancer();
    private final IllegalStateException startupFailure = new IllegalStateException("Namespace startup failed");
    private final IllegalStateException cleanupFailure = new IllegalStateException("Interface removal failed");

    ElbClassicStartupCleanupTest() {
        when(config.defaultAccountId()).thenReturn("111111111111");
        when(config.services().elb().vpcFrontends()).thenReturn(true);
        when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
        when(firewall.enabled()).thenReturn(true);
        VpcNetworkManager networks = mock(VpcNetworkManager.class);
        when(networks.enabled()).thenReturn(true);
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec()).thenReturn(List.of());
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        StorageFactory factory = mock(StorageFactory.class);
        store = spy(AccountAwareStorageBackend.inMemory("111111111111"));
        doReturn(store).when(factory).create(eq("elb"), eq("elb-classic-frontends.json"), any());
        factories = () -> factory;
        newManager = () -> new ElbClassicFrontendManager(config, ec2, firewall, networks,
                mock(ContainerBuilder.class), lifecycle, new ObjectMapper(), mock(IamService.class),
                mock(AcmService.class), mock(VpcRoutingManager.class), factories.get());
        manager = newManager.get();
        loadBalancer.setAccountId("111111111111");
        loadBalancer.setRegion(REGION);
        loadBalancer.setLoadBalancerName("failed-start");
        loadBalancer.setScheme("internal");
        loadBalancer.getSubnets().add("subnet");
        loadBalancer.getSecurityGroups().add("sg");
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-pending");
        eni.setPrivateIpAddress("10.0.1.11");
        when(ec2.createServiceNetworkInterface(eq(REGION), eq("subnet"), anyString(), anyList(), anyString())).thenReturn(eni);
        when(ec2.findServiceNetworkInterfaces(eq(REGION), anyString())).thenReturn(List.of());
        when(firewall.createNamespace(anyString(), anyString(), anyString(), anyString(),
                any(), anyMap(), anyMap())).thenThrow(startupFailure);
        doThrow(cleanupFailure).doNothing().when(ec2).deleteNetworkInterface(REGION, "eni-pending");
    }

    @ParameterizedTest
    @ValueSource(strings = {"remove", "shutdown", "reconcile"})
    void failedStartupCleanupRemainsOwnedUntilRetry(String operation) {
        assertSame(startupFailure, assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer)));
        assertArrayEquals(new Throwable[]{cleanupFailure}, startupFailure.getSuppressed());
        assertTrue(manager.address(loadBalancer).isEmpty(), "Unpublished frontends must not serve traffic");
        clearInvocations(ec2);
        if ("reconcile".equals(operation)) {
            IllegalStateException nextStart = new IllegalStateException("Next allocation reached");
            when(ec2.createServiceNetworkInterface(eq(REGION), eq("subnet"), anyString(), anyList(), anyString())).thenAnswer(invocation -> {
                        verify(ec2).deleteNetworkInterface(REGION, "eni-pending");
                        throw nextStart;
                    });
            assertSame(nextStart, assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer)));
        } else if ("remove".equals(operation)) {
            manager.remove(loadBalancer);
        } else {
            manager.stopManagedContainers();
        }
        verify(ec2).deleteNetworkInterface(REGION, "eni-pending");
        clearInvocations(ec2);
        manager.stopManagedContainers();
        verifyNoInteractions(ec2);
    }

    @Test
    void repeatedCleanupFailureBlocksAnotherAllocationAndRemainsRetryable() {
        assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer));
        doThrow(cleanupFailure).doNothing().when(ec2).deleteNetworkInterface(REGION, "eni-pending");
        clearInvocations(ec2);
        assertSame(cleanupFailure, assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer)));
        verify(ec2, never()).createServiceNetworkInterface(anyString(), anyString(), anyString(), anyList(), anyString());
        manager.remove(loadBalancer);
        verify(ec2, times(2)).deleteNetworkInterface(REGION, "eni-pending");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingInterfaceIsRecoveredBeforeAllocationAfterManagerReplacement(boolean replaceManager) {
        assertSame(startupFailure, assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer)));
        assertArrayEquals(new Throwable[]{cleanupFailure}, startupFailure.getSuppressed());
        clearInvocations(ec2);
        IllegalStateException nextAllocation = new IllegalStateException("Next allocation reached");
        when(ec2.createServiceNetworkInterface(eq(REGION), eq("subnet"), anyString(), anyList(), anyString())).thenAnswer(invocation -> {
                    verify(ec2).deleteNetworkInterface(REGION, "eni-pending");
                    throw nextAllocation;
                });

        ElbClassicFrontendManager recovering = replaceManager ? newManager.get() : manager;
        assertSame(nextAllocation, assertThrows(IllegalStateException.class, () -> recovering.reconcile(loadBalancer)));
        verify(ec2).deleteNetworkInterface(REGION, "eni-pending");
    }

    @Test
    void pendingCleanupSurvivesFreshStorageFactoryWithoutGracefulFlush() {
        persistentFactories();
        ElbClassicFrontendManager original = newManager.get();
        assertSame(startupFailure, assertThrows(IllegalStateException.class, () -> original.reconcile(loadBalancer)));
        assertEquals(1, persistedOwnership().scanAllAccounts().size());
        clearInvocations(ec2);
        ElbClassicFrontendManager restarted = newManager.get();
        IllegalStateException nextAllocation = new IllegalStateException("new allocation stopped");
        when(ec2.createServiceNetworkInterface(eq(REGION), eq("subnet"), anyString(), anyList(), anyString())).thenAnswer(invocation -> {
                    verify(ec2).deleteNetworkInterface(REGION, "eni-pending");
                    throw nextAllocation;
                });
        assertSame(nextAllocation, assertThrows(IllegalStateException.class, () -> restarted.reconcile(loadBalancer)));
        assertTrue(persistedOwnership().scanAllAccounts().isEmpty());
    }

    @Test
    void failedOwnershipWritePreventsAllocation() {
        IllegalStateException failure = new IllegalStateException("ownership unavailable");
        doThrow(failure).when(store).putAllAtomicallyForAccount(anyString(), anyMap());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer)));
        verifyNoInteractions(ec2);
        verify(firewall, never()).createNamespace(anyString(), anyString(), anyString(), anyString(),
                any(), anyMap(), anyMap());
    }

    @Test
    void failedInterfaceIdentityWriteStillCompensatesAllocation() {
        IllegalStateException failure = new IllegalStateException("identity write failed");
        doCallRealMethod().doThrow(failure).when(store).putAllAtomicallyForAccount(anyString(), anyMap());
        doNothing().when(ec2).deleteNetworkInterface(REGION, "eni-pending");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer)));
        verify(ec2).deleteNetworkInterface(REGION, "eni-pending");
        verify(firewall, never()).createNamespace(anyString(), anyString(), anyString(), anyString(),
                any(), anyMap(), anyMap());
        assertTrue(store.scanAllAccounts().isEmpty());
    }

    @Test
    void allocationIntentFindsInterfaceBeforeIdentityWasRecorded() {
        persistentFactories();
        AccountAwareStorageBackend<ElbClassicFrontendManager.Ownership> persisted = persistedOwnership();
        ElbClassicFrontendManager.Ownership allocation = new ElbClassicFrontendManager.Ownership(
                "0", "111111111111", REGION, "111111111111/" + REGION + "/failed-start", "allocation-token", null);
        persisted.putAllAtomicallyForAccount(allocation.account(), Map.of(allocation.id(), allocation));
        NetworkInterface orphan = new NetworkInterface();
        orphan.setNetworkInterfaceId("eni-orphan");
        when(ec2.findServiceNetworkInterfaces(REGION, "allocation-token")).thenReturn(List.of(orphan));
        IllegalStateException nextAllocation = new IllegalStateException("new allocation stopped");
        when(ec2.createServiceNetworkInterface(eq(REGION), eq("subnet"), anyString(), anyList(), anyString())).thenAnswer(invocation -> {
                    verify(ec2).deleteNetworkInterface(REGION, "eni-orphan");
                    throw nextAllocation;
                });
        ElbClassicFrontendManager restarted = newManager.get();
        assertSame(nextAllocation, assertThrows(IllegalStateException.class, () -> restarted.reconcile(loadBalancer)));
        assertTrue(persistedOwnership().scanAllAccounts().isEmpty());
    }

    @Test
    void recoveryFailureRetainsOwnershipAndBlocksAllocation() {
        persistentFactories();
        assertSame(startupFailure, assertThrows(IllegalStateException.class,
                () -> newManager.get().reconcile(loadBalancer)));
        doThrow(cleanupFailure).when(ec2).deleteNetworkInterface(REGION, "eni-pending");
        clearInvocations(ec2);
        assertSame(cleanupFailure, assertThrows(IllegalStateException.class,
                () -> newManager.get().reconcile(loadBalancer)));
        verify(ec2, never()).createServiceNetworkInterface(anyString(), anyString(), anyString(), anyList(), anyString());
        assertEquals(1, persistedOwnership().scanAllAccounts().size());
    }

    @Test
    void otherRuntimeOwnershipIsNotRetired() {
        ElbClassicFrontendManager.Ownership other = new ElbClassicFrontendManager.Ownership(
                "another-runtime", "222222222222", REGION, "other", "other-token", "eni-other");
        store.putAllAtomicallyForAccount(other.account(), Map.of(other.id(), other));
        assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer));
        verify(ec2, never()).deleteNetworkInterface(REGION, "eni-other");
        assertEquals(other, store.getForAccount(other.account(), other.id()).orElseThrow());
    }

    @Test
    void startupRecoveryRunsWithoutALoadBalancerDeclaration() {
        persistentFactories();
        assertSame(startupFailure, assertThrows(IllegalStateException.class,
                () -> newManager.get().reconcile(loadBalancer)));
        clearInvocations(ec2);
        newManager.get().initialize();
        verify(ec2).deleteNetworkInterface(REGION, "eni-pending");
        verify(firewall).unregister("eni-pending");
        verify(ec2, never()).createServiceNetworkInterface(anyString(), anyString(), anyString(),
                anyList(), anyString());
        assertTrue(persistedOwnership().scanAllAccounts().isEmpty());
    }

    private void persistentFactories() {
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        ServiceConfigAccess services = mock(ServiceConfigAccess.class);
        when(services.storageMode("elb")).thenReturn("persistent");
        factories = () -> new StorageFactory(config, services);
    }

    private AccountAwareStorageBackend<ElbClassicFrontendManager.Ownership> persistedOwnership() {
        return factories.get().create("elb", "elb-classic-frontends.json", new TypeReference<>() {});
    }

    @Test
    void successfulStartupCompensationLeavesNothingToRetry() {
        doNothing().when(ec2).deleteNetworkInterface(REGION, "eni-pending");
        assertSame(startupFailure, assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer)));
        assertEquals(0, startupFailure.getSuppressed().length);
        clearInvocations(ec2);
        manager.remove(loadBalancer);
        manager.stopManagedContainers();
        verifyNoInteractions(ec2);
    }
}
