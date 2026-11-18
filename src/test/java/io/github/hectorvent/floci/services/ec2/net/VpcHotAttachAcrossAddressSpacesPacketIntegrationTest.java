package io.github.hectorvent.floci.services.ec2.net;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.NetworkNamespaceLease;
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
import software.amazon.awssdk.services.ec2.model.UserIdGroupPair;

import java.net.URI;
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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real packets through a hot-attached interface of another, overlapping VPC in a protected namespace:
 * the attached interface's own security groups admit and deny on its device, replies leave through it,
 * and the instance reaches that VPC only through it, while its primary interface's rules stay as they were.
 */
@QuarkusTest
@TestProfile(VpcHotAttachAcrossAddressSpacesPacketIntegrationTest.IsolatedHotAttach.class)
@Timeout(300)
class VpcHotAttachAcrossAddressSpacesPacketIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String SERVICE = "hot-attach-packet-test";

    public static class IsolatedHotAttach implements QuarkusTestProfile {
        private static final String NAMESPACE = "hot-attach-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(Map.of(
                    "floci.services.ec2.mock", "false",
                    "floci.services.ec2.vpc-networks.enabled", "true",
                    "floci.services.ec2.vpc-networks.isolate-address-spaces", "true",
                    "floci.services.ec2.vpc-networks.hot-attach-across-address-spaces", "true",
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
        assumeTrue(DockerAvailability.isAvailable(), "Docker is required for hot-attach packets");
        ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        cleanup.addFirst(ec2::close);
    }

    @AfterEach
    void retireOwnedResources() {
        assertAll("Hot-attach packet resources", cleanup);
        assertTrue(docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(
                ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                ContainerStorageHelper.SERVICE_LABEL, SERVICE)).exec().isEmpty());
    }

    private record Vpc(String vpcId, String subnetId) {}

    private record Member(String helper, String eni, String address, String transport) {}

    private Vpc vpc(String cidr, String subnetCidr) {
        String vpc = ec2.createVpc(request -> request.cidrBlock(cidr)).vpc().vpcId();
        cleanup.addFirst(() -> ec2.deleteVpc(request -> request.vpcId(vpc)));
        String subnet = ec2.createSubnet(request -> request.vpcId(vpc).cidrBlock(subnetCidr)
                .availabilityZone("us-east-1a")).subnet().subnetId();
        cleanup.addFirst(() -> ec2.deleteSubnet(request -> request.subnetId(subnet)));
        return new Vpc(vpc, subnet);
    }

    /** A group whose members accept each other; every group keeps the default all-egress rule. */
    private String group(Vpc vpc, String name) {
        String group = ec2.createSecurityGroup(request -> request.vpcId(vpc.vpcId()).groupName(name)
                .description("Hot-attach packet test")).groupId();
        cleanup.addFirst(() -> ec2.deleteSecurityGroup(request -> request.groupId(group)));
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(IpPermission.builder()
                .ipProtocol("-1").userIdGroupPairs(UserIdGroupPair.builder().groupId(group).build()).build()));
        return group;
    }

    private String eni(Vpc vpc, String group, String address) {
        String eni = ec2.createNetworkInterface(request -> request.subnetId(vpc.subnetId()).groups(group)
                .privateIpAddress(address)).networkInterface().networkInterfaceId();
        cleanup.addFirst(() -> ec2.deleteNetworkInterface(request -> request.networkInterfaceId(eni)));
        RequestScopes.runAs(ACCOUNT, REGION, () -> service.holdNetworkInterfaceForService(REGION, eni));
        cleanup.addFirst(() -> RequestScopes.runAs(ACCOUNT, REGION,
                () -> service.releaseNetworkInterfaceFromService(REGION, eni)));
        return eni;
    }

    private NetworkInterface describe(String eni) {
        return RequestScopes.callAs(ACCOUNT, REGION, () -> service.describeNetworkInterfaces(REGION, List.of(eni),
                Map.of()).networkInterfaces().getFirst());
    }

    private List<SecurityGroup> groups(String group) {
        return RequestScopes.callAs(ACCOUNT, REGION,
                () -> service.describeSecurityGroups(REGION, List.of(group), List.of(), Map.of()));
    }

    /** A protected namespace whose only interface is {@code eni} in {@code vpc}. */
    private Member member(Vpc vpc, String group, String address) {
        String eni = eni(vpc, group, address);
        SecurityGroupFirewallManager.Namespace namespace = RequestScopes.callAs(ACCOUNT, REGION,
                () -> firewall.createNamespace(SERVICE, UUID.randomUUID().toString(), ACCOUNT, REGION,
                        Optional.empty(), Map.of()));
        String helper = namespace.helperId();
        NetworkNamespaceLease lease = new NetworkNamespaceLease(helper, docker.inspectContainerCmd(helper).exec().getName());
        cleanup.addFirst(() -> RequestScopes.runAs(ACCOUNT, REGION, () -> {
            lease.requireUnused(docker);
            firewall.unregister(eni);
            lifecycle.removeIfExistsStrict(helper);
            routing.unregister(ACCOUNT, REGION, eni);
        }));
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            assertTrue(networks.attach(REGION, vpc.vpcId(), vpc.subnetId(), helper, address).isPresent());
            firewall.register(new SecurityGroupNftCompiler.Endpoint(ACCOUNT, REGION, vpc.vpcId(), eni, address,
                    namespace.transportAddress(), Set.of(group), groups(group), List.of(), false, Set.of(address)),
                    helper, Map.of());
        });
        return new Member(helper, eni, address, namespace.transportAddress());
    }

    @Test
    void anAttachedInterfaceOfAnOverlappingVpcIsItsOwnDeviceWithItsOwnGroups() {
        // Two VPCs of the account with the same range, as isolated address spaces allow.
        Vpc home = vpc("10.87.0.0/16", "10.87.1.0/24");
        Vpc customer = vpc("10.87.0.0/16", "10.87.2.0/24");
        String homeGroup = group(home, "home");
        String customerGroup = group(customer, "customer");
        String strangerGroup = group(customer, "stranger");
        Member instance = member(home, homeGroup, "10.87.1.10");
        Member client = member(customer, customerGroup, "10.87.2.20");
        Member stranger = member(customer, strangerGroup, "10.87.2.30");
        String attachedEni = eni(customer, customerGroup, "10.87.2.10");
        NetworkInterface attached = describe(attachedEni);
        String primaryRules = execute(instance.helper(), "nft", "list", "chain", "inet", "floci_sg", "ingress").stdout();

        String device = RequestScopes.callAs(ACCOUNT, REGION, () -> networks.attachInterfaceAcrossAddressSpaces(REGION,
                customer.vpcId(), customer.subnetId(), instance.helper(), ACCOUNT, "10.87.2.10",
                attached.getMacAddress()).orElseThrow());
        Runnable detach = () -> RequestScopes.runAs(ACCOUNT, REGION, () -> {
            firewall.detachDevice(instance.eni(), attachedEni);
            networks.detachInterfaceAcrossAddressSpaces(REGION, customer.vpcId(), instance.helper(), "10.87.2.10",
                    attached.getMacAddress());
        });
        cleanup.addFirst(detach::run);
        RequestScopes.runAs(ACCOUNT, REGION, () -> firewall.attachDevice(instance.eni(),
                new SecurityGroupNftCompiler.Endpoint(ACCOUNT, REGION, customer.vpcId(), attachedEni, "10.87.2.10",
                        "10.87.2.10", Set.of(customerGroup), groups(customerGroup), List.of(), false,
                        Set.of("10.87.2.10"), List.of(), Map.of()), device, Map.of()));

        // The device carries the interface's own MAC and address.
        String link = execute(instance.helper(), "ip", "-o", "address", "show", "dev", device).stdout();
        assertTrue(link.contains("inet 10.87.2.10/24"), link);
        assertTrue(execute(instance.helper(), "cat", "/sys/class/net/" + device + "/address").stdout().trim()
                .equalsIgnoreCase(attached.getMacAddress()));

        listen(instance.helper(), "10.87.2.10", 8080);
        listen(client.helper(), "10.87.2.20", 9090);
        // The attached interface's group admits its member and nobody else of its VPC.
        assertEquals("ok", request(client.helper(), null, "10.87.2.10", 8080).stdout());
        assertNotEquals(0, request(stranger.helper(), null, "10.87.2.10", 8080).exitCode());
        // Replies to the attached address leave through its device; the overlapping range stays the home VPC's.
        assertTrue(execute(instance.helper(), "ip", "-4", "route", "get", "10.87.2.20", "from", "10.87.2.10").stdout()
                .contains(" dev " + device + " "));
        assertFalse(execute(instance.helper(), "ip", "-4", "route", "get", "10.87.2.20").stdout()
                .contains(" dev " + device + " "));
        // The instance reaches the customer VPC from its attached address only.
        assertEquals("ok", request(instance.helper(), "10.87.2.10", "10.87.2.20", 9090).stdout());
        assertNotEquals(0, request(instance.helper(), null, "10.87.2.20", 9090).exitCode());
        // The primary interface's own rules are the ones it had, entered after the device's jump.
        String withDevice = execute(instance.helper(), "nft", "list", "chain", "inet", "floci_sg", "ingress").stdout();
        assertEquals(strip(primaryRules), strip(withDevice.lines()
                .filter(line -> !line.contains("\"" + device + "\"") && !line.contains("10.87.2.10")).toList()));

        detach.run();
        cleanup.removeFirst();

        assertNotEquals(0, request(client.helper(), null, "10.87.2.10", 8080).exitCode());
        assertFalse(execute(instance.helper(), "ip", "-o", "link").stdout().toLowerCase()
                .contains(attached.getMacAddress().toLowerCase()));
        assertTrue(execute(instance.helper(), "ip", "-4", "rule").stdout().lines()
                .noneMatch(line -> line.contains("10.87.2.10")));
    }

    private static List<String> strip(String rules) {
        return strip(rules.lines().toList());
    }

    /** nft's listing, without its handles and counters, which change with every install. */
    private static List<String> strip(List<String> lines) {
        return lines.stream().map(line -> line.replaceAll(" # handle \\d+", "")
                        .replaceAll("counter packets \\d+ bytes \\d+", "counter").strip())
                .filter(line -> !line.isEmpty()).toList();
    }

    private void listen(String helper, String address, int port) {
        assertEquals(0, execute(helper, "sh", "-c", "socat TCP4-LISTEN:$2,bind=$1,reuseaddr,fork"
                + " SYSTEM:'printf ok' >/tmp/listen-$2.log 2>&1 &", "listen", address, String.valueOf(port)).exitCode());
        await().atMost(Duration.ofSeconds(5)).until(() ->
                execute(helper, "ss", "-H", "-lnt").stdout().contains(address + ":" + port + " "));
    }

    /** One TCP exchange; the listeners answer "ok" without reading. */
    private ContainerExec.Result request(String helper, String source, String address, int port) {
        String bind = source == null ? "" : ",bind=" + source;
        return execute(helper, "sh", "-c", "printf 'hello\\n' | socat -T 3 - TCP4:$1:$2,connect-timeout=2$3",
                "connect", address, String.valueOf(port), bind);
    }

    private ContainerExec.Result execute(String helper, String... command) {
        return ContainerExec.run(docker, helper, command, 8).throwIfTimedOut(helper);
    }
}
