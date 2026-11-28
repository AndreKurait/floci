package com.floci.test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;

import static org.assertj.core.api.Assertions.assertThat;

class AutoScalingIsolationTest {

    @ParameterizedTest
    @CsvSource({"us-west-2,111111111111", "us-east-1,222222222222", "us-east-1,111111111111"})
    void deletingAGroupOrPolicyPreservesOtherRegionalAccountAndGroupScopes(String otherRegion, String otherAccount) {
        String name = TestFixtures.uniqueName("asg-isolation");
        String otherName = otherRegion.equals("us-east-1") && otherAccount.equals("111111111111")
                ? name + "-other" : name;
        try (AutoScalingClient first = client("us-east-1", "111111111111");
                AutoScalingClient other = client(otherRegion, otherAccount)) {
            try {
                create(first, "us-east-1", name, name + "-first-lc");
                create(other, otherRegion, otherName, name + "-other-lc");
                for (AutoScalingClient client : new AutoScalingClient[]{first, other}) {
                    String group = client == first ? name : otherName;
                    client.putLifecycleHook(request -> request.autoScalingGroupName(group)
                            .lifecycleHookName(name).lifecycleTransition("autoscaling:EC2_INSTANCE_LAUNCHING"));
                    client.putScalingPolicy(request -> request.autoScalingGroupName(group)
                            .policyName(name).adjustmentType("ChangeInCapacity").scalingAdjustment(1));
                }
                assertThat(other.describeLifecycleHooks(request -> request.autoScalingGroupName(otherName))
                        .lifecycleHooks()).hasSize(1);
                first.deletePolicy(request -> request.autoScalingGroupName(name).policyName(name));
                assertThat(other.describePolicies(request -> request.autoScalingGroupName(otherName))
                        .scalingPolicies()).hasSize(1);
                first.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
                assertThat(other.describeAutoScalingGroups(request -> request.autoScalingGroupNames(otherName))
                        .autoScalingGroups()).hasSize(1);
                assertThat(other.describeLifecycleHooks(request -> request.autoScalingGroupName(otherName))
                        .lifecycleHooks()).hasSize(1);
                assertThat(other.describePolicies(request -> request.autoScalingGroupName(otherName))
                        .scalingPolicies()).hasSize(1);
            } finally {
                cleanup(first, name, name + "-first-lc");
                cleanup(other, otherName, name + "-other-lc");
            }
        }
    }

    private static AutoScalingClient client(String region, String account) {
        return AutoScalingClient.builder().endpointOverride(TestFixtures.endpoint())
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }

    private static void create(AutoScalingClient client, String region, String name, String launchConfiguration) {
        client.createLaunchConfiguration(request -> request.launchConfigurationName(launchConfiguration)
                .imageId("ami-12345678").instanceType("t3.micro"));
        client.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                .launchConfigurationName(launchConfiguration).minSize(0).maxSize(0).desiredCapacity(0)
                .availabilityZones(region + "a"));
    }

    private static void cleanup(AutoScalingClient client, String name, String launchConfiguration) {
        if (!client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().isEmpty()) {
            client.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
        }
        if (!client.describeLaunchConfigurations(request -> request.launchConfigurationNames(launchConfiguration))
                .launchConfigurations().isEmpty()) {
            client.deleteLaunchConfiguration(request -> request.launchConfigurationName(launchConfiguration));
        }
    }
}
