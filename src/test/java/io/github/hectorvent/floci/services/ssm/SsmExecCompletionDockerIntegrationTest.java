package io.github.hectorvent.floci.services.ssm;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

@QuarkusTest
class SsmExecCompletionDockerIntegrationTest {
    @Inject
    DockerClient docker;

    @Inject
    ContainerBuilder builder;

    @Inject
    ContainerLifecycleManager lifecycle;

    @Inject
    EmulatorConfig config;

    @ParameterizedTest
    @CsvSource({
            "busybox:stable, 0",
            "busybox:stable, 7",
            "public.ecr.aws/lambda/nodejs:20, 0",
            "public.ecr.aws/lambda/nodejs:20, 7",
            "ubuntu:24.04, 0",
            "ubuntu:24.04, 7"
    })
    void completedExecKeepsItsResultWhenAttachCompletionIsDelayed(String image, int exitCode) {
        String name = ContainerStorageHelper.resourceName(config, "ssm-completion", null, UUID.randomUUID().toString());
        try {
            ContainerInfo container = lifecycle.createAndStartEphemeral(builder.newContainer(image)
                    .withName(name).withEntrypoint(List.of("sh", "-c"))
                    .withCmd(List.of("exec sleep 600")).build());
            Ec2Service ec2 = mock(Ec2Service.class);
            Instance instance = new Instance();
            instance.setDockerContainerId(container.containerId());
            when(ec2.findInstanceById("i-completion")).thenReturn(instance);
            when(ec2.isInstanceContainerRunning("i-completion")).thenReturn(true);
            AtomicReference<String> commandId = new AtomicReference<>();
            AtomicInteger completions = new AtomicInteger();
            DockerClient delayed = delayFirstCompletion(commandId, completions);

            SsmDirectCommandExecutor.ExecutionResult result = new SsmDirectCommandExecutor(delayed, ec2)
                    .executeIfSupported("i-completion", "AWS-RunShellScript",
                            Map.of("commands", List.of("printf finished; printf command-stderr >&2; exit " + exitCode)),
                            1).orElseThrow();

            InspectExecResponse actual = docker.inspectExecCmd(commandId.get()).exec();
            assertFalse(Boolean.TRUE.equals(actual.isRunning()), "The real guest command must have exited");
            assertEquals(Long.valueOf(exitCode), actual.getExitCodeLong());
            assertEquals(1, completions.get(), "Only the completion notification is withheld");
            assertEquals(exitCode == 0 ? "Success" : "Failed", result.status(),
                    "A completed guest command must not inherit the Docker attach timeout");
            assertEquals(exitCode, result.responseCode());
            assertEquals("finished", result.standardOutput());
            assertEquals("command-stderr", result.standardError());
        } finally {
            lifecycle.removeIfExistsStrict(name, true);
        }
    }

    private DockerClient delayFirstCompletion(AtomicReference<String> commandId, AtomicInteger completions) {
        DockerClient delayed = mock(DockerClient.class, delegatesTo(docker));
        doAnswer(invocation -> {
            String execId = invocation.getArgument(0);
            ExecStartCmd actual = docker.execStartCmd(execId);
            if (!commandId.compareAndSet(null, execId)) {
                return actual;
            }
            ExecStartCmd command = mock(ExecStartCmd.class, delegatesTo(actual));
            doAnswer(start -> {
                ResultCallback<Frame> receiver = start.getArgument(0);
                return actual.exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onStart(Closeable closeable) {
                        super.onStart(closeable);
                        receiver.onStart(closeable);
                    }

                    @Override
                    public void onNext(Frame frame) {
                        receiver.onNext(frame);
                    }

                    @Override
                    public void onError(Throwable error) {
                        receiver.onError(error);
                    }

                    @Override
                    public void onComplete() {
                        // Keep only the attach completion pending; execution, frames and inspection are real.
                        completions.incrementAndGet();
                    }
                });
            }).when(command).exec(any());
            return command;
        }).when(delayed).execStartCmd(anyString());
        return delayed;
    }
}
