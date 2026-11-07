package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExecStubs;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2SystemdV1RuntimeLifecycleTest {
    private static final String GUEST = "owned-guest";
    private static final String HELPER = "owned-helper";
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final Instance instance = new Instance();
    private final InspectContainerResponse helper = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
    private final InspectContainerResponse guest = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
    private final Ec2SystemdV1Runtime runtime;
    private final String helperName;

    Ec2SystemdV1RuntimeLifecycleTest() {
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("owner"));
        instance.setInstanceId("i-owned");
        instance.setOwnerId("123456789012");
        instance.setRegion("us-gov-east-1");
        instance.setDockerContainerId(GUEST);
        helperName = ContainerStorageHelper.resourceName(config, Ec2SystemdV1Runtime.SERVICE, null, "i-owned");
        when(helper.getId()).thenReturn(HELPER);
        when(helper.getConfig().getLabels()).thenReturn(labels(Ec2SystemdV1Runtime.SERVICE));
        when(helper.getState().getRunning()).thenReturn(true);
        when(guest.getId()).thenReturn(GUEST);
        when(guest.getConfig().getLabels()).thenReturn(labels("ec2"));
        when(guest.getState().getRunning()).thenReturn(true);
        when(guest.getState().getPidLong()).thenReturn(81L);
        when(docker.inspectContainerCmd(helperName).exec()).thenReturn(helper);
        when(docker.inspectContainerCmd(HELPER).exec()).thenReturn(helper);
        when(docker.inspectContainerCmd(GUEST).exec()).thenReturn(guest);
        runtime = new Ec2SystemdV1Runtime(docker, mock(ContainerBuilder.class), lifecycle, config);
    }

    @Test
    void removalRetriesAfterDockerDeletesTheHelperButLosesItsResponse() {
        assertTrue(runtime.rememberGuest(instance, guest));
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, HELPER, 0, "", "");
        doAnswer(invocation -> {
            when(docker.inspectContainerCmd(HELPER).exec()).thenThrow(new NotFoundException("deleted"));
            when(docker.inspectContainerCmd(helperName).exec()).thenThrow(new NotFoundException("deleted"));
            throw new IllegalStateException("Docker DELETE response lost");
        }).when(lifecycle).removeIfExistsStrict(HELPER);

        assertThrows(IllegalStateException.class, () -> runtime.remove(instance));
        assertTrue(commands.stream().anyMatch(command -> command.contains("retire-init")));
        assertDoesNotThrow(() -> runtime.remove(instance));
        runtime.remove(instance);
        verify(lifecycle, times(1)).removeIfExistsStrict(HELPER);
    }

    @Test
    void unexpectedDisappearanceStillRefusesToReleaseTheHelperLease() {
        assertTrue(runtime.rememberGuest(instance, guest));
        when(docker.inspectContainerCmd(helperName).exec()).thenThrow(new NotFoundException("missing"));
        assertThrows(IllegalStateException.class, () -> runtime.remove(instance));
        assertThrows(IllegalStateException.class, () -> runtime.remove(instance));
        verifyNoInteractions(lifecycle);
    }

    @Test
    void failedScopeRetirementCannotBeReclassifiedAsSuccessfulDeletion() {
        assertTrue(runtime.rememberGuest(instance, guest));
        ContainerExecStubs.completeEveryExec(docker, HELPER, 1, "", "scope still occupied");
        assertThrows(IllegalStateException.class, () -> runtime.remove(instance));
        when(docker.inspectContainerCmd(HELPER).exec()).thenThrow(new NotFoundException("missing"));
        when(docker.inspectContainerCmd(helperName).exec()).thenThrow(new NotFoundException("missing"));
        assertThrows(IllegalStateException.class, () -> runtime.remove(instance));
        verifyNoInteractions(lifecycle);
    }

    @Test
    void aRetriedRemovalStillRejectsChangedHelperOwnership() {
        assertTrue(runtime.rememberGuest(instance, guest));
        ContainerExecStubs.completeEveryExec(docker, HELPER, 0, "", "");
        doThrow(new IllegalStateException("Docker unavailable")).when(lifecycle).removeIfExistsStrict(HELPER);
        assertThrows(IllegalStateException.class, () -> runtime.remove(instance));
        Map<String, String> foreign = labels(Ec2SystemdV1Runtime.SERVICE);
        foreign.put(ContainerStorageHelper.ACCOUNT_LABEL, "210987654321");
        when(helper.getConfig().getLabels()).thenReturn(foreign);
        assertThrows(IllegalStateException.class, () -> runtime.remove(instance));
        verify(lifecycle, times(1)).removeIfExistsStrict(HELPER);
    }

    @Test
    void releaseInstallsTheBootstrapBeforeDelegatingAndOpeningTheFinalGate() {
        List<List<String>> guestCommands = ContainerExecStubs.completeEveryExec(
                docker, GUEST, List.of(1L, 0L), "", "");
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(docker, HELPER, 0, "", "");
        when(docker.copyArchiveFromContainerCmd(HELPER, "/usr/local/bin/floci-ec2-init").exec())
                .thenReturn(new ByteArrayInputStream(new byte[0]));
        clearInvocations(docker);

        assertTrue(runtime.release(instance, GUEST));

        assertEquals(List.of("test", "-f", Ec2SystemdV1Runtime.READY), guestCommands.getFirst());
        assertEquals("mkdir", guestCommands.get(1).getFirst());
        assertEquals("mv", guestCommands.get(2).getFirst());
        assertEquals("rm", guestCommands.get(3).getFirst());
        assertEquals(List.of("touch", "/run/floci-init-binary-ready"), guestCommands.get(4));
        assertTrue(guestCommands.get(5).get(2).contains("/run/floci-time-ready"));
        assertEquals(List.of("touch", Ec2SystemdV1Runtime.READY), guestCommands.getLast());
        assertTrue(helperCommands.getFirst().contains("prepare-init"));
        assertEquals(List.of("/usr/local/bin/floci-ec2-init", "--configure-time", "81", GUEST),
                helperCommands.get(1));
        assertTrue(helperCommands.get(2).contains("delegate-init"));
        InOrder order = inOrder(docker);
        order.verify(docker).copyArchiveFromContainerCmd(HELPER, "/usr/local/bin/floci-ec2-init");
        order.verify(docker).copyArchiveToContainerCmd(GUEST);
        order.verify(docker, calls(4)).execCreateCmd(GUEST);
        order.verify(docker, calls(2)).execCreateCmd(HELPER);
        order.verify(docker).inspectContainerCmd(GUEST);
        order.verify(docker).execCreateCmd(GUEST);
    }

    @Test
    void failedClockConfigurationNeverOpensTheFinalBootGate() {
        List<List<String>> guestCommands = ContainerExecStubs.completeEveryExec(
                docker, GUEST, List.of(1L, 0L), "", "");
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(
                docker, HELPER, List.of(0L, 1L), "", "clock setup refused");
        when(docker.copyArchiveFromContainerCmd(HELPER, "/usr/local/bin/floci-ec2-init").exec())
                .thenReturn(new ByteArrayInputStream(new byte[0]));

        assertThrows(IllegalStateException.class, () -> runtime.release(instance, GUEST));

        assertFalse(guestCommands.contains(List.of("touch", Ec2SystemdV1Runtime.READY)));
        assertFalse(helperCommands.stream().anyMatch(command -> command.contains("delegate-init")));
    }

    private Map<String, String> labels(String service) {
        return new HashMap<>(Map.of(
                ContainerStorageHelper.OWNER_LABEL, "owner/4566",
                ContainerStorageHelper.SERVICE_LABEL, service,
                ContainerStorageHelper.ACCOUNT_LABEL, instance.getOwnerId(),
                ContainerStorageHelper.RESOURCE_ID_LABEL, instance.getInstanceId(),
                ContainerStorageHelper.REGION_LABEL, instance.getRegion(),
                Ec2SystemdV1Runtime.RUNTIME_LABEL, ResolvedAmiImage.SYSTEMD_V1_RUNTIME,
                Ec2SystemdV1Runtime.SCOPE_LABEL, "floci-" + "a".repeat(32)));
    }
}
