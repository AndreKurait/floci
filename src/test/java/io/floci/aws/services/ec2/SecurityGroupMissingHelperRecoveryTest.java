package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityGroupMissingHelperRecoveryTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void retirementStopsJoinedWorkloadsAndDistinguishesMissingHelperFromMissingExec(boolean helperMissing) {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        SecurityGroupFirewallManager manager = SecurityGroupDnsFixture.manager(docker,
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS));
        manager.register(new SecurityGroupNftCompiler.Endpoint(
                "111111111111", "us-east-1", "vpc-missing", "eni-missing",
                "10.0.1.10", "172.18.0.3", Set.of("sg"), List.of(), List.of(),
                false, Set.of()), "helper-missing", Map.of());
        AtomicBoolean workerStopped = new AtomicBoolean();
        AtomicBoolean helperStopped = new AtomicBoolean();
        Container worker = mock(Container.class);
        when(worker.getId()).thenReturn("namespace-worker");
        when(docker.listContainersCmd().exec()).thenReturn(List.of(worker));
        InspectContainerResponse workerInspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(workerInspect.getId()).thenReturn("namespace-worker");
        when(workerInspect.getHostConfig().getNetworkMode()).thenReturn("container:helper-missing");
        when(workerInspect.getState().getRunning()).thenAnswer(ignored -> !workerStopped.get());
        when(docker.inspectContainerCmd("namespace-worker").exec()).thenReturn(workerInspect);
        InspectContainerResponse helperInspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(helperInspect.getId()).thenReturn("helper-missing");
        when(helperInspect.getState().getRunning()).thenAnswer(ignored -> !helperStopped.get());
        if (helperMissing) {
            when(docker.inspectContainerCmd("helper-missing").exec())
                    .thenThrow(new NotFoundException("No such container: helper-missing"));
        } else {
            when(docker.inspectContainerCmd("helper-missing").exec()).thenReturn(helperInspect);
        }
        when(docker.copyArchiveToContainerCmd("helper-missing"))
                .thenThrow(new NotFoundException(helperMissing ? "No such container" : "No such exec"));
        StopContainerCmd stopWorker = mock(StopContainerCmd.class, RETURNS_SELF);
        when(docker.stopContainerCmd("namespace-worker")).thenReturn(stopWorker);
        doAnswer(ignored -> {
            workerStopped.set(true);
            return null;
        }).when(stopWorker).exec();
        StopContainerCmd stopHelper = mock(StopContainerCmd.class, RETURNS_SELF);
        when(docker.stopContainerCmd("helper-missing")).thenReturn(stopHelper);
        doAnswer(ignored -> {
            helperStopped.set(true);
            return null;
        }).when(stopHelper).exec();
        clearInvocations(docker);

        manager.unregister("eni-missing");

        assertTrue(workerStopped.get(), "A joined workload can retain the namespace after its helper disappears");
        if (helperMissing) {
            verify(docker, never()).copyArchiveToContainerCmd("helper-missing");
        } else {
            verify(docker).copyArchiveToContainerCmd("helper-missing");
            assertTrue(helperStopped.get(), "A missing exec must not be mistaken for a missing helper");
        }
    }
}
