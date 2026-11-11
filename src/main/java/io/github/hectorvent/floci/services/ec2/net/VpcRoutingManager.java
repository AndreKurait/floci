package io.github.hectorvent.floci.services.ec2.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Network;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LocallyBuiltHelperImage;
import io.github.hectorvent.floci.core.common.docker.NetworkNamespaceLease;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.NatGatewayAddress;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import io.github.hectorvent.floci.services.ec2.model.Route;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Materializes native IPv4 NAT and internet routes between isolated, managed public endpoints. */
@ApplicationScoped
public class VpcRoutingManager implements ContainerTeardown, Resettable {
    private static final Logger LOG = Logger.getLogger(VpcRoutingManager.class);
    private static final String COMPONENT = "vpc-public-transport";
    private static final String NAT_SERVICE = "vpc-nat";

    private final EmulatorConfig config;
    private final Ec2Service ec2;
    private final VpcNetworkManager vpcs;
    private final DockerClient docker;
    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final ObjectMapper mapper;
    private final VpcAwsApiBridge awsApi;
    private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    private final Map<String, PublicAddress> publicAddresses = new LinkedHashMap<>();
    private final Map<String, NatRuntime> nats = new LinkedHashMap<>();
    private String publicNetwork;
    private boolean draining;

    @Inject
    public VpcRoutingManager(EmulatorConfig config, Ec2Service ec2, VpcNetworkManager vpcs,
                             DockerClient docker, ContainerBuilder containers,
                             ContainerLifecycleManager lifecycle, ObjectMapper mapper,
                             ContainerReachableEndpoint endpoint) {
        this.config = config;
        this.ec2 = ec2;
        this.vpcs = vpcs;
        this.docker = docker;
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.mapper = mapper;
        this.awsApi = new VpcAwsApiBridge(config, docker, containers, lifecycle, mapper, endpoint);
    }

    public boolean enabled() {
        return config.network().vpcRouting().enabled();
    }

    /** Public destinations remain subject to the independently installed native route policy. */
    public synchronized Set<String> publicInstanceAddresses() {
        if (!enabled() || draining) {
            return Set.of();
        }
        return endpoints.values().stream().map(endpoint -> endpoint.publicAddress)
                .filter(address -> address != null && address.transport() != null)
                .map(NativePublicAddress::address).collect(Collectors.toUnmodifiableSet());
    }

    public void validateConfiguration() {
        if (!vpcs.enabled() || !config.services().ec2().awsFaithfulPrivateIp()
                || !config.network().securityGroupEnforcement().enabled()) {
            throw new AwsException("UnsupportedOperation",
                    "VPC packet routing requires real VPC networks, native private IPs and security-group enforcement.",
                    400);
        }
    }

    public boolean hasInternetGateway(String region, String vpc) {
        return ec2.describeInternetGateways(region, List.of(), Map.of()).stream()
                .anyMatch(gateway -> gateway.getAttachments().stream().anyMatch(attachment ->
                        vpc.equals(attachment.getVpcId()) && "available".equals(attachment.getState())));
    }

    /** Called while the namespace still has its initial security-group deny policy. */
    public synchronized void register(SecurityGroupNftCompiler.Endpoint identity, String helper,
                                      List<String> resolvers) {
        if (!enabled()) {
            return;
        }
        requireActive();
        validateConfiguration();
        RequestScopes.runAs(identity.accountId(), identity.region(), () -> {
            NetworkInterface eni = ec2.describeNetworkInterfaces(identity.accountId(), identity.region(),
                    List.of(identity.eniId()), Map.of()).networkInterfaces().getFirst();
            if (!identity.vpcId().equals(eni.getVpcId())
                    || !identity.logicalAddress().equals(eni.getPrivateIpAddress())) {
                throw new IllegalStateException("Protected namespace does not match its native network interface");
            }
            String device = deviceFor(helper, identity.logicalAddress());
            Endpoint endpoint = new Endpoint(identity, eni.getSubnetId(), helper, device, List.copyOf(resolvers));
            endpoints.put(key(identity.accountId(), identity.region(), identity.eniId()), endpoint);
            run(helper, VpcRoutingCommands.endpointGuard());
            reconcile();
        });
    }

    /** The owning service removes the namespace before releasing its native interface. */
    public synchronized void unregister(String account, String region, String eni) {
        if (!enabled()) {
            return;
        }
        String key = key(account, region, eni);
        Endpoint endpoint = endpoints.get(key);
        PublicAddress address = publicAddresses.get(key);
        if (address != null) {
            address.namespace.requireUnused(docker);
            if (exists(address.namespace.id())) {
                throw new IllegalStateException("Public namespace must retire before its address is released");
            }
        }
        if (endpoint != null) {
            detachPublicInstance(endpoint);
        }
        endpoints.remove(key, endpoint);
        publicAddresses.remove(key, address);
        reconcile();
        removeEmptyPublicNetwork();
    }

    public synchronized void unregister(String eni) {
        for (Endpoint endpoint : List.copyOf(endpoints.values())) {
            if (eni.equals(endpoint.identity.eniId())) {
                unregister(endpoint.identity.accountId(), endpoint.identity.region(), eni);
            }
        }
    }

    public synchronized void attachPublicInstance(SecurityGroupNftCompiler.Endpoint identity, String helper,
                                                  String publicIp) {
        if (!enabled()) {
            return;
        }
        requireActive();
        validateConfiguration();
        Endpoint endpoint = endpoints.get(key(identity.accountId(), identity.region(), identity.eniId()));
        if (endpoint == null || !helper.equals(endpoint.helper)
                || !identity.vpcId().equals(endpoint.identity.vpcId())
                || !identity.logicalAddress().equals(endpoint.identity.logicalAddress())) {
            throw new IllegalStateException("Public EC2 address has no matching protected interface");
        }
        if (publicIp == null || "127.0.0.1".equals(publicIp)) {
            detachPublicInstance(endpoint);
            reconcile();
            removeEmptyPublicNetwork();
            return;
        }
        if (publicIp.equals(identity.logicalAddress())) {
            throw new IllegalStateException("Public EC2 address must differ from its private address");
        }
        List<ContainerNetwork> matches = docker.inspectContainerCmd(helper).exec()
                .getNetworkSettings().getNetworks().values().stream()
                .filter(network -> publicIp.equals(network.getIpAddress())).toList();
        if (matches.size() != 1 || matches.getFirst().getNetworkID() == null
                || matches.getFirst().getNetworkID().isBlank()) {
            throw new IllegalStateException("Public EC2 address must identify one native Docker network");
        }
        if (endpoint.publicAddress != null) {
            if (!publicIp.equals(endpoint.publicAddress.address())) {
                throw new IllegalStateException("Public EC2 address conflicts with its protected interface");
            }
            if (endpoint.publicAddress.transport() != null) {
                reconcile();
                return;
            }
            detachPublicInstance(endpoint);
        }
        // Use the same transport as NAT gateways; Docker control bridges cannot carry every routed source.
        String transportNetwork = publicNetwork();
        endpoint.publicAddress = new NativePublicAddress(VpcRouteSelection.ipv4(publicIp),
                transportNetwork, null, null, true);
        try {
            if (docker.inspectContainerCmd(helper).exec().getNetworkSettings().getNetworks().values().stream()
                    .noneMatch(value -> transportNetwork.equals(value.getNetworkID()))) {
                docker.connectToNetworkCmd().withNetworkId(transportNetwork).withContainerId(helper).exec();
            }
            String transport = networkAddress(helper, transportNetwork);
            endpoint.publicAddress = new NativePublicAddress(publicIp, transportNetwork, deviceFor(helper, transport),
                    transport, true);
            reconcile();
        } catch (RuntimeException failure) {
            try {
                detachPublicInstance(endpoint);
                reconcile();
                removeEmptyPublicNetwork();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void detachPublicInstance(Endpoint endpoint) {
        NativePublicAddress address = endpoint.publicAddress;
        if (address != null && address.separateTransport() && exists(endpoint.helper)) {
            run(endpoint.helper, VpcRoutingCommands.endpointGuard());
            endpoint.applied = null;
            run(endpoint.helper, VpcRoutingCommands.publicInstanceSource(null, null, null));
            boolean connected = docker.inspectContainerCmd(endpoint.helper).exec().getNetworkSettings()
                    .getNetworks().values().stream().anyMatch(value -> address.network().equals(value.getNetworkID()));
            if (connected) {
                docker.disconnectFromNetworkCmd().withNetworkId(address.network())
                        .withContainerId(endpoint.helper).exec();
            }
        }
        endpoint.publicAddress = null;
    }

    public synchronized String attachPublicFrontend(String account, String region, NetworkInterface eni,
                                                   String helper) {
        if (!enabled()) {
            throw new AwsException("UnsupportedOperation", "Public protected frontends require VPC packet routing.", 400);
        }
        requireActive();
        validateConfiguration();
        String key = key(account, region, eni.getNetworkInterfaceId());
        if (publicAddresses.containsKey(key)) {
            throw new IllegalStateException("This interface already owns a public address");
        }
        String publicIp = allocateFrontendAddress();
        NetworkNamespaceLease namespace = namespace(helper);
        PublicAddress address = new PublicAddress(publicIp, namespace);
        publicAddresses.put(key, address);
        String network = publicNetwork();
        docker.connectToNetworkCmd().withNetworkId(network).withContainerId(helper).exec();
        address.transport = networkAddress(helper, network);
        address.device = deviceFor(helper, address.transport);
        run(helper, VpcRoutingCommands.publicAddress(publicIp, eni.getPrivateIpAddress(), address.device));
        return publicIp;
    }

    public synchronized Optional<String> publicAddress(String account, String region, String eni) {
        PublicAddress address = publicAddresses.get(key(account, region, eni));
        return address == null ? Optional.empty() : Optional.of(address.address);
    }

    /** Create holds the native EIP and private address before attaching the runtime. */
    public synchronized void createNat(String account, String region, NatGateway gateway) {
        if (!enabled()) {
            return;
        }
        requireActive();
        validateConfiguration();
        String key = key(account, region, gateway.getNatGatewayId());
        if (nats.containsKey(key)) {
            return;
        }
        if (!"public".equals(gateway.getConnectivityType()) || gateway.getNatGatewayAddresses().size() != 1) {
            throw new AwsException("UnsupportedOperation", "Packet routing supports single-address public NAT gateways.",
                    400);
        }
        NatGatewayAddress address = gateway.getNatGatewayAddresses().getFirst();
        String privateIp = VpcRouteSelection.ipv4(address.getPrivateIp());
        String publicIp = VpcRouteSelection.ipv4(address.getPublicIp());
        // Ec2Service already reserves this address through its native NAT declaration
        // and, for automatically assigned addresses, its VPC allocation lease.
        NatRuntime runtime = new NatRuntime(account, region, gateway.getVpcId(),
                gateway.getSubnetId(), privateIp, publicIp);
        nats.put(key, runtime);
        try {
            LocallyBuiltHelperImage.ensureBuilt(docker, containers,
                    config.network().securityGroupEnforcement().helperImage(), "floci/network-helper:local",
                    "/docker/network-helper.Dockerfile");
            String network = publicNetwork();
            ContainerSpec spec = containers.newContainer(config.network().securityGroupEnforcement().helperImage())
                    .withName(ContainerStorageHelper.resourceName(config, NAT_SERVICE, null,
                            gateway.getNatGatewayId() + "-" + UUID.randomUUID()))
                    .withDockerNetwork(Optional.of(network)).withCapAdd("NET_ADMIN")
                    .withNetworkSysctl("net.ipv4.ip_forward", "1")
                    .withNetworkSysctl("net.ipv4.conf.all.rp_filter", "0")
                    .withNetworkSysctl("net.ipv4.conf.default.rp_filter", "0")
                    .withEntrypoint(List.of("sh", "-c")).withCmd(List.of("exec sleep 2147483647"))
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                            NAT_SERVICE, gateway.getNatGatewayId(), account, region))
                    .withLabels(Map.of(ContainerStorageHelper.OWNER_LABEL, owner()))
                    .build();
            runtime.namespace = namespace(lifecycle.createAndStart(spec).containerId());
            run(runtime.namespace.id(), VpcRoutingCommands.natGuard());
            if (vpcs.attach(region, gateway.getVpcId(), gateway.getSubnetId(),
                    runtime.namespace.id(), privateIp).isEmpty()) {
                throw new IllegalStateException("Cannot attach the NAT gateway at its native private address");
            }
            runtime.transport = networkAddress(runtime.namespace.id(), network);
            runtime.privateDevice = deviceFor(runtime.namespace.id(), privateIp);
            runtime.publicDevice = deviceFor(runtime.namespace.id(), runtime.transport);
        } catch (RuntimeException failure) {
            try {
                retireNat(runtime);
                nats.remove(key, runtime);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** A failed retirement keeps the runtime record and the native allocation for a later retry. */
    public synchronized void prepareNatDeletion(String account, String region, String id) {
        NatRuntime runtime = nats.get(key(account, region, id));
        if (runtime != null) {
            runtime.retiring = true;
            retireNat(runtime);
        }
    }

    public synchronized void natDeleted(String account, String region, String id) {
        nats.remove(key(account, region, id));
        reconcile();
        removeEmptyPublicNetwork();
    }

    /** Also called for internet-gateway attachment and NAT lifecycle changes. */
    public synchronized void reconcile() {
        if (!enabled() || draining) {
            return;
        }
        try {
            for (Endpoint endpoint : List.copyOf(endpoints.values())) {
                RequestScopes.runAs(endpoint.identity.accountId(), endpoint.identity.region(), () -> {
                    Topology topology = topology(endpoint.identity.region());
                    for (NatGateway gateway : topology.nats) {
                        if ("available".equals(gateway.getState()) && "public".equals(gateway.getConnectivityType())
                                && endpoint.identity.vpcId().equals(gateway.getVpcId())) {
                            createNat(endpoint.identity.accountId(), endpoint.identity.region(), gateway);
                        }
                    }
                });
            }
            configureAwsApi();
            for (NatRuntime nat : List.copyOf(nats.values())) {
                if (!nat.retiring && nat.namespace != null) {
                    RequestScopes.runAs(nat.account, nat.region, () -> configureNat(nat, topology(nat.region)));
                }
            }
            for (Endpoint endpoint : List.copyOf(endpoints.values())) {
                RequestScopes.runAs(endpoint.identity.accountId(), endpoint.identity.region(),
                        () -> configureEndpoint(endpoint, topology(endpoint.identity.region())));
            }
        } catch (RuntimeException failure) {
            for (Endpoint endpoint : endpoints.values()) {
                endpoint.applied = null;
                quarantine(endpoint.helper, false, failure);
            }
            for (NatRuntime nat : nats.values()) {
                nat.applied = null;
                if (nat.namespace != null) {
                    quarantine(nat.namespace.id(), true, failure);
                }
            }
            throw failure;
        }
    }

    private void configureNat(NatRuntime nat, Topology topology) {
        List<VpcRouteSelection.Entry> routes = routes(nat.region, nat.vpc, nat.subnet, topology);
        List<VpcRoutingCommands.Hop> destinations = new ArrayList<>();
        String apiAddress = EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS;
        if (awsApi.transport() != null && internet(apiAddress, routes, nat.vpc, topology)) {
            destinations.add(new VpcRoutingCommands.Hop(apiAddress, awsApi.transport(), nat.publicDevice, false));
        }
        for (PublicAddress address : publicAddresses.values()) {
            if (address.transport != null && internet(address.address, routes, nat.vpc, topology)) {
                destinations.add(new VpcRoutingCommands.Hop(address.address, address.transport,
                        nat.publicDevice, false));
            }
        }
        for (Endpoint endpoint : endpoints.values()) {
            NativePublicAddress address = endpoint.publicAddress;
            if (address != null && address.transport() != null && publicNetwork.equals(address.network())
                    && internet(address.address(), routes, nat.vpc, topology)) {
                destinations.add(new VpcRoutingCommands.Hop(address.address(), address.transport(),
                        nat.publicDevice, false));
            }
        }
        String script = VpcRoutingCommands.nat(nat.publicIp, vpcCidr(nat.region, nat.vpc), nat.privateDevice,
                nat.publicDevice, destinations);
        if (!script.equals(nat.applied)) {
            run(nat.namespace.id(), VpcRoutingCommands.natGuard());
            nat.applied = null;
            run(nat.namespace.id(), script);
            nat.applied = script;
        }
    }

    private void configureEndpoint(Endpoint endpoint, Topology topology) {
        SecurityGroupNftCompiler.Endpoint identity = endpoint.identity;
        String account = identity.accountId();
        String region = identity.region();
        String vpc = identity.vpcId();
        List<VpcRouteSelection.Entry> routes = routes(region, vpc, endpoint.subnet, topology);
        PublicAddress own = publicAddresses.get(key(account, region, identity.eniId()));
        List<VpcRoutingCommands.Hop> hops = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        for (PublicAddress destination : publicAddresses.values()) {
            if (destination.transport == null || destination == own) {
                continue;
            }
            int previous = hops.size();
            addDestination(endpoint, topology, routes, own, destination.address, destination.transport, hops);
            if (hops.size() == previous) {
                denied.add(destination.address);
            }
        }
        NativePublicAddress nativeAddress = endpoint.publicAddress;
        for (Endpoint peer : endpoints.values()) {
            NativePublicAddress destination = peer.publicAddress;
            if (peer == endpoint || destination == null) {
                continue;
            }
            int previous = hops.size();
            if (destination.transport() != null) {
                addDestination(endpoint, topology, routes, own,
                        destination.address(), destination.transport(), hops);
            }
            if (hops.size() == previous && nativeAddress != null && destination.transport() != null
                    && nativeAddress.network().equals(destination.network())
                    && internet(destination.address(), routes, vpc, topology)) {
                hops.add(new VpcRoutingCommands.Hop(destination.address(), destination.transport(),
                        nativeAddress.device(), false));
            }
            if (hops.size() == previous) {
                denied.add(destination.address());
            }
        }
        if (awsApi.enabled()) {
            String apiAddress = EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS;
            int previous = hops.size();
            if (awsApi.transport() != null && endpoint.publicAddress != null
                    && internet(apiAddress, routes, vpc, topology)) {
                NativePublicAddress address = endpoint.publicAddress;
                VpcAwsApiBridge.NetworkEndpoint api = awsApi.onNetwork(address.network());
                hops.add(new VpcRoutingCommands.Hop(apiAddress, api.address(), address.device(), false));
            } else if (awsApi.transport() != null) {
                addDestination(endpoint, topology, routes, null, apiAddress, awsApi.transport(), hops);
            }
            if (hops.size() == previous) {
                denied.add(apiAddress);
            }
        }
        List<String> inbound = new ArrayList<>();
        String returnDevice = own != null ? own.device : nativeAddress == null ? null : nativeAddress.device();
        if (returnDevice != null) {
            for (NatRuntime nat : nats.values()) {
                if (!nat.retiring && nat.transport != null && internet(nat.publicIp, routes, vpc, topology)) {
                    hops.add(new VpcRoutingCommands.Hop(nat.publicIp, nat.transport, returnDevice, true));
                    inbound.add(nat.publicIp);
                }
            }
        }
        Cidr4 vpcCidr = vpcCidr(region, vpc);
        List<VpcRoutingCommands.LocalRoute> local = routes.stream().map(route -> new VpcRoutingCommands.LocalRoute(
                route.destination(), VpcRouteSelection.local(route.route()) && vpcCidr.contains(route.destination())))
                .toList();
        String script = VpcRoutingCommands.endpoint(identity.logicalAddress(), endpoint.privateDevice,
                local, hops, denied, endpoint.resolvers, identity.controlConnections(),
                identity.instanceMetadataAllowed(), own == null ? null : own.device, inbound,
                identity.controlListeners());
        if (endpoint.publicAddress != null && endpoint.publicAddress.separateTransport()) {
            NativePublicAddress address = endpoint.publicAddress;
            script += VpcRoutingCommands.publicInstanceSource(identity.logicalAddress(), address.address(),
                    address.device(), hops.stream().filter(hop -> address.device().equals(hop.device()))
                            .filter(hop -> !hop.replyOnly()).map(VpcRoutingCommands.Hop::destination).toList());
        }
        if (!script.equals(endpoint.applied)) {
            // Resolver access is independent of VPC routes. Keep it available while
            // withdrawing peer traffic before changing kernel routes and source NAT.
            run(endpoint.helper, VpcRoutingCommands.endpointGuard(endpoint.resolvers));
            endpoint.applied = null;
            run(endpoint.helper, script);
            endpoint.applied = script;
        }
    }

    private void addDestination(Endpoint endpoint, Topology topology, List<VpcRouteSelection.Entry> routes,
                                PublicAddress own, String address, String transport,
                                List<VpcRoutingCommands.Hop> hops) {
        SecurityGroupNftCompiler.Endpoint identity = endpoint.identity;
        Route route = VpcRouteSelection.to(address, routes).orElse(null);
        Optional<NatGateway> gateway = VpcRouteSelection.nat(route, identity.vpcId(), topology.nats);
        NatRuntime nat = gateway.map(value -> nats.get(key(identity.accountId(), identity.region(),
                value.getNatGatewayId()))).orElse(null);
        if (nat != null && !nat.retiring && nat.namespace != null
                && internet(address, routes(nat.region, nat.vpc, nat.subnet, topology), nat.vpc, topology)) {
            hops.add(new VpcRoutingCommands.Hop(address, nat.privateIp, endpoint.privateDevice, false));
        } else if (own != null && VpcRouteSelection.internet(route, identity.vpcId(), topology.gateways)) {
            hops.add(new VpcRoutingCommands.Hop(address, transport, own.device, false));
        }
    }

    private void configureAwsApi() {
        if (!awsApi.enabled()) {
            return;
        }
        if (nats.isEmpty() && !hasPublicInstances()) {
            awsApi.retire();
            return;
        }
        awsApi.ensure(publicNetwork());
        List<VpcRoutingCommands.Hop> callers = new ArrayList<>();
        for (NatRuntime nat : nats.values()) {
            if (!nat.retiring && nat.transport != null) {
                RequestScopes.runAs(nat.account, nat.region, () -> {
                    Topology topology = topology(nat.region);
                    if (internet(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS,
                            routes(nat.region, nat.vpc, nat.subnet, topology), nat.vpc, topology)) {
                        callers.add(new VpcRoutingCommands.Hop(nat.publicIp, nat.transport, awsApi.device(), true));
                    }
                });
            }
        }
        for (Endpoint endpoint : endpoints.values()) {
            NativePublicAddress address = endpoint.publicAddress;
            if (address != null) {
                if (address.transport() == null) {
                    throw new IllegalStateException("Public EC2 transport attachment has not finished");
                }
                SecurityGroupNftCompiler.Endpoint identity = endpoint.identity;
                RequestScopes.runAs(identity.accountId(), identity.region(), () -> {
                    Topology topology = topology(identity.region());
                    if (internet(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS,
                            routes(identity.region(), identity.vpcId(), endpoint.subnet, topology),
                            identity.vpcId(), topology)) {
                        VpcAwsApiBridge.NetworkEndpoint api = awsApi.onNetwork(address.network());
                        callers.add(new VpcRoutingCommands.Hop(address.address(), address.transport(),
                                api.device(), true));
                    }
                });
            }
        }
        awsApi.configure(callers);
    }

    private boolean hasPublicInstances() {
        return endpoints.values().stream().anyMatch(endpoint -> endpoint.publicAddress != null);
    }

    private List<VpcRouteSelection.Entry> routes(String region, String vpc, String subnet, Topology topology) {
        return VpcRouteSelection.entries(vpc, subnet, topology.tables, prefix ->
                ec2.getManagedPrefixListEntries(region, prefix, null).stream().map(PrefixListEntry::getCidr).toList());
    }

    private boolean internet(String destination, List<VpcRouteSelection.Entry> routes, String vpc, Topology topology) {
        return VpcRouteSelection.internet(VpcRouteSelection.to(destination, routes).orElse(null), vpc, topology.gateways);
    }

    private Topology topology(String region) {
        return new Topology(ec2.describeRouteTables(region, List.of(), Map.of()),
                ec2.describeNatGateways(region, List.of(), Map.of()),
                ec2.describeInternetGateways(region, List.of(), Map.of()));
    }

    private Cidr4 vpcCidr(String region, String vpc) {
        return VpcRouteSelection.cidr(vpcs.effectiveVpcCidr(region, vpc)
                .orElseThrow(() -> new IllegalStateException("Native VPC has no packet network")));
    }

    private String publicNetwork() {
        if (publicNetwork != null) {
            return publicNetwork;
        }
        String name = ContainerStorageHelper.resourceName(config, COMPONENT, null, "network");
        try {
            Network existing = docker.inspectNetworkCmd().withNetworkId(name).exec();
            if (!owner().equals(existing.getLabels().get(ContainerStorageHelper.OWNER_LABEL))
                    || !COMPONENT.equals(existing.getLabels().get(ContainerStorageHelper.COMPONENT_LABEL))
                    || Boolean.TRUE.equals(existing.getInternal())
                    || existing.getOptions() == null
                    || !"false".equals(existing.getOptions().get("com.docker.network.bridge.enable_ip_masquerade"))) {
                throw new IllegalStateException("Public transport name belongs to a different network");
            }
            publicNetwork = existing.getId();
        } catch (NotFoundException absent) {
            // Docker's internal bridge drops packets whose native public source or destination
            // is outside its transport subnet. Namespace filters and explicit routes own isolation.
            publicNetwork = docker.createNetworkCmd().withName(name).withInternal(false)
                    .withOptions(Map.of("com.docker.network.bridge.enable_ip_masquerade", "false"))
                    .withLabels(Map.of(ContainerStorageHelper.OWNER_LABEL, owner(),
                            ContainerStorageHelper.COMPONENT_LABEL, COMPONENT))
                    .exec().getId();
        }
        return publicNetwork;
    }

    private String allocateFrontendAddress() {
        Set<String> allocated = publicAddresses.values().stream().map(address -> address.address)
                .collect(Collectors.toSet());
        for (int host = 1; host <= 254; host++) {
            String candidate = "198.51.100." + host;
            if (!allocated.contains(candidate)) {
                return candidate;
            }
        }
        throw new AwsException("TooManyLoadBalancers", "The managed public IPv4 address pool is exhausted.", 400);
    }

    private String networkAddress(String helper, String network) {
        return docker.inspectContainerCmd(helper).exec().getNetworkSettings().getNetworks().values().stream()
                .filter(value -> network.equals(value.getNetworkID())).map(ContainerNetwork::getIpAddress)
                .filter(value -> value != null && !value.isBlank()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Public transport attachment has no address"));
    }

    private String deviceFor(String helper, String address) {
        String literal = VpcRouteSelection.ipv4(address);
        ContainerExec.Result result = ContainerExec.run(docker, helper, new String[]{"ip", "-j", "-4", "address"}, 10)
                .throwIfTimedOut(helper);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Cannot inspect namespace addresses: " + result.summary());
        }
        try {
            List<String> matches = new ArrayList<>();
            for (JsonNode device : mapper.readTree(result.stdout())) {
                for (JsonNode value : device.path("addr_info")) {
                    if (literal.equals(value.path("local").asText())) {
                        matches.add(VpcRoutingCommands.device(device.path("ifname").asText()));
                    }
                }
            }
            if (matches.size() != 1) {
                throw new IllegalStateException("Native address is absent or ambiguous inside its namespace: " + literal);
            }
            return matches.getFirst();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot decode namespace addresses", failure);
        }
    }

    private NetworkNamespaceLease namespace(String helper) {
        InspectContainerResponse inspection = docker.inspectContainerCmd(helper).exec();
        return new NetworkNamespaceLease(helper, inspection.getName());
    }

    private void retireNat(NatRuntime runtime) {
        runtime.retiring = true;
        if (runtime.namespace != null) {
            if (running(runtime.namespace.id())) {
                run(runtime.namespace.id(), VpcRoutingCommands.natGuard());
            }
            runtime.applied = null;
            runtime.namespace.requireUnused(docker);
            lifecycle.removeIfExistsStrict(runtime.namespace.id());
        }
        // The native owner releases the address only after this retirement succeeds.
    }

    private boolean exists(String id) {
        try {
            docker.inspectContainerCmd(id).exec();
            return true;
        } catch (NotFoundException absent) {
            return false;
        }
    }

    private boolean running(String id) {
        try {
            return Boolean.TRUE.equals(docker.inspectContainerCmd(id).exec().getState().getRunning());
        } catch (NotFoundException absent) {
            return false;
        }
    }

    private void quarantine(String helper, boolean nat, RuntimeException failure) {
        try {
            run(helper, nat ? VpcRoutingCommands.natGuard() : VpcRoutingCommands.endpointGuard());
        } catch (RuntimeException guardFailure) {
            failure.addSuppressed(guardFailure);
            try {
                docker.stopContainerCmd(helper).withTimeout(0).exec();
            } catch (RuntimeException stopFailure) {
                failure.addSuppressed(stopFailure);
                LOG.errorv(stopFailure, "Could not quarantine VPC namespace {0}", helper);
            }
        }
    }

    private void run(String helper, String script) {
        ContainerExec.Result result = ContainerExec.run(docker, helper, new String[]{"sh", "-c", script}, 20)
                .throwIfTimedOut(helper);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Cannot reconcile native VPC routing: " + result.summary());
        }
    }

    private void removeEmptyPublicNetwork() {
        if (publicNetwork == null || !nats.isEmpty() || !publicAddresses.isEmpty() || hasPublicInstances()) {
            return;
        }
        awsApi.retire();
        try {
            Network network = docker.inspectNetworkCmd().withNetworkId(publicNetwork).exec();
            if (network.getContainers() != null && !network.getContainers().isEmpty()) {
                return;
            }
            docker.removeNetworkCmd(publicNetwork).exec();
            publicNetwork = null;
        } catch (NotFoundException absent) {
            publicNetwork = null;
        }
    }

    @PreDestroy
    void close() {
        stopManagedContainers();
    }

    @Override
    public synchronized void beginTeardown() {
        draining = true;
    }

    @Override
    public synchronized void stopManagedContainers() {
        beginTeardown();
        for (Map.Entry<String, NatRuntime> entry : List.copyOf(nats.entrySet())) {
            try {
                retireNat(entry.getValue());
                nats.remove(entry.getKey(), entry.getValue());
            } catch (RuntimeException failure) {
                LOG.errorv(failure, "NAT namespace remains reserved during shutdown: {0}", entry.getKey());
            }
        }
        try {
            awsApi.retire();
        } catch (RuntimeException failure) {
            LOG.errorv(failure, "Public AWS API bridge remains reserved during shutdown");
        }
        removeEmptyPublicNetwork();
    }

    @Override
    public synchronized void beforeReset() {
        beginTeardown();
    }

    @Override
    public synchronized void clear() {
        removeEmptyPublicNetwork();
        if (!endpoints.isEmpty() || !nats.isEmpty() || !publicAddresses.isEmpty() || publicNetwork != null) {
            throw new IllegalStateException("VPC packet resources have not finished retirement");
        }
    }

    @Override
    public synchronized void afterReset() {
        draining = false;
    }

    private void requireActive() {
        if (draining) {
            throw new IllegalStateException("VPC packet routing is shutting down or resetting");
        }
    }

    private String owner() {
        return ContainerStorageHelper.ownerIdentity(config);
    }

    private String key(String account, String region, String id) {
        return account + "/" + region + "/" + id;
    }

    private record Topology(List<RouteTable> tables, List<NatGateway> nats, List<InternetGateway> gateways) {}

    private record NativePublicAddress(String address, String network, String device, String transport,
                                       boolean separateTransport) {}

    private static final class Endpoint {
        final SecurityGroupNftCompiler.Endpoint identity;
        final String subnet;
        final String helper;
        final String privateDevice;
        final List<String> resolvers;
        String applied;
        NativePublicAddress publicAddress;

        Endpoint(SecurityGroupNftCompiler.Endpoint identity, String subnet, String helper,
                 String privateDevice, List<String> resolvers) {
            this.identity = identity;
            this.subnet = subnet;
            this.helper = helper;
            this.privateDevice = privateDevice;
            this.resolvers = resolvers;
        }
    }

    private static final class PublicAddress {
        final String address;
        final NetworkNamespaceLease namespace;
        String transport;
        String device;

        PublicAddress(String address, NetworkNamespaceLease namespace) {
            this.address = address;
            this.namespace = namespace;
        }
    }

    private static final class NatRuntime {
        final String account;
        final String region;
        final String vpc;
        final String subnet;
        final String privateIp;
        final String publicIp;
        NetworkNamespaceLease namespace;
        String transport;
        String privateDevice;
        String publicDevice;
        String applied;
        boolean retiring;

        NatRuntime(String account, String region, String vpc, String subnet, String privateIp, String publicIp) {
            this.account = account;
            this.region = region;
            this.vpc = vpc;
            this.subnet = subnet;
            this.privateIp = privateIp;
            this.publicIp = publicIp;
        }
    }
}
