package io.floci.aws.services.ec2;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGroupFirewallIpv6UpdateTest extends SecurityGroupFirewallPeerFixture {

    @Test
    void addressReplacementAndRemovalPreserveNativeIdentity() throws ReflectiveOperationException {
        SecurityGroupFirewallManager manager = manager();
        SecurityGroupNftCompiler.Endpoint original = dualStackEndpoint();
        manager.register(original, helper(0), Map.of());
        List<String> addresses = new ArrayList<>(List.of("2001:db8::20", "2001:db8::21"));

        manager.updateIpv6Addresses(original.eniId(), addresses);
        addresses.clear();

        assertEquals(original.withIpv6Addresses(List.of("2001:db8::20", "2001:db8::21")),
                storedEndpoint(manager, original.eniId()));
        String updated = installed.get(helper(0));
        manager.reconcileAll();
        assertEquals(updated, installed.get(helper(0)));
        manager.updateIpv6Addresses(original.eniId(), List.of());
        assertEquals(original.withIpv6Addresses(List.of()), storedEndpoint(manager, original.eniId()));
    }

    @Test
    void failedUpdateRestoresIdentityAndQuarantinesPeersUntilRecovery() throws ReflectiveOperationException {
        SecurityGroupFirewallManager manager = manager();
        SecurityGroupNftCompiler.Endpoint original = dualStackEndpoint();
        manager.register(original, helper(0), Map.of());
        manager.register(endpoint(1), helper(1), Map.of());
        Map<String, String> before = Map.copyOf(installed);
        failNextInstall.add(helper(0));

        assertThrows(IllegalStateException.class,
                () -> manager.updateIpv6Addresses(original.eniId(), List.of("2001:db8::20")));

        assertEquals(original, storedEndpoint(manager, original.eniId()));
        for (String helper : List.of(helper(0), helper(1))) {
            assertEquals("flush chain inet floci_sg ingress\nflush chain inet floci_sg egress\n", installed.get(helper));
        }
        manager.reconcileAll();
        assertEquals(before, installed);
        manager.updateIpv6Addresses(original.eniId(), List.of("2001:db8::20"));
        assertEquals(original.withIpv6Addresses(List.of("2001:db8::20")),
                storedEndpoint(manager, original.eniId()));
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void absentInterfaceDoesNotChangePolicies() {
        SecurityGroupFirewallManager manager = manager();
        installs.clear();
        manager.updateIpv6Addresses("eni-absent", List.of("2001:db8::20"));
        assertTrue(installs.isEmpty());
    }

    @Test
    void stoppedHelperIsNotResurrectedByAddressUpdate() throws ReflectiveOperationException {
        SecurityGroupFirewallManager manager = manager();
        SecurityGroupNftCompiler.Endpoint original = dualStackEndpoint();
        manager.register(original, helper(0), Map.of());
        stoppedHelpers.add(helper(0));

        manager.updateIpv6Addresses(original.eniId(), List.of("2001:db8::20"));

        Field field = SecurityGroupFirewallManager.class.getDeclaredField("endpoints");
        field.setAccessible(true);
        assertFalse(((Map<?, ?>) field.get(manager)).containsKey(original.eniId()));
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

}
