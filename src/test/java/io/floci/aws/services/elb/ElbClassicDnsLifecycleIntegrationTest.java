package io.floci.aws.services.elb;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ElbClassicDnsLifecycleIntegrationTest {
    @Inject
    ElbClassicService service;

    @Test
    void sdkUpdatesAndDeletionKeepDnsOwnershipAcrossAccounts() throws Exception {
        String name = "dns-" + UUID.randomUUID().toString().substring(0, 12);
        try (ElasticLoadBalancingClient first = AwsTestClients.create(
                ElasticLoadBalancingClient.builder(), "111111111111", Region.US_EAST_1);
             ElasticLoadBalancingClient second = AwsTestClients.create(
                     ElasticLoadBalancingClient.builder(), "222222222222", Region.US_EAST_1)) {
            String firstDns = create(first, name);
            try (AutoCloseable firstCleanup = () -> first.deleteLoadBalancer(r -> r.loadBalancerName(name))) {
                String secondDns = create(second, name);
                try (AutoCloseable secondCleanup = () -> second.deleteLoadBalancer(r -> r.loadBalancerName(name))) {
                    assertEquals(1, service.findByDnsName(firstDns).size());
                    assertEquals(1, service.findByDnsName(secondDns).size());
                    assertEquals("111111111111", service.findByDnsName(firstDns).getFirst().getAccountId());
                    assertEquals("222222222222", service.findByDnsName(secondDns).getFirst().getAccountId());
                    assertEquals(1, service.findByDnsName(firstDns.toUpperCase(Locale.ROOT) + ".").size());

                    first.createLoadBalancerListeners(r -> r.loadBalancerName(name).listeners(
                            Listener.builder().protocol("TCP").loadBalancerPort(81).instancePort(8081).build()));
                    assertEquals(List.of(80, 81), first.describeLoadBalancers(r -> r.loadBalancerNames(name))
                            .loadBalancerDescriptions().getFirst().listenerDescriptions().stream()
                            .map(description -> description.listener().loadBalancerPort()).sorted().toList());
                    assertEquals(2, service.findByDnsName(firstDns).getFirst().getListeners().size());
                    assertEquals(1, service.findByDnsName(secondDns).getFirst().getListeners().size());

                    first.deleteLoadBalancerListeners(r -> r.loadBalancerName(name).loadBalancerPorts(81));
                    assertEquals(1, service.findByDnsName(firstDns).getFirst().getListeners().size());
                    first.deleteLoadBalancer(r -> r.loadBalancerName(name));
                    assertTrue(service.findByDnsName(firstDns).isEmpty());
                    assertEquals(secondDns, second.describeLoadBalancers(r -> r.loadBalancerNames(name))
                            .loadBalancerDescriptions().getFirst().dnsName());
                    assertEquals(1, service.findByDnsName(secondDns).size());
                }
                assertTrue(service.findByDnsName(secondDns).isEmpty());
            }
            assertTrue(service.findByDnsName(firstDns).isEmpty());
        }
    }

    private static String create(ElasticLoadBalancingClient client, String name) {
        return client.createLoadBalancer(r -> r.loadBalancerName(name).availabilityZones("us-east-1a")
                .listeners(Listener.builder().protocol("HTTP").loadBalancerPort(80).instancePort(8080).build()))
                .dnsName();
    }
}
