package io.floci.aws.services.amazonmq;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.mq.MqClient;
import software.amazon.awssdk.services.mq.model.Configuration;
import software.amazon.awssdk.services.mq.model.CreateConfigurationResponse;
import software.amazon.awssdk.services.mq.model.DescribeConfigurationResponse;
import software.amazon.awssdk.services.mq.model.EngineType;
import software.amazon.awssdk.services.mq.model.ListConfigurationRevisionsResponse;
import software.amazon.awssdk.services.mq.model.ListConfigurationsResponse;
import software.amazon.awssdk.services.mq.model.MqException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AmazonMqConfigurationPagingIntegrationTest {

    private static final String ACCOUNT = "591100001211";
    private final List<Owned> owned = new ArrayList<>();
    private final List<MqClient> clients = new ArrayList<>();
    private String marker;
    private MqClient client;

    private record Owned(MqClient client, CreateConfigurationResponse identity, String name, Map<String, String> tags) {}

    @BeforeEach
    void setup() {
        marker = "mq-page-" + UUID.randomUUID().toString().substring(0, 8);
        client = client(ACCOUNT, "us-east-1");
    }

    @AfterEach
    void cleanup() {
        List<AssertionError> failures = new ArrayList<>();
        for (Owned receipt : owned.reversed()) {
            try {
                DescribeConfigurationResponse current;
                try {
                    current = receipt.client().describeConfiguration(request -> request.configurationId(receipt.identity().id()));
                } catch (MqException absent) {
                    assertEquals(404, absent.statusCode());
                    continue;
                }
                assertEquals(receipt.identity().arn(), current.arn());
                assertEquals(receipt.identity().created(), current.created());
                assertEquals(receipt.name(), current.name());
                assertEquals(receipt.tags(), current.tags());
                receipt.client().deleteConfiguration(request -> request.configurationId(current.id()));
                assertEquals(404, assertThrows(MqException.class, () -> receipt.client().describeConfiguration(request ->
                        request.configurationId(current.id()))).statusCode());
            } catch (RuntimeException | AssertionError failure) {
                failures.add(new AssertionError("Owned paging configuration cleanup failed", failure));
            }
        }
        clients.forEach(MqClient::close);
        assertAll(failures.stream().map(failure -> () -> { throw failure; }));
    }

    @Test
    void sdkDefaultPagesTwentyAndCompletesBoundedKeysetTraversal() {
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 23; index++) {
            expected.add(create(client, "config-" + index).identity().id());
        }
        expected.sort(Comparator.naturalOrder());
        ListConfigurationsResponse first = client.listConfigurations();
        assertEquals(20, first.configurations().size());
        assertNotNull(first.nextToken());
        assertEquals(expected.subList(0, 20), first.configurations().stream().map(Configuration::id).toList());
        ListConfigurationsResponse finalPage = client.listConfigurations(request -> request.nextToken(first.nextToken()));
        assertEquals(expected.subList(20, 23), finalPage.configurations().stream().map(Configuration::id).toList());
        assertNull(finalPage.nextToken());
        Set<String> seen = new HashSet<>();
        String token = null;
        int pages = 0;
        do {
            String requested = token;
            ListConfigurationsResponse page = client.listConfigurations(request -> request.maxResults(5).nextToken(requested));
            for (Configuration row : page.configurations()) {
                assertTrue(seen.add(row.id()));
            }
            token = page.nextToken();
            assertTrue(++pages <= 5);
        } while (token != null);
        assertEquals(5, pages);
        assertEquals(Set.copyOf(expected), seen);
        assertEquals(400, assertThrows(MqException.class, () -> client.listConfigurations(request -> request
                .maxResults(6).nextToken(first.nextToken()))).statusCode());
        assertEquals(400, assertThrows(MqException.class,
                () -> client.listConfigurations(request -> request.maxResults(4))).statusCode());
    }

    @Test
    void revisionPagingUsesNumericOrderAndRejectsCrossParentMarkers() {
        Owned a = create(client, "a");
        Owned b = create(client, "b");
        for (int index = 2; index <= 23; index++) {
            int revision = index;
            client.updateConfiguration(request -> request.configurationId(a.identity().id()).data("eA==")
                    .description("revision-" + revision));
        }
        ListConfigurationRevisionsResponse first = client.listConfigurationRevisions(request -> request
                .configurationId(a.identity().id()));
        assertEquals(20, first.revisions().size());
        assertEquals(20, first.revisions().getLast().revision());
        ListConfigurationRevisionsResponse last = client.listConfigurationRevisions(request -> request
                .configurationId(a.identity().id()).nextToken(first.nextToken()));
        assertEquals(List.of(21, 22, 23), last.revisions().stream().map(row -> row.revision()).toList());
        assertNull(last.nextToken());
        assertEquals(400, assertThrows(MqException.class, () -> client.listConfigurationRevisions(request -> request
                .configurationId(b.identity().id()).nextToken(first.nextToken()))).statusCode());
    }

    // Default emulation serves every enabled service in every partition; this does not assert AWS endpoint availability.
    @ParameterizedTest
    @CsvSource({"cn-north-1,aws-cn", "us-gov-west-1,aws-us-gov", "us-iso-east-1,aws-iso",
            "us-isob-east-1,aws-iso-b", "eu-isoe-west-1,aws-iso-e", "us-isof-south-1,aws-iso-f",
            "eusc-de-east-1,aws-eusc"})
    void requestRegionAndPartitionAreStoredAndOtherScopesCannotRead(String region, String partition) {
        MqClient regional = client(ACCOUNT, region);
        Owned created = create(regional, "partition");
        assertTrue(created.identity().arn().startsWith("arn:" + partition + ":mq:" + region + ":" + ACCOUNT
                + ":configuration:"));
        assertEquals(created.identity().arn(), regional.describeConfiguration(request -> request
                .configurationId(created.identity().id())).arn());
        assertEquals(404, assertThrows(MqException.class, () -> client.describeConfiguration(request -> request
                .configurationId(created.identity().id()))).statusCode());
        MqClient otherAccount = client("591100001212", region);
        assertEquals(404, assertThrows(MqException.class, () -> otherAccount.describeConfiguration(request -> request
                .configurationId(created.identity().id()))).statusCode());
        assertFalse(otherAccount.listConfigurations().configurations().stream()
                .anyMatch(row -> row.id().equals(created.identity().id())));
    }

    private MqClient client(String account, String region) {
        MqClient created = AwsTestClients.create(MqClient.builder(), account, Region.of(region));
        clients.add(created);
        return created;
    }

    private Owned create(MqClient target, String suffix) {
        String name = marker + "-" + suffix;
        Map<String, String> tags = Map.of("owner", marker);
        CreateConfigurationResponse created = target.createConfiguration(request -> request.name(name)
                .engineType(EngineType.RABBITMQ).engineVersion("3.13").tags(tags));
        Owned receipt = new Owned(target, created, name, tags);
        owned.add(receipt);
        assertNotNull(created.id());
        assertNotNull(created.arn());
        return receipt;
    }
}
