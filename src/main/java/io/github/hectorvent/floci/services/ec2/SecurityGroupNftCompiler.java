package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Builds only rules for an endpoint's private nftables table. Docker's tables are untouched. */
public final class SecurityGroupNftCompiler {

    private SecurityGroupNftCompiler() {}

    public record Endpoint(String accountId, String region, String vpcId, String eniId,
                           String logicalAddress, String transportAddress, Set<String> groupIds,
                           List<SecurityGroup> groups, List<ControlConnection> controlConnections,
                           boolean instanceMetadataAllowed, Set<String> additionalTransportAddresses,
                           List<ControlListener> controlListeners) {
        public Endpoint {
            controlConnections = List.copyOf(controlConnections);
            additionalTransportAddresses = Set.copyOf(additionalTransportAddresses);
            controlListeners = List.copyOf(controlListeners);
            if (additionalTransportAddresses.stream().anyMatch(address -> !literal(address))) {
                throw new IllegalArgumentException("Transport addresses must be literal IP addresses");
            }
        }

        public Endpoint(String accountId, String region, String vpcId, String eniId,
                        String logicalAddress, String transportAddress, Set<String> groupIds,
                        List<SecurityGroup> groups, List<ControlConnection> controlConnections,
                        boolean instanceMetadataAllowed, Set<String> additionalTransportAddresses) {
            this(accountId, region, vpcId, eniId, logicalAddress, transportAddress, groupIds, groups,
                    controlConnections, instanceMetadataAllowed, additionalTransportAddresses, List.of());
        }

        public Endpoint(String accountId, String region, String vpcId, String eniId,
                        String logicalAddress, String transportAddress, Set<String> groupIds,
                        List<SecurityGroup> groups, List<ControlConnection> controlConnections,
                        boolean instanceMetadataAllowed) {
            this(accountId, region, vpcId, eniId, logicalAddress, transportAddress, groupIds, groups,
                    controlConnections, instanceMetadataAllowed, Set.of());
        }

        public Endpoint(String accountId, String region, String vpcId, String eniId,
                        String logicalAddress, String transportAddress, Set<String> groupIds,
                        List<SecurityGroup> groups) {
            this(accountId, region, vpcId, eniId, logicalAddress, transportAddress, groupIds, groups, List.of(), true);
        }

        public Endpoint(String accountId, String region, String vpcId, String eniId,
                        String logicalAddress, String transportAddress, Set<String> groupIds,
                        List<SecurityGroup> groups, Set<String> additionalTransportAddresses) {
            this(accountId, region, vpcId, eniId, logicalAddress, transportAddress, groupIds, groups,
                    List.of(), true, additionalTransportAddresses);
        }

        List<String> transportAddresses() {
            Set<String> addresses = new LinkedHashSet<>();
            if (literal(transportAddress)) {
                addresses.add(transportAddress);
            }
            additionalTransportAddresses.stream().sorted().forEach(addresses::add);
            return List.copyOf(addresses);
        }

        SecurityGroupPolicy.Peer peer() {
            return new SecurityGroupPolicy.Peer(logicalAddress, accountId, vpcId, groupIds);
        }

        boolean sameVpc(Endpoint other) {
            return accountId.equals(other.accountId) && region.equals(other.region) && vpcId.equals(other.vpcId);
        }
    }

    /** A service-owned TCP endpoint, such as one execution environment's Lambda Runtime API. */
    public record ControlConnection(String address, int port) {
        public ControlConnection {
            if (!literal(address) || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Control connections require a literal IP and one TCP port");
            }
        }
    }

    /** A service-owned listener reached only from its controller's literal address. */
    public record ControlListener(String controllerAddress, int port) {
        public ControlListener {
            if (!literal(controllerAddress) || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Control listeners require a literal controller IP and one TCP port");
            }
        }
    }

    /**
     * Security groups of a load balancer whose health checks and forwarded requests Floci sends from
     * its own address. On AWS that traffic comes from load-balancer nodes in these groups.
     */
    public record EmulatorOrigin(String accountId, String region, String vpcId, Set<String> groupIds) {
        public EmulatorOrigin {
            groupIds = Set.copyOf(groupIds);
        }
    }

    public static String initialRuleset() {
        return "add table inet floci_sg\n"
                + "add chain inet floci_sg ingress { type filter hook input priority -10; policy drop; }\n"
                + "add chain inet floci_sg egress { type filter hook output priority -10; policy drop; }\n";
    }

    public static String compile(Endpoint target, Collection<Endpoint> peers,
                                 Map<String, List<String>> prefixLists) {
        return compile(target, peers, prefixLists, List.of());
    }

    public static String compile(Endpoint target, Collection<Endpoint> peers,
                                 Map<String, List<String>> prefixLists, List<String> vpcResolvers) {
        return compile(target, peers, prefixLists, vpcResolvers, false);
    }

    public static String compile(Endpoint target, Collection<Endpoint> peers,
                                  Map<String, List<String>> prefixLists, List<String> vpcResolvers,
                                  boolean isolatedAddressSpaces) {
        return compile(target, peers, prefixLists, vpcResolvers, isolatedAddressSpaces, peer -> true);
    }

    /**
     * Compiles {@code target}'s policy, granting security-group accepts only to {@code admitted} peers.
     * A managed peer that is not admitted keeps its drop rules, so the result never accepts more
     * than the policy compiled with every peer admitted: it only withholds accepts toward peers.
     */
    public static String compile(Endpoint target, Collection<Endpoint> peers,
                                  Map<String, List<String>> prefixLists, List<String> vpcResolvers,
                                  boolean isolatedAddressSpaces, Predicate<Endpoint> admitted) {
        return compile(target, peers, prefixLists, vpcResolvers, isolatedAddressSpaces, admitted,
                List.of(), List.of());
    }

    /**
     * As above, and an ingress rule that references a security group of an {@code emulatorOrigins}
     * load balancer in the target's VPC also accepts that traffic from {@code emulatorAddresses},
     * the addresses Floci sends it from. Only group references match, never CIDR rules.
     */
    public static String compile(Endpoint target, Collection<Endpoint> peers,
                                  Map<String, List<String>> prefixLists, List<String> vpcResolvers,
                                  boolean isolatedAddressSpaces, Predicate<Endpoint> admitted,
                                  Collection<EmulatorOrigin> emulatorOrigins, List<String> emulatorAddresses) {
        return compile(target, peers, prefixLists, vpcResolvers, isolatedAddressSpaces, admitted,
                emulatorOrigins, emulatorAddresses, Set.of());
    }

    public static String compile(Endpoint target, Collection<Endpoint> peers,
                                  Map<String, List<String>> prefixLists, List<String> vpcResolvers,
                                  boolean isolatedAddressSpaces, Predicate<Endpoint> admitted,
                                  Collection<EmulatorOrigin> emulatorOrigins, List<String> emulatorAddresses,
                                  Set<String> routedPublicAddresses) {
        if (target == null || target.groups() == null || target.groups().isEmpty()) {
            throw new IllegalArgumentException("Protected endpoint needs at least one security group");
        }
        StringBuilder rules = new StringBuilder("flush chain inet floci_sg ingress\n")
                .append("flush chain inet floci_sg egress\n");
        if (!target.instanceMetadataAllowed()) {
            // Lambda has no EC2 instance metadata, even after DNAT or a control-endpoint override.
            rules.append("add rule inet floci_sg egress ip daddr 169.254.169.254 drop\n")
                    .append("add rule inet floci_sg egress ip6 daddr fd00:ec2::254 drop\n")
                    .append("add rule inet floci_sg egress meta l4proto tcp"
                            + " ct original ip daddr 169.254.169.254 ct original proto-dst 80 drop\n")
                    .append("add rule inet floci_sg egress meta l4proto tcp"
                            + " ct original ip6 daddr fd00:ec2::254 ct original proto-dst 80 drop\n");
        }
        rules.append("add rule inet floci_sg ingress iifname lo accept\n")
                .append("add rule inet floci_sg egress oifname lo accept\n")
                .append("add rule inet floci_sg ingress ct state established,related accept\n")
                .append("add rule inet floci_sg egress ct state established,related accept\n");

        // Docker's embedded DNS listens on loopback but forwards from this namespace. Security
        // groups never filter the VPC resolver on AWS. Only DNS is exempt, because the resolver
        // address also serves Floci's API. DHCP and the link-local metadata endpoint are the
        // other local infrastructure exceptions.
        for (String server : vpcResolvers) {
            String family = server.contains(":") ? "ip6" : "ip";
            rules.append("add rule inet floci_sg egress ").append(family).append(" daddr ").append(server)
                    .append(" udp dport 53 accept\n")
                    .append("add rule inet floci_sg egress ").append(family).append(" daddr ").append(server)
                    .append(" tcp dport 53 accept\n");
        }
        for (ControlConnection connection : target.controlConnections()) {
            appendRule(rules, "egress", "daddr", connection.address(),
                    "tcp dport " + connection.port() + " accept");
        }
        for (ControlListener listener : target.controlListeners()) {
            appendRule(rules, "ingress", "saddr", listener.controllerAddress(),
                    "tcp dport " + listener.port() + " accept");
        }
        rules.append("add rule inet floci_sg egress udp sport 68 udp dport 67 accept\n")
                .append("add rule inet floci_sg ingress udp sport 67 udp dport 68 accept\n");
        if (target.instanceMetadataAllowed()) {
            rules.append("add rule inet floci_sg egress ip daddr 169.254.169.254 accept\n")
                // OUTPUT DNAT runs before this filter. Exempt only traffic originally sent to
                // IMDS, not direct connections to the emulator's metadata or API ports.
                .append("add rule inet floci_sg egress meta l4proto tcp"
                        + " ct original ip daddr 169.254.169.254 ct original proto-dst 80 accept\n")
                    .append("add rule inet floci_sg ingress ip saddr 169.254.169.254 accept\n");
        }

        List<Endpoint> managed = peers == null ? List.of() : peers.stream()
                .filter(peer -> !target.eniId().equals(peer.eniId()))
                .filter(peer -> peer.transportAddress() != null && literal(peer.transportAddress()))
                .toList();

        for (boolean egress : new boolean[]{false, true}) {
            String chain = egress ? "egress" : "ingress";
            String addressField = egress ? "daddr" : "saddr";
            for (SecurityGroup group : target.groups()) {
                List<IpPermission> permissions = egress
                        ? group.getIpPermissionsEgress() : group.getIpPermissions();
                if (permissions == null) {
                    continue;
                }
                for (IpPermission permission : permissions) {
                    String protocol = protocolExpression(permission);
                    if (protocol == null) {
                        continue;
                    }
                    for (Endpoint peer : managed) {
                        if (admitted.test(peer) && target.sameVpc(peer)
                                && SecurityGroupPolicy.matchesPeer(group, permission, peer.peer(), prefixLists)) {
                            for (String address : peer.transportAddresses()) {
                                appendRule(rules, chain, addressField, address, protocol);
                            }
                        }
                    }
                    if (!egress && admitsEmulatorOrigin(target, group, permission, emulatorOrigins)) {
                        for (String address : emulatorAddresses) {
                            if (literal(address)) {
                                appendRule(rules, chain, addressField, address, protocol);
                            }
                        }
                    }
                }
            }
            // A managed peer must never fall through to a broad external CIDR rule
            // evaluated against its Docker bridge IP instead of its logical ENI address.
            for (Endpoint peer : managed) {
                List<String> visible = isolatedAddressSpaces && !target.sameVpc(peer)
                        ? List.of(peer.transportAddress()) : peer.transportAddresses();
                for (String address : visible) {
                    if (egress && admitted.test(peer) && routedPublicAddresses.contains(address)) {
                        // The native route policy controls its NAT/IGW path; evaluate public egress CIDRs below.
                        continue;
                    }
                    appendRule(rules, chain, addressField, address, "drop");
                }
            }
            for (SecurityGroup group : target.groups()) {
                List<IpPermission> permissions = egress
                        ? group.getIpPermissionsEgress() : group.getIpPermissions();
                if (permissions == null) {
                    continue;
                }
                for (IpPermission permission : permissions) {
                    String protocol = protocolExpression(permission);
                    if (protocol == null) {
                        continue;
                    }
                    Set<String> cidrs = new LinkedHashSet<>();
                    if (permission.getIpRanges() != null) {
                        permission.getIpRanges().forEach(range -> cidrs.add(range.getCidrIp()));
                    }
                    if (permission.getIpv6Ranges() != null) {
                        permission.getIpv6Ranges().forEach(range -> cidrs.add(range.getCidrIpv6()));
                    }
                    if (permission.getPrefixListIds() != null && prefixLists != null) {
                        permission.getPrefixListIds().forEach(ref ->
                                cidrs.addAll(prefixLists.getOrDefault(ref.getPrefixListId(), List.of())));
                    }
                    for (String cidr : cidrs) {
                        if (SecurityGroupPolicy.validCidr(cidr)) {
                            appendRule(rules, chain, addressField, cidr, protocol);
                        }
                    }
                }
            }
        }
        if (isolatedAddressSpaces) {
            // Native private addresses can repeat across VPCs. The resolver identifies a
            // namespace by its unique control address, including when a client binds its ENI.
            if (!literal(target.transportAddress()) || target.transportAddress().contains(":")) {
                throw new IllegalArgumentException("Isolated IPv4 VPCs require an IPv4 control transport");
            }
            rules.append("add table ip floci_vpc_dns\n")
                    .append("add chain ip floci_vpc_dns source { type nat hook postrouting priority srcnat; }\n")
                    .append("flush chain ip floci_vpc_dns source\n");
            for (String resolver : vpcResolvers) {
                if (literal(resolver) && !resolver.contains(":")) {
                    rules.append("add rule ip floci_vpc_dns source ip daddr ").append(resolver)
                            .append(" meta l4proto { tcp, udp } th dport 53 snat to ")
                            .append(target.transportAddress()).append('\n');
                }
            }
        }
        return rules.toString();
    }

    private static boolean admitsEmulatorOrigin(Endpoint target, SecurityGroup group, IpPermission permission,
                                                Collection<EmulatorOrigin> origins) {
        if (origins == null || permission.getUserIdGroupPairs() == null
                || permission.getUserIdGroupPairs().isEmpty()) {
            return false;
        }
        for (EmulatorOrigin origin : origins) {
            if (target.accountId().equals(origin.accountId()) && target.region().equals(origin.region())
                    && target.vpcId().equals(origin.vpcId())
                    && SecurityGroupPolicy.matchesPeer(group, permission,
                            new SecurityGroupPolicy.Peer(null, origin.accountId(), origin.vpcId(),
                                    origin.groupIds()), Map.of())) {
                return true;
            }
        }
        return false;
    }

    private static void appendRule(StringBuilder rules, String chain, String addressField,
                                   String address, String expression) {
        rules.append("add rule inet floci_sg ").append(chain).append(' ')
                .append(address.indexOf(':') >= 0 ? "ip6 " : "ip ")
                .append(addressField).append(' ').append(address).append(' ')
                .append(expression).append('\n');
    }

    private static String protocolExpression(IpPermission permission) {
        String protocol = permission.getIpProtocol();
        if (protocol == null) {
            return null;
        }
        if ("-1".equals(protocol)) {
            return "accept";
        }
        String normalized = switch (protocol.toLowerCase(Locale.ROOT)) {
            case "6" -> "tcp";
            case "17" -> "udp";
            case "1" -> "icmp";
            case "58" -> "icmpv6";
            default -> protocol.toLowerCase(Locale.ROOT);
        };
        if ("tcp".equals(normalized) || "udp".equals(normalized)) {
            Integer from = permission.getFromPort();
            Integer to = permission.getToPort();
            if (from == null || to == null || from < 0 || to > 65535 || to < from) {
                return null;
            }
            return normalized + " dport " + (from.equals(to) ? from : from + "-" + to) + " accept";
        }
        if ("icmp".equals(normalized) || "icmpv6".equals(normalized)) {
            // nftables header fields are separate expressions, so type and code each repeat
            // the protocol keyword: "icmp type 8 icmp code 0", never "icmp type 8 code 0".
            StringBuilder expression = new StringBuilder();
            Integer type = permission.getFromPort();
            Integer code = permission.getToPort();
            if (type != null && type >= 0) {
                expression.append(normalized).append(" type ").append(type).append(' ');
            }
            if (code != null && code >= 0) {
                expression.append(normalized).append(" code ").append(code).append(' ');
            }
            if (expression.isEmpty()) {
                expression.append("meta l4proto ").append(normalized).append(' ');
            }
            return expression.append("accept").toString();
        }
        try {
            int number = Integer.parseInt(normalized);
            return number >= 0 && number <= 255 ? "meta l4proto " + number + " accept" : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean literal(String address) {
        try {
            if (address == null || address.contains("%")
                    || !(address.contains(":") ? address.matches("[0-9a-fA-F:.]+")
                    : address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) {
                return false;
            }
            InetAddress.getByName(address);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
