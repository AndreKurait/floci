package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elbv2.ElbV2DnsRecordSource;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.route53.Route53DnsRecordSource;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.testing.AwsElbV2DnsNamesProfile;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ActionTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.AliasTarget;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * With {@code floci.services.elbv2.dns-name-strategy=aws} a load balancer is named the way AWS documents, which
 * clients that check the ELB DNS form accept. Floci's DNS answers the name with the address the {@code floci}
 * form resolved to, also where AWS endpoint names are spoofed to the public API address, answers NXDOMAIN for a name
 * in those zones that no load balancer has, and a load balancer is
 * still selected by that name and through a Route 53 alias to it. Before, the name was
 * {@code <name>-<hex id>.elb.<emulator host>} whatever the setting.
 */
@QuarkusTest
@TestProfile(AwsElbV2DnsNamesProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2AwsDnsNameIntegrationTest {
    private static final int LISTENER_PORT = 7861;
    private static final String ALB_SUFFIX = ".us-east-1.elb.amazonaws.com";
    private static final String FLOCI = "127.0.0.1";
    private static final String CLIENT = "10.88.0.9";
    /** What a client that checks the ELB form expects of a name that ends in a number: two numbers, then the region. */
    private static final Pattern CHECKED_FORM = Pattern.compile("^[a-z0-9.-]+-\\d+-\\d+\\.[a-z0-9-]+\\.elb\\.");

    @Inject
    ElbV2Service loadBalancers;
    @Inject
    Route53Service zones;
    @Inject
    EmulatorConfig config;

    private static LoadBalancer publicAlb;
    private static LoadBalancer internalAlb;
    private static LoadBalancer nlb;
    private static final List<String> LISTENERS = new ArrayList<>();
    private static String zone;
    private static final List<ResourceRecordSet> ALIASES = new ArrayList<>();
    /** Captured during a test: Quarkus resets RestAssured's port once a test ends. */
    private static URI endpoint;

    @Test
    @Order(1)
    void applicationLoadBalancersGetTheAwsFormForTheirScheme() {
        try (ElasticLoadBalancingV2Client elb = elb()) {
            publicAlb = create(elb, "awsdns-7", "application", "internet-facing");
            internalAlb = create(elb, "awsdns-8", "application", "internal");
        }
        assertThat(publicAlb.dnsName()).matches("awsdns-7-\\d{10}" + Pattern.quote(ALB_SUFFIX));
        assertThat(internalAlb.dnsName()).matches("internal-awsdns-8-\\d{10}" + Pattern.quote(ALB_SUFFIX));
        assertThat(publicAlb.dnsName()).containsPattern(CHECKED_FORM);
        assertThat(internalAlb.dnsName()).containsPattern(CHECKED_FORM);
    }

    @Test
    @Order(2)
    void networkLoadBalancersCarryTheIdOfTheirArnAndNoInternalPrefix() {
        try (ElasticLoadBalancingV2Client elb = elb()) {
            nlb = create(elb, "awsdns-nlb", "network", "internal");
        }
        String id = nlb.loadBalancerArn().substring(nlb.loadBalancerArn().lastIndexOf('/') + 1);
        assertThat(nlb.dnsName()).isEqualTo("awsdns-nlb-" + id + ".elb.us-east-1.amazonaws.com");
    }

    @Test
    @Order(3)
    void theAwsNameResolvesToFlociEvenWhereAwsEndpointNamesAreSpoofed() {
        assertThat(publicAlb.dnsName()).endsWith(ALB_SUFFIX);
        EmbeddedDnsServer spoofing = server(true);
        for (String name : List.of(publicAlb.dnsName(), internalAlb.dnsName(), nlb.dnsName(),
                "dualstack." + publicAlb.dnsName(), publicAlb.dnsName().toUpperCase())) {
            assertThat(addresses(spoofing.planQuery(name, CLIENT, FLOCI, 1))).as(name).containsExactly(FLOCI);
        }
        DnsAnswer ipv6 = spoofing.planQuery(publicAlb.dnsName(), CLIENT, FLOCI, 28).answer().orElseThrow();
        assertThat(ipv6.isEmpty()).isTrue();
        assertThat(ipv6.nameExists()).isTrue();
        for (String unknown : List.of("awsdns-none-1234567890" + ALB_SUFFIX, "dualstack.awsdns-none-1234567890" + ALB_SUFFIX,
                "awsdns-none-0123456789abcdef.elb.us-east-1.amazonaws.com")) {
            for (EmbeddedDnsServer dns : List.of(spoofing, server(false))) {
                DnsAnswer answer = dns.planQuery(unknown, CLIENT, FLOCI, 1).answer().orElseThrow();
                assertThat(answer.isEmpty()).as(unknown).isTrue();
                assertThat(answer.nameExists()).as(unknown + " is NXDOMAIN, as on AWS, not forwarded").isFalse();
            }
        }
        assertThat(addresses(spoofing.planQuery("sqs.us-east-1.amazonaws.com", CLIENT, FLOCI, 1)))
                .as("a name outside the load balancer zones is spoofed as before")
                .containsExactly(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS);
        assertThat(addresses(server(false).planQuery(publicAlb.dnsName(), CLIENT, FLOCI, 1)))
                .as("answered by Floci rather than left to upstream DNS").containsExactly(FLOCI);
    }

    @Test
    @Order(4)
    void eachNameAndARoute53AliasToItSelectTheirLoadBalancer() {
        assertThat(internalAlb.dnsName()).endsWith(ALB_SUFFIX);
        String zoneName = "awsdns-" + UUID.randomUUID().toString().substring(0, 8) + ".example.test";
        String byName = "api." + zoneName;
        String byCname = "cname." + zoneName;
        try (ElasticLoadBalancingV2Client elb = elb(); Route53Client route53 = route53()) {
            LISTENERS.add(fixedResponse(elb, publicAlb, "public"));
            LISTENERS.add(fixedResponse(elb, internalAlb, "internal"));
            zone = route53.createHostedZone(r -> r.name(zoneName).callerReference(UUID.randomUUID().toString()))
                    .hostedZone().id();
            for (ResourceRecordSet record : List.of(alias(byName, internalAlb.dnsName(), internalAlb),
                    ResourceRecordSet.builder().name(byCname).type("CNAME").ttl(60L)
                            .resourceRecords(value -> value.value(publicAlb.dnsName())).build())) {
                change(route53, ChangeAction.CREATE, record);
                ALIASES.add(record);
            }
        }
        EmbeddedDnsServer dns = server(true);
        assertThat(addresses(dns.planQuery(byName, CLIENT, FLOCI, 1))).containsExactly(FLOCI);
        assertHost(publicAlb.dnsName(), "public");
        assertHost(internalAlb.dnsName(), "internal");
        assertHost(byName, "internal");
        assertHost(byCname, "public");
    }

    @AfterAll
    static void removeResources() {
        if (endpoint == null) {
            return;
        }
        try (ElasticLoadBalancingV2Client elb = elb(); Route53Client route53 = route53()) {
            ALIASES.forEach(record -> change(route53, ChangeAction.DELETE, record));
            if (zone != null) {
                route53.deleteHostedZone(r -> r.id(zone));
            }
            LISTENERS.forEach(listener -> elb.deleteListener(r -> r.listenerArn(listener)));
            for (LoadBalancer balancer : new LoadBalancer[] {publicAlb, internalAlb, nlb}) {
                if (balancer != null) {
                    elb.deleteLoadBalancer(r -> r.loadBalancerArn(balancer.loadBalancerArn()));
                }
            }
        }
    }

    private EmbeddedDnsServer server(boolean publicAwsApi) {
        List<DnsRecordSource> sources = new ArrayList<>();
        DnsClientVpcSource clients = address -> Optional.of(
                new DnsClientVpcSource.ClientVpc("000000000000", "us-east-1", "vpc-0a1b2c3d4e5f60718"));
        EmbeddedDnsServer dns = spy(new EmbeddedDnsServer(List.of(), sources, List.of(), List.of(clients),
                publicAwsApi));
        doReturn(Optional.of(FLOCI)).when(dns).getServerIp();
        sources.add(new Route53DnsRecordSource(zones, config, new DnsLookupHelper(), dns));
        sources.add(new ElbV2DnsRecordSource(loadBalancers, dns, null));
        return dns;
    }

    private static List<String> addresses(EmbeddedDnsServer.QueryPlan plan) {
        return plan.answer().map(DnsAnswer::addresses).orElse(List.of());
    }

    private static LoadBalancer create(ElasticLoadBalancingV2Client elb, String name, String type, String scheme) {
        List<String> subnets = "application".equals(type)
                ? List.of(Ec2Service.defaultSubnetId("us-east-1", "a"), Ec2Service.defaultSubnetId("us-east-1", "b"))
                : List.of(Ec2Service.defaultSubnetId("us-east-1", "a"));
        return elb.createLoadBalancer(r -> r.name(name).type(type).scheme(scheme).subnets(subnets))
                .loadBalancers().getFirst();
    }

    private static String fixedResponse(ElasticLoadBalancingV2Client elb, LoadBalancer balancer, String body) {
        return elb.createListener(r -> r.loadBalancerArn(balancer.loadBalancerArn()).protocol(ProtocolEnum.HTTP)
                .port(LISTENER_PORT).defaultActions(action -> action.type(ActionTypeEnum.FIXED_RESPONSE)
                        .fixedResponseConfig(fixed -> fixed.statusCode("200").contentType("text/plain")
                                .messageBody(body)))).listeners().getFirst().listenerArn();
    }

    private static void assertHost(String host, String body) {
        given().baseUri("http://127.0.0.1").port(LISTENER_PORT).header("Host", host)
            .when().get("/")
            .then().statusCode(200).body(equalTo(body));
    }

    /** An A alias to a load balancer's name. */
    private static ResourceRecordSet alias(String name, String target, LoadBalancer balancer) {
        return ResourceRecordSet.builder().name(name).type("A").aliasTarget(AliasTarget.builder()
                .dnsName(target + ".").hostedZoneId(balancer.canonicalHostedZoneId())
                .evaluateTargetHealth(false).build()).build();
    }

    private static void change(Route53Client route53, ChangeAction action, ResourceRecordSet record) {
        route53.changeResourceRecordSets(r -> r.hostedZoneId(zone).changeBatch(batch -> batch.changes(
                Change.builder().action(action).resourceRecordSet(record).build())));
    }

    private static ElasticLoadBalancingV2Client elb() {
        return AwsTestClients.create(ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1,
                endpoint());
    }

    private static Route53Client route53() {
        return AwsTestClients.create(Route53Client.builder(), "000000000000", Region.US_EAST_1, endpoint());
    }

    private static URI endpoint() {
        if (endpoint == null) {
            endpoint = URI.create("http://localhost:" + RestAssured.port);
        }
        return endpoint;
    }
}
