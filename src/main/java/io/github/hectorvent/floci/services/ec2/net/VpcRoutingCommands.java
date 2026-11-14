package io.github.hectorvent.floci.services.ec2.net;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler.ControlConnection;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler.ControlListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Linux commands for owned namespaces; no command addresses the Docker host. */
final class VpcRoutingCommands {
    private static final String NATIVE_API_TRANSLATION =
            "meta l4proto tcp ct direction original ct status dnat ct original ip daddr "
                    + EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS;

    private VpcRoutingCommands() {}

    record Hop(String destination, String gateway, String device, boolean replyOnly) {
        Hop {
            destination = VpcRouteSelection.ipv4(destination);
            gateway = VpcRouteSelection.ipv4(gateway);
            device = VpcRoutingCommands.device(device);
        }
    }

    record LocalRoute(Cidr4 destination, boolean allowed) {}

    record NativeApi(String upstreamAddress, int httpPort, int httpsPort, String publicDevice) {
        NativeApi {
            upstreamAddress = VpcRouteSelection.ipv4(upstreamAddress);
            publicDevice = device(publicDevice);
            if (EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS.equals(upstreamAddress)
                    || EmulatorConfig.VpcRoutingConfig.PUBLIC_ALB_HTTPS_ADDRESS.equals(upstreamAddress)) {
                throw new IllegalArgumentException("Native public AWS APIs require a distinct upstream address");
            }
            if (httpPort < 1 || httpPort > 65535 || httpsPort < 1 || httpsPort > 65535) {
                throw new IllegalArgumentException("Native public AWS APIs require valid upstream TCP ports");
            }
        }
    }

    static String endpointGuard() {
        return endpointGuard(List.of());
    }

    static String endpointGuard(List<String> resolvers) {
        StringBuilder rules = new StringBuilder("""
                add table inet floci_vpc_routes
                add chain inet floci_vpc_routes ingress { type filter hook input priority -5; policy drop; }
                add chain inet floci_vpc_routes egress { type filter hook output priority -5; policy drop; }
                flush chain inet floci_vpc_routes ingress
                flush chain inet floci_vpc_routes egress
                """);
        appendResolverEgress(rules, resolvers);
        for (String resolver : resolvers) {
            if (!resolver.contains(":")) {
                String address = VpcRouteSelection.ipv4(resolver);
                for (String protocol : List.of("udp", "tcp")) {
                    rules.append("add rule inet floci_vpc_routes ingress ip saddr ").append(address)
                            .append(' ').append(protocol).append(" sport 53")
                            .append(" ct state established ct direction reply counter accept\n");
                }
            }
        }
        return "set -eu\n" + nft(rules.toString());
    }

    private static void appendResolverEgress(StringBuilder rules, List<String> resolvers) {
        for (String resolver : resolvers) {
            if (!resolver.contains(":")) {
                String address = VpcRouteSelection.ipv4(resolver);
                rules.append("add rule inet floci_vpc_routes egress ip daddr ").append(address)
                        .append(" udp dport 53 counter accept\n")
                        .append("add rule inet floci_vpc_routes egress ip daddr ").append(address)
                        .append(" tcp dport 53 counter accept\n");
            }
        }
    }

    static String endpoint(String privateAddress, String privateDevice, List<LocalRoute> localRoutes, List<Hop> hops,
                           List<String> deniedDestinations, List<String> resolvers, List<ControlConnection> controls,
                           boolean instanceMetadataAllowed, String publicDevice, List<String> inboundPublicSources) {
        return endpoint(privateAddress, privateDevice, localRoutes, hops, deniedDestinations, resolvers,
                controls, instanceMetadataAllowed, publicDevice, inboundPublicSources, List.of());
    }

    static String endpoint(String privateAddress, String privateDevice, List<LocalRoute> localRoutes, List<Hop> hops,
                           List<String> deniedDestinations, List<String> resolvers, List<ControlConnection> controls,
                           boolean instanceMetadataAllowed, String publicDevice, List<String> inboundPublicSources,
                           List<ControlListener> listeners) {
        return endpoint(privateAddress, privateDevice, localRoutes, hops, deniedDestinations, resolvers,
                controls, instanceMetadataAllowed, publicDevice, inboundPublicSources, listeners, null);
    }

    static String endpoint(String privateAddress, String privateDevice, List<LocalRoute> localRoutes, List<Hop> hops,
                           List<String> deniedDestinations, List<String> resolvers, List<ControlConnection> controls,
                           boolean instanceMetadataAllowed, String publicDevice, List<String> inboundPublicSources,
                           List<ControlListener> listeners, NativeApi nativeApi) {
        String source = VpcRouteSelection.ipv4(privateAddress);
        StringBuilder script = new StringBuilder("set -eu\nip -4 route flush proto 186\n");
        for (Hop hop : hops) {
            script.append("ip -4 route replace ").append(hop.destination()).append("/32 via ")
                    .append(hop.gateway()).append(" dev ").append(hop.device())
                    .append(" src ").append(source).append(" proto 186\n");
        }
        // A route lookup must reach OUTPUT for its drop policy to model a packet timeout.
        for (String destination : deniedDestinations) {
            script.append("ip -4 route replace ").append(VpcRouteSelection.ipv4(destination))
                    .append("/32 dev ").append(device(privateDevice)).append(" src ").append(source)
                    .append(" proto 186\n");
        }
        StringBuilder rules = new StringBuilder("flush chain inet floci_vpc_routes ingress\n")
                .append("flush chain inet floci_vpc_routes egress\n")
                .append("add rule inet floci_vpc_routes egress oifname lo counter accept\n");
        if (publicDevice != null) {
            String device = device(publicDevice);
            for (String address : inboundPublicSources) {
                rules.append("add rule inet floci_vpc_routes ingress iifname \"").append(device)
                        .append("\" ip saddr ").append(VpcRouteSelection.ipv4(address)).append(" counter accept\n");
            }
            rules.append("add rule inet floci_vpc_routes ingress iifname \"").append(device)
                    .append("\" counter drop\n");
        }
        rules.append("add rule inet floci_vpc_routes ingress counter accept\n");
        if (instanceMetadataAllowed) {
            // OUTPUT DNAT has already selected the metadata server. Keep the original
            // link-local destination check so its direct backend listener is not exempt.
            rules.append("add rule inet floci_vpc_routes egress meta l4proto tcp"
                    + " ct original ip daddr 169.254.169.254 ct original proto-dst 80 counter accept\n");
        }
        appendNativeApiRoute(rules, nativeApi);
        appendResolverEgress(rules, resolvers);
        for (ControlConnection control : controls) {
            if (!control.address().contains(":")) {
                rules.append("add rule inet floci_vpc_routes egress ip daddr ")
                        .append(VpcRouteSelection.ipv4(control.address())).append(" tcp dport ")
                        .append(control.port()).append(" counter accept\n");
            }
        }
        for (ControlListener listener : listeners) {
            String family = listener.controllerAddress().contains(":") ? "ip6" : "ip";
            rules.append("add rule inet floci_vpc_routes egress ").append(family).append(" daddr ")
                    .append(listener.controllerAddress()).append(" tcp sport ").append(listener.port())
                    .append(" ct direction reply ct original ").append(family).append(" saddr ")
                    .append(listener.controllerAddress()).append(" ct original proto-dst ")
                    .append(listener.port()).append(" counter accept\n");
        }
        for (Hop hop : hops) {
            rules.append("add rule inet floci_vpc_routes egress ip daddr ").append(hop.destination())
                    .append(" oifname \"").append(hop.device()).append("\" ");
            if (hop.replyOnly()) {
                rules.append("ct state established,related ");
            }
            rules.append("counter accept\n");
        }
        for (String destination : deniedDestinations) {
            rules.append("add rule inet floci_vpc_routes egress ip daddr ")
                    .append(VpcRouteSelection.ipv4(destination)).append(" counter drop\n");
        }
        for (LocalRoute route : localRoutes) {
            rules.append("add rule inet floci_vpc_routes egress ip daddr ").append(route.destination())
                    .append(route.allowed() ? " counter accept\n" : " counter drop\n");
        }
        rules.append("add rule inet floci_vpc_routes egress counter drop\n");
        return script.append(nft(rules.toString())).toString();
    }

    private static void appendNativeApiRoute(StringBuilder rules, NativeApi api) {
        if (api != null) {
            for (int port : List.of(80, 443)) {
                int upstreamPort = port == 80 ? api.httpPort() : api.httpsPort();
                rules.append("add rule inet floci_vpc_routes egress ").append(NATIVE_API_TRANSLATION)
                        .append(" ct original proto-dst ").append(port)
                        .append(" ip daddr ").append(api.upstreamAddress())
                        .append(" tcp dport ").append(upstreamPort).append(" counter accept\n");
            }
        }
        // A retired route also denies existing translations before physical-address exceptions.
        rules.append("add rule inet floci_vpc_routes egress ").append(NATIVE_API_TRANSLATION).append(" counter drop\n");
    }

    static String publicAddress(String publicAddress, String privateAddress, String publicDevice) {
        String external = VpcRouteSelection.ipv4(publicAddress);
        String internal = VpcRouteSelection.ipv4(privateAddress);
        String device = device(publicDevice);
        return "set -eu\nip -4 route flush default dev " + device + "\n"
                + "ip -4 address replace " + external + "/32 dev lo\n"
                + nft("add table ip floci_public_address\n"
                + "add chain ip floci_public_address incoming { type nat hook prerouting priority dstnat; }\n"
                + "add chain ip floci_public_address outgoing { type nat hook postrouting priority srcnat; }\n"
                + "flush chain ip floci_public_address incoming\n"
                + "flush chain ip floci_public_address outgoing\n"
                + "add rule ip floci_public_address incoming iifname \"" + device + "\" ip daddr "
                + external + " dnat to " + internal + "\n"
                + "add rule ip floci_public_address outgoing oifname \"" + device + "\" ip saddr "
                + internal + " snat to " + external + "\n");
    }

    static String publicInstanceSource(String privateAddress, String publicAddress, String publicDevice) {
        return publicInstanceSource(privateAddress, publicAddress, publicDevice,
                List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS));
    }

    static String publicInstanceSource(String privateAddress, String publicAddress, String publicDevice,
                                       List<String> destinations) {
        return publicInstanceSources(privateAddress, publicAddress, publicAddress == null ? List.of()
                : destinations.stream().map(destination -> new Hop(destination, destination, publicDevice, false))
                        .toList());
    }

    static String publicInstanceSources(String privateAddress, String publicAddress, List<Hop> destinations) {
        String rules = """
                add table ip floci_instance_api
                add chain ip floci_instance_api outgoing { type nat hook postrouting priority srcnat; }
                flush chain ip floci_instance_api outgoing
                """;
        if (publicAddress != null) {
            for (Hop destination : destinations.stream().distinct()
                    .sorted(Comparator.comparing(Hop::destination).thenComparing(Hop::device)).toList()) {
                rules += "add rule ip floci_instance_api outgoing oifname \"" + destination.device()
                        + "\" ip saddr " + VpcRouteSelection.ipv4(privateAddress) + " ip daddr "
                        + destination.destination()
                        + " snat to " + VpcRouteSelection.ipv4(publicAddress) + "\n";
            }
        }
        return nft(rules);
    }

    /** Keep both translation directions in the caller namespace; filters inspect the original API tuple. */
    static String nativePublicApi(String privateAddress, String publicAddress, NativeApi api) {
        StringBuilder rules = new StringBuilder("""
                add table ip floci_native_api
                add chain ip floci_native_api destination { type nat hook output priority dstnat; }
                add chain ip floci_native_api egress { type filter hook postrouting priority filter; }
                add chain ip floci_native_api source { type nat hook postrouting priority srcnat; }
                flush chain ip floci_native_api destination
                flush chain ip floci_native_api egress
                flush chain ip floci_native_api source
                """);
        if (api != null) {
            String original = EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS;
            String logical = VpcRouteSelection.ipv4(privateAddress);
            String external = VpcRouteSelection.ipv4(publicAddress);
            for (int port : List.of(80, 443)) {
                int upstreamPort = port == 80 ? api.httpPort() : api.httpsPort();
                rules.append("add rule ip floci_native_api destination ip daddr ").append(original)
                        .append(" tcp dport ").append(port).append(" dnat to ")
                        .append(api.upstreamAddress()).append(':').append(upstreamPort).append('\n');
                // OUTPUT retains the original route's device after DNAT; POSTROUTING has the actual device.
                rules.append("add rule ip floci_native_api egress ").append(NATIVE_API_TRANSLATION)
                        .append(" ct original proto-dst ").append(port)
                        .append(" ip daddr ").append(api.upstreamAddress())
                        .append(" tcp dport ").append(upstreamPort)
                        .append(" oifname \"").append(api.publicDevice()).append("\" counter accept\n");
                rules.append("add rule ip floci_native_api source oifname \"").append(api.publicDevice())
                        .append("\" ip saddr ").append(logical).append(" ip daddr ").append(api.upstreamAddress())
                        .append(" tcp dport ").append(upstreamPort)
                        .append(" ct original ip daddr ").append(original)
                        .append(" ct original proto-dst ").append(port)
                        .append(" snat to ").append(external).append('\n');
            }
        }
        rules.append("add rule ip floci_native_api egress ").append(NATIVE_API_TRANSLATION).append(" counter drop\n");
        return nft(rules.toString());
    }

    static String natGuard() {
        return """
                set -eu
                nft add table inet floci_public_nat
                nft 'add chain inet floci_public_nat forwarding { type filter hook forward priority -5; policy drop; }'
                nft 'add chain inet floci_public_nat incoming { type filter hook input priority -5; policy drop; }'
                nft 'add chain inet floci_public_nat local_output { type filter hook output priority -5; policy drop; }'
                nft 'add chain inet floci_public_nat outgoing { type nat hook postrouting priority srcnat; }'
                nft -f - <<'FLOCI_RULES'
                flush chain inet floci_public_nat forwarding
                flush chain inet floci_public_nat outgoing
                FLOCI_RULES
                """;
    }

    static String nat(String publicAddress, Cidr4 vpc, String privateDevice, String publicDevice,
                      List<Hop> destinations) {
        String external = VpcRouteSelection.ipv4(publicAddress);
        String inside = device(privateDevice);
        String outside = device(publicDevice);
        if (inside.equals(outside)) {
            throw new IllegalArgumentException("A NAT gateway requires separate private and public interfaces");
        }
        StringBuilder script = new StringBuilder("set -eu\nip -4 route flush default\nip -4 route flush proto 186\n")
                .append("ip -4 address replace ").append(external).append("/32 dev lo\n");
        StringBuilder rules = new StringBuilder("flush chain inet floci_public_nat forwarding\n")
                .append("flush chain inet floci_public_nat outgoing\n");
        for (Hop destination : destinations) {
            if (!outside.equals(destination.device()) || destination.replyOnly()) {
                throw new IllegalArgumentException("NAT destinations must use the public transport interface");
            }
            String address = destination.destination();
            script.append("ip -4 route replace ").append(address).append("/32 via ")
                    .append(destination.gateway()).append(" dev ").append(outside).append(" proto 186\n");
            rules.append("add rule inet floci_public_nat forwarding iifname \"").append(inside)
                    .append("\" oifname \"").append(outside).append("\" ip saddr ").append(vpc)
                    .append(" ip daddr ").append(address)
                    .append(" meta l4proto { tcp, udp, icmp } counter accept\n")
                    .append("add rule inet floci_public_nat forwarding iifname \"").append(outside)
                    .append("\" oifname \"").append(inside).append("\" ip saddr ").append(address)
                    .append(" ip daddr ").append(vpc).append(" ct state established,related counter accept\n")
                    .append("add rule inet floci_public_nat outgoing oifname \"").append(outside)
                    .append("\" ip saddr ").append(vpc).append(" ip daddr ").append(address)
                    .append(" snat ip to ").append(external).append('\n');
        }
        return script.append(nft(rules.toString())).toString();
    }

    static String publicApi(String publicAddress, String backendAddress, int httpPort, int httpsPort,
                            String publicDevice, List<Hop> callers) {
        return publicApi(publicAddress, backendAddress, httpPort, backendAddress, httpsPort, publicDevice, callers);
    }

    static String publicApi(String publicAddress, String httpAddress, int httpPort, String httpsAddress, int httpsPort,
                            String publicDevice, List<Hop> callers) {
        return publicApi(publicAddress, httpAddress, httpPort, httpsAddress, httpsPort, publicDevice, callers,
                null, List.of());
    }

    static String publicApi(String publicAddress, String httpAddress, int httpPort, String httpsAddress, int httpsPort,
                            String publicDevice, List<Hop> callers, String albAddress, List<Hop> albCallers) {
        String external = VpcRouteSelection.ipv4(publicAddress);
        String httpBackend = VpcRouteSelection.ipv4(httpAddress);
        String httpsBackend = VpcRouteSelection.ipv4(httpsAddress);
        String outside = device(publicDevice);
        String albBackend = albAddress == null ? null : VpcRouteSelection.ipv4(albAddress);
        String albPublic = EmulatorConfig.VpcRoutingConfig.PUBLIC_ALB_HTTPS_ADDRESS;
        if (httpPort < 1 || httpPort > 65535 || httpsPort < 1 || httpsPort > 65535) {
            throw new IllegalArgumentException("Public AWS APIs require valid upstream TCP ports");
        }
        if (albPublic.equals(external)) {
            throw new IllegalArgumentException("Public AWS API and native ALB destinations must be distinct");
        }
        for (String upstream : List.of(httpBackend, httpsBackend)) {
            if (external.equals(upstream) || albPublic.equals(upstream)) {
                throw new IllegalArgumentException("Public AWS API upstream must differ from public destinations");
            }
        }
        if (albBackend != null && (albPublic.equals(albBackend) || external.equals(albBackend))) {
            throw new IllegalArgumentException("Native ALB upstream must differ from public destinations");
        }
        if (albBackend == null && !albCallers.isEmpty()) {
            throw new IllegalArgumentException("Native ALB callers require their own upstream");
        }
        StringBuilder script = new StringBuilder("set -eu\nip -4 route flush default dev ")
                .append(outside).append("\nip -4 route flush proto 186\n")
                .append("ip -4 address replace ").append(external).append("/32 dev lo\n");
        if (albBackend != null) {
            script.append("ip -4 address replace ").append(albPublic).append("/32 dev lo\n");
        }
        StringBuilder rules = new StringBuilder("""
                add table inet floci_public_api
                add chain inet floci_public_api ingress { type filter hook input priority -5; policy drop; }
                add chain inet floci_public_api egress { type filter hook output priority -5; policy drop; }
                flush chain inet floci_public_api ingress
                flush chain inet floci_public_api egress
                add rule inet floci_public_api ingress iifname lo counter accept
                add rule inet floci_public_api egress oifname lo counter accept
                """);
        List<Backend> backends = new ArrayList<>(List.of(
                new Backend(httpBackend, httpPort), new Backend(httpsBackend, httpsPort)));
        if (albBackend != null) {
            backends.add(new Backend(albBackend, 443));
        }
        for (Backend backend : backends.stream().distinct().toList()) {
            rules.append("add rule inet floci_public_api ingress ip saddr ").append(backend.address())
                    .append(" tcp sport ").append(backend.port()).append(" ct state established,related counter accept\n")
                    .append("add rule inet floci_public_api egress ip daddr ").append(backend.address())
                    .append(" tcp dport ").append(backend.port()).append(" counter accept\n");
        }
        List<Hop> replies = new ArrayList<>(callers);
        replies.addAll(albCallers);
        for (Hop caller : replies.stream().distinct().toList()) {
            if (!caller.replyOnly()) {
                throw new IllegalArgumentException("Public API routes must only permit replies");
            }
            script.append("ip -4 route replace ").append(caller.destination()).append("/32 via ")
                    .append(caller.gateway()).append(" dev ").append(caller.device())
                    .append(" src ").append(external).append(" proto 186\n");
        }
        publicCallerRules(rules, callers, external, "{ 80, 443 }");
        publicCallerRules(rules, albCallers, albPublic, "{ 80, 443 }");
        return script.append(nft(rules.toString())).toString();
    }

    private static void publicCallerRules(StringBuilder rules, List<Hop> callers, String address, String ports) {
        for (Hop caller : callers) {
            rules.append("add rule inet floci_public_api ingress iifname \"").append(caller.device())
                    .append("\" ip saddr ").append(caller.destination()).append(" ip daddr ").append(address)
                    .append(" tcp dport ").append(ports).append(" counter accept\n")
                    .append("add rule inet floci_public_api egress oifname \"").append(caller.device())
                    .append("\" ip saddr ").append(address).append(" ip daddr ").append(caller.destination())
                    .append(" tcp sport ").append(ports).append(" ct state established,related counter accept\n");
        }
    }

    private record Backend(String address, int port) {}

    static String device(String value) {
        Objects.requireNonNull(value, "Network device");
        if (!value.matches("[a-zA-Z0-9_.-]+") || "lo".equals(value)) {
            throw new IllegalArgumentException("Expected one non-loopback network interface");
        }
        return value;
    }

    private static String nft(String rules) {
        return "nft -f - <<'FLOCI_RULES'\n" + rules + "FLOCI_RULES\n";
    }
}
