package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elbv2.model.AvailabilityZone;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElbV2VpcLifecycleTest {
    private static final String REGION = "us-west-2";
    private final ElbV2VpcNetworkManager networks = mock(ElbV2VpcNetworkManager.class);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final ElbV2Service service = new ElbV2Service(networks);

    @BeforeEach
    void prepare() {
        service.regionResolver = new RegionResolver(REGION, "111111111111");
        service.ec2Service = ec2;
        when(networks.enabled()).thenReturn(true);
        when(ec2.supplyWithSubnetReferencesHeld(eq(REGION), any()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        List<Subnet> subnets = List.of(subnet("subnet-a", REGION + "a"), subnet("subnet-b", REGION + "b"));
        when(ec2.describeSubnets(REGION, List.of("subnet-a", "subnet-b"), Map.of())).thenReturn(subnets);
        for (Subnet subnet : subnets) {
            when(ec2.requireSubnet(REGION, subnet.getSubnetId())).thenReturn(subnet);
        }
    }

    @Test
    void creationIsNotActiveBeforeTheBackendNetworkIsReady() {
        doAnswer(call -> {
            LoadBalancer balancer = call.getArgument(0);
            assertEquals("provisioning", balancer.getState());
            balancer.getBackendNetworkInterfaceIds().add("eni-owned");
            call.<Runnable>getArgument(1).run();
            return null;
        }).when(networks).reconcile(any(), any());
        LoadBalancer balancer = create();
        assertEquals("active", balancer.getState());
        assertEquals(List.of("eni-owned"), service.getLoadBalancer(REGION,
                balancer.getLoadBalancerArn()).getBackendNetworkInterfaceIds());
    }

    @Test
    void failedPreparationRemovesTheBalancerOnlyAfterOwnedCleanup() {
        doThrow(new IllegalStateException("proxy failed")).when(networks).reconcile(any(), any());
        doAnswer(call -> {
            LoadBalancer balancer = call.getArgument(0);
            assertSame(balancer, service.getLoadBalancer(REGION, balancer.getLoadBalancerArn()));
            return null;
        }).when(networks).release(any(), any());
        assertThrows(IllegalStateException.class, this::create);
        verify(networks).release(any(), any());
        assertTrue(service.describeLoadBalancers(REGION, null, null, null, null).isEmpty());
    }

    @Test
    void failedCleanupKeepsTheOwnerAvailableForADeletionRetry() {
        LoadBalancer balancer = create();
        balancer.getBackendNetworkInterfaceIds().add("eni-owned");
        doThrow(new IllegalStateException("Docker unavailable")).when(networks).release(eq(balancer), any());
        assertThrows(IllegalStateException.class, () -> service.deleteLoadBalancer(REGION, balancer.getLoadBalancerArn()));
        assertSame(balancer, service.getLoadBalancer(REGION, balancer.getLoadBalancerArn()));
        assertEquals(List.of("eni-owned"), balancer.getBackendNetworkInterfaceIds());
        doNothing().when(networks).release(eq(balancer), any());
        service.deleteLoadBalancer(REGION, balancer.getLoadBalancerArn());
        assertTrue(service.describeLoadBalancers(REGION, null, null, null, null).isEmpty());
    }

    @Test
    void failedSecurityGroupUpdateRestoresThePreviousAssociation() {
        LoadBalancer balancer = create();
        doAnswer(call -> {
            LoadBalancer current = call.getArgument(0);
            if (current.getSecurityGroups().contains("sg-invalid")) {
                throw new IllegalStateException("Unknown security group");
            }
            assertEquals(List.of("sg-original"), current.getSecurityGroups());
            return null;
        }).when(networks).reconcile(eq(balancer), any());
        assertThrows(IllegalStateException.class,
                () -> service.setSecurityGroups(REGION, balancer.getLoadBalancerArn(), List.of("sg-invalid")));
        assertEquals(List.of("sg-original"), balancer.getSecurityGroups());
        assertFalse(service.describeLoadBalancers(REGION, null, null, null, null).isEmpty());
    }

    @Test
    void failedSubnetPreparationRestoresThePreviousSubnets() {
        LoadBalancer balancer = create();
        Subnet replacement = subnet("subnet-c", REGION + "c");
        when(ec2.requireSubnet(REGION, "subnet-c")).thenReturn(replacement);
        when(ec2.describeSubnets(REGION, List.of("subnet-a", "subnet-c"), Map.of()))
                .thenReturn(List.of(subnet("subnet-a", REGION + "a"), replacement));
        doAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        }).when(ec2).runWithSubnetReferencesHeld(eq(REGION), any());
        doAnswer(call -> {
            LoadBalancer current = call.getArgument(0);
            if (current.getAvailabilityZones().stream().anyMatch(zone -> "subnet-c".equals(zone.getSubnetId()))) {
                throw new IllegalStateException("No namespace capacity");
            }
            return null;
        }).when(networks).reconcile(eq(balancer), any());
        assertThrows(IllegalStateException.class,
                () -> service.setSubnets(REGION, balancer.getLoadBalancerArn(), List.of("subnet-a", "subnet-c")));
        assertEquals(List.of("subnet-a", "subnet-b"), balancer.getAvailabilityZones().stream()
                .map(AvailabilityZone::getSubnetId).toList());
    }

    @Test
    void identicalActiveCreateDoesNotReconcileOrLoseExistingBackend() {
        LoadBalancer first = create();
        first.getBackendNetworkInterfaceIds().add("eni-ready");
        doThrow(new IllegalStateException("unrelated firewall reconciliation blocked"))
                .when(networks).reconcile(eq(first), any());
        assertSame(first, create());
        assertEquals("active", first.getState());
        assertEquals(List.of("eni-ready"), first.getBackendNetworkInterfaceIds());
        verify(networks, times(1)).reconcile(eq(first), any());
    }

    @Test
    void runtimeRestoreReconcilesBeforeReturningToActive() {
        LoadBalancer first = create();
        doAnswer(call -> {
            assertEquals("provisioning", first.getState());
            return null;
        }).when(networks).reconcile(eq(first), any());

        service.restorePersistedRuntime();

        assertEquals("active", first.getState());
        assertSame(first, create());
        verify(networks, times(2)).reconcile(eq(first), any());
    }

    @Test
    void failedRuntimeRestoreDoesNotLeaveAnActiveRetryFastPath() {
        LoadBalancer first = create();
        doThrow(new IllegalStateException("restore unavailable")).when(networks).reconcile(eq(first), any());
        service.restorePersistedRuntime();
        assertEquals("failed", first.getState());
        doNothing().when(networks).reconcile(eq(first), any());
        assertSame(first, create());
        assertEquals("active", first.getState());
        verify(networks, times(3)).reconcile(eq(first), any());
    }

    @Test
    void changedCreateStillFailsBeforeRuntimeReconciliation() {
        LoadBalancer first = create();
        AwsException error = assertThrows(AwsException.class,
                () -> service.createLoadBalancer(REGION, "native-alb", "internal", "application", "ipv4",
                        List.of("subnet-a", "subnet-b"), List.of("sg-other"), Map.of()));
        assertEquals("DuplicateLoadBalancerName", error.getErrorCode());
        verify(networks, times(1)).reconcile(eq(first), any());
    }

    private LoadBalancer create() {
        return service.createLoadBalancer(REGION, "native-alb", "internal", "application", "ipv4",
                List.of("subnet-a", "subnet-b"), List.of("sg-original"), Map.of());
    }

    private static Subnet subnet(String id, String zone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(id);
        subnet.setAvailabilityZone(zone);
        subnet.setVpcId("vpc-native");
        return subnet;
    }
}
