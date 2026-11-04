package io.github.hectorvent.floci.services.elb;

import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Repeats protected frontend and NAT packets with independently routed, overlapping VPC addresses. */
@QuarkusTest
@TestProfile(ElbOverlappingVpcPacketIntegrationTest.OverlappingRouting.class)
class ElbOverlappingVpcPacketIntegrationTest extends ElbClassicLambdaNatPacketIntegrationTest {
    @Inject SecurityGroupFirewallManager firewall;

    public static class OverlappingRouting extends LambdaRouting {
        private static final String NAMESPACE = "overlapping-vpc-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.docker.resource-namespace", NAMESPACE);
            overrides.put("floci.services.ec2.vpc-networks.isolate-address-spaces", "true");
            return overrides;
        }
    }

    @Override
    ServerNetwork serverNetwork() {
        return new ServerNetwork("10.89.0.0/16", "10.89.1.0/24", "10.89.1.10");
    }

    @Override
    void verifyConnectedAddresses(String callerAddress, String backendAddress) {
        assertEquals(backendAddress, callerAddress,
                "Separate accounts must retain the same native private address while using public NAT");
        for (Container container : docker.listContainersCmd().withLabelFilter(Map.of(
                ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config))).exec()) {
            Map<String, String> labels = container.getLabels();
            if ("true".equals(labels.get(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL))
                    || "vpc-nat".equals(labels.get(ContainerStorageHelper.SERVICE_LABEL))) {
                ContainerExec.Result rules = exec(container.getId(), "nft", "--json", "list", "ruleset");
                assertEquals(0, rules.exitCode(), rules.summary());
                assertFalse(rules.stdout().contains("floci-vpc-attach-"),
                        "The temporary link-layer exception must retire before workload traffic");
            }
        }
    }

    @Test
    void identicalPrivateAddressesRetainTheirOwnPrivateDnsZoneAndInstanceMetadata() throws Exception {
        Map<String, String> expected = new HashMap<>();
        Map<String, String> instancesByHelper = new HashMap<>();
        for (int index = 1; index <= 2; index++) {
            String account = Integer.toString(index).repeat(12);
            StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(account, "test"));
            Ec2Client client = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
            Route53Client zones = Route53Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
            cleanup.addFirst(client::close);
            cleanup.addFirst(zones::close);
            String vpc = client.createVpc(request -> request.cidrBlock("10.89.0.0/16")).vpc().vpcId();
            cleanup.addFirst(() -> client.deleteVpc(request -> request.vpcId(vpc)));
            client.modifyVpcAttribute(request -> request.vpcId(vpc).enableDnsSupport(value -> value.value(true)));
            client.modifyVpcAttribute(request -> request.vpcId(vpc).enableDnsHostnames(value -> value.value(true)));
            String subnet = subnet(client, vpc, "10.89.1.0/24");
            String group = group(client, vpc, "dns-" + UUID.randomUUID());
            client.revokeSecurityGroupEgress(request -> request.groupId(group).ipPermissions(
                    permission -> permission.ipProtocol("-1").ipRanges(range -> range.cidrIp("0.0.0.0/0"))));
            String zone = zones.createHostedZone(request -> request.name("same-vpc.internal")
                    .callerReference(UUID.randomUUID().toString())
                    .hostedZoneConfig(configuration -> configuration.privateZone(true))
                    .vpc(association -> association.vpcId(vpc).vpcRegion("us-east-1"))).hostedZone().id();
            cleanup.addFirst(() -> zones.deleteHostedZone(request -> request.id(zone)));
            String answer = "192.0.2." + index;
            ResourceRecordSet record = ResourceRecordSet.builder().name("service.same-vpc.internal")
                    .type("A").ttl(10L).resourceRecords(value -> value.value(answer)).build();
            zones.changeResourceRecordSets(request -> request.hostedZoneId(zone).changeBatch(batch -> batch
                    .changes(change -> change.action(ChangeAction.CREATE).resourceRecordSet(record))));
            cleanup.addFirst(() -> zones.changeResourceRecordSets(request -> request.hostedZoneId(zone)
                    .changeBatch(batch -> batch.changes(change -> change.action(ChangeAction.DELETE)
                            .resourceRecordSet(record)))));
            String instance = launch(client, subnet, group, "10.89.1.10", null);
            List<Container> workers = docker.listContainersCmd().withLabelFilter(Map.of(
                    ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                    ContainerStorageHelper.ACCOUNT_LABEL, account,
                    ContainerStorageHelper.SERVICE_LABEL, "ec2",
                    ContainerStorageHelper.RESOURCE_ID_LABEL, instance)).exec().stream()
                    .filter(container -> !"true".equals(container.getLabels()
                            .get(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL))).toList();
            assertEquals(1, workers.size());
            String helper = namespace(workers.getFirst().getId()).getId();
            String transport = docker.inspectContainerCmd(helper).exec().getNetworkSettings().getNetworks()
                    .values().stream().map(network -> network.getIpAddress())
                    .filter(address -> address != null && !address.isBlank()).findFirst().orElseThrow();
            assertEquals(account, firewall.vpcForClient(transport).orElseThrow().accountId());
            assertEquals(vpc, firewall.vpcForClient(transport).orElseThrow().vpcId());
            expected.put(helper, answer);
            instancesByHelper.put(helper, instance);
        }
        // Exercise both namespaces after both repeated addresses and zones exist.
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String helper = entry.getKey();
            ContainerExec.Result resolverFile = exec(helper, "cat", "/etc/resolv.conf");
            assertEquals(0, resolverFile.exitCode(), resolverFile.summary());
            String resolver = resolverFile.stdout().lines().filter(line -> line.startsWith("nameserver "))
                    .map(line -> line.substring("nameserver ".length()).trim()).findFirst().orElseThrow();
            assertEquals(4, InetAddress.ofLiteral(resolver).getAddress().length);
            verifyDns(helper, resolver, entry.getValue(), false, false);
            verifyDns(helper, resolver, entry.getValue(), false, true);
            verifyDns(helper, resolver, entry.getValue(), true, true);
            ContainerExec.Result metadata = exec(helper, "sh", "-c", """
                    set -eu
                    token=$(curl --noproxy '*' -fsS --max-time 2 -X PUT \
                      -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
                    curl --noproxy '*' -fsS --max-time 2 -H "X-aws-ec2-metadata-token: $token" \
                      http://169.254.169.254/latest/meta-data/instance-id
                    """);
            assertEquals(0, metadata.exitCode(), metadata.summary());
            assertEquals(instancesByHelper.get(helper), metadata.stdout().trim(),
                    "Repeated private addresses must not exchange instance metadata");
        }
    }

    private void verifyDns(String helper, String resolver, String expectedAddress, boolean tcp,
                           boolean bindPrivate) throws Exception {
        ByteArrayOutputStream query = new ByteArrayOutputStream();
        try (DataOutputStream message = new DataOutputStream(query)) {
            message.writeShort(0x5130);
            message.writeShort(0x0100);
            message.writeShort(1);
            message.write(new byte[6]);
            for (String label : "service.same-vpc.internal".split("\\.")) {
                byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
                message.writeByte(bytes.length);
                message.write(bytes);
            }
            message.writeByte(0);
            message.writeShort(1);
            message.writeShort(1);
        }
        byte[] packet = query.toByteArray();
        if (tcp) {
            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            try (DataOutputStream message = new DataOutputStream(framed)) {
                message.writeShort(packet.length);
                message.write(packet);
            }
            packet = framed.toByteArray();
        }
        // DNS frames delimit requests. Keep TCP open for the reply when the input pipe reaches EOF.
        ContainerExec.Result response = exec(helper, "sh", "-c",
                "set -eu; set -o pipefail; "
                        + "printf '%s' \"$1\" | base64 -d | socat -t 2 -T 3 - \"$2\" | od -An -v -tx1",
                "vpc-dns-query", Base64.getEncoder().encodeToString(packet),
                (tcp ? "TCP4:" : "UDP4:") + resolver + ":53" + (bindPrivate ? ",bind=10.89.1.10" : "")
                        + (tcp ? ",shut-none" : ""));
        assertEquals(0, response.exitCode(), response.summary());
        byte[] answer = HexFormat.of().parseHex(response.stdout().replaceAll("\\s", ""));
        int offset = tcp ? 2 : 0;
        assertTrue(answer.length >= offset + 16, () ->
                "The VPC resolver must answer: resolver=" + resolver + ", tcp=" + tcp + ", native=" + bindPrivate
                        + "\n" + response.summary() + "\n" + exec(helper, "ip", "-4", "address").stdout()
                        + "\n" + exec(helper, "ip", "-4", "route").stdout()
                        + "\n" + exec(helper, "nft", "list", "ruleset").stdout());
        if (tcp) {
            assertEquals(answer.length - 2, (answer[0] & 255) * 256 + (answer[1] & 255));
        }
        assertEquals(0x51, answer[offset] & 255);
        assertEquals(0x30, answer[offset + 1] & 255);
        assertEquals(0, answer[offset + 3] & 15, "DNS must answer without an error");
        assertEquals(1, (answer[offset + 6] & 255) * 256 + (answer[offset + 7] & 255));
        assertArrayEquals(InetAddress.ofLiteral(expectedAddress).getAddress(),
                Arrays.copyOfRange(answer, answer.length - 4, answer.length),
                "DNS must select the private zone in the source namespace's account and VPC");
    }
}
