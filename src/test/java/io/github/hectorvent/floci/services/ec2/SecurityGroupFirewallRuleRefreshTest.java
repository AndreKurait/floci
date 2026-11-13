package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.PrefixListId;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGroupFirewallRuleRefreshTest extends SecurityGroupFirewallPeerFixture {

    @ParameterizedTest
    @CsvSource({"111111111111,us-east-1", "222222222222,us-east-1", "111111111111,us-west-2"})
    void unrelatedRuleChangesDoNotReinstallWorkingPolicies(String account, String region) {
        SecurityGroupFirewallManager manager = manager();
        for (int index = 0; index < 3; index++) {
            manager.register(endpoint(index), helper(index), Map.of());
        }
        assertFinal(3);
        installs.clear();

        SecurityGroup attached = endpoint(0).groups().getFirst();
        SecurityGroup unrelated = peer(3, account, region, "sg-unused").groups().getFirst();
        manager.refreshPolicies(account, region,
                Map.of(attached.getGroupId(), attached, unrelated.getGroupId(), unrelated), Map.of());

        assertTrue(installs.isEmpty(), "unchanged policies must not execute Docker nft writes");
        assertFinal(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ruleChangesReinstallOnlyAffectedPolicies(boolean egress) {
        SecurityGroupFirewallManager manager = manager();
        SecurityGroupNftCompiler.Endpoint first = endpoint(0);
        SecurityGroupNftCompiler.Endpoint other = peer(1, "222222222222", "us-east-1", "sg-b");
        manager.register(first, helper(0), Map.of());
        manager.register(other, helper(1), Map.of());
        String before = installed.get(helper(0));
        installs.clear();

        SecurityGroup group = first.groups().getFirst();
        if (egress) {
            group.setIpPermissionsEgress(List.of());
        } else {
            group.setIpPermissions(List.of());
        }
        manager.refreshPolicies(first.accountId(), first.region(), Map.of(group.getGroupId(), group), Map.of());

        assertEquals(List.of(helper(0)), touched());
        assertNotEquals(before, installed.get(helper(0)));
        assertPolicies(List.of(first, other), Map.of());
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void prefixListChangesReplaceTheOldAddresses() {
        SecurityGroupFirewallManager manager = manager();
        SecurityGroupNftCompiler.Endpoint first = endpoint(0);
        SecurityGroupNftCompiler.Endpoint other = peer(1, "222222222222", "us-east-1", "sg-b");
        IpPermission ingress = tcp();
        ingress.setPrefixListIds(List.of(new PrefixListId("pl-clients", "clients")));
        SecurityGroup group = first.groups().getFirst();
        group.setIpPermissions(List.of(ingress));
        manager.register(first, helper(0), Map.of("pl-clients", List.of("192.0.2.0/24")));
        manager.register(other, helper(1), Map.of());
        assertTrue(installed.get(helper(0)).contains("192.0.2.0/24"));
        installs.clear();

        Map<String, List<String>> updated = Map.of("pl-clients", List.of("198.51.100.0/24"));
        manager.refreshPolicies(first.accountId(), first.region(), Map.of(group.getGroupId(), group), updated);

        assertEquals(List.of(helper(0)), touched());
        assertFalse(installed.get(helper(0)).contains("192.0.2.0/24"));
        assertTrue(installed.get(helper(0)).contains("198.51.100.0/24"));
        assertPolicies(List.of(first, other), updated);
    }

    @Test
    void aRuleAdmitsOnlyTheReferencedPeerInItsVpc() {
        SecurityGroupFirewallManager manager = manager();
        SecurityGroupNftCompiler.Endpoint first = endpoint(0);
        SecurityGroupNftCompiler.Endpoint referenced = peer(1, first.accountId(), first.region(), "sg-b");
        SecurityGroupNftCompiler.Endpoint foreign = peer(2, "222222222222", first.region(), "sg-b");
        manager.register(first, helper(0), Map.of());
        manager.register(referenced, helper(1), Map.of());
        manager.register(foreign, helper(2), Map.of());
        String before = installed.get(helper(0));
        installs.clear();

        UserIdGroupPair reference = new UserIdGroupPair();
        reference.setGroupId("sg-b");
        reference.setUserId(referenced.accountId());
        IpPermission ingress = tcp();
        ingress.setUserIdGroupPairs(List.of(reference));
        SecurityGroup group = first.groups().getFirst();
        List<IpPermission> original = group.getIpPermissions();
        group.setIpPermissions(List.of(ingress));
        Map<String, SecurityGroup> groups = Map.of(group.getGroupId(), group,
                "sg-b", referenced.groups().getFirst());
        manager.refreshPolicies(first.accountId(), first.region(), groups, Map.of());

        assertEquals(List.of(helper(0)), touched());
        assertTrue(installed.get(helper(0)).contains("ip saddr 172.18.0.3 tcp dport 8080 accept"));
        assertFalse(installed.get(helper(0)).contains("ip saddr 172.18.0.4 tcp dport 8080 accept"));
        assertTrue(installed.get(helper(0)).contains("ip saddr 172.18.0.4 drop"));
        assertPolicies(List.of(first, referenced, foreign), Map.of());
        group.setIpPermissions(original);
        manager.refreshPolicies(first.accountId(), first.region(), groups, Map.of());
        assertEquals(before, installed.get(helper(0)));
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void anInstallFailureQuarantinesPeersAndTheNextRefreshRestoresTheirPolicies() {
        SecurityGroupFirewallManager manager = manager();
        SecurityGroupNftCompiler.Endpoint first = endpoint(0);
        SecurityGroupNftCompiler.Endpoint other = peer(1, "222222222222", "us-east-1", "sg-b");
        manager.register(first, helper(0), Map.of());
        manager.register(other, helper(1), Map.of());
        SecurityGroup group = first.groups().getFirst();
        group.setIpPermissions(List.of());
        failNextInstall.add(helper(0));

        assertThrows(IllegalStateException.class, () -> manager.refreshPolicies(
                first.accountId(), first.region(), Map.of(group.getGroupId(), group), Map.of()));
        for (int index = 0; index < 2; index++) {
            assertEquals("flush chain inet floci_sg ingress\nflush chain inet floci_sg egress\n",
                    installed.get(helper(index)));
        }
        installs.clear();
        manager.refreshPolicies(first.accountId(), first.region(), Map.of(group.getGroupId(), group), Map.of());

        assertEquals(Set.of(helper(0), helper(1)), Set.copyOf(touched()));
        assertPolicies(List.of(first, other), Map.of());
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void anUnrelatedRefreshStillRetiresAStoppedHelperAndItsPeerPermissions() {
        SecurityGroupFirewallManager manager = manager();
        for (int index = 0; index < 3; index++) {
            manager.register(endpoint(index), helper(index), Map.of());
        }
        assertFinal(3);
        stoppedHelpers.add(helper(2));

        manager.refreshPolicies("222222222222", "us-east-1", Map.of(), Map.of());

        assertFinal(2);
        assertFalse(installed.get(helper(0)).contains(" 172.18.0.4 "));
        assertFalse(installed.get(helper(1)).contains(" 172.18.0.4 "));
    }

    @Test
    void explicitReconciliationStillReinstallsUnchangedPolicies() {
        SecurityGroupFirewallManager manager = manager();
        for (int index = 0; index < 2; index++) {
            manager.register(endpoint(index), helper(index), Map.of());
        }
        installed.put(helper(0), "externally changed firewall");
        installs.clear();

        manager.reconcileAll();

        assertEquals(Set.of(helper(0), helper(1)), Set.copyOf(touched()));
        assertFinal(2);
    }

    private SecurityGroupNftCompiler.Endpoint peer(int index, String account, String region, String groupId) {
        SecurityGroupNftCompiler.Endpoint original = endpoint(index);
        SecurityGroup group = original.groups().getFirst();
        group.setOwnerId(account);
        group.setGroupId(groupId);
        return new SecurityGroupNftCompiler.Endpoint(account, region, original.vpcId(), original.eniId(),
                original.logicalAddress(), original.transportAddress(), Set.of(groupId), List.of(group));
    }

    private List<String> touched() {
        return installs.stream().map(install -> install[0]).toList();
    }

    private void assertPolicies(List<SecurityGroupNftCompiler.Endpoint> peers,
                                Map<String, List<String>> prefixLists) {
        for (int index = 0; index < peers.size(); index++) {
            assertEquals(canonical(SecurityGroupNftCompiler.compile(
                            peers.get(index), peers, prefixLists, List.of(), false)),
                    canonical(installed.get(helper(index))));
        }
    }

    private static IpPermission tcp() {
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(8080);
        permission.setToPort(8080);
        return permission;
    }
}
