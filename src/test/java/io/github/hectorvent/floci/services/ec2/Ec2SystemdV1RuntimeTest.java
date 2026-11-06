package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
        assertEquals("floci-" + "a".repeat(32), runtime.requireHelper(helper(labels()), "i-owned", "eu-west-1"));
        verifyNoInteractions(docker, lifecycle);
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
    @ValueSource(strings = {"io.floci.owner", "io.floci.service", "io.floci.resource-id", "io.floci.region"})
    void refusesChangedHelperIdentity(String key) {
        Map<String, String> labels = labels();
        labels.put(key, "foreign");
        assertThrows(IllegalStateException.class,
                () -> runtime.requireHelper(helper(labels), "i-owned", "eu-west-1"));
        verifyNoInteractions(docker, lifecycle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "../system.slice", "floci-" + "a", "floci-/../system.slice",
            "floci-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void refusesScopePathsThatCouldEscapeTheOwnedDirectory(String scope) {
        Map<String, String> labels = labels();
        labels.put(Ec2SystemdV1Runtime.SCOPE_LABEL, scope);
        assertThrows(IllegalStateException.class,
                () -> runtime.requireHelper(helper(labels), "i-owned", "eu-west-1"));
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
                ContainerStorageHelper.RESOURCE_ID_LABEL, "i-owned",
                ContainerStorageHelper.REGION_LABEL, "eu-west-1",
                Ec2SystemdV1Runtime.SCOPE_LABEL, "floci-" + "a".repeat(32)));
    }

    private Instance instance() {
        Instance instance = new Instance();
        instance.setInstanceId("i-owned");
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
