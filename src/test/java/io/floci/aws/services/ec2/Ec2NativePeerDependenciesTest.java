package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.SecurityGroupNftCompiler.ControlConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Ec2NativePeerDependenciesTest {
    private static final String PEER = """
            [{"hostname":"api.peer.example","address":"172.18.0.2","port":443}]
            """;

    @Test
    void absentAndEmptyConfigurationGrantNothing() {
        for (String value : new String[]{null, "[]"}) {
            Ec2NativePeerDependencies policy = new Ec2NativePeerDependencies(value);
            assertTrue(policy.dependencies().isEmpty());
            assertTrue(policy.connections().isEmpty());
            assertEquals(Map.of("contract", 1, "count", 0,
                    "sha256", "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945"),
                    policy.attestation());
        }
    }

    @Test
    void canonicalAttestationDoesNotDependOnInputObjectOrArrayOrder() {
        Ec2NativePeerDependencies first = new Ec2NativePeerDependencies("""
                [{"hostname":"z.peer.example","address":"172.18.0.2","port":443},
                 {"hostname":"a.peer.example","address":"10.0.0.2","port":8443}]
                """);
        Ec2NativePeerDependencies reordered = new Ec2NativePeerDependencies("""
                [ {"port":8443,"address":"10.0.0.2","hostname":"a.peer.example"},
                  {"address":"172.18.0.2","port":443,"hostname":"z.peer.example"} ]
                """);
        assertEquals(first.attestation(), reordered.attestation());
        assertEquals(2, first.attestation().get("count"));
        assertEquals(List.of(new ControlConnection("10.0.0.2", 8443), new ControlConnection("172.18.0.2", 443)),
                first.connections());
    }

    @Test
    void sharedDestinationHasOneControlConnectionButExactDnsNames() {
        Ec2NativePeerDependencies policy = new Ec2NativePeerDependencies("""
                [{"hostname":"api.peer.example","address":"172.18.0.2","port":443},
                 {"hostname":"other.peer.example","address":"172.18.0.2","port":443}]
                """);
        assertEquals(2, policy.dependencies().size());
        assertEquals(List.of(new ControlConnection("172.18.0.2", 443)), policy.connections());
        assertThrows(UnsupportedOperationException.class, () -> policy.dependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> policy.connections().clear());
    }

    @Test
    void aChangedHostnameAddressOrPortChangesAttestation() {
        Ec2NativePeerDependencies original = new Ec2NativePeerDependencies(PEER);
        for (String different : List.of(PEER.replace("api.", "another."),
                PEER.replace("172.18.0.2", "172.18.0.3"), PEER.replace("443", "8443"))) {
            assertNotEquals(original.attestation(), new Ec2NativePeerDependencies(different).attestation());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "{}", "\"[]\"", "[null]", "[] true",
            "[{\"hostname\":\"api.peer.example\",\"address\":\"172.18.0.2\"}]",
            "[{\"hostname\":\"api.peer.example\",\"address\":\"172.18.0.2\",\"port\":443,\"extra\":1}]",
            "[{\"hostname\":\"api.peer.example\",\"address\":\"172.18.0.2\",\"port\":443,\"port\":8443}]",
            "[{\"hostname\":\"api.peer.example\",\"address\":\"172.18.0.2\",\"port\":\"443\"}]",
            "[{\"hostname\":\"api.peer.example\",\"address\":\"172.18.0.2\",\"port\":443.0}]",
            "[{\"hostname\":\"api.peer.example\",\"address\":\"172.18.0.2\",\"port\":true}]",
            "[{\"hostname\":\"api.peer.example\",\"address\":\"172.18.0.2\",\"port\":0}]",
            "[{\"hostname\":\"api.peer.example\",\"address\":\"172.18.0.2\",\"port\":65536}]",
            "[{\"hostname\":\"api.peer.example\",\"address\":\"172.18.0.2\",\"port\":9223372036854775807}]"
    })
    void invalidOrAmbiguousJsonIsRejected(String json) {
        assertThrows(IllegalArgumentException.class, () -> new Ec2NativePeerDependencies(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*.peer.example", "API.peer.example", "api.peer.example.", "api_peer.example",
            "peer", "127.0.0.1", "api..example", "-api.example", "api-.example",
            "https://api.peer.example", "api.peer.example:443", "api.peer.example/x", " api.peer.example"})
    void onlyCanonicalExactHostnamesAreAccepted(String hostname) {
        assertThrows(IllegalArgumentException.class,
                () -> new Ec2NativePeerDependencies(PEER.replace("api.peer.example", hostname)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "127.0.0.1", "169.254.169.254", "192.0.2.1",
            "8.8.8.8", "172.32.0.2", "172.15.0.2", "224.0.0.1", "255.255.255.255",
            "172.018.0.2", "172.18.0.2/32", "::1", "api.peer.example"})
    void onlyCanonicalPrivateIpv4DestinationsAreAccepted(String address) {
        assertThrows(IllegalArgumentException.class,
                () -> new Ec2NativePeerDependencies(PEER.replace("172.18.0.2", address)));
    }

    @Test
    void duplicateHostnameIsRejectedEvenWhenItsConnectionMatches() {
        for (String address : List.of("172.18.0.2", "172.18.0.3")) {
            String duplicated = PEER.strip().replace("]", ","
                    + "{\"hostname\":\"api.peer.example\",\"address\":\"" + address + "\",\"port\":443}]");
            assertThrows(IllegalArgumentException.class, () -> new Ec2NativePeerDependencies(duplicated));
        }
    }

    @Test
    void endpointCannotPublishDnsWithoutTheSameControlPermission() {
        Ec2NativePeerDependencies policy = new Ec2NativePeerDependencies(PEER);
        assertThrows(IllegalArgumentException.class, () -> endpoint(List.of(), true, policy.dependencies()));
        assertThrows(IllegalArgumentException.class,
                () -> endpoint(List.of(new ControlConnection("172.18.0.2", 8443)), true, policy.dependencies()));
        assertThrows(IllegalArgumentException.class, () -> endpoint(policy.connections(), false, policy.dependencies()));
        assertEquals(policy.dependencies(), endpoint(policy.connections(), true, policy.dependencies())
                .nativePeerDependencies());
    }

    @Test
    void policyAndIpv6RefreshRetainTheDeclaredDependency() {
        Ec2NativePeerDependencies policy = new Ec2NativePeerDependencies(PEER);
        SecurityGroupNftCompiler.Endpoint original = endpoint(policy.connections(), true, policy.dependencies());
        SecurityGroupNftCompiler.Endpoint updated = original.withIpv6Addresses(List.of("fd00::10"))
                .withPolicy(Set.of("sg-new"), List.of());
        assertEquals(policy.dependencies(), updated.nativePeerDependencies());
        assertEquals(List.of("fd00::10"), updated.logicalIpv6Addresses());
        assertEquals(Set.of("sg-new"), updated.groupIds());
    }

    private static SecurityGroupNftCompiler.Endpoint endpoint(List<ControlConnection> controls, boolean metadata,
                                                              Map<String, ControlConnection> peers) {
        return new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", "vpc", "eni",
                "10.0.1.10", "172.18.0.3", Set.of("sg"), List.of(), controls, metadata, Set.of(), List.of(), peers);
    }
}
