package io.github.hectorvent.floci.services.ec2.net;

import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.Route;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Public frontend sockets use the subnet's native return route before security-group admission. */
record VpcFrontendIngress(String address, String device, Set<Integer> ports, List<Decision> routes) {
    record Decision(Cidr4 destination, boolean internet) {}

    VpcFrontendIngress {
        address = VpcRouteSelection.ipv4(address);
        device = VpcRoutingCommands.device(device);
        ports = checkedPorts(ports);
        routes = routes.stream().sorted(Comparator.comparingInt(
                (Decision route) -> route.destination().prefix()).reversed()).toList();
    }

    static Set<Integer> checkedPorts(Set<Integer> ports) {
        Set<Integer> checked = Set.copyOf(ports);
        if (checked.stream().anyMatch(port -> port < 1 || port > 65535)) {
            throw new IllegalArgumentException("Public frontend listeners require valid TCP ports");
        }
        return checked;
    }

    static List<Decision> decisions(String vpc, List<VpcRouteSelection.Entry> entries,
                                    List<InternetGateway> gateways) {
        return entries.stream().map(VpcRouteSelection.Entry::destination).distinct().map(destination -> {
            // Reject conflicting targets even if another prefix shadows this prefix's first IP.
            List<VpcRouteSelection.Entry> samePrefix = entries.stream()
                    .filter(entry -> entry.destination().equals(destination)).toList();
            Route selected = VpcRouteSelection.to(destination.addressAt(0).orElseThrow(), samePrefix).orElseThrow();
            return new Decision(destination, VpcRouteSelection.internet(selected, vpc, gateways));
        }).toList();
    }

    void appendIngress(StringBuilder rules) {
        append(rules, "ingress", "iifname \"" + device + "\" ip daddr " + address
                + " tcp dport " + portSet() + " ", "ip saddr ");
        rules.append("add rule inet floci_vpc_routes ingress iifname \"").append(device)
                .append("\" ip daddr ").append(address).append(" counter drop\n");
    }

    void appendReply(StringBuilder rules) {
        append(rules, "egress", "oifname \"" + device + "\" meta l4proto tcp ct direction reply"
                + " ct original ip daddr " + address + " ct original proto-dst " + portSet() + " ", "ip daddr ");
        rules.append("add rule inet floci_vpc_routes egress oifname \"").append(device)
                .append("\" ct direction reply ct original ip daddr ").append(address).append(" counter drop\n");
    }

    private void append(StringBuilder rules, String chain, String socket, String peer) {
        if (ports.isEmpty()) {
            return;
        }
        for (Decision route : routes) {
            rules.append("add rule inet floci_vpc_routes ").append(chain).append(' ').append(socket)
                    .append(peer).append(route.destination())
                    .append(route.internet() ? " counter accept\n" : " counter drop\n");
        }
    }

    private String portSet() {
        return ports.stream().sorted().map(String::valueOf).collect(Collectors.joining(", ", "{ ", " }"));
    }
}
