package io.github.hectorvent.floci.services.ec2.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VpcRoutingRetirementTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final VpcNetworkManager vpcs = mock(VpcNetworkManager.class);
    private final DockerClient docker = mock(DockerClient.class);
    private final VpcRoutingManager manager = new VpcRoutingManager(config, ec2, vpcs, docker,
            mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), new ObjectMapper(),
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

    @SuppressWarnings("unchecked")
    private void installEndpoint() throws Exception {
        SecurityGroupNftCompiler.Endpoint identity = new SecurityGroupNftCompiler.Endpoint(
                ACCOUNT, REGION, "vpc", "eni", "10.0.1.10", "172.17.0.2",
                Set.of("sg"), List.of(), List.of(), false, Set.of("10.0.1.10"));
        Class<?> endpointType = Class.forName(VpcRoutingManager.class.getName() + "$Endpoint");
        Constructor<?> constructor = endpointType.getDeclaredConstructor(
                SecurityGroupNftCompiler.Endpoint.class, String.class, String.class, String.class, List.class);
        constructor.setAccessible(true);
        Object endpoint = constructor.newInstance(identity, "subnet", "helper", "eth1", List.of());
        Field field = VpcRoutingManager.class.getDeclaredField("endpoints");
        field.setAccessible(true);
        Map<String, Object> endpoints = (Map<String, Object>) field.get(manager);
        endpoints.put(ACCOUNT + "/" + REGION + "/eni", endpoint);
    }
}
