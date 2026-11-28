package io.floci.aws.services.elb;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.InstanceState;
import software.amazon.awssdk.services.elasticloadbalancing.model.InvalidInstanceException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(ElbClassicPolicyIntegrationTest.Records.class)
class ElbClassicInstanceHealthIntegrationTest {
    @Test
    void existingUnregisteredInstanceIsVisibleOnlyWhenRequestedAndOnlyToItsOwner() {
        String name = "health-" + UUID.randomUUID().toString().substring(0, 8);
        String owner = "123450001234";
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), owner, Region.US_EAST_1);
             ElasticLoadBalancingClient elb = AwsTestClients.create(
                     ElasticLoadBalancingClient.builder(), owner, Region.US_EAST_1);
             ElasticLoadBalancingClient foreign = AwsTestClients.create(
                     ElasticLoadBalancingClient.builder(), "123450005678", Region.US_EAST_1)) {
            elb.createLoadBalancer(request -> request.loadBalancerName(name).availabilityZones("us-east-1a")
                    .listeners(listener -> listener.protocol("HTTP").loadBalancerPort(80).instancePort(8080)));
            try {
                String id = ec2.runInstances(request -> request.imageId("ami-0abcdef1234567890")
                        .instanceType("t2.micro").minCount(1).maxCount(1)).instances().getFirst().instanceId();
                try {
                    InstanceState health = elb.describeInstanceHealth(request -> request.loadBalancerName(name)
                            .instances(instance -> instance.instanceId(id))).instanceStates().getFirst();
                    assertEquals(id, health.instanceId());
                    assertEquals("OutOfService", health.state());
                    assertEquals("Instance", health.reasonCode());
                    assertEquals("Instance is not currently registered with the LoadBalancer.", health.description());
                    assertTrue(elb.describeInstanceHealth(request -> request.loadBalancerName(name))
                            .instanceStates().isEmpty());
                    assertTrue(elb.describeLoadBalancers(request -> request.loadBalancerNames(name))
                            .loadBalancerDescriptions().getFirst().instances().isEmpty());

                    foreign.createLoadBalancer(request -> request.loadBalancerName(name)
                            .availabilityZones("us-east-1a").listeners(listener -> listener.protocol("HTTP")
                                    .loadBalancerPort(80).instancePort(8080)));
                    try {
                        InvalidInstanceException denied = assertThrows(InvalidInstanceException.class,
                                () -> foreign.describeInstanceHealth(request -> request.loadBalancerName(name)
                                        .instances(instance -> instance.instanceId(id))));
                        assertEquals(400, denied.statusCode());
                    } finally {
                        foreign.deleteLoadBalancer(request -> request.loadBalancerName(name));
                    }
                    assertThrows(InvalidInstanceException.class,
                            () -> elb.describeInstanceHealth(request -> request.loadBalancerName(name)
                                    .instances(instance -> instance.instanceId("i-00000000000000000"))));
                } finally {
                    ec2.terminateInstances(request -> request.instanceIds(id));
                }
            } finally {
                elb.deleteLoadBalancer(request -> request.loadBalancerName(name));
            }
        }
    }
}
