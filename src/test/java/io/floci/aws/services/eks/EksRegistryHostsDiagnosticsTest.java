package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import io.floci.aws.services.eks.model.Cluster;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EksRegistryHostsDiagnosticsTest {

    @Test
    void capturesReplacementBeforeCleanupAndPreservesOriginalFailure() {
        List<String> events = new ArrayList<>();
        AssertionError original = new AssertionError("pod remains pending");
        EksRegistryHostsDockerIntegrationTest fixture = new EksRegistryHostsDockerIntegrationTest() {
            @Override
            void verifyRegistryHosts() {
                diagnosticClusterName = "owned-cluster";
                cleanup.register(() -> events.add("cleanup"));
                throw original;
            }

            @Override
            void logPodDiagnostics(String containerId) {
                events.add(containerId);
            }
        };
        fixture.eksService = mock(EksService.class);
        Cluster replacement = new Cluster();
        replacement.setContainerId("replacement");
        when(fixture.eksService.describeCluster("owned-cluster")).thenReturn(replacement);
        assertSame(original, assertThrows(AssertionError.class,
                fixture::podPullsThroughRegistryHostConfiguredViaLaunchTemplateUserData));
        assertEquals(List.of("replacement"), events);
        fixture.cleanup.afterEach(null);
        assertEquals(List.of("replacement", "cleanup"), events);
    }

    @Test
    void lookupFailureDoesNotReplaceExecFailure() {
        IllegalStateException original = new IllegalStateException("container is not running");
        EksRegistryHostsDockerIntegrationTest fixture = new EksRegistryHostsDockerIntegrationTest() {
            @Override
            void verifyRegistryHosts() {
                diagnosticClusterName = "owned-cluster";
                throw original;
            }
        };
        fixture.eksService = mock(EksService.class);
        IllegalStateException missing = new IllegalStateException("cluster already absent");
        when(fixture.eksService.describeCluster("owned-cluster")).thenThrow(missing);
        assertSame(original, assertThrows(IllegalStateException.class,
                fixture::podPullsThroughRegistryHostConfiguredViaLaunchTemplateUserData));
        assertArrayEquals(new Throwable[]{missing}, original.getSuppressed());
    }

    @Test
    void failureBeforeClusterAllocationDoesNotInspectUnownedResources() {
        EksRegistryHostsDockerIntegrationTest fixture = new EksRegistryHostsDockerIntegrationTest() {
            @Override
            void verifyRegistryHosts() {
                throw new IllegalStateException("image upload failed");
            }
        };
        fixture.eksService = mock(EksService.class);
        assertThrows(IllegalStateException.class,
                fixture::podPullsThroughRegistryHostConfiguredViaLaunchTemplateUserData);
        verifyNoInteractions(fixture.eksService);
    }

    @Test
    void stoppedContainerCapturesStateAndLogsWithoutExec() throws Exception {
        EksRegistryHostsDockerIntegrationTest fixture = new EksRegistryHostsDockerIntegrationTest();
        fixture.dockerClient = mock(DockerClient.class);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        LogContainerCmd logs = mock(LogContainerCmd.class, RETURNS_SELF);
        when(fixture.dockerClient.inspectContainerCmd("stopped")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(false);
        when(state.getOOMKilled()).thenReturn(true);
        when(state.getExitCodeLong()).thenReturn(137L);
        when(fixture.dockerClient.logContainerCmd("stopped")).thenReturn(logs);
        when(logs.exec(any())).thenAnswer(invocation -> {
            ResultCallback<?> callback = invocation.getArgument(0);
            callback.onComplete();
            return callback;
        });
        fixture.logPodDiagnostics("stopped");
        InOrder order = inOrder(fixture.dockerClient, state);
        order.verify(fixture.dockerClient).inspectContainerCmd("stopped");
        order.verify(state).getExitCodeLong();
        order.verify(state).getOOMKilled();
        order.verify(fixture.dockerClient).logContainerCmd("stopped");
        verify(fixture.dockerClient, never()).execCreateCmd(anyString());
    }
}
