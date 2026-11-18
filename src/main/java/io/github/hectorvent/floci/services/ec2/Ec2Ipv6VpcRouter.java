package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LocallyBuiltHelperImage;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The IPv6 path between VPCs for guests with IPv6 (FLOCI_SERVICES_EC2_GUEST_IPV6): one helper namespace per Floci
 * that forwards IPv6 and joins each VPC network a dual-stack guest attaches to. On each it holds the VPC router's
 * link-local {@code fe80:ec2::1}, the guests' default IPv6 gateway, and routes that VPC's IPv6 block. It carries no
 * IPv4 (its VPC addresses are flushed and it leaves the network Floci starts helpers on) and has no default route,
 * so IPv6 reaches another VPC and nothing else. The host gets no IPv6 address or route and its forwarding settings
 * are unchanged.
 */
final class Ec2Ipv6VpcRouter {

    private static final Logger LOG = Logger.getLogger(Ec2Ipv6VpcRouter.class);
    static final String SERVICE = "ec2-ipv6-router";
    static final String LABEL = "floci.ec2.ipv6-router";

    private final DockerClient docker;
    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final EmulatorConfig config;
    private final VpcNetworkManager networks;
    private final Set<String> joined = ConcurrentHashMap.newKeySet();
    private volatile String containerId;
    private volatile String startNetwork;

    Ec2Ipv6VpcRouter(DockerClient docker, ContainerBuilder containers, ContainerLifecycleManager lifecycle,
                     EmulatorConfig config, VpcNetworkManager networks) {
        this.docker = docker;
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.config = config;
        this.networks = networks;
    }

    /** Joins {@code network} at the subnet's reserved router address and routes {@code vpcIpv6Block} there. */
    synchronized void join(String region, String subnetId, String network, String vpcIpv6Block) {
        if (joined.contains(network + "|" + vpcIpv6Block)) {
            return;
        }
        String address = networks.ipv6RouterAddress(region, subnetId)
                .orElseThrow(() -> new IllegalStateException("No IPv6 router address for subnet " + subnetId));
        String id = ensureStarted();
        try {
            docker.connectToNetworkCmd().withContainerId(id).withNetworkId(network)
                    .withContainerNetwork(new ContainerNetwork()
                            .withIpamConfig(new ContainerNetwork.Ipam().withIpv4Address(address)))
                    .exec();
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            if (!message.contains("already exists")) {
                throw e;
            }
        }
        ContainerExec.Result result = ContainerExec.run(docker, id, joinCommand(address, vpcIpv6Block), 30);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Cannot route " + vpcIpv6Block + " on " + network + ": " + result.summary());
        }
        String leave = startNetwork;
        if (leave != null) {
            // Now that it holds a VPC network, drop the one it was started on: it never talks to Floci.
            docker.disconnectFromNetworkCmd().withContainerId(id).withNetworkId(leave).withForce(true).exec();
            startNetwork = null;
        }
        joined.add(network + "|" + vpcIpv6Block);
        LOG.infov("IPv6 VPC router joined {0} for {1}", network, vpcIpv6Block);
    }

    /**
     * Removes the router when its Floci stops, as the NAT namespaces and the AWS API bridge are retired: it serves
     * only this deployment, and a later Floci starts its own under its own identity.
     */
    synchronized void retire() {
        String id = containerId;
        if (id == null) {
            return;
        }
        lifecycle.stopAndRemove(id, null, 0);
        containerId = null;
        startNetwork = null;
        joined.clear();
        networks.setIpv6RouterContainer(null);
    }

    private String ensureStarted() {
        String id = containerId;
        if (id != null) {
            return id;
        }
        String image = config.services().ec2().metadataHelperImage();
        LocallyBuiltHelperImage.ensureBuilt(docker, containers, image, Ec2MetadataNamespace.DEFAULT_IMAGE,
                "/docker/network-helper.Dockerfile");
        ContainerSpec spec = containers.newContainer(image)
                .withName(ContainerStorageHelper.resourceName(config, SERVICE, null, "deployment"))
                .withDockerNetwork(Optional.empty())
                .withCapAdd("NET_ADMIN")
                .withNetworkSysctl("net.ipv6.conf.all.disable_ipv6", "0")
                .withNetworkSysctl("net.ipv6.conf.default.disable_ipv6", "0")
                .withNetworkSysctl("net.ipv6.conf.all.forwarding", "1")
                .withNetworkSysctl("net.ipv4.ip_forward", "0")
                .withEntrypoint(List.of("sh", "-c"))
                .withCmd(List.of("exec sleep 2147483647"))
                .withLabels(Map.of(LABEL, "true",
                        ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                        ContainerStorageHelper.SERVICE_LABEL, SERVICE))
                .build();
        id = lifecycle.createAndStart(spec).containerId();
        startNetwork = networkToLeave(spec.networkMode()).orElse(null);
        containerId = id;
        networks.setIpv6RouterContainer(id);
        return id;
    }

    /** The network a started helper sits on that the router leaves once joined: any but none, host or shared. */
    static Optional<String> networkToLeave(String networkMode) {
        if (networkMode == null || networkMode.isBlank() || networkMode.equals("none") || networkMode.equals("host")
                || networkMode.startsWith("container:")) {
            return Optional.empty();
        }
        return Optional.of(networkMode);
    }

    /**
     * On the interface holding {@code address}: drop its IPv4 (no IPv4 between VPCs), hold the VPC router's link-local
     * {@code fe80:ec2::1} and route the VPC's IPv6 block on it.
     */
    static String[] joinCommand(String address, String vpcIpv6Block) {
        String script = String.join("\n",
                "set -eu",
                "dev=$(ip -o -4 addr show | awk -v a=\"$1\" '{split($4, p, \"/\"); sub(/@.*/, \"\", $2); if (p[1] == a) print $2}' | head -n1)",
                "[ -n \"$dev\" ] || { echo \"no interface holds $1\" >&2; exit 3; }",
                "ip -4 addr flush dev \"$dev\"",
                "ip -6 addr replace fe80:ec2::1/64 dev \"$dev\" nodad",
                "ip -6 route replace \"$2\" dev \"$dev\"");
        return new String[] {"sh", "-c", script, "ipv6-router", address, vpcIpv6Block};
    }
}
