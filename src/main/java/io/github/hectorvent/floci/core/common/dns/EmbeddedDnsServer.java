package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.LocalStackParity;
import io.github.hectorvent.floci.config.TlsConfigSource;
import io.github.hectorvent.floci.core.common.DomainNames;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.quarkus.runtime.Startup;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.net.NetServer;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded UDP/TCP DNS server on port 53 that is injected
 * into every spawned container (Lambda, RDS, ElastiCache) as their DNS resolver.
 *
 * Resolves *.{floci.hostname} (and any configured extra-suffixes) to Floci's own
 * Docker network IP so virtual-hosted S3 URLs (my-bucket.floci:4566) work from
 * inside Lambda containers without requiring wildcard Docker aliases.
 *
 * When floci.dns.spoof-aws-endpoints is enabled, every AWS partition suffix and all of its
 * subdomains resolve to Floci as well. With public AWS API routing enabled, known VPC
 * clients receive the managed public destination, so their native routes still govern access.
 *
 * Also answers for any name a {@link DnsRecordSource} owns, which is how a service that holds
 * a private DNS zone (Cloud Map) gets real records rather than only API responses.
 *
 * A {@link DnsForwardingRuleSource} can steer a query away from all of that before it is
 * resolved, which is how a Route 53 Resolver forwarding rule associated with the querying
 * container's VPC sends matching names to the resolvers the rule names instead.
 *
 * All other queries are forwarded to the upstream resolvers read from /etc/resolv.conf
 * (Docker's embedded DNS at 127.0.0.11), falling back to the configured public resolvers
 * (floci.dns.container-fallback-servers) so public hostnames still resolve when the
 * resolv.conf resolver does not answer.
 *
 * Starts automatically inside a container. A host process opts in by configuring
 * {@code floci.dns.listen-address}.
 */
@ApplicationScoped
@Startup
public class EmbeddedDnsServer {

    private static final Logger LOG = Logger.getLogger(EmbeddedDnsServer.class);
    static final int DNS_PORT = 53;
    private static final String FALLBACK_UPSTREAM = "127.0.0.11";
    // EDNS0-capable resolvers (Node/c-ares, glibc) advertise UDP payloads well above the
    // legacy 512-byte limit; CDN-backed public names return larger responses. Receiving into
    // a 512-byte buffer silently truncates the datagram and corrupts the forwarded answer.
    private static final int MAX_DNS_UDP_RESPONSE = 4096;
    // Per-upstream timeout. Bounded so trying every upstream stays under a typical 5s client
    // resolver timeout even in the worst case.
    private static final int FORWARD_TIMEOUT_MS = 1500;
    // Forwards block a thread for up to FORWARD_TIMEOUT_MS per target, so they get their own
    // bounded pool: a burst of queries to a dead resolver must not take every thread of the
    // shared worker pool that planQuery and the rest of the emulator also run on.
    private static final String FORWARD_POOL_NAME = "floci-dns-forward";
    private static final int FORWARD_POOL_SIZE = 8;
    public static final String DEFAULT_SUFFIX = "localhost.floci.io";
    public static final String LOCALSTACK_SUFFIX = "localhost.localstack.cloud";
    private static final Pattern EC2_PRIVATE_DNS_NAME =
            Pattern.compile("^ip-(\\d{1,3})-(\\d{1,3})-(\\d{1,3})-(\\d{1,3})\\.(?:ec2|((?:[a-z0-9]+-)+\\d+)\\.compute)\\.internal$",
                    Pattern.CASE_INSENSITIVE);

    // Well-known emulator wildcard DNS domains that always resolve to Floci's IP.
    // The suffix "localhost.X" covers "localhost.X" itself and "*.localhost.X"; it does
    // NOT cover "*.X" (e.g. "localhost.floci.io" does NOT resolve bare "*.floci.io").
    //   localhost.localstack.cloud → localhost.localstack.cloud, *.localhost.localstack.cloud
    //   localhost.floci.io         → localhost.floci.io, *.localhost.floci.io
    public static final List<String> BUILTIN_SUFFIXES = List.of(DEFAULT_SUFFIX, LOCALSTACK_SUFFIX);

    private volatile String serverIp;
    private Listeners listeners;
    private final SequencedSet<String> suffixes = new LinkedHashSet<>();
    private volatile List<String> upstreamDnsServers = List.of();
    private volatile WorkerExecutor forwardPool;
    // Held as the Iterable a CDI Instance already is, so iterating resolves the beans lazily on
    // the packet path rather than at startup, where a source's storage must not be touched yet.
    private final Iterable<DnsRecordSource> recordSources;
    private final Iterable<DnsForwardingRuleSource> forwardingRuleSources;
    private final Iterable<DnsClientVpcSource> clientVpcSources;
    private boolean publicAwsApi;
    private List<String> publicApiHosts = List.of();

    EmbeddedDnsServer(List<String> suffixes) {
        this(suffixes, List.of());
    }

    /**
     * The certificate generator cannot read application.yml, so DNS spoofing without AWS SANs on
     * the certificate only happens when the flag and TLS are on but the flag is not visible to it.
     */
    static boolean shouldWarnSpoofInvisibleToTls(boolean spoofConfigured, boolean tlsEnabled,
                                                 boolean spoofVisibleToTls) {
        return spoofConfigured && tlsEnabled && !spoofVisibleToTls;
    }

    EmbeddedDnsServer(List<String> suffixes, boolean spoofAwsEndpoints) {
        this(suffixes, List.of());
        if (spoofAwsEndpoints) {
            this.suffixes.addAll(TlsConfigSource.awsEndpointSuffixes());
        }
    }

    EmbeddedDnsServer(List<String> suffixes, Iterable<DnsRecordSource> recordSources) {
        this(suffixes, recordSources, List.of(), List.of());
    }

    EmbeddedDnsServer(List<String> suffixes, Iterable<DnsRecordSource> recordSources,
                      Iterable<DnsForwardingRuleSource> forwardingRuleSources,
                      Iterable<DnsClientVpcSource> clientVpcSources) {
        this.suffixes.addAll(BUILTIN_SUFFIXES);
        this.suffixes.addAll(suffixes);
        this.recordSources = recordSources;
        this.forwardingRuleSources = forwardingRuleSources;
        this.clientVpcSources = clientVpcSources;
    }

    EmbeddedDnsServer(List<String> suffixes, Iterable<DnsRecordSource> recordSources,
                      Iterable<DnsForwardingRuleSource> forwardingRuleSources,
                      Iterable<DnsClientVpcSource> clientVpcSources, boolean publicAwsApi) {
        this(suffixes, recordSources, forwardingRuleSources, clientVpcSources, publicAwsApi, List.of());
    }

    EmbeddedDnsServer(List<String> suffixes, Iterable<DnsRecordSource> recordSources,
                      Iterable<DnsForwardingRuleSource> forwardingRuleSources,
                      Iterable<DnsClientVpcSource> clientVpcSources, boolean publicAwsApi, List<String> publicApiHosts) {
        this(suffixes, recordSources, forwardingRuleSources, clientVpcSources);
        this.publicAwsApi = publicAwsApi;
        this.publicApiHosts = publicApiHosts.stream().map(EmbeddedDnsServer::publicEndpointHost).distinct().toList();
        if (publicAwsApi) {
            this.suffixes.addAll(TlsConfigSource.awsEndpointSuffixes());
        }
    }

    @Inject
    public EmbeddedDnsServer(EmulatorConfig config, ContainerDetector containerDetector, Vertx vertx,
                             Instance<DnsRecordSource> recordSources,
                             Instance<DnsForwardingRuleSource> forwardingRuleSources,
                             Instance<DnsClientVpcSource> clientVpcSources,
                             CurrentContainerNetworkResolver networkResolver) {
        this(config, containerDetector, vertx, recordSources, forwardingRuleSources, clientVpcSources,
                networkResolver, DNS_PORT);
    }

    EmbeddedDnsServer(EmulatorConfig config, ContainerDetector containerDetector, Vertx vertx,
                      Iterable<DnsRecordSource> recordSources,
                      Iterable<DnsForwardingRuleSource> forwardingRuleSources,
                      Iterable<DnsClientVpcSource> clientVpcSources,
                      CurrentContainerNetworkResolver networkResolver, int port) {
        this.recordSources = recordSources;
        this.forwardingRuleSources = forwardingRuleSources;
        this.clientVpcSources = clientVpcSources;
        boolean explicit = config.dns().listenAddress().isPresent();
        if (!explicit && !containerDetector.isRunningInContainer()) {
            return;
        }
        try {
            String myIp = resolveServerIp(config, networkResolver);
            upstreamDnsServers = composeUpstreams(readResolvConfNameservers(),
                    config.dns().containerFallbackServers());

            suffixes.addAll(BUILTIN_SUFFIXES);
            config.hostname().ifPresent(suffixes::add);
            LocalStackParity.withDnsSuffixes(config.dns().extraSuffixes()).ifPresent(suffixes::addAll);
            if (config.dns().spoofAwsEndpoints()) {
                suffixes.addAll(TlsConfigSource.awsEndpointSuffixes());
                publicAwsApi = config.network().vpcRouting().enabled()
                        && config.network().vpcRouting().publicAwsApi();
                publicApiHosts = config.network().vpcRouting().publicAwsApiHosts().orElse(List.of()).stream()
                        .map(EmbeddedDnsServer::publicEndpointHost).distinct().toList();
                if (shouldWarnSpoofInvisibleToTls(true, TlsConfigSource.tlsEnabledVisibleToTls(),
                        TlsConfigSource.spoofAwsEndpointsVisibleToTls())) {
                    LOG.warnv("floci.dns.spoof-aws-endpoints is set in application config but the TLS certificate only reads it from FLOCI_DNS_SPOOF_AWS_ENDPOINTS or -Dfloci.dns.spoof-aws-endpoints; https:// calls to {0} will fail the handshake until it is set there.", TlsConfigSource.awsEndpointSuffixes());
                }
            }

            listeners = startListeners(vertx, myIp, port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the embedded DNS listeners", e);
        } catch (Exception e) {
            if (explicit) {
                throw new IllegalStateException("Cannot start DNS at floci.dns.listen-address="
                        + config.dns().listenAddress().orElseThrow(), e);
            }
            LOG.warnv("Failed to initialize embedded DNS server: {0}", e.getMessage());
        }
    }

    int listenerPort() {
        return listeners == null ? -1 : listeners.udp().localAddress().port();
    }

    private Listeners startListeners(Vertx vertx, String address, int port) throws Exception {
        Future<Listeners> opening = listenAll(vertx, address, port);
        try {
            return opening.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            opening.onSuccess(bound -> {
                serverIp = null;
                bound.close();
            });
            throw failure;
        }
    }

    @PreDestroy
    void stop() {
        serverIp = null;
        if (listeners != null) {
            listeners.close().toCompletionStage().toCompletableFuture().join();
            listeners = null;
        }
        if (forwardPool != null) {
            forwardPool.close();
        }
    }

    public Optional<String> getServerIp() {
        return Optional.ofNullable(serverIp);
    }

    Future<DatagramSocket> listen(Vertx vertx, String address, int port) {
        DatagramSocket socket = vertx.createDatagramSocket(new DatagramSocketOptions().setIpV6(false));
        // Binding the advertised address keeps replies on the address the client queried,
        // including when the route back to that client uses a different local interface.
        return socket.listen(port, address).onSuccess(bound -> {
            bound.handler(packet -> handleQuery(vertx, bound, packet.data().getBytes(),
                    packet.sender().host(), packet.sender().port(), address));
            serverIp = address;
            LOG.infov("Embedded DNS server started on {0}:{1}, resolving {2} to {0}",
                    address, bound.localAddress().port(), suffixes);
        }).onFailure(error -> socket.close());
    }

    record Listeners(DatagramSocket udp, NetServer tcp) {
        Future<Void> close() {
            return Future.all(udp.close(), tcp.close()).mapEmpty();
        }
    }

    Future<Listeners> listenAll(Vertx vertx, String address, int port) {
        return DnsTcpListener.listen(vertx, address, port,
                        (query, sender) -> answerQuery(vertx, query, sender, address, true))
                .compose(tcp -> listen(vertx, address, tcp.actualPort())
                        .map(udp -> new Listeners(udp, tcp))
                        .recover(error -> tcp.close().compose(ignored -> Future.failedFuture(error))));
    }

    static String resolveServerIp(EmulatorConfig config, CurrentContainerNetworkResolver networkResolver)
            throws UnknownHostException {
        Optional<String> configured = config.dns().listenAddress();
        if (configured.isPresent()) {
            InetAddress address = InetAddress.ofLiteral(configured.get());
            if (!(address instanceof Inet4Address) || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("floci.dns.listen-address must be a concrete local IPv4 address");
            }
            return address.getHostAddress();
        }
        Optional<String> address = networkResolver.resolveContainerIp();
        if (address.isPresent()) {
            return address.get();
        }
        if (config.services().dockerNetwork().isPresent()) {
            throw new IllegalStateException("Cannot resolve the configured services Docker network for DNS");
        }
        return InetAddress.getLocalHost().getHostAddress();
    }

    // ── packet handling ───────────────────────────────────────────────────────

    void handleQuery(Vertx vertx, DatagramSocket socket, byte[] data,
                             String senderHost, int senderPort, String myIp) {
        answerQuery(vertx, data, senderHost, myIp, false).onSuccess(response -> {
            if (response != null) {
                socket.send(Buffer.buffer(response), senderPort, senderHost);
            }
        }).onFailure(error -> LOG.warnv("DNS query failed: {0}", error.getMessage()));
    }

    private Future<byte[]> answerQuery(Vertx vertx, byte[] data, String senderHost, String myIp, boolean tcp) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(data);
            short txId = buf.getShort();
            short flags = buf.getShort();
            short qdCount = buf.getShort();
            buf.getShort(); // ancount
            buf.getShort(); // nscount
            buf.getShort(); // arcount

            if ((flags & 0x8000) != 0 || qdCount < 1) {
                return Future.succeededFuture(); // not a standard query
            }

            int questionOffset = buf.position(); // always 12 for a standard query
            String qname = readName(buf, data);
            short qtype = buf.getShort();
            short qclass = buf.getShort();
            int questionEnd = buf.position();

            return vertx.<QueryPlan>executeBlocking(() -> planQuery(qname, senderHost, myIp, qtype), false)
                    .compose(plan -> {
                        if (plan.answer().isEmpty()) {
                            if (plan.ruleTargets().isEmpty()) {
                                return forwardAsync(vertx, data, tcp);
                            }
                            return forwardToRuleTargets(vertx, data, plan.ruleTargets(), tcp,
                                    qname, txId, questionOffset, questionEnd);
                        }
                        DnsAnswer records = plan.answer().orElseThrow();
                        if (records.isPublicAuthorityAnswer()
                                && (qdCount != 1 || qclass != 1 || (flags & 0x7840) != 0)) {
                            records = DnsAnswer.refused();
                        }
                        byte[] response = records.isPublicAuthorityAnswer()
                                ? buildPublicResponse(data, txId, questionOffset, questionEnd, records, tcp)
                                : !records.isEmpty()
                                ? buildAResponse(data, txId, questionOffset, questionEnd, records, tcp)
                                : buildEmptyResponse(data, txId, questionOffset, questionEnd,
                                        records.nameExists() ? 0 : 3);
                        return Future.succeededFuture(response);
                    });
        } catch (Exception e) {
            LOG.debugv("DNS packet error: {0}", e.getMessage());
            return Future.succeededFuture();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    boolean matchesSuffix(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String suffix : suffixes) {
            String s = suffix.toLowerCase();
            if (DomainNames.isWithin(lower, s)) {
                return true;
            }
        }
        return false;
    }

    List<String> resolveARecord(String name, String myIp) {
        return resolveARecordWithOwnership(name, myIp)
                .map(DnsAnswer::addresses).orElse(List.of());
    }

    Optional<DnsAnswer> resolveARecordWithOwnership(String name, String myIp) {
        return resolveRecordWithOwnership(name, myIp, 1);
    }

    /** Resolves only resource-backed alias targets, without recursive or upstream DNS lookups. */
    public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type) {
        DnsAnswer resolved = null;
        if (recordSources != null) {
            for (DnsRecordSource source : recordSources) {
                try {
                    Optional<DnsAnswer> candidate = source.resolveAliasTarget(name, hostedZoneId, type);
                    if (candidate.isPresent()) {
                        if (resolved != null) {
                            return Optional.of(DnsAnswer.refused());
                        }
                        resolved = candidate.orElseThrow();
                    }
                } catch (RuntimeException failure) {
                    LOG.debugv("DNS alias source {0} failed to resolve {1}: {2}",
                            source.getClass().getSimpleName(), name, failure.getMessage());
                    return Optional.of(DnsAnswer.refused());
                }
            }
        }
        return Optional.ofNullable(resolved);
    }

    private Optional<DnsAnswer> resolveRecordWithOwnership(String name, String myIp, int type) {
        Optional<DnsAnswer> stored = resolveFromRecordSources(name, type, true);
        if (stored.isPresent()) {
            return stored;
        }
        Optional<DnsAnswer> emulatorName = resolveEmulatorName(name, myIp, type);
        return emulatorName.isPresent() ? emulatorName : resolveFromRecordSources(name, type);
    }

    /**
     * What happens to one query, decided on a worker thread because every step behind it reads
     * storage: an answer Floci owns, a set of resolvers a rule forwards the query to, or neither,
     * which leaves it to the upstream resolvers.
     */
    record QueryPlan(Optional<DnsAnswer> answer, List<DnsForwardingRule.Target> ruleTargets) {

        static final QueryPlan UPSTREAM = new QueryPlan(Optional.empty(), List.of());

        static QueryPlan answering(DnsAnswer answer) {
            return new QueryPlan(Optional.of(answer), List.of());
        }

        static QueryPlan forwardingTo(List<DnsForwardingRule.Target> targets) {
            return new QueryPlan(Optional.empty(), targets);
        }
    }

    /**
     * Stored public authority answers take precedence over broad emulator suffix spoofing.
     * Other names the emulator itself owns come first, whatever the rules say. Route 53 Resolver does
     * the same for the AWS-internal domains it autodefines system rules for, so that a rule for a
     * broad domain (or for "." itself) cannot send the platform's own names off to a resolver that
     * knows nothing about them.
     *
     * <p>A forwarding rule associated with the querying VPC is then applied before Floci resolves
     * the name from a record source, because a Resolver rule takes precedence over a private
     * hosted zone that matches the same name.
     */
    QueryPlan planQuery(String name, String clientAddress, String myIp, int type) {
        DnsClientVpcSource.ClientVpc origin = vpcForClient(clientAddress).orElse(null);
        Optional<DnsAnswer> stored = resolveFromRecordSources(name, type, true, origin);
        boolean routedApi = publicAwsApi && origin != null && TlsConfigSource.awsEndpointSuffixes().stream()
                .anyMatch(suffix -> DomainNames.isWithin(name.toLowerCase(Locale.ROOT), suffix));
        Optional<DnsAnswer> emulatorName = resolveEmulatorName(name,
                routedApi ? EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS : myIp, type);
        if (emulatorName.isPresent() && stored.isEmpty()) {
            return QueryPlan.answering(emulatorName.orElseThrow());
        }
        // A rule steers the name whatever the query asks about it, so this is deliberately not
        // gated on the record type: the raw query is relayed to the rule's resolvers as it stands.
        Optional<DnsForwardingRule> rule = matchingRule(name, origin);
        if (rule.isPresent() && rule.orElseThrow().forwards()) {
            return QueryPlan.forwardingTo(rule.orElseThrow().targets());
        }
        return stored.or(() -> resolveFromRecordSources(name, type, false, origin))
                .or(() -> publicAwsApi && origin != null && publicApiHosts.contains(name.toLowerCase(Locale.ROOT))
                        ? Optional.of(type == 1 ? DnsAnswer.records(
                                List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS),
                                DnsAnswer.DEFAULT_TTL_SECONDS) : DnsAnswer.noData())
                        : Optional.empty())
                .map(QueryPlan::answering).orElse(QueryPlan.UPSTREAM);
    }

    private static String publicEndpointHost(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Public endpoint host must be an exact DNS name");
        }
        String host = value.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.length() > 253 || !host.contains(".")
                || !host.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")
                || host.matches("[0-9.]+")
                || Arrays.stream(host.split("\\.")).anyMatch(label ->
                        label.length() > 63 || label.startsWith("-") || label.endsWith("-"))) {
            throw new IllegalArgumentException("Public endpoint host must be an exact DNS name");
        }
        return host;
    }

    private Optional<DnsAnswer> resolveEmulatorName(String name, String myIp, int type) {
        if (matchesSuffix(name)) {
            return Optional.of(type == 1 ? DnsAnswer.records(List.of(myIp), DnsAnswer.DEFAULT_TTL_SECONDS)
                    : DnsAnswer.noData());
        }
        return resolveEc2PrivateDnsName(name)
                .map(address -> type == 1 ? DnsAnswer.records(List.of(address), DnsAnswer.DEFAULT_TTL_SECONDS)
                        : DnsAnswer.noData());
    }

    /**
     * The rule that governs this query: the most specific one whose domain matches the name among
     * those associated with the VPC the query came from. A source that throws is skipped rather
     * than allowed to fail the query, which is how {@link #resolveFromRecordSources} treats one too.
     */
    Optional<DnsForwardingRule> matchingRule(String name, String clientAddress) {
        return matchingRule(name, vpcForClient(clientAddress).orElse(null));
    }

    private Optional<DnsForwardingRule> matchingRule(String name, DnsClientVpcSource.ClientVpc origin) {
        if (forwardingRuleSources == null || name == null || name.isBlank() || origin == null) {
            return Optional.empty();
        }
        DnsForwardingRule best = null;
        for (DnsForwardingRuleSource source : forwardingRuleSources) {
            try {
                for (DnsForwardingRule rule :
                        source.rulesFor(origin.accountId(), origin.region(), origin.vpcId())) {
                    if (rule == null || !rule.matches(name)) {
                        continue;
                    }
                    if (best == null || rule.specificity() > best.specificity()) {
                        best = rule;
                    }
                }
            } catch (Exception e) {
                LOG.debugv("DNS forwarding rule source {0} failed for {1}: {2}",
                        source.getClass().getSimpleName(), name, e.getMessage());
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<DnsClientVpcSource.ClientVpc> vpcForClient(String clientAddress) {
        if (clientVpcSources == null || clientAddress == null || clientAddress.isBlank()) {
            return Optional.empty();
        }
        for (DnsClientVpcSource source : clientVpcSources) {
            try {
                Optional<DnsClientVpcSource.ClientVpc> clientVpc =
                        source.vpcForClient(clientAddress.trim());
                if (clientVpc != null && clientVpc.isPresent() && isUsableOrigin(clientVpc.orElseThrow())) {
                    return clientVpc;
                }
            } catch (Exception e) {
                LOG.debugv("DNS client VPC source {0} failed for {1}: {2}",
                        source.getClass().getSimpleName(), clientAddress, e.getMessage());
            }
        }
        return Optional.empty();
    }

    private static boolean isUsableOrigin(DnsClientVpcSource.ClientVpc clientVpc) {
        return isPresent(clientVpc.accountId()) && isPresent(clientVpc.region())
                && isPresent(clientVpc.vpcId());
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Answers from a service that owns a private DNS zone, Cloud Map being the one that does
     * today. A source that throws must not take the DNS server down with it: the query falls
     * through to the upstream resolvers, which is what happened before any source existed.
     */
    private Optional<DnsAnswer> resolveFromRecordSources(String name, int type) {
        return resolveFromRecordSources(name, type, false);
    }

    private Optional<DnsAnswer> resolveFromRecordSources(String name, int type, boolean authoritative) {
        return resolveFromRecordSources(name, type, authoritative, null);
    }

    private Optional<DnsAnswer> resolveFromRecordSources(String name, int type, boolean authoritative,
                                                         DnsClientVpcSource.ClientVpc origin) {
        if (recordSources == null) {
            return Optional.empty();
        }
        // Two sources can own the same zone (a Cloud Map namespace and the Route 53 zone behind it),
        // and bean order is unspecified. An empty answer (NXDOMAIN, or NODATA for a name holding only
        // other types) only speaks for that source, so it must not win over another source's records;
        // it is returned only if no source has any, an existing name preferred over NXDOMAIN.
        Optional<DnsAnswer> empty = Optional.empty();
        for (DnsRecordSource source : recordSources) {
            try {
                Optional<DnsAnswer> answer = authoritative ? source.resolveAuthoritative(name, type, origin)
                        : source.resolve(name, type, origin);
                if (answer != null && answer.isPresent()) {
                    if (answer.get().isEmpty()) {
                        if (empty.isEmpty() || (!empty.get().nameExists() && answer.get().nameExists())) {
                            empty = answer;
                        }
                        continue;
                    }
                    return answer;
                }
            } catch (Exception e) {
                LOG.debugv("DNS record source {0} failed to resolve {1}: {2}",
                        source.getClass().getSimpleName(), name, e.getMessage());
            }
        }
        return empty;
    }

    byte[] buildEmptyResponse(byte[] query, short txId, int questionOffset, int questionEnd, int responseCode) {
        return buildAnswerlessResponse(query, txId, questionOffset, questionEnd,
                (short) (0x8580 | responseCode));
    }

    /**
     * The failure answer for a query a forwarding rule claimed and no target answered. Not
     * authoritative, unlike {@link #buildEmptyResponse}: Floci is not the authority for a name a
     * rule sends elsewhere, it only failed to reach the resolver that is.
     */
    byte[] buildServerFailureResponse(byte[] query, short txId, int questionOffset, int questionEnd) {
        return buildAnswerlessResponse(query, txId, questionOffset, questionEnd, (short) 0x8182);
    }

    private static byte[] buildAnswerlessResponse(byte[] query, short txId, int questionOffset,
                                                  int questionEnd, short flags) {
        UdpPayload payload = udpPayload(query, questionEnd);
        ByteBuffer response = ByteBuffer.allocate(12 + questionEnd - questionOffset + (payload.edns() ? 11 : 0));
        response.putShort(txId);
        response.putShort(flags);
        response.putShort((short) 1);
        response.putShort((short) 0);
        response.putShort((short) 0);
        response.putShort((short) (payload.edns() ? 1 : 0));
        response.put(query, questionOffset, questionEnd - questionOffset);
        appendOpt(response, payload);
        return response.array();
    }

    Optional<String> resolveEc2PrivateDnsName(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = EC2_PRIVATE_DNS_NAME.matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        if (matcher.group(5) != null && !RegionResolver.isKnownRegion(matcher.group(5))) {
            return Optional.empty();
        }

        StringBuilder address = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            int octet = Integer.parseInt(matcher.group(i));
            if (octet > 255) {
                return Optional.empty();
            }
            if (i > 1) {
                address.append('.');
            }
            address.append(octet);
        }
        return Optional.of(address.toString());
    }

    static String readName(ByteBuffer buf, byte[] data) {
        return readName(buf, data, 0);
    }

    private static String readName(ByteBuffer buf, byte[] data, int depth) {
        if (depth > 16) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int safety = 0;
        while (buf.hasRemaining() && safety++ < 128) {
            int len = buf.get() & 0xFF;
            if (len == 0) {
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                // compression pointer
                if (!buf.hasRemaining()) {
                    break;
                }
                int offset = ((len & 0x3F) << 8) | (buf.get() & 0xFF);
                if (offset >= data.length) {
                    break;
                }
                ByteBuffer ptr = ByteBuffer.wrap(data);
                ptr.position(offset);
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(readName(ptr, data, depth + 1));
                return sb.toString();
            }
            if (buf.remaining() < len) {
                buf.position(buf.limit());
                break;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            byte[] label = new byte[len];
            buf.get(label);
            sb.append(new String(label, StandardCharsets.US_ASCII));
        }
        return sb.toString();
    }

    byte[] buildAResponse(byte[] query, short txId, int questionOffset, int questionEnd, DnsAnswer answer) {
        return buildAResponse(query, txId, questionOffset, questionEnd, answer, false);
    }

    private byte[] buildAResponse(byte[] query, short txId, int questionOffset, int questionEnd,
                                  DnsAnswer answer, boolean tcp) {
        List<DnsRecord> records = answer.records();
        int questionLength = questionEnd - questionOffset;
        List<byte[]> data = records.stream().map(DnsRecord::data).toList();
        UdpPayload payload = udpPayload(query, questionEnd);
        int limit = tcp ? DnsTcpListener.MAX_MESSAGE_SIZE : payload.size();
        int responseLength = 12 + questionLength + (payload.edns() ? 11 : 0);
        int answerCount = 0;
        for (byte[] recordData : data) {
            if (responseLength + 12 + recordData.length > limit) {
                break;
            }
            responseLength += 12 + recordData.length;
            answerCount++;
        }
        boolean truncated = answerCount < records.size();
        ByteBuffer resp = ByteBuffer.allocate(responseLength);

        // header
        resp.putShort(txId);
        int flags = answer.authority() == null ? 0x8180 : 0x8400 | (query[2] & 1) << 8;
        resp.putShort((short) (flags | (truncated ? 0x0200 : 0)));
        resp.putShort((short) 1);           // qdcount
        resp.putShort((short) answerCount); // ancount
        resp.putShort((short) 0);           // nscount
        resp.putShort((short) (payload.edns() ? 1 : 0)); // arcount

        // question (copied verbatim from query)
        resp.put(query, questionOffset, questionLength);

        // Each answer carries its own type and rdata; a CNAME can answer an address query.
        for (int i = 0; i < answerCount; i++) {
            resp.putShort((short) 0xC00C); // name pointer to offset 12 (start of question name)
            resp.putShort((short) records.get(i).type());
            resp.putShort((short) 1);       // class IN
            resp.putInt(answer.ttlSeconds());
            resp.putShort((short) data.get(i).length);
            resp.put(data.get(i));
        }
        appendOpt(resp, payload);
        return resp.array();
    }

    byte[] buildPublicResponse(byte[] query, short txId, int questionOffset, int questionEnd, DnsAnswer answer) {
        return buildPublicResponse(query, txId, questionOffset, questionEnd, answer, false);
    }

    private byte[] buildPublicResponse(byte[] query, short txId, int questionOffset, int questionEnd,
                                       DnsAnswer answer, boolean tcp) {
        if (!answer.isEmpty()) {
            return buildAResponse(query, txId, questionOffset, questionEnd, answer, tcp);
        }
        int flags = 0x8000 | (query[2] & 0x79) << 8 | answer.responseCode();
        DnsAnswer.Authority authority = answer.authority();
        if (authority == null) {
            return buildAnswerlessResponse(query, txId, questionOffset, questionEnd, (short) flags);
        }
        byte[] owner = DnsRecord.encodeName(authority.name());
        byte[] soa = authority.soa().data();
        UdpPayload payload = udpPayload(query, questionEnd);
        int questionLength = questionEnd - questionOffset;
        int length = 12 + questionLength + (payload.edns() ? 11 : 0);
        boolean fits = length + owner.length + 10 + soa.length
                <= (tcp ? DnsTcpListener.MAX_MESSAGE_SIZE : payload.size());
        ByteBuffer response = ByteBuffer.allocate(length + (fits ? owner.length + 10 + soa.length : 0));
        response.putShort(txId).putShort((short) (flags | 0x0400 | (fits ? 0 : 0x0200)));
        response.putShort((short) 1).putShort((short) 0).putShort((short) (fits ? 1 : 0))
                .putShort((short) (payload.edns() ? 1 : 0));
        response.put(query, questionOffset, questionLength);
        if (fits) {
            response.put(owner).putShort((short) 6).putShort((short) 1).putInt(authority.ttlSeconds())
                    .putShort((short) soa.length).put(soa);
        }
        appendOpt(response, payload);
        return response.array();
    }

    private record UdpPayload(int size, boolean edns) {}

    private static UdpPayload udpPayload(byte[] query, int questionEnd) {
        UdpPayload legacy = new UdpPayload(512, false);
        ByteBuffer packet = ByteBuffer.wrap(query);
        if (query.length < 12 || packet.getShort(4) != 1
                || packet.getShort(6) != 0 || packet.getShort(8) != 0) {
            return legacy;
        }
        int additionalCount = Short.toUnsignedInt(packet.getShort(10));
        packet.position(questionEnd);
        for (int i = 0; i < additionalCount; i++) {
            readName(packet, query);
            if (packet.remaining() < 10) {
                return legacy;
            }
            int type = Short.toUnsignedInt(packet.getShort());
            int size = Short.toUnsignedInt(packet.getShort());
            int ttl = packet.getInt();
            int dataLength = Short.toUnsignedInt(packet.getShort());
            if (packet.remaining() < dataLength) {
                return legacy;
            }
            packet.position(packet.position() + dataLength);
            if (type == 41 && ((ttl >>> 16) & 0xFF) == 0) {
                return new UdpPayload(Math.max(512, Math.min(size, MAX_DNS_UDP_RESPONSE)), true);
            }
        }
        return legacy;
    }

    private static void appendOpt(ByteBuffer response, UdpPayload payload) {
        if (payload.edns()) {
            response.put((byte) 0).putShort((short) 41).putShort((short) payload.size())
                    .putInt(0).putShort((short) 0);
        }
    }

    private Future<byte[]> forwardAsync(Vertx vertx, byte[] query, boolean tcp) {
        List<String> upstreams = upstreamDnsServers;
        if (upstreams.isEmpty()) {
            return Future.succeededFuture();
        }
        List<DnsForwardingRule.Target> targets = upstreams.stream()
                .map(upstream -> new DnsForwardingRule.Target(upstream, DNS_PORT)).toList();
        return submitForward(forwardPool(vertx),
                () -> tcp ? forwardToTargetsTcp(query, targets) : forwardToTargets(query, targets));
    }

    /**
     * Sends the query to the resolvers a forwarding rule names instead of to the upstreams. The
     * order is shuffled because Route 53 Resolver picks a rule's target at random and retries
     * another one when it does not answer.
     *
     * <p>A rule whose targets are all unreachable fails this query with SERVFAIL rather than
     * falling back to the upstream resolvers. The rule said where the name lives, so an upstream
     * answer for it would be the wrong answer, and resolution of every other name is untouched.
     */
    private Future<byte[]> forwardToRuleTargets(Vertx vertx, byte[] query,
                                      List<DnsForwardingRule.Target> targets, boolean tcp, String qname,
                                      short txId, int questionOffset, int questionEnd) {
        return submitForward(forwardPool(vertx), () -> tcp
                ? forwardToTargetsTcp(query, shuffled(targets)) : forwardToTargets(query, shuffled(targets)))
                .recover(e -> {
                    LOG.warnv("Resolver rule forwarding for {0} failed on every target {1}: {2}",
                            qname, targets, e.getMessage());
                    return Future.succeededFuture(
                            buildServerFailureResponse(query, txId, questionOffset, questionEnd));
                });
    }

    static byte[] forwardToTargetsTcp(byte[] query, List<DnsForwardingRule.Target> targets) throws Exception {
        Exception last = null;
        for (DnsForwardingRule.Target target : targets) {
            try (Socket socket = new Socket()) {
                long deadline = System.nanoTime() + FORWARD_TIMEOUT_MS * 1_000_000L;
                socket.connect(new InetSocketAddress(target.address(), target.port()), FORWARD_TIMEOUT_MS);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.write(ByteBuffer.allocate(query.length + 2).putShort((short) query.length).put(query).array());
                output.flush();
                while (true) {
                    byte[] prefix = readTcpBytes(socket, 2, deadline);
                    int length = Short.toUnsignedInt(ByteBuffer.wrap(prefix).getShort());
                    byte[] response = readTcpBytes(socket, length, deadline);
                    if (isValidResponse(query, response, response.length)) {
                        return response;
                    }
                }
            } catch (Exception e) {
                last = e;
                LOG.debugv("DNS TCP forward to {0} failed: {1}", target.address(), e.getMessage());
            }
        }
        throw last != null ? last : new IOException("no resolvers to forward to");
    }

    private static byte[] readTcpBytes(Socket socket, int length, long deadline) throws IOException {
        byte[] bytes = new byte[length];
        InputStream input = socket.getInputStream();
        int offset = 0;
        while (offset < length) {
            long remainingNs = deadline - System.nanoTime();
            if (remainingNs <= 0) {
                throw new SocketTimeoutException("DNS TCP forward timeout");
            }
            socket.setSoTimeout((int) Math.max(1, remainingNs / 1_000_000L));
            int count = input.read(bytes, offset, length - offset);
            if (count == -1) {
                throw new EOFException("DNS TCP response ended before its advertised length");
            }
            offset += count;
        }
        return bytes;
    }

    /**
     * Runs a blocking forward on the pool without Vert.x's per-context ordering, so one
     * unresponsive upstream or rule target does not hold up forwarding for every other query.
     */
    static <T> Future<T> submitForward(WorkerExecutor pool, Callable<T> task) {
        return pool.executeBlocking(task, false);
    }

    private WorkerExecutor forwardPool(Vertx vertx) {
        WorkerExecutor pool = forwardPool;
        if (pool == null) {
            synchronized (this) {
                pool = forwardPool;
                if (pool == null) {
                    pool = vertx.createSharedWorkerExecutor(FORWARD_POOL_NAME, FORWARD_POOL_SIZE);
                    forwardPool = pool;
                }
            }
        }
        return pool;
    }

    private static List<DnsForwardingRule.Target> shuffled(List<DnsForwardingRule.Target> targets) {
        if (targets.size() < 2) {
            return targets;
        }
        List<DnsForwardingRule.Target> order = new ArrayList<>(targets);
        Collections.shuffle(order, ThreadLocalRandom.current());
        return order;
    }

    /**
     * Forwards the query to each upstream in order and returns the first valid UDP response.
     * Throws if every upstream times out or errors, so the caller can log a single warning.
     * The {@code upstreamPort} is parameterised for tests; production always uses {@link #DNS_PORT}.
     */
    static byte[] forwardToUpstreams(byte[] query, List<String> upstreams, int upstreamPort) throws Exception {
        return forwardToTargets(query, upstreams.stream()
                .map(upstream -> new DnsForwardingRule.Target(upstream, upstreamPort)).toList());
    }

    /**
     * Forwards the query to each target in order and returns the first valid UDP response. Targets
     * carry their own port because a resolver rule may name a resolver that does not listen on 53.
     */
    static byte[] forwardToTargets(byte[] query, List<DnsForwardingRule.Target> targets) throws Exception {
        Exception last = null;
        for (DnsForwardingRule.Target target : targets) {
            try (java.net.DatagramSocket fwd = new java.net.DatagramSocket()) {
                InetAddress addr = InetAddress.getByName(target.address());
                fwd.connect(addr, target.port());
                fwd.send(new DatagramPacket(query, query.length, addr, target.port()));
                long deadline = System.nanoTime() + FORWARD_TIMEOUT_MS * 1_000_000L;
                byte[] buf = new byte[MAX_DNS_UDP_RESPONSE];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                while (true) {
                    long remainingNs = deadline - System.nanoTime();
                    if (remainingNs <= 0) {
                        throw new SocketTimeoutException("DNS forward timeout");
                    }
                    resp.setLength(buf.length);
                    // setSoTimeout(0) means wait forever, so never let the rounding reach 0.
                    fwd.setSoTimeout((int) Math.max(1, remainingNs / 1_000_000L));
                    fwd.receive(resp);
                    if (isValidResponse(query, resp.getData(), resp.getLength())) {
                        return Arrays.copyOf(resp.getData(), resp.getLength());
                    }
                }
            } catch (Exception e) {
                last = e;
                LOG.debugv("DNS forward to {0} failed: {1}", target.address(), e.getMessage());
            }
        }
        throw last != null ? last : new IOException("no resolvers to forward to");
    }

    /**
     * Builds the ordered, de-duplicated upstream list the forwarder tries in turn: the
     * resolver(s) from {@code /etc/resolv.conf} first (or Docker's embedded resolver as a
     * baseline when none are usable), then the configured public fallbacks. The fallbacks let
     * public names resolve even when the resolv.conf resolver does not answer, mirroring the
     * {@code --dns <FlociIP> --dns 8.8.8.8} workaround.
     */
    static List<String> composeUpstreams(List<String> resolvConf, List<String> fallbacks) {
        SequencedSet<String> ordered = new LinkedHashSet<>();
        for (String server : resolvConf) {
            if (isUsableUpstream(server)) {
                ordered.add(server.trim());
            }
        }
        if (ordered.isEmpty()) {
            ordered.add(FALLBACK_UPSTREAM);
        }
        if (fallbacks != null) {
            for (String server : fallbacks) {
                if (isUsableUpstream(server)) {
                    ordered.add(server.trim());
                }
            }
        }
        return List.copyOf(ordered);
    }

    private static boolean isUsableUpstream(String server) {
        return server != null && !server.isBlank() && !server.trim().equals("127.0.0.1");
    }

    static List<String> readResolvConfNameservers() {
        List<String> servers = new ArrayList<>();
        try {
            Path path = Path.of("/etc/resolv.conf");
            if (Files.exists(path)) {
                for (String line : Files.readAllLines(path)) {
                    line = line.trim();
                    if (line.startsWith("nameserver ")) {
                        servers.add(line.substring("nameserver ".length()).trim());
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugv("Could not read /etc/resolv.conf: {0}", e.getMessage());
        }
        return servers;
    }

    /**
     * True when the datagram answers this query: same transaction ID, QR bit set, the same question
     * count, and the query's question echoed back. The name is compared ignoring ASCII case, like
     * glibc and c-ares, since a resolver may echo it case-folded. Only the question is compared:
     * clients usually append an EDNS OPT record to the query, and the answer section takes its place
     * in the response.
     */
    private static boolean isValidResponse(byte[] query, byte[] response, int length) {
        int questionEnd = questionEnd(query);
        // Also stops the question comparison reading stale buffer bytes past a short datagram.
        if (questionEnd < 0 || length < questionEnd) {
            return false;
        }
        if (response[0] != query[0] || response[1] != query[1]) {
            return false;
        }
        if ((response[2] & 0x80) == 0) {
            return false;
        }
        if (response[4] != query[4] || response[5] != query[5]) {
            return false;
        }
        int nameEnd = questionEnd - 4;
        for (int i = 12; i < nameEnd; i++) {
            if (asciiLower(response[i]) != asciiLower(query[i])) {
                return false;
            }
        }
        return Arrays.equals(query, nameEnd, questionEnd, response, nameEnd, questionEnd);
    }

    /** Label length bytes are below 64, so lowering every byte of the name only touches letters. */
    private static byte asciiLower(byte b) {
        return b >= 'A' && b <= 'Z' ? (byte) (b + ('a' - 'A')) : b;
    }

    /** End offset of the query's single question (name, type, class), or -1 if it is malformed. */
    private static int questionEnd(byte[] query) {
        int pos = 12;
        while (pos < query.length && query[pos] != 0) {
            pos += 1 + (query[pos] & 0xFF);
        }
        int end = pos + 1 + 4;
        return end <= query.length ? end : -1;
    }
}
