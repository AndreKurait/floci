package io.floci.aws.services.elb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.CertificateReferenceResolver;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.NetworkNamespaceLease;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.SecurityGroupNftCompiler;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.PrefixListEntry;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import io.floci.aws.services.elb.model.ClassicLoadBalancer;
import io.floci.aws.services.elb.model.ClassicLoadBalancerAttributes;
import io.floci.aws.services.iam.IamService;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Owns a protected interface, firewall namespace and listener in each selected Classic subnet. */
@ApplicationScoped
public class ElbClassicFrontendManager implements ContainerTeardown {
    private static final Logger LOG = Logger.getLogger(ElbClassicFrontendManager.class);
    private static final String SERVICE = "elb-classic";
    private static final String ENI_LABEL = "io.floci.elb-classic.network-interface";

    private final EmulatorConfig config;
    private final Ec2Service ec2;
    private final SecurityGroupFirewallManager firewall;
    private final VpcNetworkManager vpcNetworks;
    private final VpcRoutingManager routing;
    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final DockerClient docker;
    private final ObjectMapper mapper;
    private final CertificateReferenceResolver certificates;
    private final Map<String, Frontends> frontends = new ConcurrentHashMap<>();
    private final Map<String, PendingCleanup> pendingCleanups = new ConcurrentHashMap<>();
    private final AccountAwareStorageBackend<Ownership> ownership;
    private final Map<String, Ownership> pendingAllocations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService drainWorker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("floci-classic-drain").factory());
    private boolean recovered;

    public ElbClassicFrontendManager(EmulatorConfig config, Ec2Service ec2,
                                    SecurityGroupFirewallManager firewall, VpcNetworkManager vpcNetworks,
                                    ContainerBuilder containers, ContainerLifecycleManager lifecycle,
                                    ObjectMapper mapper, IamService iam, AcmService acm, VpcRoutingManager routing,
                                    StorageFactory storageFactory) {
        this.config = config;
        this.ownership = storageFactory.create("elb", "elb-classic-frontends.json", new TypeReference<>() {});
        this.ec2 = ec2;
        this.firewall = firewall;
        this.vpcNetworks = vpcNetworks;
        this.routing = routing;
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.docker = lifecycle.getDockerClient();
        this.mapper = mapper;
        this.certificates = new CertificateReferenceResolver(iam, acm);
    }

    @PostConstruct
    void initialize() {
        if (enabled()) {
            recoverOrphans();
        }
    }

    boolean enabled() {
        return config.services().elb().vpcFrontends();
    }

    ClassicLoadBalancer copy(ClassicLoadBalancer source) {
        return mapper.convertValue(source, ClassicLoadBalancer.class);
    }

    /** The service serializes this with other writes to this account/Region/name. */
    boolean reconcile(ClassicLoadBalancer loadBalancer) {
        if (!enabled()) {
            return false;
        }
        validate(loadBalancer);
        recoverOrphans();
        return RequestScopes.callAs(account(loadBalancer), loadBalancer.getRegion(), () -> {
            String key = key(loadBalancer);
            retryPendingCleanups(key);
            retryPendingAllocations(key);
            String state = state(loadBalancer);
            AtomicBoolean changed = new AtomicBoolean();
            frontends.compute(key, (ignored, previous) -> {
                if (previous != null && state.equals(previous.state()) && previous.retired().isEmpty()
                        && previous.nodes().stream().allMatch(this::running)) {
                    for (Frontend frontend : previous.nodes()) {
                        if (frontend.backendRuntime() != null) {
                            NetworkInterface eni = ec2.describeNetworkInterfaces(loadBalancer.getRegion(),
                                    List.of(frontend.eni()), Map.of()).networkInterfaces().getFirst();
                            try {
                                changed.set(frontend.backendRuntime().reconcile(
                                        backendAddresses(loadBalancer, eni), loadBalancer.getAttributes())
                                        || changed.get());
                            } catch (RuntimeException failure) {
                                quarantine(frontend, failure);
                                throw failure;
                            }
                        }
                    }
                    scheduleDrainRefresh(key, previous);
                    return previous;
                }
                Frontends next = create(loadBalancer, state);
                try {
                    if (previous != null) {
                        releaseNodes(key, previous, false);
                    }
                } catch (RuntimeException failure) {
                    try {
                        releaseOrRetain(key, next);
                    } catch (RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
                changed.set(true);
                return next;
            });
            return changed.get();
        });
    }

    private void validate(ClassicLoadBalancer loadBalancer) {
        if (config.services().elb().mock() || !firewall.enabled() || !vpcNetworks.enabled()
                || !config.services().ec2().awsFaithfulPrivateIp()) {
            throw new AwsException("UnsupportedOperation",
                    "Protected Classic frontends require real ELB, VPC networks, native private IPs "
                            + "and security-group enforcement.", 400);
        }
        if (loadBalancer.getSubnets().isEmpty()
                || !"internal".equals(loadBalancer.getScheme())
                && !("internet-facing".equals(loadBalancer.getScheme()) && routing.enabled())) {
            throw new AwsException("UnsupportedOperation",
                    "Protected Classic frontends require IPv4 subnets; public frontends also need VPC routing.", 400);
        }
        if (loadBalancer.getSecurityGroups().isEmpty()) {
            throw new AwsException("InvalidConfigurationRequest", "A protected frontend needs security groups.", 400);
        }
        if (loadBalancer.getAttributes().isAccessLogEnabled()) {
            throw new AwsException("UnsupportedOperation",
                    "Protected Classic frontend access logging is not implemented.", 400);
        }
    }

    private String state(ClassicLoadBalancer loadBalancer) {
        try {
            ClassicLoadBalancerAttributes attributes = mapper.convertValue(
                    loadBalancer.getAttributes(), ClassicLoadBalancerAttributes.class);
            attributes.setConnectionDrainingEnabled(false);
            attributes.setConnectionDrainingTimeout(300);
            return mapper.writeValueAsString(List.of(loadBalancer.getSubnets(), loadBalancer.getSecurityGroups(),
                    loadBalancer.getListeners(), ClassicSslPolicies.listenerSettings(loadBalancer),
                    loadBalancer.getHealthCheck(), attributes,
                    loadBalancer.getListeners().isEmpty() ? loadBalancer.getInstanceIds() : List.of()));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot snapshot Classic frontend settings", failure);
        }
    }

    private Frontends create(ClassicLoadBalancer loadBalancer, String state) {
        List<Frontend> nodes = new ArrayList<>();
        try {
            for (String subnet : loadBalancer.getSubnets()) {
                nodes.add(create(loadBalancer, state, subnet));
            }
            return new Frontends(state, nodes);
        } catch (RuntimeException failure) {
            try {
                releaseOrRetain(key(loadBalancer), new Frontends(state, nodes));
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private Frontend create(ClassicLoadBalancer loadBalancer, String state, String subnetId) {
        String account = account(loadBalancer);
        String region = loadBalancer.getRegion();
        String resource = loadBalancer.getLoadBalancerName() + "-" + UUID.randomUUID();
        Ownership allocation = new Ownership(ContainerStorageHelper.ownerIdentity(config), account, region,
                key(loadBalancer), resource, null);
        saveOwnership(allocation);
        Frontend pending = null;
        try {
            NetworkInterface eni = ec2.createServiceNetworkInterface(region, subnetId,
                    "Classic ELB " + loadBalancer.getLoadBalancerName(), loadBalancer.getSecurityGroups(), resource);
            pending = new Frontend(account, region, state, eni.getNetworkInterfaceId(),
                    eni.getPrivateIpAddress(), null, null, Map.of(), Map.of(), resource);
            saveOwnership(new Ownership(allocation.owner(), account, region, allocation.key(), resource,
                    eni.getNetworkInterfaceId()));
            ec2.holdNetworkInterfaceForService(region, eni.getNetworkInterfaceId());
            SecurityGroupFirewallManager.Namespace namespace = firewall.createNamespace(SERVICE, resource,
                    account, region, Optional.empty(), Map.of(), Map.of(ENI_LABEL, eni.getNetworkInterfaceId()));
            pending = pending.withNamespace(new NetworkNamespaceLease(namespace.helperId(), null));
            pending = pending.withNamespace(new NetworkNamespaceLease(namespace.helperId(),
                    docker.inspectContainerCmd(namespace.helperId()).exec().getName()));
            if (vpcNetworks.attach(account, region, eni.getVpcId(), eni.getSubnetId(),
                    namespace.helperId(), eni.getPrivateIpAddress()).isEmpty()) {
                throw new IllegalStateException("Classic frontend could not attach to its VPC");
            }
            if ("internet-facing".equals(loadBalancer.getScheme())) {
                routing.attachPublicFrontend(account, region, eni, namespace.helperId());
            }
            List<SecurityGroup> groups = ec2.describeSecurityGroups(region,
                    loadBalancer.getSecurityGroups(), List.of(), Map.of());
            if (!Set.copyOf(loadBalancer.getSecurityGroups()).equals(groups.stream()
                    .map(SecurityGroup::getGroupId).collect(Collectors.toSet()))) {
                throw new IllegalStateException("A Classic frontend's security group disappeared");
            }
            Map<String, List<String>> prefixLists = new LinkedHashMap<>();
            groups.stream().flatMap(group -> Stream.concat(
                            group.getIpPermissions().stream(), group.getIpPermissionsEgress().stream()))
                    .flatMap(permission -> permission.getPrefixListIds().stream())
                    .map(reference -> reference.getPrefixListId()).distinct()
                    .forEach(id -> prefixLists.put(id, ec2.getManagedPrefixListEntries(region, id, null)
                            .stream().map(PrefixListEntry::getCidr).toList()));
            firewall.register(new SecurityGroupNftCompiler.Endpoint(account, region, eni.getVpcId(),
                    eni.getNetworkInterfaceId(), eni.getPrivateIpAddress(), namespace.transportAddress(),
                    Set.copyOf(loadBalancer.getSecurityGroups()), groups, List.of(), false,
                    Set.of(eni.getPrivateIpAddress())), namespace.helperId(), prefixLists);
            Map<String, String> addresses = backendAddresses(loadBalancer, eni);
            List<ElbClassicProxyConfig.Backend> backends = addresses.entrySet().stream()
                    .map(entry -> new ElbClassicProxyConfig.Backend(entry.getKey(), entry.getValue())).toList();
            String owner = AwsArnUtils.Arn.of("elasticloadbalancing", region, account,
                    "loadbalancer/" + loadBalancer.getLoadBalancerName()).toString();
            ElbClassicProxyConfig.Rendered rendered = ElbClassicProxyConfig.render(loadBalancer,
                    eni.getPrivateIpAddress(), backends,
                    reference -> certificates.publishedMaterial(owner, region, reference));
            if (loadBalancer.getListeners().isEmpty()) {
                return pending.withConfiguration(rendered.servers(), addresses);
            }
            ContainerSpec spec = containers.newContainer(config.services().elb().proxyImage())
                    .withName(ContainerStorageHelper.resourceName(config, SERVICE, null, resource))
                    .withNetworkMode("container:" + namespace.helperId()).withUser("0")
                    .withEntrypoint(List.of("haproxy"))
                    .withCmd(List.of("-W", "-db", "-f", ElbClassicProxyConfig.DIRECTORY + "/haproxy.cfg"))
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels(SERVICE, resource, account, region))
                    .withLabels(Map.of(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                            ContainerStorageHelper.SECURITY_GROUP_WORKLOAD_LABEL, "true",
                            ENI_LABEL, eni.getNetworkInterfaceId()))
                    .build();
            String proxy = lifecycle.create(spec);
            pending = pending.withProxy(proxy);
            rendered.files().forEach((name, content) -> RetryingTarCopier.copyBytes(docker, proxy,
                    ElbClassicProxyConfig.DIRECTORY, name, content, 0600));
            lifecycle.startCreated(proxy, spec);
            awaitReady(pending);
            Frontend configured = pending.withConfiguration(rendered.servers(), addresses);
            ElbClassicBackendRuntime runtime = new ElbClassicBackendRuntime(
                    loadBalancer.getListeners(), rendered.servers(), addresses,
                    commands -> command(configured, commands), System::nanoTime);
            runtime.reconcile(addresses, loadBalancer.getAttributes());
            return configured.withRuntime(runtime);
        } catch (RuntimeException failure) {
            try {
                if (pending == null) {
                    pendingAllocations.put(resource, allocation);
                    recoverAllocation(allocation);
                    pendingAllocations.remove(resource);
                } else {
                    releaseOrRetain(key(loadBalancer), pending);
                }
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    static boolean eligibleZone(ClassicLoadBalancer loadBalancer, String nodeZone, Instance instance) {
        String instanceZone = instance.getPlacement() == null ? null : instance.getPlacement().getAvailabilityZone();
        return instanceZone != null && loadBalancer.getAvailabilityZones().contains(instanceZone)
                && (loadBalancer.getAttributes().isCrossZoneLoadBalancingEnabled() || nodeZone.equals(instanceZone));
    }

    private Map<String, String> backendAddresses(ClassicLoadBalancer loadBalancer, NetworkInterface eni) {
        Map<String, String> addresses = new LinkedHashMap<>();
        if (loadBalancer.getInstanceIds().isEmpty()) {
            return addresses;
        }
        Subnet subnet = ec2.describeSubnets(loadBalancer.getRegion(), List.of(eni.getSubnetId()), Map.of()).getFirst();
        for (String id : loadBalancer.getInstanceIds()) {
            Instance instance = ec2.getInstance(loadBalancer.getRegion(), id).orElse(null);
            if (instance != null && eni.getVpcId().equals(instance.getVpcId())
                    && instance.getPrivateIpAddress() != null
                    && eligibleZone(loadBalancer, subnet.getAvailabilityZone(), instance)) {
                addresses.put(id, instance.getPrivateIpAddress());
            }
        }
        return addresses;
    }

    Set<String> drainingInstances(ClassicLoadBalancer loadBalancer) {
        Frontends current = frontends.get(key(loadBalancer));
        return current == null ? Set.of() : current.nodes().stream()
                .filter(frontend -> !current.retired().contains(frontend.eni()) && frontend.backendRuntime() != null)
                .flatMap(frontend -> frontend.backendRuntime().draining().stream()).collect(Collectors.toSet());
    }

    private void scheduleDrainRefresh(String key, Frontends current) {
        if (!current.pollScheduled && current.nodes().stream().anyMatch(frontend ->
                !current.retired().contains(frontend.eni())
                && frontend.backendRuntime() != null && frontend.backendRuntime().hasPendingWork())) {
            current.pollScheduled = true;
            drainWorker.schedule(() -> refreshDrains(key, current), 100, TimeUnit.MILLISECONDS);
        }
    }

    private void refreshDrains(String key, Frontends expected) {
        frontends.computeIfPresent(key, (ignored, current) -> {
            if (current != expected) {
                return current;
            }
            current.pollScheduled = false;
            RuntimeException firstFailure = null;
            for (Frontend frontend : current.nodes()) {
                if (!current.retired().contains(frontend.eni()) && frontend.backendRuntime() != null) {
                    try {
                        frontend.backendRuntime().refresh();
                    } catch (RuntimeException failure) {
                        if (firstFailure == null) {
                            firstFailure = failure;
                        } else if (firstFailure != failure) {
                            firstFailure.addSuppressed(failure);
                        }
                    }
                }
            }
            if (firstFailure != null && !current.refreshFailed) {
                LOG.warnv(firstFailure,
                        "Classic frontend {0} could not complete backend draining; retaining it for retry", key);
            }
            current.refreshFailed = firstFailure != null;
            scheduleDrainRefresh(key, current);
            return current;
        });
    }

    List<String> addresses(ClassicLoadBalancer loadBalancer) {
        Frontends current = frontends.get(key(loadBalancer));
        return current == null ? List.of() : current.nodes().stream()
                .filter(frontend -> !current.retired().contains(frontend.eni()))
                .map(frontend -> routing.publicAddress(
                frontend.account(), frontend.region(), frontend.eni()).orElse(frontend.address())).toList();
    }

    Optional<String> address(ClassicLoadBalancer loadBalancer) {
        return addresses(loadBalancer).stream().findFirst();
    }

    record Probe(Frontend frontend, String instanceId, String address, long generation) {
        Probe(Frontend frontend, String instanceId, String address) {
            this(frontend, instanceId, address, 0);
        }
    }

    List<Probe> probesFor(ClassicLoadBalancer loadBalancer, String instanceId) {
        Frontends current = frontends.get(key(loadBalancer));
        return current == null ? List.of() : current.nodes().stream()
                .filter(frontend -> !current.retired().contains(frontend.eni())
                        && frontend.backends().containsKey(instanceId))
                .map(frontend -> new Probe(frontend, instanceId, frontend.backends().get(instanceId),
                        frontend.backendRuntime() == null ? 0 : frontend.backendRuntime().generation(instanceId)))
                .toList();
    }

    Probe probeFor(ClassicLoadBalancer loadBalancer, String instanceId) {
        return probesFor(loadBalancer, instanceId).stream().findFirst().orElse(null);
    }

    boolean current(ClassicLoadBalancer loadBalancer, Probe probe) {
        Frontends current = frontends.get(key(loadBalancer));
        return probe != null && probe.address() != null && current != null
                && !current.retired().contains(probe.frontend().eni())
                && current.nodes().stream().anyMatch(frontend -> frontend == probe.frontend()
                && probe.address().equals(frontend.backends().get(probe.instanceId()))
                && (frontend.backendRuntime() == null
                    || probe.generation() == frontend.backendRuntime().generation(probe.instanceId())));
    }

    boolean probe(Probe probe, ElbClassicHealthChecker.Target target, int timeoutSeconds) {
        if (probe == null || probe.address() == null) {
            return false;
        }
        Frontend frontend = probe.frontend();
        Instance instance = ec2.findInstanceForAccount(frontend.account(), frontend.region(), probe.instanceId())
                .orElse(null);
        if (instance == null || instance.getState() == null || !"running".equals(instance.getState().getName())
                || !probe.address().equals(instance.getPrivateIpAddress())) {
            LOG.debugv("Classic node {0} cannot probe instance {1}: registered address {2}, state {3}, current address {4}",
                    frontend.eni(), probe.instanceId(), probe.address(),
                    instance == null ? null : instance.getState(),
                    instance == null ? null : instance.getPrivateIpAddress());
            return false;
        }
        String address = ElbClassicProxyConfig.ipv4(probe.address());
        String seconds = Integer.toString(timeoutSeconds);
        String[] command = target.path() != null
                ? new String[]{"curl", "--silent", "--show-error", "--insecure", "--max-time", seconds,
                    "--output", "/dev/null", "--write-out", "%{http_code}",
                    target.scheme() + "://" + address + ":" + target.port() + target.path()}
                : new String[]{"timeout", "-s", "KILL", seconds, "socat", "-u", "/dev/null",
                    ("ssl".equals(target.scheme()) ? "OPENSSL:" : "TCP:") + address + ":" + target.port()
                            + ("ssl".equals(target.scheme()) ? ",verify=0" : "") + ",connect-timeout=" + seconds};
        ContainerExec.Result result = ContainerExec.run(docker, frontend.helper(), command, timeoutSeconds + 2)
                .throwIfTimedOut(frontend.helper());
        boolean healthy = result.exitCode() == 0 && (target.path() == null || "200".equals(result.stdout().trim()));
        if (!healthy) {
            LOG.debugv("Classic node {0} health check for instance {1} at {2} returned {3}",
                    frontend.eni(), probe.instanceId(), address, result);
        }
        return healthy;
    }

    void healthChanged(ClassicLoadBalancer loadBalancer, Probe probe,
                       ElbClassicHealthChecker.InstanceHealth health) {
        if (probe == null) {
            return;
        }
        frontends.computeIfPresent(key(loadBalancer), (ignored, current) -> {
            Frontend frontend = probe.frontend();
            if (current(loadBalancer, probe) && frontend.proxy() != null) {
                publishHealth(frontend, probe.instanceId(), health);
            }
            return current;
        });
    }

    private void publishHealth(Frontend frontend, String instanceId, ElbClassicHealthChecker.InstanceHealth health) {
        try {
            if (frontend.backendRuntime() != null) {
                frontend.backendRuntime().healthChanged(instanceId, "InService".equals(health.state()));
                return;
            }
            String state = "InService".equals(health.state()) ? "ready" : "maint";
            String commands = frontend.servers().getOrDefault(instanceId, List.of()).stream()
                    .map(server -> "set server " + server + " state " + state + "\n").collect(Collectors.joining());
            if (!commands.isEmpty()) {
                String response = command(frontend, commands);
                if (!response.isBlank()) {
                    throw new IllegalStateException("Classic frontend rejected its health update: " + response);
                }
            }
        } catch (RuntimeException failure) {
            quarantine(frontend, failure);
            throw failure;
        }
    }

    private void quarantine(Frontend frontend, RuntimeException failure) {
        try {
            lifecycle.stopAndRemoveStrict(frontend.proxy(), null, 1);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    void remove(ClassicLoadBalancer loadBalancer) {
        remove(key(loadBalancer));
    }

    private void remove(String key) {
        retryPendingCleanups(key);
        retryPendingAllocations(key);
        frontends.computeIfPresent(key, (ignored, frontend) -> {
            releaseNodes(key, frontend, false);
            return null;
        });
    }

    private void releaseOrRetain(String key, Frontends frontends) {
        releaseNodes(key, frontends, true);
    }

    private void releaseNodes(String key, Frontends frontends, boolean retainUnpublished) {
        RuntimeException firstFailure = null;
        for (Frontend frontend : frontends.nodes()) {
            if (frontends.retired().contains(frontend.eni())) {
                continue;
            }
            try {
                RequestScopes.callAs(frontend.account(), frontend.region(), () -> {
                    if (retainUnpublished) {
                        releaseOrRetain(key, frontend);
                    } else {
                        release(frontend);
                    }
                    return null;
                });
                frontends.retired().add(frontend.eni());
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else if (firstFailure != failure) {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void releaseOrRetain(String key, Frontend frontend) {
        try {
            release(frontend);
        } catch (RuntimeException failure) {
            pendingCleanups.put(frontend.eni(), new PendingCleanup(key, frontend));
            throw failure;
        }
    }

    private void retryPendingCleanups(String key) {
        RuntimeException firstFailure = null;
        for (String eni : pendingCleanups.keySet()) {
            try {
                pendingCleanups.computeIfPresent(eni, (ignored, pending) -> {
                    if (!pending.key().equals(key)) {
                        return pending;
                    }
                    Frontend frontend = pending.frontend();
                    return RequestScopes.callAs(frontend.account(), frontend.region(), () -> {
                        release(frontend);
                        return null;
                    });
                });
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else if (firstFailure != failure) {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private record PendingCleanup(String key, Frontend frontend) {}

    private void release(Frontend frontend) {
        if (frontend.proxy() != null) {
            lifecycle.stopAndRemoveStrict(frontend.proxy(), null, 1);
        }
        if (frontend.helper() != null) {
            frontend.namespace().requireUnused(docker);
            firewall.unregister(frontend.eni());
            routing.unregister(frontend.account(), frontend.region(), frontend.eni());
            lifecycle.removeIfExistsStrict(frontend.helper());
        }
        ec2.releaseNetworkInterfaceFromService(frontend.region(), frontend.eni());
        try {
            ec2.deleteNetworkInterface(frontend.region(), frontend.eni());
        } catch (AwsException failure) {
            if (!"InvalidNetworkInterfaceID.NotFound".equals(failure.getErrorCode())) {
                throw failure;
            }
        }
        if (frontend.ownershipId() != null) {
            forgetOwnership(frontend.account(), frontend.ownershipId());
        }
    }

    private void saveOwnership(Ownership allocation) {
        ownership.putAllAtomicallyForAccount(allocation.account(), Map.of(allocation.id(), allocation));
    }

    private void forgetOwnership(String account, String id) {
        ownership.deleteAllAtomicallyForAccounts(Map.of(account, Set.of(id)));
    }

    private void recoverAllocation(Ownership allocation) {
        RequestScopes.callAs(allocation.account(), allocation.region(), () -> {
            List<String> interfaces = allocation.eni() == null
                    ? ec2.findServiceNetworkInterfaces(allocation.region(), allocation.id()).stream()
                            .map(NetworkInterface::getNetworkInterfaceId).toList()
                    : List.of(allocation.eni());
            for (String eni : interfaces) {
                firewall.unregister(eni);
                routing.unregister(allocation.account(), allocation.region(), eni);
                release(new Frontend(allocation.account(), allocation.region(), "", eni, null,
                        null, null, Map.of(), Map.of()));
            }
            forgetOwnership(allocation.account(), allocation.id());
            return null;
        });
    }

    private void retryPendingAllocations(String key) {
        for (Ownership allocation : pendingAllocations.values()) {
            if (allocation.key().equals(key)) {
                recoverAllocation(allocation);
                pendingAllocations.remove(allocation.id(), allocation);
            }
        }
    }

    @RegisterForReflection
    public record Ownership(String owner, String account, String region, String key, String id, String eni) {}

    private boolean running(Frontend frontend) {
        try {
            return Boolean.TRUE.equals(docker.inspectContainerCmd(frontend.helper()).exec().getState().getRunning())
                    && (frontend.proxy() == null || Boolean.TRUE.equals(
                    docker.inspectContainerCmd(frontend.proxy()).exec().getState().getRunning()));
        } catch (NotFoundException ignored) {
            // A removed owned container requires reconstruction before an update can succeed.
            return false;
        }
    }

    private void awaitReady(Frontend frontend) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline && running(frontend)) {
            try {
                if (command(frontend, "show info\n").contains("Version:")) {
                    return;
                }
            } catch (RuntimeException failure) {
                last = failure;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for the Classic frontend", failure);
            }
        }
        throw new IllegalStateException("Classic listener process did not become ready", last);
    }

    private String command(Frontend frontend, String commands) {
        ContainerExec.Result result = ContainerExec.run(docker, frontend.helper(), new String[]{
                "sh", "-c", "printf '%s' \"$1\" | socat -t 1 - ABSTRACT-CONNECT:floci-classic",
                "floci-classic-control", commands}, 3).throwIfTimedOut(frontend.helper());
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Classic frontend control failed: " + result.summary());
        }
        return result.stdout();
    }

    private synchronized void recoverOrphans() {
        if (recovered) {
            return;
        }
        Map<String, String> selection = Map.of(ContainerStorageHelper.OWNER_LABEL,
                ContainerStorageHelper.ownerIdentity(config), ContainerStorageHelper.SERVICE_LABEL, SERVICE);
        List<Container> owned = docker.listContainersCmd().withShowAll(true).withLabelFilter(selection).exec();
        for (Container container : owned) {
            if ("true".equals(container.getLabels().get(ContainerStorageHelper.SECURITY_GROUP_WORKLOAD_LABEL))) {
                lifecycle.stopAndRemoveStrict(container.getId(), null, 1);
            }
        }
        for (Container container : owned) {
            Map<String, String> labels = container.getLabels();
            if (!"true".equals(labels.get(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL))) {
                continue;
            }
            String account = labels.get(ContainerStorageHelper.ACCOUNT_LABEL);
            String region = labels.get(ContainerStorageHelper.REGION_LABEL);
            String eni = labels.get(ENI_LABEL);
            if (account == null || region == null || eni == null) {
                throw new IllegalStateException("Owned Classic helper lacks account, Region or interface identity");
            }
            RequestScopes.callAs(account, region, () -> {
                release(new Frontend(account, region, "", eni, null,
                        new NetworkNamespaceLease(container.getId(), container.getNames()[0]), null, Map.of(), Map.of()));
                return null;
            });
        }
        for (Ownership allocation : ownership.scanAllAccounts()) {
            if (ContainerStorageHelper.ownerIdentity(config).equals(allocation.owner())) {
                recoverAllocation(allocation);
            }
        }
        recovered = true;
    }

    @PreDestroy
    void close() {
        drainWorker.shutdownNow();
        stopManagedContainers();
    }

    @Override
    public void stopManagedContainers() {
        Set<String> keys = Stream.concat(frontends.keySet().stream(),
                pendingCleanups.values().stream().map(PendingCleanup::key)).collect(Collectors.toSet());
        keys.addAll(pendingAllocations.values().stream().map(Ownership::key).toList());
        for (String key : keys) {
            try {
                remove(key);
            } catch (RuntimeException failure) {
                LOG.errorv(failure, "Could not retire Classic frontend {0}; its interface remains reserved", key);
            }
        }
    }

    private String account(ClassicLoadBalancer loadBalancer) {
        return loadBalancer.getAccountId() == null ? config.defaultAccountId() : loadBalancer.getAccountId();
    }

    private String key(ClassicLoadBalancer loadBalancer) {
        return account(loadBalancer) + "/" + loadBalancer.getRegion() + "/" + loadBalancer.getLoadBalancerName();
    }

    static final class Frontends {
        private final String state;
        private final List<Frontend> nodes;
        private final Set<String> retired = ConcurrentHashMap.newKeySet();
        private boolean pollScheduled;
        private boolean refreshFailed;

        Frontends(String state, List<Frontend> nodes) {
            this.state = state;
            this.nodes = List.copyOf(nodes);
        }

        Set<String> retired() {
            return retired;
        }

        String state() {
            return state;
        }

        List<Frontend> nodes() {
            return nodes;
        }
    }

    record Frontend(String account, String region, String state, String eni, String address,
                    NetworkNamespaceLease namespace, String proxy,
                    Map<String, List<String>> servers, Map<String, String> backends, String ownershipId,
                    ElbClassicBackendRuntime backendRuntime) {
        Frontend(String account, String region, String state, String eni, String address,
                 NetworkNamespaceLease namespace, String proxy,
                 Map<String, List<String>> servers, Map<String, String> backends) {
            this(account, region, state, eni, address, namespace, proxy, servers, backends, null);
        }

        Frontend(String account, String region, String state, String eni, String address,
                 NetworkNamespaceLease namespace, String proxy, Map<String, List<String>> servers,
                 Map<String, String> backends, String ownershipId) {
            this(account, region, state, eni, address, namespace, proxy, servers, backends, ownershipId, null);
        }

        public Map<String, List<String>> servers() {
            return backendRuntime == null ? servers : backendRuntime.servers();
        }

        public Map<String, String> backends() {
            return backendRuntime == null ? backends : backendRuntime.addresses();
        }

        String helper() {
            return namespace == null ? null : namespace.id();
        }

        Frontend withNamespace(NetworkNamespaceLease value) {
            return new Frontend(account, region, state, eni, address, value, proxy, servers, backends,
                    ownershipId, backendRuntime);
        }

        Frontend withProxy(String proxyId) {
            return new Frontend(account, region, state, eni, address, namespace, proxyId, servers, backends,
                    ownershipId, backendRuntime);
        }

        Frontend withConfiguration(Map<String, List<String>> servers, Map<String, String> backends) {
            return new Frontend(account, region, state, eni, address, namespace, proxy, servers, backends,
                    ownershipId, backendRuntime);
        }

        Frontend withRuntime(ElbClassicBackendRuntime runtime) {
            return new Frontend(account, region, state, eni, address, namespace, proxy, servers, backends,
                    ownershipId, runtime);
        }
    }
}
