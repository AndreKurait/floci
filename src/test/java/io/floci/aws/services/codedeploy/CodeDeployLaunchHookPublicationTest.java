package io.floci.aws.services.codedeploy;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.autoscaling.AutoScalingService;
import io.floci.aws.services.autoscaling.model.AutoScalingGroup;
import io.floci.aws.services.autoscaling.model.LifecycleHook;
import io.floci.aws.services.codedeploy.model.DeploymentGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeDeployLaunchHookPublicationTest {
    private static final String REGION = "us-east-1";
    private final AutoScalingService scaling = mock(AutoScalingService.class);
    private final Set<String> installed = new LinkedHashSet<>();
    private final DeploymentGroup desired = new DeploymentGroup();
    private final RuntimeException refusal = new IllegalStateException("hook write refused");
    private CodeDeployLaunchHooks hooks;

    @BeforeEach
    void setUp() {
        hooks = new CodeDeployLaunchHooks(scaling, CodeDeployTestRoles.allowAll(),
                new RegionResolver(REGION, "000000000000"));
        desired.setDeploymentGroupId("deployment-group");
        desired.setComputePlatform("Server");
        desired.setAutoScalingGroups(List.of(Map.of("name", "first"), Map.of("name", "second")));
        when(scaling.describeAutoScalingGroups(eq(REGION), anyList())).thenAnswer(invocation -> {
            List<String> names = invocation.getArgument(1);
            AutoScalingGroup group = new AutoScalingGroup();
            group.setAutoScalingGroupName(names.getFirst());
            return List.of(group);
        });
        when(scaling.describeLifecycleHooks(eq(REGION), anyString(), anyList())).thenAnswer(invocation ->
                installed.contains(invocation.<String>getArgument(1)) ? List.of(new LifecycleHook()) : List.of());
        doAnswer(invocation -> {
            installed.remove(invocation.<String>getArgument(1));
            return null;
        }).when(scaling).deleteLifecycleHook(eq(REGION), anyString(), anyString());
    }

    @Test
    void postWriteRefusalRemovesCurrentAndEarlierNewHooks() {
        refuseSecondPut(true);
        assertSame(refusal, assertThrows(IllegalStateException.class, () -> hooks.configure(REGION, null, desired)));
        assertTrue(installed.isEmpty(), "The throwing installation also needs compensation");
    }

    @Test
    void preWriteRefusalRemovesEarlierNewHook() {
        refuseSecondPut(false);
        assertSame(refusal, assertThrows(IllegalStateException.class, () -> hooks.configure(REGION, null, desired)));
        assertTrue(installed.isEmpty());
    }

    @Test
    void postWriteRefusalDoesNotDeletePreviouslyExistingHook() {
        installed.add("first");
        refuseSecondPut(true);
        assertSame(refusal, assertThrows(IllegalStateException.class, () -> hooks.configure(REGION, null, desired)));
        assertEquals(Set.of("first"), installed);
        verify(scaling, never()).deleteLifecycleHook(REGION, "first", CodeDeployLaunchHooks.hookName(desired));
    }

    @Test
    void compensationRefusalIsReportedWithoutReplacingOriginalFailure() {
        refuseSecondPut(true);
        RuntimeException cleanup = new IllegalStateException("hook removal refused");
        doThrow(cleanup).when(scaling).deleteLifecycleHook(REGION, "second", CodeDeployLaunchHooks.hookName(desired));
        assertSame(refusal, assertThrows(IllegalStateException.class, () -> hooks.configure(REGION, null, desired)));
        assertArrayEquals(new Throwable[]{cleanup}, refusal.getSuppressed());
        assertEquals(Set.of("second"), installed, "Refused cleanup remains unresolved, not silently successful");
    }

    @Test
    void successfulInstallationRetainsBothHooks() {
        doAnswer(invocation -> {
            installed.add(invocation.<String>getArgument(1));
            return null;
        }).when(scaling).putLifecycleHook(eq(REGION), anyString(), anyString(), anyString(),
                isNull(), isNull(), isNull(), anyInt(), anyString());
        assertEquals(List.of(), hooks.configure(REGION, null, desired));
        assertEquals(Set.of("first", "second"), installed);
        verify(scaling, never()).deleteLifecycleHook(anyString(), anyString(), anyString());
    }

    @Test
    void laterRefusalRestoresEarlierExistingHookSettings() {
        Map<String, LifecycleHook> values = existingHookValues();
        refuseHookUpdate(values, "second", false, false);
        assertSame(refusal, assertThrows(IllegalStateException.class, () -> hooks.configure(REGION, null, desired)));
        assertOriginalSettings(values.get("first"));
        verify(scaling, never()).deleteLifecycleHook(REGION, "first", CodeDeployLaunchHooks.hookName(desired));
    }

    @Test
    void throwingExistingHookWriteRestoresItsCapturedSettings() {
        Map<String, LifecycleHook> values = existingHookValues();
        desired.setAutoScalingGroups(List.of(Map.of("name", "first")));
        refuseHookUpdate(values, "first", true, false);
        assertSame(refusal, assertThrows(IllegalStateException.class, () -> hooks.configure(REGION, null, desired)));
        assertOriginalSettings(values.get("first"));
        verify(scaling, never()).deleteLifecycleHook(anyString(), anyString(), anyString());
    }

    @Test
    void existingHookRestorationRefusalRemainsSuppressedOnInstallationFailure() {
        Map<String, LifecycleHook> values = existingHookValues();
        desired.setAutoScalingGroups(List.of(Map.of("name", "first")));
        refuseHookUpdate(values, "first", true, true);
        assertSame(refusal, assertThrows(IllegalStateException.class, () -> hooks.configure(REGION, null, desired)));
        assertEquals(1, refusal.getSuppressed().length);
        assertEquals("restoration refused", refusal.getSuppressed()[0].getMessage());
        assertEquals(600, values.get("first").getHeartbeatTimeout());
    }

    @Test
    void successfulExistingHookUpdateRetainsDesiredSettings() {
        Map<String, LifecycleHook> values = existingHookValues();
        desired.setAutoScalingGroups(List.of(Map.of("name", "first")));
        refuseHookUpdate(values, "never", false, false);
        assertEquals(List.of(), hooks.configure(REGION, null, desired));
        LifecycleHook hook = values.get("first");
        assertEquals("autoscaling:EC2_INSTANCE_LAUNCHING", hook.getLifecycleTransition());
        assertEquals(600, hook.getHeartbeatTimeout());
        assertEquals("ABANDON", hook.getDefaultResult());
        assertEquals("metadata", hook.getNotificationMetadata());
        verify(scaling, times(1)).putLifecycleHook(eq(REGION), eq("first"), anyString(), anyString(),
                isNull(), isNull(), isNull(), anyInt(), anyString());
    }

    private Map<String, LifecycleHook> existingHookValues() {
        Map<String, LifecycleHook> values = new LinkedHashMap<>();
        LifecycleHook hook = new LifecycleHook();
        hook.setLifecycleTransition("autoscaling:EC2_INSTANCE_TERMINATING");
        hook.setHeartbeatTimeout(900);
        hook.setDefaultResult("CONTINUE");
        hook.setNotificationMetadata("metadata");
        values.put("first", hook);
        when(scaling.describeLifecycleHooks(eq(REGION), anyString(), anyList())).thenAnswer(invocation -> {
            LifecycleHook existing = values.get(invocation.<String>getArgument(1));
            return existing == null ? List.of() : List.of(existing);
        });
        return values;
    }

    private void refuseHookUpdate(Map<String, LifecycleHook> values, String refusedName,
                                  boolean afterWrite, boolean refuseRestoration) {
        doAnswer(invocation -> {
            String name = invocation.getArgument(1);
            int timeout = invocation.getArgument(7);
            boolean restoring = timeout == 900;
            if (restoring && refuseRestoration) {
                throw new IllegalStateException("restoration refused");
            }
            if (!restoring && refusedName.equals(name) && !afterWrite) {
                throw refusal;
            }
            LifecycleHook hook = values.computeIfAbsent(name, ignored -> new LifecycleHook());
            hook.setLifecycleTransition(invocation.getArgument(3));
            hook.setHeartbeatTimeout(timeout);
            hook.setDefaultResult(invocation.getArgument(8));
            if (!restoring && refusedName.equals(name)) {
                throw refusal;
            }
            return null;
        }).when(scaling).putLifecycleHook(eq(REGION), anyString(), anyString(), anyString(),
                isNull(), isNull(), isNull(), anyInt(), anyString());
    }

    private void assertOriginalSettings(LifecycleHook hook) {
        assertEquals("autoscaling:EC2_INSTANCE_TERMINATING", hook.getLifecycleTransition());
        assertEquals(900, hook.getHeartbeatTimeout());
        assertEquals("CONTINUE", hook.getDefaultResult());
        assertEquals("metadata", hook.getNotificationMetadata());
    }

    private void refuseSecondPut(boolean afterWrite) {
        doAnswer(invocation -> {
            String name = invocation.getArgument(1);
            if (afterWrite || !"second".equals(name)) {
                installed.add(name);
            }
            if ("second".equals(name)) {
                throw refusal;
            }
            return null;
        }).when(scaling).putLifecycleHook(eq(REGION), anyString(), anyString(), anyString(),
                isNull(), isNull(), isNull(), anyInt(), anyString());
    }
}
