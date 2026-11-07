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
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns the protected namespace and the Floci-only nftables table for each managed ENI. */
@ApplicationScoped
public class SecurityGroupFirewallManager implements DnsClientVpcSource {

    private static final Logger LOG = Logger.getLogger(SecurityGroupFirewallManager.class);
    private static final int POLICY_INSTALL_WORKERS = 8;
    private final DockerClient dockerClient;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final EmbeddedDnsServer embeddedDnsServer;
    private final Map<String, ProtectedEndpoint> endpoints = new HashMap<>();
    private final Map<String, Set<String>> pendingHelperCleanup = new HashMap<>();
    private final Set<String> requestedHelperCleanup = new HashSet<>();
    private final Map<String, CompletableFuture<Void>> helperRemovalsInProgress = new HashMap<>();
    private final ConcurrentMap<String, DnsOrigin> dnsOrigins = new ConcurrentHashMap<>();
    private boolean peerPoliciesNeedRefresh;
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

    /** The DNS packet carries the helper's transport address, not the ENI's logical private IP. */
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
        List<String> helperIds = observed.values().stream().map(DnsOrigin::helperId).distinct().toList();
        for (Container container : dockerClient.listContainersCmd().withShowAll(true).withIdFilter(helperIds).exec()) {
            if ("running".equals(container.getState()) && container.getNetworkSettings() != null
                    && container.getNetworkSettings().getNetworks() != null) {
                Set<String> addresses = new HashSet<>();
                container.getNetworkSettings().getNetworks().values().stream()
                        .map(ContainerNetwork::getIpAddress)
                        .filter(address -> address != null && !address.isBlank()).forEach(addresses::add);
                liveAddresses.put(container.getId(), addresses);
            }
        }
        List<DnsOrigin> retired = new ArrayList<>();
        observed.forEach((address, origin) -> {
            if (!liveAddresses.getOrDefault(origin.helperId(), Set.of()).contains(address)) {
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
        retireEndpoints(retired);
    }

    private synchronized void retireEndpoints(List<DnsOrigin> retired) {
        for (DnsOrigin origin : retired) {
            ProtectedEndpoint current = endpoints.get(origin.eniId());
            if (current != null && current.dnsOrigin() == origin) {
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
        if (peerPoliciesNeedRefresh) {
            // A failed installation quarantines peers. Retry even when the last DNS origin is gone.
            reconcileAll();
            peerPoliciesNeedRefresh = false;
        }
    }

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
        SecurityGroupNftCompiler.Endpoint endpoint = protectedEndpoint.endpoint();
        dnsOrigins.computeIfPresent(endpoint.transportAddress(),
                (address, current) -> current == protectedEndpoint.dnsOrigin() ? null : current);
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
                .withHostDockerInternalOnLinux()
                .withEntrypoint(List.of("sh", "-c"))
                .withCmd(List.of("exec sleep 2147483647"))
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(service, resourceId, accountId, region))
                .withLabels(Map.of(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true",
                        ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)))
                .withLabels(extraLabels);
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

    /** Registers only after the default-deny table exists, before workload startup. */
    public synchronized void register(SecurityGroupNftCompiler.Endpoint endpoint, String helperId,
                                      Map<String, List<String>> prefixLists) {
        if (helperRemovalsInProgress.containsKey(helperId)) {
            throw new IllegalStateException("Security-group helper removal is in progress");
        }
        if (endpoints.values().stream().map(ProtectedEndpoint::endpoint)
                .anyMatch(existing -> !existing.eniId().equals(endpoint.eniId())
                        && existing.transportAddress().equals(endpoint.transportAddress()))) {
            quarantine(helperId);
            throw new IllegalStateException("Two managed ENIs share a Docker transport address");
        }
        ProtectedEndpoint previous = endpoints.put(endpoint.eniId(),
                new ProtectedEndpoint(endpoint, helperId, Map.copyOf(prefixLists)));
        try {
            reconcileAll();
            if (previous != null) {
                removeDnsOrigin(previous);
                if (!previous.helperId().equals(helperId) && endpoints.values().stream()
                        .noneMatch(current -> current.helperId().equals(previous.helperId()))) {
                    pendingHelperCleanup.computeIfAbsent(endpoint.eniId(), ignored -> new HashSet<>())
                            .add(previous.helperId());
                    quarantine(previous.helperId());
                }
            }
            ProtectedEndpoint admitted = endpoints.get(endpoint.eniId());
            if (admitted != null) {
                dnsOrigins.put(endpoint.transportAddress(), admitted.dnsOrigin());
                forgetHelperCleanup(helperId);
            }
        } catch (RuntimeException e) {
            if (previous == null) {
                endpoints.remove(endpoint.eniId());
            } else {
                endpoints.put(endpoint.eniId(), previous);
            }
            quarantine(helperId);
            throw e;
        }
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
        captureUnregister(eniId).run();
    }

    /** Captures ownership before asynchronous teardown makes a retained ENI available again. */
    public synchronized Runnable captureUnregister(String eniId) {
        ProtectedEndpoint captured = endpoints.get(eniId);
        Set<String> helpers = new HashSet<>(pendingHelperCleanup.getOrDefault(eniId, Set.of()));
        if (captured != null) {
            helpers.add(captured.helperId());
        }
        return () -> unregisterCaptured(eniId, captured, helpers);
    }

    private void unregisterCaptured(String eniId, ProtectedEndpoint captured, Set<String> capturedHelpers) {
        Set<String> helpers;
        synchronized (this) {
            ProtectedEndpoint current = endpoints.get(eniId);
            if (captured != null && current != null && current.dnsOrigin() == captured.dnsOrigin()) {
                endpoints.remove(eniId);
                removeDnsOrigin(current);
                pendingHelperCleanup.computeIfAbsent(eniId, ignored -> new HashSet<>()).add(current.helperId());
                peerPoliciesNeedRefresh = true;
                quarantine(current.helperId());
            }
            helpers = new HashSet<>(capturedHelpers);
            helpers.removeIf(helper -> endpoints.values().stream()
                    .anyMatch(endpoint -> endpoint.helperId().equals(helper)));
            helpers.retainAll(pendingHelperCleanup.getOrDefault(eniId, Set.of()));
            requestedHelperCleanup.addAll(helpers);
            if (peerPoliciesNeedRefresh) {
                reconcileAll();
                peerPoliciesNeedRefresh = false;
            }
        }
        for (String helper : helpers) {
            removeRequestedHelper(helper);
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
                identity.logicalAddress(), identity.transportAddress(), Set.copyOf(groupIds), attached);
        endpoints.put(eniId, new ProtectedEndpoint(updated, current.helperId(), Map.copyOf(prefixLists),
                current.dnsOrigin()));
        reconcileAll();
    }

    public synchronized void reconcileAll() {
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
        List<SecurityGroupNftCompiler.Endpoint> peers = endpoints.values().stream()
                .map(ProtectedEndpoint::endpoint).toList();
        List<String> vpcResolvers = vpcResolvers();
        try {
            List<PolicyInstallation> installations = new ArrayList<>();
            for (ProtectedEndpoint protectedEndpoint : endpoints.values()) {
                String rules = SecurityGroupNftCompiler.compile(protectedEndpoint.endpoint(), peers,
                        protectedEndpoint.prefixLists(), vpcResolvers);
                installations.add(new PolicyInstallation(protectedEndpoint.helperId(), rules));
            }
            installPolicies(installations);
        } catch (RuntimeException e) {
            endpoints.values().forEach(endpoint -> quarantine(endpoint.helperId()));
            throw e;
        }
    }

    private record PolicyInstallation(String helperId, String rules) {}

    private void installPolicies(List<PolicyInstallation> installations) {
        Map<String, List<PolicyInstallation>> namespaces = new LinkedHashMap<>();
        installations.forEach(policy -> namespaces.computeIfAbsent(policy.helperId(),
                ignored -> new ArrayList<>()).add(policy));
        if (namespaces.size() < 2) {
            installations.forEach(policy -> apply(policy.helperId(), policy.rules()));
            return;
        }
        // The manager's monitor still serializes policy generations. Only Docker I/O on distinct
        // namespaces overlaps. Joining every task before quarantine prevents a late installer from
        // restoring permissive rules after another namespace's installation failed.
        try (ExecutorService workers = Executors.newFixedThreadPool(
                Math.min(POLICY_INSTALL_WORKERS, namespaces.size()),
                Thread.ofVirtual().name("security-group-policy-", 0).factory())) {
            CompletableFuture<?>[] pending = namespaces.values().stream()
                    .map(policies -> CompletableFuture.runAsync(
                            () -> policies.forEach(policy -> apply(policy.helperId(), policy.rules())), workers))
                    .toArray(CompletableFuture<?>[]::new);
            CompletableFuture.allOf(pending).join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("Failed to install security-group policies", failure.getCause());
        }
    }

    private record DnsOrigin(String eniId, String helperId, ClientVpc vpc) {}

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
                    identity.logicalAddress(), identity.transportAddress(), identity.groupIds(), attached);
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
                                     Map<String, List<String>> prefixLists, DnsOrigin dnsOrigin) {
        private ProtectedEndpoint(SecurityGroupNftCompiler.Endpoint endpoint, String helperId,
                                  Map<String, List<String>> prefixLists) {
            this(endpoint, helperId, prefixLists, new DnsOrigin(endpoint.eniId(), helperId,
                    new ClientVpc(endpoint.accountId(), endpoint.region(), endpoint.vpcId())));
        }
    }
}
