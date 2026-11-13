package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.DockerFlags;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LambdaVpcNetworkManagerTest {
    @Mock EmulatorConfig config;
    @Mock Ec2Service ec2;
    @Mock SecurityGroupFirewallManager firewall;
    @Mock ContainerLifecycleManager lifecycle;
    private LambdaVpcNetworkManager manager;

    @BeforeEach
    void setUp() {
        manager = new LambdaVpcNetworkManager(config, ec2, firewall, lifecycle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--privileged", "--network host", "--publish 8080:8080",
            "--dns 192.0.2.53", "--add-host fixture:192.0.2.10"})
    void incompatibleOverridesFailBeforeAllocatingResources(String flags) {
        when(firewall.enabled()).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () ->
                manager.prepare(function(), "environment", "192.0.2.1", 9001, DockerFlags.parse(flags)));

        verifyNoInteractions(ec2, lifecycle);
    }

    @Test
    void anAdditionalNetworkCannotBypassTheProtectedNamespace() {
        when(firewall.enabled()).thenReturn(true);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.additionalDockerNetworks()).thenReturn(Optional.of(List.of("unfiltered")));

        assertThrows(IllegalArgumentException.class, () ->
                manager.prepare(function(), "environment", "192.0.2.1", 9001, DockerFlags.parse("")));

        verifyNoInteractions(ec2, lifecycle);
    }

    @Test
    void disabledEnforcementDoesNotAllocateNetworkResources() {
        assertTrue(manager.prepare(function(), "environment", "192.0.2.1", 9001,
                DockerFlags.parse("")).isEmpty());
        verifyNoInteractions(ec2, lifecycle);
    }

    @Test
    void incompletePersistedVpcConfigurationCannotFallBackToUnprotectedExecution() {
        when(firewall.enabled()).thenReturn(true);
        LambdaFunction function = function();
        function.setVpcConfig(Map.of("SecurityGroupIds", List.of("sg-caller")));

        assertThrows(IllegalStateException.class, () -> manager.prepare(function, "environment",
                "192.0.2.1", 9001, DockerFlags.parse("")));

        verifyNoInteractions(ec2, lifecycle);
    }

    @Test
    void failedHelperRemovalKeepsTheEniReserved() {
        LambdaVpcNetworkManager.Attachment attachment = attachment();
        doThrow(new IllegalStateException("Docker is unavailable")).when(lifecycle).removeIfExistsStrict("helper");

        assertThrows(IllegalStateException.class, () -> manager.release(attachment));

        verifyNoInteractions(ec2, firewall);
    }

    @Test
    void cleanupToleratesAnInterfaceAlreadyRemovedDuringRecovery() {
        doThrow(new AwsException("InvalidNetworkInterfaceID.NotFound", "already gone", 400))
                .when(ec2).deleteNetworkInterface("us-east-1", "eni-caller");

        assertDoesNotThrow(() -> manager.release(attachment()));

        verify(lifecycle).removeIfExistsStrict("helper");
        verify(firewall).unregister("eni-caller");
    }

    @Test
    void cleanupDoesNotSuppressARealInterfaceDeletionFailure() {
        doThrow(new AwsException("InvalidNetworkInterface.InUse", "still held", 400))
                .when(ec2).deleteNetworkInterface("us-east-1", "eni-caller");

        assertThrows(AwsException.class, () -> manager.release(attachment()));
    }

    @Test
    void anotherEndpointsReconciliationFailureDoesNotLeakTheRemovedHelpersEni() {
        IllegalStateException failure = new IllegalStateException("another helper disappeared");
        doThrow(failure).when(firewall).unregister("eni-caller");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.release(attachment())));

        verify(ec2).releaseNetworkInterfaceFromService("us-east-1", "eni-caller");
        verify(ec2).deleteNetworkInterface("us-east-1", "eni-caller");
    }

    @Test
    void cleanupPreservesBothReconciliationAndInterfaceFailures() {
        IllegalStateException failure = new IllegalStateException("another helper disappeared");
        AwsException cleanupFailure = new AwsException("InternalError", "store unavailable", 500);
        doThrow(failure).when(firewall).unregister("eni-caller");
        doThrow(cleanupFailure).when(ec2).deleteNetworkInterface("us-east-1", "eni-caller");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.release(attachment())));
        assertEquals(List.of(cleanupFailure), List.of(failure.getSuppressed()));
    }

    @Test
    void recoveryToleratesAContainerDisappearingAfterTheList() {
        DockerClient docker = recoveryDocker(Map.of());
        when(docker.inspectContainerCmd("workload").exec()).thenThrow(new NotFoundException("already removed"));

        assertDoesNotThrow(manager::recoverOrphans);
        assertDoesNotThrow(manager::recoverOrphans);

        verify(lifecycle, never()).stopAndRemoveStrict(anyString(), any(), anyInt());
        verify(lifecycle).removeIfExistsStrict("helper");
        verify(ec2).deleteNetworkInterface("us-east-1", "eni-caller");
    }

    @Test
    void recoveryRemovesTheOwnedWorkloadBeforeItsNamespace() {
        DockerClient docker = recoveryDocker(Map.of(ContainerStorageHelper.OWNER_LABEL, "4566",
                ContainerStorageHelper.SERVICE_LABEL, "lambda",
                ContainerStorageHelper.SECURITY_GROUP_WORKLOAD_LABEL, "true"));
        when(docker.inspectContainerCmd("workload").exec().getHostConfig())
                .thenReturn(HostConfig.newHostConfig().withNetworkMode("container:helper"));

        manager.recoverOrphans();

        InOrder order = inOrder(lifecycle, firewall, ec2);
        order.verify(lifecycle).stopAndRemoveStrict("workload", null, 1);
        order.verify(lifecycle).removeIfExistsStrict("helper");
        order.verify(firewall).unregister("eni-caller");
        order.verify(ec2).releaseNetworkInterfaceFromService("us-east-1", "eni-caller");
        order.verify(ec2).deleteNetworkInterface("us-east-1", "eni-caller");
    }

    @Test
    void recoveryRefusesToRemoveAnUnownedJoinedWorkload() {
        DockerClient docker = recoveryDocker(Map.of(ContainerStorageHelper.OWNER_LABEL, "another-emulator"));
        when(docker.inspectContainerCmd("workload").exec().getHostConfig())
                .thenReturn(HostConfig.newHostConfig().withNetworkMode("container:helper"));

        assertThrows(IllegalStateException.class, manager::recoverOrphans);

        verify(lifecycle, never()).stopAndRemoveStrict(anyString(), any(), anyInt());
        verify(lifecycle, never()).removeIfExistsStrict(anyString());
        verifyNoInteractions(ec2, firewall);
    }

    private DockerClient recoveryDocker(Map<String, String> workloadLabels) {
        when(config.port()).thenReturn(4566);
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        Container helper = mock(Container.class);
        when(helper.getId()).thenReturn("helper");
        when(helper.getLabels()).thenReturn(Map.of(LambdaVpcNetworkManager.ENI_LABEL, "eni-caller",
                ContainerStorageHelper.ACCOUNT_LABEL, "111111111111",
                ContainerStorageHelper.REGION_LABEL, "us-east-1"));
        Container workload = mock(Container.class);
        when(workload.getId()).thenReturn("workload");
        lenient().when(workload.getLabels()).thenReturn(workloadLabels);
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(list);
        when(list.exec()).thenReturn(List.of(helper), List.of(workload));
        return docker;
    }

    private static LambdaVpcNetworkManager.Attachment attachment() {
        return new LambdaVpcNetworkManager.Attachment("111111111111", "us-east-1", "eni-caller", "helper");
    }

    private static LambdaFunction function() {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionArn("arn:aws:lambda:us-east-1:111111111111:function:network");
        function.setVpcConfig(Map.of("SubnetIds", List.of("subnet-caller"),
                "SecurityGroupIds", List.of("sg-caller")));
        return function;
    }
}
