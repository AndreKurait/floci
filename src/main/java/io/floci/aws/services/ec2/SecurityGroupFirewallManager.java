package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.LocallyBuiltHelperImage;
import io.floci.aws.services.ec2.model.SecurityGroup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Owns the protected namespace and the Floci-only nftables table for each managed ENI. */
@ApplicationScoped
public class SecurityGroupFirewallManager {

    private static final Logger LOG = Logger.getLogger(SecurityGroupFirewallManager.class);
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
            String ip = dockerClient.inspectContainerCmd(helperId).exec().getNetworkSettings()
                    .getNetworks().values().stream().map(n -> n.getIpAddress())
                    .filter(address -> address != null && !address.isBlank())
                    .findFirst().orElseThrow(() -> new IllegalStateException("Firewall helper has no Docker IP"));
            apply(helperId, SecurityGroupNftCompiler.initialRuleset());
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
        SecurityGroupNftCompiler.Endpoint updated = new SecurityGroupNftCompiler.Endpoint(
                identity.accountId(), identity.region(), identity.vpcId(), identity.eniId(),
                identity.logicalAddress(), identity.transportAddress(), Set.copyOf(groupIds), attached);
        endpoints.put(eniId, new ProtectedEndpoint(updated, current.helperId(), Map.copyOf(prefixLists)));
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
                            String logicalAddress, String transportAddress, Set<String> groupIds) {
        static PeerView of(ProtectedEndpoint endpoint) {
            SecurityGroupNftCompiler.Endpoint identity = endpoint.endpoint();
            return new PeerView(endpoint.helperId(), identity.accountId(), identity.region(), identity.vpcId(),
                    identity.logicalAddress(), identity.transportAddress(), Set.copyOf(identity.groupIds()));
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
            SecurityGroupNftCompiler.Endpoint updated = new SecurityGroupNftCompiler.Endpoint(
                    identity.accountId(), identity.region(), identity.vpcId(), identity.eniId(),
                    identity.logicalAddress(), identity.transportAddress(), identity.groupIds(), attached);
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

    public record Namespace(String helperId, String transportAddress) {}
    private record ProtectedEndpoint(SecurityGroupNftCompiler.Endpoint endpoint, String helperId,
                                     Map<String, List<String>> prefixLists) {}
}
