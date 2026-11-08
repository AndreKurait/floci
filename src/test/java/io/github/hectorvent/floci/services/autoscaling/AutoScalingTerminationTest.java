package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingActivity;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoScalingTerminationTest {
    private static final String REGION = "us-east-1";
    private static final String GROUP = "termination";
    private static final String INSTANCE = "i-selected";
    private AutoScalingService service;
    private AutoScalingReconciler reconciler;
    private AutoScalingGroup group;
    private Ec2Service ec2;
    private ElbClassicService classic;

    @BeforeEach
    void setUp() {
        classic = mock(ElbClassicService.class);
        ElbV2Service elb = mock(ElbV2Service.class);
        service = new AutoScalingService(elb, classic);
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.createAutoScalingGroup(REGION, GROUP, null, "lt-test", null, "1", null,
                0, 3, 1, 300, List.of(REGION + "a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
        group = service.describeAutoScalingGroups(REGION, List.of(GROUP)).getFirst();
        AsgInstance member = new AsgInstance();
        member.setInstanceId(INSTANCE);
        member.setLifecycleState("InService");
        member.setHealthStatus("Healthy");
        group.getInstances().add(member);
        service.saveAutoScalingGroupIfPresent(group);
        ec2 = mock(Ec2Service.class);
        service.ec2Service = ec2;
        reconciler = new AutoScalingReconciler(service, ec2, elb, classic, null);
        when(ec2.isInstanceContainerRunning(INSTANCE)).thenReturn(true);
        Instance instance = new Instance();
        instance.setInstanceId(INSTANCE);
        instance.setState(InstanceState.running());
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(instance));
        when(ec2.describeInstances(REGION, List.of(INSTANCE), null)).thenReturn(List.of(reservation));
    }

    @AfterEach
    void stop() {
        reconciler.stop();
    }

    @Test
    void returnedSnapshotKeepsItsIdentityWhenReconciliationCompletes() {
        ScalingActivity response = service.terminateInstanceInAutoScalingGroup(REGION, INSTANCE, true);
        assertEquals("InProgress", response.getStatusCode());
        assertNull(response.getEndTime());
        assertEquals(response.getActivityId(), group.getInstances().getFirst().getTerminationActivityId());
        reconciler.reconcile(group);
        ScalingActivity completed = activity(response.getActivityId());
        assertEquals("Successful", completed.getStatusCode());
        assertEquals(100, completed.getProgress());
        assertNotNull(completed.getEndTime());
        assertEquals("InProgress", response.getStatusCode());
        assertEquals(1, service.describeScalingActivities(REGION, GROUP).size());
        assertTrue(group.getInstances().isEmpty());
        verify(ec2).terminateInstances(REGION, List.of(INSTANCE));
    }

    @Test
    void repeatRequestCannotReplaceTheActivityOrDecrementAgain() {
        ScalingActivity response = service.terminateInstanceInAutoScalingGroup(REGION, INSTANCE, true);
        AwsException failure = assertThrows(AwsException.class,
                () -> service.terminateInstanceInAutoScalingGroup(REGION, INSTANCE, true));
        assertEquals("ScalingActivityInProgress", failure.getErrorCode());
        assertEquals(response.getActivityId(), group.getInstances().getFirst().getTerminationActivityId());
        assertEquals(0, group.getDesiredCapacity());
        assertEquals(1, service.describeScalingActivities(REGION, GROUP).size());
    }

    @Test
    void activityWaitsWhileTheClassicLoadBalancerDrains() {
        group.setLoadBalancerNames(List.of("draining"));
        when(classic.deregisteringInstances(REGION, "draining")).thenReturn(Set.of(INSTANCE), Set.of());
        ScalingActivity response = service.terminateInstanceInAutoScalingGroup(REGION, INSTANCE, true);
        reconciler.reconcile(group);
        assertEquals("InProgress", activity(response.getActivityId()).getStatusCode());
        assertNull(activity(response.getActivityId()).getEndTime());
        verify(ec2, never()).terminateInstances(REGION, List.of(INSTANCE));
        reconciler.reconcile(group);
        assertEquals("Successful", activity(response.getActivityId()).getStatusCode());
        verify(ec2).terminateInstances(REGION, List.of(INSTANCE));
    }

    @Test
    void failedTerminationKeepsItsFailureWhenARepairPassLaterSucceeds() {
        ScalingActivity response = service.terminateInstanceInAutoScalingGroup(REGION, INSTANCE, true);
        when(ec2.terminateInstances(REGION, List.of(INSTANCE)))
                .thenThrow(new IllegalStateException("termination refused")).thenReturn(List.of());
        reconciler.reconcile(group);
        ScalingActivity failed = activity(response.getActivityId());
        assertEquals("Failed", failed.getStatusCode());
        assertEquals("termination refused", failed.getStatusMessage());
        assertNotNull(failed.getEndTime());
        assertEquals(1, group.getInstances().size());
        reconciler.reconcile(group);
        assertTrue(group.getInstances().isEmpty());
        assertEquals("Failed", activity(response.getActivityId()).getStatusCode());
        assertEquals(2, service.describeScalingActivities(REGION, GROUP).size());
    }

    @Test
    void alreadyMissingEc2InstanceCompletesTheAcceptedActivity() {
        ScalingActivity response = service.terminateInstanceInAutoScalingGroup(REGION, INSTANCE, true);
        when(ec2.describeInstances(REGION, List.of(INSTANCE), null))
                .thenThrow(new AwsException("InvalidInstanceID.NotFound", "Instance no longer exists", 400));
        reconciler.reconcile(group);
        assertEquals("Successful", activity(response.getActivityId()).getStatusCode());
        assertTrue(group.getInstances().isEmpty());
        assertEquals(1, service.describeScalingActivities(REGION, GROUP).size());
        verify(ec2, never()).terminateInstances(REGION, List.of(INSTANCE));
    }

    @Test
    void groupDeletionCancelsThePendingActivity() {
        ScalingActivity response = service.terminateInstanceInAutoScalingGroup(REGION, INSTANCE, true);
        service.deleteAutoScalingGroup(REGION, GROUP, true);
        assertTrue(service.describeAutoScalingGroups(REGION, List.of(GROUP)).isEmpty());
        ScalingActivity cancelled = activity(response.getActivityId());
        assertEquals("Cancelled", cancelled.getStatusCode());
        assertNotNull(cancelled.getEndTime());
    }

    private ScalingActivity activity(String id) {
        return service.describeScalingActivities(REGION, GROUP).stream()
                .filter(activity -> id.equals(activity.getActivityId())).findFirst().orElseThrow();
    }
}
