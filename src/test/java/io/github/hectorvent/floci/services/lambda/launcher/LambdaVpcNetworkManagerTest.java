package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerFlags;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @Mock VpcNetworkManager vpcNetworks;
    private LambdaVpcNetworkManager manager;

    @BeforeEach
    void setUp() {
        manager = new LambdaVpcNetworkManager(config, ec2, firewall, lifecycle, vpcNetworks);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedVpcAttachmentReleasesTheEniOnlyAfterItsHelperIsRemoved(boolean helperRemoved) {
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class, RETURNS_DEEP_STUBS);
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(config.services()).thenReturn(services);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(firewall.enabled()).thenReturn(true);
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-caller");
        eni.setVpcId("vpc-caller");
        eni.setPrivateIpAddress("10.74.1.10");
        when(ec2.createNetworkInterface(anyString(), anyString(), anyString(), isNull(),
                anyList(), anyList(), anyList())).thenReturn(eni);
        when(firewall.createNamespace(anyString(), anyString(), anyString(), anyString(),
                any(), anyMap(), anyMap()))
                .thenReturn(new SecurityGroupFirewallManager.Namespace("helper", "172.17.0.2"));
        when(vpcNetworks.enabled()).thenReturn(true);
        IllegalStateException cleanupFailure = new IllegalStateException("helper removal failed");
        if (!helperRemoved) {
            doThrow(cleanupFailure).when(lifecycle).removeIfExistsStrict("helper");
        }

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                manager.prepare(function(), "environment", "192.0.2.1", 9001, DockerFlags.parse("")));
        assertEquals("Protected Lambda namespace could not attach to its VPC", failure.getMessage());

        if (helperRemoved) {
            InOrder order = inOrder(lifecycle, firewall, ec2);
            order.verify(firewall).unregister("eni-caller");
            order.verify(lifecycle).removeIfExistsStrict("helper");
            order.verify(ec2).releaseNetworkInterfaceFromService("us-east-1", "eni-caller");
            order.verify(ec2).deleteNetworkInterface("us-east-1", "eni-caller");
        } else {
            assertEquals(List.of(cleanupFailure), List.of(failure.getSuppressed()));
            verify(ec2, never()).releaseNetworkInterfaceFromService(anyString(), anyString());
            verify(ec2, never()).deleteNetworkInterface(anyString(), anyString());
        }
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

        verifyNoInteractions(ec2);
        verify(firewall).unregister("eni-caller");
    }

    @Test
    void retirementCanQuarantineTheNamespaceBeforeItDisappears() {
        AtomicBoolean helperExists = new AtomicBoolean(true);
        AtomicBoolean retired = new AtomicBoolean();
        doAnswer(ignored -> {
            if (!helperExists.get()) {
                throw new IllegalStateException("Cannot quarantine a removed namespace");
            }
            retired.set(true);
            return null;
        }).when(firewall).unregister("eni-caller");
        doAnswer(ignored -> {
            helperExists.set(false);
            return null;
        }).when(lifecycle).removeIfExistsStrict("helper");

        assertDoesNotThrow(() -> manager.release(attachment()));

        assertTrue(retired.get());
        assertFalse(helperExists.get());
        verify(ec2).releaseNetworkInterfaceFromService("us-east-1", "eni-caller");
        verify(ec2).deleteNetworkInterface("us-east-1", "eni-caller");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retirementAndRemovalFailuresKeepTheEniReservedAndPreserveBothErrors(boolean sameFailure) {
        IllegalStateException retirementFailure = new IllegalStateException("peer policy failed");
        IllegalStateException removalFailure = sameFailure
                ? retirementFailure : new IllegalStateException("helper is still present");
        doThrow(retirementFailure).when(firewall).unregister("eni-caller");
        doThrow(removalFailure).when(lifecycle).removeIfExistsStrict("helper");

        assertSame(retirementFailure, assertThrows(IllegalStateException.class, () -> manager.release(attachment())));

        assertEquals(sameFailure ? List.of() : List.of(removalFailure), List.of(retirementFailure.getSuppressed()));
        verifyNoInteractions(ec2);
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
        order.verify(firewall).unregister("eni-caller");
        order.verify(lifecycle).removeIfExistsStrict("helper");
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

    @Test
    void theDnsExceptionInstallsOnlyItsOwnPolicyAndTheFinalRegistrationWaitsForPeers() {
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class, RETURNS_DEEP_STUBS);
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(config.services()).thenReturn(services);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(firewall.enabled()).thenReturn(true);
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-caller");
        eni.setVpcId("vpc-caller");
        eni.setPrivateIpAddress("10.74.1.10");
        when(ec2.createNetworkInterface(anyString(), anyString(), anyString(), isNull(),
                anyList(), anyList(), anyList())).thenReturn(eni);
        SecurityGroup group = new SecurityGroup();
        group.setGroupId("sg-caller");
        when(ec2.describeSecurityGroups(anyString(), anyList(), anyList(), anyMap())).thenReturn(List.of(group));
        when(firewall.createNamespace(anyString(), anyString(), anyString(), anyString(),
                any(), anyMap(), anyMap()))
                .thenReturn(new SecurityGroupFirewallManager.Namespace("helper", "172.17.0.2"));
        when(vpcNetworks.attach(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("attached"));
        // The Runtime API host is a name, resolved inside the namespace after the DNS exception.
        when(docker.execCreateCmd("helper").withAttachStdout(true).withAttachStderr(true)
                .withCmd(any(String[].class)).exec().getId()).thenReturn("lookup");
        ExecStartCmd start = mock(ExecStartCmd.class);
        when(docker.execStartCmd("lookup")).thenReturn(start);
        when(start.exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            callback.onNext(new Frame(StreamType.STDOUT, "172.17.0.1 floci-host\n".getBytes()));
            callback.onComplete();
            return callback;
        });
        when(docker.inspectExecCmd("lookup").exec().getExitCodeLong()).thenReturn(0L);

        assertTrue(manager.prepare(function(), "environment", "floci-host", 9001, DockerFlags.parse(""))
                .isPresent());

        InOrder order = inOrder(firewall);
        order.verify(firewall).register(any(), eq("helper"), anyMap(), eq(false));
        order.verify(firewall).register(any(), eq("helper"), anyMap());
        verify(firewall, never()).register(any(), anyString(), anyMap(), eq(true));
    }

    private static LambdaFunction function() {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionArn("arn:aws:lambda:us-east-1:111111111111:function:network");
        function.setVpcConfig(Map.of("SubnetIds", List.of("subnet-caller"),
                "SecurityGroupIds", List.of("sg-caller")));
        return function;
    }
}
