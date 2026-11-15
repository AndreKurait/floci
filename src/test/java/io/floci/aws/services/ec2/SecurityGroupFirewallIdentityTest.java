package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.SecurityGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGroupFirewallIdentityTest extends SecurityGroupFirewallPeerFixture {
    @Test
    void replacementAccountWithdrawsOldPeerAccess() {
        SecurityGroupFirewallManager manager = manager();
        manager.register(endpoint(0), helper(0), Map.of());
        manager.register(endpoint(1), helper(1), Map.of());
        SecurityGroupNftCompiler.Endpoint old = endpoint(0);
        SecurityGroup group = old.groups().getFirst();
        group.setOwnerId("222222222222");
        SecurityGroupNftCompiler.Endpoint replacement = new SecurityGroupNftCompiler.Endpoint(
                "222222222222", old.region(), old.vpcId(), old.eniId(), old.logicalAddress(),
                old.transportAddress(), old.groupIds(), List.of(group));
        manager.register(replacement, helper(0), Map.of());
        assertNoPeerAccept(helper(0), endpoint(1).transportAddress());
        assertNoPeerAccept(helper(1), replacement.transportAddress());
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void reusedTransportAddressDoesNotInheritRetiredGroupAccess() {
        SecurityGroupFirewallManager manager = manager();
        manager.register(endpoint(0), helper(0), Map.of());
        manager.register(endpoint(1), helper(1), Map.of());
        manager.unregister("eni-0");
        SecurityGroupNftCompiler.Endpoint old = endpoint(0);
        SecurityGroup group = old.groups().getFirst();
        group.setGroupId("sg-replacement");
        group.setIpPermissions(List.of());
        group.setIpPermissionsEgress(List.of());
        SecurityGroupNftCompiler.Endpoint replacement = new SecurityGroupNftCompiler.Endpoint(
                old.accountId(), old.region(), old.vpcId(), "eni-replacement", old.logicalAddress(),
                old.transportAddress(), Set.of(group.getGroupId()), List.of(group));
        manager.register(replacement, helper(0), Map.of());
        assertNoPeerAccept(helper(0), endpoint(1).transportAddress());
        assertFalse(installed.get(helper(1)).lines().anyMatch(line ->
                line.startsWith("add rule inet floci_sg ingress ip saddr " + replacement.transportAddress() + " ")
                        && line.endsWith(" accept")));
        assertTrue(installed.get(helper(1)).contains(" " + replacement.transportAddress() + " drop"));
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void groupRevocationReachesEveryStableEndpoint() {
        SecurityGroupFirewallManager manager = manager();
        for (int index = 0; index < 6; index++) {
            manager.register(endpoint(index), helper(index), Map.of());
        }
        SecurityGroup group = endpoint(0).groups().getFirst();
        group.setIpPermissions(List.of());
        group.setIpPermissionsEgress(List.of());
        manager.refreshPolicies("us-east-1", Map.of(group.getGroupId(), group), Map.of());
        for (int index = 0; index < 6; index++) {
            assertFalse(PEER_ACCEPT.matcher(installed.get(helper(index))).find());
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    private void assertNoPeerAccept(String helper, String address) {
        Matcher matcher = PEER_ACCEPT.matcher(installed.get(helper));
        while (matcher.find()) {
            assertFalse(matcher.group(1).equals(address), helper + " retains access to " + address);
        }
    }
}
