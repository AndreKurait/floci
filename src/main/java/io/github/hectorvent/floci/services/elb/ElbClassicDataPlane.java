package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.LocalStackParity;
import io.github.hectorvent.floci.config.TlsConfigSource;
import io.github.hectorvent.floci.core.common.DomainNames;
import io.github.hectorvent.floci.core.common.RequestHost;
import io.github.hectorvent.floci.core.common.SsrfProtection;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Streams requests for existing Classic load balancers through Floci's shared HTTP edge. */
@ApplicationScoped
public class ElbClassicDataPlane {

    private static final Set<String> HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "trailers", "transfer-encoding", "upgrade");

    private final ElbClassicService service;
    private final ElbClassicHealthChecker health;
    private final Route53Service route53;
    private final Ec2Service ec2;
    private final EmulatorConfig config;
    private final Vertx vertx;
    private final HttpClient client;
    private final AtomicInteger nextTarget = new AtomicInteger();

    @Inject
    public ElbClassicDataPlane(ElbClassicService service, ElbClassicHealthChecker health,
                              Route53Service route53, Ec2Service ec2, EmulatorConfig config, Vertx vertx) {
        this.service = service;
        this.health = health;
        this.route53 = route53;
        this.ec2 = ec2;
        this.config = config;
        this.vertx = vertx;
        this.client = vertx.createHttpClient(new HttpClientOptions().setConnectTimeout(5000).setKeepAlive(true));
    }

    void register(@Observes Router router) {
        router.route().order(-100).handler(this::handle);
    }

    @PreDestroy
    void close() {
        client.close();
    }

    private void handle(RoutingContext context) {
        if (!config.services().elb().enabled() || config.services().elb().mock()) {
            context.next();
            return;
        }
        String originalAuthority = RequestHost.of(context.request());
        URI authority;
        try {
            authority = URI.create("http://" + originalAuthority);
        } catch (IllegalArgumentException ignored) {
            // The ordinary HTTP/API validation owns a malformed authority.
            context.next();
            return;
        }
        if (authority.getHost() == null || authority.getUserInfo() != null
                || !authority.getRawPath().isEmpty() || authority.getRawQuery() != null
                || authority.getRawFragment() != null) {
            context.next();
            return;
        }
        String name = authority.getHost().toLowerCase(Locale.ROOT);
        name = name.endsWith(".") ? name : name + ".";
        List<ClassicLoadBalancer> balancers = service.findByDnsName(name);
        if (balancers.isEmpty()) {
            if (isEmulatorHost(name, config)) {
                context.next();
                return;
            }
            Route53Service.PublicAlias alias = route53.lookupPublicAlias(name);
            if (alias == null) {
                context.next();
                return;
            }
            balancers = service.findByDnsName(alias.dnsName());
            if (balancers.isEmpty()) {
                context.next();
                return;
            }
            balancers = balancers.stream().filter(lb -> alias.hostedZoneId() == null
                    || alias.hostedZoneId().equals(lb.getCanonicalHostedZoneNameId())).toList();
        }
        if (balancers.size() != 1) {
            fail(context, 404);
            return;
        }
        ClassicLoadBalancer lb = balancers.getFirst();
        int port = authority.getPort() > 0 ? authority.getPort() : context.request().isSSL() ? 443 : 80;
        ClassicListener listener = lb.getListeners().stream()
                .filter(candidate -> Objects.equals(candidate.getLoadBalancerPort(), port))
                .findFirst().orElse(null);
        if (listener == null || !"HTTP".equalsIgnoreCase(listener.getProtocol())
                && !"HTTPS".equalsIgnoreCase(listener.getProtocol())
                || listener.getInstanceProtocol() != null && !"HTTP".equalsIgnoreCase(listener.getInstanceProtocol())) {
            fail(context, 502);
            return;
        }
        List<String> targets = healthyTargets(lb);
        if (targets.isEmpty()) {
            fail(context, 503);
            return;
        }
        String target = targets.get(Math.floorMod(nextTarget.getAndIncrement(), targets.size()));
        context.request().pause();
        // External compute fabrics publish the modeled instance ID in DNS instead of setting a
        // Docker bridge IP. Resolve off the event loop, then pin the checked address for this request.
        vertx.executeBlocking(() -> targetAddress(target), false).onSuccess(address -> {
            RequestOptions options = new RequestOptions().setHost(address).setPort(listener.getInstancePort())
                    .setURI(context.request().uri()).setMethod(context.request().method())
                    .setTimeout(lb.getAttributes().getIdleTimeout() * 1000L);
            client.request(options).onSuccess(upstream -> forward(context, upstream, originalAuthority))
                    .onFailure(error -> fail(context, 502));
        })
                .onFailure(error -> fail(context, 502));
    }

    static boolean isEmulatorHost(String name, EmulatorConfig config) {
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
        return domains.stream().map(ElbClassicDataPlane::normalizedHost)
                .anyMatch(domain -> DomainNames.isWithin(host, domain));
    }

    private static String normalizedHost(String name) {
        String host = name.toLowerCase(Locale.ROOT);
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    private List<String> healthyTargets(ClassicLoadBalancer lb) {
        List<String> targets = new ArrayList<>();
        for (String id : List.copyOf(lb.getInstanceIds())) {
            Instance instance = ec2.findInstanceForAccount(lb.getAccountId(), lb.getRegion(), id).orElse(null);
            if (instance == null || instance.getState() == null || !"running".equals(instance.getState().getName())
                    || !Objects.equals(lb.getRegion(), instance.getRegion())
                    || !Objects.equals(lb.getVpcId(), instance.getVpcId())
                    || !"InService".equals(health.getHealth(lb, id).state())) {
                continue;
            }
            targets.add(ElbClassicHealthChecker.instanceHost(instance, id));
        }
        return targets;
    }

    static String targetAddress(String host) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        SsrfProtection.rejectMetadataAddresses(addresses, host);
        return addresses[0].getHostAddress();
    }

    private void forward(RoutingContext context, HttpClientRequest upstream, String originalAuthority) {
        copyHeaders(context.request().headers(), upstream.headers());
        upstream.putHeader("Host", originalAuthority);
        if (context.request().getHeader("Content-Length") == null) {
            upstream.setChunked(true);
        }
        upstream.response().onSuccess(response -> respond(context, response))
                .onFailure(error -> fail(context, 502));
        upstream.send(context.request()).onFailure(error -> fail(context, 502));
        context.request().resume();
    }

    private static void respond(RoutingContext context, HttpClientResponse response) {
        context.response().setStatusCode(response.statusCode());
        copyHeaders(response.headers(), context.response().headers());
        if (response.getHeader("Content-Length") == null) {
            context.response().setChunked(true);
        }
        response.pipeTo(context.response()).onFailure(error -> fail(context, 502));
    }

    private static void copyHeaders(MultiMap source, MultiMap target) {
        Set<String> excluded = new HashSet<>(HOP_HEADERS);
        for (String connection : source.getAll("Connection")) {
            for (String header : connection.split(",")) {
                excluded.add(header.strip().toLowerCase(Locale.ROOT));
            }
        }
        source.forEach(header -> {
            if (!excluded.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                target.add(header.getKey(), header.getValue());
            }
        });
    }

    private static void fail(RoutingContext context, int status) {
        context.request().resume();
        if (context.response().ended()) {
            return;
        }
        if (context.response().headWritten()) {
            context.response().close();
        } else {
            context.response().setStatusCode(status).end();
        }
    }
}
