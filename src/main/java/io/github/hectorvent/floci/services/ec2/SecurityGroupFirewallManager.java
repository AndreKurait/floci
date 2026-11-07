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
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final Map<String, ProtectedEndpoint> endpoints = new HashMap<>();
    private final Map<String, Set<String>> pendingHelperCleanup = new HashMap<>();
    private final ConcurrentMap<String, DnsOrigin> dnsOrigins = new ConcurrentHashMap<>();
    private boolean peerPoliciesNeedRefresh;
    /** What each admitted helper's nftables table currently enforces, recorded only after it installed. */
    private final Map<String, Applied> applied = new HashMap<>();
    /** Registrations that entered {@link #register} and have not installed their own policy yet. */
    private final AtomicInteger arriving = new AtomicInteger();
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
    }

    /** Observe Docker outside the firewall monitor; never inspect a container on a DNS query. */
    void retireDnsOrigins() {
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
            }
        }
        if (peerPoliciesNeedRefresh) {
            // A failed installation quarantines peers. Retry even when the last DNS origin is gone.
            reconcileAll();
            peerPoliciesNeedRefresh = false;
        }
    }

    private void removeDnsOrigin(ProtectedEndpoint protectedEndpoint) {
        removeDnsOrigin(protectedEndpoint.dnsOrigin());
    }

    private void removeDnsOrigin(DnsOrigin origin) {
        origin.transportAddresses().forEach(transport ->
                dnsOrigins.computeIfPresent(transport,
                        (address, current) -> current == origin ? null : current));
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
            String ip = dockerClient.inspectContainerCmd(helperId).exec().getNetworkSettings()
                    .getNetworks().values().stream().map(n -> n.getIpAddress())
                    .filter(address -> address != null && !address.isBlank())
                    .findFirst().orElseThrow(() -> new IllegalStateException("Firewall helper has no Docker IP"));
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
                        addresses, nativeNetwork));
        ProtectedEndpoint previous = endpoints.put(endpoint.eniId(), registered);
        try {
            admit(endpoint.eniId(), awaitPeers);
            if (vpcRouting != null && !vpcRouting.isUnsatisfied()) {
                vpcRouting.get().register(endpoint, helperId, vpcResolvers());
            }
            if (previous != null) {
                removeDnsOrigin(previous);
            }
            ProtectedEndpoint admitted = endpoints.get(endpoint.eniId());
            if (admitted != null) {
                addresses.forEach(address -> dnsOrigins.put(address, admitted.dnsOrigin()));
                pendingHelperCleanup.values().forEach(helpers -> helpers.remove(helperId));
                pendingHelperCleanup.values().removeIf(Set::isEmpty);
            }
        } catch (RuntimeException e) {
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
        install(target, peerEndpoints(), vpcResolvers(), false);
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
        ProtectedEndpoint endpoint;
        synchronized (this) {
            endpoint = endpoints.get(eniId);
        }
        if (endpoint != null) {
            lifecycleManager.removeIfExistsStrict(endpoint.helperId());
        }
    }

    public synchronized void unregister(String eniId) {
        if (vpcRouting != null && !vpcRouting.isUnsatisfied()) {
            vpcRouting.get().unregister(eniId);
        }
        ProtectedEndpoint removed = endpoints.remove(eniId);
        applied.remove(eniId);
        if (removed != null) {
            removeDnsOrigin(removed);
            pendingHelperCleanup.computeIfAbsent(eniId, ignored -> new HashSet<>()).add(removed.helperId());
            peerPoliciesNeedRefresh = true;
        }
        if (peerPoliciesNeedRefresh) {
            reconcileAll();
            peerPoliciesNeedRefresh = false;
        }
        // Retiring peer permissions must not discard the owning service's later cleanup obligation.
        Set<String> helpers = pendingHelperCleanup.getOrDefault(eniId, Set.of());
        for (String helper : new ArrayList<>(helpers)) {
            lifecycleManager.removeIfExistsStrict(helper);
            helpers.remove(helper);
        }
        pendingHelperCleanup.remove(eniId);
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
                identity.additionalTransportAddresses());
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
        try {
            for (int round = 0; round < 3; round++) {
                List<SecurityGroupNftCompiler.Endpoint> peers = peerEndpoints();
                List<ProtectedEndpoint> order = new ArrayList<>(endpoints.values());
                order.sort(Comparator.comparing((ProtectedEndpoint target) -> knownByAllPeers(target))
                        .thenComparing(target -> target.endpoint().eniId()));
                boolean changed = false;
                for (ProtectedEndpoint target : order) {
                    changed |= install(target, peers, vpcResolvers, force && round == 0);
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
                            List<String> vpcResolvers, boolean force) {
        String eniId = target.endpoint().eniId();
        Set<String> admitted = endpoints.values().stream()
                .filter(peer -> peer != target && knows(peer, target))
                .map(peer -> peer.endpoint().eniId())
                .collect(Collectors.toSet());
        String rules = SecurityGroupNftCompiler.compile(target.endpoint(), peers, target.prefixLists(),
                vpcResolvers, isolatedAddressSpaces(), peer -> admitted.contains(peer.eniId()));
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
                             String nativeNetwork) {}

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
                    identity.additionalTransportAddresses());
            endpoints.put(entry.getKey(), new ProtectedEndpoint(updated, current.helperId(),
                    Map.copyOf(prefixLists), current.dnsOrigin()));
        }
        reconcileAll();
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
