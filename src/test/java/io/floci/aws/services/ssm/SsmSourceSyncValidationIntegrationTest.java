package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ResourceDataSyncItem;
import software.amazon.awssdk.services.ssm.model.ResourceDataSyncSource;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmSourceSyncValidationIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong ACCOUNTS = new AtomicLong(873033350000L);
    private static final String SOURCE = "SyncFromSource";

    @Test
    void omittedListAndDeleteSelectDestinationAndOmittedUpdateFutureMaterializesFalse() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<ResourceDataSyncItem> before = fixture.items();
            assertTrue(fixture.client.listResourceDataSync().resourceDataSyncItems().isEmpty());
            SsmException failure = assertThrows(SsmException.class,
                    () -> fixture.client.deleteResourceDataSync(request -> request.syncName("source")));
            assertEquals(400, failure.statusCode());
            assertEquals("ResourceDataSyncNotFoundException", failure.awsErrorDetails().errorCode());
            assertEquals(before, fixture.items());
            ResourceDataSyncSource replacement = ResourceDataSyncSource.builder()
                    .sourceType("SingleAccountMultiRegions").sourceRegions("us-west-2")
                    .enableAllOpsDataSources(false).build();
            fixture.client.updateResourceDataSync(request -> request.syncName("source")
                    .syncType(SOURCE).syncSource(replacement));
            List<ResourceDataSyncItem> after = fixture.items();
            ResourceDataSyncItem changed = after.stream().filter(item -> "source".equals(item.syncName()))
                    .findFirst().orElseThrow();
            ResourceDataSyncItem original = before.stream().filter(item -> "source".equals(item.syncName()))
                    .findFirst().orElseThrow();
            assertEquals(List.of("us-west-2"), changed.syncSource().sourceRegions());
            assertEquals(false, changed.syncSource().includeFutureRegions());
            assertEquals(false, changed.syncSource().enableAllOpsDataSources());
            assertEquals(original.syncCreatedTime(), changed.syncCreatedTime());
            assertFalse(changed.syncLastModifiedTime().isBefore(original.syncLastModifiedTime()));
            assertEquals(before.stream().filter(item -> "sibling".equals(item.syncName())).toList(),
                    after.stream().filter(item -> "sibling".equals(item.syncName())).toList());
            Response response = fixture.raw("ListResourceDataSync", "{\"SyncType\":\"SyncFromSource\"}");
            JsonNode wire = MAPPER.readTree(response.asString());
            assertEquals(200, response.statusCode());
            JsonNode row = Stream.of(wire.path("ResourceDataSyncItems").get(0),
                            wire.path("ResourceDataSyncItems").get(1))
                    .filter(item -> "source".equals(item.path("SyncName").asText())).findFirst().orElseThrow();
            assertTrue(row.path("SyncSource").get("IncludeFutureRegions").isBoolean());
            assertFalse(row.path("SyncSource").get("IncludeFutureRegions").asBoolean());
            assertFalse(row.has("LastStatus"));
            assertFalse(row.path("SyncSource").has("State"));
        }
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of("CreateResourceDataSync", "UpdateResourceDataSync").flatMap(operation -> Stream.of(
                "missing-source", "missing-type", "missing-regions", "source-object", "regions-array",
                "region-length", "future-boolean", "name-length", "mixed", "organization")
                .map(control -> Arguments.of(operation, control)));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void malformedOrUnsupportedSourcesLeaveTheCompleteCollectionUnchanged(String operation, String control)
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<ResourceDataSyncItem> before = fixture.items();
            ObjectNode body = MAPPER.createObjectNode().put("SyncName",
                    operation.startsWith("Create") ? "invalid" : "source").put("SyncType", SOURCE);
            ObjectNode source = body.putObject("SyncSource").put("SourceType", "SingleAccountMultiRegions")
                    .put("IncludeFutureRegions", true).put("EnableAllOpsDataSources", false);
            source.putArray("SourceRegions").add("us-east-1");
            String error = switch (control) {
                case "missing-source" -> {
                    body.remove("SyncSource");
                    yield operation.startsWith("Create") ? "ResourceDataSyncInvalidConfigurationException"
                            : "ValidationException";
                }
                case "missing-type" -> { source.remove("SourceType"); yield "ValidationException"; }
                case "missing-regions" -> { source.remove("SourceRegions"); yield "ValidationException"; }
                case "source-object" -> { body.put("SyncSource", "wrong-type"); yield "SerializationException"; }
                case "regions-array" -> { source.put("SourceRegions", "us-east-1"); yield "SerializationException"; }
                case "region-length" -> { source.putArray("SourceRegions").add("r".repeat(65)); yield "ValidationException"; }
                case "future-boolean" -> { source.put("IncludeFutureRegions", "true"); yield "SerializationException"; }
                case "name-length" -> { body.put("SyncName", "n".repeat(65)); yield "ValidationException"; }
                case "mixed" -> {
                    body.putObject("S3Destination").put("BucketName", "owned-unused-bucket")
                            .put("Region", "us-east-1").put("SyncFormat", "JsonSerDe");
                    yield "ResourceDataSyncInvalidConfigurationException";
                }
                case "organization" -> {
                    source.put("SourceType", "AwsOrganizations").putObject("AwsOrganizationsSource")
                            .put("OrganizationSourceType", "EntireOrganization");
                    yield "ResourceDataSyncInvalidConfigurationException";
                }
                default -> throw new IllegalArgumentException(control);
            };
            Response response = fixture.raw(operation, body.toString());
            JsonNode wire = MAPPER.readTree(response.asString());
            List<ResourceDataSyncItem> actual = fixture.items();
            assertEquals(400, response.statusCode(), wire.toString());
            assertTrue(wire.path("__type").asText().endsWith(error), wire.toString());
            assertNotNull(wire.get("message"));
            assertEquals(before, actual);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final String account = Long.toString(ACCOUNTS.incrementAndGet());
        private final SsmClient client = SsmClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClient(UrlConnectionHttpClient.create()).build();
        private final List<String> created = new ArrayList<>();

        private Fixture() {
            try {
                for (String name : List.of("source", "sibling")) {
                    client.createResourceDataSync(request -> request.syncName(name).syncType(SOURCE)
                            .syncSource(source -> source.sourceType("SingleAccountMultiRegions")
                                    .sourceRegions("us-east-1").includeFutureRegions(true).enableAllOpsDataSources(false)));
                    created.add(name);
                }
            } catch (RuntimeException failure) {
                try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        private List<ResourceDataSyncItem> items() {
            return client.listResourceDataSync(request -> request.syncType(SOURCE)).resourceDataSyncItems();
        }

        private Response raw(String operation, String body) {
            return given().config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                            .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.JSON)))
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                            + "/20261113/us-east-1/ssm/aws4_request, SignedHeaders=host, Signature=fixture")
                    .header("X-Amz-Target", "AmazonSSM." + operation).contentType("application/x-amz-json-1.1")
                    .body(body).post("/");
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            for (String name : created) {
                try {
                    client.deleteResourceDataSync(request -> request.syncName(name).syncType(SOURCE));
                } catch (RuntimeException cleanup) {
                    if (failure == null) { failure = cleanup; } else { failure.addSuppressed(cleanup); }
                }
            }
            client.close();
            if (failure != null) { throw failure; }
        }
    }
}
