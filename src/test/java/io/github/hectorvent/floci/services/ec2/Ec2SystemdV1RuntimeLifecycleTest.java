package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExecStubs;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        assertEquals(List.of("/usr/local/bin/floci-ec2-init", "--delegate-init", "81", GUEST,
                Ec2SystemdV1Runtime.ROOT + "/systemd/floci-" + "a".repeat(32) + "/cgroup.procs"),
                helperCommands.get(2));
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
        assertFalse(helperCommands.stream().anyMatch(command -> command.contains("--delegate-init")));
    }

    @Test
    void failedIdentityPinnedDelegationNeverOpensTheFinalBootGate() {
        List<List<String>> guestCommands = ContainerExecStubs.completeEveryExec(
                docker, GUEST, List.of(1L, 0L), "", "");
        ContainerExecStubs.completeEveryExec(
                docker, HELPER, List.of(0L, 0L, 1L), "", "original PID namespace no longer has an init");
        when(docker.copyArchiveFromContainerCmd(HELPER, "/usr/local/bin/floci-ec2-init").exec())
                .thenReturn(new ByteArrayInputStream(new byte[0]));

        assertThrows(IllegalStateException.class, () -> runtime.release(instance, GUEST));

        assertFalse(guestCommands.contains(List.of("touch", Ec2SystemdV1Runtime.READY)));
    }

    @ParameterizedTest
    @CsvSource({"82,true", "81,false"})
    void changedGuestAfterSuccessfulDelegationNeverOpensTheFinalBootGate(long pid, boolean running) {
        List<List<String>> guestCommands = ContainerExecStubs.completeEveryExec(
                docker, GUEST, List.of(1L, 0L), "", "");
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(docker, HELPER, 0, "", "");
        when(docker.copyArchiveFromContainerCmd(HELPER, "/usr/local/bin/floci-ec2-init").exec())
                .thenReturn(new ByteArrayInputStream(new byte[0]));
        InspectContainerResponse changed = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(changed.getState().getPidLong()).thenReturn(pid);
        when(changed.getState().getRunning()).thenReturn(running);
        when(docker.inspectContainerCmd(GUEST).exec()).thenReturn(guest, changed);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runtime.release(instance, GUEST));

        assertEquals("EC2 init guest changed while delegating its cgroup", failure.getMessage());
        assertTrue(helperCommands.stream().anyMatch(command -> command.contains("--delegate-init")));
        assertFalse(guestCommands.contains(List.of("touch", Ec2SystemdV1Runtime.READY)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prepare", "release", "remove", "reconcile"})
    void slowGuestDockerOperationDoesNotBlockAnotherGuestsRecovery(String operation) throws Exception {
        assertTrue(runtime.rememberGuest(instance, guest));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        String blockedContainer = switch (operation) {
            case "release" -> GUEST;
            case "reconcile" -> HELPER;
            default -> helperName;
        };
        when(docker.inspectContainerCmd(blockedContainer).exec()).thenAnswer(ignored -> {
            entered.countDown();
            if (!resume.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test did not release Docker inspection");
            }
            return "release".equals(operation) ? guest : helper;
        });
        ContainerExecStubs.completeEveryExec(docker, GUEST, 0, "", "");
        ContainerExecStubs.completeEveryExec(docker, HELPER, 0, "", "");
        Container listed = mock(Container.class);
        when(listed.getId()).thenReturn(HELPER);
        when(listed.getLabels()).thenReturn(labels(Ec2SystemdV1Runtime.SERVICE));
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(
                ContainerStorageHelper.SERVICE_LABEL, Ec2SystemdV1Runtime.SERVICE,
                ContainerStorageHelper.OWNER_LABEL, "owner/4566")).exec()).thenReturn(List.of(listed));
        Instance other = new Instance();
        other.setInstanceId("i-independent");
        other.setOwnerId(instance.getOwnerId());
        other.setRegion(instance.getRegion());
        InspectContainerResponse otherGuest = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        Map<String, String> otherLabels = labels("ec2");
        otherLabels.put(ContainerStorageHelper.RESOURCE_ID_LABEL, other.getInstanceId());
        when(otherGuest.getId()).thenReturn("other-guest");
        when(otherGuest.getConfig().getLabels()).thenReturn(otherLabels);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> slow = executor.submit(() -> {
                switch (operation) {
                    case "prepare" -> runtime.prepare(instance, instance.getOwnerId(), instance.getRegion());
                    case "release" -> runtime.release(instance, GUEST);
                    case "remove" -> runtime.remove(instance);
                    case "reconcile" -> runtime.reconcileOrphans((region, id) -> false);
                    default -> throw new AssertionError(operation);
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(executor.submit(() -> runtime.rememberGuest(other, otherGuest)).get(2, TimeUnit.SECONDS));
            assertTrue(runtime.hasGuest(other));
            resume.countDown();
            slow.get(2, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void removalOfTheSameGuestWaitsForItsBootOperation() throws Exception {
        assertTrue(runtime.rememberGuest(instance, guest));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(docker.inspectContainerCmd(GUEST).exec()).thenAnswer(ignored -> {
            entered.countDown();
            if (!resume.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test did not release Docker inspection");
            }
            return guest;
        });
        ContainerExecStubs.completeEveryExec(docker, GUEST, 0, "", "");
        ContainerExecStubs.completeEveryExec(docker, HELPER, 0, "", "");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> releasing = executor.submit(() -> runtime.release(instance, GUEST));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<?> removing = executor.submit(() -> runtime.remove(instance));
            assertThrows(TimeoutException.class, () -> removing.get(100, TimeUnit.MILLISECONDS));
            verifyNoInteractions(lifecycle);
            resume.countDown();
            assertTrue(releasing.get(2, TimeUnit.SECONDS));
            removing.get(2, TimeUnit.SECONDS);
            verify(lifecycle).removeIfExistsStrict(HELPER);
            assertFalse(runtime.hasGuest(instance));
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
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
