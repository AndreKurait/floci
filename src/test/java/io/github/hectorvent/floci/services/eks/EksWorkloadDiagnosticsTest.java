package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.testutil.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EksWorkloadDiagnosticsTest {

    private EksRegistryHostsDockerIntegrationTest fixture;
    private ExecCreateCmd create;
    private ExecStartCmd start;

    @BeforeEach
    void setUp() {
        fixture = new EksRegistryHostsDockerIntegrationTest();
        fixture.dockerClient = mock(DockerClient.class);
        create = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn("diagnostic-exec");
        when(create.exec()).thenReturn(created);
        when(fixture.dockerClient.execCreateCmd("owned-container")).thenReturn(create);
        start = mock(ExecStartCmd.class);
        when(fixture.dockerClient.execStartCmd("diagnostic-exec")).thenReturn(start);
        InspectExecCmd inspect = mock(InspectExecCmd.class);
        InspectExecResponse result = mock(InspectExecResponse.class);
        when(result.getExitCodeLong()).thenReturn(0L);
        when(inspect.exec()).thenReturn(result);
        when(fixture.dockerClient.inspectExecCmd("diagnostic-exec")).thenReturn(inspect);
        when(start.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onStart(() -> { });
            callback.onNext(new Frame(StreamType.STDOUT, "x".repeat(5000).getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        });
    }

    @Test
    void capturesOnlyBoundedWorkloadStatusCommandsAndCapsPrintedOutput() {
        List<LogRecord> records = LogCapture.capture(EksRegistryHostsDockerIntegrationTest.class,
                () -> fixture.logWorkloadDiagnostics("owned-container"));
        ArgumentCaptor<String[]> commands = ArgumentCaptor.forClass(String[].class);
        verify(create, times(4)).withCmd(commands.capture());
        assertEquals(List.of("pod", "events", "nodes", "leases"),
                commands.getAllValues().stream().map(command -> command[3]).toList());
        for (String[] command : commands.getAllValues()) {
            assertEquals(List.of("kubectl", "--request-timeout=2s", "get"), List.of(command).subList(0, 3));
            assertTrue(List.of(command).stream().anyMatch(arg -> arg.startsWith("jsonpath=")));
        }
        assertEquals(4, records.size());
        for (LogRecord record : records) {
            assertEquals("x".repeat(4096), record.getParameters()[3]);
        }
    }

    @Test
    void failedDiagnosticDoesNotPreventRemainingEvidence() {
        when(fixture.dockerClient.execCreateCmd("owned-container"))
                .thenThrow(new IllegalStateException("unavailable")).thenReturn(create);
        assertDoesNotThrow(() -> fixture.logWorkloadDiagnostics("owned-container"));
        verify(fixture.dockerClient, times(4)).execCreateCmd("owned-container");
        verify(start, times(3)).exec(any());
    }

    @Test
    void interruptionStopsDiagnosticCollection() {
        when(fixture.dockerClient.execCreateCmd("owned-container")).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        });
        try {
            assertThrows(IllegalStateException.class, () -> fixture.logWorkloadDiagnostics("owned-container"));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(fixture.dockerClient).execCreateCmd("owned-container");
        } finally {
            Thread.interrupted();
        }
    }
}
