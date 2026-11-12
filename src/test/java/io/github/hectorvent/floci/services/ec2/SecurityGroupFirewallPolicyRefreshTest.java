package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGroupFirewallPolicyRefreshTest extends SecurityGroupFirewallPeerFixture {

    @Test
    void anUnattachedGroupChangeDoesNotRewriteLivePolicies() {
        SecurityGroupFirewallManager manager = readyPair();
        Map<String, String> before = new LinkedHashMap<>(installed);
        SecurityGroup unused = new SecurityGroup();
        unused.setGroupId("sg-unused");
        installs.clear();

        manager.refreshPolicies("111111111111", "us-east-1",
                Map.of("sg-a", endpoint(0).groups().getFirst(), "sg-unused", unused), Map.of());

        assertTrue(installs.isEmpty(), "unaffected policies must not perform Docker writes");
        assertEquals(before, installed);
        assertFinal(2);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void anotherAccountOrRegionDoesNotRewriteThesePolicies() {
        SecurityGroupFirewallManager manager = readyPair();
        installs.clear();

        manager.refreshPolicies("222222222222", "us-east-1", Map.of(), Map.of());
        manager.refreshPolicies("111111111111", "eu-west-1", Map.of(), Map.of());

        assertTrue(installs.isEmpty(), "unrelated account and region policies must remain installed");
        assertFinal(2);
    }

    @Test
    void revocationChangesPoliciesAndAnIdenticalRefreshDoesNotRewriteThem() {
        SecurityGroupFirewallManager manager = readyPair();
        SecurityGroup revoked = endpoint(0).groups().getFirst();
        revoked.setIpPermissions(List.of());
        Map<String, SecurityGroup> groups = Map.of("sg-a", revoked);
        installs.clear();

        manager.refreshPolicies("111111111111", "us-east-1", groups, Map.of());

        assertEquals(Set.of(helper(0), helper(1)), installs.stream()
                .map(install -> install[0]).collect(Collectors.toSet()));
        assertRevoked();
        Map<String, String> changed = new LinkedHashMap<>(installed);
        installs.clear();
        manager.refreshPolicies("111111111111", "us-east-1", groups, Map.of());
        assertTrue(installs.isEmpty(), "an identical policy refresh must perform no Docker writes");

        manager.reconcileAll();
        assertEquals(Set.of(helper(0), helper(1)), installs.stream()
                .map(install -> install[0]).collect(Collectors.toSet()),
                "explicit recovery must still reinstall every policy");
        assertEquals(changed, installed, "incremental refresh must produce the full reconciliation's rules");
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void failedRevocationQuarantinesEveryPeerAndTheNextRefreshRecovers() {
        SecurityGroupFirewallManager manager = readyPair();
        SecurityGroup revoked = endpoint(0).groups().getFirst();
        revoked.setIpPermissions(List.of());
        Map<String, SecurityGroup> groups = Map.of("sg-a", revoked);
        failNextInstall.add(helper(0));

        assertThrows(IllegalStateException.class,
                () -> manager.refreshPolicies("111111111111", "us-east-1", groups, Map.of()));
        for (int index = 0; index < 2; index++) {
            assertEquals("flush chain inet floci_sg ingress\nflush chain inet floci_sg egress\n",
                    installed.get(helper(index)), "a failed policy change must quarantine every peer");
        }

        manager.refreshPolicies("111111111111", "us-east-1", groups, Map.of());
        assertRevoked();
        Map<String, String> recovered = new LinkedHashMap<>(installed);
        manager.reconcileAll();
        assertEquals(recovered, installed);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    private SecurityGroupFirewallManager readyPair() {
        SecurityGroupFirewallManager manager = manager();
        manager.register(endpoint(0), helper(0), Map.of());
        manager.register(endpoint(1), helper(1), Map.of());
        assertFinal(2);
        return manager;
    }

    private void assertRevoked() {
        for (int index = 0; index < 2; index++) {
            String rules = installed.get(helper(index));
            assertFalse(rules.lines().anyMatch(line ->
                    line.startsWith("add rule inet floci_sg ingress ip saddr 172.18.")
                            && line.endsWith(" accept")), "revoked peer ingress must not remain accepted");
            assertFalse(rules.lines().anyMatch(line ->
                    line.contains("tcp dport 22") && line.endsWith(" accept")),
                    "revoked SSH ingress must not remain accepted");
        }
    }
}
