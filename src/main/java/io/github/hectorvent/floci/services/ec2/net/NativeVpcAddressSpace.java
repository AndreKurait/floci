package io.github.hectorvent.floci.services.ec2.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.DockerObjectAccessor;
import com.github.dockerjava.api.model.Network;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Assigns native IPv4 addresses inside owned helpers, outside Docker's global IPv4 IPAM. */
final class NativeVpcAddressSpace {
    private static final ObjectMapper JSON = new ObjectMapper();

    private NativeVpcAddressSpace() {}

    static void attach(DockerClient docker, EmulatorConfig config, String account, String region,
                       String network, Cidr4 vpc, Cidr4 subnet, String helper, String address) {
        InspectContainerResponse before = docker.inspectContainerCmd(helper).exec();
        String guard = guardTable(before, ContainerStorageHelper.ownerIdentity(config), account, region);
        run(docker, helper, "nft", "list", "table", "inet", guard);
        Map<String, ContainerNetwork> attachments = before.getNetworkSettings().getNetworks();
        for (Map.Entry<String, ContainerNetwork> entry : attachments.entrySet()) {
            if (network.equals(entry.getKey())) {
                continue;
            }
            ContainerNetwork attachment = entry.getValue();
            rejectTransportOverlap(vpc, attachment);
            Network other = docker.inspectNetworkCmd().withNetworkId(entry.getKey()).exec();
            if (other.getLabels() != null
                    && other.getLabels().containsKey(VpcNetworkManager.ADDRESS_SPACE_LABEL)) {
                throw new IllegalStateException("A protected namespace cannot join two native VPC address spaces");
            }
        }
        boolean connected = false;
        try {
            if (!attachments.containsKey(network)) {
                ContainerNetwork endpoint = new ContainerNetwork();
                DockerObjectAccessor.overrideRawValue(endpoint, "GwPriority", -1);
                docker.connectToNetworkCmd().withContainerId(helper).withNetworkId(network)
                        .withContainerNetwork(endpoint).exec();
                connected = true;
            }
            ContainerNetwork attached = nativeAttachment(docker.inspectContainerCmd(helper).exec(), network);
            rejectTransportOverlap(vpc, attached);
            JsonNode links = parse(run(docker, helper, "ip", "-j", "link"));
            String device = deviceFor(links, attached.getMacAddress());
            run(docker, helper, "sh", "-c", commands(device, address, subnet, vpc));
            JsonNode actual = parse(run(docker, helper, "ip", "-j", "address", "show", "dev", device));
            verifyAddress(actual, address, subnet.prefix());
        } catch (RuntimeException failure) {
            if (connected) {
                try {
                    docker.disconnectFromNetworkCmd().withContainerId(helper).withNetworkId(network)
                            .withForce(true).exec();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    /**
     * Plugs a hot-attached interface of another VPC into a protected namespace as its own device: an
     * endpoint on that VPC's native network carrying the interface's MAC, the interface's native address
     * with its subnet prefix, and a route table of its own selected by that source address, so a reply to a
     * connection that arrived on the device leaves through it. The primary attachment, its address-space
     * table and its routes are untouched; the main table gains the device's VPC route only when it overlaps
     * no route the namespace already holds.
     *
     * @return the device name
     */
    static String attachAdditional(DockerClient docker, EmulatorConfig config, String instanceAccount,
                                   String region, String network, Cidr4 vpc, Cidr4 subnet, String helper,
                                   String address, String macAddress) {
        InspectContainerResponse before = docker.inspectContainerCmd(helper).exec();
        String guard = guardTable(before, ContainerStorageHelper.ownerIdentity(config), instanceAccount, region);
        if (!"floci_sg".equals(guard)) {
            throw new IllegalStateException("Only a protected workload namespace takes an attached interface");
        }
        run(docker, helper, "nft", "list", "table", "inet", guard);
        Map<String, ContainerNetwork> attachments = before.getNetworkSettings().getNetworks();
        if (attachments.containsKey(network)) {
            throw new IllegalStateException("The namespace already holds an interface in this native VPC");
        }
        for (ContainerNetwork attachment : attachments.values()) {
            rejectTransportOverlap(vpc, attachment);
        }
        ContainerNetwork endpoint = new ContainerNetwork().withMacAddress(macAddress);
        DockerObjectAccessor.overrideRawValue(endpoint, "GwPriority", -1);
        docker.connectToNetworkCmd().withContainerId(helper).withNetworkId(network)
                .withContainerNetwork(endpoint).exec();
        try {
            ContainerNetwork attached = nativeAttachment(docker.inspectContainerCmd(helper).exec(), network);
            rejectTransportOverlap(vpc, attached);
            JsonNode links = parse(run(docker, helper, "ip", "-j", "link"));
            String device = deviceFor(links, attached.getMacAddress());
            if (!macAddress.equalsIgnoreCase(attached.getMacAddress())) {
                // Docker 25 applies an endpoint MAC only when it creates the container, not on connect:
                // the protected helper gives the device the interface's own MAC before anything uses it.
                run(docker, helper, "sh", "-c", macCommands(device, macAddress));
                links = parse(run(docker, helper, "ip", "-j", "link"));
                if (!device.equals(deviceFor(links, macAddress))) {
                    throw new IllegalStateException("The attached interface's device did not take its MAC");
                }
            }
            int table = routeTable(links, device);
            boolean mainRoute = mainRouteFree(
                    parse(run(docker, helper, "ip", "-j", "-4", "route", "show", "table", "main")), vpc, device);
            run(docker, helper, "sh", "-c", attachedCommands(device, address, subnet, vpc, table, mainRoute));
            verifyAddress(parse(run(docker, helper, "ip", "-j", "address", "show", "dev", device)),
                    address, subnet.prefix());
            return device;
        } catch (RuntimeException failure) {
            try {
                docker.disconnectFromNetworkCmd().withContainerId(helper).withNetworkId(network).withForce(true).exec();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * After an emulator restart: configures the device {@link #attachAdditional} left in the namespace again
     * and returns it, or empty when the namespace holds no endpoint on that network.
     */
    static Optional<String> restoreAdditional(DockerClient docker, String helper, String network, Cidr4 vpc,
                                              Cidr4 subnet, String address, String macAddress) {
        InspectContainerResponse inspection = docker.inspectContainerCmd(helper).exec();
        Map<String, ContainerNetwork> networks = inspection.getNetworkSettings() == null
                ? null : inspection.getNetworkSettings().getNetworks();
        if (networks == null || !networks.containsKey(network)) {
            return Optional.empty();
        }
        JsonNode links = parse(run(docker, helper, "ip", "-j", "link"));
        String device = deviceFor(links, macAddress);
        int table = routeTable(links, device);
        boolean mainRoute = mainRouteFree(
                parse(run(docker, helper, "ip", "-j", "-4", "route", "show", "table", "main")), vpc, device);
        run(docker, helper, "sh", "-c", attachedCommands(device, address, subnet, vpc, table, mainRoute));
        verifyAddress(parse(run(docker, helper, "ip", "-j", "address", "show", "dev", device)), address, subnet.prefix());
        return Optional.of(device);
    }

    /** Removes what {@link #attachAdditional} added: the device's rule, table, filter and endpoint. */
    static void detachAdditional(DockerClient docker, String helper, String network, String macAddress,
                                 String address) {
        InspectContainerResponse inspection = docker.inspectContainerCmd(helper).exec();
        Map<String, ContainerNetwork> networks = inspection.getNetworkSettings() == null
                ? null : inspection.getNetworkSettings().getNetworks();
        if (networks == null || !networks.containsKey(network)) {
            return;
        }
        JsonNode links = parse(run(docker, helper, "ip", "-j", "link"));
        String device = deviceFor(links, macAddress);
        run(docker, helper, "sh", "-c", detachedCommands(device, address, routeTable(links, device)));
        docker.disconnectFromNetworkCmd().withContainerId(helper).withNetworkId(network).withForce(true).exec();
    }

    /** The attached device's own route table and rule priority: above every table the kernel names. */
    static int routeTable(JsonNode links, String device) {
        for (JsonNode link : links) {
            if (device.equals(link.path("ifname").asText()) && link.path("ifindex").canConvertToInt()) {
                return ATTACHED_TABLE_BASE + link.path("ifindex").asInt();
            }
        }
        throw new IllegalStateException("Cannot identify the attached interface's index");
    }

    private static final Pattern MAC = Pattern.compile("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}");

    static String macCommands(String device, String macAddress) {
        device = VpcRoutingCommands.device(device);
        String mac = macAddress == null ? "" : macAddress.toLowerCase(Locale.ROOT);
        if (!MAC.matcher(mac).matches()) {
            throw new IllegalArgumentException("Expected a unicast MAC address");
        }
        return """
                set -eu
                ip link set dev %1$s down
                ip link set dev %1$s address %2$s
                ip link set dev %1$s up
                """.formatted(device, mac);
    }

    static final int ATTACHED_TABLE_BASE = 10_000;

    /**
     * Whether the device's VPC range may also go in the main table: no route through any other device overlaps
     * it. The default route is not a VPC route and does not count.
     */
    static boolean mainRouteFree(JsonNode routes, Cidr4 vpc, String device) {
        for (JsonNode route : routes) {
            String destination = route.path("dst").asText();
            if ("default".equals(destination) || device.equals(route.path("dev").asText())) {
                continue;
            }
            Optional<Cidr4> parsed = Cidr4.parse(destination.contains("/") ? destination : destination + "/32");
            if (parsed.isEmpty() || parsed.get().overlaps(vpc)) {
                return false;
            }
        }
        return true;
    }

    static String attachedCommands(String device, String address, Cidr4 subnet, Cidr4 vpc, int table,
                                   boolean mainRoute) {
        device = VpcRoutingCommands.device(device);
        address = VpcRouteSelection.ipv4(address);
        Cidr4 host = VpcRouteSelection.cidr(address + "/32");
        if (!vpc.contains(subnet) || !subnet.contains(host)) {
            throw new IllegalArgumentException("The native address must be inside its subnet and VPC");
        }
        if (table <= ATTACHED_TABLE_BASE || table > ATTACHED_TABLE_BASE + 65_535) {
            throw new IllegalArgumentException("The attached interface needs its own route table");
        }
        return """
                set -eu
                ip link set dev %1$s addrgenmode none
                ip -6 address flush dev %1$s
                ip -4 address flush dev %1$s
                ip -4 route flush dev %1$s
                nft -f - <<'FLOCI_RULES'
                add table inet floci_vpc_attached_%6$d
                add chain inet floci_vpc_attached_%6$d ingress { type filter hook input priority -20; policy accept; }
                add chain inet floci_vpc_attached_%6$d egress { type filter hook output priority -20; policy accept; }
                add chain inet floci_vpc_attached_%6$d forwarding { type filter hook forward priority -20; policy accept; }
                flush chain inet floci_vpc_attached_%6$d ingress
                flush chain inet floci_vpc_attached_%6$d egress
                flush chain inet floci_vpc_attached_%6$d forwarding
                add rule inet floci_vpc_attached_%6$d ingress iifname "%1$s" meta nfproto ipv6 drop
                add rule inet floci_vpc_attached_%6$d egress oifname "%1$s" meta nfproto ipv6 drop
                add rule inet floci_vpc_attached_%6$d forwarding iifname "%1$s" drop
                add rule inet floci_vpc_attached_%6$d forwarding oifname "%1$s" drop
                FLOCI_RULES
                ip -4 address replace %2$s/%3$d dev %1$s noprefixroute
                ip -4 route replace %4$s dev %1$s src %2$s proto 185 table %5$d
                while ip -4 rule del priority %5$d 2>/dev/null; do :; done
                ip -4 rule add priority %5$d from %2$s/32 lookup %5$d
                """.formatted(device, address, subnet.prefix(), vpc, table, table - ATTACHED_TABLE_BASE)
                + (mainRoute ? "ip -4 route replace %s dev %s src %s proto 185%n".formatted(vpc, device, address) : "");
    }

    static String detachedCommands(String device, String address, int table) {
        device = VpcRoutingCommands.device(device);
        address = VpcRouteSelection.ipv4(address);
        return """
                set -eu
                while ip -4 rule del priority %3$d 2>/dev/null; do :; done
                ip -4 route flush table %3$d
                ip -4 route flush dev %1$s
                ip -4 address flush dev %1$s
                nft delete table inet floci_vpc_attached_%4$d 2>/dev/null || true
                """.formatted(device, address, table, table - ATTACHED_TABLE_BASE);
    }

    /** Reads the real namespace address on restoration; Docker IPAM does not own this address. */
    static void verify(DockerClient docker, EmulatorConfig config, String account, String region,
                       String network, String helper, String address, int prefix) {
        InspectContainerResponse inspection = docker.inspectContainerCmd(helper).exec();
        String guard = guardTable(inspection, ContainerStorageHelper.ownerIdentity(config), account, region);
        run(docker, helper, "nft", "list", "table", "inet", guard);
        ContainerNetwork attached = nativeAttachment(inspection, network);
        String device = deviceFor(parse(run(docker, helper, "ip", "-j", "link")), attached.getMacAddress());
        verifyAddress(parse(run(docker, helper, "ip", "-j", "address", "show", "dev", device)), address, prefix);
    }

    static ContainerNetwork nativeAttachment(InspectContainerResponse helper, String network) {
        Map<String, ContainerNetwork> networks = helper.getNetworkSettings() == null
                ? null : helper.getNetworkSettings().getNetworks();
        ContainerNetwork attached = networks == null ? null : networks.get(network);
        if (attached == null || attached.getMacAddress() == null
                || attached.getIpAddress() == null || attached.getIpAddress().isBlank()) {
            throw new IllegalStateException("The native VPC interface must have a Docker transport allocation");
        }
        return attached;
    }

    static String guardTable(InspectContainerResponse helper, String owner, String account, String region) {
        Map<String, String> labels = helper.getConfig() == null ? Map.of() : helper.getConfig().getLabels();
        if (labels == null || !owner.equals(labels.get(ContainerStorageHelper.OWNER_LABEL))
                || !account.equals(labels.get(ContainerStorageHelper.ACCOUNT_LABEL))
                || !region.equals(labels.get(ContainerStorageHelper.REGION_LABEL))
                || helper.getHostConfig() == null || "host".equals(helper.getHostConfig().getNetworkMode())
                || helper.getHostConfig().getNetworkMode() != null
                && helper.getHostConfig().getNetworkMode().startsWith("container:")) {
            throw new IllegalStateException("Native VPC addresses require an owned network helper in the same account");
        }
        if ("true".equals(labels.get(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL))) {
            return "floci_sg";
        }
        if ("vpc-nat".equals(labels.get(ContainerStorageHelper.SERVICE_LABEL))) {
            return "floci_public_nat";
        }
        throw new IllegalStateException("Native VPC addresses require a protected workload or NAT helper");
    }

    static void rejectTransportOverlap(Cidr4 vpc, ContainerNetwork transport) {
        if (transport.getIpAddress() == null || transport.getIpAddress().isBlank()) {
            return;
        }
        Cidr4 range = Cidr4.parse(transport.getIpAddress() + "/" + transport.getIpPrefixLen())
                .orElseThrow(() -> new IllegalStateException("Cannot determine the helper's control network"));
        if (vpc.overlaps(range)) {
            throw new IllegalStateException("Native VPC " + vpc + " overlaps the helper's control transport "
                    + range + "; select a nonoverlapping Docker control network");
        }
    }

    static String deviceFor(JsonNode links, String mac) {
        List<String> matches = new ArrayList<>();
        for (JsonNode link : links) {
            if (mac.equalsIgnoreCase(link.path("address").asText())) {
                matches.add(link.path("ifname").asText());
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("Cannot identify exactly one native VPC interface");
        }
        return VpcRoutingCommands.device(matches.getFirst());
    }

    static String commands(String device, String address, Cidr4 subnet, Cidr4 vpc) {
        device = VpcRoutingCommands.device(device);
        address = VpcRouteSelection.ipv4(address);
        Cidr4 host = VpcRouteSelection.cidr(address + "/32");
        if (!vpc.contains(subnet) || !subnet.contains(host)) {
            throw new IllegalArgumentException("The native address must be inside its subnet and VPC");
        }
        return """
                set -eu
                ip link set dev %1$s addrgenmode none
                ip -6 address flush dev %1$s
                ip -4 address flush dev %1$s
                ip -4 route flush dev %1$s
                nft -f - <<'FLOCI_RULES'
                add table inet floci_vpc_address_space
                add chain inet floci_vpc_address_space ingress { type filter hook input priority -20; policy accept; }
                add chain inet floci_vpc_address_space egress { type filter hook output priority -20; policy accept; }
                add chain inet floci_vpc_address_space forwarding { type filter hook forward priority -20; policy accept; }
                flush chain inet floci_vpc_address_space ingress
                flush chain inet floci_vpc_address_space egress
                flush chain inet floci_vpc_address_space forwarding
                add rule inet floci_vpc_address_space ingress iifname "%1$s" meta nfproto ipv6 drop
                add rule inet floci_vpc_address_space egress oifname "%1$s" meta nfproto ipv6 drop
                add rule inet floci_vpc_address_space forwarding iifname "%1$s" meta nfproto ipv6 drop
                add rule inet floci_vpc_address_space forwarding oifname "%1$s" meta nfproto ipv6 drop
                FLOCI_RULES
                ip -4 address replace %2$s/%3$d dev %1$s
                ip -4 route replace %4$s dev %1$s src %2$s proto 185
                """.formatted(device, address, subnet.prefix(), vpc);
    }

    static void verifyAddress(JsonNode links, String address, int prefix) {
        boolean found = false;
        for (JsonNode link : links) {
            for (JsonNode item : link.path("addr_info")) {
                if ("inet6".equals(item.path("family").asText())) {
                    throw new IllegalStateException("The native IPv4 VPC interface still has an IPv6 address");
                }
                if ("inet".equals(item.path("family").asText())) {
                    if (!address.equals(item.path("local").asText()) || prefix != item.path("prefixlen").asInt()) {
                        throw new IllegalStateException("The native VPC interface still has another IPv4 address");
                    }
                    found = true;
                }
            }
        }
        if (!found) {
            throw new IllegalStateException("The native VPC interface does not hold its declared IPv4 address");
        }
    }

    private static JsonNode parse(String value) {
        JsonNode parsed = parseDocument(value);
        if (!parsed.isArray()) {
            throw new IllegalStateException("Expected a network interface array");
        }
        return parsed;
    }

    private static JsonNode parseDocument(String value) {
        try {
            return JSON.readTree(value);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read network interface state", failure);
        }
    }

    private static String run(DockerClient docker, String helper, String... command) {
        ContainerExec.Result result = ContainerExec.run(docker, helper, command, 15).throwIfTimedOut(helper);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Cannot configure native VPC namespace: " + result.summary());
        }
        return result.stdout();
    }
}
