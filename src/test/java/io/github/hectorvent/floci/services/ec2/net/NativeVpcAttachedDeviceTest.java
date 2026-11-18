package io.github.hectorvent.floci.services.ec2.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The namespace commands that give a hot-attached interface its own native device and return path. */
class NativeVpcAttachedDeviceTest {

    private static final Cidr4 VPC = Cidr4.parse("172.31.0.0/16").orElseThrow();
    private static final Cidr4 SUBNET = Cidr4.parse("172.31.0.0/20").orElseThrow();

    @Test
    void theDeviceGetsItsAddressItsOwnTableAndASourceRuleButNoConnectedRouteInMain() {
        String commands = NativeVpcAddressSpace.attachedCommands("eth2", "172.31.5.9", SUBNET, VPC, 10_007, false);
        assertTrue(commands.contains("ip -4 address replace 172.31.5.9/20 dev eth2 noprefixroute\n"));
        assertTrue(commands.contains("ip -4 route replace 172.31.0.0/16 dev eth2 src 172.31.5.9 proto 185 table 10007\n"));
        assertTrue(commands.contains("while ip -4 rule del priority 10007 2>/dev/null; do :; done\n"
                + "ip -4 rule add priority 10007 from 172.31.5.9/32 lookup 10007\n"));
        assertFalse(commands.lines().anyMatch(line -> line.startsWith("ip -4 route replace") && !line.contains(" table ")));
        // Its own filter table: the primary interface's floci_vpc_address_space is never flushed.
        assertTrue(commands.contains("add table inet floci_vpc_attached_7\n"));
        assertFalse(commands.contains("floci_vpc_address_space"));
        assertTrue(commands.contains("add rule inet floci_vpc_attached_7 forwarding iifname \"eth2\" drop\n"));
        assertTrue(commands.contains("add rule inet floci_vpc_attached_7 forwarding oifname \"eth2\" drop\n"));
    }

    @Test
    void aVpcRangeNoOtherRouteCoversAlsoGoesInTheMainTable() {
        String commands = NativeVpcAddressSpace.attachedCommands("eth2", "172.31.5.9", SUBNET, VPC, 10_007, true);
        assertTrue(commands.endsWith("ip -4 route replace 172.31.0.0/16 dev eth2 src 172.31.5.9 proto 185\n"), commands);
    }

    @Test
    void theMainTableTakesTheVpcRangeOnlyWhenNoOtherDevicesRouteOverlapsIt() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode overlapping = json.readTree("""
                [{"dst":"default","gateway":"172.18.0.1","dev":"eth0"},
                 {"dst":"172.18.0.0/16","dev":"eth0"},
                 {"dst":"172.31.0.0/16","dev":"eth1","prefsrc":"172.31.1.10"}]""");
        assertFalse(NativeVpcAddressSpace.mainRouteFree(overlapping, VPC, "eth2"));
        JsonNode separate = json.readTree("""
                [{"dst":"default","gateway":"172.18.0.1","dev":"eth0"},
                 {"dst":"172.18.0.0/16","dev":"eth0"},
                 {"dst":"10.1.0.0/16","dev":"eth1"},
                 {"dst":"172.31.0.0/16","dev":"eth2"}]""");
        assertTrue(NativeVpcAddressSpace.mainRouteFree(separate, VPC, "eth2"));
    }

    @Test
    void theDeviceTakesTheInterfacesOwnMacWhereDockerDoesNotApplyIt() {
        assertEquals("""
                set -eu
                ip link set dev eth2 down
                ip link set dev eth2 address 02:11:22:33:44:55
                ip link set dev eth2 up
                """, NativeVpcAddressSpace.macCommands("eth2", "02:11:22:33:44:55"));
        assertThrows(IllegalArgumentException.class, () -> NativeVpcAddressSpace.macCommands("eth2", "02:11:22:33:44"));
        assertThrows(IllegalArgumentException.class,
                () -> NativeVpcAddressSpace.macCommands("eth2", "02:11:22:33:44:55; reboot"));
    }

    @Test
    void attachedCommandsRefuseWhatIsNotTheInterfacesOwn() {
        assertThrows(IllegalArgumentException.class,
                () -> NativeVpcAddressSpace.attachedCommands("eth2", "10.0.0.9", SUBNET, VPC, 10_007, false));
        assertThrows(IllegalArgumentException.class,
                () -> NativeVpcAddressSpace.attachedCommands("eth2", "172.31.5.9", SUBNET, VPC, 254, false));
        assertThrows(IllegalArgumentException.class,
                () -> NativeVpcAddressSpace.attachedCommands("eth2; reboot", "172.31.5.9", SUBNET, VPC, 10_007, false));
    }

    @Test
    void theRouteTableFollowsTheDevicesIndexAndDetachRemovesWhatAttachAdded() throws Exception {
        JsonNode links = new ObjectMapper().readTree("""
                [{"ifindex":1,"ifname":"lo","address":"00:00:00:00:00:00"},
                 {"ifindex":5,"ifname":"eth0","address":"02:42:ac:12:00:02"},
                 {"ifindex":7,"ifname":"eth2","address":"02:11:22:33:44:55"}]""");
        assertEquals(10_007, NativeVpcAddressSpace.routeTable(links, "eth2"));
        assertThrows(IllegalStateException.class, () -> NativeVpcAddressSpace.routeTable(links, "eth9"));
        String detach = NativeVpcAddressSpace.detachedCommands("eth2", "172.31.5.9", 10_007);
        assertTrue(detach.contains("while ip -4 rule del priority 10007 2>/dev/null; do :; done\n"));
        assertTrue(detach.contains("ip -4 route flush table 10007\n"));
        assertTrue(detach.contains("nft delete table inet floci_vpc_attached_7"));
        assertFalse(detach.contains("floci_vpc_address_space"));
    }
}
