package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.LocalStackParity;
import io.github.hectorvent.floci.config.TlsConfigSource;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The host a client addressed. HTTP/2 (RFC 9113 section 8.3.1) sends {@code :authority} and no
 * {@code Host} header, and curl and browsers use HTTP/2 over HTTPS.
 */
public final class RequestHost {

    private RequestHost() {
    }

    public static String of(ContainerRequestContext requestContext) {
        String hostHeader = requestContext.getHeaderString("Host");
        if (hostHeader != null && !hostHeader.isBlank()) {
            return hostHeader.trim();
        }
        return of(hostHeader, requestContext.getUriInfo().getRequestUri());
    }

    /**
     * The {@code Host} header when present, else the request URI authority, which the container
     * builds from {@code :authority} on HTTP/2.
     */
    public static String of(String hostHeader, URI requestUri) {
        if (hostHeader != null && !hostHeader.isBlank()) {
            return hostHeader.trim();
        }
        return requestUri != null ? requestUri.getAuthority() : null;
    }

    public static String of(HttpServerRequest request) {
        String hostHeader = request.getHeader("Host");
        if (hostHeader != null && !hostHeader.isBlank()) {
            return hostHeader.trim();
        }
        HostAndPort authority = request.authority();
        return authority != null ? authority.toString() : null;
    }

    /** API endpoint names remain with the API when a service resolves public DNS aliases. */
    public static boolean isEmulatorHost(String name, EmulatorConfig config) {
        String host = normalizedHost(name);
        if (host.indexOf(':') >= 0 || host.chars().allMatch(c -> c == '.' || c >= '0' && c <= '9')) {
            try {
                InetAddress.ofLiteral(host);
                return true;
            } catch (IllegalArgumentException ignored) {
                // A malformed IP spelling remains subject to the ordinary HTTP route validation.
            }
        }
        List<String> domains = new ArrayList<>(List.of("localhost",
                EmbeddedDnsServer.DEFAULT_SUFFIX, EmbeddedDnsServer.LOCALSTACK_SUFFIX));
        config.hostname().ifPresent(domains::add);
        String baseHost = URI.create(config.baseUrl()).getHost();
        if (baseHost != null) {
            domains.add(baseHost);
        }
        LocalStackParity.withDnsSuffixes(config.dns().extraSuffixes()).ifPresent(domains::addAll);
        if (config.dns().spoofAwsEndpoints()) {
            domains.addAll(TlsConfigSource.awsEndpointSuffixes());
        }
        return domains.stream().map(RequestHost::normalizedHost)
                .anyMatch(domain -> DomainNames.isWithin(host, domain));
    }

    private static String normalizedHost(String name) {
        String host = name.toLowerCase(Locale.ROOT);
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }
}
