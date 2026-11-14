package io.github.hectorvent.floci.config;

import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Socket identities admitted by the managed public bridge's Docker and route lifecycle. */
@ApplicationScoped
public class PublicApiProxySources {
    private final Map<String, Admission> admissions = new HashMap<>();

    private record Admission(Object owner, Map<String, Set<String>> callers) {}

    public record Addresses(SocketAddress source, SocketAddress destination) {}

    public static final class Ticket {
        private final String peer;
        private final Admission admission;

        private Ticket(String peer, Admission admission) {
            this.peer = peer;
            this.admission = admission;
        }
    }

    public synchronized void register(Object owner, String peer, Set<String> callers) {
        register(owner, peer, Map.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS, callers));
    }

    /** Each routed destination retains its own admitted caller set. */
    public synchronized void register(Object owner, String peer, Map<String, Set<String>> callers) {
        if (owner == null) {
            throw new IllegalArgumentException("A public bridge requires its lifecycle owner");
        }
        String address = ipv4(peer);
        Map<String, Set<String>> checked = new HashMap<>();
        for (Map.Entry<String, Set<String>> destination : callers.entrySet()) {
            if (!EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS.equals(destination.getKey())
                    && !EmulatorConfig.VpcRoutingConfig.PUBLIC_ALB_HTTPS_ADDRESS.equals(destination.getKey())) {
                throw invalid();
            }
            checked.put(destination.getKey(), destination.getValue().stream().map(PublicApiProxySources::ipv4)
                    .collect(Collectors.toUnmodifiableSet()));
        }
        Admission existing = admissions.get(address);
        if (existing != null && existing.owner() != owner) {
            throw new IllegalStateException("Another public bridge owns this source socket address");
        }
        admissions.put(address, new Admission(owner, Map.copyOf(checked)));
    }

    public synchronized void revoke(Object owner) {
        admissions.values().removeIf(value -> value.owner() == owner);
    }

    public synchronized Ticket ticket(String peer) {
        Admission admission = admissions.get(peer);
        return admission == null ? null : new Ticket(peer, admission);
    }

    public synchronized Addresses decode(Ticket ticket, String preface) {
        Admission admission = ticket == null ? null : admissions.get(ticket.peer);
        if (admission == null || admission != ticket.admission
                || preface.length() > 108 || !preface.endsWith("\r\n")) {
            throw invalid();
        }
        String[] fields = preface.substring(0, preface.length() - 2).split(" ", -1);
        if (fields.length != 6 || !"PROXY".equals(fields[0]) || !"TCP4".equals(fields[1])
                || !admission.callers().getOrDefault(fields[3], Set.of()).contains(fields[2])
                || !"443".equals(fields[5])) {
            throw invalid();
        }
        String source = ipv4(fields[2]);
        if (!fields[4].matches("[1-9][0-9]{0,4}")) {
            throw invalid();
        }
        int port = Integer.parseInt(fields[4]);
        if (port > 65535) {
            throw invalid();
        }
        return new Addresses(SocketAddress.inetSocketAddress(port, source),
                SocketAddress.inetSocketAddress(443, fields[3]));
    }

    private static String ipv4(String value) {
        if (value == null || value.contains(":")
                || !InetAddress.ofLiteral(value).getHostAddress().equals(value)) {
            throw invalid();
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Public bridge source address is not admitted");
    }
}
