package io.github.hectorvent.floci.services.ec2.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterfaceListResult;
import io.github.hectorvent.floci.services.ec2.model.Route;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.RouteTableAssociation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VpcResolverContinuityTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final VpcNetworkManager vpcs = mock(VpcNetworkManager.class);
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final VpcRoutingManager manager = new VpcRoutingManager(
            config, ec2, vpcs, docker, mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
            new ObjectMapper(), mock(ContainerReachableEndpoint.class));
    private final List<String> scripts = new ArrayList<>();
    private final List<Route> routes = new ArrayList<>();

    @BeforeEach
    void prepareEndpoint() {
        when(config.network().vpcRouting().enabled()).thenReturn(true);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
        when(vpcs.enabled()).thenReturn(true);
        when(vpcs.effectiveVpcCidr(REGION, "vpc")).thenReturn(Optional.of("10.0.0.0/16"));
        routes.add(route("10.0.0.0/16", "active"));
        RouteTable table = new RouteTable();
        table.setVpcId("vpc");
        table.setRoutes(routes);
        RouteTableAssociation main = new RouteTableAssociation();
        main.setMain(true);
        main.setAssociationState("associated");
        table.setAssociations(List.of(main));
        when(ec2.describeRouteTables(REGION, List.of(), Map.of())).thenReturn(List.of(table));
        when(ec2.describeNatGateways(REGION, List.of(), Map.of())).thenReturn(List.of());
        when(ec2.describeInternetGateways(REGION, List.of(), Map.of())).thenReturn(List.of());
        NetworkInterface eni = new NetworkInterface();
        eni.setVpcId("vpc");
        eni.setSubnetId("subnet");
        eni.setPrivateIpAddress("10.0.2.10");
        when(ec2.describeNetworkInterfaces(ACCOUNT, REGION, List.of("eni"), Map.of()))
                .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));
        ExecCreateCmd create = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn("helper-exec");
        when(create.exec()).thenReturn(created);
        when(docker.execCreateCmd("helper")).thenReturn(create);
        doAnswer(invocation -> {
            String[] argv = (String[]) invocation.getRawArguments()[0];
            if (argv.length == 3 && "sh".equals(argv[0])) {
                scripts.add(argv[2]);
            }
            return create;
        }).when(create).withCmd(any(String[].class));
        when(docker.inspectExecCmd("helper-exec").exec().getExitCodeLong()).thenReturn(0L);
        when(docker.execStartCmd("helper-exec").exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            String addresses = """
                    [{"ifname":"eth1","addr_info":[{"local":"10.0.2.10"}]}]
                    """;
            callback.onNext(new Frame(StreamType.STDOUT, addresses.getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        });
        SecurityGroupNftCompiler.Endpoint identity = new SecurityGroupNftCompiler.Endpoint(
                ACCOUNT, REGION, "vpc", "eni", "10.0.2.10", "172.18.0.2",
                Set.of("sg"), List.of(), List.of(), true, Set.of("10.0.2.10"));
        manager.register(identity, "helper", List.of("172.18.0.1"));
    }

    @Test
    void routeReplacementInstallsOnlyResolverPermitsInOneGuardTransaction() {
        scripts.clear();
        routes.add(0, route("10.0.2.0/24", "blackhole"));

        manager.reconcile();

        assertEquals(2, scripts.size(), "The existing endpoint must receive a guard and a replacement policy");
        String guard = scripts.getFirst();
        assertTrue(guard.contains("policy drop;"));
        assertEquals(1, guard.lines().filter(line -> line.startsWith("nft ")).count(),
                "The guard and resolver permits must be installed in one transaction");
        for (String protocol : List.of("udp", "tcp")) {
            assertTrue(guard.contains("egress ip daddr 172.18.0.1 " + protocol + " dport 53 counter accept"));
            assertTrue(guard.contains("ingress ip saddr 172.18.0.1 " + protocol
                    + " sport 53 ct state established ct direction reply counter accept"));
        }
        assertEquals(4, guard.lines().filter(line -> line.endsWith("counter accept")).count(),
                "Peer and unsolicited inbound traffic must remain denied during replacement");
        assertTrue(scripts.getLast().contains("10.0.2.0/24"));
    }

    @Test
    void initialAdmissionStillDeniesResolverTraffic() {
        assertTrue(scripts.getFirst().contains("policy drop;"));
        assertFalse(scripts.getFirst().contains("accept"));
    }

    @Test
    void failedTopologyStillQuarantinesResolverTraffic() {
        when(ec2.describeRouteTables(REGION, List.of(), Map.of()))
                .thenThrow(new IllegalStateException("Topology unavailable"));

        assertThrows(IllegalStateException.class, manager::reconcile);

        assertTrue(scripts.getLast().contains("policy drop;"));
        assertFalse(scripts.getLast().contains("accept"));
    }

    private static Route route(String cidr, String state) {
        Route route = new Route();
        route.setDestinationCidrBlock(cidr);
        route.setGatewayId("local");
        route.setState(state);
        return route;
    }
}
