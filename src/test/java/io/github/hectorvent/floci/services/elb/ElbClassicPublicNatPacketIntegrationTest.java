package io.github.hectorvent.floci.services.elb;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.AliasTarget;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Repeats the inherited private-frontend test with route enforcement as well as exercising public NAT. */
@QuarkusTest
@TestProfile(ElbClassicPublicNatPacketIntegrationTest.PublicRouting.class)
class ElbClassicPublicNatPacketIntegrationTest extends ElbClassicVpcPacketIntegrationTest {
    public static class PublicRouting extends ProtectedFrontend {
        private static final String NAMESPACE = "classic-nat-packets-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.network.vpc-routing.enabled", "true");
            overrides.put("floci.docker.resource-namespace", NAMESPACE);
            return overrides;
        }
    }

    @Test
    void nativeRoutesAndFrontendRulesControlTheActualNatSourceAddress() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String frontendHost = "nat-app-" + suffix + ".example.test";
        Ec2Client callerAccount = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("111111111111", "test"))).build();
        cleanup.addFirst(callerAccount::close);
        Network callerVpc = network(callerAccount, "10.87.0.0/16", "10.87.2.0/24");
        String callerSubnet = subnet(callerAccount, callerVpc.vpc(), "10.87.1.0/24");
        String callerRoutes = routes(callerAccount, callerVpc.vpc(), callerSubnet);
        NatGateway nat = nat(callerAccount, callerVpc.publicSubnet());
        String publicIp = nat.natGatewayAddresses().getFirst().publicIp();
        callerAccount.createRoute(request -> request.routeTableId(callerRoutes).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(nat.natGatewayId()));
        String callerGroup = group(callerAccount, callerVpc.vpc(), "caller-" + suffix);
        String caller = launch(callerAccount, callerSubnet, callerGroup, "10.87.1.10", null);
        String callerHelper = namespace(instances.findInstanceById("111111111111", caller)
                .getDockerContainerId()).getId();

        Network serverVpc = network(ec2, "10.86.0.0/16", "10.86.1.0/24");
        String frontendGroup = group(serverVpc.vpc(), "frontend-" + suffix);
        String backendGroup = group(serverVpc.vpc(), "backend-" + suffix);
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(backendGroup)
                .ipPermissions(fromGroup(8080, frontendGroup)));
        IpPermission privateCaller = fromAddress(443, "10.87.1.10");
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(frontendGroup).ipPermissions(privateCaller));
        String backend = launch(serverVpc.publicSubnet(), backendGroup, "10.86.1.10",
                Base64.getEncoder().encodeToString("""
                        #!/bin/sh
                        set -eu
                        mkdir -p /www/cgi-bin
                        printf 'healthy' > /www/health
                        printf '0' > /tmp/request-count
                        cat > /www/cgi-bin/request <<'APP'
                        #!/bin/sh
                        count=$(cat /tmp/request-count)
                        count=$((count + 1))
                        printf '%s' "$count" > /tmp/request-count
                        printf 'Content-Type: text/plain\\r\\n\\r\\n%s %s %s\\n' \
                        "$REMOTE_ADDR" "$count" "${HTTP_X_FORWARDED_FOR:-missing}"
                        APP
                        chmod +x /www/cgi-bin/request
                        httpd -p 8080 -h /www
                        """.getBytes(StandardCharsets.UTF_8)));
        String backendGuest = localInstance(backend).getDockerContainerId();
        CertificateGenerator.GeneratedCertificate certificate = new CertificateGenerator().generateSelfSignedCertificate(
                frontendHost, List.of(), KeyAlgorithm.EC_prime256v1);
        String certificateName = "classic-nat-" + suffix;
        String arn = iam.uploadServerCertificate(request -> request.serverCertificateName(certificateName)
                .certificateBody(certificate.certificatePem()).privateKey(certificate.privateKeyPem()))
                .serverCertificateMetadata().arn();
        cleanup.addFirst(() -> iam.deleteServerCertificate(request -> request.serverCertificateName(certificateName)));
        RetryingTarCopier.copyBytes(docker, callerHelper, "/tmp", "frontend-ca.pem",
                certificate.certificatePem().getBytes(StandardCharsets.UTF_8), 0600);
        String name = "classic-nat-" + suffix;
        String hostname = elb.createLoadBalancer(request -> request.loadBalancerName(name).scheme("internet-facing")
                .subnets(serverVpc.publicSubnet()).securityGroups(frontendGroup).listeners(Listener.builder()
                        .protocol("HTTPS").loadBalancerPort(443).instanceProtocol("HTTP").instancePort(8080)
                        .sslCertificateId(arn).build())).dnsName();
        cleanup.addFirst(() -> elb.deleteLoadBalancer(request -> request.loadBalancerName(name)));
        elb.configureHealthCheck(request -> request.loadBalancerName(name).healthCheck(check -> check
                .target("HTTP:8080/health").interval(5).timeout(2).healthyThreshold(2).unhealthyThreshold(2)));
        elb.registerInstancesWithLoadBalancer(request -> request.loadBalancerName(name)
                .instances(instance -> instance.instanceId(backend)));
        await().atMost(Duration.ofSeconds(30)).until(() -> healthy(name, backend));
        String address = dns.resolveIpv4(hostname).orElseThrow().addresses().getFirst();
        Route53Client route53 = Route53Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("222222222222", "test"))).build();
        cleanup.addFirst(route53::close);
        String zone = route53.createHostedZone(request -> request.name(frontendHost).callerReference(suffix))
                .hostedZone().id();
        cleanup.addFirst(() -> route53.deleteHostedZone(request -> request.id(zone)));
        String targetZone = elb.describeLoadBalancers(request -> request.loadBalancerNames(name))
                .loadBalancerDescriptions().getFirst().canonicalHostedZoneNameID();
        ResourceRecordSet alias = ResourceRecordSet.builder().name(frontendHost).type("A")
                .aliasTarget(AliasTarget.builder().dnsName(hostname).hostedZoneId(targetZone)
                        .evaluateTargetHealth(false).build()).build();
        changeAlias(route53, zone, ChangeAction.CREATE, alias);
        cleanup.addFirst(() -> {
            for (ResourceRecordSet current : route53.listResourceRecordSets(request -> request.hostedZoneId(zone))
                    .resourceRecordSets()) {
                if ("A".equals(current.typeAsString())) {
                    changeAlias(route53, zone, ChangeAction.DELETE, current);
                }
            }
        });
        assertAlias(callerHelper, frontendHost, Set.of(address));
        changeAlias(route53, zone, ChangeAction.UPSERT, alias.toBuilder().aliasTarget(alias.aliasTarget().toBuilder()
                .hostedZoneId("ZWRONGTARGET").build()).build());
        assertNotEquals(0, exec(callerHelper, "getent", "ahostsv4", frontendHost).exitCode());
        changeAlias(route53, zone, ChangeAction.UPSERT, alias);
        assertAlias(callerHelper, frontendHost, Set.of(address));
        String frontendPrivate = ec2.describeNetworkInterfaces(request -> request.filters(filter ->
                filter.name("description").values("Classic ELB " + name)))
                .networkInterfaces().getFirst().privateIpAddress();
        assertNotEquals(frontendPrivate, address);

        assertBlocked(callerHelper, frontendHost, backendGuest, 0, "The frontend sees the NAT EIP, not the caller private IP");
        IpPermission publicCaller = fromAddress(443, publicIp);
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(backendGroup).ipPermissions(publicCaller));
        assertBlocked(callerHelper, frontendHost, backendGuest, 0, "Backend ingress cannot grant frontend access");
        assertTrue(healthy(name, backend), "Frontend denial must leave the backend and health checks healthy");
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(frontendGroup).ipPermissions(publicCaller));
        assertResponse(callerHelper, frontendHost, frontendPrivate, publicIp, 1);

        callerAccount.deleteRoute(request -> request.routeTableId(callerRoutes).destinationCidrBlock("0.0.0.0/0"));
        assertBlocked(callerHelper, frontendHost, backendGuest, 1, "A private subnet needs its NAT route");
        callerAccount.createRoute(request -> request.routeTableId(callerRoutes).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(nat.natGatewayId()));
        assertResponse(callerHelper, frontendHost, frontendPrivate, publicIp, 2);
        callerAccount.deleteRoute(request -> request.routeTableId(callerVpc.publicRoutes())
                .destinationCidrBlock("0.0.0.0/0"));
        assertBlocked(callerHelper, frontendHost, backendGuest, 2, "A public NAT subnet needs its internet gateway route");
        internetRoute(callerAccount, callerVpc);
        assertResponse(callerHelper, frontendHost, frontendPrivate, publicIp, 3);
        ec2.deleteRoute(request -> request.routeTableId(serverVpc.publicRoutes()).destinationCidrBlock("0.0.0.0/0"));
        assertBlocked(callerHelper, frontendHost, backendGuest, 3, "A public frontend needs its internet gateway route");
        internetRoute(ec2, serverVpc);
        assertResponse(callerHelper, frontendHost, frontendPrivate, publicIp, 4);

        NatGateway replacement = nat(callerAccount, callerVpc.publicSubnet());
        String replacementIp = replacement.natGatewayAddresses().getFirst().publicIp();
        assertNotEquals(publicIp, replacementIp);
        callerAccount.replaceRoute(request -> request.routeTableId(callerRoutes).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(replacement.natGatewayId()));
        assertBlocked(callerHelper, frontendHost, backendGuest, 4, "A replaced NAT EIP needs a matching frontend rule");
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(frontendGroup)
                .ipPermissions(fromAddress(443, replacementIp)));
        assertResponse(callerHelper, frontendHost, frontendPrivate, replacementIp, 5);
        changeAlias(route53, zone, ChangeAction.UPSERT, alias.toBuilder().aliasTarget(alias.aliasTarget().toBuilder()
                .dnsName("DUALSTACK." + hostname.toUpperCase(Locale.ROOT) + ".").build()).build());
        assertAlias(callerHelper, frontendHost, Set.of(address));
        changeAlias(route53, zone, ChangeAction.UPSERT, alias);

        assertNotEquals(0, exec(callerHelper, "curl", "--silent", "--show-error", "--max-time", "2",
                "http://10.86.1.10:8080/cgi-bin/request").exitCode(),
                "Docker's default route must not bypass isolation between VPCs");
        assertEquals("5", exec(backendGuest, "cat", "/tmp/request-count").stdout().trim());

        String natHelper = natHelper(replacement.natGatewayId());
        String natHelperName = docker.inspectContainerCmd(natHelper).exec().getName().substring(1);
        String consumer = docker.createContainerCmd(TestImages.BUSYBOX).withName("floci-nat-consumer-" + suffix)
                .withLabels(Map.of(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)))
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode("container:" + natHelperName))
                .withCmd("sleep", "300").exec().getId();
        cleanup.addFirst(() -> removeContainer(consumer));
        docker.startContainerCmd(consumer).exec();
        assertThrows(Ec2Exception.class,
                () -> callerAccount.deleteNatGateway(request -> request.natGatewayId(replacement.natGatewayId())));
        assertEquals("available", callerAccount.describeNatGateways(request ->
                request.natGatewayIds(replacement.natGatewayId())).natGateways().getFirst().stateAsString());
        assertEquals(replacement.natGatewayAddresses().getFirst().associationId(),
                callerAccount.describeAddresses(request -> request.allocationIds(
                        replacement.natGatewayAddresses().getFirst().allocationId()))
                        .addresses().getFirst().associationId(),
                "A failed namespace retirement must retain the native EIP reservation");
        assertTrue(docker.inspectContainerCmd(natHelper).exec().getState().getRunning());
        removeContainer(consumer);
        callerAccount.deleteNatGateway(request -> request.natGatewayId(replacement.natGatewayId()));
        assertBlocked(callerHelper, frontendHost, backendGuest, 5, "Deleting a NAT gateway must stop new connections");
        assertEquals("blackhole", callerAccount.describeRouteTables(request -> request.routeTableIds(callerRoutes))
                .routeTables().getFirst().routes().stream().filter(route ->
                        "0.0.0.0/0".equals(route.destinationCidrBlock())).findFirst().orElseThrow().stateAsString());
        docker.stopContainerCmd(natHelper(nat.natGatewayId())).withTimeout(0).exec();
        callerAccount.deleteNatGateway(request -> request.natGatewayId(nat.natGatewayId()));
        assertNull(callerAccount.describeAddresses(request -> request.allocationIds(
                nat.natGatewayAddresses().getFirst().allocationId())).addresses().getFirst().associationId(),
                "Deleting a NAT whose worker has stopped must release its native EIP reservation");
        elb.deleteLoadBalancer(request -> request.loadBalancerName(name));
        assertNotEquals(0, exec(callerHelper, "getent", "ahostsv4", frontendHost).exitCode(),
                "An alias cannot retain a retired frontend address");
        cleanup.addLast(() -> assertTrue(docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(
                ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                ContainerStorageHelper.SERVICE_LABEL, "vpc-nat")).exec().isEmpty()));
        cleanup.addLast(() -> assertTrue(docker.listNetworksCmd().withFilter("label", List.of(
                ContainerStorageHelper.OWNER_LABEL + "=" + ContainerStorageHelper.ownerIdentity(config),
                ContainerStorageHelper.COMPONENT_LABEL + "=vpc-public-transport")).exec().isEmpty()));
    }

    private String natHelper(String id) {
        return docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(
                ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                ContainerStorageHelper.SERVICE_LABEL, "vpc-nat",
                ContainerStorageHelper.RESOURCE_ID_LABEL, id)).exec().getFirst().getId();
    }

    Network network(Ec2Client client, String cidr, String publicCidr) {
        String vpc = client.createVpc(request -> request.cidrBlock(cidr)).vpc().vpcId();
        cleanup.addFirst(() -> client.deleteVpc(request -> request.vpcId(vpc)));
        String subnet = subnet(client, vpc, publicCidr);
        String gateway = client.createInternetGateway().internetGateway().internetGatewayId();
        cleanup.addFirst(() -> client.deleteInternetGateway(request -> request.internetGatewayId(gateway)));
        client.attachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc));
        cleanup.addFirst(() -> client.detachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc)));
        Network network = new Network(vpc, subnet, gateway, routes(client, vpc, subnet));
        internetRoute(client, network);
        return network;
    }

    String subnet(Ec2Client client, String vpc, String cidr) {
        String id = client.createSubnet(request -> request.vpcId(vpc).cidrBlock(cidr)).subnet().subnetId();
        cleanup.addFirst(() -> client.deleteSubnet(request -> request.subnetId(id)));
        return id;
    }

    String routes(Ec2Client client, String vpc, String subnet) {
        String id = client.createRouteTable(request -> request.vpcId(vpc)).routeTable().routeTableId();
        cleanup.addFirst(() -> client.deleteRouteTable(request -> request.routeTableId(id)));
        String association = client.associateRouteTable(request -> request.routeTableId(id).subnetId(subnet))
                .associationId();
        cleanup.addFirst(() -> client.disassociateRouteTable(request -> request.associationId(association)));
        return id;
    }

    NatGateway nat(Ec2Client client, String subnet) {
        String allocation = client.allocateAddress(request -> request.domain("vpc")).allocationId();
        cleanup.addFirst(() -> client.releaseAddress(request -> request.allocationId(allocation)));
        NatGateway gateway = client.createNatGateway(request -> request.subnetId(subnet).allocationId(allocation))
                .natGateway();
        cleanup.addFirst(() -> {
            try {
                client.deleteNatGateway(request -> request.natGatewayId(gateway.natGatewayId()));
            } catch (Ec2Exception failure) {
                if (!"NatGatewayNotFound".equals(failure.awsErrorDetails().errorCode())) {
                    throw failure;
                }
            }
        });
        assertEquals("available", gateway.stateAsString(), gateway.toString());
        return gateway;
    }

    private void internetRoute(Ec2Client client, Network network) {
        client.createRoute(request -> request.routeTableId(network.publicRoutes()).destinationCidrBlock("0.0.0.0/0")
                .gatewayId(network.gateway()));
    }

    static IpPermission fromAddress(int port, String address) {
        return IpPermission.builder().ipProtocol("tcp").fromPort(port).toPort(port)
                .ipRanges(range -> range.cidrIp(address + "/32")).build();
    }

    private ContainerExec.Result request(String helper, String hostname) {
        return exec(helper, "curl", "--silent", "--show-error", "--fail", "--max-time", "2",
                "--cacert", "/tmp/frontend-ca.pem", "https://" + hostname + "/cgi-bin/request");
    }

    private void assertAlias(String helper, String hostname, Set<String> expected) {
        ContainerExec.Result result = exec(helper, "getent", "ahostsv4", hostname);
        assertEquals(0, result.exitCode(), result.summary());
        assertEquals(expected, result.stdout().lines().map(line -> line.split("\\s+")[0]).collect(Collectors.toSet()));
    }

    private static void changeAlias(Route53Client route53, String zone, ChangeAction action, ResourceRecordSet alias) {
        route53.changeResourceRecordSets(request -> request.hostedZoneId(zone).changeBatch(batch ->
                batch.changes(change -> change.action(action).resourceRecordSet(alias))));
    }

    private void assertBlocked(String helper, String address, String backend, int count, String reason) {
        long started = System.nanoTime();
        ContainerExec.Result response = request(helper, address);
        assertNotEquals(0, response.exitCode(), reason);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 1500,
                "Dropping packets must cause a connection wait: " + response.summary());
        assertEquals(Integer.toString(count), exec(backend, "cat", "/tmp/request-count").stdout().trim(), reason);
    }

    private void assertResponse(String helper, String address, String privateAddress, String callerAddress, int count) {
        ContainerExec.Result response = request(helper, address);
        assertEquals(0, response.exitCode(), () -> response.summary() + networkDiagnostics());
        String[] fields = response.stdout().trim().split(" ");
        assertEquals(3, fields.length, response.stdout());
        assertEquals(InetAddress.ofLiteral(privateAddress),
                InetAddress.ofLiteral(fields[0].replace("[", "").replace("]", "")));
        assertEquals(Integer.toString(count), fields[1]);
        assertEquals(callerAddress, fields[2], "The frontend must observe the gateway's allocated public address");
    }

    String networkDiagnostics() {
        StringBuilder evidence = new StringBuilder();
        for (Container container : docker.listContainersCmd().withLabelFilter(Map.of(
                ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config))).exec()) {
            if (!"true".equals(container.getLabels().get(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL))
                    && !"vpc-nat".equals(container.getLabels().get(ContainerStorageHelper.SERVICE_LABEL))) {
                continue;
            }
            evidence.append("\nNamespace ").append(container.getId()).append(' ')
                    .append(container.getLabels()).append('\n');
            for (String[] command : List.of(new String[]{"ip", "-4", "address"},
                    new String[]{"ip", "-4", "route"}, new String[]{"nft", "list", "ruleset"},
                    new String[]{"ss", "-nt"}, new String[]{"sysctl", "net.ipv4.conf.all.rp_filter"})) {
                evidence.append(String.join(" ", command)).append('\n')
                        .append(exec(container.getId(), command).summary()).append('\n');
            }
        }
        return evidence.toString();
    }

    record Network(String vpc, String publicSubnet, String gateway, String publicRoutes) {}
}
