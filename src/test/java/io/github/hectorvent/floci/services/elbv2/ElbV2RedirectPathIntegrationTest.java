package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RedirectActionConfig;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2RedirectPathIntegrationTest {

    private final List<Executable> cleanup = new ArrayList<>();
    private ElasticLoadBalancingV2Client elb;
    private String loadBalancerArn;
    private int listenerPort;

    @TestHTTPResource
    URI endpoint;

    @BeforeEach
    void createLoadBalancer() throws IOException {
        Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "321321321329", Region.EU_WEST_1, endpoint);
        cleanup.add(ec2::close);
        elb = AwsTestClients.create(ElasticLoadBalancingV2Client.builder(), "321321321329", Region.EU_WEST_1, endpoint);
        cleanup.add(elb::close);
        String vpc = ec2.createVpc(r -> r.cidrBlock("10.89.0.0/16")).vpc().vpcId();
        cleanup.add(() -> ec2.deleteVpc(r -> r.vpcId(vpc)));
        List<String> subnets = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            String cidr = "10.89." + index + ".0/24";
            String zone = "eu-west-1" + (char) ('a' + index);
            String subnet = ec2.createSubnet(r -> r.vpcId(vpc).cidrBlock(cidr).availabilityZone(zone))
                    .subnet().subnetId();
            subnets.add(subnet);
            cleanup.add(() -> ec2.deleteSubnet(r -> r.subnetId(subnet)));
        }
        String name = "redirect-" + UUID.randomUUID().toString().substring(0, 8);
        loadBalancerArn = elb.createLoadBalancer(r -> r.name(name).type("application").subnets(subnets))
                .loadBalancers().getFirst().loadBalancerArn();
        cleanup.add(() -> elb.deleteLoadBalancer(r -> r.loadBalancerArn(loadBalancerArn)));
        listenerPort = FreePorts.anyInterfacePort();
    }

    @AfterEach
    void removeOwnedResources() {
        assertAll(cleanup.reversed());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "<omitted>", textBlock = """
            /probe?value=1 | /#{path}     | /probe?value=1
            /              | /#{path}     | /
            /a/b           | /new/#{path} | /new/a/b
            //a//b         | /#{path}     | //a//b
            /a%2Fb         | /#{path}     | /a%2Fb
            /probe?value=1 | <omitted>    | /probe?value=1
            /probe?value=1 | /literal     | /literal?value=1
            """)
    void redirectsPreserveThePathWithoutAddingASlash(String requestTarget, String redirectPath, String expectedPath)
            throws Exception {
        createRedirectListener(redirectPath, "#{query}");

        assertRedirect(requestTarget, expectedPath);
    }

    @Test
    void pathPlaceholderAlsoOmitsTheLeadingSlashInAQueryTemplate() throws Exception {
        createRedirectListener("/landing", "path=#{path}&#{query}");

        assertRedirect("/a/b?value=1", "/landing?path=a/b&value=1");
    }

    private void createRedirectListener(String path, String query) {
        RedirectActionConfig redirect = RedirectActionConfig.builder()
                .protocol("HTTPS").host("redirect.example.test").port("443")
                .path(path).query(query).statusCode("HTTP_301").build();
        String listener = elb.createListener(r -> r.loadBalancerArn(loadBalancerArn).protocol("HTTP")
                        .port(listenerPort).defaultActions(Action.builder().type("redirect")
                                .redirectConfig(redirect).build()))
                .listeners().getFirst().listenerArn();
        cleanup.add(() -> elb.deleteListener(r -> r.listenerArn(listener)));
        await().atMost(Duration.ofSeconds(5)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> {
                    try (Socket socket = new Socket("127.0.0.1", listenerPort)) {
                        assertTrue(socket.isConnected());
                    }
                });
    }

    private void assertRedirect(String requestTarget, String expectedPath) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            URI uri = URI.create("http://127.0.0.1:" + listenerPort + requestTarget);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(301, response.statusCode());
            assertEquals("https://redirect.example.test:443" + expectedPath,
                    response.headers().firstValue("Location").orElseThrow());
        }
    }
}
