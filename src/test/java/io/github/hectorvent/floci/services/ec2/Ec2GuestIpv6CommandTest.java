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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The IPv6 scripts take addresses as positional arguments, never interpolated into the script text.
 * Guests locate their interface by IPv4 address; the router uses its endpoint MAC to survive an IPv4 flush.
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
        String[] command = Ec2Ipv6VpcRouter.joinCommand("02:42:0a:01:02:03", "2600:1f18:abcd:ef00::/56");
        assertEquals(List.of("ipv6-router", "02:42:0a:01:02:03", "2600:1f18:abcd:ef00::/56"),
                List.of(command[3], command[4], command[5]));
        String script = command[2];
        assertTrue(script.contains("ip -4 addr flush dev \"$dev\""), script);
        assertTrue(script.contains("ip -6 addr replace fe80:ec2::1/64 dev \"$dev\" nodad"), script);
        assertTrue(script.contains("ip -6 route replace \"$2\" dev \"$dev\""), script);
        assertTrue(!script.contains("default"), script);
    }

    @Test
    void routerCanRetryAndAddAnotherBlockAfterFlushingIpv4(@TempDir Path dir) throws Exception {
        Path calls = fakeRouterIp(dir);
        String mac = "02:42:0a:01:02:03";

        assertEquals(0, runRouterCommand(dir, mac, "2001:db8::/56"));
        assertEquals(0, runRouterCommand(dir, mac, "2001:db8::/56"));
        assertEquals(0, runRouterCommand(dir, mac, "2001:db8:100::/56"));

        List<String> changes = Files.readAllLines(calls).stream()
                .filter(line -> !line.startsWith("-o ")).toList();
        assertEquals(9, changes.size());
        assertTrue(changes.stream().allMatch(line -> line.contains("dev eth7")), changes.toString());
        assertEquals("-6 route replace 2001:db8:100::/56 dev eth7", changes.getLast());
    }

    @Test
    void routerRefusesAnUnknownMacWithoutChangingAnotherInterface(@TempDir Path dir) throws Exception {
        Path calls = fakeRouterIp(dir);

        assertEquals(3, runRouterCommand(dir, "02:42:0a:01:02:ff", "2001:db8::/56"));

        assertFalse(Files.readAllLines(calls).stream().anyMatch(line -> !line.startsWith("-o ")));
    }

    private static Path fakeRouterIp(Path dir) throws Exception {
        Path calls = dir.resolve("calls");
        Path ip = dir.resolve("ip");
        Files.writeString(ip, """
                #!/bin/sh
                printf '%s\\n' "$*" >> "$ROUTER_TEST_CALLS"
                case "$*" in
                  '-o link show')
                    printf '%s\\n' '2: eth0: <UP> mtu 1500 link/ether 02:42:00:00:00:01 brd ff:ff:ff:ff:ff:ff'
                    printf '%s\\n' '7: eth7@if21: <UP> mtu 1500 link/ether 02:42:0a:01:02:03 brd ff:ff:ff:ff:ff:ff'
                    ;;
                  '-o -4 addr show')
                    if [ ! -f "$ROUTER_TEST_FLUSHED" ]; then
                      printf '%s\\n' '7: eth7 inet 10.1.2.3/24 scope global eth7'
                    fi
                    ;;
                  '-4 addr flush dev eth7') touch "$ROUTER_TEST_FLUSHED" ;;
                esac
                """);
        assertTrue(ip.toFile().setExecutable(true));
        return calls;
    }

    private static int runRouterCommand(Path dir, String mac, String block) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(Ec2Ipv6VpcRouter.joinCommand(mac, block));
        builder.environment().put("PATH", dir + ":" + builder.environment().get("PATH"));
        builder.environment().put("ROUTER_TEST_CALLS", dir.resolve("calls").toString());
        builder.environment().put("ROUTER_TEST_FLUSHED", dir.resolve("flushed").toString());
        Process process = builder.redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "router command did not finish");
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
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
