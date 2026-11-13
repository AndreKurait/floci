package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class Ec2PrimaryInterfaceDetachIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void primaryDetachIsRejectedAndTerminationRetainsInterface(boolean force) throws Exception {
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            String vpc = client.createVpc(b -> b.cidrBlock("10.240.0.0/24")).vpc().vpcId();
            try (AutoCloseable deleteVpc = () -> client.deleteVpc(b -> b.vpcId(vpc))) {
                String subnet = client.createSubnet(b -> b.vpcId(vpc).cidrBlock("10.240.0.0/24"))
                        .subnet().subnetId();
                try (AutoCloseable deleteSubnet = () -> client.deleteSubnet(b -> b.subnetId(subnet))) {
                    String primary = client.createNetworkInterface(b -> b.subnetId(subnet))
                            .networkInterface().networkInterfaceId();
                    try (AutoCloseable deletePrimary = () -> client.deleteNetworkInterface(b -> b.networkInterfaceId(primary))) {
                        String instance = client.runInstances(b -> b.imageId("ami-1234567890abcdef0")
                                        .instanceType("t3.micro").minCount(1).maxCount(1)
                                        .networkInterfaces(InstanceNetworkInterfaceSpecification.builder()
                                                .networkInterfaceId(primary).deviceIndex(0)
                                                .deleteOnTermination(false).build()))
                                .instances().getFirst().instanceId();
                        try (AutoCloseable terminate = () -> client.terminateInstances(b -> b.instanceIds(instance))) {
                            client.describeInstances(b -> b.instanceIds(instance));
                            NetworkInterface before = client.describeNetworkInterfaces(b -> b.networkInterfaceIds(primary))
                                    .networkInterfaces().getFirst();
                            Ec2Exception error = assertThrows(Ec2Exception.class,
                                    () -> client.detachNetworkInterface(b -> b.attachmentId(before.attachment().attachmentId())
                                            .force(force)));
                            assertEquals("OperationNotPermitted", error.awsErrorDetails().errorCode());
                            assertEquals(400, error.statusCode());
                            NetworkInterface after = client.describeNetworkInterfaces(b -> b.networkInterfaceIds(primary))
                                    .networkInterfaces().getFirst();
                            assertEquals(before.attachment(), after.attachment());
                            assertEquals(before.privateIpAddress(), after.privateIpAddress());
                            assertEquals("in-use", after.statusAsString());
                            assertEquals(primary, client.describeInstances(b -> b.instanceIds(instance))
                                    .reservations().getFirst().instances().getFirst()
                                    .networkInterfaces().getFirst().networkInterfaceId());
                        }
                        NetworkInterface retained = client.describeNetworkInterfaces(b -> b.networkInterfaceIds(primary))
                                .networkInterfaces().getFirst();
                        assertNull(retained.attachment());
                        assertEquals("available", retained.statusAsString());
                    }
                }
            }
        }
    }
}
