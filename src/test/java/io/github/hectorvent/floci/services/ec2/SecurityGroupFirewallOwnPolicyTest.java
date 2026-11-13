package io.github.hectorvent.floci.services.ec2;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The own-policy registration used by the Lambda DNS exception never opens a peer early. */
class SecurityGroupFirewallOwnPolicyTest extends SecurityGroupFirewallPeerFixture {

    @Test
    void anOwnPolicyRegistrationWithholdsAcceptsFromPeersThatDoNotYetKnowIt() {
        SecurityGroupFirewallManager manager = manager();
        manager.register(endpoint(0), helper(0), Map.of());
        manager.register(endpoint(1), helper(1), Map.of());
        String peerBefore = installed.get(helper(0));
        // Setup holds: the existing peers accept each other through the shared group.
        assertTrue(PEER_ACCEPT.matcher(peerBefore).find(), "the peers' group must produce peer accepts");

        String own;
        synchronized (manager) {
            // Holding the (reentrant) monitor keeps the coalesced refresh out until this is checked.
            manager.register(endpoint(2), helper(2), Map.of(), false);
            own = installed.get(helper(2));
        }
        assertTrue(own.contains("172.18.0.2 drop") && own.contains("172.18.0.3 drop"),
                "the newcomer must still drop its peers' transport addresses:\n" + own);
        Matcher accept = PEER_ACCEPT.matcher(own);
        while (accept.find()) {
            assertFalse(Set.of("172.18.0.2", "172.18.0.3").contains(accept.group(1)),
                    "the newcomer accepts a peer that does not know it yet:\n" + own);
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));

        await().atMost(10, TimeUnit.SECONDS)
                .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> assertFinal(3));
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void theOwnPolicyRegistrationTouchesOnlyItsOwnHelperBeforeReturning() {
        SecurityGroupFirewallManager manager = manager();
        for (int index = 0; index < 3; index++) {
            manager.register(endpoint(index), helper(index), Map.of());
        }
        // Setup holds: the peers enforce their complete policies before the newcomer arrives.
        assertFinal(3);
        Map<String, String> peers = new LinkedHashMap<>(installed);
        int before = installs.size();

        synchronized (manager) {
            manager.register(endpoint(3), helper(3), Map.of(), false);
            List<String> touched = installs.subList(before, installs.size()).stream()
                    .map(install -> install[0]).toList();
            assertEquals(List.of(helper(3)), touched, "only the newcomer's own policy is installed");
        }
        await().atMost(10, TimeUnit.SECONDS)
                .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> assertFinal(4));
        for (int index = 0; index < 3; index++) {
            assertFalse(peers.get(helper(index)).equals(installed.get(helper(index))),
                    "peer " + index + " learns the newcomer in the coalesced refresh");
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }
}
