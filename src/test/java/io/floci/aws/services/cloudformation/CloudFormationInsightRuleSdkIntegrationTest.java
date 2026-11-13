package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsResponse;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
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

/** Native rule reads and exact ARN outputs prevent a stubbed CREATE_COMPLETE from passing. */
@QuarkusTest
class CloudFormationInsightRuleSdkIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "us-east-1";
    private final String owner = UUID.randomUUID().toString();
    private final Set<String> ownedNames = new HashSet<>();
    private final Deque<String> stackIds = new ArrayDeque<>();
    private final Map<String, String> attemptedStacks = new LinkedHashMap<>();
    private CloudWatchClient cloudWatch;
    private CloudFormationClient cloudFormation;

    @TestHTTPResource
    URI endpoint;

    @BeforeEach
    void clients() {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCOUNT, "test"));
        cloudWatch = CloudWatchClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))).build();
        cloudFormation = CloudFormationClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))).build();
    }

    @AfterEach
    void cleanupStacksThenRemainingOwnedRules() {
        AssertionError failure = null;
        for (Map.Entry<String, String> attempted : attemptedStacks.entrySet()) {
            if (attempted.getValue() != null) {
                continue;
            }
            try {
                List<Stack> found = cloudFormation.describeStacks(request -> request.stackName(attempted.getKey()))
                        .stacks();
                assertEquals(1, found.size());
                Stack recovered = found.getFirst();
                assertEquals(attempted.getKey(), recovered.stackName());
                assertOwnedStackId(recovered.stackId(), attempted.getKey());
                attempted.setValue(recovered.stackId());
                stackIds.push(recovered.stackId());
            } catch (CloudFormationException absent) {
                try {
                    assertAbsent(absent, attempted.getKey());
                } catch (AssertionError error) {
                    failure = cleanupFailure(failure, error);
                }
            } catch (RuntimeException | AssertionError error) {
                failure = cleanupFailure(failure, error);
            }
        }
        while (!stackIds.isEmpty()) {
            try {
                deleteStack(stackIds.pop());
            } catch (RuntimeException | AssertionError error) {
                failure = cleanupFailure(failure, error);
            }
        }
        for (String name : ownedNames) {
            try {
                if (rules().containsKey(name)) {
                    assertEquals(owner, tags(name).get("audit-owner"));
                    assertTrue(cloudWatch.deleteInsightRules(request -> request.ruleNames(name)).failures().isEmpty());
                }
                assertFalse(rules().containsKey(name));
            } catch (RuntimeException | AssertionError error) {
                failure = cleanupFailure(failure, error);
            }
        }
        try {
            cloudFormation.close();
        } catch (RuntimeException error) {
            failure = cleanupFailure(failure, error);
        }
        try {
            cloudWatch.close();
        } catch (RuntimeException error) {
            failure = cleanupFailure(failure, error);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static AssertionError cleanupFailure(AssertionError failure, Throwable error) {
        if (failure == null) {
            failure = new AssertionError("CloudFormation insight rule cleanup failed");
        }
        failure.addSuppressed(error);
        return failure;
    }

    @Test
    void createAndRepeatedInPlaceUpdatesExposeArnRefAndBothGetAttValues() throws Exception {
        String name = name("current");
        String id = create(template(name, "5", Map.of("team", "first", "drop", "old"), false, false));
        assertOutputs(id, name);
        assertEquals(definition("5"), rules().get(name).definition());
        cloudWatch.tagResource(request -> request.resourceARN(arn(name))
                .tags(Tag.builder().key("external").value("keep").build()));
        update(id, template(name, "4", Map.of("team", "second"), false, false), "UPDATE_COMPLETE");
        assertOutputs(id, name);
        assertEquals(definition("4"), rules().get(name).definition());
        assertEquals(Map.of("audit-owner", owner, "team", "second", "external", "keep"), tags(name));
        update(id, template(name, "5", Map.of(), false, false), "UPDATE_COMPLETE");
        assertOutputs(id, name);
        assertEquals(Map.of("audit-owner", owner, "external", "keep"), tags(name));
        deleteStack(id);
        assertFalse(rules().containsKey(name));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacementDeletesOrRetainsTheOldRuleAndAlwaysDeletesTheCurrentRule(boolean retain) throws Exception {
        String first = name("first");
        String next = name("next");
        String id = create(template(first, "5", Map.of(), false, retain));
        update(id, template(next, "4", Map.of(), false, retain), "UPDATE_COMPLETE");
        assertOutputs(id, next);
        assertEquals(definition("4"), rules().get(next).definition());
        assertEquals(retain, rules().containsKey(first));
        deleteStack(id);
        assertFalse(rules().containsKey(next));
        assertEquals(retain, rules().containsKey(first));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedUpdateRestoresExactDefinitionTagsAndIdentity(boolean rename) throws Exception {
        String first = name("rollback");
        String next = rename ? name("replacement") : first;
        String id = create(template(first, "5", Map.of("team", "before", "removed", "restore"), false, false));
        cloudWatch.tagResource(request -> request.resourceARN(arn(first))
                .tags(Tag.builder().key("external").value("keep").build()));
        Map<String, InsightRule> before = rules();
        Map<String, String> beforeTags = tags(first);
        update(id, template(next, "4", Map.of("team", "after", "added", "remove"), true, false),
                "UPDATE_ROLLBACK_COMPLETE");
        assertOutputs(id, first);
        assertEquals(before, rules());
        assertEquals(beforeTags, tags(first));
        deleteStack(id);
        assertFalse(rules().containsKey(first));
    }

    @Test
    void stackCreationCannotAdoptOrDeleteAnExistingNativeRule() throws Exception {
        String existing = name("collision");
        ownedNames.add(existing);
        cloudWatch.putInsightRule(request -> request.ruleName(existing).ruleState("DISABLED")
                .ruleDefinition(definition("5")).tags(Tag.builder().key("audit-owner").value(owner).build(),
                        Tag.builder().key("native").value("preserve").build()));
        Map<String, InsightRule> before = rules();
        Map<String, String> beforeTags = tags(existing);
        String id = create(uncheckedTemplate(existing, "4"), "ROLLBACK_COMPLETE");
        assertEquals(before, rules());
        assertEquals(beforeTags, tags(existing));
        deleteStack(id);
        assertEquals(before, rules());
        assertEquals(beforeTags, tags(existing));
    }

    private String create(String template) {
        return create(template, "CREATE_COMPLETE");
    }

    private String create(String template, String expected) {
        String token = UUID.randomUUID().toString();
        String stackName = name("stack");
        attemptedStacks.put(stackName, null);
        String id = cloudFormation.createStack(request -> request.stackName(stackName)
                .templateBody(template).clientRequestToken(token)).stackId();
        assertOwnedStackId(id, stackName);
        attemptedStacks.put(stackName, id);
        stackIds.push(id);
        awaitOperation(id, Set.of(), expected);
        return id;
    }

    private void update(String id, String template, String expected) {
        Set<String> previousEvents = eventIds(id);
        String token = UUID.randomUUID().toString();
        cloudFormation.updateStack(request -> request.stackName(id).templateBody(template).clientRequestToken(token));
        awaitOperation(id, previousEvents, expected);
    }

    private void awaitOperation(String id, Set<String> previousEvents, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            Stack stack = cloudFormation.describeStacks(request -> request.stackName(id)).stacks().getFirst();
            assertEquals(id, stack.stackId());
            boolean completed = events(id).stream().anyMatch(event -> !previousEvents.contains(event.eventId())
                    && id.equals(event.stackId()) && id.equals(event.physicalResourceId())
                    && "AWS::CloudFormation::Stack".equals(event.resourceType())
                    && expected.equals(event.resourceStatusAsString()));
            if (completed && expected.equals(stack.stackStatusAsString())) {
                return;
            }
            pause();
        }
        throw new AssertionError("No new owned " + expected + " event for stack " + id);
    }

    private List<StackEvent> events(String id) {
        List<StackEvent> result = new ArrayList<>();
        Set<String> tokens = new HashSet<>();
        Set<String> identities = new HashSet<>();
        String token = null;
        for (int page = 0; page < 100; page++) {
            String current = token;
            DescribeStackEventsResponse response = cloudFormation.describeStackEvents(request ->
                    request.stackName(id).nextToken(current));
            for (StackEvent event : response.stackEvents()) {
                assertEquals(id, event.stackId());
                assertNotNull(event.eventId());
                assertTrue(identities.add(event.eventId()), "Duplicate stack event identity");
                result.add(event);
            }
            token = response.nextToken();
            if (token == null) {
                return result;
            }
            assertTrue(tokens.add(token), "Repeated stack event cursor");
        }
        throw new AssertionError("Stack event pagination did not terminate");
    }

    private Set<String> eventIds(String id) {
        return events(id).stream().map(StackEvent::eventId).collect(Collectors.toSet());
    }

    private static void assertOwnedStackId(String id, String name) {
        assertNotNull(id);
        assertTrue(id.startsWith("arn:aws:cloudformation:" + REGION + ":" + ACCOUNT + ":stack/" + name + "/"));
    }

    private void deleteStack(String id) {
        try {
            Stack current = cloudFormation.describeStacks(request -> request.stackName(id)).stacks().getFirst();
            assertEquals(id, current.stackId());
            if (!"DELETE_COMPLETE".equals(current.stackStatusAsString())) {
                cloudFormation.deleteStack(request -> request.stackName(id));
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                Stack stack = cloudFormation.describeStacks(request -> request.stackName(id)).stacks().getFirst();
                assertEquals(id, stack.stackId());
                if ("DELETE_COMPLETE".equals(stack.stackStatusAsString())) {
                    for (int attempts = 0; attempts < 100; attempts++) {
                        try {
                            Stack remaining = cloudFormation.describeStacks(request -> request.stackName(stack.stackName()))
                                    .stacks().getFirst();
                            assertEquals(id, remaining.stackId());
                            assertEquals("DELETE_COMPLETE", remaining.stackStatusAsString());
                            pause();
                        } catch (CloudFormationException absent) {
                            assertAbsent(absent, stack.stackName());
                            return;
                        }
                    }
                    throw new AssertionError("Deleted stack name remains visible");
                }
                assertEquals("DELETE_IN_PROGRESS", stack.stackStatusAsString());
                pause();
            }
            throw new AssertionError("Stack deletion did not settle");
        } catch (CloudFormationException absent) {
            assertAbsent(absent, id);
        }
    }

    private static void assertAbsent(CloudFormationException error, String lookup) {
        assertEquals(400, error.statusCode());
        assertEquals("ValidationError", error.awsErrorDetails().errorCode());
        assertEquals("Stack with id " + lookup + " does not exist", error.awsErrorDetails().errorMessage());
    }

    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private void assertOutputs(String id, String name) {
        Map<String, String> outputs = cloudFormation.describeStacks(request -> request.stackName(id))
                .stacks().getFirst().outputs().stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
        assertEquals(Map.of("RefValue", arn(name), "ArnValue", arn(name), "NameValue", name), outputs);
        assertNotNull(rules().get(name));
    }

    private Map<String, InsightRule> rules() {
        Map<String, InsightRule> result = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        String token = null;
        for (int pages = 0; pages < 100; pages++) {
            String current = token;
            DescribeInsightRulesResponse response =
                    cloudWatch.describeInsightRules(request -> request.nextToken(current).maxResults(500));
            for (InsightRule rule : response.insightRules()) {
                assertNull(result.put(rule.name(), rule));
            }
            token = response.nextToken();
            if (token == null) {
                return result;
            }
            assertTrue(seen.add(token));
        }
        throw new AssertionError("Rule pagination did not terminate");
    }

    private Map<String, String> tags(String name) {
        return cloudWatch.listTagsForResource(request -> request.resourceARN(arn(name))).tags().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value));
    }

    private String uncheckedTemplate(String name, String prefix) {
        try {
            return template(name, prefix, Map.of(), false, false);
        } catch (JsonProcessingException error) {
            throw new AssertionError(error);
        }
    }

    private String template(String name, String prefix, Map<String, String> extraTags, boolean fail, boolean retain)
            throws JsonProcessingException {
        ownedNames.add(name);
        Map<String, String> tags = new LinkedHashMap<>(extraTags);
        tags.put("audit-owner", owner);
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("Rule", Map.of("Type", "AWS::CloudWatch::InsightRule",
                "UpdateReplacePolicy", retain ? "Retain" : "Delete",
                "Properties", Map.of("RuleName", name, "RuleState", "DISABLED", "RuleBody", definition(prefix),
                        "Tags", tags.entrySet().stream().map(entry ->
                                Map.of("Key", entry.getKey(), "Value", entry.getValue())).toList())));
        if (fail) {
            resources.put("Failure", Map.of("Type", "AWS::SecretsManager::Secret", "DependsOn", "Rule",
                    "Properties", Map.of("SecretString", "explicit",
                            "GenerateSecretString", Map.of("PasswordLength", 32))));
        }
        return MAPPER.writeValueAsString(Map.of("Resources", resources, "Outputs", Map.of(
                "RefValue", Map.of("Value", Map.of("Ref", "Rule")),
                "ArnValue", Map.of("Value", Map.of("Fn::GetAtt", List.of("Rule", "Arn"))),
                "NameValue", Map.of("Value", Map.of("Fn::GetAtt", List.of("Rule", "RuleName"))))));
    }

    private static String definition(String prefix) {
        return "{\"Schema\":{\"Name\":\"CloudWatchLogRule\",\"Version\":1},"
                + "\"LogGroupNames\":[\"/example/cfn\"],\"LogFormat\":\"JSON\","
                + "\"Contribution\":{\"Keys\":[\"$.client\"],\"Filters\":[{\"Match\":\"$.status\","
                + "\"StartsWith\":[\"" + prefix + "\"]}]},\"AggregateOn\":\"Count\"}";
    }

    private String name(String suffix) {
        return "insight-" + owner + "-" + suffix;
    }

    private static String arn(String name) {
        return "arn:aws:cloudwatch:" + REGION + ":" + ACCOUNT + ":insight-rule/" + name;
    }
}
