package io.floci.aws.services.ec2.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.ContainerTeardowns;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.NetworkNamespaceLease;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupNftCompiler;
import io.floci.aws.services.ec2.model.NatGateway;
import io.floci.aws.services.ec2.model.NatGatewayAddress;
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
    private final ContainerBuilder containers = mock(ContainerBuilder.class);
    private final VpcRoutingManager manager = new VpcRoutingManager(config, ec2, vpcs, docker,
            containers, lifecycle, new ObjectMapper());

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
    void natCreationRetryCannotAdmitARetiringRuntime() throws Exception {
        NatGateway gateway = installNat();
        manager.prepareNatDeletion(ACCOUNT, REGION, gateway.getNatGatewayId());

        assertThrows(IllegalStateException.class, () -> manager.createNat(ACCOUNT, REGION, gateway));
        verifyNoInteractions(docker, ec2);
    }

    @Test
    void natCreationRetryKeepsAnActiveRuntime() throws Exception {
        NatGateway gateway = installNat();

        assertDoesNotThrow(() -> manager.createNat(ACCOUNT, REGION, gateway));
        verifyNoInteractions(docker, ec2);
    }

    @Test
    void deletionBeforeRuntimeAdmissionMustFenceQueuedCreate() {
        NatGateway gateway = nativeGateway();
        when(ec2.describeNatGateways(REGION, List.of(gateway.getNatGatewayId()), Map.of()))
                .thenReturn(List.of(gateway));
        manager.prepareNatDeletion(ACCOUNT, REGION, gateway.getNatGatewayId());
        assertRuntimeSetupFenced(gateway);
    }

    @Test
    void completedDeletionMustFenceStaleCreate() {
        NatGateway gateway = nativeGateway();
        manager.prepareNatDeletion(ACCOUNT, REGION, gateway.getNatGatewayId());
        manager.natDeleted(ACCOUNT, REGION, gateway.getNatGatewayId());
        assertRuntimeSetupFenced(gateway);
    }

    @Test
    void freshCreateControlReachesRuntimeSetup() {
        NatGateway gateway = nativeGateway();
        when(ec2.describeNatGateways(REGION, List.of(gateway.getNatGatewayId()), Map.of()))
                .thenReturn(List.of(gateway));
        IllegalStateException setupReached = rejectRuntimeSetup();
        assertSame(setupReached, assertThrows(IllegalStateException.class,
                () -> manager.createNat(ACCOUNT, REGION, gateway)));
        verifyNoInteractions(docker);
    }

    @Test
    void missingDeclarationRejectsStaleCreate() {
        NatGateway gateway = nativeGateway();
        when(ec2.describeNatGateways(REGION, List.of(gateway.getNatGatewayId()), Map.of()))
                .thenThrow(new AwsException("NatGatewayNotFound", "gone", 400));
        assertRuntimeSetupFenced(gateway);
    }

    @Test
    void failedDeclarationRejectsStaleAvailableSnapshot() {
        NatGateway gateway = nativeGateway();
        NatGateway current = nativeGateway();
        current.setState("failed");
        when(ec2.describeNatGateways(REGION, List.of(gateway.getNatGatewayId()), Map.of()))
                .thenReturn(List.of(current));
        assertRuntimeSetupFenced(gateway);
    }

    @Test
    void currentAddressShapeIsValidatedBeforeRuntimeSetup() {
        NatGateway gateway = nativeGateway();
        NatGateway current = nativeGateway();
        current.getNatGatewayAddresses().add(new NatGatewayAddress());
        when(ec2.describeNatGateways(REGION, List.of(gateway.getNatGatewayId()), Map.of()))
                .thenReturn(List.of(current));
        AwsException failure = assertThrows(AwsException.class,
                () -> manager.createNat(ACCOUNT, REGION, gateway));
        assertEquals("UnsupportedOperation", failure.getErrorCode());
        verifyNoInteractions(containers, docker);
    }

    @Test
    void declarationReadFailureIsNotMistakenForDeletion() {
        NatGateway gateway = nativeGateway();
        AwsException unavailable = new AwsException("InternalError", "storage unavailable", 500);
        when(ec2.describeNatGateways(REGION, List.of(gateway.getNatGatewayId()), Map.of()))
                .thenThrow(unavailable);
        assertSame(unavailable, assertThrows(AwsException.class,
                () -> manager.createNat(ACCOUNT, REGION, gateway)));
        verifyNoInteractions(containers, docker);
    }

    private void assertRuntimeSetupFenced(NatGateway gateway) {
        IllegalStateException setupReached = rejectRuntimeSetup();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> manager.createNat(ACCOUNT, REGION, gateway));
        assertNotSame(setupReached, failure, "Deletion must reject admission before helper setup");
        verifyNoInteractions(docker);
    }

    private IllegalStateException rejectRuntimeSetup() {
        String image = "floci/network-helper:local";
        when(config.network().securityGroupEnforcement().helperImage()).thenReturn(image);
        IllegalStateException setupReached = new IllegalStateException("Runtime image resolution reached");
        when(containers.resolveImage(image)).thenThrow(setupReached);
        return setupReached;
    }

    private NatGateway nativeGateway() {
        NatGateway gateway = new NatGateway();
        gateway.setNatGatewayId("nat-late");
        gateway.setState("available");
        gateway.setVpcId("vpc");
        gateway.setSubnetId("subnet");
        gateway.setConnectivityType("public");
        NatGatewayAddress address = new NatGatewayAddress();
        address.setPrivateIp("10.0.1.10");
        address.setPublicIp("192.0.2.1");
        gateway.getNatGatewayAddresses().add(address);
        return gateway;
    }

    @SuppressWarnings("unchecked")
    private NatGateway installNat() throws Exception {
        Class<?> runtimeType = Class.forName(VpcRoutingManager.class.getName() + "$NatRuntime");
        Constructor<?> constructor = runtimeType.getDeclaredConstructor(
                String.class, String.class, String.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        Object runtime = constructor.newInstance(ACCOUNT, REGION, "vpc", "subnet", "10.0.1.10", "192.0.2.1");
        Field field = VpcRoutingManager.class.getDeclaredField("nats");
        field.setAccessible(true);
        Map<String, Object> nats = (Map<String, Object>) field.get(manager);
        nats.put(ACCOUNT + "/" + REGION + "/nat", runtime);
        NatGateway gateway = new NatGateway();
        gateway.setNatGatewayId("nat");
        return gateway;
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

    private void installEndpoint() throws Exception {
        installEndpoint("eni");
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
