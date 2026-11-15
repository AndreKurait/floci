package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Container;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityGroupFirewallRemoteWriterTest extends SecurityGroupFirewallPeerFixture {
    private DockerClient client;

    @Override DockerClient docker() {
        client = super.docker();
        return client;
    }

    @Test
    void lateRemoteCompletionCannotRaceQuarantineOrPermitReuseAfterFailedStop() throws Exception {
        SecurityGroupFirewallManager manager = ready();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<ResultCallback.Adapter<?>> remoteCallback = timedOut(running);
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        when(stop.exec()).thenThrow(new IllegalStateException("stop unavailable"));
        when(client.stopContainerCmd(helper(0))).thenReturn(stop);
        IllegalStateException failure = assertThrows(IllegalStateException.class, manager::reconcileAll);
        assertTrue(failure.getMessage().contains("Could not retire security-group namespace"));
        assertEquals(1, installs.size(), "no quarantine nft may race the unresolved writer");
        assertFalse(stoppedHelpers.contains(helper(0)));
        running.set(false);
        remoteCallback.get().onComplete();
        assertThrows(IllegalStateException.class, manager::reconcileAll);
        assertEquals(1, installs.size(), "a late completion must not clear the refusal fence");
        assertTrue(client.inspectContainerCmd(helper(0)).exec().getState().getRunning());
    }

    @Test
    void terminalSuccessfulExecResolvesLostCompletionWithoutStoppingHelper() throws Exception {
        SecurityGroupFirewallManager manager = ready();
        timedOut(new AtomicBoolean(false));
        manager.reconcileAll();
        assertEquals(1, installs.size());
        assertTrue(stoppedHelpers.isEmpty());
        assertFinal(1);
    }

    @Test
    void runningExecRetiresHelperWithoutIssuingAnotherPolicy() throws Exception {
        SecurityGroupFirewallManager manager = ready();
        timedOut(new AtomicBoolean(true));
        assertThrows(IllegalStateException.class, manager::reconcileAll);
        assertTrue(stoppedHelpers.contains(helper(0)));
        assertEquals(1, installs.size());
    }

    @Test
    void execStartTransportFailureAlsoFencesRemoteWriter() throws Exception {
        SecurityGroupFirewallManager manager = ready();
        timedOut(new AtomicBoolean(true));
        ExecStartCmd start = mock(ExecStartCmd.class);
        when(start.exec(any())).thenThrow(new IllegalStateException("lost exec-start response"));
        when(client.execStartCmd("exec-" + helper(0))).thenReturn(start);
        assertThrows(IllegalStateException.class, manager::reconcileAll);
        assertTrue(stoppedHelpers.contains(helper(0)));
        assertEquals(1, installs.size());
    }

    @Test
    void failedExecInspectionCannotBeTreatedAsCompleted() throws Exception {
        SecurityGroupFirewallManager manager = ready();
        timedOut(new AtomicBoolean(false));
        when(client.inspectExecCmd("exec-" + helper(0)).exec()).thenThrow(new IllegalStateException("inspect unavailable"));
        assertThrows(IllegalStateException.class, manager::reconcileAll);
        assertTrue(stoppedHelpers.contains(helper(0)));
        assertEquals(1, installs.size());
    }

    @Test
    void alreadyStoppedExactHelperDoesNotNeedAnotherStop() throws Exception {
        SecurityGroupFirewallManager manager = ready();
        timedOut(new AtomicBoolean(true));
        InspectExecResponse result = client.inspectExecCmd("exec-" + helper(0)).exec();
        when(result.isRunning()).thenAnswer(invocation -> {
            stoppedHelpers.add(helper(0));
            return true;
        });
        assertThrows(IllegalStateException.class, manager::reconcileAll);
        verify(client, never()).stopContainerCmd(helper(0));
        assertEquals(1, installs.size());
    }

    @Test
    void successfulStopResponseWithoutStoppedStateStillFails() throws Exception {
        SecurityGroupFirewallManager manager = ready();
        timedOut(new AtomicBoolean(true));
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        when(client.stopContainerCmd(helper(0))).thenReturn(stop);
        IllegalStateException failure = assertThrows(IllegalStateException.class, manager::reconcileAll);
        assertTrue(failure.getMessage().contains("Could not retire security-group namespace"));
        assertFalse(stoppedHelpers.contains(helper(0)));
        assertEquals(1, installs.size());
    }

    @Test
    void workloadStopFailureCannotSkipExactHelperRetirement() throws Exception {
        SecurityGroupFirewallManager manager = ready();
        timedOut(new AtomicBoolean(true));
        Container workload = mock(Container.class);
        when(workload.getId()).thenReturn("workload");
        when(client.listContainersCmd().exec()).thenReturn(List.of(workload));
        InspectContainerResponse inspected = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(inspected.getId()).thenReturn("workload");
        when(inspected.getHostConfig().getNetworkMode()).thenReturn("container:" + helper(0));
        when(inspected.getState().getRunning()).thenReturn(true);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(inspect.exec()).thenReturn(inspected);
        when(client.inspectContainerCmd("workload")).thenReturn(inspect);
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        when(stop.exec()).thenThrow(new IllegalStateException("workload stop unavailable"));
        when(client.stopContainerCmd("workload")).thenReturn(stop);
        IllegalStateException failure = assertThrows(IllegalStateException.class, manager::reconcileAll);
        assertTrue(failure.getMessage().contains("Could not retire security-group namespace"));
        assertTrue(stoppedHelpers.contains(helper(0)));
        assertEquals(1, installs.size());
    }

    @Test
    void failedUnregisterQuarantineRetainsObserverCleanupRequest() throws Exception {
        SecurityGroupFirewallManager manager = ready();
        timedOut(new AtomicBoolean(true));
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        when(stop.exec()).thenThrow(new IllegalStateException("stop unavailable"));
        when(client.stopContainerCmd(helper(0))).thenReturn(stop);
        assertThrows(IllegalStateException.class, () -> manager.unregister("eni-0"));
        verify(lifecycle, never()).removeIfExistsStrict(helper(0));
        manager.retireDnsOrigins();
        verify(lifecycle).removeIfExistsStrict(helper(0));
        assertEquals(1, installs.size());
    }

    private SecurityGroupFirewallManager ready() {
        SecurityGroupFirewallManager manager = manager();
        manager.register(endpoint(0), helper(0), Map.of());
        installs.clear();
        return manager;
    }

    private AtomicReference<ResultCallback.Adapter<?>> timedOut(AtomicBoolean running) throws Exception {
        AtomicReference<ResultCallback.Adapter<?>> callback = new AtomicReference<>();
        ExecStartCmd start = mock(ExecStartCmd.class);
        ResultCallback.Adapter<?> awaiter = mock(ResultCallback.Adapter.class);
        when(awaiter.awaitCompletion(anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(start.exec(any())).thenAnswer(invocation -> {
            callback.set(invocation.getArgument(0));
            return awaiter;
        });
        when(client.execStartCmd("exec-" + helper(0))).thenReturn(start);
        InspectExecResponse result = mock(InspectExecResponse.class);
        when(result.getId()).thenReturn("exec-" + helper(0));
        when(result.getContainerID()).thenReturn(helper(0));
        when(result.isRunning()).thenAnswer(invocation -> running.get());
        when(result.getExitCodeLong()).thenReturn(0L);
        when(client.inspectExecCmd("exec-" + helper(0)).exec()).thenReturn(result);
        return callback;
    }
}
