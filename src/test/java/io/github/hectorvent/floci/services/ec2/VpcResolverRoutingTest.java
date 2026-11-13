package io.github.hectorvent.floci.services.ec2;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

class VpcResolverRoutingTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"resolver.example", "172.18.0.2; touch /tmp/escape",
            "127.0.0.1", "0.0.0.0", "169.254.169.253", "169.254.169.254",
            "224.0.0.1", "255.255.255.255", "::1", "fd00:ec2::253", "172.18.0.256"})
    void rejectsNonliteralOrNonremoteResolverTargets(String target) {
        assertThrows(IllegalArgumentException.class, () -> VpcResolverRouting.commands(target));
    }
}
