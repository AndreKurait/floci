package io.floci.aws.services.elbv2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateLoadBalancerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DuplicateLoadBalancerNameException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerNotFoundException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ElbV2CreateIdempotencyIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void sameSettingsReturnTheOriginalLoadBalancer(String region) {
        try (Fixture fixture = new Fixture(region)) {
            CreateLoadBalancerRequest request = fixture.request().build();
            LoadBalancer original = fixture.create(request);
            String listener = fixture.elb.createListener(r -> r.loadBalancerArn(original.loadBalancerArn())
                    .protocol("HTTP").port(80).defaultActions(a -> a.type("fixed-response")
                            .fixedResponseConfig(c -> c.statusCode("200").contentType("text/plain").messageBody("kept"))))
                    .listeners().getFirst().listenerArn();
            fixture.elb.modifyLoadBalancerAttributes(r -> r.loadBalancerArn(original.loadBalancerArn())
                    .attributes(a -> a.key("idle_timeout.timeout_seconds").value("120")));

            LoadBalancer repeated = assertDoesNotThrow(() -> fixture.create(request));

            assertEquals(original.loadBalancerArn(), repeated.loadBalancerArn());
            assertEquals(original.dnsName(), repeated.dnsName());
            assertEquals(original.createdTime(), repeated.createdTime());
            assertEquals(List.of(listener), fixture.elb.describeListeners(r ->
                    r.loadBalancerArn(original.loadBalancerArn())).listeners().stream().map(l -> l.listenerArn()).toList());
            assertTrue(fixture.elb.describeLoadBalancerAttributes(r -> r.loadBalancerArn(original.loadBalancerArn()))
                    .attributes().stream().anyMatch(a -> a.key().equals("idle_timeout.timeout_seconds")
                            && a.value().equals("120")));
            assertEquals(1, fixture.elb.describeLoadBalancers(r -> r.names(fixture.name)).loadBalancers().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void omittedDefaultsAndReorderedSetsAreTheSameRequest(String region) {
        try (Fixture fixture = new Fixture(region)) {
            LoadBalancer original = fixture.create(fixture.request().build());
            CreateLoadBalancerRequest reordered = fixture.request().type("application")
                    .scheme("internet-facing").ipAddressType("ipv4")
                    .subnets(fixture.subnets.reversed()).securityGroups(fixture.groups.reversed())
                    .tags(Tag.builder().key("second").value("two").build(),
                            Tag.builder().key("first").value("one").build()).build();

            LoadBalancer repeated = assertDoesNotThrow(() -> fixture.create(reordered));

            assertEquals(original.loadBalancerArn(), repeated.loadBalancerArn());
            assertEquals(original.dnsName(), repeated.dnsName());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void concurrentIdenticalCreatesHaveOneResource(String region) {
        try (Fixture fixture = new Fixture(region);
             ExecutorService executor = Executors.newFixedThreadPool(8)) {
            CreateLoadBalancerRequest request = fixture.request().build();
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<LoadBalancer>> responses = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                responses.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return fixture.create(request);
                }));
            }
            try {
                assertTrue(assertDoesNotThrow(() -> ready.await(10, TimeUnit.SECONDS)));
            } finally {
                start.countDown();
            }
            List<LoadBalancer> created = assertDoesNotThrow(() -> {
                List<LoadBalancer> values = new ArrayList<>();
                for (Future<LoadBalancer> response : responses) {
                    values.add(response.get(30, TimeUnit.SECONDS));
                }
                return values;
            });
            assertEquals(1, created.stream().map(LoadBalancer::loadBalancerArn).distinct().count());
            assertEquals(1, fixture.elb.describeLoadBalancers(r -> r.names(fixture.name)).loadBalancers().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void conflictingSettingsCannotReuseTheName(String region) {
        try (Fixture fixture = new Fixture(region)) {
            LoadBalancer original = fixture.create(fixture.request().build());
            List<CreateLoadBalancerRequest> conflicts = List.of(
                    fixture.request().type("network").build(),
                    fixture.request().scheme("internal").build(),
                    fixture.request().ipAddressType("dualstack").build(),
                    fixture.request().subnets(fixture.subnets.getFirst()).build(),
                    fixture.request().securityGroups(fixture.groups.getFirst()).build(),
                    fixture.request().tags(Tag.builder().key("first").value("changed").build()).build());
            for (CreateLoadBalancerRequest request : conflicts) {
                DuplicateLoadBalancerNameException error = assertThrows(DuplicateLoadBalancerNameException.class,
                        () -> fixture.create(request));
                assertEquals(400, error.statusCode());
                assertEquals("DuplicateLoadBalancerName", error.awsErrorDetails().errorCode());
            }
            assertEquals(original.loadBalancerArn(), fixture.elb.describeLoadBalancers(r -> r.names(fixture.name))
                    .loadBalancers().getFirst().loadBalancerArn());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final String name = "sdk-retry-" + UUID.randomUUID().toString().substring(0, 8);
        private final Ec2Client ec2;
        private final ElasticLoadBalancingV2Client elb;
        private final String vpc;
        private final List<String> subnets = new ArrayList<>();
        private final List<String> groups = new ArrayList<>();

        private Fixture(String regionName) {
            Region region = Region.of(regionName);
            ec2 = AwsTestClients.create(Ec2Client.builder(), "000000000000", region);
            elb = AwsTestClients.create(ElasticLoadBalancingV2Client.builder(), "000000000000", region);
            vpc = ec2.createVpc(r -> r.cidrBlock("10.41.0.0/16")).vpc().vpcId();
            try {
                for (int i = 0; i < 2; i++) {
                    String zone = regionName + (char) ('a' + i);
                    String cidr = "10.41." + (i + 1) + ".0/24";
                    subnets.add(ec2.createSubnet(r -> r.vpcId(vpc).availabilityZone(zone).cidrBlock(cidr))
                            .subnet().subnetId());
                    String groupName = name + "-" + i;
                    groups.add(ec2.createSecurityGroup(r -> r.vpcId(vpc).groupName(groupName).description("retry test"))
                            .groupId());
                }
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private CreateLoadBalancerRequest.Builder request() {
            return CreateLoadBalancerRequest.builder().name(name).subnets(subnets).securityGroups(groups)
                    .tags(Tag.builder().key("first").value("one").build(),
                            Tag.builder().key("second").value("two").build());
        }

        private LoadBalancer create(CreateLoadBalancerRequest request) {
            return elb.createLoadBalancer(request).loadBalancers().getFirst();
        }

        @Override
        public void close() {
            try {
                try {
                    for (LoadBalancer balancer : elb.describeLoadBalancers(r -> r.names(name)).loadBalancers()) {
                        elb.deleteLoadBalancer(r -> r.loadBalancerArn(balancer.loadBalancerArn()));
                    }
                } catch (LoadBalancerNotFoundException ignored) {
                    // A failed create has no load balancer to remove.
                }
                groups.forEach(group -> ec2.deleteSecurityGroup(r -> r.groupId(group)));
                subnets.forEach(subnet -> ec2.deleteSubnet(r -> r.subnetId(subnet)));
                ec2.deleteVpc(r -> r.vpcId(vpc));
            } finally {
                elb.close();
                ec2.close();
            }
        }
    }
}
