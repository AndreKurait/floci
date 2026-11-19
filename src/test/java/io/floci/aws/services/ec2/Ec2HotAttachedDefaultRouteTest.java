package io.floci.aws.services.ec2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ContainerNetwork;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

class Ec2HotAttachedDefaultRouteTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ContainerNetwork bridge = new ContainerNetwork()
            .withGateway("172.17.0.1").withIpv4Address("172.17.0.2").withMacAddress("02:42:ac:11:00:02");

    @ParameterizedTest
    @ValueSource(strings = {"eth0", "eth1", "uplink0"})
    void bridgeIdentitySurvivesDeviceRenaming(String device) throws Exception {
        Ec2HotAttachedRestartDockerIntegrationTest.assertDefaultRouteUsesBridge(
                routes(device, "172.17.0.1"),
                addresses(device, "02:42:ac:11:00:02", "172.17.0.2"), bridge);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gateway", "mac", "address", "device", "missing", "multiple"})
    void missingOrRedirectedDefaultRoutesStillFail(String mismatch) throws Exception {
        JsonNode routes = routes("eth1", mismatch.equals("gateway") ? "10.20.0.1" : "172.17.0.1");
        if (mismatch.equals("missing")) {
            routes = mapper.readTree("[]");
        } else if (mismatch.equals("multiple")) {
            routes = mapper.readTree("[" + routes.get(0) + "," + routes.get(0) + "]");
        }
        JsonNode addresses = addresses(mismatch.equals("device") ? "eth0" : "eth1",
                mismatch.equals("mac") ? "02:11:22:33:44:55" : "02:42:ac:11:00:02",
                mismatch.equals("address") ? "10.20.0.2" : "172.17.0.2");
        JsonNode observedRoutes = routes;
        assertThrows(AssertionError.class, () ->
                Ec2HotAttachedRestartDockerIntegrationTest.assertDefaultRouteUsesBridge(
                        observedRoutes, addresses, bridge));
    }

    private JsonNode routes(String device, String gateway) throws Exception {
        return mapper.readTree("""
                [{"dst":"default","gateway":"%s","dev":"%s"}]
                """.formatted(gateway, device));
    }

    private JsonNode addresses(String device, String mac, String address) throws Exception {
        return mapper.readTree("""
                [{"ifname":"%s","address":"%s","addr_info":[{"family":"inet","local":"%s"}]}]
                """.formatted(device, mac, address));
    }
}
