package io.github.hectorvent.floci.services.codeartifact;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.testing.DockerArchiveUploadFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SidecarStartupCleanupTest {

    @ParameterizedTest
    @CsvSource({"npm-upload,true", "npm-upload,false", "npm-start,true", "npm-start,false",
            "pypi,true", "pypi,false", "nuget,true", "nuget,false"})
    void failedStartupRemovalRemainsOwnedForRetry(String kind, boolean release) {
        Fixture fixture = new Fixture(kind);
        RuntimeException removalFailure = new IllegalStateException("daemon refused removal");
        doThrow(removalFailure).doNothing().when(fixture.remove).exec();

        RuntimeException startup = assertThrows(RuntimeException.class,
                fixture.start::run);
        assertFalse(fixture.running.getAsBoolean());
        verify(fixture.remove).exec();
        if (release) {
            fixture.release.run();
        } else {
            fixture.stopAll.run();
        }
        verify(fixture.remove, times(2)).exec();
        fixture.stopAll.run();
        verify(fixture.remove, times(2)).exec();
        assertEquals(1, startup.getSuppressed().length);
        assertSame(removalFailure, startup.getSuppressed()[0].getCause());
        if (!kind.equals("npm-upload")) {
            assertSame(fixture.startupFailure, startup);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"npm-upload", "npm-start", "pypi", "nuget"})
    void successfulStartupCompensationIsNotRetried(String kind) {
        Fixture fixture = new Fixture(kind);

        RuntimeException startup = assertThrows(RuntimeException.class,
                fixture.start::run);
        fixture.release.run();
        fixture.stopAll.run();

        verify(fixture.remove).exec();
        assertEquals(0, startup.getSuppressed().length);
        assertFalse(fixture.running.getAsBoolean());
        if (!kind.equals("npm-upload")) {
            assertSame(fixture.startupFailure, startup);
        }
    }

    private static class Fixture {
        private final DockerClient docker = mock(DockerClient.class);
        private final ContainerLifecycleManager lifecycle = spy(new ContainerLifecycleManager(docker, null, null, null));
        private final RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        private final RuntimeException startupFailure = new IllegalStateException("start rejected");
        private final Runnable start;
        private final Runnable release;
        private final Runnable stopAll;
        private final BooleanSupplier running;

        private Fixture(String kind) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().codeartifact().npmImage()).thenReturn("verdaccio/verdaccio:test");
            when(config.services().codeartifact().pypiImage()).thenReturn("pypiserver:test");
            when(config.services().codeartifact().nugetImage()).thenReturn("bagetter:test");
            when(config.services().dockerNetwork()).thenReturn(Optional.empty());
            when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
            when(config.docker().logMaxSize()).thenReturn("10m");
            when(config.docker().logMaxFile()).thenReturn("3");
            ContainerBuilder builder = new ContainerBuilder(config,
                    mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
            if (kind.startsWith("npm")) {
                VerdaccioSidecarManager manager = new VerdaccioSidecarManager(builder, lifecycle, config);
                start = () -> manager.ensureReady("repo", "http://localhost:4566");
                release = () -> manager.release("repo");
                stopAll = manager::stopManagedContainers;
                running = () -> manager.isRunning("repo");
            } else if (kind.equals("pypi")) {
                PypiserverSidecarManager manager = new PypiserverSidecarManager(builder, lifecycle, config);
                start = () -> manager.ensureReady("repo", "http://localhost:4566");
                release = () -> manager.release("repo");
                stopAll = manager::stopManagedContainers;
                running = () -> manager.isRunning("repo");
            } else {
                BagetterSidecarManager manager = new BagetterSidecarManager(builder, lifecycle, config);
                start = () -> manager.ensureReady("repo", "http://localhost:4566");
                release = () -> manager.release("repo");
                stopAll = manager::stopManagedContainers;
                running = () -> manager.isRunning("repo");
            }
            doNothing().when(lifecycle).removeIfExists(any());
            doReturn("container").when(lifecycle).create(any(ContainerSpec.class));
            DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, "container");
            if (kind.equals("npm-upload")) {
                uploads.failures = 1;
                uploads.failure = new NotFoundException("config upload rejected");
            } else {
                doThrow(startupFailure).when(lifecycle).startCreated(eq("container"), any(ContainerSpec.class));
            }
            StopContainerCmd stop = mock(StopContainerCmd.class);
            when(docker.stopContainerCmd("container")).thenReturn(stop);
            when(stop.withTimeout(5)).thenReturn(stop);
            when(docker.removeContainerCmd("container")).thenReturn(remove);
            when(remove.withForce(true)).thenReturn(remove);
        }
    }
}
