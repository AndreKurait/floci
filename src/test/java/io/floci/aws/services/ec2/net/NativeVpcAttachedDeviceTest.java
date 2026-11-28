package io.floci.aws.services.ec2.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

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
        // The kernel's main rule: a source rule at or after it would never be consulted.
        assertThrows(IllegalArgumentException.class,
                () -> NativeVpcAddressSpace.attachedCommands("eth2", "172.31.5.9", SUBNET, VPC, 32_766, false));
        assertThrows(IllegalArgumentException.class,
                () -> NativeVpcAddressSpace.attachedCommands("eth2; reboot", "172.31.5.9", SUBNET, VPC, 10_007, false));
    }

    @Test
    void anAddressKeepsTheTableItsRuleNamesAndANewOneTakesTheLowestFree() throws Exception {
        ObjectMapper json = new ObjectMapper();
        // As `ip -j -4 rule show` prints them: a /32 source carries no srclen.
        JsonNode kernel = json.readTree("""
                [{"priority":0,"src":"all","table":"local"},
                 {"priority":32766,"src":"all","table":"main"},
                 {"priority":32767,"src":"all","table":"default"}]""");
        assertEquals(10_001, NativeVpcAddressSpace.routeTable(kernel, "172.31.5.9"));
        assertEquals(OptionalInt.empty(), NativeVpcAddressSpace.heldRouteTable(kernel, "172.31.5.9"));
        JsonNode held = json.readTree("""
                [{"priority":0,"src":"all","table":"local"},
                 {"priority":10001,"src":"172.31.7.7","table":"10001"},
                 {"priority":10003,"src":"172.31.5.9","table":"10003"},
                 {"priority":10004,"src":"172.31.6.0","srclen":24,"table":"10004"},
                 {"priority":32766,"src":"all","table":"main"},
                 {"priority":32767,"src":"all","table":"default"}]""");
        assertEquals(10_003, NativeVpcAddressSpace.routeTable(held, "172.31.5.9"));
        assertEquals(OptionalInt.of(10_003), NativeVpcAddressSpace.heldRouteTable(held, "172.31.5.9"));
        assertEquals(10_002, NativeVpcAddressSpace.routeTable(held, "172.31.8.8"));
        assertEquals(OptionalInt.empty(), NativeVpcAddressSpace.heldRouteTable(held, "172.31.6.0"));
        assertEquals(OptionalInt.empty(), NativeVpcAddressSpace.heldRouteTable(held, null));
    }

    @Test
    void aFullPoolRefusesAnotherAddressAndATableFreedOnDetachIsTakenAgain() {
        ArrayNode rules = new ObjectMapper().createArrayNode();
        for (int table = 10_001; table <= 32_765; table++) {
            rules.addObject().put("priority", table).put("src", "10.0.%d.%d".formatted(table / 256, table % 256))
                    .put("table", Integer.toString(table));
        }
        assertThrows(IllegalStateException.class, () -> NativeVpcAddressSpace.routeTable(rules, "172.31.5.9"));
        rules.remove(5);
        assertEquals(10_006, NativeVpcAddressSpace.routeTable(rules, "172.31.5.9"));
    }

    @Test
    void detachRemovesWhatAttachAdded() {
        String detach = NativeVpcAddressSpace.detachedCommands("eth2", "172.31.5.9", 10_003);
        assertTrue(detach.contains("while ip -4 rule del priority 10003 2>/dev/null; do :; done\n"));
        assertTrue(detach.contains("ip -4 route flush table 10003\n"));
        assertTrue(detach.contains("nft delete table inet floci_vpc_attached_3"));
        assertFalse(detach.contains("floci_vpc_address_space"));
    }
}
