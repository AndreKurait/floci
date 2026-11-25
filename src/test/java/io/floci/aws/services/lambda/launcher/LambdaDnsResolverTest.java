package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.Network;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LambdaDnsResolverTest {

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private ContainerBuilder containers;
    private LambdaDnsResolver resolver;

    @BeforeEach
    void setup() {
        when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("dns-test"));
        when(lifecycle.getDockerClient()).thenReturn(docker);
        Network network = mock(Network.class);
        when(network.getInternal()).thenReturn(true);
        when(docker.inspectNetworkCmd().withNetworkId("isolated").exec()).thenReturn(network);
        containers = new ContainerBuilder(config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        resolver = new LambdaDnsResolver(containers, lifecycle, config);
        clearInvocations(docker);
    }

    private ContainerBuilder.Builder workload() {
        return containers.newContainer("lambda-image").withNetworkMode("isolated")
                .withDnsServer("192.0.2.53").withUser("12345:12345").withReadonlyRootfs()
                .withEntrypoint(List.of("/original-bootstrap")).withCmd(List.of("handler"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bridge", "default", "host", "none", "container:namespace"})
    void namespaceAndDefaultNetworkingAreUnchanged(String network) {
        assertFalse(LambdaDnsResolver.requiresResolver(workload().withNetworkMode(network).build(), lifecycle));
        verifyNoInteractions(docker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/etc/resolv.conf", "/etc", "/", "/etc/./resolv.conf", "/etc/", "/etc/sub/../resolv.conf", "//etc//resolv.conf"})
    void explicitResolverMountIsPreserved(String path) {
        assertNull(resolver.prepare(workload().withReadOnlyBind("/caller/file", path), null));
        verify(lifecycle, never()).ensureVolume(anyString(), anyMap());
    }

    @Test
    void externalBridgeAndAbsentDnsKeepDockerResolution() {
        Network network = mock(Network.class);
        when(network.getInternal()).thenReturn(false);
        when(docker.inspectNetworkCmd().withNetworkId("isolated").exec()).thenReturn(network);
        assertFalse(LambdaDnsResolver.requiresResolver(workload().build(), lifecycle));
        assertFalse(LambdaDnsResolver.requiresResolver(
                containers.newContainer("image").withNetworkMode("isolated").build(), lifecycle));
    }

    @Test
    void resolverKeepsDockerAliasesAndDeclaredAddressesAndRejectsInjectedDirectives() {
        ContainerSpec spec = workload().withDnsServer("2001:db8::53").build();
        assertEquals("nameserver 127.0.0.11\nnameserver 192.0.2.53\nnameserver 2001:db8::53\noptions ndots:0\n",
                new String(LambdaDnsResolver.resolverContent(spec), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> resolver.prepare(
                workload().withDnsServer("192.0.2.54\nnameserver 8.8.8.8"), null));
        verify(lifecycle, never()).ensureVolume(anyString(), anyMap());
    }

    @Test
    void preparationNeverStartsImageAndPreservesRuntimeConfiguration() {
        when(lifecycle.create(any(), eq("linux/arm64"))).thenReturn("resolver-helper");
        when(docker.inspectVolumeCmd(anyString()).exec().getMountpoint()).thenReturn("/daemon/volumes/resolver");
        ContainerBuilder.Builder workload = workload();

        String volume = resolver.prepare(workload, "linux/arm64");

        ContainerSpec prepared = workload.build();
        assertNotNull(volume);
        assertEquals("12345:12345", prepared.user());
        assertTrue(prepared.readonlyRootfs());
        assertEquals(List.of("/original-bootstrap"), prepared.entrypoint());
        assertEquals(List.of("handler"), prepared.cmd());
        assertEquals("isolated", prepared.networkMode());
        assertEquals(List.of("192.0.2.53"), prepared.dnsServers());
        assertEquals("/daemon/volumes/resolver/resolv.conf", prepared.binds().getFirst().getPath());
        assertEquals("/etc/resolv.conf", prepared.binds().getFirst().getVolume().getPath());
        assertEquals(AccessMode.ro, prepared.binds().getFirst().getAccessMode());
        assertTrue(prepared.mounts().stream().anyMatch(mount -> mount.getType() == MountType.VOLUME
                && volume.equals(mount.getSource()) && Boolean.TRUE.equals(mount.getReadOnly())));
        ArgumentCaptor<ContainerSpec> helper = ArgumentCaptor.forClass(ContainerSpec.class);
        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).ensureVolume(eq(volume), anyMap());
        order.verify(lifecycle).create(helper.capture(), eq("linux/arm64"));
        order.verify(lifecycle).removeIfExistsStrict("resolver-helper", true);
        assertEquals("none", helper.getValue().networkMode());
        assertEquals(List.of("/floci-resolver-not-executed"), helper.getValue().entrypoint());
        verify(lifecycle, never()).startCreated(anyString(), any());
        verify(lifecycle, never()).removeVolumeStrict(anyString());
    }

    @Test
    void rejectedCopyRemovesHelperAndVolumeWithoutChangingWorkload() {
        when(lifecycle.create(any())).thenReturn("resolver-helper");
        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("resolver-helper")).thenReturn(copy);
        doThrow(new IllegalStateException("copy rejected")).when(copy).exec();
        ContainerBuilder.Builder workload = workload();

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> resolver.prepare(workload, null));

        assertEquals("copy rejected", failure.getCause().getMessage());
        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).removeIfExistsStrict("resolver-helper", true);
        order.verify(lifecycle).removeVolumeStrict(anyString());
        assertTrue(workload.build().binds().isEmpty());
        assertTrue(workload.build().mounts().isEmpty());
    }

    @Test
    void cleanupRetriesFailedVolumeDeletionAndStopsAfterConfirmedRemoval() {
        doThrow(new IllegalStateException("daemon unavailable")).doNothing()
                .when(lifecycle).removeVolumeStrict("failed-launch-resolver");
        assertThrows(IllegalStateException.class,
                () -> resolver.remove("failed-launch-resolver", "failed-runtime"));
        resolver.retryCleanup();
        resolver.retryCleanup();
        verify(lifecycle, times(2)).removeIfExistsStrict("failed-runtime", true);
        verify(lifecycle, times(2)).removeVolumeStrict("failed-launch-resolver");
    }

    @Test
    void failedHelperRemovalIsRetriedAndRemainsAVisibleFailure() {
        when(lifecycle.create(any())).thenReturn("resolver-helper");
        when(docker.inspectVolumeCmd(anyString()).exec().getMountpoint()).thenReturn("/daemon/volume");
        doThrow(new IllegalStateException("remove rejected"))
                .when(lifecycle).removeIfExistsStrict("resolver-helper", true);
        assertThrows(IllegalStateException.class,
                () -> resolver.prepare(workload(), null));
        verify(lifecycle, times(2)).removeIfExistsStrict("resolver-helper", true);
        verify(lifecycle, never()).removeVolumeStrict(anyString());
        doNothing().when(lifecycle).removeIfExistsStrict("resolver-helper", true);
        resolver.retryCleanup();
        verify(lifecycle, times(3)).removeIfExistsStrict("resolver-helper", true);
        verify(lifecycle).removeVolumeStrict(anyString());
        resolver.retryCleanup();
        verify(lifecycle).removeVolumeStrict(anyString());
    }
}
