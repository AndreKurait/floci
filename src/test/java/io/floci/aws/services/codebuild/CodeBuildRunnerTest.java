package io.floci.aws.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeBuildRunnerTest {

    @Test
    void phaseWithMoreOutputThanOneBufferCanHoldStillReportsItsLastOutput() {
        byte[] chunk = new byte[8 * 1024 * 1024];
        Arrays.fill(chunk, (byte) 'x');
        // 2.5 GiB in total: more than a single Java byte array can hold.
        int chunkCount = 320;
        String lastLine = "error: build step failed\n";
        DockerClient dockerClient = dockerClientStreaming(chunk, chunkCount, lastLine, 2L);
        CodeBuildRunner runner = new CodeBuildRunner(dockerClient, null, null, null, null, null, null, null, null, null, null);

        CodeBuildRunner.PhaseResult result = runner.runPhase("container-1", "/src", List.of(),
                List.of("./build.sh"), 60, new AtomicBoolean(false));

        assertTrue(result.failed());
        String expectedTail = "x".repeat(512 - "error: build step failed".length()) + "error: build step failed";
        assertEquals("Exit code 2: " + expectedTail, result.errorMessage());
    }

    @Test
    void failureMessageIgnoresLongTrailingWhitespace() {
        DockerClient dockerClient = dockerClientStreaming(new byte[0], 0, "tests failed" + "\n".repeat(10_000), 1L);
        CodeBuildRunner runner = new CodeBuildRunner(dockerClient, null, null, null, null, null, null, null, null, null, null);

        CodeBuildRunner.PhaseResult result = runner.runPhase("container-1", "/src", List.of(),
                List.of("./test.sh"), 60, new AtomicBoolean(false));

        assertEquals("Exit code 1: tests failed", result.errorMessage());
    }

    @Test
    void phaseOutputPreservesSplitUtf8AndFlushesBothStreamsOnce() {
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
        DockerClient dockerClient = dockerClientStreaming(new byte[]{euro[0]}, 1, "failure-tail", 7L);
        ExecStartCmd startCmd = dockerClient.execStartCmd("exec-1");
        doAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            callback.onNext(new Frame(StreamType.STDOUT, new byte[]{euro[0]}));
            callback.onNext(new Frame(StreamType.STDERR, "stderr\npartial-error".getBytes(StandardCharsets.UTF_8)));
            callback.onNext(new Frame(StreamType.STDOUT, new byte[]{euro[1], euro[2], '\n'}));
            callback.onNext(new Frame(StreamType.STDOUT, "partial-out".getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        }).when(startCmd).exec(any());
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        List<String> messages = new ArrayList<>();
        when(logs.putLogEventsForAccount(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assertEquals("111111111111", invocation.getArgument(0));
            assertEquals("/aws/codebuild/project", invocation.getArgument(1));
            assertEquals("build-stream", invocation.getArgument(2));
            assertEquals("ap-northeast-3", invocation.getArgument(4));
            List<Map<String, Object>> events = invocation.getArgument(3);
            events.forEach(event -> messages.add((String) event.get("message")));
            return null;
        });
        ContainerLogStreamer streamer = new ContainerLogStreamer(null, logs);
        CodeBuildRunner runner = new CodeBuildRunner(dockerClient, null, null, streamer,
                null, null, null, null, null, null, null);

        CodeBuildRunner.PhaseResult result = runner.runPhase("container-1", "/src", List.of(),
                List.of("./build.sh"), 1, new AtomicBoolean(false),
                streamer.execLineLogCallbackForAccount("111111111111", "/aws/codebuild/project",
                        "build-stream", "ap-northeast-3"));

        assertTrue(result.failed());
        assertTrue(result.errorMessage().startsWith("Exit code 7:"));
        assertEquals(List.of("stderr", "€", "partial-error", "partial-out"), messages);
    }

    @Test
    void endpointUrlUsesListenPortNotFlociPort() {
        // QUARKUS_HTTP_PORT=4811 with floci.port left at 4566: the server listens on 4811.
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.tls()).thenReturn(mock(EmulatorConfig.TlsConfig.class));
        when(config.port()).thenReturn(4566);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        ContainerReachableEndpoint endpoint = new ContainerReachableEndpoint(config, null, null, null, 4811);
        CodeBuildRunner runner = new CodeBuildRunner(null, null, null, null, null, null, null,
                config, containerDetector, null, endpoint);

        assertEquals("http://host.docker.internal:4811", runner.resolveEndpointUrl());
    }

    private static DockerClient dockerClientStreaming(byte[] chunk, int chunkCount, String lastLine, long exitCode) {
        DockerClient dockerClient = mock(DockerClient.class);

        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse createResponse = mock(ExecCreateCmdResponse.class);
        when(createResponse.getId()).thenReturn("exec-1");
        when(createCmd.exec()).thenReturn(createResponse);
        when(dockerClient.execCreateCmd("container-1")).thenReturn(createCmd);

        ExecStartCmd startCmd = mock(ExecStartCmd.class);
        when(startCmd.exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            for (int i = 0; i < chunkCount; i++) {
                callback.onNext(new Frame(StreamType.STDOUT, chunk));
            }
            callback.onNext(new Frame(StreamType.STDERR, lastLine.getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        });
        when(dockerClient.execStartCmd("exec-1")).thenReturn(startCmd);

        InspectExecCmd inspectCmd = mock(InspectExecCmd.class);
        InspectExecResponse inspectResponse = mock(InspectExecResponse.class);
        when(inspectResponse.getExitCodeLong()).thenReturn(exitCode);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(dockerClient.inspectExecCmd("exec-1")).thenReturn(inspectCmd);
        return dockerClient;
    }
}
