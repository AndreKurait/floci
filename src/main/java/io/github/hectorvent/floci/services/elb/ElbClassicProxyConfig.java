package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.core.common.CertificateReferenceResolver.CertificateMaterial;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Maps a Classic listener snapshot onto a stock proxy inside its protected network namespace. */
final class ElbClassicProxyConfig {
    static final String DIRECTORY = "/usr/local/etc/haproxy";

    record Backend(String instanceId, String address) {}

    record Rendered(Map<String, byte[]> files, Map<String, List<String>> servers) {}

    private ElbClassicProxyConfig() {}

    static Rendered render(ClassicLoadBalancer loadBalancer, String address, List<Backend> backends,
                           Function<String, CertificateMaterial> certificates) {
        String bindAddress = ipv4(address);
        int idleTimeout = loadBalancer.getAttributes().getIdleTimeout();
        if (idleTimeout < 1 || idleTimeout > 4000) {
            throw new IllegalArgumentException("Classic idle timeout must be between 1 and 4000 seconds");
        }
        StringBuilder text = new StringBuilder("""
                global
                    master-worker
                    stats socket abnsz@floci-classic level admin
                defaults
                    timeout connect 10s
                """).append("    timeout client ").append(idleTimeout).append("s\n")
                .append("    timeout server ").append(idleTimeout).append("s\n");
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, List<String>> servers = new LinkedHashMap<>();
        for (ClassicListener listener : loadBalancer.getListeners()) {
            int port = listener.getLoadBalancerPort();
            int backendPort = listener.getInstancePort();
            if (port < 1 || port > 65535 || backendPort < 1 || backendPort > 65535) {
                throw new IllegalArgumentException("Classic listener ports must be between 1 and 65535");
            }
            String protocol = listener.getProtocol().toUpperCase(Locale.ROOT);
            String backendProtocol = listener.getInstanceProtocol().toUpperCase(Locale.ROOT);
            boolean http = protocol.equals("HTTP") || protocol.equals("HTTPS");
            boolean tls = protocol.equals("HTTPS") || protocol.equals("SSL");
            if (!List.of("HTTP", "HTTPS", "TCP", "SSL").contains(protocol)
                    || !List.of("HTTP", "HTTPS", "TCP", "SSL").contains(backendProtocol)
                    || http != List.of("HTTP", "HTTPS").contains(backendProtocol)) {
                throw new IllegalArgumentException("Incompatible Classic frontend and backend protocols");
            }
            String section = "listener_" + port;
            text.append("listen ").append(section).append("\n")
                    .append("    bind ").append(bindAddress).append(":").append(port);
            if (tls) {
                CertificateMaterial certificate = certificates.apply(listener.getSslCertificateId());
                if (certificate == null || certificate.certificateBody() == null || certificate.privateKey() == null) {
                    throw new IllegalArgumentException("Classic TLS listener needs its certificate and private key");
                }
                String file = "listener-" + port + ".pem";
                String pem = certificate.certificateBody().strip() + "\n"
                        + (certificate.certificateChain() == null ? "" : certificate.certificateChain().strip() + "\n")
                        + certificate.privateKey().strip() + "\n";
                files.put(file, pem.getBytes(StandardCharsets.UTF_8));
                text.append(" ssl crt ").append(DIRECTORY).append("/").append(file).append(" no-alpn");
            }
            if (http) {
                // Classic terminates HTTP/1.x; HAProxy otherwise advertises HTTP/2 on TLS by default.
                text.append(" proto h1");
            }
            text.append("\n    mode ").append(http ? "http" : "tcp")
                    .append("\n    balance roundrobin\n");
            if (http) {
                text.append("    option forwardfor\n")
                        .append("    http-request set-header X-Forwarded-Proto ").append(tls ? "https" : "http")
                        .append("\n    http-request set-header X-Forwarded-Port ").append(port).append("\n");
            }
            for (int index = 0; index < backends.size(); index++) {
                Backend backend = backends.get(index);
                String server = "target_" + index;
                text.append("    server ").append(server).append(" ").append(ipv4(backend.address()))
                        .append(":").append(backendPort);
                if (backendProtocol.equals("HTTPS") || backendProtocol.equals("SSL")) {
                    text.append(" ssl verify none");
                }
                // The existing Classic checker admits servers after its configured consecutive successes.
                text.append(" disabled\n");
                servers.computeIfAbsent(backend.instanceId(), ignored -> new ArrayList<>())
                        .add(section + "/" + server);
            }
        }
        files.put("haproxy.cfg", text.toString().getBytes(StandardCharsets.UTF_8));
        return new Rendered(Map.copyOf(files), servers.entrySet().stream().collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue()))));
    }

    static String ipv4(String address) {
        InetAddress parsed = InetAddress.ofLiteral(address);
        if (!(parsed instanceof Inet4Address)) {
            throw new IllegalArgumentException("Protected Classic frontends currently require IPv4");
        }
        return parsed.getHostAddress();
    }
}
