package io.github.hectorvent.floci.services.ec2.net;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.NetworkNamespaceLease;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpPermission;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real packets from an external client into a protected public frontend transport. */
@QuarkusTest
@TestProfile(VpcExternalFrontendPacketIntegrationTest.PublicFrontend.class)
@Timeout(180)
class VpcExternalFrontendPacketIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String SERVICE = "frontend-packet-test";

    public static class PublicFrontend implements QuarkusTestProfile {
        private static final String NAMESPACE = "external-frontend-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(Map.of(
                    "floci.services.ec2.mock", "false",
                    "floci.services.ec2.vpc-networks.enabled", "true",
                    "floci.services.ec2.aws-faithful-private-ip", "true",
                    "floci.network.security-group-enforcement.enabled", "true",
                    "floci.network.vpc-routing.enabled", "true",
                    "floci.docker.resource-namespace", NAMESPACE));
            String dnsAddress = System.getenv("FLOCI_TEST_HOST_DNS_ADDRESS");
            if (dnsAddress != null && !dnsAddress.isBlank()) {
                overrides.put("floci.dns.listen-address", dnsAddress);
            }
            return overrides;
        }
    }

    @Inject EmulatorConfig config;
    @Inject DockerClient docker;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject Ec2Service service;
    @Inject VpcNetworkManager networks;
    @Inject SecurityGroupFirewallManager firewall;
    @Inject VpcRoutingManager routing;
    @TestHTTPResource URI endpoint;
    private final Deque<Executable> cleanup = new ArrayDeque<>();
    private Ec2Client ec2;

    @BeforeEach
    void prepare() throws InterruptedException {
        assumeTrue(DockerAvailability.isAvailable(), "Docker is required for public frontend packets");
        ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        cleanup.addFirst(ec2::close);
    }

    @AfterEach
    void retireOwnedResources() {
        assertAll("Public frontend packet resources", cleanup);
        assertTrue(docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(
                ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                ContainerStorageHelper.SERVICE_LABEL, SERVICE)).exec().isEmpty());
    }

    @Test
    void externalPacketsRequireTheListenerSecurityGroupAndNativeInternetRoute() {
        String vpc = ec2.createVpc(request -> request.cidrBlock("10.84.0.0/16")).vpc().vpcId();
        cleanup.addFirst(() -> ec2.deleteVpc(request -> request.vpcId(vpc)));
        String subnet = ec2.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.84.1.0/24")).subnet().subnetId();
        cleanup.addFirst(() -> ec2.deleteSubnet(request -> request.subnetId(subnet)));
        String group = ec2.createSecurityGroup(request -> request.vpcId(vpc).groupName("frontend")
                .description("Public frontend packet test")).groupId();
        cleanup.addFirst(() -> ec2.deleteSecurityGroup(request -> request.groupId(group)));
        String gateway = ec2.createInternetGateway().internetGateway().internetGatewayId();
        cleanup.addFirst(() -> ec2.deleteInternetGateway(request -> request.internetGatewayId(gateway)));
        ec2.attachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc));
        cleanup.addFirst(() -> ec2.detachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc)));
        String table = ec2.describeRouteTables(request -> request.filters(filter -> filter.name("vpc-id").values(vpc)))
                .routeTables().getFirst().routeTableId();
        internetRoute(table, gateway);
        cleanup.addFirst(() -> deleteRouteIfPresent(table, "0.0.0.0/0"));
        String eni = ec2.createNetworkInterface(request -> request.subnetId(subnet).groups(group))
                .networkInterface().networkInterfaceId();
        cleanup.addFirst(() -> ec2.deleteNetworkInterface(request -> request.networkInterfaceId(eni)));
        RequestScopes.runAs(ACCOUNT, REGION, () -> service.holdNetworkInterfaceForService(REGION, eni));
        cleanup.addFirst(() -> RequestScopes.runAs(ACCOUNT, REGION,
                () -> service.releaseNetworkInterfaceFromService(REGION, eni)));
        SecurityGroupFirewallManager.Namespace namespace = RequestScopes.callAs(ACCOUNT, REGION,
                () -> firewall.createNamespace(SERVICE, UUID.randomUUID().toString(), ACCOUNT, REGION,
                        Optional.empty(), Map.of()));
        String helper = namespace.helperId();
        NetworkNamespaceLease lease = new NetworkNamespaceLease(helper, docker.inspectContainerCmd(helper).exec().getName());
        cleanup.addFirst(() -> RequestScopes.runAs(ACCOUNT, REGION, () -> {
            lease.requireUnused(docker);
            lifecycle.removeIfExistsStrict(helper);
            firewall.unregister(eni);
            routing.unregister(ACCOUNT, REGION, eni);
        }));
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            NetworkInterface networkInterface = service.describeNetworkInterfaces(REGION, List.of(eni), Map.of())
                    .networkInterfaces().getFirst();
            assertTrue(networks.attach(REGION, vpc, subnet, helper, networkInterface.getPrivateIpAddress()).isPresent());
            routing.attachPublicFrontend(ACCOUNT, REGION, networkInterface, helper);
            List<SecurityGroup> groups = service.describeSecurityGroups(REGION, List.of(group), List.of(), Map.of());
            firewall.register(new SecurityGroupNftCompiler.Endpoint(ACCOUNT, REGION, vpc, eni,
                    networkInterface.getPrivateIpAddress(), namespace.transportAddress(), Set.of(group), groups,
                    List.of(), false, Set.of(networkInterface.getPrivateIpAddress())), helper, Map.of());
        });
        String address = routing.publicFrontendTransport(ACCOUNT, REGION, eni).orElseThrow();
        ContainerNetwork publicNetwork = docker.inspectContainerCmd(helper).exec().getNetworkSettings().getNetworks()
                .values().stream().filter(network -> address.equals(network.getIpAddress())).findFirst().orElseThrow();
        String caller = docker.createContainerCmd(config.network().securityGroupEnforcement().helperImage())
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode(publicNetwork.getNetworkID()))
                .withEntrypoint("sleep").withCmd("300").withLabels(Map.of(
                        ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                        ContainerStorageHelper.SERVICE_LABEL, SERVICE)).exec().getId();
        cleanup.addFirst(() -> lifecycle.removeIfExistsStrict(caller));
        docker.startContainerCmd(caller).exec();
        String callerAddress = docker.inspectContainerCmd(caller).exec().getNetworkSettings().getNetworks()
                .values().stream().filter(network -> publicNetwork.getNetworkID().equals(network.getNetworkID()))
                .findFirst().orElseThrow().getIpAddress();
        startListeners(helper, address);
        routing.publishFrontendListeners(ACCOUNT, REGION, eni, helper, Set.of(8080));
        denied(caller, helper, address, 8080, 0);
        IpPermission ingress = IpPermission.builder().ipProtocol("tcp").fromPort(8080).toPort(8081)
                .ipRanges(range -> range.cidrIp(callerAddress + "/32")).build();
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(ingress));
        allowed(caller, helper, address, 1);
        denied(caller, helper, address, 8081, 1);

        ec2.deleteRoute(request -> request.routeTableId(table).destinationCidrBlock("0.0.0.0/0"));
        denied(caller, helper, address, 8080, 1);
        internetRoute(table, gateway);
        allowed(caller, helper, address, 2);
        String allocation = ec2.allocateAddress(request -> request.domain("vpc")).allocationId();
        cleanup.addFirst(() -> ec2.releaseAddress(request -> request.allocationId(allocation)));
        String nat = ec2.createNatGateway(request -> request.subnetId(subnet).allocationId(allocation))
                .natGateway().natGatewayId();
        cleanup.addFirst(() -> ec2.deleteNatGateway(request -> request.natGatewayId(nat)));
        ec2.createRoute(request -> request.routeTableId(table).destinationCidrBlock(callerAddress + "/32")
                .natGatewayId(nat));
        cleanup.addFirst(() -> deleteRouteIfPresent(table, callerAddress + "/32"));
        denied(caller, helper, address, 8080, 2);
        ec2.deleteRoute(request -> request.routeTableId(table).destinationCidrBlock(callerAddress + "/32"));
        allowed(caller, helper, address, 3);
        ec2.revokeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(ingress));
        denied(caller, helper, address, 8080, 3);
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(ingress));
        routing.publishFrontendListeners(ACCOUNT, REGION, eni, helper, Set.of());
        denied(caller, helper, address, 8080, 3);
        routing.publishFrontendListeners(ACCOUNT, REGION, eni, helper, Set.of(8080));
        allowed(caller, helper, address, 4);
    }

    private void startListeners(String helper, String address) {
        byte[] response = """
                #!/bin/sh
                IFS= read -r request || exit 0
                count=$(cat /tmp/frontend-count)
                printf '%s' "$((count + 1))" > /tmp/frontend-count
                printf 'HTTP/1.1 200 OK\\r\\nContent-Length: 2\\r\\nConnection: close\\r\\n\\r\\nok'
                """.getBytes(StandardCharsets.UTF_8);
        RetryingTarCopier.copyBytes(docker, helper, "/tmp", "frontend-response.sh", response, 0600);
        assertEquals(0, execute(helper, "sh", "-c", "printf '0' > /tmp/frontend-count;"
                + " for port in 8080 8081; do socat TCP4-LISTEN:$port,bind=$1,reuseaddr,fork"
                + " 'EXEC:sh /tmp/frontend-response.sh' >/tmp/frontend-$port.log 2>&1 & done",
                "frontend-listeners", address).exitCode());
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            String listening = execute(helper, "ss", "-H", "-lnt").stdout();
            return listening.contains(address + ":8080 ") && listening.contains(address + ":8081 ");
        });
    }

    private void allowed(String caller, String helper, String address, int count) {
        ContainerExec.Result result = request(caller, address, 8080);
        assertEquals(0, result.exitCode(), result.summary());
        assertEquals("ok", result.stdout());
        assertEquals(String.valueOf(count), execute(helper, "cat", "/tmp/frontend-count").stdout());
    }

    private void denied(String caller, String helper, String address, int port, int count) {
        ContainerExec.Result result = request(caller, address, port);
        assertNotEquals(0, result.exitCode(), result.summary());
        assertEquals(String.valueOf(count), execute(helper, "cat", "/tmp/frontend-count").stdout());
    }

    private ContainerExec.Result request(String caller, String address, int port) {
        return execute(caller, "curl", "--silent", "--show-error", "--connect-timeout", "1",
                "--max-time", "2", "http://" + address + ":" + port + "/");
    }

    private ContainerExec.Result execute(String helper, String... command) {
        return ContainerExec.run(docker, helper, command, 6).throwIfTimedOut(helper);
    }

    private void internetRoute(String table, String gateway) {
        ec2.createRoute(request -> request.routeTableId(table).destinationCidrBlock("0.0.0.0/0").gatewayId(gateway));
    }

    private void deleteRouteIfPresent(String table, String cidr) {
        boolean present = ec2.describeRouteTables(request -> request.routeTableIds(table)).routeTables().stream()
                .flatMap(routeTable -> routeTable.routes().stream()).anyMatch(route -> cidr.equals(route.destinationCidrBlock()));
        if (present) {
            ec2.deleteRoute(request -> request.routeTableId(table).destinationCidrBlock(cidr));
        }
    }
}
