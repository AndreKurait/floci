package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Address;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;
import software.amazon.awssdk.services.ec2.model.NetworkInterfaceStatus;
import software.amazon.awssdk.services.ec2.model.PrivateIpAddressSpecification;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2DeadHostAddressIntegrationTest {

    @Inject
    Ec2Service service;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void releasesImplicitInterfaceAddressAfterTerminalReadback(boolean defaultVpc) throws Exception {
        String account = "000000000000";
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build()) {
            String vpc = defaultVpc ? client.describeVpcs(b -> b.filters(Filter.builder()
                    .name("is-default").values("true").build())).vpcs().getFirst().vpcId()
                    : client.createVpc(b -> b.cidrBlock("10.238.0.0/24")).vpc().vpcId();
            String subnet = null;
            String host = null;
            String allocation = null;
            try {
                subnet = defaultVpc ? client.describeSubnets(b -> b.filters(Filter.builder()
                        .name("vpc-id").values(vpc).build())).subnets().getFirst().subnetId()
                        : client.createSubnet(b -> b.vpcId(vpc).cidrBlock("10.238.0.0/24")).subnet().subnetId();
                String selectedSubnet = subnet;
                host = client.runInstances(b -> b.imageId("ami-1234567890abcdef0").instanceType("t3.micro")
                        .minCount(1).maxCount(1).subnetId(selectedSubnet)).instances().getFirst().instanceId();
                allocation = client.allocateAddress().allocationId();
                String selectedHost = host;
                String selectedAllocation = allocation;
                String association = client.associateAddress(b -> b.allocationId(selectedAllocation)
                        .instanceId(selectedHost)).associationId();
                // Model the terminal state produced by failed launch; the service test runs the failure method.
                service.findInstanceForAccount(account, "us-east-1", host).orElseThrow()
                        .setState(InstanceState.terminated());
                Address address = client.describeAddresses(b -> b.allocationIds(selectedAllocation))
                        .addresses().getFirst();
                assertNull(address.instanceId());
                assertEquals(association, address.associationId());
                if (!defaultVpc) {
                    Ec2Exception error = assertThrows(Ec2Exception.class,
                            () -> client.releaseAddress(b -> b.allocationId(selectedAllocation)));
                    assertEquals("InvalidIPAddress.InUse", error.awsErrorDetails().errorCode());
                    assertEquals(association, client.describeAddresses(b -> b.allocationIds(selectedAllocation))
                            .addresses().getFirst().associationId());
                    client.disassociateAddress(b -> b.associationId(association));
                }
                client.releaseAddress(b -> b.allocationId(selectedAllocation));
                allocation = null;
                assertTrue(client.describeAddresses(b -> b.allocationIds(selectedAllocation)).addresses().isEmpty());
            } finally {
                if (allocation != null) {
                    String remainingAllocation = allocation;
                    Address remaining = client.describeAddresses(b -> b.allocationIds(remainingAllocation))
                            .addresses().getFirst();
                    if (remaining.associationId() != null) {
                        client.disassociateAddress(b -> b.associationId(remaining.associationId()));
                    }
                    client.releaseAddress(b -> b.allocationId(remainingAllocation));
                }
                if (host != null) {
                    String remainingHost = host;
                    client.terminateInstances(b -> b.instanceIds(remainingHost));
                }
                if (!defaultVpc) {
                    if (subnet != null) {
                        String remainingSubnet = subnet;
                        client.deleteSubnet(b -> b.subnetId(remainingSubnet));
                    }
                    client.deleteVpc(b -> b.vpcId(vpc));
                }
            }
        }
    }

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
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void directAddressReadPreservesAttachedHostUntilTermination(boolean secondary, boolean shuttingDown) throws Exception {
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
                                            .setState(shuttingDown ? InstanceState.shuttingDown() : InstanceState.terminated());

                                    Address address = client.describeAddresses(b -> b.allocationIds(allocation))
                                            .addresses().getFirst();
                                    assertEquals(shuttingDown ? host : null, address.instanceId());
                                    assertEquals(eni, address.networkInterfaceId());
                                    assertEquals(association, address.associationId());
                                    assertEquals(privateIp, address.privateIpAddress());
                                    assertEquals(shuttingDown ? 1 : 0, client.describeAddresses(b -> b.filters(Filter.builder()
                                            .name("instance-id").values(host).build())).addresses().size());
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
