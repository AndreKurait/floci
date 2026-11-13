package io.github.hectorvent.floci.services.elbv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDK-managed ALBs send real HTTP and health checks through their advertised private ENIs. */
@QuarkusTest
@TestProfile(ElbV2VpcBackendDockerIntegrationTest.NativeVpc.class)
class ElbV2VpcBackendDockerIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String PATH = "/cgi-bin/source";
    private static final String NODE_IMAGE = "public.ecr.aws/lambda/nodejs:20";

    public static class NativeVpc implements QuarkusTestProfile {
        private static final String NAMESPACE = "alb-vpc-test-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.network.security-group-enforcement.enabled", "true",
                    "floci.services.ec2.mock", "false", "floci.services.elbv2.mock", "false",
                    "floci.storage.mode", "memory", "floci.services.ec2.vpc-networks.enabled", "true",
                    "floci.services.ec2.aws-faithful-private-ip", "true",
                    "floci.services.ec2.vpc-networks.isolate-address-spaces", "true",
                    "floci.docker.resource-namespace", NAMESPACE);
        }
    }

    @Inject SecurityGroupFirewallManager firewall;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject ContainerBuilder builder;
    @Inject DockerClient docker;
    @Inject Ec2Service ec2Service;
    @Inject VpcNetworkManager vpcNetworks;
    @Inject EmulatorConfig config;
    @Inject Vertx vertx;
    @TestHTTPResource("/") URI endpoint;
    private final Deque<Executable> cleanup = new ArrayDeque<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void removeOwnedResources() {
        assertAll("ALB, fixture, ENI and VPC cleanup", cleanup);
    }

    @Test
    void sourceGroupsAndEgressControlBothHealthAndForwarding() throws Exception {
        try {
            Assumptions.assumeTrue("linux".equals(docker.infoCmd().exec().getOsType()), "Linux Docker is required");
        } catch (Exception unavailable) {
            Assumptions.abort("Linux Docker is unavailable");
        }
        assertTrue(firewall.enabled() && vpcNetworks.isolatesAddressSpaces());
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test"));
        Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        cleanup.addFirst(ec2::close);
        ElasticLoadBalancingV2Client elb = ElasticLoadBalancingV2Client.builder().endpointOverride(endpoint)
                .region(Region.of(REGION)).credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60))).build();
        cleanup.addFirst(elb::close);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String vpc = ec2.createVpc(r -> r.cidrBlock("10.83.0.0/16")).vpc().vpcId();
        cleanup.addFirst(() -> ec2.deleteVpc(r -> r.vpcId(vpc)));
        String subnetA = subnet(ec2, vpc, "10.83.1.0/24", REGION + "a");
        String subnetB = subnet(ec2, vpc, "10.83.2.0/24", REGION + "b");
        String callerGroup = group(ec2, vpc, "alb-source-" + suffix);
        String targetGroup = group(ec2, vpc, "alb-target-" + suffix);
        ec2.authorizeSecurityGroupIngress(r -> r.groupId(targetGroup).ipPermissions(IpPermission.builder()
                .ipProtocol("tcp").fromPort(8080).toPort(8080)
                .userIdGroupPairs(pair -> pair.groupId(callerGroup)).build()));
        String targetAddress = listener(ec2, subnetA, targetGroup, suffix);
        String targetArn = elb.createTargetGroup(r -> r.name("alb-target-" + suffix).protocol("HTTP")
                .port(8080).vpcId(vpc).targetType("ip").healthCheckProtocol("HTTP").healthCheckPath(PATH)
                .healthCheckIntervalSeconds(5).healthCheckTimeoutSeconds(2)
                .healthyThresholdCount(2).unhealthyThresholdCount(2)).targetGroups().getFirst().targetGroupArn();
        cleanup.addFirst(() -> elb.deleteTargetGroup(r -> r.targetGroupArn(targetArn)));
        elb.registerTargets(r -> r.targetGroupArn(targetArn).targets(target -> target.id(targetAddress).port(8080)));
        LoadBalancer balancer = elb.createLoadBalancer(r -> r.name("native-alb-" + suffix).type("application")
                .scheme("internal").subnets(subnetA, subnetB).securityGroups(callerGroup)).loadBalancers().getFirst();
        Executable deleteBalancer = () -> elb.deleteLoadBalancer(r -> r.loadBalancerArn(balancer.loadBalancerArn()));
        cleanup.addFirst(deleteBalancer);
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        elb.createListener(r -> r.loadBalancerArn(balancer.loadBalancerArn()).protocol("HTTP").port(port)
                .defaultActions(action -> action.type("forward").targetGroupArn(targetArn)));
        HttpClient http = vertx.createHttpClient();
        cleanup.addFirst(() -> http.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        health(elb, targetArn, "healthy");
        List<NetworkInterface> original = albInterfaces(ec2, vpc);
        assertEquals(2, original.size());
        assertEquals(Set.of(subnetA, subnetB), original.stream().map(NetworkInterface::subnetId).collect(Collectors.toSet()));
        Set<String> identities = original.stream().map(NetworkInterface::networkInterfaceId).collect(Collectors.toSet());
        response(http, balancer.dnsName(), port, original, true);

        elb.setSecurityGroups(r -> r.loadBalancerArn(balancer.loadBalancerArn()).securityGroups(targetGroup));
        health(elb, targetArn, "unhealthy");
        List<NetworkInterface> changed = albInterfaces(ec2, vpc);
        assertEquals(identities, changed.stream().map(NetworkInterface::networkInterfaceId).collect(Collectors.toSet()));
        assertTrue(changed.stream().allMatch(eni -> eni.groups().size() == 1
                && targetGroup.equals(eni.groups().getFirst().groupId())));
        response(http, balancer.dnsName(), port, changed, false);

        elb.setSecurityGroups(r -> r.loadBalancerArn(balancer.loadBalancerArn()).securityGroups(callerGroup));
        health(elb, targetArn, "healthy");
        response(http, balancer.dnsName(), port, original, true);
        List<IpPermission> egress = ec2.describeSecurityGroups(r -> r.groupIds(callerGroup))
                .securityGroups().getFirst().ipPermissionsEgress();
        ec2.revokeSecurityGroupEgress(r -> r.groupId(callerGroup).ipPermissions(egress));
        health(elb, targetArn, "unhealthy");
        response(http, balancer.dnsName(), port, original, false);
        ec2.authorizeSecurityGroupEgress(r -> r.groupId(callerGroup).ipPermissions(IpPermission.builder()
                .ipProtocol("tcp").fromPort(8080).toPort(8080)
                .ipRanges(range -> range.cidrIp(targetAddress + "/32")).build()));
        health(elb, targetArn, "healthy");
        response(http, balancer.dnsName(), port, original, true);
        elb.deleteLoadBalancer(r -> r.loadBalancerArn(balancer.loadBalancerArn()));
        cleanup.remove(deleteBalancer);
        assertTrue(albInterfaces(ec2, vpc).isEmpty());
        assertTrue(docker.listContainersCmd().withShowAll(true).withLabelFilter(
                Map.of(ElbV2VpcNetworkManager.BALANCER_LABEL, balancer.loadBalancerArn())).exec().isEmpty());
    }

    private void response(HttpClient client, String dns, int port, List<NetworkInterface> interfaces,
                          boolean permitted) throws Exception {
        Reply reply = client.request(HttpMethod.GET, port, "127.0.0.1", PATH)
                .compose(request -> request.putHeader("Host", dns).send())
                .compose(response -> response.body().map(body -> new Reply(response.statusCode(), body.toString())))
                .toCompletionStage().toCompletableFuture().get(16, TimeUnit.SECONDS);
        if (!permitted) {
            assertTrue(reply.status() == 502 || reply.status() == 504, reply.toString());
            return;
        }
        assertEquals(200, reply.status(), reply.body());
        JsonNode body = mapper.readTree(reply.body());
        assertTrue(interfaces.stream().anyMatch(eni -> eni.privateIpAddress().equals(body.path("source").asText())),
                "The target must observe an ALB ENI address: " + reply.body());
    }

    private void health(ElasticLoadBalancingV2Client elb, String target, String expected) {
        await().pollInterval(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(35)).untilAsserted(() ->
                assertEquals(expected, elb.describeTargetHealth(r -> r.targetGroupArn(target))
                        .targetHealthDescriptions().getFirst().targetHealth().stateAsString()));
    }

    private List<NetworkInterface> albInterfaces(Ec2Client ec2, String vpc) {
        return ec2.describeNetworkInterfaces(r -> r.filters(f -> f.name("vpc-id").values(vpc)))
                .networkInterfaces().stream().filter(eni -> eni.description() != null
                        && eni.description().startsWith("ELB ")).toList();
    }

    private String subnet(Ec2Client ec2, String vpc, String cidr, String zone) {
        String id = ec2.createSubnet(r -> r.vpcId(vpc).cidrBlock(cidr).availabilityZone(zone)).subnet().subnetId();
        cleanup.addFirst(() -> ec2.deleteSubnet(r -> r.subnetId(id)));
        return id;
    }

    private String group(Ec2Client ec2, String vpc, String name) {
        String id = ec2.createSecurityGroup(r -> r.vpcId(vpc).groupName(name).description("ALB network test")).groupId();
        cleanup.addFirst(() -> ec2.deleteSecurityGroup(r -> r.groupId(id)));
        return id;
    }

    private String listener(Ec2Client ec2, String subnet, String group, String suffix) {
        NetworkInterface eni = ec2.createNetworkInterface(r -> r.subnetId(subnet).groups(group)).networkInterface();
        String eniId = eni.networkInterfaceId();
        cleanup.addFirst(() -> ec2.deleteNetworkInterface(r -> r.networkInterfaceId(eniId)));
        RequestScopes.runAs(ACCOUNT, REGION, () -> ec2Service.holdNetworkInterfaceForService(REGION, eniId));
        cleanup.addFirst(() -> RequestScopes.runAs(ACCOUNT, REGION,
                () -> ec2Service.releaseNetworkInterfaceFromService(REGION, eniId)));
        SecurityGroupFirewallManager.Namespace namespace = firewall.createNamespace("fixture", "alb-target-" + suffix,
                ACCOUNT, REGION, config.services().dockerNetwork(), Map.of());
        cleanup.addFirst(() -> firewall.unregister(eniId));
        cleanup.addFirst(() -> lifecycle.removeIfExistsStrict(namespace.helperId()));
        assertTrue(RequestScopes.callAs(ACCOUNT, REGION, () -> vpcNetworks.attach(
                REGION, eni.vpcId(), subnet, namespace.helperId(), eni.privateIpAddress())).isPresent());
        List<SecurityGroup> groups = RequestScopes.callAs(ACCOUNT, REGION, () ->
                ec2Service.describeSecurityGroups(REGION, List.of(group), List.of(), Map.of()));
        firewall.register(new SecurityGroupNftCompiler.Endpoint(ACCOUNT, REGION, eni.vpcId(), eniId,
                eni.privateIpAddress(), namespace.transportAddress(), Set.of(group), groups, List.of(),
                false, Set.of(eni.privateIpAddress())), namespace.helperId(), Map.of());
        String container = lifecycle.createAndStart(builder.newContainer(NODE_IMAGE)
                .withName("floci-alb-target-" + suffix)
                .withNetworkMode("container:" + namespace.helperId())
                .withEntrypoint(List.of("/var/lang/bin/node"))
                .withCmd(List.of("-e", """
                        require('http').createServer((request, response) => {
                          response.setHeader('Content-Type', 'application/json');
                          response.setHeader('Connection', 'close');
                          response.end(JSON.stringify({source: request.socket.remoteAddress}));
                        }).listen(8080, '0.0.0.0');
                        """)).build()).containerId();
        cleanup.addFirst(() -> lifecycle.stopAndRemoveStrict(container, null, 1));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ContainerExec.Result listening = ContainerExec.run(docker, namespace.helperId(),
                    new String[]{"nc", "-z", "-w", "1", "127.0.0.1", "8080"}, 2);
            assertEquals(0, listening.exitCode(), listening.summary());
        });
        return eni.privateIpAddress();
    }

    private record Reply(int status, String body) {}
}
