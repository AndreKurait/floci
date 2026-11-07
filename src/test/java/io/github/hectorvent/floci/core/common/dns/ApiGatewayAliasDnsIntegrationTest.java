package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayDnsRecordSource;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.route53.Route53DnsRecordSource;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.CreateDomainNameResponse;
import software.amazon.awssdk.services.apigateway.model.EndpointType;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Signed management calls, real DNS packets and the native custom-domain route. */
@QuarkusTest
class ApiGatewayAliasDnsIntegrationTest {
    @Inject
    ApiGatewayService gateways;
    @Inject
    Route53Service zones;
    @Inject
    EmulatorConfig config;
    @Inject
    Vertx vertx;
    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"false,REGIONAL", "true,REGIONAL", "false,EDGE", "true,EDGE"})
    void nativeAliasSurvivesDnsAndRoutesToItsApi(boolean managedVpc, EndpointType endpointType) throws Exception {
        StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (ApiGatewayClient api = ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(credentials).httpClientBuilder(Apache5HttpClient.builder()).build();
             Route53Client route53 = Route53Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(credentials).httpClientBuilder(Apache5HttpClient.builder()).build()) {
            String domain = "api." + UUID.randomUUID() + ".example.test";
            String zone = route53.createHostedZone(r -> r.name(domain.substring(4))
                    .callerReference(UUID.randomUUID().toString())).hostedZone().id();
            String apiId = api.createRestApi(r -> r.name(domain)).id();
            boolean domainCreated = false;
            ResourceRecordSet installed = null;
            List<DnsRecordSource> sources = new ArrayList<>();
            DnsClientVpcSource.ClientVpc origin = managedVpc ? new DnsClientVpcSource.ClientVpc(
                    config.defaultAccountId(), "us-east-1", "vpc-dns-api") : null;
            EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of(), sources, List.of(),
                    List.of(address -> Optional.ofNullable(origin)), true);
            sources.add(new Route53DnsRecordSource(zones, config, new DnsLookupHelper(), dns));
            sources.add(new ApiGatewayDnsRecordSource(gateways, dns));
            DatagramSocket listener = dns.listen(vertx, "127.0.0.1", 0)
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            try {
                CreateDomainNameResponse target = createDomain(api, domain, endpointType);
                domainCreated = true;
                String targetName = endpointType == EndpointType.REGIONAL
                        ? target.regionalDomainName() : target.distributionDomainName();
                String targetZone = endpointType == EndpointType.REGIONAL
                        ? target.regionalHostedZoneId() : target.distributionHostedZoneId();
                ResourceRecordSet alias = alias(domain, targetName, targetZone);
                change(route53, zone, ChangeAction.CREATE, alias);
                installed = alias;

                byte[] answer = query(listener, domain, 1);
                assertEquals(0, rcode(answer));
                assertEquals(1, ByteBuffer.wrap(answer).getShort(6));
                assertTrue((ByteBuffer.wrap(answer).getShort(2) & 0x0400) != 0);
                String expected = managedVpc ? EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS : "127.0.0.1";
                assertArrayEquals(InetAddress.getByName(expected).getAddress(), address(answer));
                assertEquals(0, rcode(query(listener, domain, 28)));
                assertEquals(0, ByteBuffer.wrap(query(listener, domain, 28)).getShort(6));
                routeNativeApi(api, apiId, domain);

                ResourceRecordSet wrongZone = alias(domain, targetName, "WRONG");
                change(route53, zone, ChangeAction.UPSERT, wrongZone);
                installed = wrongZone;
                assertEquals(5, rcode(query(listener, domain, 1)));
                change(route53, zone, ChangeAction.UPSERT, alias);
                installed = alias;
                assertEquals(0, rcode(query(listener, domain, 1)));

                api.deleteBasePathMapping(r -> r.domainName(domain).basePath("(none)"));
                api.deleteDomainName(r -> r.domainName(domain));
                domainCreated = false;
                assertEquals(5, rcode(query(listener, domain, 1)),
                        "An orphaned alias cannot become an arbitrary public endpoint");
                CreateDomainNameResponse replacement = createDomain(api, domain, endpointType);
                domainCreated = true;
                alias = alias(domain, endpointType == EndpointType.REGIONAL
                        ? replacement.regionalDomainName() : replacement.distributionDomainName(),
                        endpointType == EndpointType.REGIONAL
                                ? replacement.regionalHostedZoneId() : replacement.distributionHostedZoneId());
                change(route53, zone, ChangeAction.UPSERT, alias);
                installed = alias;
                assertEquals(0, rcode(query(listener, domain.toUpperCase() + ".", 1)));

                change(route53, zone, ChangeAction.DELETE, alias);
                installed = null;
                assertEquals(3, rcode(query(listener, domain, 1)),
                        "Retaining the zone while pruning its API alias reproduces the native fleet failure");
            } finally {
                listener.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                if (installed != null) {
                    change(route53, zone, ChangeAction.DELETE, installed);
                }
                if (domainCreated) {
                    api.deleteDomainName(r -> r.domainName(domain));
                }
                api.deleteRestApi(r -> r.restApiId(apiId));
                route53.deleteHostedZone(r -> r.id(zone));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = EndpointType.class, names = {"REGIONAL", "EDGE"})
    void declaredNonVpcAliasUsesUpstreamOnlyWhileItsNativeTargetIsValid(EndpointType type) throws Exception {
        StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (ApiGatewayClient api = ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(credentials).httpClientBuilder(Apache5HttpClient.builder()).build();
             Route53Client route53 = Route53Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(credentials).httpClientBuilder(Apache5HttpClient.builder()).build()) {
            String domain = "api." + UUID.randomUUID() + ".example.test";
            String zone = null;
            boolean domainCreated = false;
            ResourceRecordSet installed = null;
            List<DnsRecordSource> sources = new ArrayList<>();
            EmbeddedDnsServer dns = new EmbeddedDnsServer(
                    List.of(), sources, List.of(), List.of(), true, List.of(domain));
            sources.add(new Route53DnsRecordSource(zones, config, new DnsLookupHelper(), dns));
            sources.add(new ApiGatewayDnsRecordSource(gateways, dns));
            DatagramSocket listener = dns.listen(vertx, "127.0.0.1", 0)
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            try {
                zone = route53.createHostedZone(r -> r.name(domain).callerReference(UUID.randomUUID().toString()))
                        .hostedZone().id();
                CreateDomainNameResponse target = createDomain(api, domain, type);
                domainCreated = true;
                String targetName = type == EndpointType.REGIONAL
                        ? target.regionalDomainName() : target.distributionDomainName();
                String targetZone = type == EndpointType.REGIONAL
                        ? target.regionalHostedZoneId() : target.distributionHostedZoneId();
                installed = alias(domain, targetName, targetZone);
                change(route53, zone, ChangeAction.CREATE, installed);
                assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM,
                        dns.planQuery(domain, "127.0.0.2", "127.0.0.1", 1));
                assertEquals(0, ByteBuffer.wrap(query(listener, domain, 28)).getShort(6));

                installed = ResourceRecordSet.builder().name(domain).type("A").ttl(45L)
                        .resourceRecords(record -> record.value("127.0.0.1")).build();
                change(route53, zone, ChangeAction.UPSERT, installed);
                assertArrayEquals(InetAddress.getByName("127.0.0.1").getAddress(), address(query(listener, domain, 1)));
                installed = alias(domain, targetName, "WRONG");
                change(route53, zone, ChangeAction.UPSERT, installed);
                assertEquals(5, rcode(query(listener, domain, 1)));
                installed = alias(domain, targetName, targetZone);
                change(route53, zone, ChangeAction.UPSERT, installed);
                api.deleteDomainName(r -> r.domainName(domain));
                domainCreated = false;
                assertEquals(5, rcode(query(listener, domain, 1)));
            } finally {
                listener.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                if (installed != null) {
                    change(route53, zone, ChangeAction.DELETE, installed);
                }
                if (domainCreated) {
                    api.deleteDomainName(r -> r.domainName(domain));
                }
                if (zone != null) {
                    route53.deleteHostedZone(DeleteHostedZoneRequest.builder().id(zone).build());
                }
            }
        }
    }

    private static CreateDomainNameResponse createDomain(ApiGatewayClient api, String domain, EndpointType type) {
        return api.createDomainName(r -> {
            r.domainName(domain).endpointConfiguration(c -> c.types(type));
            String certificate = "arn:aws:acm:us-east-1:000000000000:certificate/dns-fixture";
            if (type == EndpointType.REGIONAL) {
                r.regionalCertificateArn(certificate);
            } else {
                r.certificateArn(certificate);
            }
        });
    }

    private static ResourceRecordSet alias(String name, String target, String zone) {
        return ResourceRecordSet.builder().name(name).type("A").aliasTarget(AliasTarget.builder()
                .dnsName(target + ".").hostedZoneId(zone).evaluateTargetHealth(false).build()).build();
    }

    private static void change(Route53Client client, String zone, ChangeAction action, ResourceRecordSet record) {
        client.changeResourceRecordSets(r -> r.hostedZoneId(zone).changeBatch(b -> b.changes(
                Change.builder().action(action).resourceRecordSet(record).build())));
    }

    private static void routeNativeApi(ApiGatewayClient api, String id, String domain) {
        String root = api.getResources(r -> r.restApiId(id)).items().getFirst().id();
        String resource = api.createResource(r -> r.restApiId(id).parentId(root).pathPart("native-dns-probe")).id();
        api.putMethod(r -> r.restApiId(id).resourceId(resource).httpMethod("GET").authorizationType("NONE"));
        api.putMethodResponse(r -> r.restApiId(id).resourceId(resource).httpMethod("GET").statusCode("200"));
        api.putIntegration(r -> r.restApiId(id).resourceId(resource).httpMethod("GET").type(IntegrationType.MOCK)
                .requestTemplates(Map.of("application/json", "{\"statusCode\":200}")));
        api.putIntegrationResponse(r -> r.restApiId(id).resourceId(resource).httpMethod("GET").statusCode("200")
                .responseTemplates(Map.of("application/json", "{\"nativeGateway\":\"reached\"}")));
        api.createDeployment(r -> r.restApiId(id).stageName("prod"));
        api.createBasePathMapping(r -> r.domainName(domain).restApiId(id).stage("prod"));
        given().header("Host", domain).when().get("/native-dns-probe").then()
                .statusCode(200).body("nativeGateway", equalTo("reached"));
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
