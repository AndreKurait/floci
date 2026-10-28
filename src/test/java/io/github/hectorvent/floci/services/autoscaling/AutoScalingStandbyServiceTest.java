package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.InstanceRefresh;
import io.github.hectorvent.floci.services.autoscaling.model.InstanceRefreshReplacement;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingActivity;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.restassured.path.xml.XmlPath;
import jakarta.enterprise.event.Event;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoScalingStandbyServiceTest {
    private static final String REGION = "us-east-1";
    private static final String GROUP = "standby-group";
    private static final String INSTANCE = "i-0123456789abcdef0";
    private static final String OTHER = "i-0123456789abcdef1";
    private AutoScalingService service;
    private AutoScalingQueryHandler handler;
    private AutoScalingReconciler reconciler;
    private AutoScalingGroup group;
    private Ec2Service ec2;
    private ElbV2Service elb;
    private ElbClassicService classic;
    private Event<AutoScalingGroupChanged> changes;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        elb = mock(ElbV2Service.class);
        classic = mock(ElbClassicService.class);
        changes = mock(Event.class);
        service = new AutoScalingService(changes, elb);
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        group = service.createAutoScalingGroup(REGION, GROUP, null, "lt-original", null, "1", null,
                0, 4, 2, 300, List.of(REGION + "a"), List.of(), List.of("target-group"),
                List.of("classic-lb"), "EC2", 0, List.of("Default"), Map.of(), Map.of(),
                AsgOptionalFields.none());
        for (String id : List.of(INSTANCE, OTHER)) {
            AsgInstance instance = new AsgInstance();
            instance.setInstanceId(id);
            instance.setAvailabilityZone(REGION + "a");
            instance.setInstanceType("t3.micro");
            instance.setLifecycleState("InService");
            instance.setHealthStatus("Healthy");
            group.getInstances().add(instance);
        }
        ec2 = mock(Ec2Service.class);
        service.ec2Service = ec2;
        when(ec2.isInstanceContainerRunning(anyString())).thenReturn(true);
        when(ec2.describeInstances(eq(REGION), anyList(), isNull())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(1);
            Reservation reservation = new Reservation();
            reservation.setInstances(ids.stream().map(id -> {
                Instance instance = new Instance();
                instance.setInstanceId(id);
                instance.setState(new InstanceState(16, "running"));
                return instance;
            }).toList());
            return List.of(reservation);
        });
        handler = new AutoScalingQueryHandler(service);
        reconciler = new AutoScalingReconciler(service, ec2, elb, classic, null);
    }

    @AfterEach
    void tearDown() {
        reconciler.stop();
    }

    @Test
    void instanceListingIncludesItsOwningGroup() {
        XmlPath result = xml(call("DescribeAutoScalingInstances", List.of(INSTANCE), Map.of()), 200);
        assertEquals(GROUP, result.getString("DescribeAutoScalingInstancesResponse"
                + ".DescribeAutoScalingInstancesResult.AutoScalingInstances.member.AutoScalingGroupName"));
    }

    @ParameterizedTest
    @CsvSource({"Healthy,HEALTHY", "Unhealthy,UNHEALTHY"})
    void instanceListingUsesUppercaseHealthWithoutChangingGroupHealth(String stored, String listed) {
        service.setInstanceHealth(REGION, INSTANCE, stored, false);
        XmlPath result = xml(call("DescribeAutoScalingInstances", List.of(INSTANCE), Map.of()), 200);
        assertEquals(listed, result.getString("DescribeAutoScalingInstancesResponse"
                + ".DescribeAutoScalingInstancesResult.AutoScalingInstances.member.HealthStatus"));
        assertEquals(stored, member().getHealthStatus());
        XmlPath described = xml(call("DescribeAutoScalingGroups", List.of(), Map.of()), 200);
        assertEquals(stored, described.getString("DescribeAutoScalingGroupsResponse"
                + ".DescribeAutoScalingGroupsResult.AutoScalingGroups.member.Instances.member"
                + ".find { it.InstanceId == '" + INSTANCE + "' }.HealthStatus"));
    }

    @Test
    void standbyRoundTripTracksActivitiesCapacityAndBothLoadBalancerKinds() {
        XmlPath entered = xml(call("EnterStandby", List.of(INSTANCE),
                Map.of("ShouldDecrementDesiredCapacity", "true")), 200);
        String enterId = entered.getString("EnterStandbyResponse.EnterStandbyResult.Activities.member.ActivityId");
        assertEquals("InProgress", entered.getString(
                "EnterStandbyResponse.EnterStandbyResult.Activities.member.StatusCode"));
        assertEquals(50, entered.getInt("EnterStandbyResponse.EnterStandbyResult.Activities.member.Progress"));
        assertEquals("EnteringStandby", member().getLifecycleState());
        assertEquals(1, group.getDesiredCapacity());

        reconciler.reconcile(group);

        assertEquals("Standby", member().getLifecycleState());
        assertEquals(2, group.getInstances().size());
        verify(elb).deregisterTargets(eq(REGION), eq("target-group"),
                argThat(targets -> targets.size() == 1 && INSTANCE.equals(targets.getFirst().getId())));
        verify(classic).deregisterInstances(REGION, "classic-lb", List.of(INSTANCE));
        assertCompleted(enterId);

        when(ec2.isInstanceContainerRunning(INSTANCE)).thenReturn(false);
        clearInvocations(ec2);
        reconciler.reconcile(group);
        assertEquals("Standby", member().getLifecycleState());
        verify(ec2, never()).isInstanceContainerRunning(INSTANCE);
        verify(ec2, never()).terminateInstances(anyString(), anyList());

        XmlPath exited = xml(call("ExitStandby", List.of(INSTANCE), Map.of()), 200);
        String exitId = exited.getString("ExitStandbyResponse.ExitStandbyResult.Activities.member.ActivityId");
        assertNotEquals(enterId, exitId);
        assertEquals("PreInService", exited.getString(
                "ExitStandbyResponse.ExitStandbyResult.Activities.member.StatusCode"));
        assertEquals(30, exited.getInt("ExitStandbyResponse.ExitStandbyResult.Activities.member.Progress"));
        assertEquals(2, group.getDesiredCapacity());
        when(ec2.isInstanceContainerRunning(INSTANCE)).thenReturn(true);

        reconciler.reconcile(group);

        assertEquals("InService", member().getLifecycleState());
        verify(elb).registerTargets(eq(REGION), eq("target-group"),
                argThat(targets -> targets.size() == 1 && INSTANCE.equals(targets.getFirst().getId())));
        verify(classic).registerInstances(REGION, "classic-lb", List.of(INSTANCE));
        assertCompleted(exitId);
    }

    @Test
    void activityResponsesRetainTheirInitialStateAfterReconciliation() {
        doAnswer(invocation -> {
            reconciler.reconcile(group);
            return null;
        }).when(changes).fire(any(AutoScalingGroupChanged.class));
        XmlPath entering = xml(call("EnterStandby", List.of(INSTANCE),
                Map.of("ShouldDecrementDesiredCapacity", "true")), 200);
        String enterPath = "EnterStandbyResponse.EnterStandbyResult.Activities.member";
        assertCompleted(entering.getString(enterPath + ".ActivityId"));
        assertEquals("InProgress", entering.getString(enterPath + ".StatusCode"));
        assertEquals(50, entering.getInt(enterPath + ".Progress"));
        assertTrue(entering.getString(enterPath + ".EndTime").isEmpty());

        XmlPath exiting = xml(call("ExitStandby", List.of(INSTANCE), Map.of()), 200);
        String exitPath = "ExitStandbyResponse.ExitStandbyResult.Activities.member";
        assertCompleted(exiting.getString(exitPath + ".ActivityId"));
        assertEquals("PreInService", exiting.getString(exitPath + ".StatusCode"));
        assertEquals(30, exiting.getInt(exitPath + ".Progress"));
        assertTrue(exiting.getString(exitPath + ".EndTime").isEmpty());
    }

    @Test
    void sparseInstanceIdsMoveEveryRequestedMemberOnce() {
        XmlPath entered = xml(call("EnterStandby", List.of(INSTANCE),
                Map.of("ShouldDecrementDesiredCapacity", "true", "InstanceIds.member.3", OTHER)), 200);
        assertEquals(2, entered.getList("EnterStandbyResponse.EnterStandbyResult.Activities.member").size());
        assertEquals(0, group.getDesiredCapacity());
        reconciler.reconcile(group);
        assertTrue(group.getInstances().stream().allMatch(instance -> "Standby".equals(instance.getLifecycleState())));

        XmlPath exited = xml(call("ExitStandby", List.of(INSTANCE), Map.of("InstanceIds.member.3", OTHER)), 200);
        assertEquals(2, exited.getList("ExitStandbyResponse.ExitStandbyResult.Activities.member").size());
        assertEquals(2, group.getDesiredCapacity());
    }

    @Test
    void multiInstanceActivitiesFollowNumericRequestOrder() {
        List<String> ids = IntStream.rangeClosed(1, 12).mapToObj(index -> "i-%017d".formatted(index)).toList();
        group.getInstances().clear();
        group.setMaxSize(ids.size());
        group.setDesiredCapacity(ids.size());
        for (String id : ids) {
            AsgInstance instance = new AsgInstance();
            instance.setInstanceId(id);
            instance.setLifecycleState("InService");
            instance.setHealthStatus("Healthy");
            group.getInstances().add(instance);
        }

        XmlPath entering = xml(call("EnterStandby", ids, Map.of("ShouldDecrementDesiredCapacity", "true")), 200);
        assertEquals(ids.stream().map(id -> "Moving EC2 instance to Standby: " + id).toList(),
                entering.getList("EnterStandbyResponse.EnterStandbyResult.Activities.member.Description"));
        reconciler.reconcile(group);
        XmlPath exiting = xml(call("ExitStandby", ids, Map.of()), 200);
        assertEquals(ids.stream().map(id -> "Moving EC2 instance out of Standby: " + id).toList(),
                exiting.getList("ExitStandbyResponse.ExitStandbyResult.Activities.member.Description"));
    }

    @Test
    void explicitHealthStatusIsNotOverwrittenWhenReturningFromStandby() {
        xml(call("EnterStandby", List.of(INSTANCE), Map.of("ShouldDecrementDesiredCapacity", "true")), 200);
        reconciler.reconcile(group);
        service.setInstanceHealth(REGION, INSTANCE, "Unhealthy", false);
        xml(call("ExitStandby", List.of(INSTANCE), Map.of()), 200);
        reconciler.reconcile(group);
        assertEquals("InService", member().getLifecycleState());
        assertEquals("Unhealthy", member().getHealthStatus());
        XmlPath listed = xml(call("DescribeAutoScalingInstances", List.of(INSTANCE), Map.of()), 200);
        assertEquals("UNHEALTHY", listed.getString("DescribeAutoScalingInstancesResponse"
                + ".DescribeAutoScalingInstancesResult.AutoScalingInstances.member.HealthStatus"));
    }

    @Test
    void standbyNameAndBatchErrorsUseSharedModelValidation() {
        MultivaluedHashMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.putSingle("ShouldDecrementDesiredCapacity", "true");
        XmlPath missingName = xml(handler.handle("EnterStandby", parameters, REGION), 400);
        assertEquals(SmithyValidationErrors.message(List.of(SmithyValidationErrors.notNull("autoScalingGroupName"))),
                missingName.getString("ErrorResponse.Error.Message"));
        for (String invalidName : List.of("", "x".repeat(256))) {
            XmlPath result = xml(call("EnterStandby", List.of(INSTANCE),
                    Map.of("AutoScalingGroupName", invalidName, "ShouldDecrementDesiredCapacity", "true")), 400);
            assertEquals(SmithyValidationErrors.message(List.of(
                            SmithyValidationErrors.lengthOutOfRange("autoScalingGroupName", invalidName, 1, 255))),
                    result.getString("ErrorResponse.Error.Message"));
        }
        List<String> ids = IntStream.range(0, 21).mapToObj(index -> "i-%017d".formatted(index)).toList();
        XmlPath tooMany = xml(call("EnterStandby", ids, Map.of("ShouldDecrementDesiredCapacity", "true")), 400);
        assertEquals(SmithyValidationErrors.message(List.of(
                        SmithyValidationErrors.sizeOutOfRange("instanceIds", 21, 0, 20))),
                tooMany.getString("ErrorResponse.Error.Message"));
    }

    @Test
    void standbyMembershipAndLifecycleErrorsUseTheEstablishedAwsShape() {
        String missing = "i-00000000000000000";
        XmlPath unknown = xml(call("EnterStandby", List.of(missing),
                Map.of("ShouldDecrementDesiredCapacity", "true")), 400);
        assertEquals("The instance " + missing + " is not part of Auto Scaling group " + GROUP + ".",
                unknown.getString("ErrorResponse.Error.Message"));
        XmlPath notStandby = xml(call("ExitStandby", List.of(INSTANCE), Map.of()), 400);
        assertEquals("The instance " + INSTANCE + " is not in Standby.",
                notStandby.getString("ErrorResponse.Error.Message"));
        member().setLifecycleState("Standby");
        XmlPath notInService = xml(call("EnterStandby", List.of(INSTANCE),
                Map.of("ShouldDecrementDesiredCapacity", "true")), 400);
        assertEquals("The instance " + INSTANCE + " is not in InService.",
                notInService.getString("ErrorResponse.Error.Message"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void standbyCapacityErrorsUseTheSharedGroupSizeMessage(boolean entering) {
        if (entering) {
            group.setMinSize(2);
        } else {
            group.setMaxSize(2);
            member().setLifecycleState("Standby");
        }
        XmlPath response = xml(call(entering ? "EnterStandby" : "ExitStandby", List.of(INSTANCE),
                Map.of("ShouldDecrementDesiredCapacity", "true")), 400);
        assertEquals("AutoScalingGroup " + GROUP + " has min-size=" + group.getMinSize()
                        + ", max-size=" + group.getMaxSize() + ", and desired-size=2. To "
                        + (entering ? "place into standby" : "place in service")
                        + " 1 instance, please update the AutoScalingGroup sizes appropriately.",
                response.getString("ErrorResponse.Error.Message"));
    }

    @Test
    void exitStandbyRequiresLaunchToBeEnabled() {
        beginTransition("EnterStandby");
        reconciler.reconcile(group);
        int activitiesBefore = service.describeScalingActivities(REGION, GROUP).size();
        service.suspendProcesses(REGION, GROUP, List.of("Launch"));

        XmlPath response = xml(call("ExitStandby", List.of(INSTANCE), Map.of()), 400);

        assertEquals("ValidationError", response.getString("ErrorResponse.Error.Code"));
        assertEquals("Standby", member().getLifecycleState());
        assertEquals(1, group.getDesiredCapacity());
        assertEquals(activitiesBefore, service.describeScalingActivities(REGION, GROUP).size());
        verify(elb, never()).registerTargets(anyString(), anyString(), anyList());
        verify(classic, never()).registerInstances(anyString(), anyString(), anyList());

        service.resumeProcesses(REGION, GROUP, List.of("Launch"));
        xml(call("ExitStandby", List.of(INSTANCE), Map.of()), 200);
        reconciler.reconcile(group);
        assertEquals("InService", member().getLifecycleState());
        assertEquals(2, group.getDesiredCapacity());
    }

    @Test
    void returnUsesEachTargetGroupsOwnDefaultPort() {
        group.setTargetGroupARNs(List.of("target-group", "other-target-group"));
        member().setLifecycleState("Standby");
        group.setDesiredCapacity(1);
        doAnswer(invocation -> {
            List<TargetDescription> targets = invocation.getArgument(2);
            assertNull(targets.getFirst().getPort());
            targets.getFirst().setPort(80);
            return null;
        }).when(elb).registerTargets(eq(REGION), eq("target-group"), anyList());
        doAnswer(invocation -> {
            List<TargetDescription> targets = invocation.getArgument(2);
            assertNull(targets.getFirst().getPort(), "another target group's default port must not leak");
            targets.getFirst().setPort(8080);
            return null;
        }).when(elb).registerTargets(eq(REGION), eq("other-target-group"), anyList());

        xml(call("ExitStandby", List.of(INSTANCE), Map.of()), 200);
        reconciler.reconcile(group);

        assertEquals("InService", member().getLifecycleState());
        verify(elb).registerTargets(eq(REGION), eq("other-target-group"), anyList());
    }

    @Test
    void batchValidationPreventsPartialStateAndCapacityChanges() {
        assertValidation(call("EnterStandby", List.of(INSTANCE, "i-00000000000000000"),
                Map.of("ShouldDecrementDesiredCapacity", "true")));
        assertEquals("InService", member().getLifecycleState());
        assertEquals(2, group.getDesiredCapacity());
        assertTrue(service.describeScalingActivities(REGION, GROUP).isEmpty());
        verifyNoInteractions(elb, classic);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pending", "Standby", "Terminating", "Detached"})
    void enterRejectsAnInstanceOutsideInService(String state) {
        member().setLifecycleState(state);
        assertValidation(call("EnterStandby", List.of(INSTANCE),
                Map.of("ShouldDecrementDesiredCapacity", "true")));
        assertEquals(state, member().getLifecycleState());
        assertEquals(2, group.getDesiredCapacity());
        verifyNoInteractions(elb, classic);
    }

    @Test
    void exitRejectsAnInstanceOutsideStandby() {
        assertValidation(call("ExitStandby", List.of(INSTANCE), Map.of()));
        assertEquals("InService", member().getLifecycleState());
        assertEquals(2, group.getDesiredCapacity());
        verifyNoInteractions(elb, classic);
    }

    @Test
    void enterRejectsCapacityBelowMinimum() {
        group.setMinSize(2);
        assertValidation(call("EnterStandby", List.of(INSTANCE),
                Map.of("ShouldDecrementDesiredCapacity", "true")));
        assertEquals("InService", member().getLifecycleState());
        assertEquals(2, group.getDesiredCapacity());
        verifyNoInteractions(elb, classic);
    }

    @Test
    void exitRejectsCapacityAboveMaximum() {
        member().setLifecycleState("Standby");
        group.setMaxSize(2);
        assertValidation(call("ExitStandby", List.of(INSTANCE), Map.of()));
        assertEquals("Standby", member().getLifecycleState());
        assertEquals(2, group.getDesiredCapacity());
        verifyNoInteractions(elb, classic);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EnterStandby", "ExitStandby"})
    void standbyRequiresGroupMembershipAndLimitsTheBatch(String action) {
        assertValidation(call(action, List.of(INSTANCE),
                Map.of("AutoScalingGroupName", "missing-group", "ShouldDecrementDesiredCapacity", "true")));
        List<String> tooMany = IntStream.range(0, 21).mapToObj(n -> "i-%017d".formatted(n)).toList();
        assertValidation(call(action, tooMany, Map.of("ShouldDecrementDesiredCapacity", "true")));
        assertEquals(2, group.getDesiredCapacity());
        assertTrue(service.describeScalingActivities(REGION, GROUP).isEmpty());
    }

    @Test
    void enterRequiresAnExplicitValidCapacityFlag() {
        assertValidation(call("EnterStandby", List.of(INSTANCE), Map.of()));
        assertValidation(call("EnterStandby", List.of(INSTANCE),
                Map.of("ShouldDecrementDesiredCapacity", "invalid")));
        assertEquals("InService", member().getLifecycleState());
    }

    @Test
    void failedDeregistrationDoesNotFinishStandbyAndIsRetried() {
        xml(call("EnterStandby", List.of(INSTANCE), Map.of("ShouldDecrementDesiredCapacity", "true")), 200);
        doThrow(new AwsException("InternalFailure", "retry deregistration", 500))
                .doNothing().when(elb).deregisterTargets(eq(REGION), eq("target-group"), anyList());

        reconciler.reconcile(group);
        assertEquals("EnteringStandby", member().getLifecycleState());
        assertEquals("InProgress", service.describeScalingActivities(REGION, GROUP).getFirst().getStatusCode());

        reconciler.reconcile(group);
        assertEquals("Standby", member().getLifecycleState());
        assertEquals(1, group.getDesiredCapacity());
        verify(elb, times(2)).deregisterTargets(eq(REGION), eq("target-group"), anyList());
    }

    @Test
    void failedRegistrationDoesNotMarkInstanceInServiceAndIsRetried() {
        member().setLifecycleState("Standby");
        group.setDesiredCapacity(1);
        xml(call("ExitStandby", List.of(INSTANCE), Map.of()), 200);
        doThrow(new AwsException("InternalFailure", "retry registration", 500))
                .doNothing().when(elb).registerTargets(eq(REGION), eq("target-group"), anyList());

        reconciler.reconcile(group);
        assertEquals("Pending", member().getLifecycleState());
        assertEquals("PreInService", service.describeScalingActivities(REGION, GROUP).getFirst().getStatusCode());

        reconciler.reconcile(group);
        assertEquals("InService", member().getLifecycleState());
        assertEquals(2, group.getDesiredCapacity());
        verify(elb, times(2)).registerTargets(eq(REGION), eq("target-group"), anyList());
    }

    @Test
    void deletedLoadBalancersDoNotBlockTheRemainingRegistrations() {
        group.setTargetGroupARNs(List.of("deleted-target-group", "target-group"));
        group.setLoadBalancerNames(List.of("deleted-lb", "classic-lb"));
        doThrow(new AwsException("TargetGroupNotFound", "deleted", 400)).when(elb)
                .deregisterTargets(eq(REGION), eq("deleted-target-group"), anyList());
        doThrow(new AwsException("TargetGroupNotFound", "deleted", 400)).when(elb)
                .registerTargets(eq(REGION), eq("deleted-target-group"), anyList());
        doThrow(new AwsException("LoadBalancerNotFound", "deleted", 400)).when(classic)
                .deregisterInstances(REGION, "deleted-lb", List.of(INSTANCE));
        doThrow(new AwsException("LoadBalancerNotFound", "deleted", 400)).when(classic)
                .registerInstances(REGION, "deleted-lb", List.of(INSTANCE));

        xml(call("EnterStandby", List.of(INSTANCE), Map.of("ShouldDecrementDesiredCapacity", "true")), 200);
        reconciler.reconcile(group);
        assertEquals("Standby", member().getLifecycleState());
        verify(classic).deregisterInstances(REGION, "classic-lb", List.of(INSTANCE));

        xml(call("ExitStandby", List.of(INSTANCE), Map.of()), 200);
        reconciler.reconcile(group);
        assertEquals("InService", member().getLifecycleState());
        verify(elb).registerTargets(eq(REGION), eq("target-group"), anyList());
        verify(classic).registerInstances(REGION, "classic-lb", List.of(INSTANCE));
        assertTrue(service.describeScalingActivities(REGION, GROUP).stream()
                .allMatch(activity -> "Successful".equals(activity.getStatusCode())));
    }

    @ParameterizedTest
    @CsvSource({"EnterStandby,terminate", "ExitStandby,terminate", "EnterStandby,delete", "ExitStandby,delete",
            "ExitStandby,refresh"})
    void supersedingWorkCompletesThePendingActivity(String action, String superseding) {
        String activityId = beginTransition(action);
        switch (superseding) {
            case "terminate" -> service.terminateInstanceInAutoScalingGroup(REGION, INSTANCE, false);
            case "delete" -> service.deleteAutoScalingGroup(REGION, GROUP, true);
            case "refresh" -> service.startInstanceRefresh(REGION, GROUP, new InstanceRefresh());
            default -> fail("Unknown test action: " + superseding);
        }
        ScalingActivity activity = activity(activityId);
        assertEquals("Cancelled", activity.getStatusCode());
        assertNotNull(activity.getEndTime());
        assertNull(member().getStandbyActivityId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EnterStandby", "ExitStandby"})
    void detachmentRejectsTheTransitionWithoutLosingItsActivity(String action) {
        String activityId = beginTransition(action);
        AwsException error = assertThrows(AwsException.class,
                () -> service.detachInstances(REGION, GROUP, List.of(INSTANCE), false));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(activityId, member().getStandbyActivityId());
        assertNull(activity(activityId).getEndTime());
    }

    @Test
    void detachingAStandbyInstanceHonorsAnExplicitDesiredCapacityDecrement() {
        member().setLifecycleState("Standby");
        group.setDesiredCapacity(1);
        service.detachInstances(REGION, GROUP, List.of(INSTANCE), true);
        assertEquals(0, group.getDesiredCapacity());
        assertEquals(List.of(OTHER), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"stopping", "stopped"})
    void failedReturnAfterSuspensionRetainsMembershipUntilTerminationSucceeds(String state) {
        Instance stopped = new Instance();
        stopped.setInstanceId(INSTANCE);
        stopped.setState(new InstanceState(80, state));
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(stopped));
        when(ec2.describeInstances(REGION, List.of(INSTANCE), null)).thenReturn(List.of(reservation));
        when(ec2.terminateInstances(REGION, List.of(INSTANCE)))
                .thenThrow(new AwsException("InternalFailure", "retry termination", 500)).thenReturn(List.of());
        String activityId = beginTransition("ExitStandby");
        service.suspendProcesses(REGION, GROUP, List.of("Launch", "Terminate"));

        reconciler.reconcile(group);
        assertEquals("Pending", member().getLifecycleState());
        assertEquals("PreInService", activity(activityId).getStatusCode());

        reconciler.reconcile(group);
        assertTrue(group.getInstances().stream().noneMatch(instance -> INSTANCE.equals(instance.getInstanceId())));
        assertEquals("Failed", activity(activityId).getStatusCode());
        assertNotNull(activity(activityId).getEndTime());
        verify(ec2, times(2)).terminateInstances(REGION, List.of(INSTANCE));
    }

    @Test
    void standbySettlesAnAlreadyInServiceRefreshReplacementBeforeChangingItsState() {
        InstanceRefresh refresh = new InstanceRefresh();
        refresh.setInstanceRefreshId("recovered-refresh");
        refresh.setRegion(REGION);
        refresh.setAutoScalingGroupName(GROUP);
        refresh.setStatus("InProgress");
        InstanceRefreshReplacement replacement = new InstanceRefreshReplacement();
        replacement.setOriginalInstanceId("i-old");
        replacement.setReplacementInstanceId(INSTANCE);
        replacement.setPhase("Warming");
        refresh.setReplacements(List.of(replacement));
        service.saveInstanceRefresh(refresh);

        xml(call("EnterStandby", List.of(INSTANCE), Map.of("ShouldDecrementDesiredCapacity", "true")), 200);

        assertEquals("EnteringStandby", member().getLifecycleState());
        assertEquals("Complete", replacement.getPhase());
        assertEquals("Successful", refresh.getStatus());
        assertEquals(0, refresh.getInstancesToUpdate());
    }

    private String beginTransition(String action) {
        if ("ExitStandby".equals(action)) {
            member().setLifecycleState("Standby");
            group.setDesiredCapacity(1);
        }
        XmlPath response = xml(call(action, List.of(INSTANCE),
                Map.of("ShouldDecrementDesiredCapacity", "true")), 200);
        return response.getString(action + "Response." + action + "Result.Activities.member.ActivityId");
    }

    private AsgInstance member() {
        return group.getInstances().stream().filter(instance -> INSTANCE.equals(instance.getInstanceId()))
                .findFirst().orElseThrow();
    }

    private Response call(String action, List<String> ids, Map<String, String> extra) {
        MultivaluedHashMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.putSingle("AutoScalingGroupName", GROUP);
        for (int index = 0; index < ids.size(); index++) {
            parameters.putSingle("InstanceIds.member." + (index + 1), ids.get(index));
        }
        extra.forEach(parameters::putSingle);
        return handler.handle(action, parameters, REGION);
    }

    private static XmlPath xml(Response response, int expectedStatus) {
        assertEquals(expectedStatus, response.getStatus(), String.valueOf(response.getEntity()));
        return XmlPath.from((String) response.getEntity());
    }

    private static void assertValidation(Response response) {
        assertEquals("ValidationError", xml(response, 400).getString("ErrorResponse.Error.Code"));
    }

    private void assertCompleted(String id) {
        ScalingActivity activity = activity(id);
        assertEquals("Successful", activity.getStatusCode());
        assertEquals(100, activity.getProgress());
        assertNotNull(activity.getEndTime());
    }

    private ScalingActivity activity(String id) {
        return service.describeScalingActivities(REGION, GROUP).stream()
                .filter(item -> id.equals(item.getActivityId())).findFirst().orElseThrow();
    }
}
