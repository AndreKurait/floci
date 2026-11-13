package io.github.hectorvent.floci.services.elb;

import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.elasticloadbalancing.model.ElasticLoadBalancingException;
import software.amazon.awssdk.services.elasticloadbalancing.model.Instance;
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(ElbClassicMultiAzPacketIntegrationTest.MultiAzRouting.class)
class ElbClassicMultiAzPacketIntegrationTest extends ElbClassicPublicNatPacketIntegrationTest {
    private static final String HOST = "multi-az.example.test";

    public static class MultiAzRouting extends PublicRouting {
        private static final String NAMESPACE = "classic-multi-az-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.docker.resource-namespace", NAMESPACE);
            overrides.put("floci.services.ec2.vpc-networks.isolate-address-spaces", "true");
            return overrides;
        }
    }

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void threeProtectedNodesHonorZoneRoutingNatIngressAndSubnetRemoval() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Ec2Client callerAccount = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("111111111111", "test"))).build();
        cleanup.addFirst(callerAccount::close);
        Network callerVpc = network(callerAccount, "10.90.0.0/16", "10.90.200.0/24");
        String callerSubnet = subnet(callerAccount, callerVpc.vpc(), "10.90.201.0/24");
        String callerRoutes = routes(callerAccount, callerVpc.vpc(), callerSubnet);
        NatGateway nat = nat(callerAccount, callerVpc.publicSubnet());
        String natAddress = nat.natGatewayAddresses().getFirst().publicIp();
        callerAccount.createRoute(request -> request.routeTableId(callerRoutes).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(nat.natGatewayId()));
        String callerGroup = group(callerAccount, callerVpc.vpc(), "caller-" + suffix);
        String caller = launch(callerAccount, callerSubnet, callerGroup, "10.90.201.10", null);
        String callerHelper = namespace(instances.findInstanceById("111111111111", caller)
                .getDockerContainerId()).getId();

        String vpc = ec2.createVpc(request -> request.cidrBlock("10.90.0.0/16")).vpc().vpcId();
        cleanup.addFirst(() -> ec2.deleteVpc(request -> request.vpcId(vpc)));
        String gateway = ec2.createInternetGateway().internetGateway().internetGatewayId();
        cleanup.addFirst(() -> ec2.deleteInternetGateway(request -> request.internetGatewayId(gateway)));
        ec2.attachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc));
        cleanup.addFirst(() -> ec2.detachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc)));
        String frontendGroup = group(vpc, "frontend-" + suffix);
        String backendGroup = group(vpc, "backend-" + suffix);
        String blockedBackendGroup = group(vpc, "blocked-backend-" + suffix);
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(backendGroup)
                .ipPermissions(fromGroup(8080, frontendGroup)));
        IpPermission callerAccess = fromAddress(443, natAddress);
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(frontendGroup).ipPermissions(callerAccess));
        List<Node> nodes = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            String zone = "us-east-1" + (char) ('a' + index - 1);
            String frontendSubnet = zonalSubnet(vpc, "10.90." + index + ".0/24", zone);
            String routeTable = routes(ec2, vpc, frontendSubnet);
            ec2.createRoute(request -> request.routeTableId(routeTable).destinationCidrBlock("0.0.0.0/0")
                    .gatewayId(gateway));
            String backendSubnet = zonalSubnet(vpc, "10.90." + (10 + index) + ".0/24", zone);
            String backend = launch(backendSubnet, backendGroup, "10.90." + (10 + index) + ".10", application(zone));
            nodes.add(new Node(zone, frontendSubnet, backendSubnet, backend, localInstance(backend).getDockerContainerId()));
        }
        String certificateArn = certificate(callerHelper, suffix);
        String name = "classic-multi-az-" + suffix;
        String hostname = elb.createLoadBalancer(request -> request.loadBalancerName(name).scheme("internet-facing")
                .subnets(nodes.stream().map(Node::frontendSubnet).toList()).securityGroups(frontendGroup)
                .listeners(Listener.builder().protocol("HTTPS").loadBalancerPort(443).instanceProtocol("HTTP")
                        .instancePort(8080).sslCertificateId(certificateArn).build())).dnsName();
        cleanup.addFirst(() -> elb.deleteLoadBalancer(request -> request.loadBalancerName(name)));
        elb.configureHealthCheck(request -> request.loadBalancerName(name).healthCheck(check -> check
                .target("HTTP:8080/health").interval(5).timeout(2).healthyThreshold(2).unhealthyThreshold(2)));
        elb.registerInstancesWithLoadBalancer(request -> request.loadBalancerName(name)
                .instances(nodes.stream().map(node -> Instance.builder().instanceId(node.instance()).build()).toList()));
        awaitHealthy(name, nodes);
        List<String> addresses = addresses(hostname, 3);
        assertEquals(Set.copyOf(addresses), wireAddresses(callerHelper, hostname));
        Map<String, Node> bySubnet = nodes.stream().collect(Collectors.toMap(Node::frontendSubnet, Function.identity()));
        Map<InetAddress, Node> byPeer = interfaces(name).stream().collect(Collectors.toMap(
                eni -> InetAddress.ofLiteral(eni.privateIpAddress()), eni -> bySubnet.get(eni.subnetId())));
        assertEquals(Set.copyOf(bySubnet.keySet()),
                interfaces(name).stream().map(NetworkInterface::subnetId).collect(Collectors.toSet()));
        Set<InetAddress> observed = new HashSet<>();
        for (String address : addresses) {
            for (int request = 0; request < 3; request++) {
                Reply reply = fetch(callerHelper, address, natAddress);
                observed.add(reply.peer());
                assertEquals(byPeer.get(reply.peer()).zone(), reply.zone(),
                        "Without cross-zone routing each protected node must use its own zone");
            }
        }
        assertEquals(byPeer.keySet(), observed);
        Map<String, Integer> before = requestCounts(nodes);
        ec2.revokeSecurityGroupIngress(request -> request.groupId(frontendGroup).ipPermissions(callerAccess));
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(backendGroup).ipPermissions(callerAccess));
        for (String address : addresses) {
            long started = System.nanoTime();
            ContainerExec.Result blocked = request(callerHelper, address);
            assertNotEquals(0, blocked.exitCode(), "Backend ingress must not open any frontend node");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 1500, blocked.summary());
        }
        assertEquals(before, requestCounts(nodes), "Blocked TLS connections must never reach an application");
        awaitHealthy(name, nodes);
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(frontendGroup).ipPermissions(callerAccess));
        for (String address : addresses) {
            Reply reply = fetch(callerHelper, address, natAddress);
            assertEquals(byPeer.get(reply.peer()).zone(), reply.zone());
        }

        elb.modifyLoadBalancerAttributes(request -> request.loadBalancerName(name)
                .loadBalancerAttributes(attributes -> attributes.crossZoneLoadBalancing(value -> value.enabled(true))));
        verifyCrossZoneTargets(name, hostname, callerHelper, natAddress, nodes, backendGroup, blockedBackendGroup);
        assertEquals(Set.copyOf(addresses(hostname, 3)), wireAddresses(callerHelper, hostname));
        Set<String> beforeRejectedAttach = Set.copyOf(addresses(hostname, 3));
        assertThrows(ElasticLoadBalancingException.class, () -> elb.attachLoadBalancerToSubnets(request ->
                request.loadBalancerName(name).subnets(nodes.getFirst().backendSubnet())));
        assertEquals(beforeRejectedAttach, Set.copyOf(addresses(hostname, 3)));

        Node removed = nodes.getLast();
        elb.detachLoadBalancerFromSubnets(request -> request.loadBalancerName(name).subnets(removed.frontendSubnet()));
        List<Node> retained = nodes.subList(0, 2);
        awaitHealthy(name, retained);
        int removedCount = requestCounts(List.of(removed)).get(removed.instance());
        verifyCrossZoneTargets(name, hostname, callerHelper, natAddress, retained, backendGroup, blockedBackendGroup);
        assertEquals(Set.copyOf(addresses(hostname, 2)), wireAddresses(callerHelper, hostname));
        assertEquals(removedCount, requestCounts(List.of(removed)).get(removed.instance()));
        assertEquals("OutOfService", elb.describeInstanceHealth(request -> request.loadBalancerName(name)
                .instances(Instance.builder().instanceId(removed.instance()).build())).instanceStates().getFirst().state());
        assertEquals(retained.stream().map(Node::frontendSubnet).collect(Collectors.toSet()),
                interfaces(name).stream().map(NetworkInterface::subnetId).collect(Collectors.toSet()));
        assertThrows(ElasticLoadBalancingException.class, () -> elb.detachLoadBalancerFromSubnets(request ->
                request.loadBalancerName(name).subnets(retained.stream().map(Node::frontendSubnet).toList())));
        assertEquals(2, addresses(hostname, 2).size());
        elb.deleteLoadBalancer(request -> request.loadBalancerName(name));
        assertTrue(dns.resolveIpv4(hostname).isEmpty());
        assertTrue(interfaces(name).isEmpty());
        cleanup.addLast(() -> assertTrue(docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(
                ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                ContainerStorageHelper.SERVICE_LABEL, "vpc-nat")).exec().isEmpty()));
    }

    private String zonalSubnet(String vpc, String cidr, String zone) {
        String subnet = ec2.createSubnet(request -> request.vpcId(vpc).cidrBlock(cidr).availabilityZone(zone))
                .subnet().subnetId();
        cleanup.addFirst(() -> ec2.deleteSubnet(request -> request.subnetId(subnet)));
        return subnet;
    }

    private String certificate(String helper, String suffix) {
        CertificateGenerator.GeneratedCertificate certificate = new CertificateGenerator().generateSelfSignedCertificate(
                HOST, List.of(), KeyAlgorithm.EC_prime256v1);
        String name = "classic-multi-az-" + suffix;
        String arn = iam.uploadServerCertificate(request -> request.serverCertificateName(name)
                .certificateBody(certificate.certificatePem()).privateKey(certificate.privateKeyPem()))
                .serverCertificateMetadata().arn();
        cleanup.addFirst(() -> iam.deleteServerCertificate(request -> request.serverCertificateName(name)));
        RetryingTarCopier.copyBytes(docker, helper, "/tmp", "frontend-ca.pem",
                certificate.certificatePem().getBytes(StandardCharsets.UTF_8), 0600);
        return arn;
    }

    private void awaitHealthy(String name, List<Node> nodes) {
        try {
            await().atMost(Duration.ofSeconds(35)).until(() ->
                    nodes.stream().allMatch(node -> healthy(name, node.instance())));
        } catch (ConditionTimeoutException failure) {
            StringBuilder evidence = new StringBuilder("Current load balancer: ")
                    .append(elb.describeLoadBalancers(request -> request.loadBalancerNames(name)))
                    .append("\nCurrent health: ")
                    .append(elb.describeInstanceHealth(request -> request.loadBalancerName(name)));
            for (Node node : nodes) {
                evidence.append("\nBackend ").append(node).append('\n')
                        .append(ec2.describeInstances(request -> request.instanceIds(node.instance())))
                        .append('\n').append(exec(node.guest(), "sh", "-c",
                                "wget -qO- http://127.0.0.1:8080/health; ps").summary());
            }
            for (Container frontend : docker.listContainersCmd().withLabelFilter(Map.of(
                    ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                    ContainerStorageHelper.SERVICE_LABEL, "elb-classic",
                    "io.floci.security-group.helper", "true")).exec()) {
                for (Node node : nodes) {
                    String address = ec2.describeInstances(request -> request.instanceIds(node.instance()))
                            .reservations().getFirst().instances().getFirst().privateIpAddress();
                    evidence.append("\nDirect frontend probe ").append(frontend.getId())
                            .append(" -> ").append(address).append(' ')
                            .append(exec(frontend.getId(), "curl", "--silent", "--show-error", "--max-time", "2",
                                    "--output", "/dev/null", "--write-out", "%{http_code}",
                                    "http://" + address + ":8080/health"));
                }
            }
            throw new AssertionError(evidence.append(networkDiagnostics()).toString(), failure);
        }
    }

    private void verifyCrossZoneTargets(String name, String hostname, String helper, String natAddress,
                                        List<Node> nodes, String backendGroup, String blockedGroup) {
        for (Node selected : nodes) {
            for (Node node : nodes) {
                ec2.modifyInstanceAttribute(request -> request.instanceId(node.instance())
                        .groups(node.equals(selected) ? backendGroup : blockedGroup));
            }
            await().atMost(Duration.ofSeconds(35)).until(() -> nodes.stream()
                    .allMatch(node -> healthy(name, node.instance()) == node.equals(selected)));
            await().atMost(Duration.ofSeconds(35)).untilAsserted(() -> {
                for (String address : addresses(hostname, nodes.size())) {
                    assertEquals(selected.zone(), fetch(helper, address, natAddress).zone(),
                            "Every frontend must reach the healthy instance in each enabled zone");
                }
            });
        }
        for (Node node : nodes) {
            ec2.modifyInstanceAttribute(request -> request.instanceId(node.instance()).groups(backendGroup));
        }
        awaitHealthy(name, nodes);
    }

    private List<String> addresses(String hostname, int count) {
        List<String> addresses = dns.resolveIpv4(hostname).orElseThrow().addresses();
        assertEquals(count, Set.copyOf(addresses).size());
        return addresses;
    }

    private List<NetworkInterface> interfaces(String name) {
        return ec2.describeNetworkInterfaces(request -> request.filters(filter ->
                filter.name("description").values("Classic ELB " + name))).networkInterfaces();
    }

    private Set<String> wireAddresses(String helper, String hostname) throws Exception {
        String resolver = exec(helper, "cat", "/etc/resolv.conf").stdout().lines()
                .filter(line -> line.startsWith("nameserver "))
                .map(line -> line.substring("nameserver ".length()).trim()).findFirst().orElseThrow();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream query = new DataOutputStream(bytes)) {
            query.writeShort(0x5131);
            query.writeShort(0x0100);
            query.writeShort(1);
            query.write(new byte[6]);
            for (String label : hostname.split("\\.")) {
                byte[] encoded = label.getBytes(StandardCharsets.US_ASCII);
                query.writeByte(encoded.length);
                query.write(encoded);
            }
            query.writeByte(0);
            query.writeShort(1);
            query.writeShort(1);
        }
        ContainerExec.Result response = exec(helper, "sh", "-c",
                "set -eu; set -o pipefail; "
                        + "printf '%s' \"$1\" | base64 -d | socat -t 2 -T 3 - \"$2\" | od -An -v -tx1",
                "classic-node-dns", Base64.getEncoder().encodeToString(bytes.toByteArray()), "UDP4:" + resolver + ":53");
        assertEquals(0, response.exitCode(), response.summary());
        byte[] packet = HexFormat.of().parseHex(response.stdout().replaceAll("\\s", ""));
        Set<String> addresses = new HashSet<>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
            assertEquals(0x5131, input.readUnsignedShort());
            assertEquals(0, input.readUnsignedShort() & 15, response.summary());
            assertEquals(1, input.readUnsignedShort());
            int answers = input.readUnsignedShort();
            input.skipNBytes(4);
            skipName(input);
            input.skipNBytes(4);
            for (int index = 0; index < answers; index++) {
                skipName(input);
                int type = input.readUnsignedShort();
                int recordClass = input.readUnsignedShort();
                input.readInt();
                byte[] address = input.readNBytes(input.readUnsignedShort());
                if (type == 1 && recordClass == 1) {
                    assertEquals(4, address.length);
                    addresses.add(InetAddress.getByAddress(address).getHostAddress());
                }
            }
        }
        return addresses;
    }

    private void skipName(DataInputStream input) throws IOException {
        for (int length = input.readUnsignedByte(); length != 0; length = input.readUnsignedByte()) {
            if ((length & 0xc0) == 0xc0) {
                input.readUnsignedByte();
                return;
            }
            assertTrue(length <= 63);
            input.skipNBytes(length);
        }
    }

    private Reply fetch(String helper, String address, String natAddress) {
        ContainerExec.Result result = request(helper, address);
        assertEquals(0, result.exitCode(), result.summary());
        String[] fields = result.stdout().trim().split(" ");
        assertEquals(3, fields.length, result.stdout());
        assertEquals(natAddress, fields[2], "Every public node must observe the caller's actual NAT EIP");
        return new Reply(fields[0], InetAddress.ofLiteral(fields[1].replace("[", "").replace("]", "")));
    }

    private ContainerExec.Result request(String helper, String address) {
        return exec(helper, "curl", "--silent", "--show-error", "--fail", "--max-time", "2",
                "--cacert", "/tmp/frontend-ca.pem", "--resolve", HOST + ":443:" + address,
                "https://" + HOST + "/cgi-bin/request");
    }

    private Map<String, Integer> requestCounts(List<Node> nodes) {
        return nodes.stream().collect(Collectors.toMap(Node::instance, node ->
                Integer.parseInt(exec(node.guest(), "sh", "-c", "wc -c < /tmp/requests").stdout().trim())));
    }

    private String application(String zone) {
        return Base64.getEncoder().encodeToString("""
                #!/bin/sh
                set -eu
                mkdir -p /www/cgi-bin
                printf 'healthy' > /www/health
                : > /tmp/requests
                cat > /www/cgi-bin/request <<'APP'
                #!/bin/sh
                printf x >> /tmp/requests
                printf 'Content-Type: text/plain\\r\\n\\r\\n%s %s %s\\n' \
                'ZONE_ID' "$REMOTE_ADDR" "${HTTP_X_FORWARDED_FOR:-missing}"
                APP
                chmod +x /www/cgi-bin/request
                httpd -p 8080 -h /www
                """.replace("ZONE_ID", zone).getBytes(StandardCharsets.UTF_8));
    }

    private record Node(String zone, String frontendSubnet, String backendSubnet, String instance, String guest) {}

    private record Reply(String zone, InetAddress peer) {}
}
