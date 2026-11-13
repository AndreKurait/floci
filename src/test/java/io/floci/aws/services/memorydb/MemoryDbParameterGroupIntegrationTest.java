package io.floci.aws.services.memorydb;

import io.floci.aws.testing.ValidateSignaturesProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.memorydb.MemoryDbClient;
import software.amazon.awssdk.services.memorydb.model.DescribeParameterGroupsRequest;
import software.amazon.awssdk.services.memorydb.model.DescribeParametersResponse;
import software.amazon.awssdk.services.memorydb.model.Parameter;
import software.amazon.awssdk.services.memorydb.model.ParameterGroup;
import software.amazon.awssdk.services.memorydb.model.ParameterGroupAlreadyExistsException;
import software.amazon.awssdk.services.memorydb.model.ParameterGroupNotFoundException;
import software.amazon.awssdk.services.memorydb.model.ParameterNameValue;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class MemoryDbParameterGroupIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    private MemoryDbClient client() {
        return MemoryDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(config -> config.retryPolicy(RetryPolicy.none())
                        .apiCallTimeout(Duration.ofSeconds(10)))
                .build();
    }

    @Test
    void describesTheFiveSupportedDefaultGroups() {
        try (MemoryDbClient client = client()) {
            List<ParameterGroup> groups = client.describeParameterGroups(
                    DescribeParameterGroupsRequest.builder().build()).parameterGroups();
            Set<String> defaults = groups.stream().map(ParameterGroup::name)
                    .filter(name -> name.startsWith("default.")).collect(Collectors.toSet());
            assertEquals(Set.of("default.memorydb-redis6", "default.memorydb-redis7",
                    "default.memorydb-valkey7", "default.memorydb-redis7.search",
                    "default.memorydb-valkey7.search"), defaults);
            for (ParameterGroup group : groups) {
                assertEquals("arn:aws:memorydb:us-east-1:000000000000:parametergroup/" + group.name(), group.arn());
                assertNotNull(group.description());
                assertNotNull(group.family());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"redis6", "redis7", "valkey7"})
    void defaultParametersExposeDocumentedValuesAndRecordedDomains(String engine) {
        try (MemoryDbClient client = client()) {
            Map<String, Parameter> values = parameters(client, "default.memorydb-" + engine);
            assertEquals("0", values.get("timeout").value());
            assertEquals("0,20-", values.get("timeout").allowedValues());
            assertEquals("integer", values.get("timeout").dataType());
            assertEquals("65000", values.get("maxclients").value());
            assertEquals("1073741824", values.get("client-query-buffer-limit").value());
            assertEquals("-", values.get("slowlog-log-slower-than").allowedValues());
            assertEquals("The length of the slow log. There is no limit to this length. Just be aware that it will "
                    + "consume memory. You can reclaim memory used by the slow log with SLOWLOG RESET.",
                    values.get("slowlog-max-len").description());
            if ("redis6".equals(engine)) {
                assertTrue(values.containsKey("hash-max-ziplist-entries"));
                assertTrue(values.containsKey("activerehashing"));
                assertFalse(values.containsKey("search-enabled"));
                assertFalse(values.containsKey("hash-max-listpack-entries"));
            } else {
                assertTrue(values.containsKey("hash-max-listpack-entries"));
                assertFalse(values.containsKey("hash-max-ziplist-entries"));
                assertFalse(values.containsKey("activerehashing"));
                assertEquals("no", values.get("search-enabled").value());
                assertEquals("1-60000", values.get("search-query-timeout-ms").allowedValues());
                assertEquals("When set to yes, it enables search capabilities.",
                        values.get("search-enabled").description());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"redis7", "valkey7"})
    void searchDefaultsHaveTheSearchOverride(String engine) {
        try (MemoryDbClient client = client()) {
            assertEquals("yes", parameters(client, "default.memorydb-" + engine + ".search")
                    .get("search-enabled").value());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"redis6", "redis7", "valkey7"})
    void parameterPagesReconstructTheCompleteUnpagedSnapshot(String engine) {
        try (MemoryDbClient client = client()) {
            String group = "default.memorydb-" + engine;
            Map<String, Parameter> complete = parameters(client, group);
            Map<String, Parameter> paged = new TreeMap<>();
            Set<String> tokens = new HashSet<>();
            String token = null;
            int pageCount = 0;
            do {
                String next = token;
                DescribeParametersResponse page = client.describeParameters(request -> request
                        .parameterGroupName(group).maxResults(20).nextToken(next));
                assertFalse(page.parameters().isEmpty());
                assertTrue(page.parameters().size() <= 20);
                for (Parameter parameter : page.parameters()) {
                    assertNull(paged.put(parameter.name(), parameter), "No parameter may appear on two pages");
                }
                token = page.nextToken();
                if (token != null) {
                    assertFalse(token.isEmpty());
                    assertTrue(tokens.add(token), "A continuation token must progress");
                }
                assertTrue(++pageCount <= 10, "The owned fixed catalog must terminate");
            } while (token != null);
            assertTrue(pageCount >= 2);
            assertEquals(complete, paged);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"redis6", "redis7", "valkey7"})
    void customGroupUpdatesResetsTagsAndDeletesThroughSdk(String engine) {
        try (MemoryDbClient client = client()) {
            String name = freshName();
            ParameterGroup group = client.createParameterGroup(request -> request.parameterGroupName(name)
                    .family("memorydb_" + engine).description("retained description")
                    .tags(tag -> tag.key("team").value("first"))).parameterGroup();
            try {
                assertEquals(name, group.name());
                assertEquals("memorydb_" + engine, group.family());
                assertEquals(group, client.describeParameterGroups(request -> request.parameterGroupName(name))
                        .parameterGroups().getFirst());
                Map<String, Parameter> baseline = parameters(client, name);
                assertEquals(group, client.updateParameterGroup(request -> request.parameterGroupName(name)
                        .parameterNameValues(value("timeout", "20"), value("slowlog-log-slower-than", "-1")))
                        .parameterGroup());
                Map<String, Parameter> changed = parameters(client, name);
                assertEquals("20", changed.get("timeout").value());
                assertEquals("-1", changed.get("slowlog-log-slower-than").value());
                assertEquals(group, client.resetParameterGroup(request -> request.parameterGroupName(name)
                        .parameterNames("timeout")).parameterGroup());
                assertEquals("0", parameters(client, name).get("timeout").value());
                assertEquals("-1", parameters(client, name).get("slowlog-log-slower-than").value());
                assertEquals(group, client.resetParameterGroup(request -> request.parameterGroupName(name)
                        .allParameters(true)).parameterGroup());
                assertEquals(baseline, parameters(client, name));
                assertEquals("first", client.listTags(request -> request.resourceArn(group.arn()))
                        .tagList().getFirst().value());
                client.tagResource(request -> request.resourceArn(group.arn())
                        .tags(tag -> tag.key("team").value("second")));
                assertEquals("second", client.listTags(request -> request.resourceArn(group.arn()))
                        .tagList().getFirst().value());
                client.untagResource(request -> request.resourceArn(group.arn()).tagKeys("team"));
                assertTrue(client.listTags(request -> request.resourceArn(group.arn())).tagList().isEmpty());
                assertEquals(group, client.deleteParameterGroup(request -> request.parameterGroupName(name))
                        .parameterGroup());
                assertThrows(ParameterGroupNotFoundException.class,
                        () -> client.describeParameterGroups(request -> request.parameterGroupName(name)));
                ParameterGroup recreated = client.createParameterGroup(request -> request.parameterGroupName(name)
                        .family("memorydb_" + engine).description("new lifetime")).parameterGroup();
                assertEquals("new lifetime", recreated.description());
                assertEquals(baseline, parameters(client, name));
                assertTrue(client.listTags(request -> request.resourceArn(recreated.arn())).tagList().isEmpty());
            } finally {
                deleteIfPresent(client, name);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DeleteParameterGroup", "DescribeParameterGroups", "DescribeParameters",
            "UpdateParameterGroup", "ResetParameterGroup"})
    void missingGroupReturnsModeledFaultWithoutCreatingAParent(String operation) {
        try (MemoryDbClient client = client()) {
            String name = freshName();
            ParameterGroupNotFoundException failure = assertThrows(ParameterGroupNotFoundException.class, () -> {
                switch (operation) {
                    case "DeleteParameterGroup" ->
                            client.deleteParameterGroup(request -> request.parameterGroupName(name));
                    case "DescribeParameterGroups" ->
                            client.describeParameterGroups(request -> request.parameterGroupName(name));
                    case "DescribeParameters" ->
                            client.describeParameters(request -> request.parameterGroupName(name));
                    case "UpdateParameterGroup" -> client.updateParameterGroup(request -> request
                            .parameterGroupName(name).parameterNameValues(value("timeout", "20")));
                    case "ResetParameterGroup" -> client.resetParameterGroup(request -> request
                            .parameterGroupName(name).allParameters(true));
                    default -> throw new AssertionError(operation);
                }
            });
            assertEquals(400, failure.statusCode());
            assertEquals("ParameterGroupNotFoundFault", failure.awsErrorDetails().errorCode());
        }
    }

    @Test
    void mixedCaseCreateCanonicalizesTheIdentityAndDefaultsDescription() {
        try (MemoryDbClient client = client()) {
            String lower = freshName();
            String upper = lower.toUpperCase(Locale.ROOT);
            ParameterGroup group = client.createParameterGroup(request -> request.parameterGroupName(upper)
                    .family("MEMORYDB_REDIS6")).parameterGroup();
            try {
                assertEquals(lower, group.name());
                assertEquals(" ", group.description());
                assertEquals("memorydb_redis6", group.family());
                assertEquals("arn:aws:memorydb:us-east-1:000000000000:parametergroup/" + lower, group.arn());
                assertEquals(group, client.describeParameterGroups(request -> request.parameterGroupName(upper))
                        .parameterGroups().getFirst());
                assertThrows(ParameterGroupAlreadyExistsException.class, () -> client.createParameterGroup(request ->
                        request.parameterGroupName(lower).family("memorydb_redis6")));
            } finally {
                deleteIfPresent(client, lower);
            }
        }
    }

    private static Map<String, Parameter> parameters(MemoryDbClient client, String name) {
        DescribeParametersResponse response = client.describeParameters(request -> request.parameterGroupName(name));
        assertNull(response.nextToken(), "The bounded family catalog fits in the default page");
        List<String> names = new ArrayList<>();
        Map<String, Parameter> result = new TreeMap<>();
        for (Parameter parameter : response.parameters()) {
            names.add(parameter.name());
            assertNull(result.put(parameter.name(), parameter), "Parameter names are unique");
            assertNotNull(parameter.description());
            assertNotNull(parameter.minimumEngineVersion());
        }
        assertFalse(result.isEmpty());
        assertEquals(new ArrayList<>(result.keySet()), names);
        return result;
    }

    private static ParameterNameValue value(String name, String value) {
        return ParameterNameValue.builder().parameterName(name).parameterValue(value).build();
    }

    private static String freshName() {
        return "sdk-pg-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void deleteIfPresent(MemoryDbClient client, String name) {
        try {
            client.deleteParameterGroup(request -> request.parameterGroupName(name));
        } catch (ParameterGroupNotFoundException expected) {
            // The lifecycle may have already deleted the owned group.
        }
    }
}
