package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.SecurityGroup;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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


    @Test
    void groupReplacementPreservesDualStackEndpointIdentity() throws ReflectiveOperationException {
        SecurityGroupFirewallManager manager = manager();
        SecurityGroupNftCompiler.Endpoint original = dualStackEndpoint();
        manager.register(original, helper(0), Map.of());
        SecurityGroup replacement = new SecurityGroup();
        replacement.setGroupId("sg-replacement");

        manager.updateGroups(original.eniId(), Set.of("sg-replacement"),
                Map.of("sg-replacement", replacement), Map.of());

        assertEquals(original.withPolicy(Set.of("sg-replacement"), List.of(replacement)),
                storedEndpoint(manager, original.eniId()));
    }

    @Test
    void policyRefreshPreservesDualStackEndpointIdentity() throws ReflectiveOperationException {
        SecurityGroupFirewallManager manager = manager();
        SecurityGroupNftCompiler.Endpoint original = dualStackEndpoint();
        manager.register(original, helper(0), Map.of());
        SecurityGroup replacement = new SecurityGroup();
        replacement.setGroupId("sg-a");

        manager.refreshPolicies(original.accountId(), original.region(), Map.of("sg-a", replacement), Map.of());

        assertEquals(original.withPolicy(original.groupIds(), List.of(replacement)),
                storedEndpoint(manager, original.eniId()));
    }

    @Test
    void attachedPolicyCopyPreservesDualStackEndpointIdentity() throws ReflectiveOperationException {
        SecurityGroupNftCompiler.Endpoint original = dualStackEndpoint();
        SecurityGroup replacement = new SecurityGroup();
        replacement.setGroupId("sg-replacement");
        Method copy = SecurityGroupFirewallManager.class.getDeclaredMethod("withGroups",
                SecurityGroupNftCompiler.Endpoint.class, Set.class, List.class);
        copy.setAccessible(true);

        assertEquals(original.withPolicy(Set.of("sg-replacement"), List.of(replacement)),
                copy.invoke(null, original, Set.of("sg-replacement"), List.of(replacement)));
    }

    private SecurityGroupNftCompiler.Endpoint dualStackEndpoint() {
        SecurityGroupNftCompiler.Endpoint original = endpoint(0);
        SecurityGroupNftCompiler.ControlConnection control =
                new SecurityGroupNftCompiler.ControlConnection("192.0.2.10", 443);
        return new SecurityGroupNftCompiler.Endpoint(original.accountId(), original.region(), original.vpcId(),
                original.eniId(), original.logicalAddress(), List.of("2001:db8::10"), original.transportAddress(),
                "2001:db8:1::10", original.groupIds(), original.groups(), List.of(control), true,
                Set.of("192.0.2.11"), List.of(), Map.of("peer.example", control));
    }

    private static SecurityGroupNftCompiler.Endpoint storedEndpoint(SecurityGroupFirewallManager manager,
                                                                    String eniId)
            throws ReflectiveOperationException {
        Field endpoints = SecurityGroupFirewallManager.class.getDeclaredField("endpoints");
        endpoints.setAccessible(true);
        Map<?, ?> stored = (Map<?, ?>) endpoints.get(manager);
        Object protectedEndpoint = stored.get(eniId);
        Method accessor = protectedEndpoint.getClass().getDeclaredMethod("endpoint");
        accessor.setAccessible(true);
        return (SecurityGroupNftCompiler.Endpoint) accessor.invoke(protectedEndpoint);
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
