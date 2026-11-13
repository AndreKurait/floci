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
import java.util.Map;

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
                nft add table inet floci_vpc_address_space
                nft 'add chain inet floci_vpc_address_space ingress { type filter hook input priority -20; policy accept; }'
                nft 'add chain inet floci_vpc_address_space egress { type filter hook output priority -20; policy accept; }'
                nft 'add chain inet floci_vpc_address_space forwarding { type filter hook forward priority -20; policy accept; }'
                nft -f - <<'FLOCI_RULES'
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
