package io.github.hectorvent.floci.services.ec2.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.ContainerTeardowns;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.NetworkNamespaceLease;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VpcRoutingRetirementTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final VpcNetworkManager vpcs = mock(VpcNetworkManager.class);
    private final DockerClient docker = mock(DockerClient.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final VpcRoutingManager manager = new VpcRoutingManager(config, ec2, vpcs, docker,
            mock(ContainerBuilder.class), lifecycle, new ObjectMapper(),
            mock(ContainerReachableEndpoint.class));

    VpcRoutingRetirementTest() {
        when(config.network().vpcRouting().enabled()).thenReturn(true);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
        when(vpcs.enabled()).thenReturn(true);
    }

    @Test
    void queuedReconciliationCannotRecreateWorkersDuringTeardown() throws Exception {
        installEndpoint();
        IllegalStateException topologyRead = new IllegalStateException("Reconciliation reached native topology");
        when(ec2.describeRouteTables(REGION, List.of(), Map.of())).thenThrow(topologyRead);

        manager.stopManagedContainers();
        assertDoesNotThrow(manager::reconcile);
        verifyNoInteractions(ec2);

        ((Resettable) manager).afterReset();
        assertSame(topologyRead, assertThrows(IllegalStateException.class, manager::reconcile),
                "An emulator reset must resume routing, including after a failed storage wipe");
    }

    @Test
    void resetRefusesNewRuntimeAdmissionAndResumesAfterwards() {
        NatGateway unsupported = new NatGateway();
        unsupported.setConnectivityType("private");
        ((Resettable) manager).beforeReset();
        assertThrows(IllegalStateException.class, () -> manager.createNat(ACCOUNT, REGION, unsupported));
        verifyNoInteractions(docker, ec2);

        ((Resettable) manager).afterReset();
        AwsException failure = assertThrows(AwsException.class,
                () -> manager.createNat(ACCOUNT, REGION, unsupported));
        assertEquals("UnsupportedOperation", failure.getErrorCode(),
                "After reset the request must reach ordinary NAT validation");
    }

    @Test
    void teardownPreparationRetainsOwnershipAndResetReopensAdmission() throws Exception {
        installEndpoint();
        NatGateway unsupported = new NatGateway();
        unsupported.setConnectivityType("private");

        manager.beginTeardown();
        manager.beginTeardown();

        assertThrows(IllegalStateException.class, () -> manager.createNat(ACCOUNT, REGION, unsupported));
        assertDoesNotThrow(manager::reconcile);
        assertThrows(IllegalStateException.class, ((Resettable) manager)::clear,
                "Preparation must retain the endpoint until its owner retires it");
        verifyNoInteractions(docker, ec2);
        manager.unregister(ACCOUNT, REGION, "eni");
        assertDoesNotThrow(((Resettable) manager)::clear);
        ((Resettable) manager).afterReset();
        assertEquals("UnsupportedOperation", assertThrows(AwsException.class,
                () -> manager.createNat(ACCOUNT, REGION, unsupported)).getErrorCode());
    }

    @Test
    void preparationKeepsSharedNatUntilItsOrderedStop() throws Exception {
        NetworkNamespaceLease namespace = new NetworkNamespaceLease("nat-helper", "/nat-helper");
        installNat(namespace);

        manager.beginTeardown();

        assertThrows(IllegalStateException.class, ((Resettable) manager)::clear);
        verifyNoInteractions(docker, lifecycle, ec2);

        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("nat-helper")).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("already stopped"));
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(list);
        when(list.exec()).thenReturn(List.of());
        manager.stopManagedContainers();

        verify(list).exec();
        verify(lifecycle).removeIfExistsStrict("nat-helper");
        assertDoesNotThrow(((Resettable) manager)::clear);
    }

    @Test
    void resetCannotForgetAnEndpointThatItsOwnerHasNotRetired() throws Exception {
        installEndpoint();
        Resettable reset = (Resettable) manager;
        reset.beforeReset();
        assertThrows(IllegalStateException.class, reset::clear);

        manager.unregister(ACCOUNT, REGION, "eni");
        assertDoesNotThrow(reset::clear);
        reset.afterReset();
        assertDoesNotThrow(manager::reconcile);
        verifyNoInteractions(docker, ec2);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 83})
    void shutdownRetiresEndpointsWithoutReconcilingSurvivingWorkloads(int count) throws Exception {
        for (int index = 0; index < count; index++) {
            installEndpoint("eni-" + index);
        }
        when(ec2.describeRouteTables(REGION, List.of(), Map.of()))
                .thenThrow(new IllegalStateException("Reconciliation reached native topology during shutdown"));
        AtomicBoolean retired = new AtomicBoolean();
        ContainerTeardown owner = new ContainerTeardown() {
            @Override
            public void stopManagedContainers() {
                for (int index = 0; index < count; index++) {
                    manager.unregister(ACCOUNT, REGION, "eni-" + index);
                }
                retired.set(true);
            }

            @Override
            public int teardownOrder() {
                return -10;
            }
        };
        @SuppressWarnings("unchecked")
        Instance<ContainerTeardown> teardowns = mock(Instance.class);
        when(teardowns.iterator()).thenAnswer(invocation -> List.of(manager, owner).iterator());

        ContainerTeardowns.stopAll(teardowns, Logger.getLogger(VpcRoutingRetirementTest.class));

        assertTrue(retired.get(), "Every owned endpoint must retire before shared routing stops");
        verifyNoInteractions(ec2, docker);
        assertDoesNotThrow(((Resettable) manager)::clear);
    }

    private void installEndpoint() throws Exception {
        installEndpoint("eni");
    }

    @SuppressWarnings("unchecked")
    private void installNat(NetworkNamespaceLease namespace) throws Exception {
        Class<?> runtimeType = Class.forName(VpcRoutingManager.class.getName() + "$NatRuntime");
        Constructor<?> constructor = runtimeType.getDeclaredConstructor(
                String.class, String.class, String.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        Object runtime = constructor.newInstance(ACCOUNT, REGION, "vpc", "subnet", "10.0.1.20", "198.51.100.20");
        Field lease = runtimeType.getDeclaredField("namespace");
        lease.setAccessible(true);
        lease.set(runtime, namespace);
        Field field = VpcRoutingManager.class.getDeclaredField("nats");
        field.setAccessible(true);
        Map<String, Object> nats = (Map<String, Object>) field.get(manager);
        nats.put(ACCOUNT + "/" + REGION + "/nat", runtime);
    }

    @SuppressWarnings("unchecked")
    private void installEndpoint(String eni) throws Exception {
        SecurityGroupNftCompiler.Endpoint identity = new SecurityGroupNftCompiler.Endpoint(
                ACCOUNT, REGION, "vpc", eni, "10.0.1.10", "172.17.0.2",
                Set.of("sg"), List.of(), List.of(), false, Set.of("10.0.1.10"));
        Class<?> endpointType = Class.forName(VpcRoutingManager.class.getName() + "$Endpoint");
        Constructor<?> constructor = endpointType.getDeclaredConstructor(
                SecurityGroupNftCompiler.Endpoint.class, String.class, String.class, String.class, List.class);
        constructor.setAccessible(true);
        Object endpoint = constructor.newInstance(identity, "subnet", "helper", "eth1", List.of());
        Field field = VpcRoutingManager.class.getDeclaredField("endpoints");
        field.setAccessible(true);
        Map<String, Object> endpoints = (Map<String, Object>) field.get(manager);
        endpoints.put(ACCOUNT + "/" + REGION + "/" + eni, endpoint);
    }
}
