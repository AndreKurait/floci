package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchLifecycleAction;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingActivity;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.testing.ManualClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A launch lifecycle action starts, is extended and times out on the clock the service and its reconciler
 * were given, and every activity is stamped from it, so a deadline is never compared with the wall clock.
 */
class AutoScalingTimelineTest {

    private static final String REGION = "us-east-1";
    private static final String FLEET = "timeline-fleet";
    private static final String INSTANCE = "i-0123456789abcdef0";
    private static final String HOOK = "prepare";
    private static final Instant START = Instant.parse("2031-03-04T05:06:07.008Z");
    private static final int HEARTBEAT_TIMEOUT_SECONDS = 300;
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(HEARTBEAT_TIMEOUT_SECONDS);

    private final ManualClock clock = new ManualClock(START);
    private AutoScalingService service;
    private AutoScalingReconciler reconciler;
    private AutoScalingGroup group;
    private AsgInstance member;

    @BeforeEach
    void setUp() {
        Ec2Service ec2 = mock(Ec2Service.class);
        ElbV2Service targets = mock(ElbV2Service.class);
        ElbClassicService classic = mock(ElbClassicService.class);
        service = new AutoScalingService(targets, classic, clock);
        service.regionResolver = mock(RegionResolver.class);
        when(service.regionResolver.getAccountId()).thenReturn("123456789012");
        service.ec2Service = ec2;
        group = group();
        member = group.getInstances().getFirst();
        reconciler = new AutoScalingReconciler(service, ec2, targets, classic, null,
                mock(ScheduledExecutorService.class), clock);
        Instance instance = new Instance();
        instance.setInstanceId(INSTANCE);
        instance.setState(InstanceState.running());
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(instance));
        when(ec2.describeInstances(eq(REGION), eq(List.of(INSTANCE)), isNull()))
                .thenReturn(List.of(reservation));
        when(ec2.isInstanceContainerRunning(INSTANCE)).thenReturn(true);
        service.putLifecycleHook(REGION, FLEET, HOOK, "autoscaling:EC2_INSTANCE_LAUNCHING",
                null, null, null, HEARTBEAT_TIMEOUT_SECONDS, "ABANDON");
    }

    @AfterEach
    void stopWorkers() {
        reconciler.stop();
    }

    @Test
    void launchLifecycleAction_startsOnTheClock_withItsDeadlineOneHeartbeatTimeoutLater() {
        reconciler.reconcile(group);

        LaunchLifecycleAction action = member.getLaunchLifecycleActions().get(HOOK);
        assertEquals("Pending:Wait", member.getLifecycleState());
        assertEquals(START.plus(HEARTBEAT_TIMEOUT), action.deadline());
    }

    @Test
    void launchLifecycleAction_oneMillisecondBeforeItsHeartbeatTimeout_stillWaitsAndCanBeCompleted() {
        reconciler.reconcile(group);

        clock.advance(HEARTBEAT_TIMEOUT.minusMillis(1));
        reconciler.reconcile(group);

        assertEquals("Pending:Wait", member.getLifecycleState());
        assertNull(member.getLaunchLifecycleActions().get(HOOK).result());
        service.completeLifecycleAction(REGION, FLEET, HOOK, INSTANCE, "CONTINUE", null);
        assertEquals("Pending:Proceed", member.getLifecycleState());
    }

    @Test
    void launchLifecycleAction_atItsHeartbeatTimeout_refusesCompletionAndTakesTheDefaultResult() {
        reconciler.reconcile(group);

        clock.advance(HEARTBEAT_TIMEOUT);
        AwsException refused = assertThrows(AwsException.class,
                () -> service.completeLifecycleAction(REGION, FLEET, HOOK, INSTANCE, "CONTINUE", null));
        assertEquals("ValidationError", refused.getErrorCode());
        reconciler.reconcile(group);

        LaunchLifecycleAction action = member.getLaunchLifecycleActions().get(HOOK);
        assertEquals("ABANDON", action.result());
        assertEquals("Heartbeat Timeout", action.reason());
        ScalingActivity failed = service.describeScalingActivities(REGION, FLEET).getFirst();
        assertEquals("Failed", failed.getStatusCode());
        assertEquals(START.plus(HEARTBEAT_TIMEOUT), failed.getStartTime());
    }

    @Test
    void heartbeat_oneMillisecondBeforeTheTimeout_extendsTheDeadlineFromTheClock() {
        reconciler.reconcile(group);

        clock.advance(HEARTBEAT_TIMEOUT.minusMillis(1));
        service.recordLifecycleActionHeartbeat(REGION, FLEET, HOOK, INSTANCE, null);

        Instant extended = START.plus(HEARTBEAT_TIMEOUT.minusMillis(1)).plus(HEARTBEAT_TIMEOUT);
        assertEquals(extended, member.getLaunchLifecycleActions().get(HOOK).deadline());

        clock.advance(HEARTBEAT_TIMEOUT.minusMillis(1));
        reconciler.reconcile(group);
        assertEquals("Pending:Wait", member.getLifecycleState());

        clock.advance(Duration.ofMillis(1));
        reconciler.reconcile(group);
        assertEquals("Heartbeat Timeout", member.getLaunchLifecycleActions().get(HOOK).reason());
    }

    @Test
    void heartbeat_atTheTimeout_isRefusedAndLeavesTheDeadlineAlone() {
        reconciler.reconcile(group);

        clock.advance(HEARTBEAT_TIMEOUT);
        AwsException refused = assertThrows(AwsException.class,
                () -> service.recordLifecycleActionHeartbeat(REGION, FLEET, HOOK, INSTANCE, null));

        assertEquals("ValidationError", refused.getErrorCode());
        assertEquals(START.plus(HEARTBEAT_TIMEOUT), member.getLaunchLifecycleActions().get(HOOK).deadline());
    }

    @Test
    void activity_startAndEndTimes_areReadFromTheClock() {
        ScalingActivity activity = service.recordActivity(REGION, FLEET, "Launching", "cause", "InProgress");
        assertEquals(START, activity.getStartTime());

        clock.advance(Duration.ofSeconds(42));
        service.completeActivity(activity.getActivityId(), "Successful", null);

        ScalingActivity completed = service.describeScalingActivities(REGION, FLEET).getFirst();
        assertEquals(START, completed.getStartTime());
        assertEquals(START.plusSeconds(42), completed.getEndTime());
    }

    private AutoScalingGroup group() {
        AutoScalingGroup result = new AutoScalingGroup();
        result.setAutoScalingGroupName(FLEET);
        result.setRegion(REGION);
        result.setDesiredCapacity(1);
        result.setMinSize(0);
        result.setMaxSize(2);
        AsgInstance instance = new AsgInstance();
        instance.setInstanceId(INSTANCE);
        instance.setLifecycleState("Pending");
        instance.setHealthStatus("Healthy");
        result.setInstances(new ArrayList<>(List.of(instance)));
        service.groups.put(REGION + "::" + FLEET, result);
        return result;
    }
}
