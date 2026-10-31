package io.github.hectorvent.floci.services.ssm;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SsmTimeoutDockerIntegrationTest {
    @Inject
    DockerClient docker;

    @Inject
    ContainerBuilder builder;

    @Inject
    ContainerLifecycleManager lifecycle;

    @Inject
    EmulatorConfig config;

    @ParameterizedTest
    @ValueSource(strings = {"busybox:stable", "public.ecr.aws/lambda/nodejs:20"})
    void timeoutPreservesOutputAndTerminatesScriptsOnBothGuestToolchains(String image) {
        String name = ContainerStorageHelper.resourceName(config, "ssm-timeout", null, UUID.randomUUID().toString());
        try {
            ContainerInfo container = lifecycle.createAndStartEphemeral(builder.newContainer(image)
                    .withName(name).withEntrypoint(List.of("sh", "-c"))
                    .withCmd(List.of("exec sleep 600")).build());
            ContainerExec.Result output = execute(container.containerId(),
                    "printf '%s' \"a 'quoted' line\"; printf '%s' stderr-marker >&2; exit 7", 5);
            assertFalse(output.timedOut());
            assertEquals(7, output.exitCode(), output.summary());
            assertEquals("a 'quoted' line", output.stdout());
            assertEquals("stderr-marker", output.stderr());

            ContainerExec.Result terminated = execute(container.containerId(), "exec sleep 20", 1);
            assertFalse(terminated.timedOut(), "The guest timeout must finish before Docker attach's deadline");
            assertTrue(Set.of(124L, 143L).contains(terminated.exitCode()), terminated.summary());

            ContainerExec.Result killed = execute(container.containerId(), "trap '' TERM; exec sleep 20", 1);
            assertFalse(killed.timedOut(), "Kill escalation must finish before Docker attach's deadline");
            assertEquals(137, killed.exitCode(), killed.summary());
        } finally {
            lifecycle.removeIfExistsStrict(name, true);
        }
    }

    private ContainerExec.Result execute(String container, String script, int seconds) {
        return ContainerExec.run(docker, container,
                new String[]{"sh", "-c", SsmDirectCommandExecutor.timeoutWrappedScript(script, seconds)}, 8);
    }
}
