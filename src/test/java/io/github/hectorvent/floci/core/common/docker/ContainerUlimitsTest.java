package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ulimit;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerUlimitsTest {

    @Test
    void explicitLimitsReachDockerWithoutChangingOtherResourceControls() {
        ContainerSpec spec = builder()
                .withUlimit("memlock", -1, -1)
                .withUlimit("nofile", 1_024_000, 1_024_000)
                .withMemoryBytes(512L * 1024 * 1024)
                .build();

        HostConfig host = create(spec);
        Map<String, Ulimit> limits = Arrays.stream(host.getUlimits())
                .collect(Collectors.toMap(Ulimit::getName, limit -> limit));
        assertEquals(2, limits.size());
        assertEquals(-1L, limits.get("memlock").getSoft().longValue());
        assertEquals(-1L, limits.get("memlock").getHard().longValue());
        assertEquals(1_024_000L, limits.get("nofile").getSoft().longValue());
        assertEquals(1_024_000L, limits.get("nofile").getHard().longValue());
        assertEquals(512L * 1024 * 1024, host.getMemory().longValue());
        assertNull(host.getNanoCPUs());
    }

    @Test
    void defaultAndLegacySpecsLeaveDockerProcessLimitsUnset() {
        assertEquals(Map.of(), builder().build().ulimits());
        assertNull(create(builder().build()).getUlimits());
        assertNull(create(new ContainerSpec("example/worker:1")).getUlimits());
    }

    @Test
    void laterBuilderUpdatesDoNotMutateExistingSpecsOrOtherLimits() {
        ContainerBuilder.Builder builder = builder()
                .withUlimit("nofile", 1024, 4096)
                .withUlimit("memlock", -1, -1);
        ContainerSpec before = builder.build();
        ContainerSpec after = builder.withUlimit("nofile", 2048, 8192).build();
        assertEquals(new ContainerSpec.ResourceLimit(1024, 4096), before.ulimits().get("nofile"));
        assertEquals(new ContainerSpec.ResourceLimit(2048, 8192), after.ulimits().get("nofile"));
        assertEquals(before.ulimits().get("memlock"), after.ulimits().get("memlock"));
        assertThrows(UnsupportedOperationException.class,
                () -> before.ulimits().put("stack", new ContainerSpec.ResourceLimit(1, 1)));
    }

    @ParameterizedTest
    @CsvSource({"-1,-1", "0,0", "0,-1", "1,-1", "1024,4096"})
    void validFiniteAndUnlimitedValuesSurviveConstruction(long soft, long hard) {
        ContainerSpec spec = builder().withUlimit("nofile", soft, hard).build();
        Ulimit limit = create(spec).getUlimits()[0];
        assertEquals(soft, limit.getSoft().longValue());
        assertEquals(hard, limit.getHard().longValue());
    }

    @ParameterizedTest
    @CsvSource({"-2,-1", "0,-2", "-1,0", "-1,100", "2,1"})
    void invalidLimitValuesFailBeforeContainerCreation(long soft, long hard) {
        assertThrows(IllegalArgumentException.class, () -> builder().withUlimit("nofile", soft, hard));
        assertThrows(IllegalArgumentException.class, () -> new ContainerSpec.ResourceLimit(soft, hard));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" two ", "nofile=1", "../nofile", "NOFILE"})
    void invalidLimitNamesAreRejected(String name) {
        assertThrows(IllegalArgumentException.class, () -> builder().withUlimit(name, 1, 1));
    }

    private static ContainerBuilder.Builder builder() {
        return new ContainerBuilder(null, null, null).newContainer("example/worker:1");
    }

    private static HostConfig create(ContainerSpec spec) {
        DockerClient docker = mock(DockerClient.class);
        ImageCacheService images = mock(ImageCacheService.class);
        when(images.ensureImageExists(spec.image())).thenReturn(spec.image());
        CreateContainerCmd command = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(docker.createContainerCmd(spec.image())).thenReturn(command);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("ulimit-container");
        when(command.exec()).thenReturn(response);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
                docker, images, mock(ContainerDetector.class), mock(PortAllocator.class), config);

        assertEquals("ulimit-container", manager.create(spec));
        ArgumentCaptor<HostConfig> host = ArgumentCaptor.forClass(HostConfig.class);
        verify(command).withHostConfig(host.capture());
        return host.getValue();
    }
}
