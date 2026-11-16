package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
    void guestCommandKeepsLocalhostOnTheIpv4Loopback() {
        // Docker's hosts file names ::1 localhost too; once the namespace has IPv6, getaddrinfo returns ::1 first,
        // and a client of an IPv4-only loopback listener (a service that listens on 127.0.0.1 only) fails.
        String script = Ec2ContainerManager.guestIpv6Command("10.1.2.10", "2600:1f18:abcd:ef01::1234",
                "2600:1f18:abcd:ef00::/56")[2];
        assertTrue(script.contains("' /etc/hosts)") && script.contains("> /etc/hosts"), script);
    }

    @Test
    void theHostsRewriteDropsLocalhostFromTheIpv6LoopbackOnly(@TempDir Path dir) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/awk")) || Files.isExecutable(Path.of("/bin/awk")), "awk");
        // The awk program exactly as the guest IPv6 script runs it against /etc/hosts.
        String script = Ec2ContainerManager.guestIpv6Command("10.1.2.10", "2600:1f18:abcd:ef01::1234",
                "2600:1f18:abcd:ef00::/56")[2];
        Matcher rewrite = Pattern.compile("hosts=\\$\\(awk '([^']*)' /etc/hosts\\)").matcher(script);
        assertTrue(rewrite.find(), script);
        Path hosts = dir.resolve("hosts");
        Files.writeString(hosts, String.join("\n", "127.0.0.1\tlocalhost", "::1\tlocalhost ip6-localhost ip6-loopback",
                "::1\tlocalhost", "fe00::0\tip6-localnet", "172.16.5.38\tc45f7283f23a", ""), StandardCharsets.UTF_8);
        Process awk = new ProcessBuilder("awk", rewrite.group(1), hosts.toString())
                .redirectErrorStream(true).start();
        assertTrue(awk.waitFor(10, TimeUnit.SECONDS));
        String out = new String(awk.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, awk.exitValue(), out);
        assertEquals(List.of("127.0.0.1\tlocalhost", "::1\tip6-localhost\tip6-loopback", "fe00::0\tip6-localnet",
                "172.16.5.38\tc45f7283f23a"), out.lines().toList());
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
