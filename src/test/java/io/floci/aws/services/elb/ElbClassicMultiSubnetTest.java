package io.floci.aws.services.elb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import io.floci.aws.services.elb.model.ClassicLoadBalancer;
import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElbClassicMultiSubnetTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
    private final VpcNetworkManager networks = mock(VpcNetworkManager.class);
    private final VpcRoutingManager routing = mock(VpcRoutingManager.class);
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final StorageFactory factory = mock(StorageFactory.class);
    private final AccountAwareStorageBackend<ElbClassicFrontendManager.Ownership> store =
            AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final ClassicLoadBalancer loadBalancer = new ClassicLoadBalancer();
    private final ElbClassicFrontendManager manager;
    private final AtomicInteger allocations = new AtomicInteger();

    ElbClassicMultiSubnetTest() {
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services().elb().vpcFrontends()).thenReturn(true);
        when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
        when(firewall.enabled()).thenReturn(true);
        when(networks.enabled()).thenReturn(true);
        when(networks.attach(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("network"));
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec()).thenReturn(List.of());
        when(docker.listContainersCmd().withShowAll(true).exec()).thenReturn(List.of());
        doReturn(store).when(factory).create(eq("elb"), eq("elb-classic-frontends.json"), any());
        SecurityGroup group = new SecurityGroup();
        group.setGroupId("sg");
        when(ec2.describeSecurityGroups(eq(REGION), anyList(), anyList(), anyMap())).thenReturn(List.of(group));
        when(ec2.createServiceNetworkInterface(eq(REGION), anyString(), anyString(), anyList(), anyString()))
                .thenAnswer(call -> {
                    int index = allocations.incrementAndGet();
                    NetworkInterface eni = new NetworkInterface();
                    eni.setNetworkInterfaceId("eni-" + index);
                    eni.setPrivateIpAddress("10.0." + index + ".10");
                    eni.setVpcId("vpc");
                    eni.setSubnetId(call.getArgument(1));
                    return eni;
                });
        when(firewall.createNamespace(anyString(), anyString(), anyString(), anyString(), any(), anyMap(), anyMap()))
                .thenAnswer(call -> {
                    Map<String, String> labels = call.getArgument(6);
                    String eni = labels.values().iterator().next();
                    String helper = "helper-" + eni;
                    when(docker.inspectContainerCmd(helper).exec().getName()).thenReturn(helper);
                    when(docker.inspectContainerCmd(helper).exec().getState().getRunning()).thenReturn(true);
                    return new SecurityGroupFirewallManager.Namespace(helper, "127.0.0.1", null);
                });
        loadBalancer.setAccountId(ACCOUNT);
        loadBalancer.setRegion(REGION);
        loadBalancer.setLoadBalancerName("multi");
        loadBalancer.setScheme("internal");
        loadBalancer.setSubnets(List.of("subnet-a", "subnet-b"));
        loadBalancer.setSecurityGroups(List.of("sg"));
        manager = newManager();
    }

    @Test
    void eachSubnetOwnsAnInterfaceAndUnchangedNodesAreReused() {
        assertTrue(manager.reconcile(loadBalancer));
        assertEquals(List.of("10.0.1.10", "10.0.2.10"), manager.addresses(loadBalancer));
        assertEquals(2, store.scanAllAccounts().size());
        verify(ec2).createServiceNetworkInterface(eq(REGION), eq("subnet-a"), anyString(), eq(List.of("sg")), anyString());
        verify(ec2).createServiceNetworkInterface(eq(REGION), eq("subnet-b"), anyString(), eq(List.of("sg")), anyString());
        assertFalse(manager.reconcile(loadBalancer));
        assertEquals(2, allocations.get());
        manager.remove(loadBalancer);
        assertTrue(manager.addresses(loadBalancer).isEmpty());
        assertTrue(store.scanAllAccounts().isEmpty());
        verify(ec2).deleteNetworkInterface(REGION, "eni-1");
        verify(ec2).deleteNetworkInterface(REGION, "eni-2");
    }

    @Test
    void laterSubnetFailureRollsBackEarlierNodeAndItsDurableOwnership() {
        IllegalStateException failure = failSecondAllocation();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer)));
        assertTrue(manager.addresses(loadBalancer).isEmpty());
        verify(lifecycle).removeIfExistsStrict("helper-eni-1");
        verify(ec2).deleteNetworkInterface(REGION, "eni-1");
        assertTrue(store.scanAllAccounts().isEmpty());
    }

    @Test
    void partialAllocationCleanupFailureIsRetainedAndBlocksNewAllocation() {
        IllegalStateException startup = failSecondAllocation();
        IllegalStateException cleanup = new IllegalStateException("cannot remove first node");
        doThrow(cleanup).when(ec2).deleteNetworkInterface(REGION, "eni-1");
        assertSame(startup, assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer)));
        assertArrayEquals(new Throwable[]{cleanup}, startup.getSuppressed());
        assertEquals("eni-1", store.scanAllAccounts().getFirst().eni());
        clearInvocations(ec2);
        assertSame(cleanup, assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer)));
        verify(ec2, never()).createServiceNetworkInterface(anyString(), anyString(), anyString(), anyList(), anyString());
        doNothing().when(ec2).deleteNetworkInterface(REGION, "eni-1");
        manager.remove(loadBalancer);
        assertTrue(store.scanAllAccounts().isEmpty());
    }

    @Test
    void removalAttemptsEveryNodeAndRetainsEachFailureForRetry() {
        assertTrue(manager.reconcile(loadBalancer));
        IllegalStateException first = new IllegalStateException("first removal");
        IllegalStateException second = new IllegalStateException("second removal");
        doThrow(first).when(ec2).deleteNetworkInterface(REGION, "eni-1");
        doThrow(second).when(ec2).deleteNetworkInterface(REGION, "eni-2");
        assertSame(first, assertThrows(IllegalStateException.class, () -> manager.remove(loadBalancer)));
        assertArrayEquals(new Throwable[]{second}, first.getSuppressed());
        verify(ec2).deleteNetworkInterface(REGION, "eni-2");
        assertEquals(2, store.scanAllAccounts().size());
        doNothing().when(ec2).deleteNetworkInterface(eq(REGION), anyString());
        manager.remove(loadBalancer);
        assertTrue(store.scanAllAccounts().isEmpty());
        assertTrue(manager.addresses(loadBalancer).isEmpty());
    }

    @Test
    void replacementFailureRetainsOldNodesAndCompensatesAllNewNodes() {
        assertTrue(manager.reconcile(loadBalancer));
        IllegalStateException failure = new IllegalStateException("old node busy");
        doThrow(failure).when(lifecycle).removeIfExistsStrict("helper-eni-1");
        loadBalancer.setSubnets(List.of("subnet-c", "subnet-d"));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer)));
        verify(ec2).deleteNetworkInterface(REGION, "eni-2");
        verify(ec2).deleteNetworkInterface(REGION, "eni-3");
        verify(ec2).deleteNetworkInterface(REGION, "eni-4");
        assertEquals("eni-1", store.scanAllAccounts().getFirst().eni());
        assertEquals(List.of("10.0.1.10"), manager.addresses(loadBalancer));
        doNothing().when(lifecycle).removeIfExistsStrict("helper-eni-1");
        manager.remove(loadBalancer);
        verify(ec2, times(1)).deleteNetworkInterface(REGION, "eni-2");
        assertTrue(store.scanAllAccounts().isEmpty());
    }

    @Test
    void restartRecoversAllJournaledNodesBeforeNewAllocations() {
        assertTrue(manager.reconcile(loadBalancer));
        ElbClassicFrontendManager replacement = newManager();
        clearInvocations(ec2);
        replacement.initialize();
        verify(ec2).deleteNetworkInterface(REGION, "eni-1");
        verify(ec2).deleteNetworkInterface(REGION, "eni-2");
        verify(ec2, never()).createServiceNetworkInterface(anyString(), anyString(), anyString(), anyList(), anyString());
        assertTrue(store.scanAllAccounts().isEmpty());
    }

    @Test
    void publicAddressesAreResolvedForEachOwnedNode() {
        assertTrue(manager.reconcile(loadBalancer));
        when(routing.publicAddress(ACCOUNT, REGION, "eni-1")).thenReturn(Optional.of("198.51.100.1"));
        when(routing.publicAddress(ACCOUNT, REGION, "eni-2")).thenReturn(Optional.of("198.51.100.2"));
        assertEquals(List.of("198.51.100.1", "198.51.100.2"), manager.addresses(loadBalancer));
        manager.stopManagedContainers();
        assertTrue(store.scanAllAccounts().isEmpty());
    }

    @Test
    void singleSubnetStillAllocatesAndCleansOneNode() {
        loadBalancer.setSubnets(List.of("subnet-a"));
        assertTrue(manager.reconcile(loadBalancer));
        assertEquals(List.of("10.0.1.10"), manager.addresses(loadBalancer));
        manager.remove(loadBalancer);
        assertTrue(store.scanAllAccounts().isEmpty());
    }

    @Test
    void missingSubnetFailsBeforeAllocatingAnything() {
        loadBalancer.setSubnets(List.of());
        assertThrows(AwsException.class, () -> manager.reconcile(loadBalancer));
        assertEquals(0, allocations.get());
        assertTrue(store.scanAllAccounts().isEmpty());
    }

    @Test
    void pendingCleanupRetryAttemptsEveryUnpublishedNodeDespiteFailures() {
        loadBalancer.setSubnets(List.of("subnet-a", "subnet-b", "subnet-c"));
        IllegalStateException startup = new IllegalStateException("third subnet unavailable");
        when(ec2.createServiceNetworkInterface(eq(REGION), eq("subnet-c"), anyString(), anyList(), anyString()))
                .thenThrow(startup);
        doThrow(new IllegalStateException("first rollback failed")).when(ec2).deleteNetworkInterface(REGION, "eni-1");
        doThrow(new IllegalStateException("second rollback failed")).when(ec2).deleteNetworkInterface(REGION, "eni-2");
        assertSame(startup, assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer)));
        assertEquals(2, store.scanAllAccounts().size());
        assertTrue(manager.addresses(loadBalancer).isEmpty());
        clearInvocations(ec2);
        doThrow(new IllegalStateException("first retry failed")).when(ec2).deleteNetworkInterface(REGION, "eni-1");
        doThrow(new IllegalStateException("second retry failed")).when(ec2).deleteNetworkInterface(REGION, "eni-2");
        assertThrows(IllegalStateException.class, () -> manager.remove(loadBalancer));
        verify(ec2).deleteNetworkInterface(REGION, "eni-1");
        verify(ec2).deleteNetworkInterface(REGION, "eni-2");
        assertEquals(2, store.scanAllAccounts().size());
        doNothing().when(ec2).deleteNetworkInterface(eq(REGION), anyString());
        manager.remove(loadBalancer);
        assertTrue(store.scanAllAccounts().isEmpty());
    }

    private IllegalStateException failSecondAllocation() {
        IllegalStateException failure = new IllegalStateException("second subnet unavailable");
        when(ec2.createServiceNetworkInterface(eq(REGION), eq("subnet-b"), anyString(), anyList(), anyString()))
                .thenThrow(failure);
        return failure;
    }

    private ElbClassicFrontendManager newManager() {
        return new ElbClassicFrontendManager(config, ec2, firewall, networks, mock(ContainerBuilder.class), lifecycle,
                new ObjectMapper(), mock(IamService.class), mock(AcmService.class), routing, factory);
    }
}
