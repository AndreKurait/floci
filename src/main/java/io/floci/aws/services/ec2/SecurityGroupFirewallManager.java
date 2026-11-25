package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.LocallyBuiltHelperImage;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.services.ec2.model.SecurityGroup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Owns the network-namespace helpers that EC2 instances and ECS tasks run in: the protected namespace and the
 * Floci-only nftables table for each managed ENI, and the shared namespace of an ECS task that only needs its
 * containers on one {@code localhost}.
 */
@ApplicationScoped
public class SecurityGroupFirewallManager {

    private static final Logger LOG = Logger.getLogger(SecurityGroupFirewallManager.class);
    private static final String PROTECTED_HELPER_PREFIX = "sg";
    private static final String SHARED_HELPER_PREFIX = "netns";
    private final DockerClient dockerClient;
    /** Never reuse a namespace whose remote policy writer has no proven terminal result. */
    private final Set<String> uncertainPolicyWriters = ConcurrentHashMap.newKeySet();
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final EmbeddedDnsServer embeddedDnsServer;
    private final Map<String, ProtectedEndpoint> endpoints = new HashMap<>();
    private final Map<String, Applied> applied = new HashMap<>();

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
        return createNamespace(service, resourceId, accountId, region, dockerNetwork, portBindings, extraLabels,
                hostIp, List.of());
    }

    /**
     * {@link #createNamespace} with {@code linkLocalIps} on the helper's Docker network endpoint. Every workload
     * shares the helper's namespace, so one address gives all of them a connected route into 169.254.0.0/16, which
     * is what an ECS task needs to reach its credential endpoint at 169.254.170.2.
     */
    public Namespace createNamespace(String service, String resourceId, String accountId, String region,
                                     Optional<String> dockerNetwork, Map<Integer, Integer> portBindings,
                                     Map<String, String> extraLabels, String hostIp, List<String> linkLocalIps) {
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
        ContainerBuilder.Builder builder = helperBuilder(PROTECTED_HELPER_PREFIX, service, resourceId, accountId,
                region, dockerNetwork, portBindings, hostIp)
                .withLabels(Map.of(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true",
                        ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)))
                .withLabels(extraLabels);
        linkLocalIps.forEach(builder::withLinkLocalIp);
        return startHelper(builder, resourceId, "protected",
                helperId -> apply(helperId, SecurityGroupNftCompiler.initialRuleset()));
    }

    /**
     * Starts a namespace helper that filters nothing, for containers that only need to share one network
     * namespace, as the containers of an {@code awsvpc} task do on Fargate. It needs neither security-group
     * enforcement nor rootful Docker: the helper holds the namespace, its published ports and
     * {@code linkLocalIps}, and no nftables table.
     */
    public Namespace createSharedNamespace(String service, String resourceId, String accountId, String region,
                                           Optional<String> dockerNetwork, Map<Integer, Integer> portBindings,
                                           Map<String, String> extraLabels, String hostIp, List<String> linkLocalIps) {
        ContainerBuilder.Builder builder = helperBuilder(SHARED_HELPER_PREFIX, service, resourceId, accountId,
                region, dockerNetwork, portBindings, hostIp)
                .withLabels(Map.of(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)))
                .withLabels(extraLabels);
        linkLocalIps.forEach(builder::withLinkLocalIp);
        return startHelper(builder, resourceId, "shared", helperId -> { });
    }

    /**
     * Removes the helper {@link #createNamespace} started for {@code resourceId}, if there is one, and throws when
     * Docker keeps it, since it may still hold host ports and a link-local address. Found by its name, so this also
     * removes a helper whose ENI was never registered, or was already forgotten, which {@link #removeNamespaceStrict}
     * cannot find.
     */
    public void removeProtectedNamespaceStrict(String resourceId) {
        lifecycleManager.removeIfExistsStrict(helperName(PROTECTED_HELPER_PREFIX, resourceId));
    }

    /**
     * Removes the helper {@link #createSharedNamespace} started for {@code resourceId}, if there is one, and
     * throws when Docker keeps it, since it may still hold host ports and a link-local address.
     */
    public void removeSharedNamespaceStrict(String resourceId) {
        lifecycleManager.removeIfExistsStrict(sharedNamespaceName(resourceId));
    }

    /** The container name of the helper {@link #createSharedNamespace} starts for {@code resourceId}. */
    public String sharedNamespaceName(String resourceId) {
        return helperName(SHARED_HELPER_PREFIX, resourceId);
    }

    private String helperName(String prefix, String resourceId) {
        return ContainerStorageHelper.resourceName(config, prefix, null,
                resourceId.replaceAll("[^a-zA-Z0-9_.-]", "-"));
    }

    private ContainerBuilder.Builder helperBuilder(String prefix, String service, String resourceId, String accountId,
                                                   String region, Optional<String> dockerNetwork,
                                                   Map<Integer, Integer> portBindings, String hostIp) {
        ensureHelperImage();
        ContainerBuilder.Builder builder = containerBuilder.newContainer(config.network().securityGroupEnforcement().helperImage())
                .withName(helperName(prefix, resourceId))
                .withDockerNetwork(dockerNetwork)
                // Workloads join this network namespace and use its resolver and hosts file.
                .withEmbeddedDns()
                .withHostDockerInternalOnLinux()
                .withEntrypoint(List.of("sh", "-c"))
                .withCmd(List.of("exec sleep 2147483647"))
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(service, resourceId, accountId, region));
        if (portBindings != null) {
            portBindings.forEach((containerPort, hostPort) -> {
                if (hostIp == null) {
                    builder.withPortBinding(containerPort, hostPort);
                } else {
                    builder.withPortBinding(containerPort, hostPort, hostIp);
                }
            });
        }
        return builder;
    }

    /** Starts the helper, then {@code prepare}s it; a helper that fails either step is removed. */
    private Namespace startHelper(ContainerBuilder.Builder builder, String resourceId, String kind,
                                  Consumer<String> prepare) {
        ContainerSpec spec = builder.build();
        String helperId = null;
        try {
            helperId = lifecycleManager.createAndStart(spec).containerId();
            Map<String, ContainerNetwork> networks = dockerClient.inspectContainerCmd(helperId).exec()
                    .getNetworkSettings().getNetworks();
            String ip = firstAddress(networks, ContainerNetwork::getIpAddress)
                    .orElseThrow(() -> new IllegalStateException("Namespace helper has no Docker IP"));
            prepare.accept(helperId);
            return new Namespace(helperId, ip, firstAddress(networks, ContainerNetwork::getGlobalIPv6Address)
                    .orElse(null));
        } catch (Exception e) {
            if (helperId != null) {
                lifecycleManager.removeIfExists(helperId);
            }
            throw new IllegalStateException("Cannot prepare " + kind + " network namespace for " + resourceId, e);
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
        if (endpoints.values().stream().map(ProtectedEndpoint::endpoint)
                .anyMatch(existing -> !existing.eniId().equals(endpoint.eniId())
                        && existing.transportAddress().equals(endpoint.transportAddress()))) {
            quarantine(helperId);
            throw new IllegalStateException("Two managed ENIs share a Docker transport address");
        }
        ProtectedEndpoint previous = endpoints.put(endpoint.eniId(),
                new ProtectedEndpoint(endpoint, helperId, Map.copyOf(prefixLists)));
        try {
            install(endpoints.get(endpoint.eniId()), peerEndpoints(), vpcResolvers(), false);
            reconcileAll();
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
        ProtectedEndpoint endpoint;
        synchronized (this) {
            endpoint = endpoints.get(eniId);
        }
        if (endpoint != null) {
            lifecycleManager.removeIfExistsStrict(endpoint.helperId());
        }
    }

    public synchronized void unregister(String eniId) {
        ProtectedEndpoint removed = endpoints.remove(eniId);
        if (removed != null) {
            lifecycleManager.removeIfExists(removed.helperId());
            reconcileAll();
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
        SecurityGroupNftCompiler.Endpoint updated = identity.withPolicy(Set.copyOf(groupIds), attached);
        endpoints.put(eniId, new ProtectedEndpoint(updated, current.helperId(), Map.copyOf(prefixLists)));
        reconcileAll();
    }

    /**
     * Gives a protected ENI the IPv6 addresses it holds now, after AssignIpv6Addresses or UnassignIpv6Addresses,
     * so peers' rules naming an IPv6 range match it on those addresses. Does nothing for an ENI not protected.
     */
    public synchronized void updateIpv6Addresses(String eniId, List<String> addresses) {
        ProtectedEndpoint current = endpoints.get(eniId);
        if (current == null) {
            return;
        }
        endpoints.put(eniId, new ProtectedEndpoint(current.endpoint().withIpv6Addresses(addresses),
                current.helperId(), current.prefixLists()));
        try {
            reconcileAll();
        } catch (RuntimeException e) {
            // The ENI keeps the addresses the caller is told it still holds.
            endpoints.computeIfPresent(eniId, (id, updated) -> current);
            throw e;
        }
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
                quarantine(entry.getValue().helperId());
                endpoints.remove(entry.getKey());
            }
        }
        try {
            convergePolicies(true);
        } catch (RuntimeException failure) {
            quarantineAll(endpoints.values().stream().map(ProtectedEndpoint::helperId).toList());
            throw failure;
        }
    }


    private void convergePolicies(boolean force) {
        List<String> vpcResolvers = vpcResolvers();
        for (int round = 0; round < 3; round++) {
            List<SecurityGroupNftCompiler.Endpoint> peers = peerEndpoints();
            List<ProtectedEndpoint> order = new ArrayList<>(endpoints.values());
            order.sort(Comparator.comparing((ProtectedEndpoint target) -> knownByAllPeers(target))
                    .thenComparing(target -> target.endpoint().eniId()));
            // Freeze the partition before any install can teach a peer another identity.
            List<ProtectedEndpoint> unknown = order.stream().filter(target -> !knownByAllPeers(target)).toList();
            List<ProtectedEndpoint> stable = order.stream().filter(this::knownByAllPeers).toList();
            boolean changed = false;
            for (ProtectedEndpoint target : unknown) {
                // Withdraw old accepts before any peer learns a changed identity.
                changed |= install(target, peers, vpcResolvers, force && round == 0);
            }
            List<Installation> plans = new ArrayList<>();
            for (ProtectedEndpoint target : stable) {
                plans.add(plan(target, peers, vpcResolvers, force && round == 0));
            }
            Map<String, List<Installation>> byHelper = new LinkedHashMap<>();
            for (Installation plan : plans) {
                if (plan.changed()) {
                    applied.remove(plan.eniId());
                    byHelper.computeIfAbsent(plan.state().helperId(), ignored -> new ArrayList<>()).add(plan);
                }
            }
            // Only Docker I/O runs concurrently. The monitor keeps identities fixed, and every
            // worker finishes before publication or quarantine can write to any helper again.
            PolicyApplyBatch.run(byHelper.values().stream().<Runnable>map(batch -> () -> {
                for (Installation plan : batch) {
                    apply(plan.state().helperId(), plan.state().rules());
                }
            }).toList());
            for (Installation plan : plans) {
                applied.put(plan.eniId(), plan.state());
                changed |= plan.changed();
            }
            if (!changed && endpoints.values().stream().allMatch(this::complete)) {
                return;
            }
        }
        throw new IllegalStateException("Security-group policies did not converge");
    }

    /** Every registered endpoint in ENI order, so a policy's text depends only on its inputs. */
    private List<SecurityGroupNftCompiler.Endpoint> peerEndpoints() {
        return endpoints.values().stream().map(ProtectedEndpoint::endpoint)
                .sorted(Comparator.comparing(SecurityGroupNftCompiler.Endpoint::eniId)).toList();
    }

    /** Installs {@code target}'s policy toward the peers that enforce its identity; true when Docker ran. */
    private boolean install(ProtectedEndpoint target, List<SecurityGroupNftCompiler.Endpoint> peers,
                            List<String> vpcResolvers, boolean force) {
        Installation plan = plan(target, peers, vpcResolvers, force);
        if (plan.changed()) {
            applied.remove(plan.eniId());
            apply(plan.state().helperId(), plan.state().rules());
        }
        applied.put(plan.eniId(), plan.state());
        return plan.changed();
    }

    private Installation plan(ProtectedEndpoint target, List<SecurityGroupNftCompiler.Endpoint> peers,
                              List<String> vpcResolvers, boolean force) {
        String eniId = target.endpoint().eniId();
        Set<String> admitted = endpoints.values().stream()
                .filter(peer -> peer != target && knows(peer, target))
                .map(peer -> peer.endpoint().eniId())
                .collect(Collectors.toSet());
        String rules = SecurityGroupNftCompiler.compile(target.endpoint(), peers, target.prefixLists(),
                vpcResolvers, peer -> admitted.contains(peer.eniId()));
        Map<String, PeerView> known = endpoints.values().stream()
                .filter(peer -> !peer.endpoint().eniId().equals(eniId))
                .collect(Collectors.toMap(peer -> peer.endpoint().eniId(), PeerView::of));
        Applied current = applied.get(eniId);
        boolean unchanged = current != null && current.helperId().equals(target.helperId())
                && current.rules().equals(rules);
        return new Installation(eniId,
                new Applied(target.helperId(), rules, Map.copyOf(known), Set.copyOf(admitted)), !unchanged || force);
    }

    private record Installation(String eniId, Applied state, boolean changed) {}

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

    private record PeerView(String helperId, String accountId, String region, String vpcId,
                            String logicalAddress, List<String> logicalIpv6Addresses,
                            String transportAddress, String transportIpv6Address, Set<String> groupIds) {
        static PeerView of(ProtectedEndpoint endpoint) {
            SecurityGroupNftCompiler.Endpoint identity = endpoint.endpoint();
            return new PeerView(endpoint.helperId(), identity.accountId(), identity.region(), identity.vpcId(),
                    identity.logicalAddress(), List.copyOf(identity.logicalIpv6Addresses()),
                    identity.transportAddress(), identity.transportIpv6Address(), Set.copyOf(identity.groupIds()));
        }
    }

    private record Applied(String helperId, String rules, Map<String, PeerView> known, Set<String> admitted) {}

    /** Refreshes every affected EC2 instance and ECS task after a control-plane rule change. */
    public synchronized void refreshPolicies(String region, Map<String, SecurityGroup> groups,
                                             Map<String, List<String>> prefixLists) {
        for (Map.Entry<String, ProtectedEndpoint> entry : new ArrayList<>(endpoints.entrySet())) {
            ProtectedEndpoint current = entry.getValue();
            SecurityGroupNftCompiler.Endpoint identity = current.endpoint();
            if (!region.equals(identity.region())) {
                continue;
            }
            List<SecurityGroup> attached = identity.groupIds().stream().map(groups::get).toList();
            if (attached.stream().anyMatch(group -> group == null)) {
                quarantineAll(endpoints.values().stream().map(ProtectedEndpoint::helperId).toList());
                throw new IllegalStateException("A protected endpoint's security group disappeared");
            }
            SecurityGroupNftCompiler.Endpoint updated = identity.withPolicy(identity.groupIds(), attached);
            endpoints.put(entry.getKey(), new ProtectedEndpoint(updated, current.helperId(),
                    Map.copyOf(prefixLists)));
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

    private void quarantineAll(List<String> helperIds) {
        RuntimeException failure = null;
        for (String helperId : helperIds) {
            try {
                quarantine(helperId);
            } catch (RuntimeException stopFailure) {
                if (failure == null) {
                    failure = stopFailure;
                } else {
                    failure.addSuppressed(stopFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void quarantine(String helperId) {
        applied.values().removeIf(state -> state.helperId().equals(helperId));
        try {
            apply(helperId, "flush chain inet floci_sg ingress\nflush chain inet floci_sg egress\n");
        } catch (Exception e) {
            LOG.errorv(e, "Failed to quarantine security-group helper {0}", helperId);
            RuntimeException failure = null;
            try {
                stopNamespaceWorkloads(helperId);
            } catch (RuntimeException stopFailure) {
                failure = stopFailure;
            }
            try {
                stopExactContainer(helperId);
            } catch (RuntimeException stopFailure) {
                if (failure == null) {
                    failure = stopFailure;
                } else {
                    failure.addSuppressed(stopFailure);
                }
            }
            if (failure != null) {
                throw new IllegalStateException("Could not retire security-group namespace " + helperId, failure);
            }
        }
    }

    private void stopNamespaceWorkloads(String helperId) {
        RuntimeException failure = null;
        for (Container container : dockerClient.listContainersCmd().exec()) {
            try {
                InspectContainerResponse inspect = dockerClient.inspectContainerCmd(container.getId()).exec();
                if (inspect.getHostConfig() != null && ("container:" + helperId)
                        .equals(inspect.getHostConfig().getNetworkMode())) {
                    stopExactContainer(container.getId());
                }
            } catch (NotFoundException expected) {
                // A concurrently removed workload no longer retains the namespace.
            } catch (RuntimeException stopFailure) {
                if (failure == null) {
                    failure = stopFailure;
                } else {
                    failure.addSuppressed(stopFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void stopExactContainer(String containerId) {
        try {
            InspectContainerResponse before = dockerClient.inspectContainerCmd(containerId).exec();
            if (!containerId.equals(before.getId())) {
                throw new IllegalStateException("Container identity changed before namespace retirement");
            }
            if (before.getState() != null && Boolean.FALSE.equals(before.getState().getRunning())) {
                return;
            }
            dockerClient.stopContainerCmd(containerId).withTimeout(0).exec();
            InspectContainerResponse after = dockerClient.inspectContainerCmd(containerId).exec();
            if (!containerId.equals(after.getId()) || after.getState() == null
                    || !Boolean.FALSE.equals(after.getState().getRunning())) {
                throw new IllegalStateException("Container retirement is not confirmed: " + containerId);
            }
        } catch (NotFoundException expected) {
            // An absent exact container cannot retain or write this namespace.
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
        if (uncertainPolicyWriters.contains(helperId)) {
            throw new IllegalStateException("Unresolved remote policy writer for " + helperId);
        }
        for (int attempt = 0; attempt < 120; attempt++) {
            try {
                RetryingTarCopier.copyBytes(dockerClient, helperId, "/tmp", "floci-sg.nft",
                        script.getBytes(StandardCharsets.UTF_8), 0644);
                String execId = dockerClient.execCreateCmd(helperId)
                        .withAttachStdout(true).withAttachStderr(true)
                        .withCmd("nft", "-f", "/tmp/floci-sg.nft").exec().getId();
                StringBuilder output = new StringBuilder();
                Exception streamFailure = null;
                try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<Frame>() {
                    @Override public void onNext(Frame frame) {
                        output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                }) {
                    boolean completed = dockerClient.execStartCmd(execId)
                            .exec(callback).awaitCompletion(15, TimeUnit.SECONDS);
                    if (!completed) {
                        streamFailure = new IllegalStateException("nft command timed out");
                    }
                } catch (Exception failure) {
                    streamFailure = failure;
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
                Long exit;
                try {
                    InspectExecResponse result = dockerClient.inspectExecCmd(execId).exec();
                    exit = result.getExitCodeLong();
                    if (!Boolean.FALSE.equals(result.isRunning()) || exit == null) {
                        throw new IllegalStateException("Remote nft command has no terminal result", streamFailure);
                    }
                } catch (RuntimeException unresolved) {
                    uncertainPolicyWriters.add(helperId);
                    throw new IllegalStateException("Unresolved remote policy writer for " + helperId, unresolved);
                }
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

    /** The address on any attached Docker network that {@code pick} gives, the first one set. */
    static Optional<String> firstAddress(Map<String, ContainerNetwork> networks,
                                         Function<ContainerNetwork, String> pick) {
        return networks == null ? Optional.empty() : networks.values().stream().map(pick)
                .filter(address -> address != null && !address.isBlank()).findFirst();
    }

    /** {@code transportIpv6Address} is null when the helper's Docker network has no IPv6. */
    public record Namespace(String helperId, String transportAddress, String transportIpv6Address) {
        public Namespace(String helperId, String transportAddress) {
            this(helperId, transportAddress, null);
        }
    }
    private record ProtectedEndpoint(SecurityGroupNftCompiler.Endpoint endpoint, String helperId,
                                     Map<String, List<String>> prefixLists) {}
}
