package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(Ec2RestoreEveryAccountIntegrationTest.EnforcedContainerBackedEc2.class)
class Ec2RuntimeInterfaceAddressIntegrationTest {

    @Inject
    Ec2Service service;

    @InjectMock
    Ec2ContainerManager manager;

    @InjectMock
    AmiImageResolver imageResolver;

    @InjectMock
    Ec2PortForwardManager portForwardManager;

    @BeforeEach
    void prepareLifecycleBoundary() {
        when(imageResolver.resolveImage(any())).thenReturn(ResolvedAmiImage.minimal("alpine:3.21"));
        doAnswer(call -> {
            Instance host = call.getArgument(0);
            host.setDockerContainerId("runtime-interface-sdk");
            host.setState(InstanceState.running());
            Ec2ContainerManager.exposeReachablePrivateAddress(host, "172.17.0.81", false);
            call.getArgument(7, Runnable.class).run();
            return null;
        }).when(manager).launch(any(), any(), any(), anyString(), anySet(), anyList(), anyMap(), any());
        doAnswer(call -> {
            Instance host = call.getArgument(0);
            host.setState(InstanceState.running());
            Ec2ContainerManager.exposeReachablePrivateAddress(host, "172.17.0.82", false);
            call.getArgument(1, Runnable.class).run();
            return null;
        }).when(manager).start(any(), any());
        doAnswer(call -> {
            Instance host = call.getArgument(0);
            Ec2ContainerManager.exposeReachablePrivateAddress(host, "172.17.0.82", false);
            call.getArgument(1, Runnable.class).run();
            return null;
        }).when(manager).reboot(any(), any());
        doAnswer(call -> {
            Instance host = call.getArgument(0);
            host.setState(InstanceState.terminated());
            call.getArgument(1, Runnable.class).run();
            return null;
        }).when(manager).terminate(any(), any());
        when(manager.restoreMetadataRegistration(any())).thenAnswer(call -> {
            Ec2ContainerManager.exposeReachablePrivateAddress(call.getArgument(0), "172.17.0.82", false);
            return true;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "restore", "reboot"})
    void sdkDescribesTheCurrentRuntimeAddressAfterLifecycleCompletion(String operation) throws Exception {
        String account = "111122223333";
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build()) {
            String vpc = client.createVpc(b -> b.cidrBlock("10.241.0.0/24")).vpc().vpcId();
            try (AutoCloseable deleteVpc = () -> client.deleteVpc(b -> b.vpcId(vpc))) {
                String subnet = client.createSubnet(b -> b.vpcId(vpc).cidrBlock("10.241.0.0/24"))
                        .subnet().subnetId();
                try (AutoCloseable deleteSubnet = () -> client.deleteSubnet(b -> b.subnetId(subnet))) {
                    String secondary = client.createNetworkInterface(b -> b.subnetId(subnet)
                            .privateIpAddress("10.241.0.201")).networkInterface().networkInterfaceId();
                    try (AutoCloseable deleteSecondary = () -> client.deleteNetworkInterface(b -> b.networkInterfaceId(secondary))) {
                        String host = client.runInstances(b -> b.imageId("ami-1234567890abcdef0").instanceType("t3.micro")
                                .minCount(1).maxCount(1).networkInterfaces(InstanceNetworkInterfaceSpecification.builder()
                                        .deviceIndex(0).subnetId(subnet).deleteOnTermination(false).build()))
                                .instances().getFirst().instanceId();
                        String eni = client.describeInstances(b -> b.instanceIds(host)).reservations().getFirst()
                                .instances().getFirst().networkInterfaces().getFirst().networkInterfaceId();
                        try (AutoCloseable deleteEni = () -> client.deleteNetworkInterface(b -> b.networkInterfaceId(eni));
                             AutoCloseable terminate = () -> client.terminateInstances(b -> b.instanceIds(host))) {
                            client.attachNetworkInterface(b -> b.networkInterfaceId(secondary).instanceId(host).deviceIndex(1));
                            assertEquals("172.17.0.81", client.describeNetworkInterfaces(b -> b.networkInterfaceIds(eni))
                                    .networkInterfaces().getFirst().privateIpAddress());
                            if ("reboot".equals(operation)) {
                                client.rebootInstances(b -> b.instanceIds(host));
                            } else if ("restore".equals(operation)) {
                                service.restoreMetadataRegistrations();
                            } else {
                                service.findInstanceForAccount(account, "us-east-1", host).orElseThrow()
                                        .setState(InstanceState.stopped());
                                client.startInstances(b -> b.instanceIds(host));
                            }
                            String instanceIp = client.describeInstances(b -> b.instanceIds(host)).reservations()
                                    .getFirst().instances().getFirst().privateIpAddress();
                            assertEquals("172.17.0.82", instanceIp);
                            assertEquals("10.241.0.201", client.describeInstances(b -> b.instanceIds(host))
                                    .reservations().getFirst().instances().getFirst().networkInterfaces().stream()
                                    .filter(ni -> ni.attachment().deviceIndex() == 1).findFirst().orElseThrow().privateIpAddress());
                            assertEquals("10.241.0.201", client.describeNetworkInterfaces(b -> b.networkInterfaceIds(secondary))
                                    .networkInterfaces().getFirst().privateIpAddress());
                            assertEquals(instanceIp, client.describeNetworkInterfaces(b -> b.networkInterfaceIds(eni))
                                    .networkInterfaces().getFirst().privateIpAddress());
                            assertEquals(instanceIp, client.describeNetworkInterfaces(b -> b.networkInterfaceIds(eni))
                                    .networkInterfaces().getFirst().privateIpAddresses().getFirst().privateIpAddress());
                        }
                    }
                }
            }
        }
    }
}
