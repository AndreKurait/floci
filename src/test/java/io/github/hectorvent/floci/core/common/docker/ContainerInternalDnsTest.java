package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateVolumeCmd;
import com.github.dockerjava.api.command.CreateVolumeResponse;
import com.github.dockerjava.api.command.InspectNetworkCmd;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveVolumeCmd;
import com.github.dockerjava.api.exception.BadRequestException;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.api.model.VolumesFrom;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerInternalDnsTest {
    private DockerClient docker;
    private EmulatorConfig config;
    private ImageCacheService images;
    private CreateContainerCmd workload;
    private CreateContainerCmd helper;
    private CreateVolumeCmd createVolume;
    private InspectVolumeResponse inspectedVolume;
    private CopyArchiveToContainerCmd copy;
    private Network network;
    private byte[] archive;

    @BeforeEach
    void setUp() {
        docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        images = mock(ImageCacheService.class);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("dns-test"));
        when(images.ensureImageExists("native-image")).thenReturn("native-image");
        network = mock(Network.class);
        when(network.getId()).thenReturn("internal-network-id");
        when(network.getDriver()).thenReturn("bridge");
        when(network.getInternal()).thenReturn(true);
        InspectNetworkCmd inspect = mock(InspectNetworkCmd.class, RETURNS_SELF);
        when(docker.inspectNetworkCmd()).thenReturn(inspect);
        when(inspect.exec()).thenReturn(network);
        helper = mock(CreateContainerCmd.class, RETURNS_SELF);
        workload = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse helperResponse = mock(CreateContainerResponse.class);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(helperResponse.getId()).thenReturn("resolver-helper");
        when(response.getId()).thenReturn("native-workload");
        when(helper.exec()).thenReturn(helperResponse);
        when(workload.exec()).thenReturn(response);
        when(docker.createContainerCmd("native-image")).thenReturn(helper, workload);
        CreateVolumeResponse volume = mock(CreateVolumeResponse.class);
        when(volume.getMountpoint()).thenReturn("/daemon/volumes/resolver/_data");
        createVolume = mock(CreateVolumeCmd.class, RETURNS_SELF);
        when(docker.createVolumeCmd()).thenReturn(createVolume);
        when(createVolume.exec()).thenReturn(volume);
        inspectedVolume = mock(InspectVolumeResponse.class);
        when(inspectedVolume.getMountpoint()).thenReturn("/daemon/volumes/resolver/_data");
        InspectVolumeCmd inspectVolume = mock(InspectVolumeCmd.class, RETURNS_SELF);
        when(docker.inspectVolumeCmd(anyString())).thenReturn(inspectVolume);
        when(inspectVolume.exec()).thenReturn(inspectedVolume);
        when(createVolume.withLabels(any())).thenAnswer(invocation -> {
            Map<String, String> labels = invocation.getArgument(0);
            when(inspectedVolume.getLabels()).thenReturn(labels);
            return createVolume;
        });
        copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("resolver-helper")).thenReturn(copy);
        doAnswer(invocation -> {
            InputStream input = invocation.getArgument(0);
            archive = input.readAllBytes();
            return copy;
        }).when(copy).withTarInputStream(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void internalNetworkGetsResolverBeforeNativeProcessStarts(boolean readOnlyRoot) {
        ContainerSpec spec = spec("internal-network", List.of(), List.of(), List.of(),
                List.of("172.16.4.0"), readOnlyRoot);
        ContainerLifecycleManager manager = manager();

        String id = manager.create(spec);

        assertEquals("native-workload", id);
        ArgumentCaptor<HostConfig> host = ArgumentCaptor.forClass(HostConfig.class);
        verify(workload).withHostConfig(host.capture());
        assertTrue(host.getValue().getMounts().stream().anyMatch(mount ->
                "/etc/resolv.conf".equals(mount.getTarget())
                        && Boolean.TRUE.equals(mount.getReadOnly())
                        && "/daemon/volumes/resolver/_data/resolv.conf".equals(mount.getSource())));
        assertEquals("internal-network", host.getValue().getNetworkMode());
        verify(workload).withEntrypoint(List.of("/native-entrypoint"));
        verify(workload).withCmd(List.of("native-argument"));
        verify(workload).withUser("1001");
        verify(docker, never()).startContainerCmd(anyString());
    }

    @Test
    void resolverPreservesDockerAliasPriorityAndConfiguredServerOrder() {
        assertEquals("nameserver 127.0.0.11\nnameserver 172.16.4.0\nnameserver 172.16.0.3\n"
                        + "options ndots:0\n",
                ContainerInternalDns.resolverContents(
                        List.of("172.16.4.0", "172.16.0.3", "172.16.4.0", "127.0.0.11")));
        assertThrows(IllegalArgumentException.class,
                () -> ContainerInternalDns.resolverContents(List.of("dns.invalid")));
        assertThrows(IllegalArgumentException.class,
                () -> ContainerInternalDns.resolverContents(List.of("172.16.4.0\noptions rotate")));
    }

    @Test
    void sharedFileIsPreparedOnceWithoutStartingHelperAndRemainsTrackedByDocker() throws Exception {
        ContainerInternalDns dns = dns();
        HostConfig first = HostConfig.newHostConfig();
        HostConfig second = HostConfig.newHostConfig();
        try (ContainerInternalDns.Lease lease = dns.configure(spec(), "native-image", first)) {
            assertEquals(2, first.getMounts().size());
        }
        try (ContainerInternalDns.Lease lease = dns.configure(spec(), "native-image", second)) {
            assertEquals(first.getMounts(), second.getMounts());
        }
        assertEquals(MountType.VOLUME, first.getMounts().getFirst().getType());
        assertEquals(Boolean.TRUE, first.getMounts().getFirst().getReadOnly());
        verify(createVolume, times(1)).exec();
        verify(docker, never()).startContainerCmd(anyString());
        verify(docker.removeContainerCmd("resolver-helper").withForce(true)).withRemoveVolumes(true);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
            assertEquals("resolv.conf", tar.getNextEntry().getName());
            assertEquals("nameserver 127.0.0.11\nnameserver 172.16.4.0\noptions ndots:0\n",
                    new String(tar.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"host", "none", "bridge", "default", "container:vpc-helper"})
    void specialNetworksKeepTheirResolver(String mode) {
        try (ContainerInternalDns.Lease lease = dns().configure(
                spec(mode, List.of(), List.of(), List.of(), List.of("172.16.4.0"), false),
                "native-image", HostConfig.newHostConfig())) {
            verify(docker, never()).inspectNetworkCmd();
            verify(docker, never()).createVolumeCmd();
        }
    }

    @Test
    void externalNetworkKeepsDockerForwarding() {
        when(network.getInternal()).thenReturn(false);
        try (ContainerInternalDns.Lease lease = dns().configure(spec(), "native-image", HostConfig.newHostConfig())) {
            verify(docker, never()).createVolumeCmd();
        }
    }

    @Test
    void noConfiguredDnsKeepsDaemonDefaults() {
        try (ContainerInternalDns.Lease lease = dns().configure(
                spec("internal-network", List.of(), List.of(), List.of(), List.of(), false),
                "native-image", HostConfig.newHostConfig())) {
            verify(docker, never()).inspectNetworkCmd();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/etc/resolv.conf", "/etc", "/etc/", "/.floci-dns"})
    void explicitMountsAreRespected(String target) {
        ContainerInternalDns dns = dns();
        List<ContainerSpec> specs = List.of(
                spec("internal-network", List.of(new Mount().withTarget(target)), List.of(),
                        List.of(), List.of("172.16.4.0"), false),
                spec("internal-network", List.of(), List.of(new Bind("/operator", new Volume(target))),
                        List.of(), List.of("172.16.4.0"), false));
        for (ContainerSpec spec : specs) {
            try (ContainerInternalDns.Lease lease = dns.configure(spec, "native-image", HostConfig.newHostConfig())) {
                verify(docker, never()).inspectNetworkCmd();
            }
        }
    }

    @Test
    void inheritedVolumesKeepTheirResolver() {
        try (ContainerInternalDns.Lease lease = dns().configure(
                spec("internal-network", List.of(), List.of(), List.of(new VolumesFrom("operator")),
                        List.of("172.16.4.0"), false), "native-image", HostConfig.newHostConfig())) {
            verify(docker, never()).inspectNetworkCmd();
        }
    }

    @Test
    void protectedNamespaceHelperKeepsItsOwnWritableResolver() {
        ContainerSpec spec = spec("internal-network", List.of(), List.of(), List.of(),
                List.of("172.16.4.0"), false,
                Map.of(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true"));
        HostConfig host = HostConfig.newHostConfig();
        try (ContainerInternalDns.Lease lease = dns().configure(spec, "native-image", host)) {
            assertTrue(host.getMounts() == null || host.getMounts().isEmpty());
            verify(docker, never()).createVolumeCmd();
        }
    }

    @Test
    void failedCopyRemovesHelperAndVolumeWithoutStartingNativeProcess() {
        doThrow(new BadRequestException("copy refused")).when(copy).exec();
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> dns().configure(spec(), "native-image", HostConfig.newHostConfig()));
        assertTrue(failure.getCause() instanceof BadRequestException);
        verify(docker.removeContainerCmd("resolver-helper").withForce(true).withRemoveVolumes(true)).exec();
        verify(docker).removeVolumeCmd(anyString());
        verify(docker, never()).startContainerCmd(anyString());
    }

    @Test
    void failedCopyRetainsItsCauseWhenHelperRemovalAlsoFails() {
        BadRequestException copyFailure = new BadRequestException("copy refused");
        doThrow(copyFailure).when(copy).exec();
        IllegalStateException cleanupFailure = new IllegalStateException("helper removal refused");
        RemoveContainerCmd removal = docker.removeContainerCmd("resolver-helper")
                .withForce(true).withRemoveVolumes(true);
        doThrow(cleanupFailure).when(removal).exec();

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> dns().configure(spec(), "native-image", HostConfig.newHostConfig()));

        assertTrue(failure.getCause() instanceof BadRequestException);
        assertSame(copyFailure, failure.getCause());
        assertEquals(List.of(cleanupFailure), List.of(failure.getSuppressed()));
        verify(docker).removeVolumeCmd(anyString());
        verify(docker, never()).startContainerCmd(anyString());
    }

    @Test
    void successfulCopyStillRefusesAHelperRemovalFailure() {
        IllegalStateException cleanupFailure = new IllegalStateException("helper removal refused");
        RemoveContainerCmd removal = docker.removeContainerCmd("resolver-helper")
                .withForce(true).withRemoveVolumes(true);
        doThrow(cleanupFailure).when(removal).exec();

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> dns().configure(spec(), "native-image", HostConfig.newHostConfig()));

        assertEquals(cleanupFailure, failure);
        verify(docker).removeVolumeCmd(anyString());
        verify(docker, never()).startContainerCmd(anyString());
    }

    @Test
    void cleanupWaitsForCreateToAcquireItsDockerVolumeReference() {
        ContainerInternalDns dns = dns();
        ContainerInternalDns.Lease lease = dns.configure(spec(), "native-image", HostConfig.newHostConfig());
        dns.removeUnusedVolumes();
        verify(docker, never()).removeVolumeCmd(anyString());
        lease.close();
        dns.removeUnusedVolumes();
        verify(docker).removeVolumeCmd(anyString());
        dns.removeUnusedVolumes();
        verify(docker, times(1)).removeVolumeCmd(anyString());
    }

    @Test
    void workloadCreateFailureReleasesVolumeForOrderedTeardown() {
        when(workload.exec()).thenThrow(new BadRequestException("workload refused"));
        ContainerLifecycleManager manager = manager();
        assertThrows(BadRequestException.class, () -> manager.create(spec()));
        manager.stopManagedContainers();
        verify(docker).removeVolumeCmd(anyString());
        assertTrue(manager.teardownOrder() > 1);
    }

    @Test
    void cleanupKeepsInUseVolumeForAnotherAttempt() {
        ContainerInternalDns dns = dns();
        String name;
        HostConfig host = HostConfig.newHostConfig();
        try (ContainerInternalDns.Lease lease = dns.configure(spec(), "native-image", host)) {
            name = host.getMounts().getFirst().getSource();
        }
        RemoveVolumeCmd remove = docker.removeVolumeCmd(name);
        doThrow(new ConflictException("in use")).doNothing().when(remove).exec();
        dns.removeUnusedVolumes();
        dns.removeUnusedVolumes();
        verify(docker.removeVolumeCmd(name), times(2)).exec();
    }

    @Test
    void reusedVolumeMustKeepItsOwnershipAndDaemonPath() {
        ContainerInternalDns dns = dns();
        try (ContainerInternalDns.Lease lease = dns.configure(spec(), "native-image", HostConfig.newHostConfig())) {
            when(inspectedVolume.getLabels()).thenReturn(Map.of("other-owner", "true"));
        }
        assertThrows(IllegalStateException.class,
                () -> dns.configure(spec(), "native-image", HostConfig.newHostConfig()));
        dns.removeUnusedVolumes();
        verify(docker, never()).removeVolumeCmd(anyString());
    }

    @Test
    void removedCachedVolumeIsRecreatedBeforeNextWorkload() {
        ContainerInternalDns dns = dns();
        HostConfig first = HostConfig.newHostConfig();
        try (ContainerInternalDns.Lease lease = dns.configure(spec(), "native-image", first)) {
            when(docker.inspectVolumeCmd(anyString()).exec()).thenThrow(new NotFoundException("gone"));
        }
        when(docker.createContainerCmd("native-image")).thenReturn(helper);
        try (ContainerInternalDns.Lease lease = dns.configure(spec(), "native-image", HostConfig.newHostConfig())) {
            verify(createVolume, times(2)).exec();
        }
    }

    private ContainerInternalDns dns() {
        return new ContainerInternalDns(docker, config, 0);
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(docker, images, mock(ContainerDetector.class), config, 0);
    }

    private static ContainerSpec spec() {
        return spec("internal-network", List.of(), List.of(), List.of(), List.of("172.16.4.0"), false);
    }

    private static ContainerSpec spec(String network, List<Mount> mounts, List<Bind> binds,
                                      List<VolumesFrom> inherited, List<String> dns, boolean readOnlyRoot) {
        return spec(network, mounts, binds, inherited, dns, readOnlyRoot, Map.of());
    }

    private static ContainerSpec spec(String network, List<Mount> mounts, List<Bind> binds,
                                      List<VolumesFrom> inherited, List<String> dns, boolean readOnlyRoot,
                                      Map<String, String> labels) {
        return new ContainerSpec(
                "native-image", "native-workload", List.of("NATIVE_ENV=kept"),
                List.of("native-argument"), List.of("/native-entrypoint"), null,
                Map.of(), List.of(), List.of(), network,
                mounts, binds, inherited, List.of(), labels, null,
                false, null, dns, null, "1001", List.of(), List.of(),
                null, null, readOnlyRoot, List.of(), Map.of(), false);
    }
}
