package io.floci.aws.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.model.ContainerNetwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the actual bridge endpoint even when Docker renames its interface after a restart. */
public final class DockerDefaultRouteAssertions {
    private DockerDefaultRouteAssertions() {
    }

    public static void assertDefaultRouteUsesBridge(JsonNode routes, JsonNode addresses, ContainerNetwork bridge) {
        assertNotNull(bridge, "The primary Docker bridge endpoint must remain attached");
        assertEquals(1, routes.size(), routes.toString());
        JsonNode route = routes.get(0);
        assertEquals(bridge.getGateway(), route.path("gateway").asText(), routes.toString());
        // Docker can rename interfaces on restart; identify the routed endpoint by its current MAC and IP.
        JsonNode routedDevice = null;
        for (JsonNode device : addresses) {
            if (route.path("dev").asText().equals(device.path("ifname").asText())) {
                routedDevice = device;
            }
        }
        assertNotNull(routedDevice, addresses.toString());
        assertTrue(bridge.getMacAddress().equalsIgnoreCase(routedDevice.path("address").asText()),
                routedDevice.toString());
        boolean bridgeAddressPresent = false;
        for (JsonNode address : routedDevice.path("addr_info")) {
            bridgeAddressPresent |= bridge.getIpAddress().equals(address.path("local").asText());
        }
        assertTrue(bridgeAddressPresent, routedDevice.toString());
    }
}
