package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.IpPermission;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(Ec2PrivateAddressPacketIntegrationTest.ProtectedVpc.class)
class Ec2PrivateAddressPacketIntegrationTest {

    public static class ProtectedVpc implements QuarkusTestProfile {
        private static final String NAMESPACE = "ec2-private-address-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false",
                    "floci.services.ec2.vpc-networks.enabled", "true",
                    "floci.services.ec2.aws-faithful-private-ip", "true",
                    "floci.network.security-group-enforcement.enabled", "true",
                    "floci.docker.resource-namespace", NAMESPACE);
        }
    }

    @Inject Ec2Service service;
    @Inject SecurityGroupFirewallManager firewall;
    @Inject DockerClient docker;
    @InjectMock AmiImageResolver images;
    @TestHTTPResource URI endpoint;
    @TempDir Path imageContext;

    private final Deque<Executable> cleanup = new ArrayDeque<>();

    @BeforeEach
    void requireDocker() throws Exception {
        assumeTrue(DockerAvailability.isAvailable(), "Docker is required for private-address packets");
        Files.writeString(imageContext.resolve("Dockerfile"),
                "FROM " + TestImages.BUSYBOX + "\nRUN mkdir -p /var/lib\n");
        try (BuildImageResultCallback build = docker.buildImageCmd(imageContext.toFile()).withPull(false).start()) {
            String image = build.awaitImageId(60, TimeUnit.SECONDS);
            cleanup.addFirst(() -> docker.removeImageCmd(image).exec());
            when(images.resolveImage("ami-private-address")).thenReturn(ResolvedAmiImage.minimal(image));
        }
    }

    @AfterEach
    void deleteOwnedResources() {
        assertAll("EC2 private-address resources", cleanup);
    }

    @Test
    void clientsReachTheAdvertisedPrivateAddressOnlyWhileIngressAllowsThem() throws Exception {
        Ec2Client sdk = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        cleanup.addFirst(sdk::close);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String vpc = sdk.createVpc(request -> request.cidrBlock("10.87.0.0/16")).vpc().vpcId();
        cleanup.addFirst(() -> sdk.deleteVpc(request -> request.vpcId(vpc)));
        String subnet = sdk.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.87.1.0/24"))
                .subnet().subnetId();
        cleanup.addFirst(() -> sdk.deleteSubnet(request -> request.subnetId(subnet)));
        String group = sdk.createSecurityGroup(request -> request.vpcId(vpc)
                .groupName("private-address-" + suffix).description("Private address packet validation")).groupId();
        cleanup.addFirst(() -> sdk.deleteSecurityGroup(request -> request.groupId(group)));
        String otherGroup = sdk.createSecurityGroup(request -> request.vpcId(vpc)
                .groupName("untrusted-" + suffix).description("No membership in the allowed group")).groupId();
        cleanup.addFirst(() -> sdk.deleteSecurityGroup(request -> request.groupId(otherGroup)));
        IpPermission ingress = IpPermission.builder().ipProtocol("tcp").fromPort(8080).toPort(8080)
                .userIdGroupPairs(pair -> pair.groupId(group)).build();
        sdk.authorizeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(ingress));
        String marker = "private-address-" + suffix;
        String userData = Base64.getEncoder().encodeToString(("""
                #!/bin/sh
                set -eu
                mkdir -p /www
                printf '%%s' '%s' > /www/index.html
                httpd -p 8080 -h /www
                """).formatted(marker).getBytes(StandardCharsets.UTF_8));
        String target = launch(sdk, subnet, group, "10.87.1.10", userData);
        String caller = launch(sdk, subnet, group, "10.87.1.11", null);
        Instance targetInstance = localInstance(target);
        String targetGuest = targetInstance.getDockerContainerId();
        String callerGuest = localInstance(caller).getDockerContainerId();
        String privateAddress = sdk.describeInstances(request -> request.instanceIds(target)).reservations()
                .getFirst().instances().getFirst().privateIpAddress();
        String transportAddress = networkNamespace(targetGuest).getNetworkSettings()
                .getNetworks().get("bridge").getIpAddress();
        assertEquals("10.87.1.10", privateAddress);
        assertNotEquals(privateAddress, transportAddress);
        await().atMost(Duration.ofSeconds(30)).until(() -> fetch(targetGuest, "127.0.0.1").code() == 0);
        assertEquals(marker, fetch(callerGuest, transportAddress).output().trim(),
                "The listener and the declared security-group permission must work before the private-IP probe");

        CommandResult allowed = fetch(callerGuest, privateAddress);
        assertEquals(0, allowed.code(),
                "An allowed same-VPC client must reach the EC2 API's private address: " + allowed.output());
        assertEquals(marker, allowed.output().trim());
        sdk.revokeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(ingress));
        long started = System.nanoTime();
        assertNotEquals(0, fetch(callerGuest, privateAddress).code());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 1500,
                "Denied ingress should drop the connection until the client's network timeout");
        assertEquals(marker, fetch(targetGuest, "127.0.0.1").output().trim());
        sdk.authorizeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(ingress));
        assertEquals(marker, fetch(callerGuest, privateAddress).output().trim());

        sdk.stopInstances(request -> request.instanceIds(caller));
        await().atMost(Duration.ofSeconds(60)).until(() -> state(sdk, caller) == InstanceStateName.STOPPED);
        sdk.startInstances(request -> request.instanceIds(caller));
        await().atMost(Duration.ofSeconds(60)).until(() -> state(sdk, caller) == InstanceStateName.RUNNING);
        assertEquals(marker, fetch(callerGuest, privateAddress).output().trim());
        assertEquals(marker, fetch(callerGuest, transportAddress).output().trim());

        sdk.modifyInstanceAttribute(request -> request.instanceId(caller).groups(otherGroup));
        assertNotEquals(0, fetch(callerGuest, privateAddress).code());
        assertNotEquals(0, fetch(callerGuest, transportAddress).code());
        sdk.modifyInstanceAttribute(request -> request.instanceId(caller).groups(group));
        assertEquals(marker, fetch(callerGuest, privateAddress).output().trim());
        assertEquals(marker, fetch(callerGuest, transportAddress).output().trim());

        InspectContainerResponse callerNetwork = networkNamespace(callerGuest);
        String callerTransport = callerNetwork.getNetworkSettings().getNetworks().get("bridge").getIpAddress();
        String vpcNetwork = callerNetwork.getNetworkSettings().getNetworks().values().stream()
                .filter(network -> "10.87.1.11".equals(network.getIpAddress()))
                .findFirst().orElseThrow().getNetworkID();
        docker.disconnectFromNetworkCmd().withContainerId(callerNetwork.getId()).withNetworkId(vpcNetwork).exec();
        await().atMost(Duration.ofSeconds(10)).until(() -> firewall.vpcForClient(callerTransport).isEmpty()
                && firewall.vpcForClient("10.87.1.11").isEmpty());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertNotEquals(0, fetch(callerGuest, transportAddress).code(),
                        "Losing the VPC interface must also withdraw permissions from the surviving bridge"));
        assertEquals(marker, fetch(targetGuest, "127.0.0.1").output().trim());
    }

    private String launch(Ec2Client sdk, String subnet, String group, String address, String userData) {
        String id = sdk.runInstances(request -> request.imageId("ami-private-address")
                .instanceType("t3.micro").subnetId(subnet).securityGroupIds(group).privateIpAddress(address)
                .userData(userData).minCount(1).maxCount(1)).instances().getFirst().instanceId();
        cleanup.addFirst(() -> {
            sdk.terminateInstances(request -> request.instanceIds(id));
            await().atMost(Duration.ofSeconds(60)).until(() -> state(sdk, id) == InstanceStateName.TERMINATED);
        });
        await().atMost(Duration.ofSeconds(60)).until(() -> state(sdk, id) == InstanceStateName.RUNNING);
        return id;
    }

    private Instance localInstance(String id) {
        return service.describeInstances("us-east-1", List.of(id), Map.of()).getFirst().getInstances().getFirst();
    }

    private InspectContainerResponse networkNamespace(String guest) {
        String mode = docker.inspectContainerCmd(guest).exec().getHostConfig().getNetworkMode();
        assertTrue(mode.startsWith("container:"));
        return docker.inspectContainerCmd(mode.substring("container:".length())).exec();
    }

    private static InstanceStateName state(Ec2Client sdk, String id) {
        return sdk.describeInstances(request -> request.instanceIds(id)).reservations()
                .getFirst().instances().getFirst().state().name();
    }

    private CommandResult fetch(String container, String address) throws Exception {
        String id = docker.execCreateCmd(container).withAttachStdout(true).withAttachStderr(true)
                .withCmd("wget", "-q", "-T", "2", "-O", "-", "http://" + address + ":8080/").exec().getId();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                output.writeBytes(frame.getPayload());
            }
        }) {
            assertTrue(docker.execStartCmd(id).exec(callback).awaitCompletion(10, TimeUnit.SECONDS));
        }
        Long code = docker.inspectExecCmd(id).exec().getExitCodeLong();
        assertNotNull(code);
        return new CommandResult(code.intValue(), output.toString(StandardCharsets.UTF_8));
    }

    private record CommandResult(int code, String output) {}
}
