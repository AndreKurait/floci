package io.github.hectorvent.floci.services.ec2.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeVpcAddressSpaceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String OWNER = "isolation-test/4566";

    @ParameterizedTest
    @ValueSource(strings = {"io.floci.owner", "io.floci.account", "io.floci.region"})
    void rejectsHelpersOutsideTheOwningScope(String label) {
        Map<String, String> labels = labels();
        labels.put(label, "different");
        assertThrows(IllegalStateException.class, () ->
                NativeVpcAddressSpace.guardTable(helper(labels), OWNER, ACCOUNT, REGION));
    }

    @Test
    void acceptsOnlyTheWorkloadAndNatNamespaceGuards() {
        assertEquals("floci_sg", NativeVpcAddressSpace.guardTable(helper(labels()), OWNER, ACCOUNT, REGION));
        Map<String, String> labels = labels();
        labels.remove(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL);
        assertThrows(IllegalStateException.class, () ->
                NativeVpcAddressSpace.guardTable(helper(labels), OWNER, ACCOUNT, REGION));
        labels.put(ContainerStorageHelper.SERVICE_LABEL, "vpc-nat");
        assertEquals("floci_public_nat",
                NativeVpcAddressSpace.guardTable(helper(labels), OWNER, ACCOUNT, REGION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"host", "container:other"})
    void refusesToChangeAnotherNetworkNamespace(String mode) {
        InspectContainerResponse helper = helper(labels());
        helper.getHostConfig().withNetworkMode(mode);
        assertThrows(IllegalStateException.class, () ->
                NativeVpcAddressSpace.guardTable(helper, OWNER, ACCOUNT, REGION));
    }

    @Test
    void detectsControlTransportOverlapFromItsWholeSubnet() {
        ContainerNetwork transport = JSON.convertValue(
                Map.of("IPAddress", "172.18.0.2", "IPPrefixLen", 16), ContainerNetwork.class);
        NativeVpcAddressSpace.rejectTransportOverlap(cidr("10.0.0.0/16"), transport);
        assertThrows(IllegalStateException.class, () ->
                NativeVpcAddressSpace.rejectTransportOverlap(cidr("172.18.3.0/24"), transport));
    }

    @Test
    void selectsTheAttachedInterfaceByMacInsteadOfInterfaceOrder() throws Exception {
        assertEquals("eth2", NativeVpcAddressSpace.deviceFor(JSON.readTree("""
                [{"ifname":"eth0","address":"02:00:00:00:00:01"},
                 {"ifname":"eth2","address":"02:00:00:00:00:02"}]
                """), "02:00:00:00:00:02"));
        assertThrows(IllegalStateException.class, () ->
                NativeVpcAddressSpace.deviceFor(JSON.readTree("[]"), "02:00:00:00:00:02"));
        assertThrows(IllegalStateException.class, () ->
                NativeVpcAddressSpace.deviceFor(JSON.readTree("""
                        [{"ifname":"eth1","address":"02:00:00:00:00:02"},
                         {"ifname":"eth2","address":"02:00:00:00:00:02"}]
                        """), "02:00:00:00:00:02"));
    }

    @Test
    void nativeAddressVerificationRequiresTheDeclaredNetworkAndItsTransportIdentity() {
        Map<String, Object> attachment = new HashMap<>(Map.of("MacAddress", "02:00:00:00:00:02",
                "NetworkID", "native-network-id", "IPAddress", "172.28.0.2"));
        InspectContainerResponse helper = JSON.convertValue(Map.of("NetworkSettings",
                Map.of("Networks", Map.of("native-network", attachment))), InspectContainerResponse.class);
        assertEquals("02:00:00:00:00:02",
                NativeVpcAddressSpace.nativeAttachment(helper, "native-network").getMacAddress());
        assertThrows(IllegalStateException.class, () ->
                NativeVpcAddressSpace.nativeAttachment(helper, "another-network"));
        assertThrows(IllegalStateException.class, () ->
                NativeVpcAddressSpace.nativeAttachment(helper(labels()), "native-network"));
        attachment.put("IPAddress", "");
        InspectContainerResponse withoutTransport = JSON.convertValue(Map.of("NetworkSettings",
                Map.of("Networks", Map.of("native-network", attachment))), InspectContainerResponse.class);
        assertThrows(IllegalStateException.class, () ->
                NativeVpcAddressSpace.nativeAttachment(withoutTransport, "native-network"));
    }

    @Test
    void refusesInvalidOrOutOfScopeAddressCommands() {
        assertThrows(IllegalArgumentException.class, () -> NativeVpcAddressSpace.commands(
                "eth1; true", "10.0.1.10", cidr("10.0.1.0/24"), cidr("10.0.0.0/16")));
        assertThrows(IllegalArgumentException.class, () -> NativeVpcAddressSpace.commands(
                "eth1", "10.0.2.10", cidr("10.0.1.0/24"), cidr("10.0.0.0/16")));
        assertThrows(IllegalArgumentException.class, () -> NativeVpcAddressSpace.commands(
                "eth1", "10.1.1.10", cidr("10.1.1.0/24"), cidr("10.0.0.0/16")));
    }

    @Test
    void verifiesTheNativeSubnetMaskAndAbsenceOfTransportAddressesAndIpv6() throws Exception {
        NativeVpcAddressSpace.verifyAddress(JSON.readTree("""
                [{"addr_info":[{"family":"inet","local":"10.0.1.10","prefixlen":24}]}]
                """), "10.0.1.10", 24);
        assertThrows(IllegalStateException.class, () -> NativeVpcAddressSpace.verifyAddress(JSON.readTree("""
                [{"addr_info":[{"family":"inet","local":"10.0.1.10","prefixlen":16}]}]
                """), "10.0.1.10", 24));
        assertThrows(IllegalStateException.class, () -> NativeVpcAddressSpace.verifyAddress(JSON.readTree("""
                [{"addr_info":[{"family":"inet","local":"10.0.1.10","prefixlen":24},
                               {"family":"inet6","local":"fd00::2","prefixlen":64}]}]
                """), "10.0.1.10", 24));
        assertThrows(IllegalStateException.class, () -> NativeVpcAddressSpace.verifyAddress(JSON.readTree("""
                [{"addr_info":[{"family":"inet","local":"10.0.1.10","prefixlen":24},
                               {"family":"inet","local":"172.28.0.2","prefixlen":16}]}]
                """), "10.0.1.10", 24));
    }

    private static Cidr4 cidr(String text) {
        return Cidr4.parse(text).orElseThrow();
    }

    private static Map<String, String> labels() {
        return new HashMap<>(Map.of(ContainerStorageHelper.OWNER_LABEL, OWNER,
                ContainerStorageHelper.ACCOUNT_LABEL, ACCOUNT, ContainerStorageHelper.REGION_LABEL, REGION,
                ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true"));
    }

    private static InspectContainerResponse helper(Map<String, String> labels) {
        return JSON.convertValue(Map.of("Config", Map.of("Labels", labels),
                "HostConfig", Map.of("NetworkMode", "bridge")), InspectContainerResponse.class);
    }
}
