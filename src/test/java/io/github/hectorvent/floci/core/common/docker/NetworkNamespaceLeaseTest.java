package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerHostConfig;
import com.github.dockerjava.api.model.HostConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetworkNamespaceLeaseTest {
    @ParameterizedTest
    @ValueSource(strings = {"helper-id", "helper-name", "/helper-name"})
    void aConsumerRetainsTheLeaseEvenWhenTheHelperIsGone(String reference) {
        DockerClient docker = dockerWithConsumer(reference);
        assertThrows(IllegalStateException.class,
                () -> new NetworkNamespaceLease("helper-id", "/helper-name").requireUnused(docker));
    }

    @ParameterizedTest
    @ValueSource(strings = {"another-helper", "/another-helper"})
    void anotherRemovedNamespaceDoesNotRetainThisLease(String reference) {
        DockerClient docker = dockerWithConsumer(reference);
        InspectContainerCmd missing = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("another-helper")).thenReturn(missing);
        when(missing.exec()).thenThrow(new NotFoundException("removed"));
        assertDoesNotThrow(() -> new NetworkNamespaceLease("helper-id", "/helper-name").requireUnused(docker));
    }

    @Test
    void anUnrelatedContainerDoesNotNeedInspectionDuringItsOwnStop() {
        DockerClient docker = dockerWithListedContainer("bridge");
        assertDoesNotThrow(() -> new NetworkNamespaceLease("helper-id", "helper-name").requireUnused(docker));
        verify(docker, never()).inspectContainerCmd("consumer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"helper-id", "helper-name", "/helper-name"})
    void aListedConsumerKeepsTheReservationWithoutAnotherInspect(String reference) {
        DockerClient docker = dockerWithListedContainer("container:" + reference);
        assertThrows(IllegalStateException.class,
                () -> new NetworkNamespaceLease("helper-id", "helper-name").requireUnused(docker));
        verify(docker, never()).inspectContainerCmd("consumer");
    }

    @Test
    void aShortReferenceResolvesToTheLeasedContainer() {
        DockerClient docker = dockerWithListedContainer("container:0123456789ab");
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(docker.inspectContainerCmd("0123456789ab")).thenReturn(command);
        when(command.exec()).thenReturn(response);
        when(response.getId()).thenReturn("0123456789abcdef");
        assertThrows(IllegalStateException.class,
                () -> new NetworkNamespaceLease("0123456789abcdef", "helper-name").requireUnused(docker));
    }

    @Test
    void aShortReferenceStillRetainsTheLeaseAfterTheHelperIsRemoved() {
        DockerClient docker = dockerWithListedContainer("container:0123456789ab");
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("0123456789ab")).thenReturn(command);
        when(command.exec()).thenThrow(new NotFoundException("removed"));
        assertThrows(IllegalStateException.class,
                () -> new NetworkNamespaceLease("0123456789abcdef", "helper-name").requireUnused(docker));
    }

    @Test
    void anotherLiveContainerNamedWithTheLeasePrefixDoesNotRetainIt() {
        DockerClient docker = dockerWithListedContainer("container:0123456789ab");
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(docker.inspectContainerCmd("0123456789ab")).thenReturn(command);
        when(command.exec()).thenReturn(response);
        when(response.getId()).thenReturn("another-helper-id");
        assertDoesNotThrow(() -> new NetworkNamespaceLease("0123456789abcdef", "helper-name").requireUnused(docker));
    }

    private DockerClient dockerWithListedContainer(String mode) {
        DockerClient docker = mock(DockerClient.class);
        Container container = mock(Container.class);
        when(container.getId()).thenReturn("consumer");
        when(container.getHostConfig()).thenReturn(new ContainerHostConfig().withNetworkMode(mode));
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(list);
        when(list.exec()).thenReturn(List.of(container));
        return docker;
    }

    private DockerClient dockerWithConsumer(String reference) {
        DockerClient docker = mock(DockerClient.class);
        Container consumer = mock(Container.class);
        when(consumer.getId()).thenReturn("consumer");
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(list);
        when(list.exec()).thenReturn(List.of(consumer));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("consumer")).thenReturn(inspect);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(response.getHostConfig()).thenReturn(HostConfig.newHostConfig().withNetworkMode("container:" + reference));
        when(inspect.exec()).thenReturn(response);
        return docker;
    }
}
