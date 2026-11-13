package io.github.hectorvent.floci.services.ssm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ListResourceDataSyncResponse;
import software.amazon.awssdk.services.ssm.model.ResourceDataSyncItem;
import software.amazon.awssdk.services.ssm.model.ResourceDataSyncSource;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmSourceSyncSdkIntegrationTest {
    private static final String SOURCE = "SyncFromSource";
    private static final AtomicLong ACCOUNTS = new AtomicLong(873033330000L);

    @Test
    void listsAnEmptySourceCollection() {
        try (Scenario scenario = new Scenario("us-east-1")) {
            assertEquals(Map.of(), scenario.all());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void sourceConfigurationRoundTripsWithoutReportingAnExport(String region) {
        try (Scenario scenario = new Scenario(region)) {
            ResourceDataSyncSource source = source(region);
            scenario.create("source", source);
            ResourceDataSyncItem item = scenario.all().get("source");
            assertEquals(SOURCE, item.syncType());
            assertSource(source, item);
            assertNotNull(item.syncCreatedTime());
            assertNotNull(item.syncLastModifiedTime());
            assertNull(item.s3Destination());
            assertNull(item.lastStatus());
            assertNull(item.lastSyncTime());
            assertNull(item.lastSuccessfulSyncTime());
            assertNull(item.lastSyncStatusMessage());
        }
    }

    @Test
    void updateReplacesTheCompleteSourceAndPreservesBothKindsOfSibling() {
        try (Scenario scenario = new Scenario("us-east-1");
             Scenario otherRegion = new Scenario(scenario.account, "us-west-2")) {
            scenario.create("source", source(scenario.region));
            scenario.create("sibling", source(scenario.region));
            otherRegion.create("source", source(otherRegion.region));
            Map<String, ResourceDataSyncItem> before = scenario.all();
            Map<String, ResourceDataSyncItem> regionalBefore = otherRegion.all();
            ResourceDataSyncSource replacement = replacement();
            scenario.client.updateResourceDataSync(request -> request.syncName("source")
                    .syncType(SOURCE).syncSource(replacement));
            Map<String, ResourceDataSyncItem> after = scenario.all();
            assertSource(replacement, after.get("source"));
            assertEquals(before.get("source").syncCreatedTime(), after.get("source").syncCreatedTime());
            assertFalse(after.get("source").syncLastModifiedTime()
                    .isBefore(before.get("source").syncLastModifiedTime()));
            assertEquals(before.get("sibling"), after.get("sibling"));
            assertEquals(regionalBefore, otherRegion.all());
            assertEquals(before.keySet(), after.keySet());
        }
    }

    @Test
    void sourcePagesContainTheWholeCollectionAndTokensStayScoped() {
        try (Scenario scenario = new Scenario("us-east-1");
             Scenario foreign = new Scenario("us-east-1");
             Scenario otherRegion = new Scenario(scenario.account, "us-west-2")) {
            scenario.create("a-source", source(scenario.region));
            scenario.create("b-source", source(scenario.region));
            scenario.create("c-source", source(scenario.region));
            Map<String, ResourceDataSyncItem> whole = scenario.all();
            ListResourceDataSyncResponse first = scenario.client.listResourceDataSync(
                    request -> request.syncType(SOURCE).maxResults(1));
            assertEquals(1, first.resourceDataSyncItems().size());
            assertNotNull(first.nextToken());
            assertEquals(whole, scenario.all(1));
            assertEquals(Map.of(), foreign.all());
            assertEquals(Map.of(), otherRegion.all());
            assertTrue(scenario.client.listResourceDataSync(request -> request.syncType("SyncToDestination"))
                    .resourceDataSyncItems().isEmpty());
            assertError("InvalidNextToken", () -> foreign.client.listResourceDataSync(
                    request -> request.syncType(SOURCE).nextToken(first.nextToken())));
            assertError("InvalidNextToken", () -> otherRegion.client.listResourceDataSync(
                    request -> request.syncType(SOURCE).nextToken(first.nextToken())));
            assertError("InvalidNextToken", () -> scenario.client.listResourceDataSync(
                    request -> request.syncType("SyncToDestination").nextToken(first.nextToken())));
        }
    }

    @Test
    void duplicateCreationPreservesEveryStoredField() {
        try (Scenario scenario = new Scenario("us-east-1")) {
            scenario.create("source", source(scenario.region));
            scenario.create("sibling", source(scenario.region));
            Map<String, ResourceDataSyncItem> before = scenario.all();
            assertError("ResourceDataSyncAlreadyExistsException",
                    () -> scenario.client.createResourceDataSync(request -> request.syncName("source")
                            .syncType(SOURCE).syncSource(replacement())));
            assertEquals(before, scenario.all());
        }
    }

    @Test
    void accountOptionChangeRefusalPreservesEveryStoredField() {
        try (Scenario scenario = new Scenario("us-east-1")) {
            scenario.create("source", source(scenario.region));
            scenario.create("sibling", source(scenario.region));
            Map<String, ResourceDataSyncItem> before = scenario.all();
            ResourceDataSyncSource organization = replacement().toBuilder().sourceType("AwsOrganizations")
                    .awsOrganizationsSource(source -> source.organizationSourceType("EntireOrganization")).build();
            assertError("ResourceDataSyncInvalidConfigurationException",
                    () -> scenario.client.updateResourceDataSync(request -> request.syncName("source")
                            .syncType(SOURCE).syncSource(organization)));
            assertEquals(before, scenario.all());
        }
    }

    @Test
    void deletionMatchesTypeAndRemovesOnlyTheAddressedConfiguration() {
        try (Scenario scenario = new Scenario("us-east-1");
             Scenario otherRegion = new Scenario(scenario.account, "us-west-2")) {
            scenario.create("source", source(scenario.region));
            scenario.create("sibling", source(scenario.region));
            otherRegion.create("source", source(otherRegion.region));
            Map<String, ResourceDataSyncItem> before = scenario.all();
            Map<String, ResourceDataSyncItem> regionalBefore = otherRegion.all();
            assertError("ResourceDataSyncNotFoundException",
                    () -> scenario.client.deleteResourceDataSync(request -> request.syncName("source")
                            .syncType("SyncToDestination")));
            assertEquals(before, scenario.all());
            scenario.delete("source");
            assertEquals(Map.of("sibling", before.get("sibling")), scenario.all());
            assertEquals(regionalBefore, otherRegion.all());
            assertError("ResourceDataSyncNotFoundException",
                    () -> scenario.client.deleteResourceDataSync(request -> request.syncName("source")
                            .syncType(SOURCE)));
        }
    }

    @Test
    void aMissingSourceUpdateReturnsTheModeledNotFoundError() {
        try (Scenario scenario = new Scenario("us-east-1")) {
            assertError("ResourceDataSyncNotFoundException",
                    () -> scenario.client.updateResourceDataSync(request -> request.syncName("missing")
                            .syncType(SOURCE).syncSource(source(scenario.region))));
        }
    }

    @Test
    void explorerQuotaIsFiveWithinEachAccountAndRegion() {
        try (Scenario scenario = new Scenario("us-east-1");
             Scenario foreign = new Scenario("us-east-1");
             Scenario otherRegion = new Scenario(scenario.account, "us-west-2")) {
            for (int index = 0; index < 5; index++) {
                scenario.create("source-" + index, source(scenario.region));
            }
            Map<String, ResourceDataSyncItem> before = scenario.all();
            assertError("ResourceDataSyncCountExceededException",
                    () -> scenario.client.createResourceDataSync(request -> request.syncName("sixth")
                            .syncType(SOURCE).syncSource(source(scenario.region))));
            assertEquals(before, scenario.all());
            foreign.create("source-0", source(foreign.region));
            otherRegion.create("source-0", source(otherRegion.region));
            assertEquals(1, foreign.all().size());
            assertEquals(1, otherRegion.all().size());
        }
    }

    @Test
    void theExistingParameterStoreRoundTripRemainsAvailable() {
        try (Scenario scenario = new Scenario("us-east-1")) {
            String name = "/source-sync-control/" + UUID.randomUUID();
            scenario.client.putParameter(request -> request.name(name).type("String").value("control"));
            try {
                assertEquals("control", scenario.client.getParameter(request -> request.name(name)).parameter().value());
            } finally {
                scenario.client.deleteParameter(request -> request.name(name));
            }
        }
    }

    private static ResourceDataSyncSource source(String region) {
        return ResourceDataSyncSource.builder().sourceType("SingleAccountMultiRegions")
                .sourceRegions(region).includeFutureRegions(false).enableAllOpsDataSources(true).build();
    }

    private static ResourceDataSyncSource replacement() {
        return ResourceDataSyncSource.builder().sourceType("SingleAccountMultiRegions")
                .sourceRegions("us-east-1", "us-west-2", "eu-west-1")
                .includeFutureRegions(true).enableAllOpsDataSources(false).build();
    }

    private static void assertSource(ResourceDataSyncSource expected, ResourceDataSyncItem actual) {
        assertNotNull(actual);
        assertNotNull(actual.syncSource());
        assertEquals(expected.sourceType(), actual.syncSource().sourceType());
        assertEquals(expected.sourceRegions(), actual.syncSource().sourceRegions());
        assertEquals(expected.includeFutureRegions(), actual.syncSource().includeFutureRegions());
        assertEquals(expected.enableAllOpsDataSources(), actual.syncSource().enableAllOpsDataSources());
        assertEquals(expected.awsOrganizationsSource(), actual.syncSource().awsOrganizationsSource());
        assertNull(actual.syncSource().state());
    }

    private static void assertError(String code, Runnable operation) {
        SsmException failure = assertThrows(SsmException.class, operation::run);
        assertEquals(400, failure.statusCode());
        assertEquals(code, failure.awsErrorDetails().errorCode());
    }

    private static final class Scenario implements AutoCloseable {
        private final String account;
        private final String region;
        private final SsmClient client;
        private final List<String> created = new ArrayList<>();

        private Scenario(String region) {
            this(Long.toString(ACCOUNTS.incrementAndGet()), region);
        }

        private Scenario(String account, String region) {
            this.account = account;
            this.region = region;
            client = SsmClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                    .httpClient(UrlConnectionHttpClient.create()).build();
        }

        private void create(String name, ResourceDataSyncSource source) {
            client.createResourceDataSync(request -> request.syncName(name).syncType(SOURCE).syncSource(source));
            created.add(name);
        }

        private Map<String, ResourceDataSyncItem> all() {
            return all(50);
        }

        private Map<String, ResourceDataSyncItem> all(int pageSize) {
            Map<String, ResourceDataSyncItem> result = new LinkedHashMap<>();
            List<String> tokens = new ArrayList<>();
            String token = null;
            for (int page = 0; page < 20; page++) {
                String current = token;
                ListResourceDataSyncResponse response = client.listResourceDataSync(
                        request -> request.syncType(SOURCE).maxResults(pageSize).nextToken(current));
                assertTrue(response.resourceDataSyncItems().size() <= pageSize);
                for (ResourceDataSyncItem item : response.resourceDataSyncItems()) {
                    assertEquals(SOURCE, item.syncType());
                    assertNull(result.put(item.syncName(), item));
                }
                token = response.nextToken();
                if (token == null) {
                    return result;
                }
                assertFalse(tokens.contains(token));
                tokens.add(token);
            }
            throw new AssertionError("Source sync pagination exceeded its bounded collection");
        }

        private void delete(String name) {
            client.deleteResourceDataSync(request -> request.syncName(name).syncType(SOURCE));
            created.remove(name);
        }

        @Override
        public void close() {
            try {
                for (String name : List.copyOf(created)) {
                    delete(name);
                }
            } finally {
                client.close();
            }
        }
    }
}
