package io.floci.aws.services.ec2.net;

import java.util.Comparator;
import java.util.List;

/** Native EC2 public-address connections retain their subnet's internet return-route policy. */
record VpcPublicInstanceIngress(String address, String privateAddress, String device,
                                List<VpcFrontendIngress.Decision> routes) {
    VpcPublicInstanceIngress {
        address = VpcRouteSelection.ipv4(address);
        privateAddress = VpcRouteSelection.ipv4(privateAddress);
        device = VpcRoutingCommands.device(device);
        routes = routes.stream().sorted(Comparator.comparingInt(
                (VpcFrontendIngress.Decision route) -> route.destination().prefix()).reversed()).toList();
    }

    void append(StringBuilder rules) {
        String incoming = "iifname \"" + device + "\" ip daddr " + privateAddress
                + " ct direction original ct status dnat ct original ip daddr " + address;
        String reply = "oifname \"" + device + "\" ip saddr " + privateAddress
                + " ct direction reply ct status dnat ct original ip daddr " + address;
        for (VpcFrontendIngress.Decision route : routes) {
            String action = route.internet() ? " counter accept\n" : " counter drop\n";
            rules.append("add rule inet floci_vpc_routes ingress ").append(incoming)
                    .append(" ip saddr ").append(route.destination()).append(action);
            rules.append("add rule inet floci_vpc_routes egress ").append(reply)
                    .append(" ip daddr ").append(route.destination()).append(action);
        }
        rules.append("add rule inet floci_vpc_routes ingress ").append(incoming).append(" counter drop\n");
        rules.append("add rule inet floci_vpc_routes egress ").append(reply).append(" counter drop\n");
    }
}
