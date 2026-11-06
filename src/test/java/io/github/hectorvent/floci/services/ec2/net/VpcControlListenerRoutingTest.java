package io.github.hectorvent.floci.services.ec2.net;

import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler.ControlListener;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VpcControlListenerRoutingTest {
    @Test
    void onlyRepliesToTheDeclaredControllerListenerBypassVpcRoutes() {
        String script = VpcRoutingCommands.endpoint("10.0.1.10", List.of(), List.of(), List.of(),
                List.of(), false, null, List.of(), List.of(new ControlListener("172.18.0.2", 1080)));

        assertTrue(script.contains("egress ip daddr 172.18.0.2 tcp sport 1080 ct direction reply"
                + " ct original ip saddr 172.18.0.2 ct original proto-dst 1080 counter accept"));
        assertFalse(script.contains("daddr 172.18.0.2 counter accept"));
        assertFalse(script.contains("ct state established,related counter accept"));
        assertTrue(script.contains("add rule inet floci_vpc_routes egress counter drop"));
    }

    @Test
    void ordinaryEndpointHasNoControllerException() {
        String script = VpcRoutingCommands.endpoint("10.0.1.10", List.of(), List.of(), List.of(),
                List.of(), false, null, List.of());

        assertFalse(script.contains("ct direction reply"));
        assertTrue(script.contains("add rule inet floci_vpc_routes egress counter drop"));
    }
}
