package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.Instance;
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoScalingDetachLoadBalancersIntegrationTest {
    // Exercise management membership through the SDK without launching an EC2 guest.
    @InjectMock
    AutoScalingReconciler reconciler;

    @Test
    void sdkDetachPreservesOtherGroupAndManualClassicRegistrations() throws Exception {
        String name = "detach-" + UUID.randomUUID().toString().substring(0, 12);
        try (AutoScalingClient scaling = AwsTestClients.create(AutoScalingClient.builder(), "000000000000", Region.US_EAST_1);
             Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1);
             ElasticLoadBalancingClient classic = AwsTestClients.create(
                     ElasticLoadBalancingClient.builder(), "000000000000", Region.US_EAST_1)) {
            classic.createLoadBalancer(request -> request.loadBalancerName(name).availabilityZones("us-east-1a")
                    .listeners(Listener.builder().protocol("HTTP").loadBalancerPort(80).instancePort(8080).build()));
            try (AutoCloseable loadBalancer = () -> classic.deleteLoadBalancer(request -> request.loadBalancerName(name))) {
                scaling.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                        .imageId("ami-12345678").instanceType("t3.micro"));
                try (AutoCloseable launchConfiguration = () -> scaling.deleteLaunchConfiguration(
                        request -> request.launchConfigurationName(name))) {
                    createGroup(scaling, name, name);
                    try (AutoCloseable group = () -> scaling.deleteAutoScalingGroup(
                            request -> request.autoScalingGroupName(name).forceDelete(true))) {
                        String other = name + "-other";
                        createGroup(scaling, other, name);
                        try (AutoCloseable otherGroup = () -> scaling.deleteAutoScalingGroup(
                                request -> request.autoScalingGroupName(other).forceDelete(true))) {
                            List<String> instances = ec2.runInstances(request -> request.imageId("ami-12345678")
                                    .instanceType(InstanceType.T3_MICRO).minCount(3).maxCount(3)
                                    .placement(placement -> placement.availabilityZone("us-east-1a")))
                                    .instances().stream().map(instance -> instance.instanceId()).toList();
                            try (AutoCloseable launched = () -> ec2.terminateInstances(
                                    request -> request.instanceIds(instances))) {
                                String owned = instances.get(0);
                                String otherMember = instances.get(1);
                                String manual = instances.get(2);
                                scaling.attachInstances(request -> request.autoScalingGroupName(name).instanceIds(owned));
                                scaling.attachInstances(request -> request.autoScalingGroupName(other).instanceIds(otherMember));
                                classic.registerInstancesWithLoadBalancer(request -> request.loadBalancerName(name).instances(
                                        Instance.builder().instanceId(owned).build(),
                                        Instance.builder().instanceId(otherMember).build(),
                                        Instance.builder().instanceId(manual).build()));

                                scaling.detachInstances(request -> request.autoScalingGroupName(name)
                                        .instanceIds(owned).shouldDecrementDesiredCapacity(true));

                                assertEquals(List.of(otherMember, manual), classic.describeLoadBalancers(
                                        request -> request.loadBalancerNames(name)).loadBalancerDescriptions().getFirst()
                                        .instances().stream().map(Instance::instanceId).toList());
                                assertTrue(scaling.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                                        .autoScalingGroups().getFirst().instances().isEmpty());
                                assertEquals(List.of(otherMember), scaling.describeAutoScalingGroups(
                                        request -> request.autoScalingGroupNames(other)).autoScalingGroups().getFirst()
                                        .instances().stream().map(instance -> instance.instanceId()).toList());
                            }
                        }
                    }
                }
            }
            assertTrue(scaling.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name, name + "-other"))
                    .autoScalingGroups().isEmpty());
            assertTrue(scaling.describeLaunchConfigurations(request -> request.launchConfigurationNames(name))
                    .launchConfigurations().isEmpty());
            assertTrue(classic.describeLoadBalancers().loadBalancerDescriptions().stream()
                    .noneMatch(loadBalancer -> name.equals(loadBalancer.loadBalancerName())));
        }
    }

    private static void createGroup(AutoScalingClient scaling, String group, String loadBalancer) {
        scaling.createAutoScalingGroup(request -> request.autoScalingGroupName(group)
                .launchConfigurationName(loadBalancer).minSize(0).maxSize(3).desiredCapacity(0)
                .availabilityZones("us-east-1a").loadBalancerNames(loadBalancer));
    }
}
