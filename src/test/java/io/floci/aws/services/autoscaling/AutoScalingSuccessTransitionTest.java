package io.floci.aws.services.autoscaling;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.autoscaling.model.AsgInstance;
import io.floci.aws.services.autoscaling.model.AutoScalingGroup;
import io.floci.aws.services.autoscaling.model.LaunchConfiguration;
import io.floci.aws.services.autoscaling.model.ScalingActivity;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.model.Reservation;
import io.floci.aws.services.elbv2.ElbV2Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoScalingSuccessTransitionTest {
    @ParameterizedTest
    @ValueSource(strings = {"ready", "pending", "not-stored", "standby-return", "standby-registration-rejected"})
    void onlyStoredNewRunningTransitionPublishesLaunch(String phase) {
        AutoScalingService scaling = service();
        Ec2Service ec2 = mock(Ec2Service.class);
        ElbV2Service balancers = mock(ElbV2Service.class);
        AutoScalingGroup group = group();
        AsgInstance member = new AsgInstance();
        member.setInstanceId("i-transition");
        member.setLifecycleState("Pending");
        group.getInstances().add(member);
        Instance instance = new Instance();
        instance.setInstanceId(member.getInstanceId());
        instance.setState("pending".equals(phase) ? InstanceState.pending() : InstanceState.running());
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(instance));
        when(ec2.describeInstances("us-east-1", List.of(member.getInstanceId()), null))
                .thenReturn(List.of(reservation));
        when(ec2.isInstanceContainerRunning(member.getInstanceId())).thenReturn(true);
        when(scaling.saveAutoScalingGroupIfPresent(group)).thenReturn(!"not-stored".equals(phase));
        ScalingActivity activity = new ScalingActivity();
        when(scaling.recordActivity(anyString(), anyString(), anyString(), anyString(), eq("Successful")))
                .thenReturn(activity);
        if (phase.startsWith("standby")) {
            member.setStandbyActivityId("standby-activity");
        }
        if ("standby-registration-rejected".equals(phase)) {
            group.setTargetGroupARNs(List.of("target"));
            doThrow(new AwsException("Throttling", "registration rejected", 503))
                    .when(balancers).registerTargets(anyString(), anyString(), anyList());
        }
        AutoScalingReconciler reconciler = new AutoScalingReconciler(scaling, ec2, balancers);
        try {
            reconciler.reconcile(group);
            reconciler.reconcile(group);
            if ("ready".equals(phase)) {
                verify(scaling, times(1)).instanceLaunched(group, instance, activity);
                assertEquals("InService", member.getLifecycleState());
            } else {
                verify(scaling, never()).instanceLaunched(any(), any(), any());
            }
        } finally {
            reconciler.stop();
        }
    }

    @Test
    void rejectedNativeStartDoesNotPublishAnySuccess() {
        AutoScalingService scaling = service();
        Ec2Service ec2 = mock(Ec2Service.class, invocation -> {
            if ("runInstances".equals(invocation.getMethod().getName())) {
                throw new AwsException("InsufficientInstanceCapacity", "native start rejected", 400);
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        AutoScalingGroup group = group();
        group.setLaunchConfigurationName("launch");
        LaunchConfiguration configuration = new LaunchConfiguration();
        configuration.setLaunchConfigurationName("launch");
        configuration.setImageId("ami-test");
        configuration.setInstanceType("t3.micro");
        when(scaling.describeLaunchConfigurations("us-east-1", List.of("launch")))
                .thenReturn(List.of(configuration));
        when(scaling.recordActivity(anyString(), anyString(), anyString(), anyString(), eq("Failed")))
                .thenReturn(new ScalingActivity());
        AutoScalingReconciler reconciler = new AutoScalingReconciler(scaling, ec2, mock(ElbV2Service.class));
        try {
            reconciler.reconcile(group);
            assertTrue(mockingDetails(ec2).getInvocations().stream()
                    .anyMatch(i -> "runInstances".equals(i.getMethod().getName())));
            assertTrue(group.getInstances().isEmpty());
            verify(scaling, never()).instanceLaunched(any(), any(), any());
            verify(scaling, never()).terminationObserver(any(), anyList());
        } finally {
            reconciler.stop();
        }
    }

    private static AutoScalingGroup group() {
        AutoScalingGroup group = new AutoScalingGroup();
        group.setAutoScalingGroupName("owned-group");
        group.setRegion("us-east-1");
        group.setDesiredCapacity(1);
        group.setAvailabilityZones(List.of("us-east-1a"));
        return group;
    }

    private static AutoScalingService service() {
        AutoScalingService scaling = mock(AutoScalingService.class);
        doAnswer(call -> {
            Runnable body = call.getArgument(1);
            body.run();
            return null;
        }).when(scaling).reconcileIfPresent(any(), any());
        return scaling;
    }
}
