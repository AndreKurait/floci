package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Floci sends a load balancer's health checks and forwarded requests from its own container address.
 * On AWS they come from load-balancer nodes in the load balancer's security groups, so a target that
 * admits only those groups is reachable; the firewall must give Floci's address the same admission.
 */
class SecurityGroupFirewallEmulatorOriginTest extends SecurityGroupFirewallPeerFixture {

    private static final String FLOCI = "172.18.0.1";
    private static final String ACCEPT_FLOCI = "add rule inet floci_sg ingress ip saddr " + FLOCI
            + " tcp dport 8080 accept";

    /** Floci's address also serves the VPC's DNS, so only ingress from it is about load balancers. */
    private static final String FROM_FLOCI = "ingress ip saddr " + FLOCI + " ";

    private final List<SecurityGroupNftCompiler.EmulatorOrigin> loadBalancers = new CopyOnWriteArrayList<>();

    @Test
    void aTargetAdmittingOnlyTheLoadBalancersGroupAcceptsFlociWhileThatLoadBalancerExists() {
        SecurityGroupFirewallManager manager = managerWithLoadBalancers();
        loadBalancers.add(alb("vpc-a", "sg-alb"));
        manager.register(target("eni-target", "172.18.0.50", "sg-alb"), "helper-target", Map.of());

        assertTrue(installed.get("helper-target").contains(ACCEPT_FLOCI), installed.get("helper-target"));

        loadBalancers.clear();
        manager.emulatorOriginsChanged();
        await().atMost(10, TimeUnit.SECONDS).until(() -> !installed.get("helper-target").contains(ACCEPT_FLOCI));
    }

    @Test
    void aLoadBalancerCreatedAfterItsTargetOpensTheTargetOnNotification() {
        SecurityGroupFirewallManager manager = managerWithLoadBalancers();
        manager.register(target("eni-target", "172.18.0.50", "sg-alb"), "helper-target", Map.of());
        assertFalse(installed.get("helper-target").contains(FROM_FLOCI));

        loadBalancers.add(alb("vpc-a", "sg-alb"));
        manager.emulatorOriginsChanged();
        await().atMost(10, TimeUnit.SECONDS).until(() -> installed.get("helper-target").contains(ACCEPT_FLOCI));
    }

    /** Passes with or without the defect: nothing admits the load balancer's group or Floci's address. */
    @Test
    void aTargetAdmittingNeitherTheLoadBalancersGroupNorFlociStaysClosedToFloci() {
        SecurityGroupFirewallManager manager = managerWithLoadBalancers();
        loadBalancers.add(alb("vpc-a", "sg-alb"));
        loadBalancers.add(alb("vpc-b", "sg-other"));
        manager.register(target("eni-target", "172.18.0.50", "sg-other"), "helper-target", Map.of());

        assertFalse(installed.get("helper-target").contains(FROM_FLOCI), installed.get("helper-target"));
    }

    /**
     * In a shared address space Floci joins the VPC network, so a protected target of the target
     * group's VPC is dialled at the address named. A private address with no VPC network behind it
     * belongs to nobody Floci protects and is dialled as before.
     */
    @Test
    void loadBalancerTrafficDialsAProtectedTargetOfItsVpcAtTheAddressNamed() {
        SecurityGroupFirewallManager manager = managerWithLoadBalancers();
        manager.register(attachedTarget("eni-target", "172.18.0.50", "10.0.1.50"), "helper-target", Map.of());

        assertEquals("10.0.1.50", manager.emulatorRoute("111111111111", "us-east-1", "vpc-a", "10.0.1.50"));
        assertEquals("172.18.0.50", manager.emulatorRoute("111111111111", "us-east-1", "vpc-a", "172.18.0.50"));
        assertEquals("192.0.2.7", manager.emulatorRoute("111111111111", "us-east-1", "vpc-a", "192.0.2.7"),
                "an address no protected endpoint holds is dialled as before");

        manager.unregister("eni-target");
        assertEquals("10.0.1.50", manager.emulatorRoute("222222222222", "us-east-1", "vpc-b", "10.0.1.50"),
                "a retired endpoint no longer reserves its addresses");
    }

    /** Another account's or VPC's load balancer must not reach the target through Floci's address. */
    @Test
    void aProtectedEndpointOfAnotherVpcOrAccountIsUnreachableForALoadBalancer() {
        SecurityGroupFirewallManager manager = managerWithLoadBalancers();
        manager.register(attachedTarget("eni-target", "172.18.0.50", "10.0.1.50"), "helper-target", Map.of());

        assertNull(manager.emulatorRoute("222222222222", "us-east-1", "vpc-a", "10.0.1.50"));
        assertNull(manager.emulatorRoute("111111111111", "us-east-1", "vpc-b", "172.18.0.50"));
        assertNull(manager.emulatorRoute("111111111111", "us-west-2", "vpc-a", "10.0.1.50"));
        assertNull(manager.emulatorRoute("111111111111", "us-east-1", "vpc-b", "::ffff:172.18.0.50"),
                "another spelling of the same address is the same endpoint");
    }

    /** Without a VPC network the private address exists only on paper; another host may own it. */
    @Test
    void aPaperOnlyPrivateAddressIsNotReserved() {
        SecurityGroupFirewallManager manager = managerWithLoadBalancers();
        manager.register(target("eni-target", "172.18.0.50", "sg-alb"), "helper-target", Map.of());

        assertEquals("10.0.1.50", manager.emulatorRoute("111111111111", "us-east-1", "vpc-b", "10.0.1.50"));
    }

    /**
     * Floci admits only its own address on the network it shares with the target: the target's VPC
     * network where both joined it, otherwise its main network, and nothing for a network it is not on.
     */
    @Test
    void onlyFlocisAddressOnTheNetworkItSharesWithTheTargetIsAdmitted() {
        SecurityGroupFirewallManager manager = managerWithLoadBalancers();
        CurrentContainerNetworkResolver self = mock(CurrentContainerNetworkResolver.class);
        when(self.resolveContainerId()).thenReturn(Optional.of("floci-self"));
        manager.currentContainer = self;
        containerNetworks.put("floci-self", Map.of(
                "main", network("172.18.0.1", 16), "vpc-a-network", network("10.0.1.2", 24)));
        loadBalancers.add(alb("vpc-a", "sg-alb"));

        manager.register(attachedTarget("eni-attached", "172.18.0.50", "10.0.1.50"), "helper-attached", Map.of());
        manager.register(target("eni-task", "172.18.0.60", "sg-alb"), "helper-task", Map.of());
        manager.register(target("eni-elsewhere", "192.168.5.5", "sg-alb"), "helper-elsewhere", Map.of());

        String attached = installed.get("helper-attached");
        assertTrue(attached.contains("ingress ip saddr 10.0.1.2 tcp dport 8080 accept"), attached);
        assertFalse(attached.contains("ingress ip saddr 172.18.0.1 "), attached);
        assertTrue(installed.get("helper-task").contains(ACCEPT_FLOCI), installed.get("helper-task"));
        assertFalse(installed.get("helper-elsewhere").contains(" tcp dport 8080 accept"),
                installed.get("helper-elsewhere"));
    }

    /** A failed look at Floci's own networks keeps the last answer instead of moving the admission. */
    @Test
    void aFailedLookAtFlocisOwnNetworksKeepsTheLastAnswer() {
        SecurityGroupFirewallManager manager = managerWithLoadBalancers();
        CurrentContainerNetworkResolver self = mock(CurrentContainerNetworkResolver.class);
        when(self.resolveContainerId()).thenReturn(Optional.of("floci-self"));
        manager.currentContainer = self;
        containerNetworks.put("floci-self", Map.of(
                "main", network("172.18.0.1", 16), "vpc-a-network", network("10.0.1.2", 24)));
        loadBalancers.add(alb("vpc-a", "sg-alb"));
        manager.register(attachedTarget("eni-attached", "172.18.0.50", "10.0.1.50"), "helper-attached", Map.of());

        when(self.resolveContainerId()).thenThrow(new IllegalStateException("docker unavailable"));
        manager.reconcileAll();

        String attached = installed.get("helper-attached");
        assertTrue(attached.contains("ingress ip saddr 10.0.1.2 tcp dport 8080 accept"), attached);
        assertFalse(attached.contains("ingress ip saddr 172.18.0.1 "), attached);
    }

    /** A target group or load balancer outside any VPC reaches no protected endpoint. */
    @Test
    void noVpcReachesNoProtectedEndpoint() {
        SecurityGroupFirewallManager manager = managerWithLoadBalancers();
        manager.register(attachedTarget("eni-target", "172.18.0.50", "10.0.1.50"), "helper-target", Map.of());

        assertNull(manager.emulatorRoute("111111111111", "us-east-1", null, "10.0.1.50"));
        assertEquals("192.0.2.7", manager.emulatorRoute("111111111111", "us-east-1", null, "192.0.2.7"));
    }

    private static ContainerNetwork network(String address, int prefix) {
        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn(address);
        when(network.getIpPrefixLen()).thenReturn(prefix);
        return network;
    }

    /** Outside Docker, Floci reaches containers from a gateway every host process shares. */
    @Test
    void flociOutsideDockerAdmitsNoLoadBalancerTraffic() {
        SecurityGroupFirewallManager manager = managerWithLoadBalancers();
        ContainerDetector host = mock(ContainerDetector.class);
        when(host.isRunningInContainer()).thenReturn(false);
        manager.containerDetector = host;
        loadBalancers.add(alb("vpc-a", "sg-alb"));
        manager.register(target("eni-target", "172.18.0.50", "sg-alb"), "helper-target", Map.of());

        assertFalse(installed.get("helper-target").contains(FROM_FLOCI), installed.get("helper-target"));
    }

    /** A load-balancer source that fails admits nothing instead of quarantining the endpoint. */
    @Test
    @SuppressWarnings("unchecked")
    void aFailingLoadBalancerSourceAdmitsNothingAndKeepsTheTargetsPolicy() {
        SecurityGroupFirewallManager manager = manager(Optional.of(FLOCI));
        EmulatorOriginSource broken = () -> {
            throw new IllegalStateException("storage unavailable");
        };
        Instance<EmulatorOriginSource> sources = mock(Instance.class);
        when(sources.isUnsatisfied()).thenReturn(false);
        when(sources.stream()).thenAnswer(ignored -> Stream.of(broken));
        manager.emulatorOriginSources = sources;

        manager.register(target("eni-target", "172.18.0.50", "sg-alb"), "helper-target", Map.of());

        String policy = installed.get("helper-target");
        assertFalse(policy.contains(FROM_FLOCI), policy);
        assertTrue(policy.contains("ingress ct state established,related accept"), "the policy is installed");
    }

    /** A read that fails after a good one keeps the good answer instead of closing every target. */
    @Test
    @SuppressWarnings("unchecked")
    void aSourceThatFailsLaterKeepsItsLastAnswer() {
        SecurityGroupFirewallManager manager = manager(Optional.of(FLOCI));
        AtomicBoolean failing = new AtomicBoolean();
        EmulatorOriginSource flaky = () -> {
            if (failing.get()) {
                throw new IllegalStateException("storage unavailable");
            }
            return List.of(alb("vpc-a", "sg-alb"));
        };
        Instance<EmulatorOriginSource> sources = mock(Instance.class);
        when(sources.isUnsatisfied()).thenReturn(false);
        when(sources.stream()).thenAnswer(ignored -> Stream.of(flaky));
        manager.emulatorOriginSources = sources;
        manager.register(target("eni-target", "172.18.0.50", "sg-alb"), "helper-target", Map.of());
        assertTrue(installed.get("helper-target").contains(ACCEPT_FLOCI));

        failing.set(true);
        manager.reconcileAll();

        assertTrue(installed.get("helper-target").contains(ACCEPT_FLOCI), installed.get("helper-target"));
    }

    @SuppressWarnings("unchecked")
    private SecurityGroupFirewallManager managerWithLoadBalancers() {
        SecurityGroupFirewallManager manager = manager(Optional.of(FLOCI));
        EmulatorOriginSource source = () -> List.copyOf(loadBalancers);
        Instance<EmulatorOriginSource> sources = mock(Instance.class);
        when(sources.isUnsatisfied()).thenReturn(false);
        when(sources.stream()).thenAnswer(ignored -> Stream.of(source));
        manager.emulatorOriginSources = sources;
        return manager;
    }

    private static SecurityGroupNftCompiler.EmulatorOrigin alb(String vpcId, String groupId) {
        return new SecurityGroupNftCompiler.EmulatorOrigin("111111111111", "us-east-1", vpcId, Set.of(groupId));
    }

    /** A target attached to its VPC network at {@code vpcAddress}, admitting the ALB's group. */
    private static SecurityGroupNftCompiler.Endpoint attachedTarget(String eni, String transport, String vpcAddress) {
        SecurityGroupNftCompiler.Endpoint base = target(eni, transport, "sg-alb");
        return new SecurityGroupNftCompiler.Endpoint(base.accountId(), base.region(), base.vpcId(), eni,
                vpcAddress, transport, base.groupIds(), base.groups(), Set.of(vpcAddress));
    }

    private static SecurityGroupNftCompiler.Endpoint target(String eni, String transport, String admittedGroup) {
        SecurityGroup group = new SecurityGroup();
        group.setGroupId("sg-target");
        group.setVpcId("vpc-a");
        group.setOwnerId("111111111111");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(8080);
        permission.setToPort(8080);
        UserIdGroupPair source = new UserIdGroupPair();
        source.setGroupId(admittedGroup);
        permission.setUserIdGroupPairs(List.of(source));
        group.setIpPermissions(List.of(permission));
        return new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", "vpc-a", eni,
                "10.0.1.50", transport, Set.of("sg-target"), List.of(group));
    }
}
