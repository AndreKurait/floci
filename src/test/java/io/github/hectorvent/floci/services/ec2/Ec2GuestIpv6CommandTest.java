package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The guest and router IPv6 scripts take every address as a positional argument, never interpolated into the
 * script text, and configure exactly the interface that holds the given IPv4 address.
 */
class Ec2GuestIpv6CommandTest {

    @Test
    void guestCommandAddsTheAddressRoutesTheVpcBlockAndDefaultsToTheVpcRouter() {
        String[] command = Ec2ContainerManager.guestIpv6Command("10.1.2.10", "2600:1f18:abcd:ef01::1234",
                "2600:1f18:abcd:ef00::/56");
        assertEquals(List.of("sh", "-c"), List.of(command[0], command[1]));
        assertEquals(List.of("guest-ipv6", "10.1.2.10", "2600:1f18:abcd:ef01::1234", "2600:1f18:abcd:ef00::/56"),
                List.of(command[3], command[4], command[5], command[6]));
        String script = command[2];
        assertTrue(script.contains("ip -6 addr replace \"$2/64\" dev \"$vpc\" nodad"), script);
        assertTrue(script.contains("ip -6 route replace \"$3\" dev \"$vpc\""), script);
        assertTrue(script.contains("ip -6 route replace default via fe80:ec2::1 dev \"$vpc\""), script);
        assertTrue(script.contains("addrgenmode none"), script);
        assertTrue(!script.contains("10.1.2.10") && !script.contains("2600:"), script);
    }

    @Test
    void routerCommandDropsIpv4HoldsTheLinkLocalGatewayAndRoutesTheBlock() {
        String[] command = Ec2Ipv6VpcRouter.joinCommand("10.1.2.3", "2600:1f18:abcd:ef00::/56");
        assertEquals(List.of("ipv6-router", "10.1.2.3", "2600:1f18:abcd:ef00::/56"),
                List.of(command[3], command[4], command[5]));
        String script = command[2];
        assertTrue(script.contains("ip -4 addr flush dev \"$dev\""), script);
        assertTrue(script.contains("ip -6 addr replace fe80:ec2::1/64 dev \"$dev\" nodad"), script);
        assertTrue(script.contains("ip -6 route replace \"$2\" dev \"$dev\""), script);
        assertTrue(!script.contains("default"), script);
    }

    @Test
    void retiringARouterThatNeverStartedTouchesNoContainer() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        Ec2Ipv6VpcRouter router = new Ec2Ipv6VpcRouter(mock(DockerClient.class), mock(ContainerBuilder.class), lifecycle,
                mock(EmulatorConfig.class), mock(VpcNetworkManager.class));
        router.retire();
        router.retire();
        verifyNoInteractions(lifecycle);
    }

    @Test
    void routerLeavesTheNetworkItStartedOnUnlessThatIsNoneHostOrShared() {
        assertEquals(Optional.of("floci-run"), Ec2Ipv6VpcRouter.networkToLeave("floci-run"));
        assertEquals(Optional.of("bridge"), Ec2Ipv6VpcRouter.networkToLeave("bridge"));
        for (String mode : new String[] {null, "", "none", "host", "container:abc"}) {
            assertTrue(Ec2Ipv6VpcRouter.networkToLeave(mode).isEmpty(), String.valueOf(mode));
        }
    }
}
