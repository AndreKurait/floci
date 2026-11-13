package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
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
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.NatGateway;

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
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(Ec2PublicPeerPacketIntegrationTest.RoutedVpc.class)
class Ec2PublicPeerPacketIntegrationTest {
    public static class RoutedVpc implements QuarkusTestProfile {
        private static final String NAMESPACE = "ec2-public-peers-" + UUID.randomUUID();
        private static final String CONTROL_NETWORK = NAMESPACE + "-control";

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.ec2.mock", "false",
                    "floci.services.ec2.vpc-networks.enabled", "true",
                    "floci.services.ec2.aws-faithful-private-ip", "true",
                    "floci.services.ec2.container-ips-routable", "true",
                    "floci.network.security-group-enforcement.enabled", "true",
                    "floci.network.vpc-routing.enabled", "true",
                    "floci.network.vpc-routing.public-aws-api", "false",
                    "floci.services.docker-network", CONTROL_NETWORK,
                    "floci.docker.resource-namespace", NAMESPACE);
        }
    }

    @Inject EmulatorConfig config;
    @Inject Ec2Service service;
    @Inject DockerClient docker;
    @InjectMock AmiImageResolver images;
    @TestHTTPResource URI endpoint;
    @TempDir Path imageContext;
    private final Deque<Executable> cleanup = new ArrayDeque<>();

    @BeforeEach
    void prepareOwnedNetworkAndGuestImage() throws Exception {
        assumeTrue(DockerAvailability.answers(docker), "Docker is required for public peer packets");
        String network = docker.createNetworkCmd().withName(config.services().dockerNetwork().orElseThrow())
                .withInternal(true).exec().getId();
        cleanup.addFirst(() -> docker.removeNetworkCmd(network).exec());
        Files.writeString(imageContext.resolve("Dockerfile"),
                "FROM " + TestImages.BUSYBOX + "\nRUN mkdir -p /var/lib\n");
        try (BuildImageResultCallback build = docker.buildImageCmd(imageContext.toFile()).withPull(false).start()) {
            String image = build.awaitImageId(60, TimeUnit.SECONDS);
            cleanup.addFirst(() -> docker.removeImageCmd(image).exec());
            when(images.resolveImage("ami-public-peers")).thenReturn(ResolvedAmiImage.minimal(image));
        }
    }

    @AfterEach
    void deleteOwnedResources() {
        assertAll("Public peer packet resources", cleanup);
    }

    @Test
    void publicPeerPacketsRetainSourceIdentityAndRespectRoutesAndSecurityGroups() {
        Ec2Client sdk = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        cleanup.addFirst(sdk::close);
        String vpc = sdk.createVpc(request -> request.cidrBlock("10.87.0.0/16")).vpc().vpcId();
        cleanup.addFirst(() -> sdk.deleteVpc(request -> request.vpcId(vpc)));
        String firstSubnet = subnet(sdk, vpc, "10.87.1.0/24");
        String secondSubnet = subnet(sdk, vpc, "10.87.2.0/24");
        String gateway = sdk.createInternetGateway().internetGateway().internetGatewayId();
        cleanup.addFirst(() -> sdk.deleteInternetGateway(request -> request.internetGatewayId(gateway)));
        sdk.attachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc));
        cleanup.addFirst(() -> sdk.detachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc)));
        String table = sdk.describeRouteTables(request -> request.filters(
                filter -> filter.name("vpc-id").values(vpc),
                filter -> filter.name("association.main").values("true"))).routeTables().getFirst().routeTableId();
        sdk.createRoute(request -> request.routeTableId(table).destinationCidrBlock("0.0.0.0/0").gatewayId(gateway));
        cleanup.addFirst(() -> sdk.deleteRoute(request -> request.routeTableId(table)
                .destinationCidrBlock("0.0.0.0/0")));
        String group = sdk.createSecurityGroup(request -> request.vpcId(vpc)
                .groupName(config.docker().resourceNamespace().orElseThrow()).description("Public peer packet validation")).groupId();
        cleanup.addFirst(() -> sdk.deleteSecurityGroup(request -> request.groupId(group)));
        IpPermission ingress = IpPermission.builder().ipProtocol("tcp").fromPort(8080).toPort(8080)
                .userIdGroupPairs(pair -> pair.groupId(group)).build();
        sdk.authorizeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(ingress));
        String userData = Base64.getEncoder().encodeToString("""
                #!/bin/sh
                set -eu
                mkdir -p /www/cgi-bin
                cat > /www/cgi-bin/identity <<'CGI'
                #!/bin/sh
                printf 'Content-Type: text/plain\\r\\n\\r\\n%s' "$REMOTE_ADDR"
                CGI
                chmod 755 /www/cgi-bin/identity
                httpd -p 10.87.1.10:8080 -h /www
                """.getBytes(StandardCharsets.UTF_8));
        String target = launch(sdk, firstSubnet, group, "10.87.1.10", userData);
        String caller = launch(sdk, secondSubnet, group, "10.87.2.10", null);
        Instance targetIdentity = instance(sdk, target);
        assertNotEquals(targetIdentity.privateIpAddress(), targetIdentity.publicIpAddress());
        assertNotNull(targetIdentity.publicIpAddress());
        assertNotEquals("127.0.0.1", targetIdentity.publicIpAddress());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertEquals(0, fetch(target, targetIdentity.privateIpAddress()).exitCode()));

        assertEquals("10.87.2.10", fetch(caller, targetIdentity.privateIpAddress()).stdout().strip());
        assertPublicSource(sdk, caller, targetIdentity.publicIpAddress());

        sdk.revokeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(ingress));
        assertDropped(caller, targetIdentity.publicIpAddress());
        sdk.authorizeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(ingress));
        assertPublicSource(sdk, caller, targetIdentity.publicIpAddress());

        sdk.detachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc));
        assertDropped(caller, targetIdentity.publicIpAddress());
        assertEquals("10.87.2.10", fetch(caller, targetIdentity.privateIpAddress()).stdout().strip());
        sdk.attachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc));
        assertPublicSource(sdk, caller, targetIdentity.publicIpAddress());

        sdk.stopInstances(request -> request.instanceIds(caller));
        await().atMost(Duration.ofSeconds(60)).until(() -> instance(sdk, caller).state().name()
                == InstanceStateName.STOPPED);
        sdk.startInstances(request -> request.instanceIds(caller));
        await().atMost(Duration.ofSeconds(60)).until(() -> instance(sdk, caller).state().name()
                == InstanceStateName.RUNNING);
        assertPublicSource(sdk, caller, targetIdentity.publicIpAddress());
    }

    private String subnet(Ec2Client sdk, String vpc, String cidr) {
        String id = sdk.createSubnet(request -> request.vpcId(vpc).cidrBlock(cidr)).subnet().subnetId();
        cleanup.addFirst(() -> sdk.deleteSubnet(request -> request.subnetId(id)));
        return id;
    }

    @Test
    void privateCallerReachesPublicInstanceThroughNatWithTheAllocatedSource() {
        Ec2Client sdk = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        cleanup.addFirst(sdk::close);
        String serverVpc = routedVpc(sdk, "10.88.0.0/16");
        String serverSubnet = subnet(sdk, serverVpc, "10.88.1.0/24");
        String serverGroup = packetGroup(sdk, serverVpc, "server");
        String target = launch(sdk, serverSubnet, serverGroup, "10.88.1.10",
                Base64.getEncoder().encodeToString("""
                        #!/bin/sh
                        set -eu
                        mkdir -p /www/cgi-bin
                        cat > /www/cgi-bin/identity <<'CGI'
                        #!/bin/sh
                        printf 'Content-Type: text/plain\\r\\n\\r\\n%s' "$REMOTE_ADDR"
                        CGI
                        chmod 755 /www/cgi-bin/identity
                        httpd -p 10.88.1.10:8080 -h /www
                        """.getBytes(StandardCharsets.UTF_8)));
        String destination = instance(sdk, target).publicIpAddress();
        assertNotNull(destination);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertEquals(0, fetch(target, "10.88.1.10").exitCode()));

        String callerVpc = routedVpc(sdk, "10.89.0.0/16");
        String publicSubnet = subnet(sdk, callerVpc, "10.89.1.0/24");
        String privateSubnet = subnet(sdk, callerVpc, "10.89.2.0/24");
        String allocation = sdk.allocateAddress(request -> request.domain("vpc")).allocationId();
        cleanup.addFirst(() -> sdk.releaseAddress(request -> request.allocationId(allocation)));
        NatGateway nat = sdk.createNatGateway(request -> request.subnetId(publicSubnet)
                .allocationId(allocation)).natGateway();
        cleanup.addFirst(() -> sdk.deleteNatGateway(request -> request.natGatewayId(nat.natGatewayId())));
        assertEquals("available", nat.stateAsString());
        String publicSource = nat.natGatewayAddresses().getFirst().publicIp();
        String table = sdk.createRouteTable(request -> request.vpcId(callerVpc)).routeTable().routeTableId();
        cleanup.addFirst(() -> sdk.deleteRouteTable(request -> request.routeTableId(table)));
        String association = sdk.associateRouteTable(request -> request.routeTableId(table)
                .subnetId(privateSubnet)).associationId();
        cleanup.addFirst(() -> sdk.disassociateRouteTable(request -> request.associationId(association)));
        sdk.createRoute(request -> request.routeTableId(table).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(nat.natGatewayId()));
        String caller = launch(sdk, privateSubnet, packetGroup(sdk, callerVpc, "caller"),
                "10.89.2.10", null, false);
        assertNull(instance(sdk, caller).publicIpAddress(), "The caller must use its subnet NAT route");
        IpPermission privateSource = addressPermission("10.89.2.10");
        sdk.authorizeSecurityGroupIngress(request -> request.groupId(serverGroup).ipPermissions(privateSource));
        assertDropped(caller, destination);
        IpPermission natSource = addressPermission(publicSource);
        sdk.authorizeSecurityGroupIngress(request -> request.groupId(serverGroup).ipPermissions(natSource));
        ContainerExec.Result response = fetch(caller, destination);
        assertEquals(0, response.exitCode(), response.summary());
        assertEquals(publicSource, response.stdout().strip(), "The server must see the NAT allocation");
        assertDropped(caller, "10.88.1.10");

        sdk.deleteRoute(request -> request.routeTableId(table).destinationCidrBlock("0.0.0.0/0"));
        assertDropped(caller, destination);
        sdk.createRoute(request -> request.routeTableId(table).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(nat.natGatewayId()));
        assertEquals(publicSource, fetch(caller, destination).stdout().strip());
        sdk.revokeSecurityGroupIngress(request -> request.groupId(serverGroup).ipPermissions(natSource));
        assertDropped(caller, destination);
    }

    private String routedVpc(Ec2Client sdk, String cidr) {
        String vpc = sdk.createVpc(request -> request.cidrBlock(cidr)).vpc().vpcId();
        cleanup.addFirst(() -> sdk.deleteVpc(request -> request.vpcId(vpc)));
        String gateway = sdk.createInternetGateway().internetGateway().internetGatewayId();
        cleanup.addFirst(() -> sdk.deleteInternetGateway(request -> request.internetGatewayId(gateway)));
        sdk.attachInternetGateway(request -> request.vpcId(vpc).internetGatewayId(gateway));
        cleanup.addFirst(() -> sdk.detachInternetGateway(request -> request.vpcId(vpc).internetGatewayId(gateway)));
        String table = sdk.describeRouteTables(request -> request.filters(
                filter -> filter.name("vpc-id").values(vpc),
                filter -> filter.name("association.main").values("true"))).routeTables().getFirst().routeTableId();
        sdk.createRoute(request -> request.routeTableId(table).destinationCidrBlock("0.0.0.0/0").gatewayId(gateway));
        return vpc;
    }

    private String packetGroup(Ec2Client sdk, String vpc, String name) {
        String group = sdk.createSecurityGroup(request -> request.vpcId(vpc)
                .groupName("nat-peer-" + name).description("NAT peer packet validation")).groupId();
        cleanup.addFirst(() -> sdk.deleteSecurityGroup(request -> request.groupId(group)));
        return group;
    }

    private static IpPermission addressPermission(String address) {
        return IpPermission.builder().ipProtocol("tcp").fromPort(8080).toPort(8080)
                .ipRanges(range -> range.cidrIp(address + "/32")).build();
    }

    private String launch(Ec2Client sdk, String subnet, String group, String address, String userData) {
        return launch(sdk, subnet, group, address, userData, true);
    }

    private String launch(Ec2Client sdk, String subnet, String group, String address, String userData,
                          boolean publicAddress) {
        String id = sdk.runInstances(request -> request.imageId("ami-public-peers").instanceType("t3.micro")
                .networkInterfaces(eni -> eni.deviceIndex(0).subnetId(subnet).groups(group)
                        .privateIpAddress(address).associatePublicIpAddress(publicAddress))
                .userData(userData).minCount(1).maxCount(1)).instances().getFirst().instanceId();
        cleanup.addFirst(() -> {
            sdk.terminateInstances(request -> request.instanceIds(id));
            await().atMost(Duration.ofSeconds(60)).until(() -> instance(sdk, id).state().name()
                    == InstanceStateName.TERMINATED);
        });
        await().atMost(Duration.ofSeconds(60)).until(() -> instance(sdk, id).state().name()
                == InstanceStateName.RUNNING);
        return id;
    }

    private static Instance instance(Ec2Client sdk, String id) {
        return sdk.describeInstances(request -> request.instanceIds(id)).reservations()
                .getFirst().instances().getFirst();
    }

    private void assertPublicSource(Ec2Client sdk, String caller, String destination) {
        ContainerExec.Result response = fetch(caller, destination);
        assertEquals(0, response.exitCode(), response.summary());
        assertEquals(instance(sdk, caller).publicIpAddress(), response.stdout().strip());
    }

    private void assertDropped(String caller, String destination) {
        long started = System.nanoTime();
        assertNotEquals(0, fetch(caller, destination).exitCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 1500);
    }

    private ContainerExec.Result fetch(String instance, String address) {
        String guest = service.describeInstances("us-east-1", List.of(instance), Map.of())
                .getFirst().getInstances().getFirst().getDockerContainerId();
        ContainerExec.Result result = ContainerExec.run(docker, guest,
                new String[]{"wget", "-q", "-T", "2", "-O", "-", "http://" + address + ":8080/cgi-bin/identity"}, 10);
        assertFalse(result.timedOut(), result.summary());
        return result;
    }
}
