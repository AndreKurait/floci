package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A launch's IPv6 address comes from its subnet's /64. Per
 * https://docs.aws.amazon.com/vpc/latest/userguide/subnet-sizing.html#subnet-sizing-ipv6 the first four
 * addresses of every IPv6 subnet are reserved, and an address is not handed out twice.
 */
class Ec2LaunchIpv6AddressTest {

    private static final String BLOCK = "2600:1f18:abcd:ef01::/64";

    @Test
    void addressesStayInsideTheBlockOutsideTheReservesAndAreCanonical() throws Exception {
        BigInteger network = new BigInteger(1, InetAddress.getByName("2600:1f18:abcd:ef01::").getAddress());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String address = Ec2Service.randomIpv6InBlock(BLOCK, seen);
            BigInteger value = new BigInteger(1, InetAddress.getByName(address).getAddress());
            assertEquals(network, value.shiftRight(64).shiftLeft(64), address);
            assertTrue(value.subtract(network).compareTo(BigInteger.valueOf(4)) >= 0, address);
            assertEquals(address.toLowerCase(), address);
            assertFalse(address.matches(".*:0[0-9a-f]+(:|$).*"), "leading zero in " + address);
            assertTrue(seen.add(address), "handed out twice: " + address);
        }
    }

    @Test
    void anAddressAlreadyHeldIsNeverReturned() {
        Set<String> held = new HashSet<>();
        String first = Ec2Service.randomIpv6InBlock(BLOCK, held);
        // The held set may spell the address differently; it is compared canonically.
        held.add(first.toUpperCase());
        for (int i = 0; i < 100; i++) {
            assertFalse(first.equals(Ec2Service.randomIpv6InBlock(BLOCK, held)));
        }
    }

    @Test
    void aBlockSmallerThanASlash64IsRefused() {
        assertThrows(AwsException.class, () -> Ec2Service.randomIpv6InBlock("2600:1f18:abcd:ef01::/80", Set.of()));
    }
}
