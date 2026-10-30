package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Address;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;
import software.amazon.awssdk.services.ec2.model.NetworkInterfaceStatus;
import software.amazon.awssdk.services.ec2.model.PrivateIpAddressSpecification;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2DeadHostAddressIntegrationTest {

    @Inject
    Ec2Service service;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reconcilesDeadHostBeforeAddressReadback(boolean secondary) throws Exception {
        String account = "000000000000";
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build()) {
            String vpc = client.createVpc(b -> b.cidrBlock("10.231.0.0/24")).vpc().vpcId();
            try (AutoCloseable deleteVpc = () -> client.deleteVpc(b -> b.vpcId(vpc))) {
                String subnet = client.createSubnet(b -> b.vpcId(vpc).cidrBlock("10.231.0.0/24"))
                        .subnet().subnetId();
                try (AutoCloseable deleteSubnet = () -> client.deleteSubnet(b -> b.subnetId(subnet))) {
                    String eni = client.createNetworkInterface(b -> b.subnetId(subnet).privateIpAddresses(
                            PrivateIpAddressSpecification.builder().privateIpAddress("10.231.0.10")
                                    .primary(true).build(),
                            PrivateIpAddressSpecification.builder().privateIpAddress("10.231.0.11")
                                    .primary(false).build())).networkInterface().networkInterfaceId();
                    try (AutoCloseable deleteEni = () -> client.deleteNetworkInterface(b -> b.networkInterfaceId(eni))) {
                        String host = client.runInstances(b -> b.imageId("ami-1234567890abcdef0")
                                .instanceType("t3.micro").minCount(1).maxCount(1).networkInterfaces(
                                        InstanceNetworkInterfaceSpecification.builder().networkInterfaceId(eni)
                                                .deviceIndex(0).deleteOnTermination(false).build()))
                                .instances().getFirst().instanceId();
                        try (AutoCloseable terminate = () -> client.terminateInstances(b -> b.instanceIds(host))) {
                            String allocation = client.allocateAddress().allocationId();
                            try (AutoCloseable release = () -> client.releaseAddress(b -> b.allocationId(allocation))) {
                                String privateIp = secondary ? "10.231.0.11" : "10.231.0.10";
                                String association = client.associateAddress(b -> b.allocationId(allocation)
                                        .instanceId(host).privateIpAddress(privateIp)).associationId();
                                try (AutoCloseable disassociate = () -> client.disassociateAddress(
                                        b -> b.associationId(association))) {
                                    // Model the asynchronous terminal state without the TerminateInstances path.
                                    service.findInstanceForAccount(account, "us-east-1", host).orElseThrow()
                                            .setState(InstanceState.terminated());
                                    assertEquals(NetworkInterfaceStatus.AVAILABLE, client.describeNetworkInterfaces(
                                            b -> b.networkInterfaceIds(eni)).networkInterfaces().getFirst().status());
                                    Address address = client.describeAddresses(b -> b.allocationIds(allocation))
                                            .addresses().getFirst();
                                    assertNull(address.instanceId());
                                    assertEquals(eni, address.networkInterfaceId());
                                    assertEquals(association, address.associationId());
                                    assertEquals(privateIp, address.privateIpAddress());
                                    assertTrue(client.describeAddresses(b -> b.filters(Filter.builder()
                                            .name("instance-id").values(host).build())).addresses().isEmpty());
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
