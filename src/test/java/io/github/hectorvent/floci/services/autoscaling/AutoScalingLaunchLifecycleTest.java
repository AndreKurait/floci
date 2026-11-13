package io.github.hectorvent.floci.services.autoscaling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchLifecycleAction;
import io.github.hectorvent.floci.services.autoscaling.model.LifecycleHook;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AutoScalingLaunchLifecycleTest {
    private static final String REGION = "us-east-1";
    private static final String FLEET = "waiting-fleet";
    private static final String INSTANCE = "i-0123456789abcdef0";
    private AutoScalingService service;
    private Ec2Service ec2;
    private ElbV2Service targets;
    private ElbClassicService classic;
    private AutoScalingReconciler reconciler;
    private AutoScalingGroup group;
    private AsgInstance member;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Service.class);
        targets = mock(ElbV2Service.class);
        classic = mock(ElbClassicService.class);
        service = new AutoScalingService(targets, classic);
        service.regionResolver = mock(RegionResolver.class);
        when(service.regionResolver.getAccountId()).thenReturn("123456789012");
        service.ec2Service = ec2;
        group = group(REGION);
        member = group.getInstances().getFirst();
        reconciler = new AutoScalingReconciler(service, ec2, targets, classic, null);
        Instance instance = new Instance();
        instance.setInstanceId(INSTANCE);
        instance.setState(InstanceState.running());
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(instance));
        when(ec2.describeInstances(eq(REGION), eq(List.of(INSTANCE)), isNull()))
                .thenReturn(List.of(reservation));
        when(ec2.isInstanceContainerRunning(INSTANCE)).thenReturn(true);
    }

    @AfterEach
    void stopWorkers() {
        reconciler.stop();
    }

    @Test
    void allHooksMustContinueBeforeTheHostRegistersOrBecomesInService() {
        hook(REGION, "prepare", 300, "ABANDON");
        hook(REGION, "verify", 300, "ABANDON");

        reconciler.reconcile(group);
        reconciler.reconcile(group);

        assertEquals("Pending:Wait", member.getLifecycleState());
        assertEquals(1, AutoScalingReconciler.activeCapacity(group));
        assertEquals(1, group.getInstances().size());
        verifyNoInteractions(targets, classic);
        service.completeLifecycleAction(REGION, FLEET, "prepare", INSTANCE, "CONTINUE", null);
        reconciler.reconcile(group);
        assertEquals("Pending:Wait", member.getLifecycleState());
        verifyNoInteractions(targets, classic);

        String token = member.getLaunchLifecycleActions().get("verify").token();
        service.completeLifecycleAction(REGION, FLEET, "verify", null, "CONTINUE", token);
        assertEquals("Pending:Proceed", member.getLifecycleState());
        reconciler.reconcile(group);
        assertEquals("InService", member.getLifecycleState());
        verify(targets).registerTargets(eq(REGION), eq("target-group"), anyList());
        verify(classic).registerInstances(REGION, "classic", List.of(INSTANCE));
    }

    @Test
    void oneAbandonedHookPreventsAdmissionAfterAnotherHookContinues() {
        hook(REGION, "prepare", 300, "CONTINUE");
        hook(REGION, "verify", 300, "CONTINUE");
        reconciler.reconcile(group);

        service.completeLifecycleAction(REGION, FLEET, "prepare", INSTANCE, "CONTINUE", null);
        service.completeLifecycleAction(REGION, FLEET, "verify", INSTANCE, "ABANDON", null);

        assertEquals("Terminating", member.getLifecycleState());
        assertEquals(0, AutoScalingReconciler.activeCapacity(group));
        verifyNoInteractions(targets, classic);
        assertThrows(AwsException.class, () -> service.recordLifecycleActionHeartbeat(
                REGION, FLEET, "prepare", INSTANCE, null));
    }

    @ParameterizedTest
    @CsvSource({"ABANDON,Terminating", "CONTINUE,Pending:Proceed"})
    void timeoutUsesTheCapturedDefaultAndAnExpiredHeartbeatCannotReviveIt(
            String defaultResult, String state) {
        hook(REGION, "prepare", 300, defaultResult);
        service.beginLaunchLifecycleActions(group, member, Instant.now().minusSeconds(301));
        LaunchLifecycleAction expired = member.getLaunchLifecycleActions().get("prepare");

        AwsException error = assertThrows(AwsException.class, () -> service.recordLifecycleActionHeartbeat(
                REGION, FLEET, "prepare", INSTANCE, null));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(expired, member.getLaunchLifecycleActions().get("prepare"));
        service.settleLaunchLifecycleActions(group, Instant.now());

        assertEquals(state, member.getLifecycleState());
        assertEquals("Heartbeat Timeout", member.getLaunchLifecycleActions().get("prepare").reason());
    }

    @Test
    void heartbeatExtendsTheCapturedTimeoutEvenAfterTheHookIsUpdated() {
        hook(REGION, "prepare", 300, "ABANDON");
        service.beginLaunchLifecycleActions(group, member, Instant.now().minusSeconds(100));
        LaunchLifecycleAction original = member.getLaunchLifecycleActions().get("prepare");
        hook(REGION, "prepare", 30, "CONTINUE");
        Instant beforeHeartbeat = Instant.now();

        service.recordLifecycleActionHeartbeat(REGION, FLEET, "prepare", null, original.token());

        LaunchLifecycleAction extended = member.getLaunchLifecycleActions().get("prepare");
        assertEquals(original.token(), extended.token());
        assertEquals(original.globalDeadline(), extended.globalDeadline());
        assertEquals("ABANDON", extended.defaultResult());
        assertFalse(extended.deadline().isBefore(beforeHeartbeat.plusSeconds(300)));
        assertTrue(extended.deadline().isAfter(original.deadline()));
        assertEquals("Pending:Wait", member.getLifecycleState());
    }

    @Test
    void heartbeatCannotExtendTheGlobalDeadline() {
        hook(REGION, "prepare", 7200, "ABANDON");
        Instant started = Instant.now().minusSeconds(172200);
        service.beginLaunchLifecycleActions(group, member, started);
        LaunchLifecycleAction old = member.getLaunchLifecycleActions().get("prepare");
        member.getLaunchLifecycleActions().put("prepare", new LaunchLifecycleAction(
                old.hookName(), old.token(), Instant.now().plusSeconds(300), old.globalDeadline(),
                old.heartbeatTimeout(), old.defaultResult(), null, null));

        service.recordLifecycleActionHeartbeat(REGION, FLEET, "prepare", INSTANCE, null);

        assertEquals(started.plusSeconds(172800), member.getLaunchLifecycleActions().get("prepare").deadline());
        service.settleLaunchLifecycleActions(group, started.plusSeconds(172801));
        assertEquals("Terminating", member.getLifecycleState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-hook", "wrong-instance", "wrong-token", "missing-selector"})
    void invalidCompletionDoesNotReleaseTheWaitingHost(String scenario) {
        hook(REGION, "prepare", 300, "ABANDON");
        reconciler.reconcile(group);
        String hookName = "wrong-hook".equals(scenario) ? "other" : "prepare";
        String instance = "wrong-instance".equals(scenario) ? "i-11111111111111111" : INSTANCE;
        String token = "wrong-token".equals(scenario) ? UUID.randomUUID().toString() : null;
        if ("missing-selector".equals(scenario)) {
            instance = null;
        }
        String selectedInstance = instance;

        assertThrows(AwsException.class, () -> service.completeLifecycleAction(
                REGION, FLEET, hookName, selectedInstance, "CONTINUE", token));

        assertEquals("Pending:Wait", member.getLifecycleState());
        assertNull(member.getLaunchLifecycleActions().get("prepare").result());
    }

    @Test
    void completedActionCannotBeCompletedOrHeartbeatedAgain() {
        hook(REGION, "prepare", 300, "ABANDON");
        reconciler.reconcile(group);
        service.completeLifecycleAction(REGION, FLEET, "prepare", INSTANCE, "CONTINUE", null);

        assertThrows(AwsException.class, () -> service.completeLifecycleAction(
                REGION, FLEET, "prepare", INSTANCE, "ABANDON", null));
        assertThrows(AwsException.class, () -> service.recordLifecycleActionHeartbeat(
                REGION, FLEET, "prepare", INSTANCE, null));
        assertEquals("Pending:Proceed", member.getLifecycleState());
    }

    @Test
    void deletingAHookAbandonsOnlyItsOwnRegionAndKeepsOtherHooks() {
        hook(REGION, "prepare", 300, "CONTINUE");
        hook(REGION, "other", 300, "CONTINUE");
        service.beginLaunchLifecycleActions(group, member, Instant.now());
        AutoScalingGroup western = group("us-west-2");
        hook("us-west-2", "prepare", 300, "CONTINUE");
        service.beginLaunchLifecycleActions(western, western.getInstances().getFirst(), Instant.now());

        service.deleteLifecycleHookByName(REGION, "prepare");

        assertEquals("Terminating", member.getLifecycleState());
        assertEquals("Pending:Wait", western.getInstances().getFirst().getLifecycleState());
        assertEquals(List.of("other"), service.describeLifecycleHooks(REGION, FLEET, null)
                .stream().map(LifecycleHook::getLifecycleHookName).toList());
        assertEquals(1, service.describeLifecycleHooks("us-west-2", FLEET, null).size());
        service.deleteLifecycleHookByName(null, "prepare");
        assertEquals("Terminating", western.getInstances().getFirst().getLifecycleState());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 29, 7201})
    void invalidHookTimeoutDoesNotCreateAStoredHook(int seconds) {
        assertThrows(AwsException.class, () -> hook(REGION, "invalid", seconds, "ABANDON"));
        assertTrue(service.describeLifecycleHooks(REGION, FLEET, null).isEmpty());
    }

    @Test
    void invalidTransitionDoesNotCreateAStoredHook() {
        assertThrows(AwsException.class, () -> service.putLifecycleHook(
                REGION, FLEET, "invalid", "invalid", null, null, null, 300, "ABANDON"));
        assertTrue(service.describeLifecycleHooks(REGION, FLEET, null).isEmpty());
    }

    @Test
    void scaleInCanRemoveAWaitingHostWithoutEverRegisteringIt() {
        hook(REGION, "prepare", 300, "ABANDON");
        reconciler.reconcile(group);
        group.setDesiredCapacity(0);

        reconciler.reconcile(group);

        verify(ec2).terminateInstances(REGION, List.of(INSTANCE));
        verify(targets, never()).registerTargets(anyString(), anyString(), anyList());
        verify(classic, never()).registerInstances(anyString(), anyString(), anyList());
    }

    @Test
    void storedWaitingActionsRetainTheirTokensAndDeadlinesAcrossReload() throws Exception {
        hook(REGION, "prepare", 300, "ABANDON");
        reconciler.reconcile(group);
        LaunchLifecycleAction action = member.getLaunchLifecycleActions().get("prepare");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AutoScalingGroup restored = mapper.readValue(mapper.writeValueAsBytes(group), AutoScalingGroup.class);
        service.groups.put(REGION + "::" + FLEET, restored);

        assertEquals(action, restored.getInstances().getFirst().getLaunchLifecycleActions().get("prepare"));
        service.completeLifecycleAction(REGION, FLEET, "prepare", null, "CONTINUE", action.token());
        assertEquals("Pending:Proceed", restored.getInstances().getFirst().getLifecycleState());
    }

    private void hook(String region, String name, int timeout, String defaultResult) {
        service.putLifecycleHook(region, FLEET, name, "autoscaling:EC2_INSTANCE_LAUNCHING",
                null, null, null, timeout, defaultResult);
    }

    private AutoScalingGroup group(String region) {
        AutoScalingGroup result = new AutoScalingGroup();
        result.setAutoScalingGroupName(FLEET);
        result.setRegion(region);
        result.setDesiredCapacity(1);
        result.setMinSize(0);
        result.setMaxSize(2);
        result.setTargetGroupARNs(List.of("target-group"));
        result.setLoadBalancerNames(List.of("classic"));
        AsgInstance instance = new AsgInstance();
        instance.setInstanceId(INSTANCE);
        instance.setLifecycleState("Pending");
        instance.setHealthStatus("Healthy");
        result.setInstances(new ArrayList<>(List.of(instance)));
        service.groups.put(region + "::" + FLEET, result);
        return result;
    }
}
