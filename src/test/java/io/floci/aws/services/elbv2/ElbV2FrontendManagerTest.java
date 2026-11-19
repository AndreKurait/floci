package io.floci.aws.services.elbv2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.LoadBalancerProxySources;
import io.floci.aws.config.LoadBalancerProxySources.Backend;
import io.floci.aws.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElbV2FrontendManagerTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-west-2";
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final VpcRoutingManager routing = mock(VpcRoutingManager.class);
    private final LoadBalancerProxySources sources = new LoadBalancerProxySources();
    private final ElbV2FrontendManager manager = new ElbV2FrontendManager(config,
            mock(ContainerLifecycleManager.class), routing, sources);
    private final LoadBalancer balancer = new LoadBalancer();

    @Test
    void internalFrontendAddressesAreScopedToTheExactAccountRegionAndVpc() {
        prepare("internal");
        ClientVpc owner = new ClientVpc(ACCOUNT, REGION, "vpc-owned");
        assertEquals(List.of("10.0.1.10"), manager.addresses(balancer, owner));
        for (ClientVpc foreign : List.of(new ClientVpc("222222222222", REGION, "vpc-owned"),
                new ClientVpc(ACCOUNT, "us-east-1", "vpc-owned"), new ClientVpc(ACCOUNT, REGION, "vpc-other"))) {
            assertTrue(manager.addresses(balancer, foreign).isEmpty());
        }
        assertTrue(manager.addresses(balancer, null).isEmpty());
        manager.retire("eni-owned");
        assertTrue(manager.addresses(balancer, owner).isEmpty());
    }

    @Test
    void publicFrontendsUseAnOwnedReachableTransportWithoutChangingVpcPublicAddresses() {
        when(routing.publicFrontendTransport(ACCOUNT, REGION, "eni-owned")).thenReturn(Optional.of("240.0.0.2"));
        when(routing.publicAddress(ACCOUNT, REGION, "eni-owned")).thenReturn(Optional.of("198.51.100.1"));
        prepare("internet-facing");
        when(routing.publicFrontendTransport(ACCOUNT, REGION, "eni-owned")).thenReturn(Optional.of("172.16.0.10"));
        assertEquals(List.of("172.16.0.10"), manager.addresses(balancer, null));
        assertEquals(List.of("198.51.100.1"), manager.addresses(balancer,
                new ClientVpc(ACCOUNT, REGION, "vpc-caller")));
        when(routing.publicFrontendTransport(ACCOUNT, REGION, "eni-owned")).thenReturn(Optional.empty());
        assertTrue(manager.addresses(balancer, null).isEmpty());
    }

    @Test
    void nativeConnectorsUseSocketMetadataAndOnlyExactListenerAddresses() {
        String connector = ElbV2FrontendManager.connector("172.16.0.2", "172.16.0.10", 443);
        assertTrue(connector.contains("$SOCAT_PEERADDR"));
        assertTrue(connector.contains("$SOCAT_SOCKADDR"));
        assertTrue(connector.contains("TCP4:172.16.0.2:443,bind=172.16.0.10"));
        assertFalse(connector.contains("Forwarded"));
        String script = ElbV2FrontendManager.listenerScript(443, Set.of("10.0.1.10", "172.16.0.10"));
        assertTrue(script.contains("TCP4-LISTEN:443,bind=10.0.1.10,"));
        assertTrue(script.contains("TCP4-LISTEN:443,bind=172.16.0.10,"));
        assertFalse(script.contains("0.0.0.0"));
        assertThrows(IllegalArgumentException.class,
                () -> ElbV2FrontendManager.connector("controller.example", "172.16.0.10", 443));
        assertThrows(IllegalArgumentException.class,
                () -> ElbV2FrontendManager.listenerScript(443, Set.of("172.16.0.10; exit 0")));
    }

    @Test
    void oneUnsupportedPortWithdrawsTheWholeAlbUntilEverySupportedListenerIsReady() {
        prepare("internal");
        String arn = balancer.getLoadBalancerArn();
        ClientVpc owner = new ClientVpc(ACCOUNT, REGION, "vpc-owned");
        assertTrue(manager.select("https", arn, 443));
        assertNull(sources.ticket("172.16.0.10"));
        assertTrue(manager.addresses(balancer, owner).isEmpty(), "Pending listener is not ready");
        manager.select("control-port", arn, 1080);
        assertTrue(manager.usesSharedListener(arn));
        assertFalse(manager.usesSharedListener(arn + "-other"));
        manager.publish("control-port", arn, 1080, this, new Backend(10080, false, () -> true));
        manager.reconcileBalancer(arn);
        assertTrue(manager.addresses(balancer, owner).isEmpty());
        assertNull(sources.ticket("172.16.0.10"), "A shared-route ALB has no owned admission");
        assertEquals(arn, manager.forget("control-port"));
        manager.reconcileBalancer(arn);
        assertFalse(manager.usesSharedListener(arn));
        assertNull(sources.ticket("172.16.0.10"), "The remaining unpublished HTTPS listener is still pending");
        assertTrue(manager.addresses(balancer, owner).isEmpty());
        manager.forget("https");
        manager.reconcileBalancer(arn);
        assertEquals(List.of("10.0.1.10"), manager.addresses(balancer, owner));
    }

    private void prepare(String scheme) {
        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(true);
        when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.tls().enabled()).thenReturn(true);
        when(config.tls().awsHttpsPort()).thenReturn(443);
        when(routing.enabled()).thenReturn(true);
        balancer.setLoadBalancerArn("arn:aws:elasticloadbalancing:" + REGION + ":" + ACCOUNT
                + ":loadbalancer/app/owned/1");
        balancer.setRegion(REGION);
        balancer.setVpcId("vpc-owned");
        balancer.setType("application");
        balancer.setScheme(scheme);
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-owned");
        eni.setPrivateIpAddress("10.0.1.10");
        eni.setVpcId("vpc-owned");
        manager.prepare(balancer, eni, new ElbV2VpcNetworkManager.Attachment(
                "eni-owned", "owned-helper", "172.16.0.10", "172.16.0.2"));
        manager.reconcile("eni-owned");
    }
}
