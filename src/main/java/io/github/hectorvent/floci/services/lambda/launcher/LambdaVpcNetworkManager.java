package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerFlags;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Owns an execution environment's ENI and its protected Docker network namespace. */
@ApplicationScoped
public class LambdaVpcNetworkManager {
    static final String ENI_LABEL = "io.floci.lambda.network-interface";

    private final EmulatorConfig config;
    private final Ec2Service ec2;
    private final SecurityGroupFirewallManager firewall;
    private final ContainerLifecycleManager lifecycle;
    private final VpcNetworkManager vpcNetworks;
    private final AtomicInteger subnetSelection = new AtomicInteger();
    private boolean recovered;

    public LambdaVpcNetworkManager(EmulatorConfig config, Ec2Service ec2,
                                   SecurityGroupFirewallManager firewall, ContainerLifecycleManager lifecycle,
                                   VpcNetworkManager vpcNetworks) {
        this.config = config;
        this.ec2 = ec2;
        this.firewall = firewall;
        this.lifecycle = lifecycle;
        this.vpcNetworks = vpcNetworks;
    }

    Optional<Attachment> prepare(LambdaFunction function, String resourceId, String runtimeHost, int runtimePort,
                                 DockerFlags flags) {
        if (!firewall.enabled()) {
            return Optional.empty();
        }
        List<String> subnets = ids(function, "SubnetIds");
        List<String> groupIds = ids(function, "SecurityGroupIds");
        if (subnets.isEmpty() && groupIds.isEmpty()) {
            return Optional.empty();
        }
        if (subnets.isEmpty() || groupIds.isEmpty()) {
            throw new IllegalStateException("Persisted Lambda VPC configuration needs subnets and security groups");
        }
        if (flags.privileged() || flags.network() != null || !flags.publishedPorts().isEmpty()
                || !flags.dnsServers().isEmpty() || !flags.extraHosts().isEmpty()
                || !config.services().lambda().additionalDockerNetworks().orElse(List.of()).isEmpty()
                || !config.services().lambda().extraHosts().orElse(List.of()).isEmpty()) {
            throw new IllegalArgumentException("Protected Lambda VPC networking cannot use privileged containers, "
                    + "additional networks, or Docker network, port, DNS and host overrides");
        }
        recoverOrphans();
        String account = AwsArnUtils.accountOrDefault(function.getFunctionArn(), config.defaultAccountId());
        String region = AwsArnUtils.regionOrDefault(function.getFunctionArn(), config.defaultRegion());
        String subnet = subnets.get(Math.floorMod(subnetSelection.getAndIncrement(), subnets.size()));
        return Optional.of(RequestScopes.callAs(account, region, () ->
                prepareInAccount(account, region, subnet, groupIds, resourceId, runtimeHost, runtimePort)));
    }

    private Attachment prepareInAccount(String account, String region, String subnet, List<String> groupIds,
                                        String resourceId, String runtimeHost, int runtimePort) {
        NetworkInterface eni = ec2.createNetworkInterface(region, subnet, "Lambda " + resourceId,
                null, List.of(), groupIds, List.of());
        String eniId = eni.getNetworkInterfaceId();
        SecurityGroupFirewallManager.Namespace namespace = null;
        try {
            ec2.holdNetworkInterfaceForService(region, eniId);
            namespace = firewall.createNamespace("lambda", resourceId, account, region,
                    config.services().lambda().dockerNetwork(), Map.of(), Map.of(ENI_LABEL, eniId));
            boolean attached = vpcNetworks.attach(region, eni.getVpcId(), subnet,
                    namespace.helperId(), eni.getPrivateIpAddress()).isPresent();
            if (vpcNetworks.enabled() && !attached) {
                throw new IllegalStateException("Protected Lambda namespace could not attach to its VPC");
            }
            Set<String> privateAddresses = attached ? Set.of(eni.getPrivateIpAddress()) : Set.of();
            List<SecurityGroup> groups = ec2.describeSecurityGroups(region, groupIds, List.of(), Map.of());
            if (!Set.copyOf(groupIds).equals(groups.stream().map(SecurityGroup::getGroupId)
                    .collect(Collectors.toSet()))) {
                throw new IllegalStateException("A Lambda execution environment's security group disappeared");
            }
            Map<String, List<String>> prefixLists = new LinkedHashMap<>();
            for (SecurityGroup group : groups) {
                Stream.concat(group.getIpPermissions().stream(), group.getIpPermissionsEgress().stream())
                        .flatMap(permission -> permission.getPrefixListIds().stream())
                        .map(reference -> reference.getPrefixListId()).distinct()
                        .forEach(id -> prefixLists.put(id, ec2.getManagedPrefixListEntries(region, id, null)
                                .stream().map(PrefixListEntry::getCidr).toList()));
            }
            List<SecurityGroupNftCompiler.ControlConnection> control = literalConnection(runtimeHost, runtimePort);
            if (control.isEmpty()) {
                // Install the VPC DNS exception before resolving names inside this namespace.
                // host.docker.internal is supplied by Docker here, not by the host JVM's resolver.
                firewall.register(new SecurityGroupNftCompiler.Endpoint(account, region, eni.getVpcId(), eniId,
                        eni.getPrivateIpAddress(), namespace.transportAddress(), Set.copyOf(groupIds), groups,
                        List.of(), false, privateAddresses), namespace.helperId(), prefixLists);
                control = runtimeConnections(namespace.helperId(), runtimeHost, runtimePort);
            }
            firewall.register(new SecurityGroupNftCompiler.Endpoint(account, region, eni.getVpcId(), eniId,
                            eni.getPrivateIpAddress(), namespace.transportAddress(), Set.copyOf(groupIds), groups,
                            control, false, privateAddresses), namespace.helperId(), prefixLists);
            return new Attachment(account, region, eniId, namespace.helperId());
        } catch (RuntimeException failure) {
            try {
                if (namespace != null) {
                    release(new Attachment(account, region, eniId, namespace.helperId()));
                } else {
                    releaseInterface(region, eniId);
                }
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Called only after the workload has been confirmed removed. */
    void release(Attachment attachment) {
        RequestScopes.callAs(attachment.accountId(), attachment.region(), () -> {
            // Keep the ENI reserved if the helper cannot be removed.
            lifecycle.removeIfExistsStrict(attachment.helperId());
            try {
                firewall.unregister(attachment.eniId());
            } catch (RuntimeException failure) {
                // A different endpoint may fail reconciliation after this helper is gone.
                try {
                    releaseInterface(attachment.region(), attachment.eniId());
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
            releaseInterface(attachment.region(), attachment.eniId());
            return null;
        });
    }

    private void releaseInterface(String region, String eniId) {
        ec2.releaseNetworkInterfaceFromService(region, eniId);
        try {
            ec2.deleteNetworkInterface(region, eniId);
        } catch (AwsException failure) {
            if (!"InvalidNetworkInterfaceID.NotFound".equals(failure.getErrorCode())) {
                throw failure;
            }
        }
    }

    synchronized void recoverOrphans() {
        if (recovered) {
            return;
        }
        String owner = ContainerStorageHelper.ownerIdentity(config);
        Map<String, String> selection = Map.of(ContainerStorageHelper.OWNER_LABEL, owner,
                ContainerStorageHelper.SERVICE_LABEL, "lambda",
                ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true");
        for (Container helper : lifecycle.getDockerClient().listContainersCmd().withShowAll(true)
                .withLabelFilter(selection).exec()) {
            Map<String, String> labels = helper.getLabels();
            String eniId = labels.get(ENI_LABEL);
            if (eniId == null) {
                continue;
            }
            String account = labels.get(ContainerStorageHelper.ACCOUNT_LABEL);
            String region = labels.get(ContainerStorageHelper.REGION_LABEL);
            if (account == null || region == null) {
                throw new IllegalStateException("Protected Lambda helper lacks its account or region");
            }
            for (Container container : lifecycle.getDockerClient().listContainersCmd().withShowAll(true).exec()) {
                InspectContainerResponse inspected;
                try {
                    inspected = lifecycle.getDockerClient().inspectContainerCmd(container.getId()).exec();
                } catch (NotFoundException ignored) {
                    // Containers may finish removal between listing and inspection.
                    continue;
                }
                if (inspected.getHostConfig() == null
                        || !("container:" + helper.getId()).equals(inspected.getHostConfig().getNetworkMode())) {
                    continue;
                }
                Map<String, String> workloadLabels = container.getLabels();
                if (workloadLabels == null || !owner.equals(workloadLabels.get(ContainerStorageHelper.OWNER_LABEL))
                        || !"lambda".equals(workloadLabels.get(ContainerStorageHelper.SERVICE_LABEL))
                        || !"true".equals(workloadLabels.get(ContainerStorageHelper.SECURITY_GROUP_WORKLOAD_LABEL))) {
                    throw new IllegalStateException("A protected Lambda namespace contains an unowned workload");
                }
                lifecycle.stopAndRemoveStrict(container.getId(), null, 1);
            }
            release(new Attachment(account, region, eniId, helper.getId()));
        }
        recovered = true;
    }

    private static List<String> ids(LambdaFunction function, String member) {
        Object value = function.getVpcConfig() == null ? null : function.getVpcConfig().get(member);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || list.stream().anyMatch(id -> !(id instanceof String))) {
            throw new IllegalStateException("Invalid persisted Lambda VPC " + member);
        }
        return list.stream().map(String.class::cast).toList();
    }

    private static List<SecurityGroupNftCompiler.ControlConnection> literalConnection(String host, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid Lambda Runtime API port");
        }
        try {
            return List.of(new SecurityGroupNftCompiler.ControlConnection(host, port));
        } catch (IllegalArgumentException ignored) {
            // A hostname must be resolved in the workload namespace before it can enter a rule.
            return List.of();
        }
    }

    private List<SecurityGroupNftCompiler.ControlConnection> runtimeConnections(String helper, String host, int port) {
        Set<SecurityGroupNftCompiler.ControlConnection> resolved = new LinkedHashSet<>();
        for (String database : List.of("ahosts", "hosts")) {
            String id = lifecycle.getDockerClient().execCreateCmd(helper)
                    .withAttachStdout(true).withAttachStderr(true)
                    .withCmd("timeout", "5", "getent", database, host).exec().getId();
            StringBuilder output = new StringBuilder();
            try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                @Override public void onNext(Frame frame) {
                    output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                }
            }) {
                if (!lifecycle.getDockerClient().execStartCmd(id).exec(callback)
                        .awaitCompletion(6, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Lambda Runtime API hostname lookup did not finish");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Lambda Runtime API hostname lookup was interrupted", interrupted);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot read the Lambda Runtime API hostname lookup", failure);
            }
            if (Long.valueOf(0).equals(lifecycle.getDockerClient().inspectExecCmd(id).exec().getExitCodeLong())) {
                for (String line : output.toString().lines().toList()) {
                    String address = line.strip().split("\\s+", 2)[0];
                    resolved.addAll(literalConnection(address, port));
                }
            }
            if (!resolved.isEmpty()) {
                return List.copyOf(resolved);
            }
        }
        throw new IllegalStateException("Cannot resolve the Lambda Runtime API endpoint in its protected namespace: "
                + host);
    }

    record Attachment(String accountId, String region, String eniId, String helperId) {}
}
