package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.NetworkSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2PublicAddressRoutingTest {
    @Test
    void primaryComesFromTheNamespaceNetworkModeRegardlessOfMapOrder() {
        for (boolean vpcFirst : new boolean[]{false, true}) {
            Map<String, ContainerNetwork> networks = new LinkedHashMap<>();
            String first = vpcFirst ? "vpc" : "control-plane";
            String second = vpcFirst ? "control-plane" : "vpc";
            networks.put(first, new ContainerNetwork().withIpv4Address(vpcFirst ? "10.0.1.10" : "172.18.0.3"));
            networks.put(second, new ContainerNetwork().withIpv4Address(vpcFirst ? "172.18.0.3" : "10.0.1.10"));
            assertEquals("172.18.0.3",
                    Ec2PublicAddressRouting.primaryAddress(namespace("control-plane", networks)));
        }
    }

    @Test
    void missingPrimaryIsNotReplacedWithAnArbitraryVpcAddress() {
        assertThrows(IllegalStateException.class, () -> Ec2PublicAddressRouting.primaryAddress(
                namespace("control-plane", Map.of("vpc", new ContainerNetwork().withIpv4Address("10.0.1.10")))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "localhost", "127.0.0.1", "0.0.0.0", "169.254.169.254",
            "224.0.0.1", "256.1.1.1", "172.18.0.3;true", "::1"})
    void nonInterfaceAddressesCannotEnterTheNamespaceCommand(String address) {
        assertThrows(IllegalArgumentException.class, () -> Ec2PublicAddressRouting.command("10.0.1.10", address));
        assertThrows(IllegalArgumentException.class, () -> Ec2PublicAddressRouting.command(address, "172.18.0.3"));
    }

    @Test
    void equalAddressesNeedNoTranslation() {
        assertThrows(IllegalArgumentException.class,
                () -> Ec2PublicAddressRouting.command("10.0.1.10", "10.0.1.10"));
    }

    @Test
    void recordedPrivateAddressDoesNotDependOnNetworkOrder() {
        for (boolean primaryFirst : List.of(true, false)) {
            Map<String, ContainerNetwork> networks = new LinkedHashMap<>();
            ContainerNetwork primary = new ContainerNetwork().withIpv4Address("172.18.0.3");
            ContainerNetwork vpc = new ContainerNetwork().withIpv4Address("10.0.1.10");
            networks.put(primaryFirst ? "control-plane" : "floci-vpc", primaryFirst ? primary : vpc);
            networks.put(primaryFirst ? "floci-vpc" : "control-plane", primaryFirst ? vpc : primary);
            assertEquals("10.0.1.10", Ec2PublicAddressRouting.privateAddress(
                    namespace("control-plane", networks), "10.0.1.10"));
        }
    }

    @Test
    void retainedSingleNetworkDoesNotNeedARecordedAddress() {
        assertEquals("172.18.0.3", Ec2PublicAddressRouting.privateAddress(namespace("control-plane",
                Map.of("control-plane", new ContainerNetwork().withIpv4Address("172.18.0.3"))), null));
    }

    @Test
    void missingRecordedAddressDoesNotChooseBetweenNetworks() {
        assertThrows(IllegalStateException.class, () -> Ec2PublicAddressRouting.privateAddress(
                namespace("control-plane", Map.of(
                        "control-plane", new ContainerNetwork().withIpv4Address("172.18.0.3"),
                        "floci-vpc", new ContainerNetwork().withIpv4Address("10.0.1.10"))), null));
    }

    @Test
    void staleRecordedAddressDoesNotFallBackToThePrimaryNetwork() {
        assertThrows(IllegalStateException.class, () -> Ec2PublicAddressRouting.privateAddress(
                namespace("control-plane", Map.of(
                        "control-plane", new ContainerNetwork().withIpv4Address("172.18.0.3"))), "10.0.1.10"));
    }

    private static InspectContainerResponse namespace(String mode, Map<String, ContainerNetwork> networks) {
        InspectContainerResponse result = mock(InspectContainerResponse.class);
        when(result.getHostConfig()).thenReturn(HostConfig.newHostConfig().withNetworkMode(mode));
        NetworkSettings settings = mock(NetworkSettings.class);
        when(result.getNetworkSettings()).thenReturn(settings);
        when(settings.getNetworks()).thenReturn(networks);
        return result;
    }
}
