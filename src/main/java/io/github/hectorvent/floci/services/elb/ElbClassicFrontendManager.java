package io.github.hectorvent.floci.services.elb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CertificateReferenceResolver;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import io.github.hectorvent.floci.services.iam.IamService;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Owns a Classic load balancer's private interface, firewall namespace and listener process. */
@ApplicationScoped
public class ElbClassicFrontendManager implements ContainerTeardown {
    private static final Logger LOG = Logger.getLogger(ElbClassicFrontendManager.class);
    private static final String SERVICE = "elb-classic";
    private static final String ENI_LABEL = "io.floci.elb-classic.network-interface";

    private final EmulatorConfig config;
    private final Ec2Service ec2;
    private final SecurityGroupFirewallManager firewall;
    private final VpcNetworkManager vpcNetworks;
    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final DockerClient docker;
    private final ObjectMapper mapper;
    private final CertificateReferenceResolver certificates;
    private final Map<String, Frontend> frontends = new ConcurrentHashMap<>();
    private boolean recovered;

    public ElbClassicFrontendManager(EmulatorConfig config, Ec2Service ec2,
                                    SecurityGroupFirewallManager firewall, VpcNetworkManager vpcNetworks,
                                    ContainerBuilder containers, ContainerLifecycleManager lifecycle,
                                    ObjectMapper mapper, IamService iam, AcmService acm) {
        this.config = config;
        this.ec2 = ec2;
        this.firewall = firewall;
        this.vpcNetworks = vpcNetworks;
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.docker = lifecycle.getDockerClient();
        this.mapper = mapper;
        this.certificates = new CertificateReferenceResolver(iam, acm);
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
            String state = state(loadBalancer);
            AtomicBoolean changed = new AtomicBoolean();
            frontends.compute(key, (ignored, previous) -> {
                if (previous != null && state.equals(previous.state()) && running(previous)) {
                    return previous;
                }
                Frontend next = create(loadBalancer, state);
                try {
                    if (previous != null) {
                        release(previous);
                    }
                } catch (RuntimeException failure) {
                    try {
                        release(next);
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
        if (!"internal".equals(loadBalancer.getScheme()) || loadBalancer.getSubnets().size() != 1) {
            throw new AwsException("UnsupportedOperation",
                    "Protected Classic frontends currently support one IPv4 subnet and the internal scheme.", 400);
        }
        if (loadBalancer.getSecurityGroups().isEmpty()) {
            throw new AwsException("InvalidConfigurationRequest", "A protected frontend needs security groups.", 400);
        }
        if (loadBalancer.getAttributes().isAccessLogEnabled()
                || loadBalancer.getAttributes().isConnectionDrainingEnabled()) {
            throw new AwsException("UnsupportedOperation",
                    "Protected Classic frontend access logging and connection draining are not implemented.", 400);
        }
    }

    private String state(ClassicLoadBalancer loadBalancer) {
        try {
            return mapper.writeValueAsString(List.of(loadBalancer.getSubnets(), loadBalancer.getSecurityGroups(),
                    loadBalancer.getListeners(), loadBalancer.getHealthCheck(), loadBalancer.getAttributes(),
                    loadBalancer.getInstanceIds()));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot snapshot Classic frontend settings", failure);
        }
    }

    private Frontend create(ClassicLoadBalancer loadBalancer, String state) {
        String account = account(loadBalancer);
        String region = loadBalancer.getRegion();
        String resource = loadBalancer.getLoadBalancerName() + "-" + UUID.randomUUID();
        NetworkInterface eni = ec2.createNetworkInterface(region, loadBalancer.getSubnets().getFirst(),
                "Classic ELB " + loadBalancer.getLoadBalancerName(), null, List.of(),
                loadBalancer.getSecurityGroups(), List.of());
        Frontend pending = new Frontend(account, region, state, eni.getNetworkInterfaceId(),
                eni.getPrivateIpAddress(), null, null, Map.of(), Map.of());
        try {
            ec2.holdNetworkInterfaceForService(region, eni.getNetworkInterfaceId());
            SecurityGroupFirewallManager.Namespace namespace = firewall.createNamespace(SERVICE, resource,
                    account, region, Optional.empty(), Map.of(), Map.of(ENI_LABEL, eni.getNetworkInterfaceId()));
            pending = pending.withNamespace(new NetworkNamespace(namespace.helperId(), null));
            pending = pending.withNamespace(new NetworkNamespace(namespace.helperId(),
                    docker.inspectContainerCmd(namespace.helperId()).exec().getName()));
            if (vpcNetworks.attach(region, eni.getVpcId(), eni.getSubnetId(),
                    namespace.helperId(), eni.getPrivateIpAddress()).isEmpty()) {
                throw new IllegalStateException("Classic frontend could not attach to its VPC");
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
            List<ElbClassicProxyConfig.Backend> backends = new ArrayList<>();
            for (String id : loadBalancer.getInstanceIds()) {
                Instance instance = ec2.getInstance(region, id).orElse(null);
                if (instance != null && eni.getVpcId().equals(instance.getVpcId())
                        && instance.getPrivateIpAddress() != null) {
                    backends.add(new ElbClassicProxyConfig.Backend(id, instance.getPrivateIpAddress()));
                }
            }
            String owner = AwsArnUtils.Arn.of("elasticloadbalancing", region, account,
                    "loadbalancer/" + loadBalancer.getLoadBalancerName()).toString();
            ElbClassicProxyConfig.Rendered rendered = ElbClassicProxyConfig.render(loadBalancer,
                    eni.getPrivateIpAddress(), backends,
                    reference -> certificates.publishedMaterial(owner, region, reference));
            Map<String, String> addresses = backends.stream().collect(Collectors.toUnmodifiableMap(
                    ElbClassicProxyConfig.Backend::instanceId, ElbClassicProxyConfig.Backend::address));
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
            return pending.withConfiguration(rendered.servers(), addresses);
        } catch (RuntimeException failure) {
            try {
                release(pending);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    Optional<String> address(ClassicLoadBalancer loadBalancer) {
        Frontend frontend = frontends.get(key(loadBalancer));
        return frontend == null ? Optional.empty() : Optional.of(frontend.address());
    }

    record Probe(Frontend frontend, String instanceId, String address) {}

    Probe probeFor(ClassicLoadBalancer loadBalancer, String instanceId) {
        Frontend frontend = frontends.get(key(loadBalancer));
        return frontend == null ? null : new Probe(frontend, instanceId, frontend.backends().get(instanceId));
    }

    boolean current(ClassicLoadBalancer loadBalancer, Probe probe) {
        return probe != null && frontends.get(key(loadBalancer)) == probe.frontend();
    }

    boolean probe(Probe probe, ElbClassicHealthChecker.Target target, int timeoutSeconds) {
        if (probe == null || probe.address() == null) {
            return false;
        }
        Frontend frontend = probe.frontend();
        Instance instance = RequestScopes.callAs(frontend.account(), frontend.region(),
                () -> ec2.getInstance(frontend.region(), probe.instanceId()).orElse(null));
        if (instance == null || instance.getState() == null || !"running".equals(instance.getState().getName())
                || !probe.address().equals(instance.getPrivateIpAddress())) {
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
        return result.exitCode() == 0 && (target.path() == null || "200".equals(result.stdout().trim()));
    }

    void healthChanged(ClassicLoadBalancer loadBalancer, Probe probe,
                       ElbClassicHealthChecker.InstanceHealth health) {
        if (probe == null) {
            return;
        }
        frontends.computeIfPresent(key(loadBalancer), (ignored, frontend) -> {
            if (frontend == probe.frontend() && frontend.proxy() != null) {
                publishHealth(frontend, probe.instanceId(), health);
            }
            return frontend;
        });
    }

    private void publishHealth(Frontend frontend, String instanceId, ElbClassicHealthChecker.InstanceHealth health) {
        String state = "InService".equals(health.state()) ? "ready" : "maint";
        String commands = frontend.servers().getOrDefault(instanceId, List.of()).stream()
                .map(server -> "set server " + server + " state " + state + "\n").collect(Collectors.joining());
        if (!commands.isEmpty()) {
            try {
                String response = command(frontend, commands);
                if (!response.isBlank()) {
                    throw new IllegalStateException("Classic frontend rejected its health update: " + response);
                }
            } catch (RuntimeException failure) {
                try {
                    lifecycle.stopAndRemoveStrict(frontend.proxy(), null, 1);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }
    }

    void remove(ClassicLoadBalancer loadBalancer) {
        remove(key(loadBalancer));
    }

    private void remove(String key) {
        frontends.computeIfPresent(key, (ignored, frontend) ->
            RequestScopes.callAs(frontend.account(), frontend.region(), () -> {
                release(frontend);
                return null;
            }));
    }

    private void release(Frontend frontend) {
        if (frontend.proxy() != null) {
            lifecycle.stopAndRemoveStrict(frontend.proxy(), null, 1);
        }
        if (frontend.helper() != null) {
            requireUnusedNamespace(frontend.namespace());
            lifecycle.removeIfExistsStrict(frontend.helper());
            firewall.unregister(frontend.eni());
        }
        ec2.releaseNetworkInterfaceFromService(frontend.region(), frontend.eni());
        try {
            ec2.deleteNetworkInterface(frontend.region(), frontend.eni());
        } catch (AwsException failure) {
            if (!"InvalidNetworkInterfaceID.NotFound".equals(failure.getErrorCode())) {
                throw failure;
            }
        }
    }

    private void requireUnusedNamespace(NetworkNamespace namespace) {
        for (Container container : docker.listContainersCmd().withShowAll(true).exec()) {
            try {
                String mode = docker.inspectContainerCmd(container.getId()).exec().getHostConfig().getNetworkMode();
                if (mode != null && mode.startsWith("container:")) {
                    String reference = mode.substring("container:".length());
                    if (namespace.id().equals(reference) || reference.equals(namespace.name())
                            || namespace.id().equals(docker.inspectContainerCmd(reference).exec().getId())) {
                        throw new IllegalStateException("Classic namespace still has a consumer: " + container.getId());
                    }
                }
            } catch (NotFoundException ignored) {
                // Removed containers can disappear during this inspection.
            }
        }
    }

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
                        new NetworkNamespace(container.getId(), container.getNames()[0]), null, Map.of(), Map.of()));
                return null;
            });
        }
        recovered = true;
    }

    @PreDestroy
    void close() {
        stopManagedContainers();
    }

    @Override
    public void stopManagedContainers() {
        for (String key : frontends.keySet()) {
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

    record NetworkNamespace(String id, String name) {
        NetworkNamespace {
            if (name != null && name.startsWith("/")) {
                name = name.substring(1);
            }
        }
    }

    record Frontend(String account, String region, String state, String eni, String address,
                    NetworkNamespace namespace, String proxy,
                    Map<String, List<String>> servers, Map<String, String> backends) {
        String helper() {
            return namespace == null ? null : namespace.id();
        }

        Frontend withNamespace(NetworkNamespace value) {
            return new Frontend(account, region, state, eni, address, value, proxy, servers, backends);
        }

        Frontend withProxy(String proxyId) {
            return new Frontend(account, region, state, eni, address, namespace, proxyId, servers, backends);
        }

        Frontend withConfiguration(Map<String, List<String>> servers, Map<String, String> backends) {
            return new Frontend(account, region, state, eni, address, namespace, proxy, servers, backends);
        }
    }
}
