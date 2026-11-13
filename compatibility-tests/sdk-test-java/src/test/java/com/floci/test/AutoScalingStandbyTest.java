package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingInstanceDetails;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Auto Scaling standby and instance details")
class AutoScalingStandbyTest {
    private AutoScalingClient autoScaling;
    private ElasticLoadBalancingV2Client elb;
    private String name;
    private String targetGroup;
    private boolean configurationCreated;
    private boolean groupCreated;

    @BeforeEach
    void createGroup() throws InterruptedException {
        autoScaling = TestFixtures.autoScalingClient();
        elb = TestFixtures.elbV2Client();
        name = TestFixtures.uniqueName("sdk-standby");
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpc = ec2.describeVpcs().vpcs().stream().filter(item -> Boolean.TRUE.equals(item.isDefault()))
                    .findFirst().orElseThrow().vpcId();
            targetGroup = elb.createTargetGroup(request -> request.name(name).protocol("HTTP").port(80)
                    .vpcId(vpc).targetType("instance")).targetGroups().get(0).targetGroupArn();
        }
        autoScaling.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                .imageId("ami-amazonlinux2023").instanceType("t3.micro"));
        configurationCreated = true;
        autoScaling.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                .launchConfigurationName(name).minSize(0).maxSize(4).desiredCapacity(2)
                .availabilityZones(TestFixtures.availabilityZone()).targetGroupARNs(targetGroup));
        groupCreated = true;
        TestFixtures.await(() -> group().instances().size() == 2
                        && group().instances().stream().allMatch(instance -> "InService".equals(
                                instance.lifecycleStateAsString())),
                name, "two InService instances", TimeUnit.SECONDS.toNanos(60), 250);
    }

    @AfterEach
    void cleanUp() {
        try {
            if (groupCreated) {
                autoScaling.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
            }
            if (configurationCreated) {
                autoScaling.deleteLaunchConfiguration(request -> request.launchConfigurationName(name));
            }
            if (targetGroup != null) {
                elb.deleteTargetGroup(request -> request.targetGroupArn(targetGroup));
            }
        } finally {
            if (autoScaling != null) {
                autoScaling.close();
            }
            if (elb != null) {
                elb.close();
            }
        }
    }

    @Test
    @DisplayName("DescribeAutoScalingInstances returns the required owning group name")
    void instanceDetailsIncludeGroupName() {
        String id = group().instances().get(0).instanceId();
        assertThat(instance(id).autoScalingGroupName()).isEqualTo(name);
    }

    @Test
    @DisplayName("Instance details use uppercase health while group members retain title case")
    void healthCasingFollowsTheOperation() {
        String id = group().instances().get(0).instanceId();
        assertThat(instance(id).healthStatus()).isEqualTo("HEALTHY");
        autoScaling.suspendProcesses(request -> request.autoScalingGroupName(name)
                .scalingProcesses("HealthCheck", "ReplaceUnhealthy"));
        autoScaling.setInstanceHealth(request -> request.instanceId(id).healthStatus("Unhealthy")
                .shouldRespectGracePeriod(false));
        assertThat(instance(id).healthStatus()).isEqualTo("UNHEALTHY");
        assertThat(group().instances().stream().filter(item -> id.equals(item.instanceId())).findFirst()
                .orElseThrow().healthStatus()).isEqualTo("Unhealthy");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("EnterStandby and ExitStandby change polled lifecycle, target membership and capacity")
    void standbyRoundTrip(boolean decrement) throws InterruptedException {
        String id = group().instances().get(0).instanceId();
        assertThat(targetIds()).contains(id);
        List<Activity> entering = autoScaling.enterStandby(request -> request.autoScalingGroupName(name)
                .instanceIds(id).shouldDecrementDesiredCapacity(decrement)).activities();
        assertThat(entering).hasSize(1);
        assertThat(entering.get(0).statusCodeAsString()).isEqualTo("InProgress");
        assertThat(entering.get(0).autoScalingGroupName()).isEqualTo(name);
        assertThat(entering.get(0).activityId()).isNotBlank();
        TestFixtures.await(() -> "Standby".equals(instance(id).lifecycleState()), name, "Standby",
                TimeUnit.SECONDS.toNanos(60), 250);
        assertThat(instance(id).autoScalingGroupName()).isEqualTo(name);
        assertThat(instance(id).healthStatus()).isEqualTo("HEALTHY");
        assertThat(targetIds()).doesNotContain(id);
        assertThat(group().desiredCapacity()).isEqualTo(decrement ? 1 : 2);
        if (!decrement) {
            TestFixtures.await(() -> group().instances().size() == 3, name, "replacement capacity",
                    TimeUnit.SECONDS.toNanos(60), 250);
        }

        List<Activity> exiting = autoScaling.exitStandby(request -> request.autoScalingGroupName(name)
                .instanceIds(id)).activities();
        assertThat(exiting).hasSize(1);
        assertThat(exiting.get(0).statusCodeAsString()).isEqualTo("PreInService");
        TestFixtures.await(() -> "InService".equals(instance(id).lifecycleState()), name, "InService",
                TimeUnit.SECONDS.toNanos(60), 250);
        assertThat(targetIds()).contains(id);
        assertThat(group().desiredCapacity()).isEqualTo(decrement ? 2 : 3);
        assertThat(autoScaling.describeScalingActivities(request -> request.autoScalingGroupName(name))
                .activities()).anySatisfy(activity -> {
                    assertThat(activity.activityId()).isEqualTo(exiting.get(0).activityId());
                    assertThat(activity.statusCodeAsString()).isEqualTo("Successful");
                    assertThat(activity.endTime()).isNotNull();
                });
    }

    @Test
    @DisplayName("Standby rejects invalid membership, lifecycle and minimum capacity through the SDK")
    void standbyValidationMapsToSdkErrors() {
        String id = group().instances().get(0).instanceId();
        assertValidation(() -> autoScaling.enterStandby(request -> request.autoScalingGroupName(name)
                .instanceIds("i-00000000000000000").shouldDecrementDesiredCapacity(true)));
        assertValidation(() -> autoScaling.exitStandby(request -> request.autoScalingGroupName(name).instanceIds(id)));
        autoScaling.updateAutoScalingGroup(request -> request.autoScalingGroupName(name).minSize(2));
        assertValidation(() -> autoScaling.enterStandby(request -> request.autoScalingGroupName(name)
                .instanceIds(id).shouldDecrementDesiredCapacity(true)));
        assertThat(group().desiredCapacity()).isEqualTo(2);
        assertThat(instance(id).lifecycleState()).isEqualTo("InService");
        assertThat(targetIds()).contains(id);
    }

    private static void assertValidation(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AutoScalingException.class, error -> {
            assertThat(error.statusCode()).isEqualTo(400);
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
            assertThat(error.requestId()).isNotBlank();
        });
    }

    private AutoScalingGroup group() {
        return autoScaling.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().get(0);
    }

    private AutoScalingInstanceDetails instance(String id) {
        return autoScaling.describeAutoScalingInstances(request -> request.instanceIds(id))
                .autoScalingInstances().get(0);
    }

    private List<String> targetIds() {
        return elb.describeTargetHealth(request -> request.targetGroupArn(targetGroup)).targetHealthDescriptions()
                // AWS can still list draining targets after they stop receiving new traffic.
                .stream().filter(description -> !"draining".equals(description.targetHealth().stateAsString()))
                .map(description -> description.target()).map(TargetDescription::id).toList();
    }
}
