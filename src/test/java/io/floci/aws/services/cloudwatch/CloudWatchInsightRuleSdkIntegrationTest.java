package io.floci.aws.services.cloudwatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.CloudWatchException;
import software.amazon.awssdk.services.cloudwatch.model.DescribeInsightRulesResponse;
import software.amazon.awssdk.services.cloudwatch.model.InsightRule;
import software.amazon.awssdk.services.cloudwatch.model.Tag;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Public SDK calls only: this exact source can run before the native implementation exists. */
@QuarkusTest
class CloudWatchInsightRuleSdkIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "us-east-1";
    private final String owner = UUID.randomUUID().toString();
    private final Deque<Runnable> cleanup = new ArrayDeque<>();
    private final List<CloudWatchClient> clients = new ArrayList<>();
    private final Map<CloudWatchClient, Scope> scopes = new LinkedHashMap<>();

    private record Scope(String account, String region) {}

    @TestHTTPResource
    URI endpoint;

    @AfterEach
    void deleteOwnedRulesAndCloseClients() {
        AssertionError failure = null;
        while (!cleanup.isEmpty()) {
            try {
                cleanup.pop().run();
            } catch (RuntimeException | AssertionError error) {
                if (failure == null) {
                    failure = new AssertionError("Insight rule cleanup failed");
                }
                failure.addSuppressed(error);
            }
        }
        for (CloudWatchClient client : clients) {
            try {
                client.close();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = new AssertionError("Insight rule client close failed");
                }
                failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Test
    void disabledRuleRoundTripPreservesDefinitionAndCreateOnlyTags() throws Exception {
        CloudWatchClient client = client(ACCOUNT, REGION);
        String name = name("lifecycle");
        String original = definition("/example/service", "5");
        put(client, name, original, Map.of("team", "first", "removed", "old"));
        InsightRule created = rule(client, name);
        assertEquals(name, created.name());
        assertEquals("DISABLED", created.state());
        assertEquals(original, created.definition());
        assertEquals("CloudWatchLogRule/1", created.schema());
        assertFalse(created.managedRule());
        assertFalse(created.applyOnTransformedLogs());
        String arn = arn("aws", REGION, ACCOUNT, name);
        String updated = definition("/example/service", "4");
        client.putInsightRule(request -> request.ruleName(name).ruleDefinition(updated).ruleState("DISABLED")
                .tags(Tag.builder().key("team").value("ignored").build()));
        assertEquals(updated, rule(client, name).definition());
        assertEquals(Map.of("team", "first", "removed", "old", "audit-owner", owner), tags(client, arn));
        client.tagResource(request -> request.resourceARN(arn)
                .tags(Tag.builder().key("team").value("second").build(),
                        Tag.builder().key("external").value("keep").build()));
        client.untagResource(request -> request.resourceARN(arn).tagKeys("removed"));
        assertEquals(Map.of("team", "second", "external", "keep", "audit-owner", owner), tags(client, arn));
        assertTrue(client.disableInsightRules(request -> request.ruleNames(name)).failures().isEmpty());
        assertEquals("DISABLED", rule(client, name).state());
        assertTrue(client.deleteInsightRules(request -> request.ruleNames(name)).failures().isEmpty());
        assertFalse(all(client, 500).containsKey(name));
        CloudWatchException missing = assertThrows(CloudWatchException.class,
                () -> client.listTagsForResource(request -> request.resourceARN(arn)));
        assertEquals(404, missing.statusCode());
        assertEquals("ResourceNotFoundException", missing.awsErrorDetails().errorCode());
    }

    @Test
    void paginationIsCompleteAndCannotCrossAccountsOrRegions() {
        CloudWatchClient first = client(ACCOUNT, REGION);
        CloudWatchClient otherAccount = client("444455556666", REGION);
        CloudWatchClient otherRegion = client(ACCOUNT, "eu-west-1");
        Set<String> own = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            String name = name("page-" + i);
            own.add(name);
            put(first, name, definition("/example/page", "5"), Map.of());
        }
        String repeated = own.iterator().next();
        put(otherAccount, repeated, definition("/example/account", "4"), Map.of());
        put(otherRegion, repeated, definition("/example/region", "4"), Map.of());
        assertTrue(all(first, 1).keySet().containsAll(own));
        assertFalse(all(otherAccount, 500).keySet().containsAll(own));
        assertFalse(all(otherRegion, 500).keySet().containsAll(own));
        DescribeInsightRulesResponse page = first.describeInsightRules(request -> request.maxResults(1));
        assertNotNull(page.nextToken());
        for (CloudWatchClient scoped : List.of(otherAccount, otherRegion)) {
            CloudWatchException invalid = assertThrows(CloudWatchException.class,
                    () -> scoped.describeInsightRules(request -> request.maxResults(1).nextToken(page.nextToken())));
            assertEquals("InvalidNextToken", invalid.awsErrorDetails().errorCode());
        }
        assertEquals(definition("/example/account", "4"), rule(otherAccount, repeated).definition());
        assertEquals(definition("/example/region", "4"), rule(otherRegion, repeated).definition());
    }

    @Test
    void rejectedDefinitionAndConfigurationLeaveFullRuleAndTagsUnchanged() {
        CloudWatchClient client = client(ACCOUNT, REGION);
        String name = name("refusal");
        String definition = definition("/example/refusal", "5");
        put(client, name, definition, Map.of("team", "keep"));
        Map<String, InsightRule> before = all(client, 500);
        Map<String, String> beforeTags = tags(client, arn("aws", REGION, ACCOUNT, name));
        for (String invalid : List.of("{", definition + "{}",
                definition.replace("\"Count\"", "\"Sum\""),
                definition.replace("\"JSON\"", "\"CLF\""),
                definition.replace("\"StartsWith\"", "\"NotIn\""))) {
            assertThrows(CloudWatchException.class, () -> client.putInsightRule(request ->
                    request.ruleName(name).ruleDefinition(invalid).ruleState("DISABLED")));
            assertEquals(before, all(client, 500));
            assertEquals(beforeTags, tags(client, arn("aws", REGION, ACCOUNT, name)));
        }
        assertThrows(CloudWatchException.class, () -> client.putInsightRule(request -> request.ruleName(name)
                .ruleDefinition(definition).ruleState("DISABLED").applyOnTransformedLogs(true)));
        assertThrows(CloudWatchException.class, () -> client.putInsightRule(request -> request.ruleName(name)
                .ruleDefinition(definition).ruleState("PAUSED")));
        assertEquals(before, all(client, 500));
        assertEquals(beforeTags, tags(client, arn("aws", REGION, ACCOUNT, name)));
    }

    @Test
    void ruleNamesAndTagArnsRespectPartitionsAndTheDocumentedLengthLimit() {
        for (Map.Entry<String, String> scope : Map.of("us-east-1", "aws", "cn-north-1", "aws-cn",
                "us-gov-west-1", "aws-us-gov").entrySet()) {
            CloudWatchClient client = client(ACCOUNT, scope.getKey());
            String name = name("partition");
            name = name + "x".repeat(128 - name.length());
            put(client, name, definition("/example/partition", "5"), Map.of());
            String expectedArn = arn(scope.getValue(), scope.getKey(), ACCOUNT, name);
            assertEquals(owner, tags(client, expectedArn).get("audit-owner"));
            String tooLong = name + "x";
            CloudWatchException invalid = assertThrows(CloudWatchException.class, () ->
                    client.putInsightRule(request -> request.ruleName(tooLong)
                            .ruleDefinition(definition("/example/partition", "5")).ruleState("DISABLED")));
            assertEquals(400, invalid.statusCode());
            assertFalse(all(client, 500).containsKey(tooLong));
        }
    }

    private CloudWatchClient client(String account, String region) {
        CloudWatchClient client = CloudWatchClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10)))
                .build();
        clients.add(client);
        scopes.put(client, new Scope(account, region));
        return client;
    }

    private void put(CloudWatchClient client, String name, String definition, Map<String, String> extraTags) {
        Map<String, String> tags = new LinkedHashMap<>(extraTags);
        tags.put("audit-owner", owner);
        cleanup.push(() -> {
            if (all(client, 500).containsKey(name)) {
                Scope scope = scopes.get(client);
                String partition = scope.region().startsWith("cn-") ? "aws-cn"
                        : scope.region().startsWith("us-gov-") ? "aws-us-gov" : "aws";
                assertEquals(owner, tags(client, arn(partition, scope.region(), scope.account(), name))
                        .get("audit-owner"));
                assertTrue(client.deleteInsightRules(request -> request.ruleNames(name)).failures().isEmpty());
            }
            assertFalse(all(client, 500).containsKey(name));
        });
        client.putInsightRule(request -> request.ruleName(name).ruleState("DISABLED").ruleDefinition(definition)
                .tags(tags.entrySet().stream().map(entry ->
                        Tag.builder().key(entry.getKey()).value(entry.getValue()).build()).toList()));
    }

    private static Map<String, InsightRule> all(CloudWatchClient client, int maximum) {
        Map<String, InsightRule> result = new LinkedHashMap<>();
        Set<String> tokens = new HashSet<>();
        String token = null;
        for (int pages = 0; pages < 100; pages++) {
            String current = token;
            DescribeInsightRulesResponse page = client.describeInsightRules(request ->
                    request.maxResults(maximum).nextToken(current));
            for (InsightRule rule : page.insightRules()) {
                assertNull(result.put(rule.name(), rule), "Duplicate rule across pages");
            }
            token = page.nextToken();
            if (token == null) {
                return result;
            }
            assertTrue(tokens.add(token), "Repeated pagination token");
        }
        throw new AssertionError("Insight rule pagination did not terminate");
    }

    private static InsightRule rule(CloudWatchClient client, String name) {
        InsightRule rule = all(client, 500).get(name);
        assertNotNull(rule, "A CFN stub or empty SDK response cannot satisfy the native rule read");
        return rule;
    }

    private static Map<String, String> tags(CloudWatchClient client, String arn) {
        return client.listTagsForResource(request -> request.resourceARN(arn)).tags().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value));
    }

    private String name(String suffix) {
        return "insight-" + owner + "-" + suffix;
    }

    static String definition(String group, String prefix) {
        return "{\"Schema\":{\"Name\":\"CloudWatchLogRule\",\"Version\":1},\"LogGroupNames\":[\"" + group
                + "\"],\"LogFormat\":\"JSON\",\"Contribution\":{\"Keys\":[\"$.client\"],"
                + "\"Filters\":[{\"Match\":\"$.status\",\"StartsWith\":[\"" + prefix
                + "\"]}]},\"AggregateOn\":\"Count\"}";
    }

    private static String arn(String partition, String region, String account, String name) {
        return "arn:" + partition + ":cloudwatch:" + region + ":" + account + ":insight-rule/" + name;
    }
}
