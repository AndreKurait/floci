package io.github.hectorvent.floci.services.ec2;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Registration bursts refresh peer policies once, ending exactly where a full reconcile would. */
class SecurityGroupFirewallPeerRefreshTest extends SecurityGroupFirewallPeerFixture {

    @Test
    void aConcurrentRegistrationBurstRefreshesPeersOnceInsteadOfOncePerRegistration() throws Exception {
        SecurityGroupFirewallManager manager = manager();
        int existing = 8;
        for (int index = 0; index < existing; index++) {
            manager.register(endpoint(index), helper(index), Map.of());
        }
        // Setup holds: every existing endpoint enforces its complete policy before the burst.
        assertFinal(existing);
        installs.clear();

        int burst = 8;
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        synchronized (manager) {
            for (int index = existing; index < existing + burst; index++) {
                int id = index;
                Thread thread = Thread.ofPlatform().start(() -> {
                    try {
                        manager.register(endpoint(id), helper(id), Map.of());
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
                threads.add(thread);
            }
            // Setup holds: the whole burst is queued on the firewall monitor before any of it runs.
            await().atMost(5, TimeUnit.SECONDS).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10)).until(() ->
                    threads.stream().allMatch(thread -> thread.getState() == Thread.State.BLOCKED));
        }
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(thread.isAlive(), "a registration did not return");
        }
        assertTrue(failures.isEmpty(), () -> "registrations failed: " + failures);

        int total = existing + burst;
        // Each registration installs its own policy; a coalesced pass then refreshes everyone at
        // most twice (learn the newcomers, then accept them), and the bound allows a second pass
        // on a slow host. Per-registration full reconciles cost the sum of the growing endpoint
        // count instead: 8 registrations over 8 peers install 100.
        int bound = burst + 4 * total;
        assertTrue(installs.size() <= bound,
                "a burst of " + burst + " over " + existing + " peers installed " + installs.size()
                        + " policies; a coalesced refresh needs at most " + bound);
        assertFinal(total);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void aSingleRegistrationWithoutPeersInstallsOnePolicy() {
        // Passes with or without the defect: there are no peers to refresh.
        SecurityGroupFirewallManager manager = manager();
        manager.register(endpoint(0), helper(0), Map.of());
        assertEquals(1, installs.size());
        assertFinal(1);
    }

    @Test
    void peersEndWithExactlyTheRulesAFullReconcileGivesThem() throws Exception {
        SecurityGroupFirewallManager manager = manager();
        for (int index = 0; index < 3; index++) {
            manager.register(endpoint(index), helper(index), Map.of());
        }
        manager.register(endpoint(3), helper(3), Map.of());
        assertFinal(4);

        Map<String, String> coalesced = new LinkedHashMap<>(installed);
        installs.clear();
        manager.reconcileAll();
        for (int index = 0; index < 4; index++) {
            assertEquals(coalesced.get(helper(index)), installed.get(helper(index)),
                    "helper " + index + " must already hold the full-reconcile policy");
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }
    @Test
    void aRegistrationThatFailsAfterItsOwnInstallLeavesPeersWithoutItsIdentity() {
        SecurityGroupFirewallManager manager = manager();
        manager.register(endpoint(0), helper(0), Map.of());
        manager.register(endpoint(1), helper(1), Map.of());
        // Setup holds: both peers enforce their complete policies.
        assertFinal(2);

        // The coalesced pass fails on a peer, so the newcomer's registration fails after its own install.
        failNextInstall.add(helper(0));
        assertThrows(IllegalStateException.class, () -> manager.register(endpoint(2), helper(2), Map.of()));

        // The reverted newcomer is forgotten and the quarantined peers converge again without it.
        await().atMost(10, TimeUnit.SECONDS)
                .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> assertFinal(2));
        for (int index = 0; index < 2; index++) {
            assertFalse(installed.get(helper(index)).contains(" 172.18.0.4 "),
                    "peer " + index + " still enforces the failed registration's address");
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }
}
