package io.github.hectorvent.floci.services.ec2.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.InternetGatewayAttachment;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterfaceListResult;
import io.github.hectorvent.floci.services.ec2.model.Route;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.RouteTableAssociation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VpcPublicPeerRoutingTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final VpcNetworkManager vpcs = mock(VpcNetworkManager.class);
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final ContainerBuilder containers = mock(ContainerBuilder.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
    private final VpcRoutingManager manager = new VpcRoutingManager(
            config, ec2, vpcs, docker, containers, lifecycle, JSON, endpoint);
    private final Map<String, List<String>> scripts = new LinkedHashMap<>();
    private final List<Route> routes = new ArrayList<>();
    private final InternetGateway gateway = new InternetGateway();

    @BeforeEach
    void prepareNativeTopology() {
        when(config.network().vpcRouting().enabled()).thenReturn(true);
        // EC2 peer routing must not depend on whether the optional AWS API bridge runs.
        when(config.network().vpcRouting().publicAwsApi()).thenReturn(false);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("public-peers"));
        when(vpcs.enabled()).thenReturn(true);
        when(vpcs.effectiveVpcCidr(REGION, "vpc")).thenReturn(Optional.of("10.0.0.0/16"));
        routes.add(route("10.0.0.0/16", "local", "active"));
        routes.add(route("0.0.0.0/0", "igw", "active"));
        RouteTable table = new RouteTable();
        table.setVpcId("vpc");
        table.setRoutes(routes);
        RouteTableAssociation main = new RouteTableAssociation();
        main.setMain(true);
        main.setAssociationState("associated");
        table.setAssociations(List.of(main));
        when(ec2.describeRouteTables(REGION, List.of(), Map.of())).thenReturn(List.of(table));
        when(ec2.describeNatGateways(REGION, List.of(), Map.of())).thenReturn(List.of());
        gateway.setInternetGatewayId("igw");
        InternetGatewayAttachment attached = new InternetGatewayAttachment();
        attached.setVpcId("vpc");
        attached.setState("available");
        gateway.setAttachments(List.of(attached));
        when(ec2.describeInternetGateways(REGION, List.of(), Map.of())).thenReturn(List.of(gateway));

        Network publicNetwork = mock(Network.class);
        when(publicNetwork.getId()).thenReturn("public-network");
        String owner = ContainerStorageHelper.ownerIdentity(config);
        when(publicNetwork.getLabels()).thenReturn(Map.of(
                ContainerStorageHelper.OWNER_LABEL, owner,
                ContainerStorageHelper.COMPONENT_LABEL, "vpc-public-transport"));
        when(publicNetwork.getOptions()).thenReturn(Map.of("com.docker.network.bridge.enable_ip_masquerade", "false"));
        String name = ContainerStorageHelper.resourceName(config, "vpc-public-transport", null, "network");
        when(docker.inspectNetworkCmd().withNetworkId(name).exec()).thenReturn(publicNetwork);
        when(docker.inspectNetworkCmd().withNetworkId("public-network").exec()).thenReturn(publicNetwork);
        when(publicNetwork.getContainers()).thenReturn(Map.of());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicPeersFollowInternetRoutesWithTheirOwnSourceIdentity(boolean internalControlNetwork) {
        admit("first", 2, internalControlNetwork);
        admit("second", 3, internalControlNetwork);

        String device = "eth2";
        String nextHop = "240.0.0.3";
        assertTrue(latest("first").contains(
                "ip -4 route replace 172.18.0.3/32 via " + nextHop + " dev " + device + " src 10.0.2.10"));
        assertTrue(latest("first").contains(
                "egress ip daddr 172.18.0.3 oifname \"" + device + "\" counter accept"));
        assertTrue(latest("first").contains(
                "ip saddr 10.0.2.10 ip daddr 172.18.0.3 snat to 172.18.0.2"));
        assertFalse(latest("first").contains("floci_sg"),
                "Native route admission must leave security-group enforcement to its existing owner");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicApiUsesTheNativeSourceNetworkUnlessItIsInternal(boolean internalControlNetwork) {
        enablePublicApi();
        admit("first", 2, internalControlNetwork);
        admit("second", 3, internalControlNetwork);

        String callerDevice = internalControlNetwork ? "eth2" : "eth0";
        String apiNextHop = internalControlNetwork ? "240.0.0.1" : "172.18.0.254";
        String bridgeDevice = internalControlNetwork ? "eth1" : "eth0";
        String returnGateway = internalControlNetwork ? "240.0.0.2" : "172.18.0.2";
        String installed = latest("first");
        assertTrue(installed.contains("ip -4 route replace 192.0.2.1/32 via " + apiNextHop
                + " dev " + callerDevice + " src 10.0.2.10"));
        assertTrue(installed.contains("egress ip daddr 192.0.2.1 oifname \"" + callerDevice + "\" counter accept"));
        assertTrue(installed.contains("outgoing oifname \"" + callerDevice
                + "\" ip saddr 10.0.2.10 ip daddr 192.0.2.1 snat to 172.18.0.2"));
        assertTrue(latest("aws-api").contains("ip -4 route replace 172.18.0.2/32 via "
                + returnGateway + " dev " + bridgeDevice + " src 192.0.2.1 proto 186"));
        assertTrue(latest("aws-api").contains("ingress iifname \"" + bridgeDevice
                + "\" ip saddr 172.18.0.2 ip daddr 192.0.2.1 tcp dport { 80, 443 } counter accept"));
        assertTrue(installed.contains("ip -4 route replace 172.18.0.3/32 via 240.0.0.3 dev eth2"));
        assertTrue(installed.contains("outgoing oifname \"eth2\" ip saddr 10.0.2.10"
                + " ip daddr 172.18.0.3 snat to 172.18.0.2"));
        assertFalse(installed.contains("floci_sg"));
        assertFalse(installed.contains("snat to 240.0.0.2"));
        assertEquals(Set.of("172.18.0.2", "172.18.0.3"), manager.publicInstanceAddresses());
    }

    @Test
    void publicApiBlackholeWithdrawsItsSourceTranslationAndBridgeCaller() {
        enablePublicApi();
        admit("first", 2, false);
        assertTrue(latest("aws-api").contains("ip saddr 172.18.0.2 ip daddr 192.0.2.1"));
        routes.add(0, route("192.0.2.1/32", "igw", "blackhole"));

        manager.reconcile();

        assertFalse(latest("aws-api").contains("ip saddr 172.18.0.2"));
        assertFalse(latest("aws-api").contains("route replace 172.18.0.2/32"));
        assertTrue(latest("first").contains("egress ip daddr 192.0.2.1 counter drop"));
        assertFalse(latest("first").contains("ip daddr 192.0.2.1 snat"));
        assertFalse(latest("first").contains("via 172.18.0.254"));
    }

    @Test
    void moreSpecificBlackholeWithdrawsThePeerRouteAndSourceTranslation() {
        admit("first", 2, true);
        admit("second", 3, true);
        assertTrue(latest("first").contains("via 240.0.0.3"));

        routes.add(0, route("172.18.0.3/32", "igw", "blackhole"));
        manager.reconcile();

        assertFalse(latest("first").contains("via 240.0.0.3"));
        assertTrue(latest("first").contains("egress ip daddr 172.18.0.3 counter drop"));
        assertFalse(latest("first").contains("ip daddr 172.18.0.3 snat"));
    }

    @Test
    void detachingTheGatewayWithdrawsBothDirections() {
        admit("first", 2, true);
        admit("second", 3, true);
        assertTrue(latest("first").contains("via 240.0.0.3"));
        gateway.setAttachments(List.of());

        manager.reconcile();

        assertTrue(latest("first").contains("egress ip daddr 172.18.0.3 counter drop"));
        assertTrue(latest("second").contains("egress ip daddr 172.18.0.2 counter drop"));
        assertFalse(latest("first").contains("via 240.0.0.3"));
    }

    @Test
    void privateOnlyEndpointDoesNotGainAnInternetRouteFromItsPublicPeer() {
        prepareNamespace("first", 2, true);
        manager.register(identity("first", 2), "first", List.of());
        admit("second", 3, true);

        assertFalse(latest("first").contains("via 240.0.0.3"));
        assertTrue(latest("first").contains("egress ip daddr 172.18.0.3 counter drop"));
    }

    @Test
    void publicAddressPublicationEndsWhenTheInstanceLosesItsAddress() {
        admit("first", 2, true);
        admit("second", 3, true);
        assertEquals(Set.of("172.18.0.2", "172.18.0.3"), manager.publicInstanceAddresses());

        manager.attachPublicInstance(identity("second", 3), "second", null);

        assertEquals(Set.of("172.18.0.2"), manager.publicInstanceAddresses());
        assertFalse(latest("first").contains("via 240.0.0.3"));
        manager.beginTeardown();
        assertTrue(manager.publicInstanceAddresses().isEmpty());
    }

    @Test
    void peerChangesPreserveOnlyDeclaredResolverTrafficDuringRouteUpdates() {
        prepareNamespace("first", 2, true);
        manager.register(identity("first", 2), "first", List.of("172.18.0.1"));
        manager.attachPublicInstance(identity("first", 2), "first", "172.18.0.2");
        scripts.get("first").clear();

        admit("second", 3, true);

        List<String> updates = scripts.get("first");
        String guard = updates.get(updates.size() - 2);
        assertTrue(guard.contains("policy drop;"));
        assertEquals(1, guard.lines().filter(line -> line.startsWith("nft ")).count(),
                "Replacing the guard must install resolver permits in the same nft transaction");
        for (String protocol : List.of("udp", "tcp")) {
            assertTrue(guard.contains("egress ip daddr 172.18.0.1 " + protocol + " dport 53 counter accept"));
            assertTrue(guard.contains("ingress ip saddr 172.18.0.1 " + protocol
                    + " sport 53 ct state established ct direction reply counter accept"));
        }
        assertEquals(4, guard.lines().filter(line -> line.endsWith("counter accept")).count(),
                "The update guard must not admit peer or unsolicited inbound traffic");
        assertTrue(latest("first").contains("via 240.0.0.3"));
    }

    @Test
    void failedRoutingStillQuarantinesAllTrafficIncludingResolvers() {
        prepareNamespace("first", 2, true);
        manager.register(identity("first", 2), "first", List.of("172.18.0.1"));
        when(ec2.describeRouteTables(REGION, List.of(), Map.of()))
                .thenThrow(new IllegalStateException("Topology unavailable"));

        assertThrows(IllegalStateException.class, manager::reconcile);

        assertTrue(latest("first").contains("policy drop;"));
        assertFalse(latest("first").contains("accept"),
                "Failure quarantine must not reuse the temporary update guard");
    }

    @Test
    void missingPeerDoesNotBlockAnotherOwnersRetirementOrForgetItsOwnCleanup() {
        admit("first", 2, true);
        admit("second", 3, true);
        admit("third", 4, true);
        missing("second");

        assertDoesNotThrow(() -> manager.unregister(ACCOUNT, REGION, "first"));

        assertEquals(Set.of("172.18.0.4"), manager.publicInstanceAddresses());
        assertFalse(latest("third").contains("via 240.0.0.3"));
        assertTrue(latest("third").contains("egress ip daddr 172.18.0.3 counter drop"));
        verify(docker, never()).removeContainerCmd("second");
        manager.unregister(ACCOUNT, REGION, "third");
        assertThrows(IllegalStateException.class, manager::clear,
                "Missing namespace ownership remains until its service unregisters it");
        manager.unregister(ACCOUNT, REGION, "second");
        assertDoesNotThrow(manager::clear);
    }

    @Test
    void peerDisappearingDuringRouteInstallationIsWithdrawnInTheSameReconciliation() {
        admit("first", 2, true);
        admit("second", 3, true);
        gateway.setAttachments(List.of());
        InspectContainerCmd inspect = docker.inspectContainerCmd("second");
        when(docker.execCreateCmd("second")).thenAnswer(invocation -> {
            when(inspect.exec()).thenThrow(new NotFoundException("namespace absent"));
            throw new NotFoundException("namespace removed during retirement");
        });

        assertDoesNotThrow(manager::reconcile);

        assertEquals(Set.of("172.18.0.2"), manager.publicInstanceAddresses());
        assertTrue(latest("first").contains("egress ip daddr 172.18.0.3 counter drop"));
        assertFalse(latest("first").contains("via 240.0.0.3"));
        verify(docker, never()).stopContainerCmd("first");
    }

    @Test
    void missingExecForAnExistingNamespaceStillFailsAndQuarantinesSurvivors() {
        admit("first", 2, true);
        admit("second", 3, true);
        gateway.setAttachments(List.of());
        ExecCreateCmd ordinary = docker.execCreateCmd("second");
        AtomicBoolean once = new AtomicBoolean();
        NotFoundException failure = new NotFoundException("exec object absent, namespace still present");
        when(docker.execCreateCmd("second")).thenAnswer(invocation -> {
            if (once.compareAndSet(false, true)) {
                throw failure;
            }
            return ordinary;
        });

        assertSame(failure, assertThrows(NotFoundException.class, manager::reconcile));

        assertEquals(Set.of("172.18.0.2", "172.18.0.3"), manager.publicInstanceAddresses());
        assertFalse(latest("first").contains("accept"));
        assertFalse(latest("second").contains("accept"));
        assertDoesNotThrow(manager::reconcile);
    }

    @Test
    void inabilityToInspectAnAffectedNamespacePreservesTheOriginalFailure() {
        admit("first", 2, true);
        admit("second", 3, true);
        gateway.setAttachments(List.of());
        NotFoundException original = new NotFoundException("exec object absent");
        IllegalStateException inspectionFailure = new IllegalStateException("Docker inspection unavailable");
        ExecCreateCmd ordinary = docker.execCreateCmd("second");
        AtomicBoolean once = new AtomicBoolean();
        when(docker.execCreateCmd("second")).thenAnswer(invocation -> {
            if (once.compareAndSet(false, true)) {
                throw original;
            }
            return ordinary;
        });
        when(docker.inspectContainerCmd("second").exec()).thenThrow(inspectionFailure);

        assertSame(original, assertThrows(NotFoundException.class, manager::reconcile));

        assertTrue(List.of(original.getSuppressed()).contains(inspectionFailure));
        assertEquals(Set.of("172.18.0.2", "172.18.0.3"), manager.publicInstanceAddresses());
        assertFalse(latest("first").contains("accept"));
    }

    @Test
    void missingFrontendRetainsItsPublicAddressUntilItsOwnerChecksNamespaceUsers() {
        prepareNamespace("frontend", 2, true);
        manager.register(identity("frontend", 2), "frontend", List.of());
        String address = manager.attachPublicFrontend(ACCOUNT, REGION, interfaceFor("frontend", 2), "frontend");
        admit("peer", 3, true);
        missing("frontend");
        gateway.setAttachments(List.of());

        assertDoesNotThrow(manager::reconcile);

        assertEquals(Optional.of(address), manager.publicAddress(ACCOUNT, REGION, "frontend"));
        assertTrue(latest("peer").contains("egress ip daddr " + address + " counter drop"));
        prepareNamespace("replacement", 4, true);
        manager.register(identity("replacement", 4), "replacement", List.of());
        String replacement = manager.attachPublicFrontend(
                ACCOUNT, REGION, interfaceFor("replacement", 4), "replacement");
        assertNotEquals(address, replacement, "Missing namespace addresses are not free allocations");
        verify(docker, never()).removeContainerCmd("frontend");
        assertThrows(IllegalStateException.class, manager::clear);
        Container consumer = mock(Container.class, RETURNS_DEEP_STUBS);
        when(consumer.getId()).thenReturn("surviving-consumer");
        when(consumer.getHostConfig().getNetworkMode()).thenReturn("container:frontend");
        when(docker.listContainersCmd().withShowAll(true).exec()).thenReturn(List.of(consumer));
        IllegalStateException inUse = assertThrows(IllegalStateException.class,
                () -> manager.unregister(ACCOUNT, REGION, "frontend"));
        assertTrue(inUse.getMessage().contains("still has a consumer"));
        assertEquals(Optional.of(address), manager.publicAddress(ACCOUNT, REGION, "frontend"));
        when(docker.listContainersCmd().withShowAll(true).exec()).thenReturn(List.of());
        manager.unregister(ACCOUNT, REGION, "frontend");
        assertTrue(manager.publicAddress(ACCOUNT, REGION, "frontend").isEmpty());
    }

    private void missing(String helper) {
        InspectContainerCmd inspect = docker.inspectContainerCmd(helper);
        when(inspect.exec()).thenThrow(new NotFoundException("namespace absent"));
        when(docker.execCreateCmd(helper)).thenThrow(new NotFoundException("namespace absent"));
    }

    private void enablePublicApi() {
        when(config.network().vpcRouting().publicAwsApi()).thenReturn(true);
        when(config.network().securityGroupEnforcement().helperImage()).thenReturn("helper-image");
        when(endpoint.baseUrl(false)).thenReturn("http://172.18.0.1:4566");
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containers.newContainer("helper-image")).thenReturn(builder);
        when(lifecycle.createAndStart(any()))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("aws-api", Map.of()));
        when(docker.listContainersCmd().withShowAll(true).exec()).thenReturn(List.of());
        when(docker.inspectContainerCmd("aws-api").exec().getState().getRunning()).thenReturn(true);
        when(docker.inspectContainerCmd("aws-api").exec().getName()).thenReturn("/aws-api");
        when(docker.inspectContainerCmd("aws-api").exec().getNetworkSettings().getNetworks()).thenReturn(Map.of(
                "control", attachment("control-network", "172.18.0.254"),
                "public", attachment("public-network", "240.0.0.1")));
        ExecCreateCmd create = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn("api-exec");
        when(create.exec()).thenReturn(created);
        when(docker.execCreateCmd("aws-api")).thenReturn(create);
        AtomicReference<String[]> command = new AtomicReference<>();
        scripts.put("aws-api", new ArrayList<>());
        doAnswer(invocation -> {
            String[] argv = (String[]) invocation.getRawArguments()[0];
            command.set(argv);
            if (argv.length == 3 && "sh".equals(argv[0])) {
                scripts.get("aws-api").add(argv[2]);
            }
            return create;
        }).when(create).withCmd(any(String[].class));
        when(docker.inspectExecCmd("api-exec").exec().getExitCodeLong()).thenReturn(0L);
        when(docker.execStartCmd("api-exec").exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            String output = switch (command.get()[0]) {
                case "getent" -> "172.18.0.1 STREAM backend";
                case "ip" -> JSON.writeValueAsString(List.of(
                        Map.of("ifname", "eth0", "addr_info", List.of(Map.of("local", "172.18.0.254"))),
                        Map.of("ifname", "eth1", "addr_info", List.of(Map.of("local", "240.0.0.1")))));
                case "ss" -> "LISTEN 0 128 192.0.2.1:80 *:*\nLISTEN 0 128 192.0.2.1:443 *:*";
                default -> "";
            };
            callback.onNext(new Frame(StreamType.STDOUT, output.getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        });
    }

    private static NetworkInterface interfaceFor(String helper, int host) {
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId(helper);
        eni.setVpcId("vpc");
        eni.setSubnetId("subnet");
        eni.setPrivateIpAddress("10.0." + host + ".10");
        return eni;
    }

    private void admit(String helper, int host, boolean internal) {
        prepareNamespace(helper, host, internal);
        SecurityGroupNftCompiler.Endpoint identity = identity(helper, host);
        manager.register(identity, helper, List.of());
        manager.attachPublicInstance(identity, helper, "172.18.0." + host);
    }

    private void prepareNamespace(String helper, int host, boolean internal) {
        String privateIp = "10.0." + host + ".10";
        NetworkInterface eni = new NetworkInterface();
        eni.setVpcId("vpc");
        eni.setSubnetId("subnet");
        eni.setPrivateIpAddress(privateIp);
        when(ec2.describeNetworkInterfaces(ACCOUNT, REGION, List.of(helper), Map.of()))
                .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));
        Map<String, ContainerNetwork> attachments = Map.of(
                "control", attachment("control-network", "172.18.0." + host),
                "vpc", attachment("vpc-network", privateIp),
                "public", attachment("public-network", "240.0.0." + host));
        when(docker.inspectContainerCmd(helper).exec().getNetworkSettings().getNetworks()).thenReturn(attachments);
        when(docker.inspectContainerCmd(helper).exec().getName()).thenReturn("/" + helper);
        Network control = mock(Network.class);
        when(control.getInternal()).thenReturn(internal);
        when(docker.inspectNetworkCmd().withNetworkId("control-network").exec()).thenReturn(control);
        ExecCreateCmd create = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn(helper + "-exec");
        when(create.exec()).thenReturn(created);
        when(docker.execCreateCmd(helper)).thenReturn(create);
        scripts.put(helper, new ArrayList<>());
        doAnswer(invocation -> {
            String[] argv = (String[]) invocation.getRawArguments()[0];
            if (argv.length == 3 && "sh".equals(argv[0])) {
                scripts.get(helper).add(argv[2]);
            }
            return create;
        }).when(create).withCmd(any(String[].class));
        when(docker.inspectExecCmd(helper + "-exec").exec().getExitCodeLong()).thenReturn(0L);
        when(docker.execStartCmd(helper + "-exec").exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            String addresses = JSON.writeValueAsString(List.of(
                    Map.of("ifname", "eth0", "addr_info", List.of(Map.of("local", "172.18.0." + host))),
                    Map.of("ifname", "eth1", "addr_info", List.of(Map.of("local", privateIp))),
                    Map.of("ifname", "eth2", "addr_info", List.of(Map.of("local", "240.0.0." + host)))));
            callback.onNext(new Frame(StreamType.STDOUT, addresses.getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        });
    }

    private String latest(String helper) {
        return scripts.get(helper).getLast();
    }

    private static SecurityGroupNftCompiler.Endpoint identity(String eni, int host) {
        return new SecurityGroupNftCompiler.Endpoint(
                ACCOUNT, REGION, "vpc", eni, "10.0." + host + ".10", "172.18.0." + host,
                Set.of("sg"), List.of(), List.of(), true, Set.of("10.0." + host + ".10"));
    }

    private static ContainerNetwork attachment(String network, String address) {
        return JSON.convertValue(Map.of("NetworkID", network, "IPAddress", address), ContainerNetwork.class);
    }

    private static Route route(String cidr, String gateway, String state) {
        Route route = new Route();
        route.setDestinationCidrBlock(cidr);
        route.setGatewayId(gateway);
        route.setState(state);
        return route;
    }
}
