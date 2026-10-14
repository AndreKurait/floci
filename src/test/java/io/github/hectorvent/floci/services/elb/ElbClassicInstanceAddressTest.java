package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElbClassicInstanceAddressTest {

    @Test
    void externallyManagedInstancesUseTheSameDnsIdentityAsHealthChecks() {
        Instance instance = new Instance();
        instance.setInstanceId("i-0123456789abcdef0");
        assertEquals(instance.getInstanceId(), ElbClassicHealthChecker.instanceHost(instance));
        instance.setContainerBridgeIp(" ");
        assertEquals(instance.getInstanceId(), ElbClassicHealthChecker.instanceHost(instance));
        instance.setContainerBridgeIp("10.20.30.40");
        assertEquals("10.20.30.40", ElbClassicHealthChecker.instanceHost(instance));
    }

    @Test
    void connectionsUseTheValidatedNumericAddressRatherThanResolvingTheNameAgain() throws Exception {
        String address = ElbClassicDataPlane.targetAddress("localhost");
        assertTrue(InetAddress.getByName(address).isLoopbackAddress());
        assertTrue(address.matches("[0-9a-fA-F:.]+"));
        assertEquals("10.20.30.40", ElbClassicDataPlane.targetAddress("10.20.30.40"));
    }

    @Test
    void metadataAddressesRemainUnavailableAsLoadBalancerTargets() {
        for (String address : new String[] {"169.254.169.254", "169.254.170.2", "fd00:ec2::254"}) {
            assertThrows(IOException.class, () -> ElbClassicDataPlane.targetAddress(address));
        }
    }
}
