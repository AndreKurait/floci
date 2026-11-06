package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.elbv2.ElbV2DnsRecordSource;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.route53.Route53DnsRecordSource;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeleteLoadBalancerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.AliasTarget;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.DeleteHostedZoneRequest;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/** Signed management calls in separate accounts, followed by accountless DNS packets. */
@QuarkusTest
class ElbV2AliasDnsIntegrationTest {
    @Inject
    ElbV2Service loadBalancers;
    @Inject
    Route53Service zones;
    @Inject
    EmulatorConfig config;
    @Inject
    Vertx vertx;
    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"internal", "internet-facing"})
    void publicAliasFindsItsNativeAlbAcrossAccounts(String scheme) throws Exception {
        StaticCredentialsProvider owner =
                StaticCredentialsProvider.create(AwsBasicCredentials.create("730000000001", "test"));
        StaticCredentialsProvider zoneOwner =
                StaticCredentialsProvider.create(AwsBasicCredentials.create("730000000002", "test"));
        try (Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                     .credentialsProvider(owner).httpClientBuilder(Apache5HttpClient.builder()).build();
             ElasticLoadBalancingV2Client elb = ElasticLoadBalancingV2Client.builder().endpointOverride(endpoint)
                     .region(Region.EU_WEST_1).credentialsProvider(owner)
                     .httpClientBuilder(Apache5HttpClient.builder()).build();
             ElasticLoadBalancingV2Client other = ElasticLoadBalancingV2Client.builder().endpointOverride(endpoint)
                     .region(Region.EU_WEST_1).credentialsProvider(zoneOwner)
                     .httpClientBuilder(Apache5HttpClient.builder()).build();
             Route53Client route53 = Route53Client.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                     .credentialsProvider(zoneOwner).httpClientBuilder(Apache5HttpClient.builder()).build()) {
            String name = "api." + UUID.randomUUID() + ".example.test";
            String vpc = ec2.createVpc(r -> r.cidrBlock("10.73.0.0/16")).vpc().vpcId();
            List<String> subnets = new ArrayList<>();
            String zone = null;
            String arn = null;
            ResourceRecordSet installed = null;
            DatagramSocket listener = vertx.createDatagramSocket(new DatagramSocketOptions().setIpV6(false));
            try {
                subnets.add(ec2.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.73.1.0/24")
                        .availabilityZone("eu-west-1a")).subnet().subnetId());
                subnets.add(ec2.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.73.2.0/24")
                        .availabilityZone("eu-west-1b")).subnet().subnetId());
                LoadBalancer target = elb.createLoadBalancer(r -> r.name("dns-" + UUID.randomUUID().toString().substring(0, 20))
                        .type("application").scheme(scheme).subnets(subnets)).loadBalancers().getFirst();
                arn = target.loadBalancerArn();
                assertTrue(arn.contains(":730000000001:"));
                assertTrue(other.describeLoadBalancers().loadBalancers().stream()
                        .noneMatch(lb -> lb.loadBalancerArn().equals(target.loadBalancerArn())));
                zone = route53.createHostedZone(r -> r.name(name).callerReference(UUID.randomUUID().toString()))
                        .hostedZone().id();
                List<DnsRecordSource> sources = new ArrayList<>();
                EmbeddedDnsServer dns = spy(new EmbeddedDnsServer(List.of(), sources));
                doReturn(Optional.of("127.0.0.1")).when(dns).getServerIp();
                sources.add(new Route53DnsRecordSource(zones, config, new DnsLookupHelper(), dns));
                sources.add(new ElbV2DnsRecordSource(loadBalancers, dns));
                listener.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                listener.handler(packet -> dns.handleQuery(vertx, listener, packet.data().getBytes(),
                        packet.sender().host(), packet.sender().port(), "127.0.0.1"));

                ResourceRecordSet alias = alias(name, target.dnsName(), target.canonicalHostedZoneId());
                change(route53, zone, ChangeAction.CREATE, alias);
                installed = alias;
                byte[] response = query(listener, name, 1);
                assertEquals(0, rcode(response));
                assertEquals(1, ByteBuffer.wrap(response).getShort(6));
                assertArrayEquals(InetAddress.getByName("127.0.0.1").getAddress(), address(response));
                assertTrue((ByteBuffer.wrap(response).getShort(2) & 0x0400) != 0);
                assertEquals(0, rcode(query(listener, name, 28)));
                assertEquals(0, ByteBuffer.wrap(query(listener, name, 28)).getShort(6));

                installed = alias(name, "dualstack." + target.dnsName().toUpperCase(), target.canonicalHostedZoneId());
                change(route53, zone, ChangeAction.UPSERT, installed);
                assertEquals(0, rcode(query(listener, name.toUpperCase() + ".", 1)));
                installed = alias(name, target.dnsName(), "WRONG");
                change(route53, zone, ChangeAction.UPSERT, installed);
                assertEquals(5, rcode(query(listener, name, 1)));
                installed = alias;
                change(route53, zone, ChangeAction.UPSERT, installed);
                assertEquals(0, rcode(query(listener, name, 1)));

                elb.deleteLoadBalancer(r -> r.loadBalancerArn(target.loadBalancerArn()));
                arn = null;
                assertEquals(5, rcode(query(listener, name, 1)),
                        "A deleted ALB must not resolve via the emulator suffix or a recursive lookup");
            } finally {
                listener.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                if (installed != null) {
                    change(route53, zone, ChangeAction.DELETE, installed);
                }
                if (zone != null) {
                    route53.deleteHostedZone(DeleteHostedZoneRequest.builder().id(zone).build());
                }
                if (arn != null) {
                    elb.deleteLoadBalancer(DeleteLoadBalancerRequest.builder().loadBalancerArn(arn).build());
                }
                for (String subnet : subnets) {
                    ec2.deleteSubnet(r -> r.subnetId(subnet));
                }
                ec2.deleteVpc(r -> r.vpcId(vpc));
            }
        }
    }

    private static ResourceRecordSet alias(String name, String target, String zone) {
        return ResourceRecordSet.builder().name(name).type("A").aliasTarget(AliasTarget.builder()
                .dnsName(target + ".").hostedZoneId(zone).evaluateTargetHealth(false).build()).build();
    }

    private static void change(Route53Client client, String zone, ChangeAction action, ResourceRecordSet record) {
        client.changeResourceRecordSets(r -> r.hostedZoneId(zone).changeBatch(b -> b.changes(
                Change.builder().action(action).resourceRecordSet(record).build())));
    }

    private static byte[] query(DatagramSocket listener, String name, int type) throws Exception {
        byte[] encoded = DnsRecord.encodeName(name);
        byte[] request = ByteBuffer.allocate(16 + encoded.length)
                .putShort((short) 1234).putShort((short) 256).putShort((short) 1)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0)
                .put(encoded).putShort((short) type).putShort((short) 1).array();
        try (java.net.DatagramSocket client = new java.net.DatagramSocket()) {
            client.connect(InetAddress.getByName("127.0.0.1"), listener.localAddress().port());
            client.setSoTimeout(5000);
            client.send(new DatagramPacket(request, request.length));
            DatagramPacket reply = new DatagramPacket(new byte[4096], 4096);
            client.receive(reply);
            return Arrays.copyOf(reply.getData(), reply.getLength());
        }
    }

    private static int rcode(byte[] answer) {
        return ByteBuffer.wrap(answer).getShort(2) & 15;
    }

    private static byte[] address(byte[] answer) {
        ByteBuffer packet = ByteBuffer.wrap(answer);
        packet.position(12);
        EmbeddedDnsServer.readName(packet, answer);
        packet.getInt();
        EmbeddedDnsServer.readName(packet, answer);
        assertEquals(1, packet.getShort());
        packet.getShort();
        assertEquals(60, packet.getInt());
        assertEquals(4, packet.getShort());
        byte[] value = new byte[4];
        packet.get(value);
        return value;
    }
}
