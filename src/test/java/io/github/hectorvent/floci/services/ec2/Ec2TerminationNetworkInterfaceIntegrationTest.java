package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(Ec2CapturedImageReclaimIntegrationTest.ContainerBackedEc2.class)
class Ec2TerminationNetworkInterfaceIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @InjectMock
    Ec2ContainerManager containerManager;

    @InjectMock
    Ec2VolumeBlockDeviceManager volumeDevices;

    @InjectMock
    AmiImageResolver amiImageResolver;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private final Map<String, Runnable> pending = new ConcurrentHashMap<>();

    @BeforeEach
    void holdPhysicalTeardownUntilExplicitlyCompleted() {
        pending.clear();
        when(amiImageResolver.resolveImage(anyString())).thenReturn(ResolvedAmiImage.minimal("floci/base:latest"));
        doAnswer(call -> {
            call.<Instance>getArgument(0).setState(InstanceState.running());
            return null;
        }).when(containerManager).launch(any(), any(), any(), anyString(), any(), any(), any(), any(), any());
        doAnswer(call -> {
            Instance instance = call.getArgument(0);
            Runnable callback = call.getArgument(1);
            if ("terminated".equals(instance.getState().getName())) {
                callback.run();
            } else {
                instance.setState(InstanceState.shuttingDown());
                pending.putIfAbsent(instance.getInstanceId(), () -> {
                    instance.setState(InstanceState.terminated());
                    callback.run();
                });
            }
            return null;
        }).when(containerManager).terminate(any(Instance.class), any());
    }

    @ParameterizedTest
    @CsvSource({
        "us-east-1, true",
        "us-east-1, false",
        "us-gov-west-1, true",
        "us-gov-west-1, false"
    })
    void interfaceStaysAttachedUntilPhysicalTeardownCompletes(String region, boolean deleteOnTermination)
            throws Exception {
        Ec2Client client = client("935000000001", region);
        Ec2Client foreign = client("935000000002", region);
        String vpc = client.createVpc(request -> request.cidrBlock("10.237.0.0/16")).vpc().vpcId();
        cleanup.register(() -> client.deleteVpc(request -> request.vpcId(vpc)));
        String group = client.createSecurityGroup(request -> request.vpcId(vpc)
                .groupName("termination-network-" + UUID.randomUUID()).description("teardown ordering"))
                .groupId();
        cleanup.register(() -> {
            if (client.describeSecurityGroups().securityGroups().stream()
                    .anyMatch(value -> group.equals(value.groupId()))) {
                client.deleteSecurityGroup(request -> request.groupId(group));
            }
        });
        String subnet = client.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.237.0.0/24"))
                .subnet().subnetId();
        cleanup.register(() -> client.deleteSubnet(request -> request.subnetId(subnet)));
        RunInstancesResponse launch = client.runInstances(request -> request.imageId("ami-amazonlinux2023")
                .instanceType("t3.micro").minCount(1).maxCount(1)
                .networkInterfaces(InstanceNetworkInterfaceSpecification.builder().subnetId(subnet).groups(group)
                        .deviceIndex(0).deleteOnTermination(deleteOnTermination).build()));
        String instance = launch.instances().getFirst().instanceId();
        String eni = launch.instances().getFirst().networkInterfaces().getFirst().networkInterfaceId();
        cleanup.register(() -> {
            if (!interfaces(client, eni).isEmpty()) {
                client.deleteNetworkInterface(request -> request.networkInterfaceId(eni));
            }
        });
        cleanup.register(() -> {
            client.terminateInstances(request -> request.instanceIds(instance));
            Runnable callback = pending.remove(instance);
            if (callback != null) {
                callback.run();
            }
        });
        NetworkInterface before = interfaces(client, eni).getFirst();
        assertEquals("in-use", before.statusAsString());
        assertEquals(instance, before.attachment().instanceId());
        assertTrue(interfaces(foreign, eni).isEmpty());

        client.terminateInstances(request -> request.instanceIds(instance));
        client.terminateInstances(request -> request.instanceIds(instance));

        assertEquals("shutting-down", client.describeInstances(request -> request.instanceIds(instance))
                .reservations().getFirst().instances().getFirst().state().nameAsString());
        List<NetworkInterface> during = interfaces(client, eni);
        assertEquals(1, during.size(), "Physical teardown still owns this interface");
        assertEquals(before, during.getFirst(), "An in-flight teardown must preserve the attachment");
        assertDependency(client, group);
        Runnable callback = pending.remove(instance);
        assertNotNull(callback);
        // A plain worker has no HTTP request scope: the callback must restore its owner's account.
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            worker.submit(callback).get(10, TimeUnit.SECONDS);
        }

        assertEquals("terminated", client.describeInstances(request -> request.instanceIds(instance))
                .reservations().getFirst().instances().getFirst().state().nameAsString());
        if (deleteOnTermination) {
            assertTrue(interfaces(client, eni).isEmpty());
        } else {
            NetworkInterface retained = interfaces(client, eni).getFirst();
            assertEquals("available", retained.statusAsString());
            assertNull(retained.attachment());
            assertDependency(client, group);
            client.deleteNetworkInterface(request -> request.networkInterfaceId(eni));
        }
        assertTrue(interfaces(foreign, eni).isEmpty());
        client.deleteSecurityGroup(request -> request.groupId(group));
        assertTrue(client.describeSecurityGroups().securityGroups().stream()
                .noneMatch(value -> group.equals(value.groupId())));
        assertTrue(pending.isEmpty());
    }

    private Ec2Client client(String account, String region) {
        Ec2Client client = Ec2Client.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        cleanup.register(client::close);
        return client;
    }

    private static List<NetworkInterface> interfaces(Ec2Client client, String id) {
        return client.describeNetworkInterfaces().networkInterfaces().stream()
                .filter(value -> id.equals(value.networkInterfaceId())).toList();
    }

    private static void assertDependency(Ec2Client client, String group) {
        Ec2Exception error = assertThrows(Ec2Exception.class,
                () -> client.deleteSecurityGroup(request -> request.groupId(group)));
        assertEquals("DependencyViolation", error.awsErrorDetails().errorCode());
    }
}
