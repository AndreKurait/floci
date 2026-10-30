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
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2InterfaceTagLifecycleIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagsFollowInterfaceRetentionAndExplicitDeletion(boolean deleteOnTermination) throws Exception {
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            String vpc = client.createVpc(b -> b.cidrBlock("10.232.0.0/24")).vpc().vpcId();
            try (AutoCloseable deleteVpc = () -> client.deleteVpc(b -> b.vpcId(vpc))) {
                String subnet = client.createSubnet(b -> b.vpcId(vpc).cidrBlock("10.232.0.0/24"))
                        .subnet().subnetId();
                try (AutoCloseable deleteSubnet = () -> client.deleteSubnet(b -> b.subnetId(subnet))) {
                    Instance launched = client.runInstances(
                            b -> b.imageId("ami-1234567890abcdef0").instanceType("t3.micro").minCount(1).maxCount(1)
                                    .networkInterfaces(InstanceNetworkInterfaceSpecification.builder().subnetId(subnet)
                                            .deviceIndex(0).deleteOnTermination(deleteOnTermination).build()))
                            .instances().getFirst();
                    String host = launched.instanceId();
                    String eni = launched.networkInterfaces().getFirst().networkInterfaceId();
                    try (AutoCloseable cleanup = () -> {
                        client.terminateInstances(b -> b.instanceIds(host));
                        deleteIfPresent(client, eni);
                    }) {
                        client.createTags(b -> b.resources(eni).tags(Tag.builder().key("lifetime").value("eni").build()));
                        if (!deleteOnTermination) {
                            assertEquals("InvalidParameterValue", assertThrows(Ec2Exception.class,
                                    () -> client.deleteNetworkInterface(b -> b.networkInterfaceId(eni)))
                                    .awsErrorDetails().errorCode());
                        }
                        assertEquals(1, client.describeTags(b -> b.filters(Filter.builder()
                                .name("resource-id").values(eni).build())).tags().size());
                        client.terminateInstances(b -> b.instanceIds(host));
                        assertEquals(deleteOnTermination, client.describeTags(b -> b.filters(Filter.builder()
                                .name("resource-id").values(eni).build())).tags().isEmpty());
                        if (!deleteOnTermination) {
                            assertEquals("eni", client.describeNetworkInterfaces(b -> b.networkInterfaceIds(eni))
                                    .networkInterfaces().getFirst().tagSet().getFirst().value());
                            client.deleteNetworkInterface(b -> b.networkInterfaceId(eni));
                        }
                        assertTrue(client.describeTags(b -> b.filters(Filter.builder()
                                .name("resource-id").values(eni).build())).tags().isEmpty());
                        assertEquals("InvalidNetworkInterfaceID.NotFound", assertThrows(Ec2Exception.class,
                                () -> client.describeNetworkInterfaces(b -> b.networkInterfaceIds(eni)))
                                .awsErrorDetails().errorCode());
                    }
                }
            }
        }
    }

    private static void deleteIfPresent(Ec2Client client, String interfaceId) {
        try {
            client.deleteNetworkInterface(b -> b.networkInterfaceId(interfaceId));
        } catch (Ec2Exception expected) {
            // Delete-on-termination and the explicit-delete assertion may already have removed it.
            if (!"InvalidNetworkInterfaceID.NotFound".equals(expected.awsErrorDetails().errorCode())) {
                throw expected;
            }
        }
    }
}
