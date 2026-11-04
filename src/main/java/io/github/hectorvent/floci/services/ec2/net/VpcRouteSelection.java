package io.github.hectorvent.floci.services.ec2.net;

import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.Route;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Selects a native subnet route before any local packet path is admitted. */
public final class VpcRouteSelection {
    private VpcRouteSelection() {}

    public record Entry(Cidr4 destination, Route route) {}

    public static List<Entry> entries(String vpc, String subnet, List<RouteTable> tables,
                                     Function<String, List<String>> prefixLists) {
        List<RouteTable> owned = tables.stream().filter(table -> vpc.equals(table.getVpcId())).toList();
        List<RouteTable> explicit = owned.stream().filter(table -> table.getAssociations().stream()
                .anyMatch(association -> !association.isMain() && subnet.equals(association.getSubnetId())
                        && "associated".equals(association.getAssociationState()))).toList();
        List<RouteTable> selected = explicit.isEmpty()
                ? owned.stream().filter(table -> table.getAssociations().stream()
                        .anyMatch(association -> association.isMain()
                                && "associated".equals(association.getAssociationState()))).toList()
                : explicit;
        if (selected.size() > 1) {
            throw new IllegalStateException("Subnet has ambiguous native route-table associations: " + subnet);
        }
        List<Entry> result = new ArrayList<>();
        for (RouteTable table : selected) {
            for (Route route : table.getRoutes()) {
                if (route.getDestinationCidrBlock() != null) {
                    result.add(new Entry(cidr(route.getDestinationCidrBlock()), route));
                } else if (route.getDestinationPrefixListId() != null) {
                    prefixLists.apply(route.getDestinationPrefixListId()).stream()
                            .filter(value -> !value.contains(":"))
                            .forEach(value -> result.add(new Entry(cidr(value), route)));
                }
            }
        }
        result.sort(Comparator.comparingInt((Entry entry) -> entry.destination().prefix()).reversed());
        return List.copyOf(result);
    }

    public static Optional<Route> to(String address, List<Entry> entries) {
        long destination = cidr(ipv4(address) + "/32").network();
        List<Entry> matches = entries.stream().filter(entry -> entry.destination().containsAddress(destination))
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        Entry selected = matches.getFirst();
        if (matches.stream().skip(1).anyMatch(entry -> entry.destination().prefix() == selected.destination().prefix()
                && !sameTarget(entry.route(), selected.route()))) {
            throw new IllegalStateException("Conflicting native routes for " + address);
        }
        return Optional.of(selected.route());
    }

    public static boolean local(Route route) {
        return usable(route) && "local".equals(route.getGatewayId());
    }

    public static boolean internet(Route route, String vpc, List<InternetGateway> gateways) {
        return usable(route) && route.getGatewayId() != null
                && gateways.stream().anyMatch(gateway -> route.getGatewayId().equals(gateway.getInternetGatewayId())
                        && gateway.getAttachments().stream().anyMatch(attachment -> vpc.equals(attachment.getVpcId())
                                && "available".equals(attachment.getState())));
    }

    public static Optional<NatGateway> nat(Route route, String vpc, List<NatGateway> gateways) {
        if (!usable(route) || route.getNatGatewayId() == null) {
            return Optional.empty();
        }
        return gateways.stream().filter(gateway -> route.getNatGatewayId().equals(gateway.getNatGatewayId())
                && vpc.equals(gateway.getVpcId()) && "available".equals(gateway.getState())
                && "public".equals(gateway.getConnectivityType())
                && gateway.getNatGatewayAddresses().size() == 1
                && gateway.getNatGatewayAddresses().getFirst().getPublicIp() != null).findFirst();
    }

    private static boolean usable(Route route) {
        if (route == null || !"active".equals(route.getState())) {
            return false;
        }
        int targets = 0;
        for (String target : new String[]{route.getGatewayId(), route.getNatGatewayId(), route.getInstanceId(),
                route.getNetworkInterfaceId(), route.getVpcPeeringConnectionId(),
                route.getEgressOnlyInternetGatewayId()}) {
            if (target != null && !target.isBlank()) {
                targets++;
            }
        }
        return targets == 1;
    }

    private static boolean sameTarget(Route first, Route second) {
        return target(first).equals(target(second));
    }

    private static List<String> target(Route route) {
        return List.of(String.valueOf(route.getState()), String.valueOf(route.getGatewayId()),
                String.valueOf(route.getNatGatewayId()), String.valueOf(route.getInstanceId()),
                String.valueOf(route.getNetworkInterfaceId()), String.valueOf(route.getVpcPeeringConnectionId()),
                String.valueOf(route.getEgressOnlyInternetGatewayId()));
    }

    public static Cidr4 cidr(String value) {
        return Cidr4.parse(value).orElseThrow(() -> new IllegalArgumentException("Expected IPv4 CIDR: " + value));
    }

    public static String ipv4(String value) {
        if (value == null || !value.matches("[0-9]+(?:\\.[0-9]+){3}")) {
            throw new IllegalArgumentException("Expected literal IPv4 address");
        }
        Cidr4 parsed = cidr(value + "/32");
        if (!parsed.addressAt(0).orElseThrow().equals(value)) {
            throw new IllegalArgumentException("Expected canonical IPv4 address");
        }
        return value;
    }
}
