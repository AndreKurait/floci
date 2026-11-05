package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.InventoryFilter;
import software.amazon.awssdk.services.ssm.model.InventoryItem;
import software.amazon.awssdk.services.ssm.model.ListInventoryEntriesResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmInventorySdkIntegrationTest {
    private static final String ACCOUNT = "830011112101";
    private static final String REGION = "us-east-1";
    private static final String CAPTURE = "2026-01-02T03:04:05Z";
    private static final String REFRESH = "2026-01-02T05:04:05Z";

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({
        "AWS:Application,1.1", "AWS:AWSComponent,1.0", "AWS:BillingInfo,1.0", "AWS:File,1.0",
        "AWS:InstanceInformation,1.0", "AWS:InstanceDetailedInformation,1.0", "AWS:Network,1.0",
        "AWS:WindowsRegistry,1.0", "AWS:WindowsRole,1.0", "AWS:Service,1.0", "AWS:WindowsUpdate,1.0",
        "Custom:RackInformation,2.0"
    })
    void reportsInventoryThroughTheSdk(String type, String version) {
        String instance = register(ACCOUNT, REGION);
        try (SsmClient client = client(ACCOUNT, REGION)) {
            Map<String, String> content = switch (type) {
                case "AWS:BillingInfo" -> Map.of("BillingProductId", "bp-example");
                case "AWS:InstanceInformation" -> Map.of("InstanceId", instance, "PlatformType", "Linux");
                case "AWS:InstanceDetailedInformation" -> Map.of("KernelVersion", "6.1", "CPUCores", "2");
                case "AWS:WindowsRegistry" -> Map.of("KeyPath", "HKEY_LOCAL_MACHINE\\Example", "Value", "sample");
                case "AWS:WindowsUpdate" -> Map.of("HotFixId", "KB1234567", "Description", "Example update");
                default -> Map.of("Name", "component");
            };
            InventoryItem item = item(type, version, content);
            client.putInventory(request -> request.instanceId(instance).items(item));
            ListInventoryEntriesResponse read = client.listInventoryEntries(
                    request -> request.instanceId(instance).typeName(type));
            assertEquals(instance, read.instanceId());
            assertEquals(type, read.typeName());
            assertEquals(version, read.schemaVersion());
            assertEquals(CAPTURE, read.captureTime());
            assertEquals(item.content(), read.entries());
        }
    }

    @Test
    void unchangedHashRetainsContentAndAdvancesCaptureTime() {
        String instance = register(ACCOUNT, REGION);
        try (SsmClient client = client(ACCOUNT, REGION)) {
            InventoryItem original = item("AWS:Application", "1.1", Map.of("Name", "ssm-agent"))
                    .toBuilder().contentHash("client-calculated-md5").build();
            client.putInventory(request -> request.instanceId(instance).items(original));
            InventoryItem refresh = InventoryItem.builder().typeName(original.typeName())
                    .schemaVersion(original.schemaVersion()).captureTime(REFRESH)
                    .contentHash(original.contentHash()).build();
            client.putInventory(request -> request.instanceId(instance).items(refresh));
            ListInventoryEntriesResponse read = client.listInventoryEntries(
                    request -> request.instanceId(instance).typeName(original.typeName()));
            assertEquals(original.content(), read.entries());
            assertEquals(REFRESH, read.captureTime());

            InventoryItem incorrect = refresh.toBuilder().contentHash("different").build();
            assertError("ItemContentMismatchException",
                    () -> client.putInventory(request -> request.instanceId(instance).items(incorrect)));
            assertTrue(read.equalsBySdkFields(client.listInventoryEntries(
                    request -> request.instanceId(instance).typeName(original.typeName()))));

            client.putInventory(request -> request.instanceId(instance).items(original.toBuilder()
                    .content(List.of()).contentHash("empty-list-hash").captureTime(REFRESH).build()));
            assertTrue(client.listInventoryEntries(
                    request -> request.instanceId(instance).typeName(original.typeName())).entries().isEmpty());
        }
    }

    @Test
    void unregisteredOrForeignManagedNodesAreRejected() {
        String instance = register(ACCOUNT, REGION);
        InventoryItem item = item("AWS:Application", "1.1", Map.of("Name", "owned"));
        try (SsmClient owner = client(ACCOUNT, REGION);
             SsmClient foreign = client("830011112102", REGION);
             SsmClient otherRegion = client(ACCOUNT, "us-west-2")) {
            owner.putInventory(request -> request.instanceId(instance).items(item));
            assertError("InvalidInstanceId", () -> foreign.putInventory(
                    request -> request.instanceId(instance).items(item)));
            assertError("InvalidInstanceId", () -> foreign.listInventoryEntries(
                    request -> request.instanceId(instance).typeName(item.typeName())));
            assertError("InvalidInstanceId", () -> otherRegion.listInventoryEntries(
                    request -> request.instanceId(instance).typeName(item.typeName())));
            assertError("InvalidInstanceId", () -> owner.putInventory(
                    request -> request.instanceId("i-1234567890abcdef0").items(item)));
            assertEquals(item.content(), owner.listInventoryEntries(
                    request -> request.instanceId(instance).typeName(item.typeName())).entries());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-gov-west-1", "cn-north-1"})
    void inventoryIsIsolatedWhenBothAccountsHaveTheSameManagedNode(String region) {
        String instance = register(ACCOUNT, region);
        register("830011112102", region, instance);
        register(ACCOUNT, REGION, instance);
        try (SsmClient owner = client(ACCOUNT, region);
             SsmClient foreign = client("830011112102", region);
             SsmClient otherRegion = client(ACCOUNT, REGION)) {
            InventoryItem item = item("AWS:Application", "1.1", Map.of("Name", region));
            owner.putInventory(request -> request.instanceId(instance).items(item));
            assertTrue(foreign.listInventoryEntries(
                    request -> request.instanceId(instance).typeName(item.typeName())).entries().isEmpty());
            assertTrue(otherRegion.listInventoryEntries(
                    request -> request.instanceId(instance).typeName(item.typeName())).entries().isEmpty());
            assertEquals(item.content(), owner.listInventoryEntries(
                    request -> request.instanceId(instance).typeName(item.typeName())).entries());
        }
    }

    @Test
    void malformedBatchDoesNotPartiallyReplaceEarlierItems() {
        String instance = register(ACCOUNT, REGION);
        try (SsmClient client = client(ACCOUNT, REGION)) {
            InventoryItem initial = item("AWS:Application", "1.1", Map.of("Name", "original"));
            client.putInventory(request -> request.instanceId(instance).items(initial));
            InventoryItem changed = item("AWS:Application", "1.1", Map.of("Name", "changed"));
            InventoryItem invalid = item("AWS:UnknownInventory", "1.0", Map.of("Name", "invalid"));
            assertError("InvalidTypeNameException", () -> client.putInventory(
                    request -> request.instanceId(instance).items(changed, invalid)));
            assertEquals(initial.content(), client.listInventoryEntries(
                    request -> request.instanceId(instance).typeName(initial.typeName())).entries());
            assertError("InvalidItemContentException", () -> client.putInventory(
                    request -> request.instanceId(instance).items(changed, changed)));
            assertEquals(initial.content(), client.listInventoryEntries(
                    request -> request.instanceId(instance).typeName(initial.typeName())).entries());
        }
    }

    @Test
    void filtersAndPagesDoNotRepeatOrSkipMatchingEntries() {
        String instance = register(ACCOUNT, REGION);
        try (SsmClient client = client(ACCOUNT, REGION)) {
            List<Map<String, String>> entries = IntStream.range(0, 121)
                    .mapToObj(index -> Map.of("Name", (index % 2 == 0 ? "keep-" : "omit-") + index,
                            "Version", index % 3 == 0 ? "1" : "2")).toList();
            client.putInventory(request -> request.instanceId(instance)
                    .items(InventoryItem.builder().typeName("AWS:Application").schemaVersion("1.1")
                            .captureTime(CAPTURE).content(entries).build()));
            InventoryFilter prefix = InventoryFilter.builder().key("AWS:Application.Name")
                    .type("BeginWith").values("keep-").build();
            InventoryFilter version = InventoryFilter.builder().key("Version").type("NotEqual").values("1").build();
            List<Map<String, String>> collected = new ArrayList<>();
            String token = null;
            do {
                String currentToken = token;
                ListInventoryEntriesResponse page = client.listInventoryEntries(request -> request
                        .instanceId(instance).typeName("AWS:Application").maxResults(7)
                        .filters(prefix, version).nextToken(currentToken));
                collected.addAll(page.entries());
                token = page.nextToken();
            } while (token != null);
            assertEquals(entries.stream().filter(entry -> entry.get("Name").startsWith("keep-")
                    && !entry.get("Version").equals("1")).toList(), collected);

            String firstToken = client.listInventoryEntries(request -> request
                    .instanceId(instance).typeName("AWS:Application").maxResults(1)).nextToken();
            assertNotNull(firstToken);
            assertError("InvalidNextToken", () -> client.listInventoryEntries(request -> request
                    .instanceId(instance).typeName("AWS:Application").filters(prefix).nextToken(firstToken)));
            assertError("InvalidNextToken", () -> client.listInventoryEntries(request -> request
                    .instanceId(instance).typeName("AWS:Application").nextToken("not-a-cursor")));
            assertError("InvalidFilter", () -> client.listInventoryEntries(request -> request
                    .instanceId(instance).typeName("AWS:Application").filters(prefix.toBuilder().type("Exists").build())));
        }
    }

    @Test
    void customAttributeCasingCannotDriftBetweenManagedNodes() {
        String instance = register(ACCOUNT, REGION);
        String other = register(ACCOUNT, REGION);
        try (SsmClient client = client(ACCOUNT, REGION)) {
            String type = "Custom:RackInformation";
            client.putInventory(request -> request.instanceId(instance)
                    .items(item(type, "2.0", Map.of("Vendor", "Example"))));
            assertError("InvalidItemContentException", () -> client.putInventory(request -> request.instanceId(other)
                    .items(item(type, "2.0", Map.of("vendor", "Example")))));
            assertTrue(client.listInventoryEntries(request -> request.instanceId(other).typeName(type)).entries().isEmpty());
        }
    }

    @Test
    void customTypeLimitDoesNotApplyAnOverLimitBatch() {
        String account = "830011112103";
        String instance = register(account, REGION);
        try (SsmClient client = client(account, REGION)) {
            List<InventoryItem> first = IntStream.range(0, 20)
                    .mapToObj(index -> item("Custom:Type" + index, "1.0", Map.of("Name", "value"))).toList();
            client.putInventory(request -> request.instanceId(instance).items(first));
            assertError("CustomSchemaCountLimitExceededException", () -> client.putInventory(request -> request
                    .instanceId(instance).items(item("Custom:Type20", "1.0", Map.of("Name", "uncommitted")))));
            assertFalse(client.listInventoryEntries(request -> request
                    .instanceId(instance).typeName("Custom:Type0")).entries().isEmpty());
            assertTrue(client.listInventoryEntries(request -> request
                    .instanceId(instance).typeName("Custom:Type20")).entries().isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "'{\"Name\":7}',SerializationException",
        "'{\"Name\":{\"nested\":\"value\"}}',SerializationException",
        "'{\"Name\":[\"value\"]}',SerializationException"
    })
    void inventoryEntryValuesMustRemainStrings(String content, String error) {
        String instance = register(ACCOUNT, REGION);
        given().header("X-Amz-Target", "AmazonSSM.PutInventory")
                .header("Authorization", authorization(ACCOUNT, REGION))
                .contentType("application/x-amz-json-1.1")
                .body("""
                        {"InstanceId":"%s","Items":[{"TypeName":"AWS:Application","SchemaVersion":"1.1",
                          "CaptureTime":"%s","Content":[%s]}]}
                        """.formatted(instance, CAPTURE, content))
                .post("/").then().statusCode(400).body("__type", equalTo(error));
    }

    @Test
    void invalidDatesVersionsContextsAndPageSizesAreRefused() {
        String instance = register(ACCOUNT, REGION);
        try (SsmClient client = client(ACCOUNT, REGION)) {
            InventoryItem item = item("AWS:Application", "1.1", Map.of("Name", "agent"));
            assertError("InvalidItemContentException", () -> client.putInventory(request -> request
                    .instanceId(instance).items(item.toBuilder().captureTime("2026-02-30T01:02:03Z").build())));
            assertError("UnsupportedInventorySchemaVersionException", () -> client.putInventory(request -> request
                    .instanceId(instance).items(item.toBuilder().schemaVersion("2.0").build())));
            assertError("UnsupportedInventoryItemContextException", () -> client.putInventory(request -> request
                    .instanceId(instance).items(item.toBuilder().context(Map.of("ExecutionId", "example")).build())));
            assertError("ValidationException", () -> client.listInventoryEntries(request -> request
                    .instanceId(instance).typeName(item.typeName()).maxResults(0)));
            assertError("ValidationException", () -> client.listInventoryEntries(request -> request
                    .instanceId(instance).typeName(item.typeName()).maxResults(51)));
        }
    }

    private static InventoryItem item(String type, String version, Map<String, String> content) {
        return InventoryItem.builder().typeName(type).schemaVersion(version).captureTime(CAPTURE)
                .content(content).build();
    }

    private static void assertError(String code, Runnable operation) {
        SsmException failure = assertThrows(SsmException.class, operation::run);
        assertEquals(400, failure.statusCode());
        assertEquals(code, failure.awsErrorDetails().errorCode());
    }

    private static String register(String account, String region) {
        String instance = "i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        register(account, region, instance);
        return instance;
    }

    private static void register(String account, String region, String instance) {
        given().header("X-Amz-Target", "AmazonSSM.UpdateInstanceInformation")
                .header("Authorization", authorization(account, region))
                .contentType("application/x-amz-json-1.1")
                .body(Map.of("InstanceId", instance, "AgentName", "amazon-ssm-agent",
                        "PlatformType", "Linux", "PlatformName", "Example Linux", "AgentVersion", "3.3.0"))
                .post("/").then().statusCode(200);
    }

    private static String authorization(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260102/" + region
                + "/ssm/aws4_request, SignedHeaders=host, Signature=example";
    }

    private static SsmClient client(String account, String region) {
        return SsmClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
