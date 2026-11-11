package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.SweepWorker;
import io.github.hectorvent.floci.core.common.dns.DnsClientVpcSource;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.core.common.docker.LocallyBuiltHelperImage;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.net.VpcRoutingManager;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Owns the protected namespace and the Floci-only nftables table for each managed ENI. */
@ApplicationScoped
public class SecurityGroupFirewallManager implements DnsClientVpcSource {

    private static final Logger LOG = Logger.getLogger(SecurityGroupFirewallManager.class);
    private final DockerClient dockerClient;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final EmbeddedDnsServer embeddedDnsServer;
    // Written under this monitor; concurrent so emulatorRoute can read a current identity without it.
    private final Map<String, ProtectedEndpoint> endpoints = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> pendingHelperCleanup = new HashMap<>();
    private final Set<String> requestedHelperCleanup = new HashSet<>();
    private final Map<String, CompletableFuture<Void>> helperRemovalsInProgress = new HashMap<>();
    private final ConcurrentMap<String, DnsOrigin> dnsOrigins = new ConcurrentHashMap<>();
    /** Every address of each admitted endpoint, for {@link #emulatorRoute}; read without the monitor. */
    private final ConcurrentMap<String, Set<DnsOrigin>> routeOrigins = new ConcurrentHashMap<>();
    private final AtomicBoolean emulatorOriginRefreshQueued = new AtomicBoolean();
    private boolean peerPoliciesNeedRefresh;
    /** What each admitted helper's nftables table currently enforces, recorded only after it installed. */
    private final Map<String, Applied> applied = new HashMap<>();
    /** Registrations that entered {@link #register} and have not installed their own policy yet. */
    private final AtomicInteger arriving = new AtomicInteger();
    /** Retirements queued for ownership capture or namespace quarantine. */
    private final AtomicInteger retiring = new AtomicInteger();
    private RetirementRefresh retirementRefresh;
    private boolean peerRefreshScheduled;
    private long peerRefreshFailures;
    private RuntimeException lastPeerRefreshFailure;
    private Arrival currentArrival;
    /** A registration burst waits at most this long for its own policies before peers refresh. */
    private static final long COALESCE_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long ADMISSION_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(5);
    private Executor peerRefresh = task -> Thread.ofVirtual().name("security-group-peer-refresh").start(task);
    @Inject
    Instance<VpcRoutingManager> vpcRouting;
    @Inject
    Instance<EmulatorOriginSource> emulatorOriginSources;
    /** Null in unit tests, which model Floci in a container. */
    @Inject
    ContainerDetector containerDetector;
    /** Null in unit tests, which fall back to the embedded DNS address as Floci's only address. */
    @Inject
    CurrentContainerNetworkResolver currentContainer;
    private final Map<String, List<SecurityGroupNftCompiler.EmulatorOrigin>> lastEmulatorOrigins =
            new ConcurrentHashMap<>();
    private volatile List<SelfAddress> lastSelfAddresses = List.of();
    private final SweepWorker dnsOriginObserver = new SweepWorker(
            "protected-namespace-dns", true, 1, false, ignored -> retireDnsOrigins());

    @Inject
    public SecurityGroupFirewallManager(DockerClient dockerClient, ContainerBuilder containerBuilder,
                                        ContainerLifecycleManager lifecycleManager, EmulatorConfig config,
                                        EmbeddedDnsServer embeddedDnsServer) {
        this.dockerClient = dockerClient;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.embeddedDnsServer = embeddedDnsServer;
    }

    public boolean enabled() {
        return config.network().securityGroupEnforcement().enabled();
    }

    /** Resolve the caller only from addresses actually attached to an admitted namespace. */
    @Override
    public Optional<ClientVpc> vpcForClient(String clientAddress) {
        if (clientAddress == null || clientAddress.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(dnsOrigins.get(clientAddress)).map(DnsOrigin::vpc);
    }

    void startDnsOriginObserver(@Observes StartupEvent event) {
        if (enabled()) {
            dnsOriginObserver.start();
        }
    }

    @PreDestroy
    void stopDnsOriginObserver() {
        dnsOriginObserver.stop();
        dnsOrigins.clear();
        routeOrigins.clear();
        lastEmulatorOrigins.clear();
    }

    /** Observe Docker outside the firewall monitor; never inspect a container on a DNS query. */
    void retireDnsOrigins() {
        try {
            observeDnsOrigins();
        } finally {
            retryRequestedHelperCleanup();
        }
    }

    private void observeDnsOrigins() {
        Map<String, DnsOrigin> observed = Map.copyOf(dnsOrigins);
        if (observed.isEmpty()) {
            retireEndpoints(List.of());
            return;
        }
        Map<String, Set<String>> liveAddresses = new HashMap<>();
        Map<String, Set<String>> liveNetworks = new HashMap<>();
        List<String> helperIds = observed.values().stream().map(DnsOrigin::helperId).distinct().toList();
        for (Container container : dockerClient.listContainersCmd().withShowAll(true).withIdFilter(helperIds).exec()) {
            if ("running".equals(container.getState()) && container.getNetworkSettings() != null
                    && container.getNetworkSettings().getNetworks() != null) {
                Set<String> addresses = new HashSet<>();
                container.getNetworkSettings().getNetworks().values().stream()
                        .map(ContainerNetwork::getIpAddress)
                        .filter(address -> address != null && !address.isBlank()).forEach(addresses::add);
                liveAddresses.put(container.getId(), addresses);
                liveNetworks.put(container.getId(), container.getNetworkSettings().getNetworks().keySet());
            }
        }
        List<DnsOrigin> retired = new ArrayList<>();
        observed.forEach((address, origin) -> {
            if (!liveAddresses.getOrDefault(origin.helperId(), Set.of()).contains(address)
                    || origin.nativeNetwork() != null
                    && !liveNetworks.getOrDefault(origin.helperId(), Set.of()).contains(origin.nativeNetwork())) {
                // A new registration may already own this address; retire only the observed identity.
                dnsOrigins.computeIfPresent(address, (key, current) -> {
                    if (current != origin) {
                        return current;
                    }
                    retired.add(origin);
                    return null;
                });
            }
        });
        retired.forEach(this::removeDnsOrigin);
        retireEndpoints(retired);
    }

    private synchronized void retireEndpoints(List<DnsOrigin> retired) {
        for (DnsOrigin origin : retired) {
            ProtectedEndpoint current = endpoints.get(origin.eniId());
            if (current != null && current.dnsOrigin() == origin) {
                removeDnsOrigin(current);
                endpoints.remove(origin.eniId());
                pendingHelperCleanup.computeIfAbsent(origin.eniId(), ignored -> new HashSet<>())
                        .add(current.helperId());
                peerPoliciesNeedRefresh = true;
                quarantine(current.helperId());
            } else if (endpoints.values().stream()
                    .noneMatch(endpoint -> endpoint.helperId().equals(origin.helperId()))) {
                // The observer may lose the monitor to a replacement, but still owns the old helper.
                pendingHelperCleanup.computeIfAbsent(origin.eniId(), ignored -> new HashSet<>())
                        .add(origin.helperId());
                quarantine(origin.helperId());
            }
        }
        // A failed installation quarantines peers. Retry even when the last DNS origin is gone.
        reconcileRetirements();
    }

    /** The observer retries removals an owning service requested but Docker has not completed. */
    private void retryRequestedHelperCleanup() {
        Set<String> helpers;
        synchronized (this) {
            helpers = Set.copyOf(requestedHelperCleanup);
        }
        for (String helper : helpers) {
            synchronized (this) {
                if (helperRemovalsInProgress.containsKey(helper)) {
                    continue;
                }
            }
            try {
                removeRequestedHelper(helper);
            } catch (RuntimeException failure) {
                LOG.warnv(failure, "Could not remove retired security-group helper {0}; retaining cleanup", helper);
            }
        }
    }

    /** Removes a requested helper outside the monitor; a concurrent caller joins the removal in progress. */
    private void removeRequestedHelper(String helper) {
        CompletableFuture<Void> removal;
        boolean startRemoval;
        synchronized (this) {
            if (!requestedHelperCleanup.contains(helper)) {
                return;
            }
            removal = helperRemovalsInProgress.get(helper);
            startRemoval = removal == null;
            if (startRemoval) {
                removal = new CompletableFuture<>();
                helperRemovalsInProgress.put(helper, removal);
            }
        }
        if (!startRemoval) {
            try {
                removal.join();
            } catch (CompletionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw new IllegalStateException("Security-group helper removal failed", failure.getCause());
            }
            return;
        }
        try {
            lifecycleManager.removeIfExistsStrict(helper);
            synchronized (this) {
                forgetHelperCleanup(helper);
            }
            removal.complete(null);
        } catch (RuntimeException | Error failure) {
            removal.completeExceptionally(failure);
            throw failure;
        } finally {
            synchronized (this) {
                helperRemovalsInProgress.remove(helper, removal);
            }
        }
    }

    private void forgetHelperCleanup(String helper) {
        requestedHelperCleanup.remove(helper);
        pendingHelperCleanup.values().forEach(helpers -> helpers.remove(helper));
        pendingHelperCleanup.values().removeIf(Set::isEmpty);
    }

    private void removeDnsOrigin(ProtectedEndpoint protectedEndpoint) {
        removeDnsOrigin(protectedEndpoint.dnsOrigin());
    }

    private void removeDnsOrigin(DnsOrigin origin) {
        origin.transportAddresses().forEach(transport ->
                dnsOrigins.computeIfPresent(transport,
                        (address, current) -> current == origin ? null : current));
        removeRouteOrigin(origin);
    }

    private void addRouteOrigin(DnsOrigin origin) {
        origin.routeAddresses().forEach(address -> routeOrigins.merge(address, Set.of(origin), (holders, added) -> {
            Set<DnsOrigin> merged = new HashSet<>(holders);
            merged.addAll(added);
            return Set.copyOf(merged);
        }));
    }

    private void removeRouteOrigin(DnsOrigin origin) {
        origin.routeAddresses().forEach(routed -> routeOrigins.computeIfPresent(routed, (address, holders) -> {
            Set<DnsOrigin> remaining = new HashSet<>(holders);
            remaining.removeIf(holder -> holder == origin);
            return remaining.isEmpty() ? null : Set.copyOf(remaining);
        }));
    }

    /**
     * The address Floci dials for a load balancer in the given VPC to reach {@code address}. With
     * isolated VPC address spaces a protected endpoint of that VPC is reached at its transport address
     * on Floci's main network, since its private address lives only inside its namespace; otherwise
     * the address named is dialled, as Floci joins the VPC network. An address that only protected
     * endpoints of other VPCs or accounts hold, or any protected address when {@code vpcId} is null,
     * gives null: no load balancer reaches them on AWS. Any other address is returned unchanged.
     */
    public String emulatorRoute(String accountId, String region, String vpcId, String address) {
        return emulatorRoute(accountId, region, vpcId, address, -1, null);
    }

    /**
     * As {@link #emulatorRoute(String, String, String, String)}, and a protected endpoint is also
     * refused (null) unless its ingress admits tcp {@code port} from a peer in {@code originGroups},
     * the dialling load balancer's own security groups, at the address Floci sends from. The firewall's
     * rule admits Floci's shared address for every such load balancer of the VPC, so this decides per
     * load balancer, as AWS does. {@code originGroups} null skips the check.
     */
    public String emulatorRoute(String accountId, String region, String vpcId, String address,
                                int port, Set<String> originGroups) {
        String canonical = canonicalAddress(address);
        Set<DnsOrigin> holders = canonical == null ? null : routeOrigins.get(canonical);
        if (holders == null || holders.isEmpty()) {
            return address;
        }
        if (vpcId == null) {
            // A target group or load balancer outside any VPC reaches no protected endpoint.
            return null;
        }
        ClientVpc vpc = new ClientVpc(accountId, region, vpcId);
        Optional<DnsOrigin> holder = holders.stream().filter(candidate -> candidate.vpc().equals(vpc))
                .min(Comparator.comparing((DnsOrigin candidate) -> !canonical.equals(candidate.transportAddress()))
                        .thenComparing(DnsOrigin::eniId));
        if (holder.isEmpty()) {
            return null;
        }
        if (originGroups != null && !admitsOrigin(holder.get(), accountId, vpcId, port, originGroups)) {
            return null;
        }
        // Floci joins shared-address VPC networks and reaches a VPC address from its own address
        // there; an isolated VPC address lives only inside the namespace.
        return isolatedAddressSpaces() ? holder.get().transportAddress() : canonical;
    }

    private boolean admitsOrigin(DnsOrigin holder, String accountId, String vpcId, int port,
                                 Set<String> originGroups) {
        ProtectedEndpoint current = endpoints.get(holder.eniId());
        if (current == null) {
            return false;
        }
        List<String> sources = emulatorAddresses(current.endpoint(), lastSelfAddresses);
        String source = sources.isEmpty() ? null : sources.getFirst();
        return SecurityGroupPolicy.allows(current.endpoint().groups(), false, "tcp", port, -1,
                new SecurityGroupPolicy.Peer(source, accountId, vpcId, Set.copyOf(originGroups)),
                current.prefixLists());
    }

    /** One spelling per address, so an IPv4-mapped or expanded IPv6 form finds the same endpoint. */
    private static String canonicalAddress(String address) {
        if (address != null && address.length() > 2 && address.startsWith("[") && address.endsWith("]")) {
            // A bracketed IPv6 literal resolves to the same address as its bare form.
            address = address.substring(1, address.length() - 1);
        }
        if (address != null && address.indexOf('%') > 0 && address.indexOf(':') >= 0) {
            // A zone-scoped IPv6 literal dials the same address without its scope.
            address = address.substring(0, address.indexOf('%'));
        }
        if (address == null || address.isBlank() || address.contains("%")
                || !address.matches("[0-9a-fA-F:.]+")) {
            return address;
        }
        try {
            return InetAddress.ofLiteral(address).getHostAddress();
        } catch (IllegalArgumentException e) {
            return address;
        }
    }

    /** {@link #emulatorRoute} through a load balancer's optional manager; unchanged without enforcement. */
    public static String emulatorRoute(Instance<SecurityGroupFirewallManager> firewall, String accountId,
                                       String region, String vpcId, String address,
                                       int port, Set<String> originGroups) {
        if (firewall == null || !firewall.isResolvable()) {
            return address;
        }
        SecurityGroupFirewallManager manager = firewall.get();
        return manager.enabled()
                ? manager.emulatorRoute(accountId, region, vpcId, address, port, originGroups) : address;
    }

    @PostConstruct
    void quarantineSurvivingNamespaces() {
        if (!enabled()) {
            return;
        }
        try {
            String owner = ContainerStorageHelper.ownerIdentity(config);
            ContainerStorageHelper.LabelAliases aliases = ContainerStorageHelper.CONTAINER_LABEL_ALIASES;
            aliases.listByLabels(Map.of(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true"),
                            filter -> dockerClient.listContainersCmd().withShowAll(true)
                                    .withLabelFilter(filter).exec(),
                            Container::getId).stream()
                    .filter(container -> aliases.consistent(container.getId(), container.getLabels())
                            && aliases.matches(container.getLabels(), ContainerStorageHelper.OWNER_LABEL, owner))
                    .forEach(container -> {
                        if ("running".equals(container.getState())) {
                            quarantine(container.getId());
                        } else {
                            stopNamespaceWorkloads(container.getId());
                        }
                        if ("ecs".equals(container.getLabels().get(ContainerStorageHelper.SERVICE_LABEL))) {
                            removeNamespaceWorkloads(container.getId());
                            lifecycleManager.removeIfExists(container.getId());
                        }
                    });
        } catch (Exception e) {
            LOG.warnv("Could not inspect surviving security-group namespaces: {0}", e.getMessage());
        }
    }

    /** The helper starts before any workload process and owns all published ports. */
    public Namespace createNamespace(String service, String resourceId, String accountId, String region,
                                     Optional<String> dockerNetwork, Map<Integer, Integer> portBindings) {
        return createNamespace(service, resourceId, accountId, region, dockerNetwork, portBindings, Map.of());
    }

    /** {@link #createNamespace} with extra labels on the helper, as the owning service tracks it by. */
    public Namespace createNamespace(String service, String resourceId, String accountId, String region,
                                     Optional<String> dockerNetwork, Map<Integer, Integer> portBindings,
                                     Map<String, String> extraLabels) {
        return createNamespace(service, resourceId, accountId, region, dockerNetwork, portBindings, extraLabels, null);
    }

    /** Lets the owning service choose the host interface for published namespace ports. */
    public Namespace createNamespace(String service, String resourceId, String accountId, String region,
                                     Optional<String> dockerNetwork, Map<Integer, Integer> portBindings,
                                     Map<String, String> extraLabels, String hostIp) {
        return createNamespace(service, resourceId, accountId, region, dockerNetwork, portBindings,
                extraLabels, hostIp, null);
    }

    /**
     * Lets the owning service choose the hosts entries its workloads see, as "hostname:ip" pairs.
     * Workloads join this namespace and so read its hosts file; {@code null} keeps the default of
     * {@code host.docker.internal} on a Linux host.
     */
    public Namespace createNamespace(String service, String resourceId, String accountId, String region,
                                     Optional<String> dockerNetwork, Map<Integer, Integer> portBindings,
                                     Map<String, String> extraLabels, String hostIp, List<String> workloadHosts) {
        if (!enabled()) {
            throw new IllegalStateException("Security-group enforcement is disabled");
        }
        Info daemon = dockerClient.infoCmd().exec();
        if (!"linux".equalsIgnoreCase(daemon.getOsType())) {
            throw new IllegalStateException("Security-group enforcement requires a Linux Docker daemon");
        }
        if (daemon.getSecurityOptions() != null && daemon.getSecurityOptions().stream()
                .anyMatch(option -> option.toLowerCase(Locale.ROOT).contains("rootless"))) {
            throw new IllegalStateException("Security-group enforcement requires rootful Docker");
        }
        ensureHelperImage();
        String name = ContainerStorageHelper.resourceName(config, "sg", null,
                resourceId.replaceAll("[^a-zA-Z0-9_.-]", "-"));
        ContainerBuilder.Builder builder = containerBuilder.newContainer(config.network().securityGroupEnforcement().helperImage())
                .withName(name)
                .withDockerNetwork(dockerNetwork)
                // Workloads join this network namespace and use its resolver and hosts file.
                .withEmbeddedDns()
                .withEntrypoint(List.of("sh", "-c"))
                .withCmd(List.of("exec sleep 2147483647"))
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(service, resourceId, accountId, region))
                .withLabels(Map.of(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true",
                        ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)))
                .withLabels(extraLabels);
        if (workloadHosts == null) {
            builder.withHostDockerInternalOnLinux();
        } else {
            for (String entry : workloadHosts) {
                int separator = entry.indexOf(':');
                builder.withExtraHost(entry.substring(0, separator), entry.substring(separator + 1));
            }
        }
        if (portBindings != null) {
            portBindings.forEach((containerPort, hostPort) -> {
                if (hostIp == null) {
                    builder.withPortBinding(containerPort, hostPort);
                } else {
                    builder.withPortBinding(containerPort, hostPort, hostIp);
                }
            });
        }
        ContainerSpec spec = builder.build();
        String helperId = null;
        try {
            helperId = lifecycleManager.createAndStart(spec).containerId();
            String ip = lifecycleManager.resolveContainerNetworkIp(helperId, spec.networkMode());
            if (ip == null || ip.isBlank()) {
                throw new IllegalStateException("Firewall helper has no Docker IP");
            }
            apply(helperId, SecurityGroupNftCompiler.initialRuleset());
            String resolver = embeddedDnsServer.getServerIp().orElse(null);
            if (resolver != null) {
                // Docker's resolver does not forward from internal networks. Workloads inherit
                // this file, so finish configuring the VPC resolver before admitting any workload.
                ContainerExec.Result result = ContainerExec.run(dockerClient, helperId, new String[]{
                        "sh", "-c", "printf 'nameserver %s\\noptions ndots:0\\n' \"$1\" > /etc/resolv.conf",
                        "floci-vpc-dns", resolver}, 10).throwIfTimedOut(helperId);
                if (result.exitCode() != 0) {
                    throw new IllegalStateException("Cannot configure namespace DNS: " + result.summary());
                }
                ContainerExec.Result routing = ContainerExec.run(dockerClient, helperId,
                        new String[]{"sh", "-c", VpcResolverRouting.commands(resolver)}, 10).throwIfTimedOut(helperId);
                if (routing.exitCode() != 0) {
                    throw new IllegalStateException("Cannot route the VPC link-local resolver: " + routing.summary());
                }
            }
            return new Namespace(helperId, ip);
        } catch (Exception e) {
            if (helperId != null) {
                lifecycleManager.removeIfExists(helperId);
            }
            throw new IllegalStateException("Cannot prepare protected network namespace for " + resourceId, e);
        }
    }

    private void ensureHelperImage() {
        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                config.network().securityGroupEnforcement().helperImage(), "floci/network-helper:local",
                "/docker/network-helper.Dockerfile");
    }

    /**
     * Registers only after the default-deny table exists, before workload startup. Returns once the
     * endpoint and every peer enforce their complete policies toward each other, as a full reconcile
     * would leave them.
     */
    public void register(SecurityGroupNftCompiler.Endpoint endpoint, String helperId,
                         Map<String, List<String>> prefixLists) {
        register(endpoint, helperId, prefixLists, true);
    }

    /**
     * {@link #register} that, with {@code awaitPeers} false, returns once the endpoint's own policy is
     * installed. Peers learn the endpoint in one coalesced refresh per registration burst. Until a peer
     * enforces the endpoint's identity, the endpoint's own policy withholds every accept toward that
     * peer, so no pair is ever more permissive than its complete policies allow.
     */
    public void register(SecurityGroupNftCompiler.Endpoint endpoint, String helperId,
                         Map<String, List<String>> prefixLists, boolean awaitPeers) {
        Arrival arrival = new Arrival();
        arriving.incrementAndGet();
        try {
            registerQueued(endpoint, helperId, prefixLists, awaitPeers, arrival);
        } finally {
            arrived(arrival);
            clearArrival(arrival);
        }
    }

    private synchronized void clearArrival(Arrival arrival) {
        if (currentArrival == arrival) {
            currentArrival = null;
        }
    }

    /** One registration's place in {@link #arriving}; it leaves exactly once. */
    private static final class Arrival {
        private boolean left;
    }

    private synchronized void arrived(Arrival arrival) {
        if (arrival != null && !arrival.left) {
            arrival.left = true;
            arriving.decrementAndGet();
            notifyAll();
        }
    }

    private synchronized void registerQueued(SecurityGroupNftCompiler.Endpoint endpoint, String helperId,
                                             Map<String, List<String>> prefixLists, boolean awaitPeers,
                                             Arrival arrival) {
        // The monitor is held from here until admit() has installed this endpoint's own policy.
        currentArrival = arrival;
        if (helperRemovalsInProgress.containsKey(helperId)) {
            throw new IllegalStateException("Security-group helper removal is in progress");
        }
        List<String> addresses = endpoint.transportAddresses();
        if (endpoints.values().stream().map(ProtectedEndpoint::endpoint)
                .anyMatch(existing -> !existing.eniId().equals(endpoint.eniId())
                        && sharesTransport(existing, endpoint))) {
            quarantine(helperId);
            throw new IllegalStateException("Two managed ENIs share a Docker transport address");
        }
        String nativeNetwork = null;
        if (isolatedAddressSpaces()) {
            nativeNetwork = VpcNetworkManager.nativeNetworkName(
                    config, endpoint.accountId(), endpoint.region(), endpoint.vpcId());
            InspectContainerResponse helper = dockerClient.inspectContainerCmd(helperId).exec();
            if (!endpoint.additionalTransportAddresses().contains(endpoint.logicalAddress())
                    || helper.getNetworkSettings() == null || helper.getNetworkSettings().getNetworks() == null
                    || !helper.getNetworkSettings().getNetworks().containsKey(nativeNetwork)) {
                quarantine(helperId);
                throw new IllegalStateException("The protected endpoint has no native VPC attachment");
            }
            addresses = List.of(endpoint.transportAddress());
        }
        ProtectedEndpoint registered = new ProtectedEndpoint(endpoint, helperId, Map.copyOf(prefixLists),
                new DnsOrigin(endpoint.eniId(), helperId,
                        new ClientVpc(endpoint.accountId(), endpoint.region(), endpoint.vpcId()),
                        addresses, nativeNetwork, endpoint.transportAddress(), routeAddresses(endpoint)));
        ProtectedEndpoint previous = endpoints.put(endpoint.eniId(), registered);
        // Indexed before its policy admits anyone, so no load balancer of another VPC dials it meanwhile.
        addRouteOrigin(registered.dnsOrigin());
        try {
            admit(endpoint.eniId(), awaitPeers);
            if (vpcRouting != null && !vpcRouting.isUnsatisfied()) {
                vpcRouting.get().register(endpoint, helperId, vpcResolvers());
            }
            if (previous != null) {
                removeDnsOrigin(previous);
                if (!previous.helperId().equals(helperId) && endpoints.values().stream()
                        .noneMatch(current -> current.helperId().equals(previous.helperId()))) {
                    // The replaced helper stays the ENI owner's to remove.
                    pendingHelperCleanup.computeIfAbsent(endpoint.eniId(), ignored -> new HashSet<>())
                            .add(previous.helperId());
                    quarantine(previous.helperId());
                }
            }
            ProtectedEndpoint admitted = endpoints.get(endpoint.eniId());
            if (admitted != null) {
                addresses.forEach(address -> dnsOrigins.put(address, admitted.dnsOrigin()));
                forgetHelperCleanup(helperId);
            }
        } catch (RuntimeException e) {
            removeRouteOrigin(registered.dnsOrigin());
            // Revert only this registration: a concurrent one for the same ENI may have replaced it.
            if (endpoints.get(endpoint.eniId()) == registered) {
                if (previous == null) {
                    endpoints.remove(endpoint.eniId());
                    applied.remove(endpoint.eniId());
                } else {
                    endpoints.put(endpoint.eniId(), previous);
                }
            }
            quarantine(helperId);
            // Peers may still enforce rules for the reverted identity, and a restored previous
            // registration on this helper was just flushed: converge them again.
            schedulePeerRefresh();
            throw e;
        }
    }

    /**
     * Installs the registered endpoint's own policy, then (with {@code awaitPeers}) waits for the
     * coalesced peer refresh to leave the endpoint and its peers with their complete policies.
     */
    void admit(String eniId, boolean awaitPeers) {
        ProtectedEndpoint target = endpoints.get(eniId);
        install(target, peerEndpoints(), vpcResolvers(), emulatorOrigins(), selfAddresses(), false);
        Arrival arrival = currentArrival;
        currentArrival = null;
        arrived(arrival);
        if (!awaitPeers) {
            schedulePeerRefresh();
            return;
        }
        long failures = peerRefreshFailures;
        long deadline = System.nanoTime() + ADMISSION_TIMEOUT_NANOS;
        while (true) {
            ProtectedEndpoint current = endpoints.get(eniId);
            if (current == null || !current.helperId().equals(target.helperId())) {
                throw new IllegalStateException("Protected endpoint " + eniId + " was retired before admission");
            }
            if (complete(current)) {
                return;
            }
            if (peerRefreshFailures != failures) {
                throw new IllegalStateException("Peer security-group policies could not be refreshed",
                        lastPeerRefreshFailure);
            }
            schedulePeerRefresh();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("Protected endpoint " + eniId + " was not admitted by its peers");
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted awaiting peer security-group policies", e);
            }
        }
    }

    private void schedulePeerRefresh() {
        if (!peerRefreshScheduled) {
            peerRefreshScheduled = true;
            try {
                peerRefresh.execute(this::refreshPeers);
            } catch (RuntimeException e) {
                peerRefreshScheduled = false;
                throw e;
            }
        }
    }

    /** One coalesced pass: lets queued registrations install their own policies first (bounded). */
    synchronized void refreshPeers() {
        long deadline = System.nanoTime() + COALESCE_NANOS;
        try {
            long remaining;
            while (arriving.get() > 0 && (remaining = deadline - System.nanoTime()) > 0) {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        peerRefreshScheduled = false;
        try {
            retireStoppedHelpers();
            converge(false);
        } catch (RuntimeException e) {
            peerRefreshFailures++;
            lastPeerRefreshFailure = e;
            LOG.errorv(e, "Could not refresh peer security-group policies");
        } finally {
            notifyAll();
        }
    }

    private boolean isolatedAddressSpaces() {
        return config.services().ec2().vpcNetworks().isolateAddressSpaces();
    }

    private boolean sharesTransport(SecurityGroupNftCompiler.Endpoint first,
                                     SecurityGroupNftCompiler.Endpoint second) {
        if (!isolatedAddressSpaces() || first.sameVpc(second)) {
            return first.transportAddresses().stream().anyMatch(second.transportAddresses()::contains);
        }
        return first.transportAddresses().contains(second.transportAddress())
                || second.transportAddresses().contains(first.transportAddress());
    }

    /** Removes the port-owning helper without forgetting its identity if Docker fails. */
    public void removeNamespaceStrict(String eniId) {
        Set<String> helpers;
        synchronized (this) {
            helpers = new HashSet<>(pendingHelperCleanup.getOrDefault(eniId, Set.of()));
            ProtectedEndpoint endpoint = endpoints.get(eniId);
            if (endpoint != null) {
                helpers.add(endpoint.helperId());
            }
            requestedHelperCleanup.addAll(helpers);
        }
        for (String helper : helpers) {
            removeRequestedHelper(helper);
        }
    }

    public void unregister(String eniId) {
        retire(retirement -> captureRetirement(eniId).accept(retirement));
    }

    /** Captures ownership before asynchronous teardown makes a retained ENI available again. */
    public Runnable captureUnregister(String eniId) {
        Consumer<Retirement> captured = captureRetirement(eniId);
        return () -> retire(captured);
    }

    private synchronized Consumer<Retirement> captureRetirement(String eniId) {
        ProtectedEndpoint captured = endpoints.get(eniId);
        Set<String> helpers = new HashSet<>(pendingHelperCleanup.getOrDefault(eniId, Set.of()));
        if (captured != null) {
            helpers.add(captured.helperId());
        }
        return retirement -> unregisterCaptured(eniId, captured, helpers, retirement);
    }

    private void retire(Consumer<Retirement> action) {
        Retirement retirement = new Retirement();
        retiring.incrementAndGet();
        try {
            action.accept(retirement);
        } finally {
            retirementPrepared(retirement);
        }
    }

    private static final class Retirement {
        private boolean prepared;
    }

    private static final class RetirementRefresh {
        private boolean complete;
        private Throwable failure;
    }

    private synchronized void retirementPrepared(Retirement retirement) {
        if (!retirement.prepared) {
            retirement.prepared = true;
            retiring.decrementAndGet();
            notifyAll();
        }
    }

    /** A completed batch remains its callers' barrier even when another batch starts. */
    private void reconcileRetirements() {
        if (!peerPoliciesNeedRefresh) {
            return;
        }
        RetirementRefresh refresh = retirementRefresh;
        if (refresh != null) {
            try {
                while (!refresh.complete) {
                    wait();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting security-group retirement", error);
            }
            if (refresh.failure instanceof RuntimeException failure) {
                throw failure;
            }
            if (refresh.failure instanceof Error failure) {
                throw failure;
            }
            return;
        }
        refresh = new RetirementRefresh();
        retirementRefresh = refresh;
        try {
            long deadline = System.nanoTime() + COALESCE_NANOS;
            try {
                long remaining;
                while (retiring.get() > 0 && (remaining = deadline - System.nanoTime()) > 0) {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while preparing security-group retirement", error);
            }
            reconcileAll();
            peerPoliciesNeedRefresh = false;
        } catch (RuntimeException | Error failure) {
            refresh.failure = failure;
            throw failure;
        } finally {
            refresh.complete = true;
            retirementRefresh = null;
            notifyAll();
        }
    }

    private void unregisterCaptured(String eniId, ProtectedEndpoint captured, Set<String> capturedHelpers,
                                    Retirement retirement) {
        Set<String> helpers;
        synchronized (this) {
            ProtectedEndpoint current = endpoints.get(eniId);
            boolean owned = captured != null && current != null && current.dnsOrigin() == captured.dnsOrigin();
            if ((current == null || owned) && vpcRouting != null && !vpcRouting.isUnsatisfied()) {
                // A replacement registered on this ENI after the capture keeps its routes.
                vpcRouting.get().unregister(eniId);
            }
            if (owned) {
                endpoints.remove(eniId);
                applied.remove(eniId);
                removeDnsOrigin(current);
                pendingHelperCleanup.computeIfAbsent(eniId, ignored -> new HashSet<>()).add(current.helperId());
                peerPoliciesNeedRefresh = true;
                quarantine(current.helperId());
            }
            // Retiring peer permissions must not discard the owning service's later cleanup obligation.
            helpers = new HashSet<>(capturedHelpers);
            helpers.removeIf(helper -> endpoints.values().stream()
                    .anyMatch(endpoint -> endpoint.helperId().equals(helper)));
            helpers.retainAll(pendingHelperCleanup.getOrDefault(eniId, Set.of()));
            requestedHelperCleanup.addAll(helpers);
            retirementPrepared(retirement);
            reconcileRetirements();
        }
        for (String helper : helpers) {
            removeRequestedHelper(helper);
        }
    }

    public synchronized void publishPublicAddress(String eniId, String address) {
        ProtectedEndpoint current = endpoints.get(eniId);
        if (current == null) {
            if (address == null) {
                return;
            }
            throw new IllegalStateException("Public address has no admitted security-group endpoint");
        }
        if (vpcRouting != null && !vpcRouting.isUnsatisfied()) {
            vpcRouting.get().attachPublicInstance(current.endpoint(), current.helperId(), address);
        }
    }

    public synchronized void updateGroups(String eniId, Set<String> groupIds,
                                          Map<String, SecurityGroup> groups,
                                          Map<String, List<String>> prefixLists) {
        ProtectedEndpoint current = endpoints.get(eniId);
        if (current == null) {
            return;
        }
        SecurityGroupNftCompiler.Endpoint identity = current.endpoint();
        List<SecurityGroup> attached = groupIds.stream().map(groups::get).toList();
        if (attached.isEmpty() || attached.stream().anyMatch(group -> group == null)) {
            quarantine(current.helperId());
            throw new IllegalArgumentException("Protected endpoint needs valid security groups");
        }
        SecurityGroupNftCompiler.Endpoint updated = new SecurityGroupNftCompiler.Endpoint(
                identity.accountId(), identity.region(), identity.vpcId(), identity.eniId(),
                identity.logicalAddress(), identity.transportAddress(), Set.copyOf(groupIds), attached,
                identity.controlConnections(), identity.instanceMetadataAllowed(),
                identity.additionalTransportAddresses(), identity.controlListeners());
        endpoints.put(eniId, new ProtectedEndpoint(updated, current.helperId(), Map.copyOf(prefixLists),
                current.dnsOrigin()));
        reconcileAll();
    }

    public synchronized void reconcileAll() {
        retireStoppedHelpers();
        converge(true);
    }

    private void retireStoppedHelpers() {
        for (Map.Entry<String, ProtectedEndpoint> entry : new ArrayList<>(endpoints.entrySet())) {
            boolean running;
            try {
                running = Boolean.TRUE.equals(dockerClient.inspectContainerCmd(entry.getValue().helperId())
                        .exec().getState().getRunning());
            } catch (NotFoundException e) {
                running = false;
            }
            if (!running) {
                removeDnsOrigin(entry.getValue());
                endpoints.remove(entry.getKey());
                pendingHelperCleanup.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>())
                        .add(entry.getValue().helperId());
                peerPoliciesNeedRefresh = true;
                quarantine(entry.getValue().helperId());
            }
        }
    }

    /**
     * Brings every endpoint to its complete policy. Each install admits only peers that already
     * enforce the target's current identity, so the order of installs never opens a pair wider than
     * its complete policies. Endpoints whose identity peers do not know yet install first, withdrawing
     * their own accepts before peers learn them. {@code force} re-installs unchanged policies too.
     */
    private void converge(boolean force) {
        List<String> vpcResolvers = vpcResolvers();
        List<SecurityGroupNftCompiler.EmulatorOrigin> origins = emulatorOrigins();
        List<SelfAddress> self = selfAddresses();
        try {
            for (int round = 0; round < 3; round++) {
                List<SecurityGroupNftCompiler.Endpoint> peers = peerEndpoints();
                List<ProtectedEndpoint> order = new ArrayList<>(endpoints.values());
                order.sort(Comparator.comparing((ProtectedEndpoint target) -> knownByAllPeers(target))
                        .thenComparing(target -> target.endpoint().eniId()));
                boolean changed = false;
                for (ProtectedEndpoint target : order) {
                    changed |= install(target, peers, vpcResolvers, origins, self, force && round == 0);
                }
                if (!changed && endpoints.values().stream().allMatch(this::complete)) {
                    return;
                }
            }
            throw new IllegalStateException("Security-group policies did not converge");
        } catch (RuntimeException e) {
            endpoints.values().forEach(endpoint -> quarantine(endpoint.helperId()));
            throw e;
        }
    }

    /** Every registered endpoint in ENI order, so a policy's text depends only on its inputs. */
    private List<SecurityGroupNftCompiler.Endpoint> peerEndpoints() {
        return endpoints.values().stream().map(ProtectedEndpoint::endpoint)
                .sorted(Comparator.comparing(SecurityGroupNftCompiler.Endpoint::eniId)).toList();
    }

    /** Installs {@code target}'s policy toward the peers that enforce its identity; true when Docker ran. */
    private boolean install(ProtectedEndpoint target, List<SecurityGroupNftCompiler.Endpoint> peers,
                            List<String> vpcResolvers, List<SecurityGroupNftCompiler.EmulatorOrigin> origins,
                            List<SelfAddress> self, boolean force) {
        String eniId = target.endpoint().eniId();
        Set<String> admitted = endpoints.values().stream()
                .filter(peer -> peer != target && knows(peer, target))
                .map(peer -> peer.endpoint().eniId())
                .collect(Collectors.toSet());
        String rules = SecurityGroupNftCompiler.compile(target.endpoint(), peers, target.prefixLists(),
                vpcResolvers, isolatedAddressSpaces(), peer -> admitted.contains(peer.eniId()),
                origins, emulatorAddresses(target.endpoint(), self));
        Map<String, PeerView> known = endpoints.values().stream()
                .filter(peer -> !peer.endpoint().eniId().equals(eniId))
                .collect(Collectors.toMap(peer -> peer.endpoint().eniId(), PeerView::of));
        Applied current = applied.get(eniId);
        boolean unchanged = current != null && current.helperId().equals(target.helperId())
                && current.rules().equals(rules);
        if (!unchanged || force) {
            applied.remove(eniId);
            apply(target.helperId(), rules);
        }
        applied.put(eniId, new Applied(target.helperId(), rules, Map.copyOf(known), Set.copyOf(admitted)));
        return !unchanged || force;
    }

    /** Whether {@code holder}'s installed table enforces {@code subject}'s current identity. */
    private boolean knows(ProtectedEndpoint holder, ProtectedEndpoint subject) {
        Applied state = applied.get(holder.endpoint().eniId());
        return state != null && state.helperId().equals(holder.helperId())
                && PeerView.of(subject).equals(state.known().get(subject.endpoint().eniId()));
    }

    private boolean knownByAllPeers(ProtectedEndpoint target) {
        return endpoints.values().stream()
                .filter(peer -> peer != target)
                .allMatch(peer -> knows(peer, target));
    }

    /** The endpoint and every peer enforce their complete policies toward each other. */
    private boolean complete(ProtectedEndpoint target) {
        Applied state = applied.get(target.endpoint().eniId());
        if (state == null || !state.helperId().equals(target.helperId())) {
            return false;
        }
        for (ProtectedEndpoint peer : endpoints.values()) {
            if (peer == target) {
                continue;
            }
            Applied peerState = applied.get(peer.endpoint().eniId());
            if (!knows(target, peer) || !state.admitted().contains(peer.endpoint().eniId())
                    || !knows(peer, target) || peerState == null
                    || !peerState.admitted().contains(target.endpoint().eniId())) {
                return false;
            }
        }
        return true;
    }

    /** The parts of an endpoint's identity that its peers' compiled policies depend on. */
    private record PeerView(String helperId, String accountId, String region, String vpcId,
                            String transportAddress, List<String> transportAddresses,
                            SecurityGroupPolicy.Peer peer) {
        static PeerView of(ProtectedEndpoint endpoint) {
            SecurityGroupNftCompiler.Endpoint identity = endpoint.endpoint();
            return new PeerView(endpoint.helperId(), identity.accountId(), identity.region(), identity.vpcId(),
                    identity.transportAddress(), identity.transportAddresses(), identity.peer());
        }
    }

    private record Applied(String helperId, String rules, Map<String, PeerView> known, Set<String> admitted) {}

    private record DnsOrigin(String eniId, String helperId, ClientVpc vpc, List<String> transportAddresses,
                             String nativeNetwork, String transportAddress, List<String> routeAddresses) {}

    /**
     * Every address the endpoint really holds: its Docker addresses, including its VPC address when
     * attached to a VPC network. A private address without a VPC network exists only on paper and
     * stays dialable as whatever host owns it.
     */
    private static List<String> routeAddresses(SecurityGroupNftCompiler.Endpoint endpoint) {
        Set<String> addresses = new LinkedHashSet<>();
        endpoint.transportAddresses().forEach(address -> addresses.add(canonicalAddress(address)));
        endpoint.additionalTransportAddresses().forEach(address -> addresses.add(canonicalAddress(address)));
        return List.copyOf(addresses);
    }

    /** Refreshes the caller's EC2 instances and ECS tasks after a control-plane rule change. */
    public synchronized void refreshPolicies(String accountId, String region, Map<String, SecurityGroup> groups,
                                             Map<String, List<String>> prefixLists) {
        for (Map.Entry<String, ProtectedEndpoint> entry : new ArrayList<>(endpoints.entrySet())) {
            ProtectedEndpoint current = entry.getValue();
            SecurityGroupNftCompiler.Endpoint identity = current.endpoint();
            if (!accountId.equals(identity.accountId()) || !region.equals(identity.region())) {
                continue;
            }
            List<SecurityGroup> attached = identity.groupIds().stream().map(groups::get).toList();
            if (attached.stream().anyMatch(group -> group == null)) {
                endpoints.values().stream()
                        .filter(endpoint -> accountId.equals(endpoint.endpoint().accountId())
                                && region.equals(endpoint.endpoint().region()))
                        .forEach(endpoint -> quarantine(endpoint.helperId()));
                throw new IllegalStateException("A protected endpoint's security group disappeared");
            }
            SecurityGroupNftCompiler.Endpoint updated = new SecurityGroupNftCompiler.Endpoint(
                    identity.accountId(), identity.region(), identity.vpcId(), identity.eniId(),
                    identity.logicalAddress(), identity.transportAddress(), identity.groupIds(), attached,
                    identity.controlConnections(), identity.instanceMetadataAllowed(),
                    identity.additionalTransportAddresses(), identity.controlListeners());
            endpoints.put(entry.getKey(), new ProtectedEndpoint(updated, current.helperId(),
                    Map.copyOf(prefixLists), current.dnsOrigin()));
        }
        retireStoppedHelpers();
        converge(false);
    }

    /**
     * Re-installs every policy whose load-balancer admission changed, on the peer-refresh executor,
     * so the caller never waits on Docker or this monitor.
     */
    public void emulatorOriginsChanged() {
        if (enabled() && emulatorOriginRefreshQueued.compareAndSet(false, true)) {
            try {
                peerRefresh.execute(() -> {
                    // Cleared first: a change after this point queues another pass that sees it.
                    emulatorOriginRefreshQueued.set(false);
                    refreshPeers();
                });
            } catch (RuntimeException e) {
                emulatorOriginRefreshQueued.set(false);
                throw e;
            }
        }
    }

    /**
     * Load balancers whose traffic Floci sends from {@link #emulatorAddresses()}, read once per pass.
     * A source that fails admits nothing rather than quarantining every endpoint.
     */
    private List<SecurityGroupNftCompiler.EmulatorOrigin> emulatorOrigins() {
        if (emulatorOriginSources == null || emulatorOriginSources.isUnsatisfied()) {
            return List.of();
        }
        List<SecurityGroupNftCompiler.EmulatorOrigin> origins = new ArrayList<>();
        emulatorOriginSources.stream().forEach(source -> {
            String key = source.getClass().getName();
            try {
                List<SecurityGroupNftCompiler.EmulatorOrigin> read = List.copyOf(source.emulatorOrigins());
                lastEmulatorOrigins.put(key, read);
                origins.addAll(read);
            } catch (RuntimeException e) {
                // Keep what this source last reported rather than closing or opening every target.
                LOG.warnv(e, "Could not read load-balancer security groups from {0}; keeping its last answer", key);
                origins.addAll(lastEmulatorOrigins.getOrDefault(key, List.of()));
            }
        });
        return origins.stream().distinct().toList();
    }

    /** One of Floci's own container addresses, with its network's prefix length. */
    private record SelfAddress(String address, int prefixLength) {}

    /** Floci's own container addresses, read once per pass; empty outside a container or in unit tests. */
    private List<SelfAddress> selfAddresses() {
        if (currentContainer == null) {
            return List.of();
        }
        try {
            Optional<String> self = currentContainer.resolveContainerId();
            if (self.isEmpty()) {
                return lastSelfAddresses;
            }
            List<SelfAddress> addresses = new ArrayList<>();
            dockerClient.inspectContainerCmd(self.get()).exec().getNetworkSettings().getNetworks().values()
                    .forEach(network -> {
                        if (network.getIpAddress() != null && !network.getIpAddress().isBlank()
                                && network.getIpPrefixLen() != null) {
                            addresses.add(new SelfAddress(network.getIpAddress(), network.getIpPrefixLen()));
                        }
                    });
            lastSelfAddresses = List.copyOf(addresses);
            return lastSelfAddresses;
        } catch (RuntimeException e) {
            // A transient inspect failure must not move admission to an address Floci does not dial from.
            LOG.warnv("Could not inspect Floci's own networks, keeping the last answer: {0}", e.getMessage());
            return lastSelfAddresses;
        }
    }

    /**
     * The addresses Floci's own connections to {@code target} leave from: its address on the Docker
     * network it shares with the target, the target's VPC network first where Floci joined it. Only
     * that network's address is admitted, so traffic Floci sends elsewhere never matches. Floci outside
     * Docker reaches containers from a gateway every host process shares, so it admits nothing.
     */
    private List<String> emulatorAddresses(SecurityGroupNftCompiler.Endpoint target, List<SelfAddress> self) {
        if (containerDetector != null && !containerDetector.isRunningInContainer()) {
            return List.of();
        }
        if (self.isEmpty()) {
            return embeddedDnsServer.getServerIp().map(List::of).orElse(List.of());
        }
        List<String> preferred = isolatedAddressSpaces() ? List.of()
                : List.copyOf(new TreeSet<>(target.additionalTransportAddresses()));
        for (List<String> reached : List.of(preferred, target.transportAddress() == null
                ? List.<String>of() : List.of(target.transportAddress()))) {
            List<String> matched = self.stream()
                    .filter(own -> reached.stream().anyMatch(address ->
                            SecurityGroupPolicy.inCidr(address, own.address() + "/" + own.prefixLength())))
                    .map(SelfAddress::address).sorted().toList();
            if (!matched.isEmpty()) {
                return matched;
            }
        }
        return List.of();
    }

    /**
     * Floci's embedded DNS stands in for the VPC's Amazon DNS server, the one resolver security
     * groups never filter on AWS. The fallback resolvers are ordinary internet destinations.
     */
    List<String> vpcResolvers() {
        return embeddedDnsServer.getServerIp().map(List::of).orElse(List.of());
    }

    private void quarantine(String helperId) {
        applied.values().removeIf(state -> state.helperId().equals(helperId));
        try {
            apply(helperId, "flush chain inet floci_sg ingress\nflush chain inet floci_sg egress\n");
        } catch (Exception e) {
            LOG.errorv(e, "Failed to quarantine security-group helper {0}", helperId);
            try {
                stopNamespaceWorkloads(helperId);
                dockerClient.stopContainerCmd(helperId).withTimeout(0).exec();
            } catch (Exception stopFailure) {
                LOG.errorv(stopFailure, "Failed to stop workloads after quarantine failure for {0}", helperId);
            }
        }
    }

    private void stopNamespaceWorkloads(String helperId) {
        for (Container container : dockerClient.listContainersCmd().exec()) {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(container.getId()).exec();
            if (inspect.getHostConfig() != null && ("container:" + helperId)
                    .equals(inspect.getHostConfig().getNetworkMode())) {
                dockerClient.stopContainerCmd(container.getId()).withTimeout(0).exec();
            }
        }
    }

    private void removeNamespaceWorkloads(String helperId) {
        for (Container container : dockerClient.listContainersCmd().withShowAll(true).exec()) {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(container.getId()).exec();
            if (inspect.getHostConfig() != null && ("container:" + helperId)
                    .equals(inspect.getHostConfig().getNetworkMode())) {
                lifecycleManager.removeIfExists(container.getId());
            }
        }
    }

    private void apply(String helperId, String script) {
        for (int attempt = 0; attempt < 120; attempt++) {
            try {
                byte[] content = script.getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream archive = new ByteArrayOutputStream(content.length + 1024);
                try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
                    TarArchiveEntry entry = new TarArchiveEntry("floci-sg.nft");
                    entry.setSize(content.length);
                    tar.putArchiveEntry(entry);
                    tar.write(content);
                    tar.closeArchiveEntry();
                }
                dockerClient.copyArchiveToContainerCmd(helperId)
                        .withRemotePath("/tmp")
                        .withTarInputStream(new ByteArrayInputStream(archive.toByteArray()))
                        .exec();
                String execId = dockerClient.execCreateCmd(helperId)
                        .withAttachStdout(true).withAttachStderr(true)
                        .withCmd("nft", "-f", "/tmp/floci-sg.nft").exec().getId();
                StringBuilder output = new StringBuilder();
                try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<Frame>() {
                    @Override public void onNext(Frame frame) {
                        output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                }) {
                    boolean completed = dockerClient.execStartCmd(execId)
                            .exec(callback).awaitCompletion(15, TimeUnit.SECONDS);
                    if (!completed) {
                        throw new IllegalStateException("nft command timed out");
                    }
                }
                Long exit = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
                if (exit != null && exit == 0) {
                    return;
                }
                if (output.toString().contains("not found") && attempt < 119) {
                    Thread.sleep(500);
                    continue;
                }
                throw new IllegalStateException("nft rejected security-group policy: " + output);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted installing security-group policy", e);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to install security-group policy", e);
            }
        }
    }

    public record Namespace(String helperId, String transportAddress) {}
    private record ProtectedEndpoint(SecurityGroupNftCompiler.Endpoint endpoint, String helperId,
                                     Map<String, List<String>> prefixLists, DnsOrigin dnsOrigin) {}
}
