package io.floci.aws.services.elb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.NetworkNamespaceLease;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.NetworkInterfaceListResult;
import io.floci.aws.services.ec2.model.Placement;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import io.floci.aws.services.elb.model.ClassicListener;
import io.floci.aws.services.elb.model.ClassicLoadBalancer;
import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElbClassicRuntimeReconciliationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final ElbClassicFrontendManager manager;
    private final ClassicLoadBalancer loadBalancer = new ClassicLoadBalancer();
    private final IllegalStateException allocationFailure = new IllegalStateException("Replacement allocation failed");

    private final List<String> commands = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();
    private long activeSessions = 1;
    private boolean rejectStatistics;
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

    ElbClassicRuntimeReconciliationTest() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services().elb().vpcFrontends()).thenReturn(true);
        when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
        when(firewall.enabled()).thenReturn(true);
        VpcNetworkManager networks = mock(VpcNetworkManager.class);
        when(networks.enabled()).thenReturn(true);
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec()).thenReturn(List.of());
        when(docker.inspectContainerCmd("helper").exec().getState().getRunning()).thenReturn(true);
        when(docker.inspectContainerCmd("proxy").exec().getState().getRunning()).thenReturn(true);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(AccountAwareStorageBackend.inMemory(ACCOUNT)).when(factory)
                .create(eq("elb"), eq("elb-classic-frontends.json"), any());
        manager = new ElbClassicFrontendManager(config, ec2, firewall, networks,
                mock(ContainerBuilder.class), lifecycle, new ObjectMapper(), mock(IamService.class),
                mock(AcmService.class), mock(VpcRoutingManager.class), factory);
        manager.initialize();
        Field worker = ElbClassicFrontendManager.class.getDeclaredField("drainWorker");
        worker.setAccessible(true);
        ((ScheduledExecutorService) worker.get(manager)).shutdownNow();
        worker.set(manager, scheduler);
        loadBalancer.setAccountId(ACCOUNT);
        loadBalancer.setRegion(REGION);
        loadBalancer.setLoadBalancerName("policy-reload");
        loadBalancer.setScheme("internal");
        loadBalancer.setSubnets(List.of("subnet"));
        loadBalancer.setSecurityGroups(List.of("sg"));
        loadBalancer.setAvailabilityZones(List.of("us-east-1a"));
        ClassicListener listener = new ClassicListener();
        listener.setProtocol("HTTPS");
        listener.setInstanceProtocol("HTTP");
        listener.setLoadBalancerPort(443);
        listener.setInstancePort(8080);
        listener.setSslCertificateId("certificate");
        loadBalancer.setListeners(List.of(listener));
        when(ec2.createServiceNetworkInterface(eq(REGION), eq("subnet"), anyString(), anyList(), anyString()))
                .thenThrow(allocationFailure);
        when(ec2.findServiceNetworkInterfaces(eq(REGION), anyString())).thenReturn(List.of());
    }

    @AfterEach
    void closeWorker() {
        scheduler.shutdownNow();
    }

    @Test
    void registrationAndDrainingChangesReuseRunningFrontends() throws Exception {
        ElbClassicBackendRuntime runtime = runtime();
        install(runtime);
        loadBalancer.setInstanceIds(List.of("i-new"));
        loadBalancer.getAttributes().setConnectionDrainingEnabled(true);
        loadBalancer.getAttributes().setConnectionDrainingTimeout(60);
        Instance instance = new Instance();
        instance.setVpcId("vpc");
        instance.setPrivateIpAddress("10.0.1.20");
        Placement placement = new Placement();
        placement.setAvailabilityZone("us-east-1a");
        instance.setPlacement(placement);
        when(ec2.getInstance(REGION, "i-new")).thenReturn(Optional.of(instance));

        assertTrue(manager.reconcile(loadBalancer));
        assertEquals(Map.of("i-new", "10.0.1.20"), runtime.addresses());
        verify(ec2, never()).createServiceNetworkInterface(anyString(), anyString(), anyString(), anyList(), anyString());
        verify(lifecycle, never()).stopAndRemoveStrict(anyString(), any(), anyInt());
    }

    @Test
    void pendingDrainSchedulesOneRefreshAndRetriesFailure() throws Exception {
        ElbClassicBackendRuntime runtime = runtime();
        install(runtime);
        loadBalancer.getAttributes().setConnectionDrainingEnabled(true);
        manager.reconcile(loadBalancer);
        manager.reconcile(loadBalancer);
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(tasks.capture(), eq(100L), eq(TimeUnit.MILLISECONDS));
        assertEquals(Set.of("i-old"), manager.drainingInstances(loadBalancer));

        rejectStatistics = true;
        tasks.getValue().run();
        verify(scheduler, times(2)).schedule(tasks.capture(), eq(100L), eq(TimeUnit.MILLISECONDS));
        rejectStatistics = false;
        activeSessions = 0;
        tasks.getValue().run();
        assertFalse(runtime.hasPendingWork());
        verify(scheduler, times(2)).schedule(any(Runnable.class), anyLong(), any());
        assertEquals("10.0.1.10", manager.address(loadBalancer).orElseThrow());
    }

    @Test
    void retiredNodesAreExcludedFromDrainsAndProbes() throws Exception {
        ElbClassicBackendRuntime runtime = runtime();
        ElbClassicFrontendManager.Frontends group = install(runtime);
        loadBalancer.getAttributes().setConnectionDrainingEnabled(true);
        runtime.reconcile(Map.of(), loadBalancer.getAttributes());
        group.retired().add("eni-current");
        assertTrue(manager.drainingInstances(loadBalancer).isEmpty());
        assertTrue(manager.probesFor(loadBalancer, "i-old").isEmpty());
    }

    @Test
    void generationChangeRefusesStaleHealthPublication() throws Exception {
        ElbClassicBackendRuntime runtime = runtime();
        install(runtime);
        loadBalancer.getAttributes().setConnectionDrainingEnabled(true);
        ElbClassicFrontendManager.Probe probe = manager.probeFor(loadBalancer, "i-old");
        assertNotNull(probe);
        assertTrue(manager.current(loadBalancer, probe));
        runtime.reconcile(Map.of(), loadBalancer.getAttributes());
        runtime.reconcile(Map.of("i-old", "10.0.1.20"), loadBalancer.getAttributes());
        commands.clear();
        assertFalse(manager.current(loadBalancer, probe));
        manager.healthChanged(loadBalancer, probe, ElbClassicHealthChecker.InstanceHealth.inService());
        assertTrue(commands.isEmpty());
        manager.healthChanged(loadBalancer, manager.probeFor(loadBalancer, "i-old"),
                ElbClassicHealthChecker.InstanceHealth.inService());
        assertEquals(List.of("set server listener_443/backend state ready\n"), commands);
    }

    @Test
    void runtimeFailureQuarantinesProxyAndRetainsOwnership() throws Exception {
        ElbClassicBackendRuntime runtime = runtime();
        ElbClassicFrontendManager.Frontends group = install(runtime);
        rejectStatistics = true;
        assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer));
        verify(lifecycle).stopAndRemoveStrict("proxy", null, 1);
        verify(ec2, never()).releaseNetworkInterfaceFromService(anyString(), anyString());
        assertEquals("allocation", group.nodes().getFirst().ownershipId());
        assertEquals("10.0.1.10", manager.address(loadBalancer).orElseThrow());
    }

    @Test
    void queuedDrainCannotUpdateReplacedGroup() throws Exception {
        ElbClassicBackendRuntime runtime = runtime();
        install(runtime);
        loadBalancer.getAttributes().setConnectionDrainingEnabled(true);
        manager.reconcile(loadBalancer);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), eq(100L), eq(TimeUnit.MILLISECONDS));
        install(runtime());
        commands.clear();
        task.getValue().run();
        assertTrue(commands.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedNodeDrainDoesNotPreventSiblingCompletion() throws Exception {
        loadBalancer.getAttributes().setConnectionDrainingEnabled(true);
        ElbClassicBackendRuntime first = runtime();
        ElbClassicFrontendManager.Frontends initial = install(first);
        AtomicLong siblingSessions = new AtomicLong(1);
        ElbClassicBackendRuntime second = new ElbClassicBackendRuntime(loadBalancer.getListeners(),
                Map.of("i-old", List.of("listener_443/backend")), Map.of("i-old", "10.0.1.20"),
                command -> command.startsWith("show stat")
                        ? "# pxname,svname,qcur,scur\nlistener_443,backend,0," + siblingSessions.get() + "\n"
                        : command.startsWith("del server") ? "Server deleted." : "", clock::get);
        first.reconcile(Map.of(), loadBalancer.getAttributes());
        second.reconcile(Map.of(), loadBalancer.getAttributes());
        ElbClassicFrontendManager.Frontend sibling = new ElbClassicFrontendManager.Frontend(
                ACCOUNT, REGION, initial.state(), "eni-sibling", "10.0.2.10", null, "proxy-sibling",
                Map.of(), Map.of(), "sibling-allocation").withRuntime(second);
        ElbClassicFrontendManager.Frontends group = new ElbClassicFrontendManager.Frontends(initial.state(),
                List.of(initial.nodes().getFirst(), sibling));
        Field field = ElbClassicFrontendManager.class.getDeclaredField("frontends");
        field.setAccessible(true);
        Map<String, ElbClassicFrontendManager.Frontends> groups =
                (Map<String, ElbClassicFrontendManager.Frontends>) field.get(manager);
        String key = ACCOUNT + "/" + REGION + "/policy-reload";
        groups.put(key, group);
        Method schedule = ElbClassicFrontendManager.class.getDeclaredMethod("scheduleDrainRefresh",
                String.class, ElbClassicFrontendManager.Frontends.class);
        schedule.setAccessible(true);
        schedule.invoke(manager, key, group);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), eq(100L), eq(TimeUnit.MILLISECONDS));
        rejectStatistics = true;
        siblingSessions.set(0);

        task.getValue().run();

        assertTrue(first.hasPendingWork());
        assertFalse(second.hasPendingWork(), "A failed node must not starve a sibling that finished draining");
        assertEquals(Set.of("i-old"), manager.drainingInstances(loadBalancer));
        verify(scheduler, times(2)).schedule(any(Runnable.class), eq(100L), eq(TimeUnit.MILLISECONDS));
    }

    private ElbClassicBackendRuntime runtime() {
        return new ElbClassicBackendRuntime(loadBalancer.getListeners(),
                Map.of("i-old", List.of("listener_443/backend")), Map.of("i-old", "10.0.1.20"),
                this::command, clock::get);
    }

    private String command(String command) {
        commands.add(command);
        if (command.startsWith("show stat")) {
            if (rejectStatistics) {
                throw new IllegalStateException("temporary statistics failure");
            }
            return "# pxname,svname,qcur,scur\nlistener_443,backend,0," + activeSessions + "\n";
        }
        if (command.startsWith("add server ")) {
            return "New server registered.";
        }
        if (command.startsWith("del server ")) {
            return "Server deleted.";
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private ElbClassicFrontendManager.Frontends install(ElbClassicBackendRuntime runtime) throws Exception {
        Method state = ElbClassicFrontendManager.class.getDeclaredMethod("state", ClassicLoadBalancer.class);
        state.setAccessible(true);
        String snapshot = (String) state.invoke(manager, loadBalancer);
        ElbClassicFrontendManager.Frontend node = new ElbClassicFrontendManager.Frontend(
                ACCOUNT, REGION, snapshot, "eni-current", "10.0.1.10",
                new NetworkNamespaceLease("helper", "helper-name"), "proxy", Map.of(), Map.of(), "allocation")
                .withRuntime(runtime);
        ElbClassicFrontendManager.Frontends group = new ElbClassicFrontendManager.Frontends(snapshot, List.of(node));
        Field field = ElbClassicFrontendManager.class.getDeclaredField("frontends");
        field.setAccessible(true);
        Map<String, ElbClassicFrontendManager.Frontends> frontends =
                (Map<String, ElbClassicFrontendManager.Frontends>) field.get(manager);
        frontends.put(ACCOUNT + "/" + REGION + "/policy-reload", group);
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-current");
        eni.setSubnetId("subnet");
        eni.setVpcId("vpc");
        when(ec2.describeNetworkInterfaces(eq(REGION), eq(List.of("eni-current")), anyMap()))
                .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));
        Subnet subnet = new Subnet();
        subnet.setAvailabilityZone("us-east-1a");
        when(ec2.describeSubnets(eq(REGION), eq(List.of("subnet")), anyMap())).thenReturn(List.of(subnet));
        return group;
    }
}
