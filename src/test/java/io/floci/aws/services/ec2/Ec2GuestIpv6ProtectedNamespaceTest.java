package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.DisconnectFromNetworkCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.PingCmd;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.NetworkSettings;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceNetworkInterface;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.testing.TestTime;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.invocation.Invocation;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * With FLOCI_SERVICES_EC2_GUEST_IPV6, an instance launched into a security-group protected namespace gets the same
 * guest IPv6 as one without enforcement, before its workload starts: the namespace starts with IPv6 enabled, the
 * instance's address is put on its VPC interface and the IPv6 VPC router joins its VPC. In an isolated address space
 * the native interface keeps that address, and the router joins at the transport address Docker gives it there.
 */
class Ec2GuestIpv6ProtectedNamespaceTest {

    private static final String HELPER = "firewall-helper";
    private static final String ROUTER = "ipv6-router";
    private static final String WORKLOAD = "container-1";
    private static final String VPC_NETWORK = "floci-vpc-a";
    private static final String PRIVATE_IP = "10.0.0.12";
    private static final String IPV6 = "2600:1f18:abcd:ef01::1234";
    private static final String VPC_IPV6 = "2600:1f18:abcd:ef00::/56";
    private static final String ROUTER_ADDRESS = "10.0.0.3";
    private static final String ROUTER_TRANSPORT = "172.31.5.9";
    private static final String ROUTER_MAC = "02:42:ac:12:00:03";

    @Test
    void aProtectedDualStackGuestGetsItsIpv6BeforeItsWorkloadStarts() throws Exception {
        Launch launch = launch(false, true);

        assertEquals(Boolean.TRUE, lastArgument(only(launch.firewall(), "createNamespace")),
                "the protected namespace must start with IPv6 enabled");
        Exec guest = only(launch.beforeStart(), HELPER, "guest-ipv6");
        assertEquals(List.of(PRIVATE_IP, IPV6, VPC_IPV6), guest.command().subList(4, 7));
        Exec router = only(launch.beforeStart(), ROUTER, "ipv6-router");
        assertEquals(List.of(ROUTER_MAC, VPC_IPV6), router.command().subList(4, 6));
    }

    @Test
    void anIsolatedAddressSpaceKeepsTheGuestsIpv6OnItsNativeInterface() throws Exception {
        Launch launch = launch(true, true);

        assertEquals(Boolean.TRUE, lastArgument(only(launch.networks(), "attach")),
                "the native interface must leave the guest's IPv6 unblocked");
        List<Invocation> verified = calls(launch.networks(), "requireNativePrivateAddress");
        assertFalse(verified.isEmpty(), "the native address is verified before routing");
        for (Invocation verification : verified) {
            assertEquals(IPV6, lastArgument(verification), "verification must admit the guest's own IPv6 address");
        }
        only(launch.beforeStart(), HELPER, "guest-ipv6");
        Exec router = only(launch.beforeStart(), ROUTER, "ipv6-router");
        assertEquals(List.of(ROUTER_MAC, VPC_IPV6), router.command().subList(4, 6),
                "the router finds its interface by Docker's MAC even after its IPv4 address is flushed");
        verify(launch.connect()).withContainerNetwork(argThat(endpoint -> endpoint.getIpamConfig() == null));
    }

    @Test
    void aProtectedGuestWithoutIpv6KeepsAnIpv4OnlyNamespace() throws Exception {
        Launch launch = launch(true, false);

        assertNotEquals(Boolean.TRUE, lastArgument(only(launch.firewall(), "createNamespace")));
        assertNotEquals(Boolean.TRUE, lastArgument(only(launch.networks(), "attach")));
        for (Invocation verification : calls(launch.networks(), "requireNativePrivateAddress")) {
            assertFalse(Arrays.asList(verification.getArguments()).contains(IPV6));
        }
        assertTrue(launch.execs().stream().noneMatch(exec -> exec.tagged("guest-ipv6") || exec.tagged("ipv6-router")),
                summary(launch.execs()));
        verifyNoInteractions(launch.connect());
    }

    private record Exec(String containerId, List<String> command) {
        boolean tagged(String tag) {
            return command.size() > 3 && tag.equals(command.get(3));
        }
    }

    private record Launch(SecurityGroupFirewallManager firewall, VpcNetworkManager networks,
                          ConnectToNetworkCmd connect, List<Exec> execs, List<Exec> beforeStart) {}

    private static Launch launch(boolean isolated, boolean dualStack) throws Exception {
        EmulatorConfig mapped = new SmallRyeConfigBuilder()
                .withSources(new EnvConfigSource(Map.of("FLOCI_SERVICES_EC2_GUEST_IPV6", "true"), 300))
                .withDefaultValue("floci.services.ec2.metadata-helper-image", "example/network-helper:test")
                .withMapping(EmulatorConfig.class).build()
                .getConfigMapping(EmulatorConfig.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(config.network()).thenReturn(mapped.network());
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(mapped.services().ec2());
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(docker.logMaxSize()).thenReturn("10m");
        when(docker.logMaxFile()).thenReturn("3");
        when(docker.extraLabels()).thenReturn(List.of());
        when(docker.resourceNamespace()).thenReturn(Optional.empty());
        when(docker.imageRegistryBase()).thenReturn(Optional.empty());
        when(config.docker()).thenReturn(docker);

        DockerHostResolver hosts = mock(DockerHostResolver.class);
        when(hosts.resolve()).thenReturn("floci");
        ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
        when(endpoint.baseUrlWithTls(true)).thenReturn("http://localhost.floci.io:4680");
        when(endpoint.baseUrlWithTls(false)).thenReturn("http://172.30.0.2:4680");
        PortAllocator ports = mock(PortAllocator.class);
        when(ports.allocate(anyInt(), anyInt())).thenReturn(2201);
        when(ports.allocateAndStart(anyInt(), anyInt(), any())).thenCallRealMethod();
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getAccountId()).thenReturn("000000000000");

        List<Exec> execs = new CopyOnWriteArrayList<>();
        List<Exec> beforeStart = new CopyOnWriteArrayList<>();
        AtomicBoolean started = new AtomicBoolean();
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.create(any(ContainerSpec.class))).thenReturn(WORKLOAD);
        when(lifecycle.isContainerRunning(WORKLOAD)).thenReturn(true);
        when(lifecycle.createAndStart(any(ContainerSpec.class)))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo(ROUTER, Map.of()));
        when(lifecycle.startCreated(eq(WORKLOAD), any())).thenAnswer(invocation -> {
            beforeStart.addAll(execs);
            started.set(true);
            return null;
        });

        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.pingCmd()).thenReturn(mock(PingCmd.class));
        when(dockerClient.inspectImageCmd(anyString())).thenReturn(mock(InspectImageCmd.class));
        for (String containerId : List.of(HELPER, ROUTER, WORKLOAD)) {
            stubExecs(dockerClient, containerId, execs);
        }
        // In a shared address space Docker's IPAM holds the VPC address; in an isolated one only the namespace does.
        stubInspect(dockerClient, HELPER, isolated
                ? Map.of("bridge", new ContainerNetwork().withIpv4Address("172.18.0.12"))
                : Map.of("bridge", new ContainerNetwork().withIpv4Address("172.18.0.12"),
                        VPC_NETWORK, new ContainerNetwork().withIpv4Address(PRIVATE_IP)));
        stubInspect(dockerClient, WORKLOAD, Map.of("bridge", new ContainerNetwork().withIpv4Address("172.18.0.12")));
        stubInspect(dockerClient, ROUTER, Map.of(VPC_NETWORK, new ContainerNetwork()
                .withIpv4Address(ROUTER_TRANSPORT).withMacAddress(ROUTER_MAC)));
        ConnectToNetworkCmd connect = mock(ConnectToNetworkCmd.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
        when(dockerClient.connectToNetworkCmd()).thenReturn(connect);
        when(dockerClient.disconnectFromNetworkCmd()).thenReturn(
                mock(DisconnectFromNetworkCmd.class, withSettings().defaultAnswer(Answers.RETURNS_SELF)));

        // Every overload of these answers the same way, so the launch path picks whichever one it needs.
        VpcNetworkManager networks = mock(VpcNetworkManager.class, invocation ->
                switch (invocation.getMethod().getName()) {
                    case "enabled" -> true;
                    case "isolatesAddressSpaces" -> isolated;
                    case "attach" -> Optional.of(VPC_NETWORK);
                    case "ipv6RouterAddress" -> Optional.of(ROUTER_ADDRESS);
                    case "requireNativePrivateAddress" -> invocation.getArgument(5);
                    default -> Answers.RETURNS_DEFAULTS.answer(invocation);
                });
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class, invocation ->
                switch (invocation.getMethod().getName()) {
                    case "enabled" -> true;
                    case "createNamespace" -> new SecurityGroupFirewallManager.Namespace(HELPER, "172.18.0.12");
                    default -> Answers.RETURNS_DEFAULTS.answer(invocation);
                });

        TestTime time = TestTime.create();
        Ec2ContainerManager manager = new Ec2ContainerManager(
                new ContainerBuilder(config, hosts, mock(EmbeddedDnsServer.class)),
                lifecycle,
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                hosts,
                dockerClient,
                ports,
                config,
                mock(Ec2MetadataServer.class),
                mock(Ec2PortForwardManager.class),
                regions,
                mock(ContainerNetworkReachability.class),
                networks,
                endpoint,
                firewall,
                new Ec2InstanceTypeCatalog(),
                millis -> time.sleepAdvancing(Duration.ofMillis(millis)));

        Instance instance = new Instance();
        instance.setInstanceId("i-dual-stack");
        instance.setInstanceType("t3.micro");
        instance.setRegion("us-west-2");
        instance.setVpcId("vpc-a");
        instance.setSubnetId("subnet-a");
        instance.setPrivateIpAddress(PRIVATE_IP);
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setNetworkInterfaceId("eni-dual-stack");
        eni.setDeviceIndex(0);
        eni.setPrivateIpAddress(PRIVATE_IP);
        if (dualStack) {
            eni.setIpv6Addresses(List.of(IPV6));
            eni.setVpcIpv6CidrBlocks(List.of(VPC_IPV6));
        }
        instance.setNetworkInterfaces(List.of(eni));
        try {
            manager.launch(instance, ResolvedAmiImage.minimal("ubuntu:24.04"), null, "us-west-2");
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(5)).until(() -> started.get()
                    || instance.getState() != null && "terminated".equals(instance.getState().getName()));
        } finally {
            manager.stop();
        }
        assertTrue(started.get(), "the workload must start; state "
                + (instance.getState() == null ? null : instance.getState().getName())
                + ", reason " + instance.getStateReasonMessage() + ", execs " + summary(execs));
        return new Launch(firewall, networks, connect, execs, beforeStart);
    }

    private static void stubExecs(DockerClient dockerClient, String containerId, List<Exec> execs) {
        ExecCreateCmd create = mock(ExecCreateCmd.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
        when(create.withCmd(any(String[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            String[] command = args.length == 1 && args[0] instanceof String[] array
                    ? array : Arrays.copyOf(args, args.length, String[].class);
            execs.add(new Exec(containerId, List.of(command)));
            return create;
        });
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn("exec-" + containerId);
        when(create.exec()).thenReturn(created);
        when(dockerClient.execCreateCmd(containerId)).thenReturn(create);
        ExecStartCmd start = mock(ExecStartCmd.class);
        when(start.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onComplete();
            return callback;
        });
        when(dockerClient.execStartCmd("exec-" + containerId)).thenReturn(start);
        InspectExecResponse result = mock(InspectExecResponse.class);
        when(result.getExitCodeLong()).thenReturn(0L);
        InspectExecCmd inspect = mock(InspectExecCmd.class);
        when(inspect.exec()).thenReturn(result);
        when(dockerClient.inspectExecCmd("exec-" + containerId)).thenReturn(inspect);
    }

    private static void stubInspect(DockerClient dockerClient, String containerId,
                                    Map<String, ContainerNetwork> attached) {
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        NetworkSettings settings = mock(NetworkSettings.class);
        when(response.getNetworkSettings()).thenReturn(settings);
        when(settings.getNetworks()).thenReturn(attached);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(inspect.exec()).thenReturn(response);
        when(dockerClient.inspectContainerCmd(containerId)).thenReturn(inspect);
    }

    private static List<Invocation> calls(Object mock, String method) {
        return mockingDetails(mock).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(method)).toList();
    }

    private static Invocation only(Object mock, String method) {
        List<Invocation> calls = calls(mock, method);
        assertEquals(1, calls.size(), method + ": " + calls);
        return calls.getFirst();
    }

    private static Object lastArgument(Invocation invocation) {
        Object[] arguments = invocation.getArguments();
        return arguments[arguments.length - 1];
    }

    private static Exec only(List<Exec> execs, String containerId, String tag) {
        List<Exec> matches = execs.stream()
                .filter(exec -> exec.containerId().equals(containerId) && exec.tagged(tag)).toList();
        assertEquals(1, matches.size(), tag + " in " + containerId + "; execs before start: " + summary(execs));
        return matches.getFirst();
    }

    private static String summary(List<Exec> execs) {
        return execs.stream().map(exec -> exec.containerId() + ":"
                + (exec.command().size() > 3 && exec.command().get(0).equals("sh") ? exec.command().get(3)
                : exec.command().get(0))).toList().toString();
    }
}
