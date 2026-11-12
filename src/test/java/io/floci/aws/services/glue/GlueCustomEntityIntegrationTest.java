package io.floci.aws.services.glue;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.AlreadyExistsException;
import software.amazon.awssdk.services.glue.model.CustomEntityType;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GlueException;
import software.amazon.awssdk.services.glue.model.ListCustomEntityTypesResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class GlueCustomEntityIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1", "us-gov-west-1"})
    void nativeSdkRunsTheFiveApiLifecycleInEveryTestedPartition(String regionName) {
        String first = "custom-a-" + UUID.randomUUID();
        String second = "custom-b-" + UUID.randomUUID();
        try (GlueClient glue = AwsTestClients.create(GlueClient.builder(), "111111111111", Region.of(regionName))) {
            try {
                assertEquals(first, glue.createCustomEntityType(r -> r.name(first).regexString("[A-Z]{2}[0-9]{6}")
                        .contextWords("ticket", "case").tags(Map.of("fixture", first))).name());
                assertEquals(second, glue.createCustomEntityType(r -> r.name(second).regexString("[0-9]{3}")
                        .tags(Map.of("fixture", first))).name());
                assertEquals(List.of("ticket", "case"), glue.getCustomEntityType(r -> r.name(first)).contextWords());
                assertThrows(AlreadyExistsException.class,
                        () -> glue.createCustomEntityType(r -> r.name(first).regexString("different")));
                assertEquals("[A-Z]{2}[0-9]{6}", glue.getCustomEntityType(r -> r.name(first)).regexString());
                assertEquals(2, glue.batchGetCustomEntityTypes(r -> r.names(second, first)).customEntityTypes().size());
                assertEquals(List.of(first + "-missing"), glue.batchGetCustomEntityTypes(r ->
                        r.names(second, first + "-missing", first)).customEntityTypesNotFound());
                List<CustomEntityType> listed = glue.listCustomEntityTypes(r -> r.tags(Map.of("fixture", first)))
                        .customEntityTypes();
                assertEquals(List.of(first, second), listed.stream().map(CustomEntityType::name).toList());
                assertEquals(List.of("[A-Z]{2}[0-9]{6}", "[0-9]{3}"),
                        listed.stream().map(CustomEntityType::regexString).toList());
                assertEquals(List.of("ticket", "case"), listed.getFirst().contextWords());
                assertEquals(first, glue.deleteCustomEntityType(r -> r.name(first)).name());
                assertThrows(EntityNotFoundException.class, () -> glue.getCustomEntityType(r -> r.name(first)));
                assertEquals("[0-9]{3}", glue.getCustomEntityType(r -> r.name(second)).regexString());
                assertEquals(List.of(second), glue.listCustomEntityTypes(r -> r.tags(Map.of("fixture", first)))
                        .customEntityTypes().stream().map(CustomEntityType::name).toList());
                glue.createCustomEntityType(r -> r.name(first).regexString("REPLACEMENT"));
                assertEquals("REPLACEMENT", glue.getCustomEntityType(r -> r.name(first)).regexString());
                assertFalse(glue.getCustomEntityType(r -> r.name(first)).hasContextWords());
            } finally {
                remove(glue, first);
                remove(glue, second);
            }
        }
    }

    @Test
    void nativeClientsKeepEqualNamesSeparateAcrossAccountsAndRegions() {
        String name = "custom-isolated-" + UUID.randomUUID();
        try (GlueClient first = AwsTestClients.create(GlueClient.builder(), "111111111111", Region.US_EAST_1);
             GlueClient otherAccount = AwsTestClients.create(GlueClient.builder(), "222222222222", Region.US_EAST_1);
             GlueClient otherRegion = AwsTestClients.create(GlueClient.builder(), "111111111111", Region.US_WEST_2)) {
            try {
                first.createCustomEntityType(r -> r.name(name).regexString("first"));
                assertThrows(EntityNotFoundException.class, () -> otherAccount.getCustomEntityType(r -> r.name(name)));
                assertThrows(EntityNotFoundException.class, () -> otherRegion.getCustomEntityType(r -> r.name(name)));
                otherAccount.createCustomEntityType(r -> r.name(name).regexString("account"));
                otherRegion.createCustomEntityType(r -> r.name(name).regexString("region"));
                assertEquals("first", first.getCustomEntityType(r -> r.name(name)).regexString());
                assertEquals("account", otherAccount.getCustomEntityType(r -> r.name(name)).regexString());
                assertEquals("region", otherRegion.getCustomEntityType(r -> r.name(name)).regexString());
                otherAccount.deleteCustomEntityType(r -> r.name(name));
                assertEquals("first", first.getCustomEntityType(r -> r.name(name)).regexString());
            } finally {
                remove(first, name);
                remove(otherAccount, name);
                remove(otherRegion, name);
            }
        }
    }

    @Test
    void nativeTagFiltersAndPageTokensSelectOnlyMatchingStoredDefinitions() {
        String marker = UUID.randomUUID().toString();
        List<String> names = List.of("custom-a-" + marker, "custom-b-" + marker, "custom-c-" + marker);
        try (GlueClient glue = AwsTestClients.create(GlueClient.builder(), "111111111111", Region.US_EAST_1)) {
            try {
                for (int index = 0; index < names.size(); index++) {
                    String name = names.get(index);
                    String kind = index == 2 ? "other" : "chosen";
                    glue.createCustomEntityType(r -> r.name(name).regexString(name)
                            .tags(Map.of("fixture", marker, "kind", kind, "empty", "")));
                }
                Map<String, String> filter = Map.of("fixture", marker, "kind", "chosen", "empty", "");
                ListCustomEntityTypesResponse page = glue.listCustomEntityTypes(r -> r.tags(filter).maxResults(1));
                assertEquals(names.getFirst(), page.customEntityTypes().getFirst().name());
                String token = page.nextToken();
                assertNotNull(token);
                ListCustomEntityTypesResponse second = glue.listCustomEntityTypes(r ->
                        r.tags(filter).maxResults(1).nextToken(token));
                assertEquals(names.get(1), second.customEntityTypes().getFirst().name());
                assertNull(second.nextToken());
                assertTrue(glue.listCustomEntityTypes(r -> r.tags(Map.of("fixture", marker, "kind", "missing")))
                        .customEntityTypes().isEmpty());
                GlueException mismatched = assertThrows(GlueException.class,
                        () -> glue.listCustomEntityTypes(r -> r.tags(Map.of("fixture", marker)).nextToken(token)));
                assertEquals("InvalidInputException", mismatched.awsErrorDetails().errorCode());
                List<String> readNames = new ArrayList<>();
                for (CustomEntityType value : glue.listCustomEntityTypes(r -> r.tags(Map.of("fixture", marker)))
                        .customEntityTypes()) {
                    readNames.add(value.name());
                    assertEquals(value.name(), value.regexString());
                }
                assertEquals(names, readNames);
            } finally {
                names.forEach(name -> remove(glue, name));
            }
        }
    }

    @Test
    void malformedWireMembersAreRefusedBeforeAnyPatternIsStored() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        String name = "custom-invalid-" + UUID.randomUUID();
        for (String request : List.of(
                "{\"Name\":\"" + name + "\",\"RegexString\":42}",
                "{\"Name\":\"" + name + "\",\"RegexString\":\"x\",\"ContextWords\":[]}",
                "{\"Name\":\"" + name + "\",\"RegexString\":\"x\",\"Tags\":{\"bad\":false}}")) {
            given().contentType("application/x-amz-json-1.1")
                    .header("X-Amz-Target", "AWSGlue.CreateCustomEntityType")
                    .body(request).when().post("/").then().statusCode(400)
                    .body(containsString("InvalidInputException"));
        }
        try (GlueClient glue = AwsTestClients.create(GlueClient.builder(), "000000000000", Region.US_EAST_1)) {
            assertThrows(EntityNotFoundException.class, () -> glue.getCustomEntityType(r -> r.name(name)));
            glue.createCustomEntityType(r -> r.name(name).regexString("valid"));
            try {
                assertEquals("valid", glue.getCustomEntityType(r -> r.name(name)).regexString());
            } finally {
                remove(glue, name);
            }
        }
    }

    private static void remove(GlueClient glue, String name) {
        try {
            glue.deleteCustomEntityType(r -> r.name(name));
        } catch (EntityNotFoundException absent) {
            // This fixture may fail before the create, or already have deleted this exact name.
        }
        assertThrows(EntityNotFoundException.class, () -> glue.getCustomEntityType(r -> r.name(name)));
    }
}
