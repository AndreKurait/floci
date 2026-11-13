package io.github.hectorvent.floci.services.ec2;

import java.net.Inet4Address;
import java.net.InetAddress;

/** DNS-only routing in a newly created, service-owned network namespace. */
final class VpcResolverRouting {
    private VpcResolverRouting() {}

    static String commands(String resolver) {
        if (resolver == null) {
            throw new IllegalArgumentException("The VPC resolver target is required");
        }
        InetAddress address = InetAddress.ofLiteral(resolver);
        if (!(address instanceof Inet4Address) || address.isAnyLocalAddress()
                || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()
                || "255.255.255.255".equals(address.getHostAddress())) {
            throw new IllegalArgumentException("The VPC resolver requires a remote unicast IPv4 target");
        }
        String target = address.getHostAddress();
        // Linux selects a source before OUTPUT DNAT. The host route preserves the resolver's
        // control-network source even when the namespace has no default route or another bridge.
        return """
                set -eu
                target='%s'
                route=$(ip -4 route get "$target")
                device=; source=
                set -- $route
                [ "${1-}" = "$target" ] || exit 1
                while [ "$#" -gt 0 ]; do
                  case "$1" in dev) device=${2-} ;; src) source=${2-} ;; esac
                  shift
                done
                case "$device" in ''|lo|*[!A-Za-z0-9_.:-]*) exit 1 ;; esac
                case "$source" in ''|*[!0-9.]*) exit 1 ;; esac
                ip -4 -o addr show dev "$device" | grep -Fq "inet $source/" || exit 1
                ip -4 route add 169.254.169.253/32 dev "$device" scope link src "$source"
                nft -f - <<FLOCI_DNS
                add table ip floci_vpc_resolver
                add chain ip floci_vpc_resolver output { type nat hook output priority dstnat; }
                add rule ip floci_vpc_resolver output ip daddr 169.254.169.253 udp dport 53 dnat to $target:53
                add rule ip floci_vpc_resolver output ip daddr 169.254.169.253 tcp dport 53 dnat to $target:53
                FLOCI_DNS
                """.formatted(target);
    }
}
