package io.github.hectorvent.floci.services.ec2;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2MetadataRoutingTest {

    @ParameterizedTest
    @CsvSource({
        "172.17.0.1, 172.17.0.1",
        "240.10.0.1, 240.10.0.1",
        "host.docker.internal, 240.10.0.1",
        "255.255.255.254, 255.255.255.254"
    })
    void routedDockerHostReachesFirewallInspection(String host, String address, @TempDir Path directory)
            throws Exception {
        Map<String, String> environment = tools(directory, address,
                address + " dev eth0 src 240.10.0.2");

        int result = HelperShellFixture.run(Ec2MetadataProxy.ec2RoutingCommand(host, 9169, false),
                directory, environment);

        assertEquals(43, result, HelperShellFixture.output(directory));
        assertEquals("-w 2 -t nat -S OUTPUT\n", Files.readString(directory.resolve("firewall")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0.1.2.3", "127.0.0.2", "169.254.169.254", "224.0.0.1", "239.255.255.255",
        "255.255.255.255", "240.10.0.2", "256.10.0.1", "240.010.0.1"
    })
    void unsuitableAddressFailsBeforeFirewallInspection(String address, @TempDir Path directory)
            throws Exception {
        Map<String, String> environment = tools(directory, address,
                address + " dev eth0 src 240.10.0.2");

        int result = HelperShellFixture.run(Ec2MetadataProxy.ec2RoutingCommand(address, 9169, false),
                directory, environment);

        assertEquals(1, result, HelperShellFixture.output(directory));
        assertFalse(Files.exists(directory.resolve("firewall")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"broadcast", "local", "multicast"})
    void nonUnicastRouteFailsBeforeFirewallInspection(String routeType, @TempDir Path directory)
            throws Exception {
        Map<String, String> environment = tools(directory, "172.17.0.255",
                routeType + " 172.17.0.255 dev eth0 src 240.10.0.2");

        int result = HelperShellFixture.run(Ec2MetadataProxy.ec2RoutingCommand("172.17.0.255", 9169, false),
                directory, environment);

        assertEquals(1, result, HelperShellFixture.output(directory));
        assertTrue(HelperShellFixture.output(directory).contains("remote unicast route"));
        assertFalse(Files.exists(directory.resolve("firewall")));
    }

    private static Map<String, String> tools(Path directory, String resolvedAddress, String route)
            throws Exception {
        Path bin = Files.createDirectory(directory.resolve("bin"));
        HelperShellFixture.writeTool(bin, "getent",
                "printf '%s STREAM host.docker.internal\\n' \"$TEST_RESOLVED_ADDRESS\"\n");
        HelperShellFixture.writeTool(bin, "ip", """
                case "$*" in
                  '-4 -o addr show'|'-4 -o addr show dev eth0')
                    printf '2: eth0 inet 240.10.0.2/24 scope global eth0\\n' ;;
                  '-4 route get '*) printf '%s\\n' "$TEST_ROUTE" ;;
                  '-4 route show table main exact 169.254.169.254/32') : ;;
                  *) echo "Unexpected ip command: $*" >&2; exit 99 ;;
                esac
                """);
        // Stop at the first firewall inspection; this fixture never changes host networking.
        HelperShellFixture.writeTool(bin, "iptables",
                "printf '%s\\n' \"$*\" > \"$TEST_FIREWALL\"\nexit 43\n");
        return Map.of("PATH", bin + ":" + System.getenv("PATH"),
                "TEST_RESOLVED_ADDRESS", resolvedAddress,
                "TEST_ROUTE", route,
                "TEST_FIREWALL", directory.resolve("firewall").toString());
    }
}
