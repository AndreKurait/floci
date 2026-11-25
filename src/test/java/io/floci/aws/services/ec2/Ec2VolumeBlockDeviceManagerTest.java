package io.floci.aws.services.ec2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Device;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExecStubs;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.model.Volume;
import io.floci.aws.services.ec2.model.VolumeAttachment;
import io.floci.aws.testing.HelperShellFixture;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers how the volume manager drives its helper container without a Docker daemon: the command
 * it runs there, and that a Docker failure while running it is reported rather than thrown.
 */
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class Ec2VolumeBlockDeviceManagerTest {

    private static final String HELPER_ID = "helper-container-id";
    private static final String CREATED_HELPER_ID = "created-helper-container-id";
    private static final String HELPER_IMAGE = "alpine:3.21";

    private DockerClient dockerClient;
    private InspectContainerResponse helper;
    private ContainerBuilder containerBuilder;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerDetector containerDetector;
    private Ec2VolumeBlockDeviceManager manager;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().volumeBlockDevices()).thenReturn(true);
        when(config.services().ec2().mock()).thenReturn(false);

        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        helper = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(helper.getState().getRunning()).thenReturn(true);
        when(helper.getId()).thenReturn(HELPER_ID);
        when(dockerClient.inspectContainerCmd(anyString()).exec()).thenReturn(helper);

        containerBuilder = mock(ContainerBuilder.class);
        lifecycleManager = mock(ContainerLifecycleManager.class);
        containerDetector = mock(ContainerDetector.class);
        manager = new Ec2VolumeBlockDeviceManager(dockerClient, containerBuilder,
                lifecycleManager, containerDetector, config);
    }

    @ParameterizedTest
    @ValueSource(strings = {"detach-failure", "still-bound", "released"})
    void deletionPreservesBackingFileUntilAllLoopsAreReleased(String outcome, @TempDir Path directory)
            throws Exception {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");
        assertTrue(manager.deleteVolume("vol-delete"));
        Path rawFile = Files.createFile(directory.resolve("volume.raw"));
        String detachAction = switch (outcome) {
            case "detach-failure" -> "return 1";
            case "still-bound" -> ":";
            default -> "bound=0";
        };
        String fakeCommands = "bound=1\n"
                + "losetup() {\n"
                + "  case \"$1\" in\n"
                + "    -j) [ \"$bound\" = 0 ] || echo \"/dev/loop3: 0 $2\";;\n"
                + "    -d) " + detachAction + ";;\n"
                + "    *) return 1;;\n"
                + "  esac\n"
                + "}\n";
        int exitCode = HelperShellFixture.run(new String[]{"sh", "-c",
                fakeCommands + commands.getFirst().get(2), "delete", rawFile.toString()}, directory, Map.of());

        if ("released".equals(outcome)) {
            assertEquals(0, exitCode, HelperShellFixture.output(directory));
            assertFalse(Files.exists(rawFile));
        } else {
            assertEquals(1, exitCode, HelperShellFixture.output(directory));
            assertTrue(Files.exists(rawFile), "Failed cleanup must retain the backing file for retry");
        }
    }

    @Test
    void deletionFailureIsReportedAndCanBeRetried() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID,
                List.of(1L, 0L), "", "busy loop");

        assertFalse(manager.deleteVolume("vol-delete"));
        assertTrue(manager.deleteVolume("vol-delete"));
        assertEquals(2, commands.size());
        assertEquals(commands.getFirst(), commands.getLast());
    }

    @Test
    void managedAttachVerifiesRetainedMountInsteadOfCreatingARawDevice() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        recoveredGuest(volume, instance, true, "/dev/loop7");
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(
                dockerClient, HELPER_ID, 0, "", "");

        assertEquals(Optional.of("/dev/sdf"), manager.attachVolume(volume, instance, "sdf"));

        assertEquals(1, commands.size());
        assertFalse(commands.getFirst().get(2).contains("truncate"));
        verify(dockerClient, never()).execCreateCmd("guest");
        verify(dockerClient, never()).createVolumeCmd();
    }

    @Test
    void managedAttachRefusesChangedMountBeforeCreatingADevice() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        recoveredGuest(volume, instance, false, "/dev/loop7");

        assertThrows(AwsException.class, () -> manager.attachVolume(volume, instance, "sdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void managedAttachCannotDegradeToMetadataWhenDisabled() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        when(config.services().ec2().volumeBlockDevices()).thenReturn(false);

        assertThrows(AwsException.class, () -> manager.attachVolume(volume, instance, "sdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void managedRawReattachRequiresARunningGuest() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectContainerResponse guest = recoveredGuest(volume, instance, true, "/dev/loop7");
        when(guest.getHostConfig().getPrivileged()).thenReturn(true);
        when(lifecycleManager.isContainerRunning("guest")).thenReturn(false);

        assertThrows(AwsException.class, () -> manager.attachVolume(volume, instance, "sdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void managedRawReattachRejectsARetainedDockerBindingBeforeAllocation() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectContainerResponse guest = recoveredGuest(volume, instance, true, "/dev/loop7");
        when(guest.getHostConfig().getPrivileged()).thenReturn(true);
        when(lifecycleManager.isContainerRunning("guest")).thenReturn(true);

        assertThrows(AwsException.class, () -> manager.attachVolume(volume, instance, "sdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void managedDetachPreservesTheBackingFileAfterReleasingTheBinding() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectVolumeResponse binding = managedBinding(volume, instance);
        when(dockerClient.inspectVolumeCmd(anyString()).exec()).thenReturn(binding);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(
                dockerClient, HELPER_ID, 0, "", "");

        manager.detachVolume(volume, instance, "sdf");

        assertEquals(2, commands.size());
        assertTrue(commands.getLast().get(2).contains("losetup -d"));
        assertFalse(commands.getLast().get(2).contains("rm -f"));
        verify(dockerClient).removeVolumeCmd(anyString());
    }

    @Test
    void managedDetachRefusesMissingOwnerBeforeAnyStorageMutation() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);

        assertThrows(AwsException.class, () -> manager.detachVolume(volume, null, "sdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
        verify(dockerClient, never()).removeVolumeCmd(anyString());
    }

    @Test
    void managedRawDetachKeepsTheLoopWhenGuestDeviceVerificationFails() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectContainerResponse guest = recoveredGuest(volume, instance, true, "/dev/loop7");
        when(guest.getHostConfig().getPrivileged()).thenReturn(true);
        when(lifecycleManager.isContainerRunning("guest")).thenReturn(true);
        when(dockerClient.inspectVolumeCmd(anyString()).exec()).thenThrow(new NotFoundException("no mount"));
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(
                dockerClient, HELPER_ID, 0, "/dev/loop7", "");
        List<List<String>> guestCommands = ContainerExecStubs.completeEveryExec(
                dockerClient, "guest", 1, "", "guest filesystem is still mounted");

        AwsException failure = assertThrows(AwsException.class,
                () -> manager.detachVolume(volume, instance, "sdf"));

        assertTrue(failure.getMessage().contains("guest filesystem is still mounted"));
        assertEquals(1, helperCommands.size());
        assertEquals(1, guestCommands.size());
        assertTrue(guestCommands.getFirst().get(2).contains("/proc/self/mountinfo"));
        assertFalse(helperCommands.getFirst().get(2).contains("losetup -d"));
        verify(dockerClient, never()).removeVolumeCmd(anyString());
    }

    @Test
    void recoveredManagedMountVerifiesTheExistingGuestBindingWithoutRecreation() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        recoveredGuest(volume, instance, true, "/dev/loop7");
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(
                dockerClient, HELPER_ID, 0, "", "");

        manager.verifyInstanceMount(volume, instance, "sdf");

        assertEquals(1, commands.size());
        assertEquals("/dev/loop7", commands.getFirst().get(6));
        assertFalse(commands.getFirst().get(2).contains("mkfs"));
        verify(dockerClient, never()).createVolumeCmd();
        verify(dockerClient, never()).removeVolumeCmd(anyString());
    }

    @Test
    void recoveredManagedMountRejectsReadOnlyBindingBeforeHelperExecution() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        recoveredGuest(volume, instance, false, "/dev/loop7");

        assertThrows(AwsException.class, () -> manager.verifyInstanceMount(volume, instance, "sdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void recoveredManagedMountRejectsRetargetedLoopBeforeHelperExecution() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        recoveredGuest(volume, instance, true, "/dev/loop8");

        assertThrows(AwsException.class, () -> manager.verifyInstanceMount(volume, instance, "sdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void recoveredManagedMountRejectsMissingGuestMountWithoutCreatingStorage() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectContainerResponse guest = recoveredGuest(volume, instance, true, "/dev/loop7");
        when(guest.getMounts()).thenReturn(List.of());

        assertThrows(AwsException.class, () -> manager.verifyInstanceMount(volume, instance, "sdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
        verify(dockerClient, never()).createVolumeCmd();
    }

    @Test
    void recoveredRawMountRequiresTheExistingFilesystemAndLoopWithoutFormatting() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectContainerResponse guest = recoveredGuest(volume, instance, true, "/dev/loop7");
        when(guest.getHostConfig().getPrivileged()).thenReturn(true);
        when(dockerClient.inspectVolumeCmd(anyString()).exec()).thenThrow(new NotFoundException("no mount"));
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(
                dockerClient, HELPER_ID, 0, "/dev/loop7", "");

        manager.verifyInstanceMount(volume, instance, "sdf");

        assertEquals(1, commands.size());
        String script = commands.getFirst().get(2);
        assertTrue(script.contains("retained filesystem changed"));
        assertTrue(script.contains("retained loop is missing"));
        assertFalse(script.contains("mkfs"));
        assertFalse(script.contains("truncate"));
        verify(dockerClient, never()).createVolumeCmd();
    }

    @Test
    void recoveredRawMountRefusesAStillRetainedDockerMount() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectContainerResponse guest = recoveredGuest(volume, instance, true, "/dev/loop7");
        when(guest.getHostConfig().getPrivileged()).thenReturn(true);

        assertThrows(AwsException.class, () -> manager.verifyInstanceMount(volume, instance, "sdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
        verify(dockerClient, never()).removeVolumeCmd(anyString());
    }

    @Test
    void recoveredMountReportsFailedBackingVerificationWithoutReplacingStorage() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        recoveredGuest(volume, instance, true, "/dev/loop7");
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(
                dockerClient, HELPER_ID, 1, "", "backing file changed");

        AwsException failure = assertThrows(AwsException.class,
                () -> manager.verifyInstanceMount(volume, instance, "sdf"));

        assertEquals("InternalError", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("backing file changed"));
        assertEquals(1, commands.size());
        verify(dockerClient, never()).createVolumeCmd();
        verify(dockerClient, never()).removeVolumeCmd(anyString());
    }

    private InspectContainerResponse recoveredGuest(Volume volume, Instance instance, boolean writable,
                                                    String loop) {
        instance.setDockerContainerId("guest");
        InspectVolumeResponse binding = managedBinding(volume, instance);
        when(dockerClient.inspectVolumeCmd(anyString()).exec()).thenReturn(binding);
        InspectContainerResponse guest = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(guest.getHostConfig().getPrivileged()).thenReturn(false);
        when(guest.getHostConfig().getDevices()).thenReturn(new Device[]{new Device("rwm", "/dev/sdf", loop)});
        InspectContainerResponse.Mount mount = mock(InspectContainerResponse.Mount.class);
        String name = ContainerStorageHelper.resourceName(config, "ec2", null, volume.getVolumeId());
        when(mount.getName()).thenReturn(name);
        when(mount.getRW()).thenReturn(writable);
        // Docker and EC2 both expose a Volume type.
        when(mount.getDestination()).thenReturn(new com.github.dockerjava.api.model.Volume("/data"));
        when(guest.getMounts()).thenReturn(List.of(mount));
        InspectContainerCmd inspectGuest = mock(InspectContainerCmd.class);
        when(inspectGuest.exec()).thenReturn(guest);
        when(dockerClient.inspectContainerCmd("guest")).thenReturn(inspectGuest);
        return guest;
    }

    @Test
    void managedPreparationRejectsConflictingDockerOwnershipBeforeTouchingTheBackingFile() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectVolumeResponse foreign = mock(InspectVolumeResponse.class);
        when(foreign.getDriver()).thenReturn("local");
        when(foreign.getLabels()).thenReturn(Map.of("io.floci.account", "999999999999"));
        when(dockerClient.inspectVolumeCmd(anyString()).exec()).thenReturn(foreign);

        AwsException failure = assertThrows(AwsException.class, () -> manager.prepareMount(volume, instance,
                new Ec2ImageCatalog.VolumeMount("sdf", "/data", "ext4")));

        assertEquals("InternalError", failure.getErrorCode());
        verify(dockerClient, never()).execCreateCmd(anyString());
        verify(dockerClient, never()).createVolumeCmd();
    }

    @Test
    void managedPreparationRejectsUnsupportedAttachmentsWithoutDockerMutation() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        Ec2ImageCatalog.VolumeMount mount = new Ec2ImageCatalog.VolumeMount("sdf", "/data", "ext4");
        volume.setSnapshotId("snap-abc");
        assertThrows(AwsException.class, () -> manager.prepareMount(volume, instance, mount));
        volume.setSnapshotId(null);
        volume.setMultiAttachEnabled(true);
        assertThrows(AwsException.class, () -> manager.prepareMount(volume, instance, mount));
        volume.setMultiAttachEnabled(false);
        volume.setRegion("eu-west-1");
        assertThrows(AwsException.class, () -> manager.prepareMount(volume, instance, mount));
        volume.setRegion(instance.getRegion());
        instance.setRootVolumeId(volume.getVolumeId());
        assertThrows(AwsException.class, () -> manager.prepareMount(volume, instance, mount));
        verify(dockerClient, never()).execCreateCmd(anyString());
        verify(dockerClient, never()).createVolumeCmd();
    }

    @Test
    void changedDriverOptionsRefuseBeforeFilesystemPreparation() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectVolumeResponse existing = mock(InspectVolumeResponse.class);
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.defaultLabels(config));
        labels.putAll(ContainerStorageHelper.resourceIdentityLabels("ec2", volume.getVolumeId(),
                instance.getOwnerId(), instance.getRegion()));
        when(existing.getDriver()).thenReturn("local");
        when(existing.getLabels()).thenReturn(labels);
        when(existing.getOptions()).thenReturn(Map.of("type", "ext4", "device", "/dev/loop7", "o", "ro"));
        when(dockerClient.inspectVolumeCmd(anyString()).exec()).thenReturn(existing);

        assertThrows(AwsException.class, () -> manager.prepareMount(volume, instance,
                new Ec2ImageCatalog.VolumeMount("sdf", "/data", "ext4")));

        verify(dockerClient, never()).execCreateCmd(anyString());
        verify(dockerClient, never()).createVolumeCmd();
    }

    @Test
    void managedVolumeCannotDegradeToMetadataWhenSupportIsDisabled() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        when(config.services().ec2().volumeBlockDevices()).thenReturn(false);
        assertThrows(AwsException.class, () -> manager.prepareMount(volume, instance,
                new Ec2ImageCatalog.VolumeMount("sdf", "/data", "ext4")));
        assertThrows(AwsException.class, () -> manager.deleteManagedVolume(volume, instance.getOwnerId()));
        verifyNoInteractions(lifecycleManager);
    }

    @Test
    void managedPreparationCreatesAndVerifiesAnOwnedBinding() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectVolumeResponse binding = managedBinding(volume, instance);
        when(dockerClient.inspectVolumeCmd(anyString()).exec())
                .thenThrow(new NotFoundException("not yet created")).thenReturn(binding);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(
                dockerClient, HELPER_ID, 0, "/dev/loop7", "");

        Ec2VolumeBlockDeviceManager.PreparedMount mount = manager.prepareMount(volume, instance,
                new Ec2ImageCatalog.VolumeMount("sdf", "/data", "ext4"));
        manager.verifyMount(volume, instance, mount);

        assertEquals("vol-abc", mount.volumeId());
        assertEquals("/dev/loop7", mount.loopDevice());
        assertEquals("/data", mount.target());
        assertEquals(2, commands.size());
        assertEquals("/volumes/vol-abc.raw", commands.getFirst().get(4));
        assertEquals("", commands.getFirst().get(6));
        assertEquals("/dev/loop7", commands.getLast().get(6));
        verify(dockerClient).createVolumeCmd();
    }

    @Test
    void retainedManagedBindingIsVerifiedWithoutCreatingAnotherVolume() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectVolumeResponse binding = managedBinding(volume, instance);
        when(dockerClient.inspectVolumeCmd(anyString()).exec()).thenReturn(binding);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(
                dockerClient, HELPER_ID, 0, "/dev/loop7", "");

        Ec2VolumeBlockDeviceManager.PreparedMount mount = manager.prepareMount(volume, instance,
                new Ec2ImageCatalog.VolumeMount("sdf", "/data", "ext4"));
        manager.verifyMount(volume, instance, mount);

        assertEquals(2, commands.size());
        assertEquals("/dev/loop7", commands.getFirst().get(6));
        verify(dockerClient, never()).createVolumeCmd();
        assertThrows(AwsException.class, () -> manager.verifyMount(volume, instance,
                new Ec2VolumeBlockDeviceManager.PreparedMount(
                        "vol-other", "sdf", "/dev/loop7", mount.dockerVolume(), "/data")));
        assertEquals(2, commands.size(), "A different identity must fail before another helper command");
    }

    @Test
    void managedReleaseStopsBeforeDetachingStorageWhenDockerRefusesRemoval() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectVolumeResponse binding = managedBinding(volume, instance);
        when(dockerClient.inspectVolumeCmd(anyString()).exec()).thenReturn(binding);
        when(dockerClient.removeVolumeCmd(anyString()).exec())
                .thenThrow(new IllegalStateException("volume is in use"));
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(
                dockerClient, HELPER_ID, 0, "", "");

        assertThrows(AwsException.class, () -> manager.deleteManagedVolume(volume, instance.getOwnerId()));
        assertEquals(1, commands.size(), "Only verification may run while Docker retains the mount");
        assertFalse(commands.getFirst().get(2).contains("losetup -d"));
    }

    @Test
    void managedReleaseVerifiesBeforeRemovingBindingAndBackingFile() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectVolumeResponse binding = managedBinding(volume, instance);
        when(dockerClient.inspectVolumeCmd(anyString()).exec()).thenReturn(binding);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(
                dockerClient, HELPER_ID, 0, "", "");

        manager.deleteManagedVolume(volume, instance.getOwnerId());

        assertEquals(2, commands.size());
        assertFalse(commands.getFirst().get(2).contains("rm -f"));
        assertTrue(commands.getLast().get(2).contains("rm -f \"$raw\""));
        verify(dockerClient).removeVolumeCmd(anyString());
    }

    @Test
    void managedFilesystemRoundTripsAndLegacyVolumesRemainUnmanaged() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Volume volume = mapper.readValue("{\"volumeId\":\"vol-legacy\"}", Volume.class);
        assertNull(volume.getManagedFilesystem());
        volume.setManagedFilesystem("ext4");
        Volume restored = mapper.readValue(mapper.writeValueAsString(volume), Volume.class);
        assertEquals("ext4", restored.getManagedFilesystem());
        assertThrows(IllegalArgumentException.class, () -> restored.setManagedFilesystem("xfs"));
        assertEquals("ext4", restored.getManagedFilesystem());
        restored.setManagedFilesystem(null);
        assertNull(mapper.readValue(mapper.writeValueAsString(restored), Volume.class)
                .getManagedFilesystem());
    }

    private InspectVolumeResponse managedBinding(Volume volume, Instance instance) {
        InspectVolumeResponse binding = mock(InspectVolumeResponse.class);
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.defaultLabels(config));
        labels.putAll(ContainerStorageHelper.resourceIdentityLabels(
                "ec2", volume.getVolumeId(), instance.getOwnerId(), instance.getRegion()));
        when(binding.getDriver()).thenReturn("local");
        when(binding.getLabels()).thenReturn(labels);
        when(binding.getOptions()).thenReturn(Map.of("type", "ext4", "device", "/dev/loop7", "o", "rw"));
        return binding;
    }

    private static Instance managedInstance() {
        Instance instance = new Instance();
        instance.setInstanceId("i-abc");
        instance.setOwnerId("123456789012");
        instance.setRegion("us-east-1");
        return instance;
    }

    private static Volume managedVolume(Instance instance) {
        Volume volume = new Volume();
        volume.setVolumeId("vol-abc");
        volume.setRegion(instance.getRegion());
        volume.setSize(1);
        volume.setManagedFilesystem("ext4");
        VolumeAttachment attachment = new VolumeAttachment();
        attachment.setInstanceId(instance.getInstanceId());
        attachment.setVolumeId(volume.getVolumeId());
        attachment.setDevice("/dev/sdf");
        attachment.setState("attached");
        volume.getAttachments().add(attachment);
        return volume;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rawAttachSkipsUnprivilegedOrDisappearedGuests(boolean disappeared) {
        Instance instance = runningInstance();
        if (disappeared) {
            when(dockerClient.inspectContainerCmd("guest").exec()).thenThrow(new NotFoundException("gone"));
        } else {
            when(dockerClient.inspectContainerCmd("guest").exec().getHostConfig().getPrivileged()).thenReturn(false);
        }
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(
                dockerClient, HELPER_ID, 0, "1 /dev/loop3", "");
        List<List<String>> guestCommands = ContainerExecStubs.completeEveryExec(dockerClient, "guest", 0, "", "");

        assertEquals(Optional.empty(), manager.attachVolume(volume(), instance, "/dev/xvdf"));

        assertTrue(helperCommands.isEmpty());
        assertTrue(guestCommands.isEmpty());
        verify(containerBuilder, never()).newContainer(anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rawDetachSkipsUnavailableGuestButReleasesHelperLoops(boolean disappeared) {
        Instance instance = runningInstance();
        if (disappeared) {
            when(dockerClient.inspectContainerCmd("guest").exec()).thenThrow(new NotFoundException("gone"));
        } else {
            when(dockerClient.inspectContainerCmd("guest").exec().getHostConfig().getPrivileged()).thenReturn(false);
        }
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");
        List<List<String>> guestCommands = ContainerExecStubs.completeEveryExec(dockerClient, "guest", 0, "", "");

        manager.detachVolume(volume(), instance, "/dev/xvdf");

        assertTrue(guestCommands.isEmpty());
        assertEquals(1, helperCommands.size());
        assertTrue(helperCommands.getFirst().get(2).contains("losetup -d"));
        assertFalse(helperCommands.getFirst().get(2).contains("rm -f"));
    }

    @Test
    void rawGuestInspectionFailureDoesNotBecomeMetadataOnlySuccess() {
        Instance instance = runningInstance();
        IllegalStateException unavailable = new IllegalStateException("Docker inspection unavailable");
        when(dockerClient.inspectContainerCmd("guest").exec()).thenThrow(unavailable);

        assertSame(unavailable, assertThrows(IllegalStateException.class,
                () -> manager.attachVolume(volume(), instance, "/dev/xvdf")));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void attachVolumeCreatesTheDeviceBeforeReportingSuccess() {
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID,
                0, "1 /dev/loop3", "");
        List<List<String>> targetCommands = ContainerExecStubs.completeEveryExec(dockerClient, "guest", 0, "", "");

        assertEquals(Optional.of("/dev/xvdf"), manager.attachVolume(volume(), runningInstance(), "xvdf"));
        assertEquals(1, helperCommands.size());
        assertEquals(1, targetCommands.size());
        assertTrue(targetCommands.getFirst().get(2).endsWith("[ -b \"$dev\" ]"));
        assertEquals("/dev/xvdf", targetCommands.getFirst().get(4));
    }

    @Test
    void loopAllocationFailureReturnsAnAwsErrorWithoutReleasingUnownedLoops() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID,
                List.of(1L, 0L), "", "permission denied");

        AwsException error = assertThrows(AwsException.class,
                () -> manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));

        assertEquals("InternalError", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
        assertFalse(error.getMessage().contains("permission denied"));
        assertEquals(1, commands.size());
        verify(dockerClient, never()).execCreateCmd("guest");
    }

    @Test
    void guestDeviceFailureCleansUpAndAllowsRetry() {
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID,
                0, "1 /dev/loop3", "");
        List<List<String>> targetCommands = ContainerExecStubs.completeEveryExec(dockerClient, "guest",
                List.of(1L, 0L, 0L), "", "");
        Instance instance = runningInstance();

        assertThrows(AwsException.class, () -> manager.attachVolume(volume(), instance, "/dev/xvdf"));
        assertEquals(List.of("rm", "-f", "/dev/xvdf"), targetCommands.getLast());
        assertEquals(List.of("losetup", "-d", "/dev/loop3"), helperCommands.getLast());
        assertEquals(Optional.of("/dev/xvdf"), manager.attachVolume(volume(), instance, "/dev/xvdf"));
    }

    @Test
    void failedRestoreDoesNotReleaseTheReusedLoopAndCanBeRetried() {
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID,
                0, "0 /dev/loop3", "");
        ContainerExecStubs.completeEveryExec(dockerClient, "guest", List.of(1L, 0L, 0L), "", "");
        Volume volume = volume();
        VolumeAttachment attachment = new VolumeAttachment();
        attachment.setVolumeId(volume.getVolumeId());
        attachment.setDevice("/dev/xvdf");
        attachment.setState("attached");
        volume.setState("in-use");
        volume.getAttachments().add(attachment);
        Instance instance = runningInstance();

        assertDoesNotThrow(() -> manager.restoreInstanceVolumeDevices(instance, List.of(attachment),
                id -> Optional.of(volume)));

        assertEquals(1, helperCommands.size(), "restoration must not detach a pre-existing loop");
        assertEquals("in-use", volume.getState());
        assertEquals("attached", attachment.getState());
        assertEquals(Optional.of("/dev/xvdf"), manager.attachVolume(volume, instance, "/dev/xvdf"));
        assertEquals(2, helperCommands.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void helperReportsWhetherItAllocatedOrReusedTheLoop(boolean reused, @TempDir Path directory) throws Exception {
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID,
                0, "1 /dev/loop3", "");
        ContainerExecStubs.completeEveryExec(dockerClient, "guest", 0, "", "");
        manager.attachVolume(volume(), runningInstance(), "/dev/xvdf");
        String script = helperCommands.getFirst().get(2);
        String fakeCommands = "losetup() {\n"
                + "  case \"$1\" in\n"
                + "    -j) " + (reused ? "echo \"/dev/loop3: 0 $2\"" : ":") + ";;\n"
                + "    -f) echo /dev/loop3;;\n"
                + "    /dev/loop3) :;;\n"
                + "    *) return 1;;\n"
                + "  esac\n"
                + "}\n"
                + "mknod() { :; }\n";
        Path rawFile = Files.createFile(directory.resolve("volume.raw"));
        int exitCode = HelperShellFixture.run(
                new String[]{"sh", "-c", fakeCommands + script, "helper", rawFile.toString(), "1"}, directory, Map.of());
        String output = HelperShellFixture.output(directory).trim();
        assertEquals(0, exitCode, output);
        assertEquals((reused ? "0" : "1") + " /dev/loop3", output);
    }

    @Test
    void cleanupFailureStillReportsTheOriginalAttachmentError() {
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID,
                List.of(0L, 1L), "1 /dev/loop3", "");
        ContainerExecStubs.completeEveryExec(dockerClient, "guest", 1, "", "permission denied");

        AwsException error = assertThrows(AwsException.class,
                () -> manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));

        assertEquals("InternalError", error.getErrorCode());
        assertEquals(2, helperCommands.size());
        assertEquals(List.of("losetup", "-d", "/dev/loop3"), helperCommands.getLast());
    }

    @Test
    void mockModeRetainsMetadataOnlyTracking() {
        when(config.services().ec2().mock()).thenReturn(true);

        assertEquals(Optional.empty(), manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void unexpectedLoopOutputFailsWithoutCreatingAGuestDevice() {
        ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "not a loop device", "");

        assertThrows(AwsException.class, () -> manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
        verify(dockerClient, never()).execCreateCmd("guest");
    }

    @Test
    void dockerExecExceptionFailsAttachment() {
        when(dockerClient.execCreateCmd(HELPER_ID)).thenThrow(new IllegalStateException("daemon went away"));

        assertThrows(AwsException.class, () -> manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
    }

    @Test
    void unavailableHelperFailsAttachment() {
        when(dockerClient.inspectContainerCmd(anyString()).exec()).thenThrow(new IllegalStateException("unavailable"));

        assertThrows(AwsException.class, () -> manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
    }

    @Test
    void disabledBlockDevicesRetainMetadataOnlyTracking() {
        when(config.services().ec2().volumeBlockDevices()).thenReturn(false);

        assertEquals(Optional.empty(), manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void unavailableDockerRetainsMetadataOnlyTracking() {
        when(dockerClient.pingCmd().exec()).thenThrow(new IllegalStateException("unavailable"));

        assertEquals(Optional.empty(), manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void stoppedInstanceDefersDeviceCreationEvenWhenContainerIsStillRunning() {
        Instance instance = runningInstance();
        instance.setState(InstanceState.stopped());

        assertEquals(Optional.empty(), manager.attachVolume(volume(), instance, "/dev/xvdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void missingOrStoppedContainerRetainsMetadataOnlyTracking() {
        Instance instance = runningInstance();
        when(lifecycleManager.isContainerRunning("guest")).thenReturn(false);
        assertEquals(Optional.empty(), manager.attachVolume(volume(), instance, "/dev/xvdf"));
        instance.setDockerContainerId(null);
        assertEquals(Optional.empty(), manager.attachVolume(volume(), instance, "/dev/xvdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void restorationLogsAttachmentFailureAndKeepsExistingRecords() {
        ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 1, "", "permission denied");
        VolumeAttachment attachment = new VolumeAttachment();
        attachment.setVolumeId("vol-1");
        attachment.setDevice("/dev/xvdf");
        attachment.setState("attached");

        assertDoesNotThrow(() -> manager.restoreInstanceVolumeDevices(runningInstance(), List.of(attachment),
                id -> Optional.of(volume())));
        assertEquals("attached", attachment.getState());
    }

    /**
     * Inside a container the host persistent path names a directory on the Docker host, which
     * Floci can neither see nor create. Creating it from here used to fail, so no helper started
     * and every attachment reported the helper unavailable; the daemon creates the bind source.
     */
    @Test
    void helperInsideAContainerLeavesCreatingTheHostBindDirectoryToDocker(@TempDir Path directory) {
        Path hostPersistentPath = directory.resolve("floci-data");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        ContainerBuilder.Builder helperBuilder = helperToBeCreatedOn(hostPersistentPath);

        assertEquals(Optional.of("/dev/xvdf"), manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
        assertFalse(Files.exists(hostPersistentPath), "Floci must not create the Docker host's directory itself");
        verify(helperBuilder).withBind(hostPersistentPath.resolve("ec2").resolve("volumes").toString(), "/volumes");
    }

    @Test
    void helperOnTheHostCreatesTheBindDirectoryBeforeBindingIt(@TempDir Path directory) {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        ContainerBuilder.Builder helperBuilder = helperToBeCreatedOn(directory);

        assertEquals(Optional.of("/dev/xvdf"), manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
        assertTrue(Files.isDirectory(directory.resolve("ec2").resolve("volumes")));
        verify(helperBuilder).withBind(directory.resolve("ec2").resolve("volumes").toString(), "/volumes");
    }

    /**
     * Stubs bind-mode storage under {@code hostPersistentPath} with no helper container yet, so the
     * next operation creates the helper through the returned builder and its commands succeed.
     */
    private ContainerBuilder.Builder helperToBeCreatedOn(Path hostPersistentPath) {
        when(config.storage().hostPersistentPath()).thenReturn(hostPersistentPath.toString());
        when(config.services().ec2().volumeHelperImage()).thenReturn(HELPER_IMAGE);
        when(dockerClient.inspectContainerCmd(anyString()).exec()).thenThrow(new NotFoundException("no such container"));
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        ContainerSpec spec = new ContainerSpec(HELPER_IMAGE);
        when(containerBuilder.newContainer(HELPER_IMAGE)).thenReturn(builder);
        when(builder.build()).thenReturn(spec);
        when(lifecycleManager.createAndStart(spec))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo(CREATED_HELPER_ID, Map.of()));
        ContainerExecStubs.completeEveryExec(dockerClient, CREATED_HELPER_ID, 0, "1 /dev/loop3", "");
        ContainerExecStubs.completeEveryExec(dockerClient, "guest", 0, "", "");
        return builder;
    }

    private Instance runningInstance() {
        Instance instance = new Instance();
        instance.setInstanceId("i-1");
        instance.setState(InstanceState.running());
        instance.setDockerContainerId("guest");
        when(lifecycleManager.isContainerRunning("guest")).thenReturn(true);
        InspectContainerResponse guest = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(guest.getHostConfig().getPrivileged()).thenReturn(true);
        InspectContainerCmd inspectGuest = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("guest")).thenReturn(inspectGuest);
        when(inspectGuest.exec()).thenReturn(guest);
        return instance;
    }

    private Volume volume() {
        Volume volume = new Volume();
        volume.setVolumeId("vol-1");
        volume.setSize(1);
        return volume;
    }

    @Test
    void shutdownRetainsOnlyOwnedManagedVolumeBindings() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectVolumeResponse retained = managedBinding(volume, instance);
        String retainedName = ContainerStorageHelper.resourceName(config, "ec2", null, "vol-abc");
        when(retained.getName()).thenReturn(retainedName);
        InspectVolumeResponse wrongName = managedBinding(volume, instance);
        when(wrongName.getName()).thenReturn("unrelated-volume");
        InspectVolumeResponse wrongService = managedBinding(volume, instance);
        Map<String, String> foreignLabels = new LinkedHashMap<>(retained.getLabels());
        foreignLabels.put(ContainerStorageHelper.SERVICE_LABEL, "lambda");
        when(wrongService.getLabels()).thenReturn(foreignLabels);
        InspectVolumeResponse unlabelled = mock(InspectVolumeResponse.class);
        when(dockerClient.listVolumesCmd().exec().getVolumes())
                .thenReturn(List.of(retained, wrongName, wrongService, unlabelled));
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");

        manager.stopManagedContainers();

        assertEquals(1, commands.size());
        assertEquals(5, commands.getFirst().size());
        assertEquals("shutdown", commands.getFirst().get(3));
        assertEquals(" vol-abc.raw ", commands.getFirst().get(4));
        assertTrue(commands.getFirst().get(2).contains("case \"$retained\" in"));
        assertTrue(commands.getFirst().get(2).contains("continue;; esac"));
        verify(lifecycleManager).stopAndRemove(HELPER_ID, null, 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shutdownRetainsHelperWhenVolumeInventoryIsUnavailable(boolean missingResponse) {
        if (missingResponse) {
            when(dockerClient.listVolumesCmd().exec()).thenReturn(null);
        } else {
            ListVolumesResponse inventory = mock(ListVolumesResponse.class);
            when(inventory.getVolumes()).thenReturn(null);
            when(dockerClient.listVolumesCmd().exec()).thenReturn(inventory);
        }

        IllegalStateException failure = assertThrows(IllegalStateException.class, manager::stopManagedContainers);

        assertTrue(failure.getMessage().contains("retaining loop bindings"));
        verify(dockerClient, never()).execCreateCmd(anyString());
        verifyNoInteractions(lifecycleManager);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rawHotAttachDoesNotCreateDevicesInAnUnprivilegedGuest(boolean privileged) {
        Instance instance = new Instance();
        instance.setInstanceId("i-hot-attach");
        instance.setDockerContainerId("guest-container");
        Volume volume = new Volume();
        volume.setVolumeId("vol-hot-attach");
        volume.setSize(8);
        InspectContainerResponse guest = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(guest.getHostConfig().getPrivileged()).thenReturn(privileged);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(inspect.exec()).thenReturn(guest);
        when(dockerClient.inspectContainerCmd("guest-container")).thenReturn(inspect);
        when(lifecycleManager.isContainerRunning("guest-container")).thenReturn(true);
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(
                dockerClient, HELPER_ID, 0, "1 /dev/loop7", "");
        List<List<String>> guestCommands = ContainerExecStubs.completeEveryExec(
                dockerClient, "guest-container", 0, "", "");

        assertEquals(privileged ? Optional.of("/dev/xvdf") : Optional.empty(),
                manager.attachVolume(volume, instance, "/dev/xvdf"));

        assertEquals(privileged ? 1 : 0, helperCommands.size());
        assertEquals(privileged ? 1 : 0, guestCommands.size());

        manager.detachVolume(volume, instance, "/dev/xvdf");

        assertEquals(privileged ? 2 : 0, guestCommands.size(),
                "Detaching a metadata-only attachment must preserve an existing guest device path");
    }

    @Test
    void rawGuestDisappearingAfterRunningCheckStillReleasesItsLoopDevice() {
        Instance instance = new Instance();
        instance.setInstanceId("i-disappeared");
        instance.setDockerContainerId("disappeared-guest");
        Volume volume = new Volume();
        volume.setVolumeId("vol-disappeared");
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(inspect.exec()).thenThrow(new NotFoundException("guest disappeared after running check"));
        when(dockerClient.inspectContainerCmd("disappeared-guest")).thenReturn(inspect);
        when(lifecycleManager.isContainerRunning("disappeared-guest")).thenReturn(true);
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(
                dockerClient, HELPER_ID, 0, "", "");

        assertEquals(Optional.empty(), manager.attachVolume(volume, instance, "/dev/xvdf"));
        assertTrue(helperCommands.isEmpty(), "Metadata-only attach must not allocate a loop device");

        assertDoesNotThrow(() -> manager.detachVolume(volume, instance, "/dev/xvdf"));

        assertEquals(1, helperCommands.size());
        assertTrue(helperCommands.getFirst().contains("detach"));
        assertTrue(helperCommands.getFirst().contains("/volumes/vol-disappeared.raw"));
        verify(dockerClient, never()).execCreateCmd("disappeared-guest");
    }

    @Test
    void disappearedManagedGuestDoesNotFallBackToMetadataOnly() {
        Instance instance = managedInstance();
        instance.setDockerContainerId("disappeared-managed-guest");
        Volume volume = managedVolume(instance);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(inspect.exec()).thenThrow(new NotFoundException("managed guest disappeared"));
        when(dockerClient.inspectContainerCmd(instance.getDockerContainerId())).thenReturn(inspect);
        when(lifecycleManager.isContainerRunning(instance.getDockerContainerId())).thenReturn(true);

        assertThrows(NotFoundException.class, () -> manager.attachVolume(volume, instance, "/dev/xvdf"));
        assertThrows(NotFoundException.class, () -> manager.detachVolume(volume, instance, "/dev/xvdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }







    @Test
    void stopManagedContainersReleasesLoopDevicesAndRemovesTheHelper() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");

        manager.stopManagedContainers();

        assertEquals(1, commands.size());
        String script = commands.getFirst().get(2);
        assertTrue(script.contains("losetup -d \"$loop\""));
        assertFalse(script.contains("rm -f"), "backing files must survive a shutdown");
        verify(lifecycleManager).stopAndRemove(HELPER_ID, null, 0);
    }

    @Test
    void stopManagedContainersRemovesAStoppedHelperWithoutRunningAnythingInIt() {
        when(helper.getState().getRunning()).thenReturn(false);

        manager.stopManagedContainers();

        verify(dockerClient, never()).execCreateCmd(anyString());
        verify(lifecycleManager).stopAndRemove(HELPER_ID, null, 0);
    }

    @Test
    void stopManagedContainersNeverCreatesAHelperThatDoesNotExist() {
        when(dockerClient.inspectContainerCmd(anyString()).exec()).thenThrow(new NotFoundException("no such container"));

        manager.stopManagedContainers();

        verifyNoInteractions(lifecycleManager);
    }

    @Test
    void stopManagedContainersStillRemovesTheHelperWhenReleasingLoopDevicesFails() {
        when(dockerClient.execCreateCmd(HELPER_ID)).thenThrow(new IllegalStateException("daemon went away"));

        assertDoesNotThrow(() -> manager.stopManagedContainers());

        verify(lifecycleManager).stopAndRemove(HELPER_ID, null, 0);
    }

    @Test
    void stopManagedContainersFailsTheReleaseScriptWhenALoopDeviceWillNotDetach() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 1,
                "could not detach /dev/loop3 from /volumes/vol-1.raw", "");

        assertDoesNotThrow(() -> manager.stopManagedContainers());

        String script = commands.getFirst().get(2);
        assertTrue(script.contains("|| { echo \"could not detach $loop from $f\"; failed=1; }"));
        assertTrue(script.endsWith("exit $failed"));
        verify(lifecycleManager).stopAndRemove(HELPER_ID, null, 0);
    }

    @Test
    void coldResetDoesNotCreateStorageOrAHelperWhenNoBackingVolumeExists() {
        when(dockerClient.inspectContainerCmd(anyString()).exec()).thenThrow(new NotFoundException("absent"));
        when(dockerClient.inspectVolumeCmd(anyString()).exec()).thenThrow(new NotFoundException("absent"));

        manager.clear();

        verifyNoInteractions(lifecycleManager);
        verify(dockerClient, never()).createVolumeCmd();
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void coldResetRefusesForeignBackingStorageWithoutCreatingAHelper() {
        when(dockerClient.inspectContainerCmd(anyString()).exec()).thenThrow(new NotFoundException("absent"));
        InspectVolumeResponse foreign = mock(InspectVolumeResponse.class);
        when(foreign.getDriver()).thenReturn("local");
        when(foreign.getLabels()).thenReturn(Map.of());
        when(dockerClient.inspectVolumeCmd(anyString()).exec()).thenReturn(foreign);

        assertThrows(IllegalStateException.class, manager::clear);

        verifyNoInteractions(lifecycleManager);
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void clearRetainsOwnedManagedMountFiles() {
        Instance instance = managedInstance();
        Volume volume = managedVolume(instance);
        InspectVolumeResponse retained = managedBinding(volume, instance);
        String retainedName = ContainerStorageHelper.resourceName(config, "ec2", null, "vol-abc");
        when(retained.getName()).thenReturn(retainedName);
        when(dockerClient.listVolumesCmd().exec().getVolumes()).thenReturn(List.of(retained));
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");

        manager.clear();

        assertEquals(1, commands.size());
        assertEquals(5, commands.getFirst().size());
        assertEquals("clear", commands.getFirst().get(3));
        assertEquals(" vol-abc.raw ", commands.getFirst().get(4));
        String script = commands.getFirst().get(2);
        assertTrue(script.contains("case \"$retained\" in"));
        assertTrue(script.contains("[ ! -L \"$f\" ] || continue"));
        assertTrue(script.contains("losetup -d \"$loop\" || exit 1"));
        verify(lifecycleManager).stopAndRemove(HELPER_ID, null, 0);
    }

    @Test
    void clearPreservesHelperWhenBackingCleanupFails() {
        ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 1, "loop still attached", "");

        IllegalStateException failure = assertThrows(IllegalStateException.class, manager::clear);

        assertTrue(failure.getMessage().contains("loop still attached"));
        verifyNoInteractions(lifecycleManager);
    }

    @Test
    void clearRefusesUnavailableManagedInventoryBeforeExec() {
        when(dockerClient.listVolumesCmd().exec()).thenReturn(null);
        ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");

        assertThrows(IllegalStateException.class, manager::clear);

        verify(dockerClient, never()).execCreateCmd(anyString());
        verifyNoInteractions(lifecycleManager);
    }

    @Test
    void coldResetDoesNotCreateAbsentHostStorage(@TempDir Path root) {
        when(config.storage().hostPersistentPath()).thenReturn(root.toString());
        when(dockerClient.inspectContainerCmd(anyString()).exec()).thenThrow(new NotFoundException("absent"));

        manager.clear();

        assertFalse(Files.exists(ContainerStorageHelper.hostResourcePath(config, "ec2", "volumes")));
        verifyNoInteractions(lifecycleManager);
        verify(dockerClient, never()).createVolumeCmd();
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void coldResetRejectsSymlinkHostStorage(@TempDir Path root) throws Exception {
        when(config.storage().hostPersistentPath()).thenReturn(root.toString());
        Path storage = ContainerStorageHelper.hostResourcePath(config, "ec2", "volumes");
        Files.createDirectories(storage.getParent());
        Path target = Files.createDirectory(root.resolve("foreign"));
        Files.createSymbolicLink(storage, target);
        when(dockerClient.inspectContainerCmd(anyString()).exec()).thenThrow(new NotFoundException("absent"));

        assertThrows(IllegalStateException.class, manager::clear);

        assertTrue(Files.isSymbolicLink(storage));
        assertTrue(Files.isDirectory(target));
        verifyNoInteractions(lifecycleManager);
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void clearDeletesTheBackingFilesAndRemovesTheHelper() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");

        manager.clear();

        assertEquals(1, commands.size());
        assertTrue(commands.getFirst().get(2).contains("rm -f \"$f\""));
        verify(lifecycleManager).stopAndRemove(HELPER_ID, null, 0);
    }



    @Test
    void stopManagedContainersDoesNothingWhenBlockDevicesAreDisabled() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().volumeBlockDevices()).thenReturn(false);
        Ec2VolumeBlockDeviceManager disabledManager = new Ec2VolumeBlockDeviceManager(dockerClient,
                mock(ContainerBuilder.class), lifecycleManager, mock(ContainerDetector.class), config);

        disabledManager.stopManagedContainers();

        verify(lifecycleManager, never()).stopAndRemove(anyString(), any(), anyInt());
    }

    @Test
    void createVolumeSizesTheBackingFileInsideTheHelperContainer() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");

        manager.createVolume("vol-1", 0);

        assertEquals(List.of(List.of("sh", "-c", "truncate -s 8G /volumes/vol-1.raw")), commands);
    }

    @Test
    void aDockerFailureWhileRunningTheCommandIsNotThrown() {
        when(dockerClient.execCreateCmd(HELPER_ID)).thenThrow(new IllegalStateException("daemon went away"));

        assertDoesNotThrow(() -> manager.createVolume("vol-1", 8));
    }

    @Test
    void resizeVolumeSizesBackingFileAndRefreshesLoopDevicesInsideHelperContainer() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");

        boolean ok = manager.resizeVolume("vol-1", 16);

        assertTrue(ok);
        assertEquals(1, commands.size());
        List<String> cmd = commands.getFirst();
        assertEquals("sh", cmd.get(0));
        assertEquals("-c", cmd.get(1));
        assertEquals("resize", cmd.get(3));
        assertEquals("/volumes/vol-1.raw", cmd.get(4));
        assertEquals("16", cmd.get(5));
        assertTrue(cmd.get(2).contains("truncate -s \"${size}G\" \"$raw\""));
        assertTrue(cmd.get(2).contains("losetup -c \"$loop\""));
        assertTrue(cmd.get(2).contains("cur_bytes=$(stat -c %s \"$raw\" 2>/dev/null || echo 0)"));
        assertTrue(cmd.get(2).contains("if [ \"$cur_bytes\" -lt \"$target_bytes\" ]; then"));
        assertTrue(cmd.get(2).contains("truncate -s \"$cur_bytes\" \"$raw\""));
        assertTrue(cmd.get(2).contains("for rloop in $(find_loops \"$raw\"); do"));
    }

    @Test
    void resizeVolumeDockerFailureWhileRunningIsNotThrown() {
        when(dockerClient.execCreateCmd(HELPER_ID)).thenThrow(new IllegalStateException("daemon went away"));

        assertDoesNotThrow(() -> {
            boolean ok = manager.resizeVolume("vol-1", 16);
            assertFalse(ok);
        });
    }

    @Test
    void resizeVolumeTruncateFailureLogsAndReturnsFalseWithoutThrowing() {
        ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 1, "", "truncate: out of space");

        assertDoesNotThrow(() -> {
            boolean ok = manager.resizeVolume("vol-1", 16);
            assertFalse(ok);
        });
    }

    @Test
    void resizeVolumeRefreshFailureLogsAndReturnsFalseWithoutThrowing() {
        ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 2, "", "losetup: invalid option -- c");

        assertDoesNotThrow(() -> {
            boolean ok = manager.resizeVolume("vol-1", 16);
            assertFalse(ok);
        });
    }

    @Test
    void resizeVolumeReturnsFalseWhenNotAvailable() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().volumeBlockDevices()).thenReturn(false);
        Ec2VolumeBlockDeviceManager disabledManager = new Ec2VolumeBlockDeviceManager(dockerClient,
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(ContainerDetector.class), config);

        assertFalse(disabledManager.resizeVolume("vol-1", 16));
    }
}
