package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExecStubs;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2SystemdV1RuntimeTest {
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final Ec2SystemdV1Runtime runtime;

    Ec2SystemdV1RuntimeTest() {
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("owner"));
        runtime = new Ec2SystemdV1Runtime(docker, mock(ContainerBuilder.class), lifecycle, config);
    }

    @Test
    void catalogSelectsNativeInitOnlyForExplicitSystemdModes() {
        assertTrue(new ResolvedAmiImage("guest", "systemd-v1", true, null).systemd());
        assertTrue(new ResolvedAmiImage("guest", "systemd-v1", true, null).systemdV1());
        assertFalse(new ResolvedAmiImage("guest", "systemd", true, null).systemdV1());
        assertFalse(new ResolvedAmiImage("guest", "image", true, null).systemd());
        assertFalse(ResolvedAmiImage.minimal("guest").systemdV1());
    }

    @Test
    void hardwareIdentityIsStableAndRecognizableWithoutHostDmi() {
        String uuid = Ec2SystemdV1Runtime.hardwareUuid("123456789012", "eu-west-1", "i-owned");
        assertEquals(uuid, UUID.fromString(uuid).toString());
        assertTrue(uuid.startsWith("ec2"));
        assertEquals(uuid, Ec2SystemdV1Runtime.hardwareUuid("123456789012", "eu-west-1", "i-owned"));
        assertNotEquals(uuid, Ec2SystemdV1Runtime.hardwareUuid("123456789012", "eu-west-1", "i-other"));
        assertNotEquals(uuid, Ec2SystemdV1Runtime.hardwareUuid("210987654321", "eu-west-1", "i-owned"));
        assertNotEquals(uuid, Ec2SystemdV1Runtime.hardwareUuid("123456789012", "us-gov-east-1", "i-owned"));
    }

    @Test
    void validatesOwnedScopeWithoutChangingDockerState() {
        assertEquals("floci-" + "a".repeat(32),
                runtime.requireHelper(helper(labels()), "i-owned", "eu-west-1", "123456789012"));
        verifyNoInteractions(docker, lifecycle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unconfined", "floci-native"})
    void helperUsesTheNativeMountProfileWithoutPrivilegedMode(String profile) {
        when(config.services().ec2().nativeInitApparmorProfile())
                .thenReturn("unconfined".equals(profile) ? Optional.empty() : Optional.of(profile));
        when(config.services().ec2().metadataHelperImage()).thenReturn("test/native-helper:1");
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("3");
        String name = ContainerStorageHelper.resourceName(config, Ec2SystemdV1Runtime.SERVICE, null, "i-owned");
        when(docker.inspectContainerCmd(name).exec()).thenThrow(new NotFoundException("not created"));
        when(lifecycle.create(any(ContainerSpec.class))).thenAnswer(invocation -> {
            ContainerSpec spec = invocation.getArgument(0);
            assertEquals(profile, spec.linuxOptions().apparmorProfile());
            assertFalse(spec.privileged());
            InspectContainerResponse response = helper(spec.labels());
            when(response.getState()).thenReturn(mock(InspectContainerResponse.ContainerState.class));
            when(response.getState().getRunning()).thenReturn(true);
            when(docker.inspectContainerCmd("owned-helper").exec()).thenReturn(response);
            return "owned-helper";
        });
        ContainerExecStubs.completeEveryExec(docker, "owned-helper", 0, "", "");
        ContainerBuilder builder = new ContainerBuilder(config, mock(DockerHostResolver.class), null);

        new Ec2SystemdV1Runtime(docker, builder, lifecycle, config)
                .prepare(instance(), "123456789012", "eu-west-1");

        verify(lifecycle).startCreated(eq("owned-helper"), any(ContainerSpec.class));
    }

    @Test
    void ordinaryGuestWithoutRuntimeLabelsDoesNotAcquireAnInitHelper() {
        InspectContainerResponse ordinary = mock(InspectContainerResponse.class);
        Instance instance = instance();
        assertFalse(runtime.rememberGuest(instance, ordinary));
        runtime.remove(instance);
        verifyNoInteractions(docker, lifecycle);
    }

    @Test
    void anUnseenInstanceDoesNotRequireInitHelperLookupDuringTermination() {
        runtime.remove(instance());
        verifyNoInteractions(docker, lifecycle);
    }

    @Test
    void aRestoredGuestMustRetainItsRuntimeIdentityBeforeRelease() {
        Instance instance = instance();
        Map<String, String> labels = labels();
        labels.put(ContainerStorageHelper.SERVICE_LABEL, "ec2");
        labels.put(Ec2SystemdV1Runtime.RUNTIME_LABEL, ResolvedAmiImage.SYSTEMD_V1_RUNTIME);
        assertTrue(runtime.rememberGuest(instance, helper(labels)));
        labels.remove(Ec2SystemdV1Runtime.RUNTIME_LABEL);
        assertThrows(IllegalStateException.class, () -> runtime.rememberGuest(instance, helper(labels)));
        verifyNoInteractions(docker, lifecycle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"io.floci.owner", "io.floci.service", "io.floci.account",
            "io.floci.resource-id", "io.floci.region"})
    void refusesChangedHelperIdentity(String key) {
        Map<String, String> labels = labels();
        labels.put(key, "foreign");
        assertThrows(IllegalStateException.class,
                () -> runtime.requireHelper(helper(labels), "i-owned", "eu-west-1", "123456789012"));
        verifyNoInteractions(docker, lifecycle);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"210987654321"})
    void aRestoredGuestMustBelongToItsInstancesAccount(String account) {
        Map<String, String> labels = labels();
        labels.put(ContainerStorageHelper.SERVICE_LABEL, "ec2");
        labels.put(Ec2SystemdV1Runtime.RUNTIME_LABEL, ResolvedAmiImage.SYSTEMD_V1_RUNTIME);
        if (account == null) {
            labels.remove(ContainerStorageHelper.ACCOUNT_LABEL);
        } else {
            labels.put(ContainerStorageHelper.ACCOUNT_LABEL, account);
        }
        assertThrows(IllegalStateException.class, () -> runtime.rememberGuest(instance(), helper(labels)));
        verifyNoInteractions(docker, lifecycle);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void aGuestCannotBeRestoredWithoutItsDeclaredAccount(String account) {
        Instance instance = instance();
        instance.setOwnerId(account);
        Map<String, String> labels = labels();
        labels.put(ContainerStorageHelper.SERVICE_LABEL, "ec2");
        labels.put(Ec2SystemdV1Runtime.RUNTIME_LABEL, ResolvedAmiImage.SYSTEMD_V1_RUNTIME);
        assertThrows(IllegalStateException.class, () -> runtime.rememberGuest(instance, helper(labels)));
        verifyNoInteractions(docker, lifecycle);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void anInitHelperCannotBeAdoptedWithoutItsDeclaredAccount(String account) {
        assertThrows(IllegalStateException.class,
                () -> runtime.requireHelper(helper(labels()), "i-owned", "eu-west-1", account));
        verifyNoInteractions(docker, lifecycle);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void anInitHelperMustCarryItsAccountIdentity(String account) {
        Map<String, String> labels = labels();
        if (account == null) {
            labels.remove(ContainerStorageHelper.ACCOUNT_LABEL);
        } else {
            labels.put(ContainerStorageHelper.ACCOUNT_LABEL, account);
        }
        assertThrows(IllegalStateException.class,
                () -> runtime.requireHelper(helper(labels), "i-owned", "eu-west-1", "123456789012"));
        verifyNoInteractions(docker, lifecycle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "../system.slice", "floci-" + "a", "floci-/../system.slice",
            "floci-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void refusesScopePathsThatCouldEscapeTheOwnedDirectory(String scope) {
        Map<String, String> labels = labels();
        labels.put(Ec2SystemdV1Runtime.SCOPE_LABEL, scope);
        assertThrows(IllegalStateException.class,
                () -> runtime.requireHelper(helper(labels), "i-owned", "eu-west-1", "123456789012"));
        verifyNoInteractions(docker, lifecycle);
    }

    @Test
    void ignoresOtherServicesEvenIfDockerReturnsThemToTheFilteredList() {
        Container other = mock(Container.class);
        when(other.getLabels()).thenReturn(Map.of(ContainerStorageHelper.SERVICE_LABEL, "ec2",
                ContainerStorageHelper.OWNER_LABEL, "owner/4566"));
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec())
                .thenReturn(List.of(other));
        runtime.reconcileOrphans((region, instance) -> fail("Not an init helper"));
        verify(docker, never()).inspectContainerCmd(anyString());
        verifyNoInteractions(lifecycle);
    }

    private Map<String, String> labels() {
        return new HashMap<>(Map.of(
                ContainerStorageHelper.OWNER_LABEL, "owner/4566",
                ContainerStorageHelper.SERVICE_LABEL, Ec2SystemdV1Runtime.SERVICE,
                ContainerStorageHelper.ACCOUNT_LABEL, "123456789012",
                ContainerStorageHelper.RESOURCE_ID_LABEL, "i-owned",
                ContainerStorageHelper.REGION_LABEL, "eu-west-1",
                Ec2SystemdV1Runtime.SCOPE_LABEL, "floci-" + "a".repeat(32)));
    }

    private Instance instance() {
        Instance instance = new Instance();
        instance.setInstanceId("i-owned");
        instance.setOwnerId("123456789012");
        instance.setRegion("eu-west-1");
        return instance;
    }

    private InspectContainerResponse helper(Map<String, String> labels) {
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        ContainerConfig containerConfig = mock(ContainerConfig.class);
        when(response.getId()).thenReturn("owned-helper");
        when(response.getConfig()).thenReturn(containerConfig);
        when(containerConfig.getLabels()).thenReturn(labels);
        return response;
    }
}
