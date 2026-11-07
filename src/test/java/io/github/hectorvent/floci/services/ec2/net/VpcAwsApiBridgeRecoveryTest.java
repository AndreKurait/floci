package io.github.hectorvent.floci.services.ec2.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerHostConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VpcAwsApiBridgeRecoveryTest {
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final DockerClient docker = mock(DockerClient.class);
    private final ContainerBuilder containers = mock(ContainerBuilder.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
    private final ListContainersCmd listed = mock(ListContainersCmd.class, RETURNS_SELF);
    private final IllegalStateException allocation = new IllegalStateException("Replacement allocation reached");
    private final VpcAwsApiBridge bridge = new VpcAwsApiBridge(
            config, docker, containers, lifecycle, new ObjectMapper(), endpoint);

    VpcAwsApiBridgeRecoveryTest() {
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("recovery-owner"));
        when(config.network().securityGroupEnforcement().helperImage()).thenReturn("helper-image");
        when(endpoint.baseUrl(false)).thenReturn("http://172.18.0.2:4566");
        when(docker.listContainersCmd()).thenReturn(listed);
        when(containers.newContainer("helper-image")).thenThrow(allocation);
    }

    @Test
    void replacementRetiresOnlyThePreviousBridgeOfThisOwner() {
        Container old = helper("old", owner(), "vpc-aws-api");
        Container foreign = helper("foreign", "another-owner", "vpc-aws-api");
        Container otherService = helper("other-service", owner(), "vpc-nat");
        when(listed.exec()).thenReturn(List.of(old, foreign, otherService));

        assertSame(allocation, assertThrows(IllegalStateException.class, () -> bridge.ensure("public-network")));
        InOrder order = inOrder(lifecycle, containers);
        order.verify(lifecycle).removeIfExistsStrict("old");
        order.verify(containers).newContainer("helper-image");
        verifyNoMoreInteractions(lifecycle);
        verify(listed).withLabelFilter(Map.of(ContainerStorageHelper.OWNER_LABEL, owner(),
                ContainerStorageHelper.SERVICE_LABEL, "vpc-aws-api"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"old", "old-name", "/old-name"})
    void aNamespaceConsumerPreventsRemovalAndReplacement(String reference) {
        Container old = helper("old", owner(), "vpc-aws-api");
        Container consumer = helper("consumer", owner(), "ec2");
        when(consumer.getHostConfig()).thenReturn(
                new ContainerHostConfig().withNetworkMode("container:" + reference));
        when(listed.exec()).thenReturn(List.of(old, consumer));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bridge.ensure("public-network"));
        assertTrue(failure.getMessage().contains("Network namespace still has a consumer"));
        verifyNoInteractions(lifecycle, containers);
    }

    @Test
    void failedRetirementDoesNotAllocateAReplacement() {
        Container old = helper("old", owner(), "vpc-aws-api");
        when(listed.exec()).thenReturn(List.of(old));
        IllegalStateException failure = new IllegalStateException("Docker removal failed");
        doThrow(failure).when(lifecycle).removeIfExistsStrict("old");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> bridge.ensure("public-network")));
        verifyNoInteractions(containers);
    }

    @Test
    void unrelatedHelpersRemainWhenNoPriorBridgeExists() {
        Container foreign = helper("foreign", "another-owner", "vpc-aws-api");
        when(listed.exec()).thenReturn(List.of(foreign));
        assertSame(allocation, assertThrows(IllegalStateException.class, () -> bridge.ensure("public-network")));
        verifyNoInteractions(lifecycle);
    }

    private String owner() {
        return ContainerStorageHelper.ownerIdentity(config);
    }

    private Container helper(String id, String owner, String service) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getNames()).thenReturn(new String[]{"/" + id + "-name"});
        when(container.getLabels()).thenReturn(Map.of(
                ContainerStorageHelper.OWNER_LABEL, owner, ContainerStorageHelper.SERVICE_LABEL, service));
        when(container.getHostConfig()).thenReturn(new ContainerHostConfig().withNetworkMode("bridge"));
        return container;
    }
}
