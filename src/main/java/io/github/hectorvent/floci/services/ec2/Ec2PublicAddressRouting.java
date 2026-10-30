package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.netty.util.NetUtil;

import java.util.List;
import java.util.Map;

/** Public transport for a private listener in an EC2-owned network namespace. */
final class Ec2PublicAddressRouting {
    private Ec2PublicAddressRouting() {}

    static String primaryAddress(InspectContainerResponse namespace) {
        Map<String, ContainerNetwork> networks = namespace.getNetworkSettings() == null
                ? null : namespace.getNetworkSettings().getNetworks();
        if (networks == null || networks.isEmpty()) {
            throw new IllegalStateException("EC2 namespace has no Docker network addresses");
        }
        String mode = namespace.getHostConfig() == null ? null : namespace.getHostConfig().getNetworkMode();
        String primary = mode == null || "default".equals(mode) ? "bridge" : mode;
        List<ContainerNetwork> matches = networks.entrySet().stream()
                .filter(entry -> primary.equals(entry.getKey())
                        || entry.getValue() != null && primary.equals(entry.getValue().getNetworkID()))
                .map(Map.Entry::getValue).toList();
        if (matches.size() != 1 || matches.getFirst() == null) {
            throw new IllegalStateException("EC2 namespace's primary Docker network is missing or ambiguous");
        }
        String address = matches.getFirst().getIpAddress();
        requireAddress(address);
        return address;
    }

    static String[] command(String privateAddress, String publicAddress) {
        requireAddress(privateAddress);
        requireAddress(publicAddress);
        if (privateAddress.equals(publicAddress)) {
            throw new IllegalArgumentException("EC2 public translation requires distinct interface addresses");
        }
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                Ec2MetadataProxy.CLEAR_APPLICATION_PATHS,
                "refuse() { echo \"$1\" >&2; exit 1; }",
                "private_ip='" + privateAddress + "'; public_ip='" + publicAddress + "'",
                "addresses=$(ip -4 -o addr show scope global)",
                "printf '%s\\n' \"$addresses\" | grep -Fq \"inet $private_ip/\""
                        + " || refuse 'EC2 private address is not on a guest interface'",
                "public_device=$(printf '%s\\n' \"$addresses\""
                        + " | awk -v ip=\"$public_ip/\" '$3 == \"inet\" && index($4, ip) == 1 {print $2}')",
                "case \"$public_device\" in ''|lo|*[!A-Za-z0-9_.:-]*)"
                        + " refuse 'EC2 public address lacks one unambiguous guest interface' ;; esac",
                "check_rule() {",
                "  chain=$1; shift",
                "  count=$(iptables -w 2 -t nat -S \"$chain\""
                        + " | grep -Ec -- '--comment \"?floci-ec2-public\"?( |$)' || true)",
                "  case \"$count\" in",
                "    0) return 1 ;;",
                "    1) iptables -w 2 -t nat -C \"$chain\" \"$@\""
                        + " || refuse 'Conflicting EC2 public-address routing rule' ;;",
                "    *) refuse 'Multiple EC2 public-address routing rules' ;;",
                "  esac",
                "}",
                "for chain in PREROUTING OUTPUT POSTROUTING; do",
                "  case \"$chain\" in",
                "    PREROUTING|OUTPUT) set -- -d \"$public_ip/32\" -m comment"
                        + " --comment floci-ec2-public -j DNAT --to-destination \"$private_ip\" ;;",
                "    POSTROUTING) set -- -s \"$private_ip/32\" -o \"$public_device\" -m comment"
                        + " --comment floci-ec2-public -j SNAT --to-source \"$public_ip\" ;;",
                "  esac",
                "  if ! check_rule \"$chain\" \"$@\"; then",
                "    iptables -w 2 -t nat -I \"$chain\" 1 \"$@\"",
                "  fi",
                "  check_rule \"$chain\" \"$@\""
                        + " || refuse 'EC2 public-address routing was not installed'",
                "done")};
    }

    static String privateAddress(InspectContainerResponse namespace, String recordedAddress) {
        Map<String, ContainerNetwork> networks = namespace.getNetworkSettings() == null
                ? null : namespace.getNetworkSettings().getNetworks();
        if (networks == null || networks.isEmpty()) {
            throw new IllegalStateException("EC2 namespace has no Docker network addresses");
        }
        if (recordedAddress != null && !recordedAddress.isBlank()) {
            requireAddress(recordedAddress);
            long matches = networks.values().stream()
                    .filter(network -> network != null && recordedAddress.equals(network.getIpAddress()))
                    .count();
            if (matches != 1) {
                throw new IllegalStateException("Recorded EC2 transport address is missing or ambiguous");
            }
            return recordedAddress;
        }
        if (networks.size() != 1) {
            throw new IllegalStateException("EC2 namespace has multiple networks but no recorded transport address");
        }
        ContainerNetwork network = networks.values().iterator().next();
        String address = network == null ? null : network.getIpAddress();
        requireAddress(address);
        return address;
    }

    private static void requireAddress(String address) {
        if (address == null || !NetUtil.isValidIpV4Address(address)
                || !address.matches("[0-9]+(?:\\.[0-9]+){3}")) {
            throw new IllegalArgumentException("EC2 routing requires literal IPv4 interface addresses");
        }
        int first = Integer.parseInt(address.substring(0, address.indexOf('.')));
        if (first == 0 || first == 127 || first >= 224 || address.startsWith("169.254.")) {
            throw new IllegalArgumentException("EC2 routing requires non-loopback unicast interface addresses");
        }
    }
}
