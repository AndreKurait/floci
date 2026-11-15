package io.github.hectorvent.floci.services.ec2.net;

import io.github.hectorvent.floci.services.ec2.Ec2NativePeerDependencies;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VpcNativePeerAdmissionTest {
    @Test
    void bothPacketGuardsAdmitOnlyTheDeclaredTcpConnection() {
        Ec2NativePeerDependencies policy = new Ec2NativePeerDependencies("""
                [{"hostname":"api.peer.example","address":"172.18.0.2","port":443}]
                """);
        SecurityGroup group = new SecurityGroup();
        group.setGroupId("sg-no-user-egress");
        group.setVpcId("vpc");
        SecurityGroupNftCompiler.Endpoint endpoint = new SecurityGroupNftCompiler.Endpoint(
                "111111111111", "us-east-1", "vpc", "eni", "10.0.1.10", "172.18.0.3",
                Set.of(group.getGroupId()), List.of(group), policy.connections(), true, Set.of(), List.of(),
                policy.dependencies());
        String securityGroup = SecurityGroupNftCompiler.compile(endpoint, List.of(), Map.of(), List.of());
        String routes = VpcRoutingCommands.endpoint(endpoint.logicalAddress(), "eth1",
                List.of(), List.of(), List.of(), List.of(), endpoint.controlConnections(),
                endpoint.instanceMetadataAllowed(), null, List.of());
        assertTrue(securityGroup.contains("egress ip daddr 172.18.0.2 tcp dport 443 accept"));
        assertTrue(routes.contains("egress ip daddr 172.18.0.2 tcp dport 443 counter accept"));
        for (String script : List.of(securityGroup, routes)) {
            assertFalse(script.contains("daddr 172.18.0.2 tcp dport 80 "));
            assertFalse(script.contains("daddr 172.18.0.99"));
            assertFalse(script.contains("daddr 172.18.0.2 udp"));
            assertFalse(script.contains("daddr 172.18.0.2 accept"));
            assertFalse(script.contains("snat"), "control admission does not replace the guest's real source identity");
        }
        assertTrue(routes.contains("add rule inet floci_vpc_routes egress counter drop"));
    }

    @Test
    void emptyConfigurationDoesNotCreateAnyPeerRouteException() {
        Ec2NativePeerDependencies policy = new Ec2NativePeerDependencies("[]");
        String routes = VpcRoutingCommands.endpoint("10.0.1.10", "eth1",
                List.of(), List.of(), List.of(), List.of(), policy.connections(), true, null, List.of());
        assertFalse(routes.contains("172.18.0.2"));
        assertTrue(routes.contains("add rule inet floci_vpc_routes egress counter drop"));
    }
}
