package io.github.hectorvent.floci.services.elbv2;

import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.elbv2.model.AvailabilityZone;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Owns the ALB's backend connections in its VPC, including its health-check traffic. */
@ApplicationScoped
public class ElbV2VpcNetworkManager {
    static final int PROXY_PORT = 1080;
    static final String ENI_LABEL = "io.floci.elbv2.network-interface";
    static final String BALANCER_LABEL = "io.floci.elbv2.load-balancer";
    private final EmulatorConfig config;
    private final Ec2Service ec2;
    private final SecurityGroupFirewallManager firewall;
    private final ContainerLifecycleManager lifecycle;
    private final VpcNetworkManager vpcNetworks;
    private final Map<String, List<Attachment>> attachments = new ConcurrentHashMap<>();
    private final AtomicInteger selection = new AtomicInteger();

    public ElbV2VpcNetworkManager(EmulatorConfig config, Ec2Service ec2,
                                SecurityGroupFirewallManager firewall, ContainerLifecycleManager lifecycle,
                                VpcNetworkManager vpcNetworks) {
        this.config = config;
        this.ec2 = ec2;
        this.firewall = firewall;
        this.lifecycle = lifecycle;
        this.vpcNetworks = vpcNetworks;
    }

    boolean enabled() {
        return firewall.enabled() && vpcNetworks.enabled() && !config.services().elbv2().mock();
    }

    /** The owner persists ENI identities before starting their network processes. */
    synchronized void reconcile(LoadBalancer balancer, Runnable persist) {
        if (!enabled() || !"application".equals(balancer.getType())) {
            return;
        }
        String account = AwsArnUtils.parse(balancer.getLoadBalancerArn()).accountId();
        RequestScopes.callAs(account, balancer.getRegion(), () -> {
            reconcileInAccount(balancer, account, persist);
            return null;
        });
    }

    private void reconcileInAccount(LoadBalancer balancer, String account, Runnable persist) {
        String arn = balancer.getLoadBalancerArn();
        String region = balancer.getRegion();
        List<Attachment> previous = attachments.remove(arn);
        Map<String, Attachment> live = previous == null ? new LinkedHashMap<>() : previous.stream()
                .collect(Collectors.toMap(Attachment::eniId, value -> value));
        List<Attachment> ready = new ArrayList<>();
        Map<String, NetworkInterface> interfaces = new LinkedHashMap<>();
        for (String eniId : List.copyOf(balancer.getBackendNetworkInterfaceIds())) {
            try {
                NetworkInterface eni = ec2.describeNetworkInterfaces(region, List.of(eniId), Map.of())
                        .networkInterfaces().getFirst();
                interfaces.put(eni.getSubnetId(), eni);
            } catch (AwsException failure) {
                if (!"InvalidNetworkInterfaceID.NotFound".equals(failure.getErrorCode())) {
                    throw failure;
                }
                // A previous deletion can finish before its updated owner record is saved.
                removeSavedHelpers(arn, eniId);
                firewall.unregister(eniId);
                balancer.getBackendNetworkInterfaceIds().remove(eniId);
                persist.run();
            }
        }
        try {
            for (AvailabilityZone zone : balancer.getAvailabilityZones()) {
                NetworkInterface eni = interfaces.remove(zone.getSubnetId());
                if (eni == null) {
                    eni = ec2.createNetworkInterface(region, zone.getSubnetId(),
                            "ELB " + AwsArnUtils.parse(arn).resource().substring("loadbalancer/".length()),
                            null, List.of(), balancer.getSecurityGroups(), List.of());
                    balancer.getBackendNetworkInterfaceIds().add(eni.getNetworkInterfaceId());
                    persist.run();
                }
                ec2.holdNetworkInterfaceForService(region, eni.getNetworkInterfaceId());
                List<String> groups = balancer.getSecurityGroups().isEmpty()
                        ? eni.getGroups().stream().map(GroupIdentifier::getGroupId).toList()
                        : List.copyOf(balancer.getSecurityGroups());
                if (balancer.getSecurityGroups().isEmpty()) {
                    balancer.setSecurityGroups(new ArrayList<>(groups));
                    persist.run();
                }
                ec2.setNetworkInterfaceGroupsForService(region, eni.getNetworkInterfaceId(), groups);
                Attachment attachment = live.remove(eni.getNetworkInterfaceId());
                if (attachment == null) {
                    removeSavedHelpers(arn, eni.getNetworkInterfaceId());
                    attachment = prepare(account, region, arn, eni, groups);
                } else {
                    register(account, region, eni, groups, attachment);
                }
                ready.add(attachment);
            }
            for (NetworkInterface obsolete : interfaces.values()) {
                releaseInterface(balancer, obsolete.getNetworkInterfaceId(), persist);
            }
            attachments.put(arn, List.copyOf(ready));
        } catch (RuntimeException failure) {
            // Helpers and ENIs stay recorded for retry or deletion. There is no direct-network fallback.
            throw failure;
        }
    }

    private Attachment prepare(String account, String region, String arn, NetworkInterface eni,
                               List<String> groups) {
        SecurityGroupFirewallManager.Namespace namespace = firewall.createNamespace("elbv2",
                "alb-" + eni.getNetworkInterfaceId(), account, region, config.services().dockerNetwork(), Map.of(),
                Map.of(ENI_LABEL, eni.getNetworkInterfaceId(), BALANCER_LABEL, arn));
        try {
            if (vpcNetworks.attach(region, eni.getVpcId(), eni.getSubnetId(), namespace.helperId(),
                    eni.getPrivateIpAddress()).isEmpty()) {
                throw new IllegalStateException("ALB backend namespace could not attach to its VPC");
            }
            String controller = controllerAddress(namespace.transportAddress());
            Attachment attachment = new Attachment(eni.getNetworkInterfaceId(), namespace.helperId(),
                    namespace.transportAddress(), controller);
            register(account, region, eni, groups, attachment);
            String contents = proxyConfiguration(controller, namespace.transportAddress(), eni.getPrivateIpAddress());
            ContainerExec.Result configured = ContainerExec.run(lifecycle.getDockerClient(), namespace.helperId(),
                    new String[]{"sh", "-c", "umask 077; printf '%s' \"$1\" > /tmp/floci-alb-sockd.conf",
                            "floci-alb-proxy", contents}, 10).throwIfTimedOut(namespace.helperId());
            requireSuccess(configured, "write ALB proxy configuration");
            requireSuccess(ContainerExec.run(lifecycle.getDockerClient(), namespace.helperId(),
                    new String[]{"sockd", "-D", "-f", "/tmp/floci-alb-sockd.conf"}, 10)
                    .throwIfTimedOut(namespace.helperId()), "start ALB backend proxy");
            requireSuccess(ContainerExec.run(lifecycle.getDockerClient(), namespace.helperId(),
                    new String[]{"sh", "-c",
                            "for i in $(seq 1 50); do ss -ltnH 'sport = :1080' | grep -q . && exit 0;"
                                    + " sleep 0.1; done; exit 1"}, 6)
                    .throwIfTimedOut(namespace.helperId()), "wait for ALB backend proxy");
            return attachment;
        } catch (RuntimeException failure) {
            try {
                lifecycle.removeIfExistsStrict(namespace.helperId());
                firewall.unregister(eni.getNetworkInterfaceId());
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void register(String account, String region, NetworkInterface eni, List<String> groupIds,
                          Attachment attachment) {
        List<SecurityGroup> groups = ec2.describeSecurityGroups(region, groupIds, List.of(), Map.of());
        if (!Set.copyOf(groupIds).equals(groups.stream().map(SecurityGroup::getGroupId).collect(Collectors.toSet()))) {
            throw new IllegalStateException("An ALB security group disappeared");
        }
        Map<String, List<String>> prefixLists = new LinkedHashMap<>();
        for (SecurityGroup group : groups) {
            Stream.concat(group.getIpPermissions().stream(), group.getIpPermissionsEgress().stream())
                    .flatMap(permission -> permission.getPrefixListIds().stream())
                    .map(reference -> reference.getPrefixListId()).distinct()
                    .forEach(id -> prefixLists.put(id, ec2.getManagedPrefixListEntries(region, id, null)
                            .stream().map(PrefixListEntry::getCidr).toList()));
        }
        firewall.register(new SecurityGroupNftCompiler.Endpoint(account, region, eni.getVpcId(),
                eni.getNetworkInterfaceId(), eni.getPrivateIpAddress(), attachment.transportAddress(),
                Set.copyOf(groupIds), groups, List.of(), false, Set.of(eni.getPrivateIpAddress()),
                List.of(new SecurityGroupNftCompiler.ControlListener(attachment.controllerAddress(), PROXY_PORT))),
                attachment.helperId(), prefixLists);
    }

    synchronized void release(LoadBalancer balancer, Runnable persist) {
        attachments.remove(balancer.getLoadBalancerArn());
        String account = AwsArnUtils.parse(balancer.getLoadBalancerArn()).accountId();
        RequestScopes.callAs(account, balancer.getRegion(), () -> {
            for (String eniId : List.copyOf(balancer.getBackendNetworkInterfaceIds())) {
                releaseInterface(balancer, eniId, persist);
            }
            return null;
        });
    }

    private void releaseInterface(LoadBalancer balancer, String eniId, Runnable persist) {
        removeSavedHelpers(balancer.getLoadBalancerArn(), eniId);
        firewall.unregister(eniId);
        ec2.releaseNetworkInterfaceFromService(balancer.getRegion(), eniId);
        try {
            ec2.deleteNetworkInterface(balancer.getRegion(), eniId);
        } catch (AwsException failure) {
            if (!"InvalidNetworkInterfaceID.NotFound".equals(failure.getErrorCode())) {
                throw failure;
            }
        }
        balancer.getBackendNetworkInterfaceIds().remove(eniId);
        persist.run();
    }

    private void removeSavedHelpers(String arn, String eniId) {
        Map<String, String> labels = Map.of(ContainerStorageHelper.OWNER_LABEL,
                ContainerStorageHelper.ownerIdentity(config), ContainerStorageHelper.SERVICE_LABEL, "elbv2",
                ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true", BALANCER_LABEL, arn, ENI_LABEL, eniId);
        for (Container helper : lifecycle.getDockerClient().listContainersCmd().withShowAll(true)
                .withLabelFilter(labels).exec()) {
            lifecycle.removeIfExistsStrict(helper.getId());
        }
    }

    BackendProxy proxy(String loadBalancerArn) {
        if (!enabled() || loadBalancerArn == null
                || !AwsArnUtils.parse(loadBalancerArn).resource().startsWith("loadbalancer/app/")) {
            return BackendProxy.DIRECT;
        }
        List<Attachment> selected = attachments.get(loadBalancerArn);
        if (selected == null || selected.isEmpty()) {
            throw new IllegalStateException("ALB backend network is not ready");
        }
        Attachment attachment = selected.get(Math.floorMod(selection.getAndIncrement(), selected.size()));
        return new BackendProxy(attachment.transportAddress());
    }

    BackendProxy proxy(TargetGroup group) {
        return proxy(group.getLoadBalancerArns().stream().sorted().findFirst().orElse(null));
    }

    static String controllerAddress(String transportAddress) {
        try (DatagramSocket route = new DatagramSocket()) {
            route.connect(InetAddress.getByName(transportAddress), PROXY_PORT);
            return route.getLocalAddress().getHostAddress();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot determine the ALB proxy control route", failure);
        }
    }

    static String proxyConfiguration(String controller, String transport, String source) {
        // Reuse the compiler's literal validation before placing any address in a daemon configuration.
        new SecurityGroupNftCompiler.ControlListener(controller, PROXY_PORT);
        new SecurityGroupNftCompiler.ControlListener(transport, PROXY_PORT);
        new SecurityGroupNftCompiler.ControlListener(source, PROXY_PORT);
        String controllerMask = controller.contains(":") ? "/128" : "/32";
        String transportMask = transport.contains(":") ? "/128" : "/32";
        return "logoutput: /tmp/floci-alb-sockd.log\ninternal: " + transport
                + " port = " + PROXY_PORT + "\nexternal: " + source
                + "\nclientmethod: none\nsocksmethod: none\nuser.privileged: root\nuser.unprivileged: nobody\n"
                + "client pass {\n from: " + controller + controllerMask + " to: " + transport + transportMask
                + "\n log: error\n}\nsocks pass {\n from: " + controller + controllerMask
                + " to: 0.0.0.0/0\n command: connect\n log: error\n}\n";
    }

    private static void requireSuccess(ContainerExec.Result result, String action) {
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Cannot " + action + ": " + result.summary());
        }
    }

    record Attachment(String eniId, String helperId, String transportAddress, String controllerAddress) {}

    record BackendProxy(String host) {
        static final BackendProxy DIRECT = new BackendProxy(null);

        Proxy javaProxy() {
            return host == null ? Proxy.NO_PROXY : new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, PROXY_PORT));
        }

        ProxyOptions vertxProxy() {
            return host == null ? null : new ProxyOptions().setType(ProxyType.SOCKS5).setHost(host).setPort(PROXY_PORT);
        }
    }
}
