package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityGroupFirewallRestartFenceTest extends SecurityGroupFirewallPeerFixture {
    private DockerClient client;

    @Override DockerClient docker() {
        if (client == null) {
            client = super.docker();
        }
        return client;
    }

    @Test
    void pendingIdentityIsOnDiskBeforeExecStartAndCompletionLeavesAReusableRecord() throws Exception {
        SecurityGroupFirewallManager manager = ready(1);
        AtomicInteger starts = new AtomicInteger();
        when(client.execStartCmd(anyString())).thenAnswer(invocation -> {
            String execId = invocation.getArgument(0);
            ExecStartCmd start = mock(ExecStartCmd.class);
            when(start.exec(any())).thenAnswer(call -> {
                JsonNode record = new ObjectMapper().readTree(Files.readString(record(0)));
                assertEquals("pending", record.get("state").textValue());
                assertEquals(execId, record.get("execId").textValue());
                assertEquals(helper(0), record.get("helperId").textValue());
                assertEquals("0", record.get("owner").textValue());
                starts.incrementAndGet();
                ResultCallback.Adapter<?> callback = call.getArgument(0);
                callback.onComplete();
                return callback;
            });
            return start;
        });
        manager.reconcileAll();
        assertEquals(1, starts.get());
        try (PolicyWriterJournal.Lease lease = journal().acquire(helper(0))) {
            assertNull(lease.pendingExecId());
        }
    }

    @Test
    void completedPolicyRestartKeepsTheSameHelperAndCanReadmitItsEndpoint() {
        ready(1);
        SecurityGroupFirewallManager replacement = manager();
        replacement.quarantineSurvivingNamespaces();
        assertEquals(1, installs.size());
        assertTrue(stoppedHelpers.isEmpty());
        replacement.register(endpoint(0), helper(0), Map.of());
        assertTrue(stoppedHelpers.isEmpty());
        assertFinal(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void terminalSavedExecResolvesCrashBeforeJournalCompletionWithoutReplacingHelper(long exit) {
        ready(1);
        pending(0, false, exit, "saved-0", helper(0));
        manager().quarantineSurvivingNamespaces();
        assertEquals(1, installs.size());
        assertTrue(stoppedHelpers.isEmpty());
        try (PolicyWriterJournal.Lease lease = journal().acquire(helper(0))) {
            assertNull(lease.pendingExecId());
        }
    }

    @Test
    void stoppedHelperCanResolveItsExactTerminalWriterWithoutStartingAnyNewExec() {
        ready(1);
        pending(0, false, 137L, "saved-0", helper(0));
        stoppedHelpers.add(helper(0));
        Container stopped = survivor(0);
        when(stopped.getState()).thenReturn("exited");
        when(client.listContainersCmd().exec()).thenReturn(List.of(stopped));
        manager().quarantineSurvivingNamespaces();
        assertTrue(installs.isEmpty());
        try (PolicyWriterJournal.Lease lease = journal().acquire(helper(0))) {
            assertNull(lease.pendingExecId());
        }
    }

    @ParameterizedTest
    @MethodSource("unresolved")
    void unresolvedOrMismatchedSavedExecCannotDispatchAnotherWriter(
            Boolean running, Long exit, String reportedExec, String reportedHelper) {
        ready(1);
        pending(0, running, exit, reportedExec, reportedHelper);
        manager().quarantineSurvivingNamespaces();
        assertTrue(installs.isEmpty(), "neither a new archive copy nor exec may precede resolution");
        assertTrue(stoppedHelpers.contains(helper(0)));
    }

    private static Stream<Arguments> unresolved() {
        return Stream.of(
                Arguments.of(true, null, "saved-0", "helper-0"),
                Arguments.of(true, 0L, "saved-0", "helper-0"),
                Arguments.of(false, null, "saved-0", "helper-0"),
                Arguments.of(null, 0L, "saved-0", "helper-0"),
                Arguments.of(false, 0L, "other-exec", "helper-0"),
                Arguments.of(false, 0L, "saved-0", "other-helper"),
                Arguments.of(false, 0L, null, "helper-0"),
                Arguments.of(false, 0L, "saved-0", null));
    }

    @Test
    void missingExecIsUnresolvedAndCannotAuthorizeAnotherWriter() {
        ready(1);
        pending(0, false, 0L, "saved-0", helper(0));
        when(client.inspectExecCmd("saved-0").exec()).thenThrow(new NotFoundException("exec missing"));
        manager().quarantineSurvivingNamespaces();
        assertTrue(installs.isEmpty());
        assertTrue(stoppedHelpers.contains(helper(0)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingPreJournalOrCorruptRecordRetiresWithoutDispatch(boolean corrupt) throws Exception {
        ready(1);
        if (corrupt) {
            Files.writeString(record(0), "{broken");
        } else {
            Files.delete(record(0));
        }
        manager().quarantineSurvivingNamespaces();
        assertTrue(installs.isEmpty());
        assertTrue(stoppedHelpers.contains(helper(0)));
    }

    @Test
    void failedRetirementStaysVisibleAndCannotSkipAnotherOwnedUnresolvedHelper() {
        ready(2);
        pending(0, true, null, "saved-0", helper(0));
        pending(1, true, null, "saved-1", helper(1));
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        when(stop.exec()).thenThrow(new IllegalStateException("stop unavailable"));
        when(client.stopContainerCmd(helper(0))).thenReturn(stop);
        assertThrows(IllegalStateException.class, () -> manager().quarantineSurvivingNamespaces());
        assertTrue(installs.isEmpty());
        assertFalse(stoppedHelpers.contains(helper(0)));
        assertTrue(stoppedHelpers.contains(helper(1)));
    }

    @Test
    void anotherOwnersHelperIsNeverRecoveredOrRetired() {
        ready(1);
        Container other = survivor(0);
        when(other.getLabels()).thenReturn(Map.of(
                ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true",
                ContainerStorageHelper.OWNER_LABEL, "someone-else"));
        when(client.listContainersCmd().exec()).thenReturn(List.of(other));
        manager().quarantineSurvivingNamespaces();
        assertTrue(installs.isEmpty());
        assertTrue(stoppedHelpers.isEmpty());
    }

    @Test
    void confirmedHelperRemovalReleasesItsJournalFiles() throws Exception {
        SecurityGroupFirewallManager manager = ready(1);
        Path record = record(0);
        manager.unregister("eni-0");
        assertFalse(Files.exists(record));
        assertTrue(journal().helpers().isEmpty());
    }

    private SecurityGroupFirewallManager ready(int count) {
        SecurityGroupFirewallManager manager = manager();
        List<Container> survivors = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            manager.register(endpoint(index), helper(index), Map.of());
            survivors.add(survivor(index));
        }
        when(client.listContainersCmd().exec()).thenReturn(survivors);
        installs.clear();
        return manager;
    }

    private Container survivor(int index) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(helper(index));
        when(container.getState()).thenReturn("running");
        when(container.getLabels()).thenReturn(Map.of(
                ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true",
                ContainerStorageHelper.OWNER_LABEL, "0",
                ContainerStorageHelper.SERVICE_LABEL, "ec2"));
        return container;
    }

    private void pending(int index, Boolean running, Long exit, String reportedExec, String reportedHelper) {
        String execId = "saved-" + index;
        try (PolicyWriterJournal.Lease lease = journal().acquire(helper(index))) {
            lease.started(execId);
        }
        InspectExecCmd inspect = mock(InspectExecCmd.class);
        InspectExecResponse result = mock(InspectExecResponse.class);
        when(result.getId()).thenReturn(reportedExec);
        when(result.getContainerID()).thenReturn(reportedHelper);
        when(result.isRunning()).thenReturn(running);
        when(result.getExitCodeLong()).thenReturn(exit);
        when(inspect.exec()).thenReturn(result);
        when(client.inspectExecCmd(execId)).thenReturn(inspect);
    }

    private PolicyWriterJournal journal() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(writerDirectory.toString());
        return new PolicyWriterJournal(config);
    }

    private Path record(int index) throws Exception {
        try (Stream<Path> files = Files.walk(writerDirectory)) {
            return files.filter(path -> path.getFileName().toString().equals(helper(index) + ".json"))
                    .findFirst().orElseThrow();
        }
    }
}
