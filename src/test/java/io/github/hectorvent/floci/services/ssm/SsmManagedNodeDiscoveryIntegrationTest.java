package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DescribeInstanceInformationResponse;
import software.amazon.awssdk.services.ssm.model.InstanceInformation;
import software.amazon.awssdk.services.ssm.model.InstanceInformationFilter;
import software.amazon.awssdk.services.ssm.model.InstanceInformationStringFilter;
import software.amazon.awssdk.services.ssm.model.InvalidFilterKeyException;
import software.amazon.awssdk.services.ssm.model.InvalidInstanceIdException;
import software.amazon.awssdk.services.ssm.model.InvalidInstanceInformationFilterValueException;
import software.amazon.awssdk.services.ssm.model.InvalidNextTokenException;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmManagedNodeDiscoveryIntegrationTest {
    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1"})
    void modernAndLegacyFiltersSelectOnlyMatchingRegisteredNodes(String region) {
        try (Cloud cloud = new Cloud(region)) {
            String linux = cloud.node("Linux", "3.3.5700.0");
            String windows = cloud.node("Windows", "3.3.5700.0");
            cloud.node("Linux", "3.2.0.0");
            assertEquals(Set.of(linux), ids(cloud.ssm.describeInstanceInformation(request -> request.filters(
                    filter("InstanceIds", linux)))));
            assertEquals(Set.of(linux, windows), ids(cloud.ssm.describeInstanceInformation(request -> request.filters(
                    filter("AgentVersion", "3.3.5700.0"), filter("PlatformTypes", "Linux", "Windows"),
                    filter("ResourceType", "EC2Instance"), filter("PingStatus", "Online")))));
            assertEquals(Set.of(windows), ids(cloud.ssm.describeInstanceInformation(request ->
                    request.instanceInformationFilterList(InstanceInformationFilter.builder()
                            .key("InstanceIds").valueSet(windows).build()))));
            assertTrue(cloud.ssm.describeInstanceInformation(request -> request.filters(
                    filter("AgentVersion", "unmatched"))).instanceInformationList().isEmpty());
        }
    }

    @Test
    void paginationDefaultsToTenAndRejectsAnotherOwnerRegionOrSelection() {
        try (Cloud cloud = new Cloud("us-west-2"); Cloud other = new Cloud("us-west-2");
             SsmClient anotherRegion = cloud.ssm("us-east-1")) {
            for (int count = 0; count < 12; count++) {
                cloud.node("Linux", "3.3.5700.0");
            }
            DescribeInstanceInformationResponse first = cloud.ssm.describeInstanceInformation(request -> {});
            assertEquals(10, first.instanceInformationList().size());
            assertNotNull(first.nextToken());
            DescribeInstanceInformationResponse last = cloud.ssm.describeInstanceInformation(request ->
                    request.nextToken(first.nextToken()));
            assertEquals(2, last.instanceInformationList().size());
            assertTrue(last.nextToken() == null || last.nextToken().isEmpty());
            Set<String> all = new HashSet<>(ids(first));
            all.addAll(ids(last));
            assertEquals(Set.copyOf(cloud.instances), all);
            assertEquals(5, cloud.ssm.describeInstanceInformation(request -> request.maxResults(5))
                    .instanceInformationList().size());
            assertThrows(InvalidNextTokenException.class, () -> other.ssm.describeInstanceInformation(request ->
                    request.nextToken(first.nextToken())));
            assertThrows(InvalidNextTokenException.class, () -> anotherRegion.describeInstanceInformation(request ->
                    request.nextToken(first.nextToken())));
            assertThrows(InvalidNextTokenException.class, () -> cloud.ssm.describeInstanceInformation(request ->
                    request.nextToken(first.nextToken()).filters(filter("PlatformTypes", "Linux"))));
        }
    }

    @Test
    void unknownAndForeignNodesDoNotSatisfyReadiness() {
        try (Cloud cloud = new Cloud("us-east-1"); Cloud other = new Cloud("us-east-1");
             SsmClient anotherRegion = cloud.ssm("us-west-2")) {
            String node = cloud.node("Linux", "3.3.5700.0");
            assertThrows(InvalidInstanceIdException.class, () -> other.ssm.describeInstanceInformation(request ->
                    request.filters(filter("InstanceIds", node))));
            assertThrows(InvalidInstanceIdException.class, () -> anotherRegion.describeInstanceInformation(request ->
                    request.filters(filter("InstanceIds", node))));
            assertThrows(InvalidInstanceIdException.class, () -> cloud.ssm.describeInstanceInformation(request ->
                    request.filters(filter("InstanceIds", node, "i-0123456789abcdef0"))));
            String unregistered = cloud.launch();
            assertThrows(InvalidInstanceIdException.class, () -> cloud.ssm.describeInstanceInformation(request ->
                    request.filters(filter("InstanceIds", unregistered))));
        }
    }

    @Test
    void stoppedAndTerminatedEc2NodesAreNotReturnedAsOnline() {
        try (Cloud cloud = new Cloud("us-east-1")) {
            String node = cloud.node("Linux", "3.3.5700.0");
            cloud.ec2.stopInstances(request -> request.instanceIds(node));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTrue(
                    cloud.ssm.describeInstanceInformation(request -> {}).instanceInformationList().isEmpty()));
            cloud.ec2.startInstances(request -> request.instanceIds(node));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(Set.of(node),
                    ids(cloud.ssm.describeInstanceInformation(request -> request.filters(filter("InstanceIds", node))))));
            cloud.ec2.terminateInstances(request -> request.instanceIds(node));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTrue(
                    cloud.ssm.describeInstanceInformation(request -> {}).instanceInformationList().isEmpty()));
        }
    }

    @Test
    void invalidAndUnimplementedFiltersDoNotReturnUnfilteredSuccess() {
        try (Cloud cloud = new Cloud("us-east-1")) {
            assertThrows(InvalidFilterKeyException.class, () -> cloud.ssm.describeInstanceInformation(request ->
                    request.filters(filter("Typo", "value"))));
            assertThrows(InvalidFilterKeyException.class, () -> cloud.ssm.describeInstanceInformation(request ->
                    request.filters(filter("PingStatus", "Online")).instanceInformationFilterList(
                            InstanceInformationFilter.builder().key("PingStatus").valueSet("Online").build())));
            for (InstanceInformationStringFilter invalid : List.of(filter("PingStatus", "online"),
                    filter("PlatformTypes", "Other"), filter("ResourceType", "Any"), filter("InstanceIds"),
                    filter("AgentVersion", ""))) {
                assertThrows(InvalidInstanceInformationFilterValueException.class, () ->
                        cloud.ssm.describeInstanceInformation(request -> request.filters(invalid)));
            }
            SsmException unsupported = assertThrows(SsmException.class, () ->
                    cloud.ssm.describeInstanceInformation(request -> request.filters(filter("tag:Purpose", "test"))));
            assertEquals("UnsupportedOperation", unsupported.awsErrorDetails().errorCode());
            for (int invalid : new int[]{0, 4, 51}) {
                SsmException error = assertThrows(SsmException.class, () ->
                        cloud.ssm.describeInstanceInformation(request -> request.maxResults(invalid)));
                assertEquals("ValidationException", error.awsErrorDetails().errorCode());
            }
        }
    }

    private static InstanceInformationStringFilter filter(String key, String... values) {
        return InstanceInformationStringFilter.builder().key(key).values(values).build();
    }

    private static Set<String> ids(DescribeInstanceInformationResponse response) {
        Set<String> result = new HashSet<>();
        for (InstanceInformation node : response.instanceInformationList()) {
            assertEquals("Online", node.pingStatusAsString());
            assertNotNull(node.lastPingDateTime());
            assertTrue(result.add(node.instanceId()), "Duplicate managed-node result");
        }
        return result;
    }

    private static final class Cloud implements AutoCloseable {
        private final String account = String.format("%012d",
                Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 999_999_999_999L));
        private final String region;
        private final List<String> instances = new ArrayList<>();
        private final StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(account, "test"));
        private final URI endpoint = URI.create("http://localhost:" + RestAssured.port);
        private final Ec2Client ec2;
        private final SsmClient ssm;

        private Cloud(String region) {
            this.region = region;
            ssm = ssm(region);
            ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        }

        private SsmClient ssm(String selectedRegion) {
            return SsmClient.builder().endpointOverride(endpoint).region(Region.of(selectedRegion))
                    .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        }

        private String launch() {
            String id = ec2.runInstances(request -> request.imageId("ami-1234567890abcdef0")
                    .instanceType("t3.micro").minCount(1).maxCount(1)).instances().getFirst().instanceId();
            instances.add(id);
            return id;
        }

        private String node(String platform, String agentVersion) {
            String id = launch();
            // This agent-only operation is absent from the public Java SDK.
            given().contentType("application/x-amz-json-1.1")
                    .header("X-Amz-Target", "AmazonSSM.UpdateInstanceInformation")
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20261105/" + region
                            + "/ssm/aws4_request, SignedHeaders=host, Signature=example")
                    .body(Map.of("InstanceId", id, "AgentVersion", agentVersion, "PlatformType", platform))
                    .post("/").then().statusCode(200);
            return id;
        }

        @Override
        public void close() {
            try {
                if (!instances.isEmpty()) {
                    ec2.terminateInstances(request -> request.instanceIds(instances));
                }
            } finally {
                ec2.close();
                ssm.close();
            }
        }
    }
}
