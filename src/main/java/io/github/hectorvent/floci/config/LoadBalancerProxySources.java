package io.github.hectorvent.floci.config;

import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Destination-specific listeners admitted by the owning load balancer's protected namespace. */
@ApplicationScoped
public class LoadBalancerProxySources {
    private final Map<String, Admission> admissions = new HashMap<>();

    public record Destination(String address, int port) {
        public Destination {
            address = ipv4(address);
            checkedPort(port);
        }
    }

    public record Backend(int port, boolean tls, BooleanSupplier current) {
        public Backend {
            checkedPort(port);
            Objects.requireNonNull(current);
        }
    }

    public record Route(PublicApiProxySources.Addresses addresses, Backend backend) {}

    private record Admission(Object owner, Map<Destination, Backend> listeners) {}

    public static final class Ticket {
        private final String peer;
        private final Admission admission;

        private Ticket(String peer, Admission admission) {
            this.peer = peer;
            this.admission = admission;
        }
    }

    /** The caller first verifies the helper's native Docker, ENI and route ownership. */
    public synchronized void register(Object owner, String peer, Map<Destination, Backend> listeners) {
        Objects.requireNonNull(owner, "A load balancer proxy requires its lifecycle owner");
        String address = ipv4(peer);
        Admission existing = admissions.get(address);
        if (existing != null && existing.owner() != owner) {
            throw new IllegalStateException("Another load balancer owns this proxy source address");
        }
        admissions.put(address, new Admission(owner, Map.copyOf(listeners)));
    }

    public synchronized void revoke(Object owner) {
        admissions.values().removeIf(value -> value.owner() == owner);
    }

    public synchronized Ticket ticket(String peer) {
        Admission admission = admissions.get(peer);
        return admission == null ? null : new Ticket(peer, admission);
    }

    public synchronized boolean current(Ticket ticket) {
        return ticket != null && admissions.get(ticket.peer) == ticket.admission;
    }

    public synchronized Route decode(Ticket ticket, String preface) {
        Admission admission = ticket == null ? null : admissions.get(ticket.peer);
        if (admission == null || admission != ticket.admission
                || preface == null || preface.length() > 108 || !preface.endsWith("\r\n")) {
            throw invalid();
        }
        String[] fields = preface.substring(0, preface.length() - 2).split(" ", -1);
        if (fields.length != 6 || !"PROXY".equals(fields[0]) || !"TCP4".equals(fields[1])) {
            throw invalid();
        }
        String source = ipv4(fields[2]);
        Destination destination = new Destination(fields[3], port(fields[5]));
        Backend backend = admission.listeners().get(destination);
        if (backend == null || !backend.current().getAsBoolean()) {
            throw invalid();
        }
        return new Route(new PublicApiProxySources.Addresses(
                SocketAddress.inetSocketAddress(port(fields[4]), source),
                SocketAddress.inetSocketAddress(destination.port(), destination.address())), backend);
    }

    private static int port(String value) {
        if (!value.matches("[1-9][0-9]{0,4}")) {
            throw invalid();
        }
        int port = Integer.parseInt(value);
        checkedPort(port);
        return port;
    }

    private static void checkedPort(int port) {
        if (port < 1 || port > 65535) {
            throw invalid();
        }
    }

    private static String ipv4(String value) {
        if (value == null || value.contains(":")
                || !InetAddress.ofLiteral(value).getHostAddress().equals(value)) {
            throw invalid();
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Load balancer proxy source or destination is not admitted");
    }
}
