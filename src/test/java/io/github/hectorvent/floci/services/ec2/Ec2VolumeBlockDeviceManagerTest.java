package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExecStubs;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers how the volume manager drives its helper container without a Docker daemon: the command
 * it runs there, and that a Docker failure while running it is reported rather than thrown.
 */
class Ec2VolumeBlockDeviceManagerTest {

    private static final String HELPER_ID = "helper-container-id";

    private DockerClient dockerClient;
    private InspectContainerResponse helper;
    private ContainerLifecycleManager lifecycleManager;
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

        lifecycleManager = mock(ContainerLifecycleManager.class);
        manager = new Ec2VolumeBlockDeviceManager(dockerClient, mock(ContainerBuilder.class),
                lifecycleManager, config);
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
                dockerClient, HELPER_ID, 0, "/dev/loop7", "");
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
    void clearDeletesTheBackingFilesAndRemovesTheHelper() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");

        manager.clear();

        assertEquals(1, commands.size());
        assertTrue(commands.getFirst().get(2).contains("rm -f \"$f\""));
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
    void stopManagedContainersDoesNothingWhenBlockDevicesAreDisabled() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().volumeBlockDevices()).thenReturn(false);
        Ec2VolumeBlockDeviceManager disabledManager = new Ec2VolumeBlockDeviceManager(dockerClient,
                mock(ContainerBuilder.class), lifecycleManager, config);

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
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), config);

        assertFalse(disabledManager.resizeVolume("vol-1", 16));
    }
}
