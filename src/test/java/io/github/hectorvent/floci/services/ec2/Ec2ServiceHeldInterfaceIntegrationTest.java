package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class Ec2ServiceHeldInterfaceIntegrationTest {

    @Inject
    EcsContainerManager manager;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ecsTaskInterfaceCannotBeClaimedByAnInstance(boolean launch) throws Exception {
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            String vpc = client.createVpc(b -> b.cidrBlock("10.236.0.0/24")).vpc().vpcId();
            try (AutoCloseable deleteVpc = () -> client.deleteVpc(b -> b.vpcId(vpc))) {
                String subnet = client.createSubnet(b -> b.vpcId(vpc).cidrBlock("10.236.0.0/24"))
                        .subnet().subnetId();
                try (AutoCloseable deleteSubnet = () -> client.deleteSubnet(b -> b.subnetId(subnet))) {
                    EcsTask task = new EcsTask();
                    AwsVpcConfiguration awsvpc = new AwsVpcConfiguration();
                    awsvpc.setSubnets(List.of(subnet));
                    NetworkConfiguration network = new NetworkConfiguration();
                    network.setAwsvpcConfiguration(awsvpc);
                    task.setNetworkConfiguration(network);
                    TaskDefinition definition = new TaskDefinition();
                    definition.setNetworkMode(NetworkMode.awsvpc);
                    manager.attachTaskNetwork(task, definition, "us-east-1");
                    String interfaceId = task.getNetworkInterfaceId();
                    try (AutoCloseable cleanup = () -> {
                        List<String> instances = client.describeInstances(b -> b.filters(Filter.builder()
                                        .name("subnet-id").values(subnet).build())).reservations().stream()
                                .flatMap(reservation -> reservation.instances().stream())
                                .map(instance -> instance.instanceId()).toList();
                        if (!instances.isEmpty()) {
                            client.terminateInstances(b -> b.instanceIds(instances));
                        }
                        manager.releaseTaskNetwork(task, "us-east-1");
                    }) {
                        String owner = client.runInstances(b -> b.imageId("ami-1234567890abcdef0")
                                .instanceType("t3.micro").minCount(1).maxCount(1).subnetId(subnet))
                                .instances().getFirst().instanceId();
                        client.describeInstances(b -> b.instanceIds(owner));
                        Ec2Exception error = assertThrows(Ec2Exception.class, () -> {
                            if (launch) {
                                client.runInstances(b -> b.imageId("ami-1234567890abcdef0").instanceType("t3.micro")
                                        .minCount(1).maxCount(1).networkInterfaces(InstanceNetworkInterfaceSpecification
                                                .builder().networkInterfaceId(interfaceId).deviceIndex(0).build()));
                            } else {
                                client.attachNetworkInterface(b -> b.networkInterfaceId(interfaceId)
                                        .instanceId(owner).deviceIndex(1));
                            }
                        });
                        assertEquals("InvalidNetworkInterface.InUse", error.awsErrorDetails().errorCode());
                        assertNull(client.describeNetworkInterfaces(b -> b.networkInterfaceIds(interfaceId))
                                .networkInterfaces().getFirst().attachment());
                        assertEquals("in-use", client.describeNetworkInterfaces(b -> b.networkInterfaceIds(interfaceId))
                                .networkInterfaces().getFirst().statusAsString());
                        assertEquals(1, client.describeInstances(b -> b.filters(Filter.builder()
                                        .name("subnet-id").values(subnet).build())).reservations().stream()
                                .mapToInt(reservation -> reservation.instances().size()).sum());
                    }
                    assertEquals("InvalidNetworkInterfaceID.NotFound", assertThrows(Ec2Exception.class,
                            () -> client.describeNetworkInterfaces(b -> b.networkInterfaceIds(interfaceId)))
                            .awsErrorDetails().errorCode());
                }
            }
        }
    }
}
