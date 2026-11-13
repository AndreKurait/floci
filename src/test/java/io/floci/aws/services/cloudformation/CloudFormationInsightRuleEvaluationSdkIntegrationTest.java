package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import software.amazon.awssdk.services.cloudwatch.model.CloudWatchException;
import software.amazon.awssdk.services.cloudwatch.model.DescribeInsightRulesResponse;
import software.amazon.awssdk.services.cloudwatch.model.GetInsightRuleReportResponse;
import software.amazon.awssdk.services.cloudwatch.model.InsightRule;
import software.amazon.awssdk.services.cloudwatch.model.InsightRuleContributor;
import software.amazon.awssdk.services.cloudwatch.model.Tag;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
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

/** SDK ingestion and reports validate the enabled rule behind the stack throughout its lifecycle. */
@QuarkusTest
class CloudFormationInsightRuleEvaluationSdkIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "us-east-1";
    private final String owner = UUID.randomUUID().toString();
    private final Instant base = Instant.ofEpochMilli(System.currentTimeMillis() / 60000 * 60000 - 120000);
    private final Set<String> ownedGroups = new HashSet<>();
    private final List<Runnable> close = new ArrayList<>();
    private Map<String, InsightRule> baselineRules;
    private Map<String, LogGroup> baselineGroups;
    private CloudWatchLogsClient logs;
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
        close.add(cloudWatch::close);
        cloudFormation = CloudFormationClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))).build();
        close.add(cloudFormation::close);
        logs = CloudWatchLogsClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))).build();
        close.add(logs::close);
        baselineRules = rules();
        baselineGroups = groups();
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
        for (String group : ownedGroups) {
            try {
                if (groups().containsKey(group)) {
                    assertEquals(owner, logs.listTagsLogGroup(request -> request.logGroupName(group))
                            .tags().get("audit-owner"));
                    logs.deleteLogGroup(request -> request.logGroupName(group));
                }
                assertFalse(groups().containsKey(group));
                assertEquals(400, assertThrows(ResourceNotFoundException.class,
                        () -> logs.describeLogStreams(request -> request.logGroupName(group))).statusCode());
            } catch (RuntimeException | AssertionError error) {
                failure = cleanupFailure(failure, error);
            }
        }
        try {
            if (baselineRules != null) {
                assertEquals(baselineRules, rules());
            }
            if (baselineGroups != null) {
                assertEquals(baselineGroups, groups());
            }
        } catch (RuntimeException | AssertionError error) {
            failure = cleanupFailure(failure, error);
        }
        for (Runnable action : close) {
            try {
                action.run();
            } catch (RuntimeException | AssertionError error) {
                failure = cleanupFailure(failure, error);
            }
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
    void enabledStackCountsSdkLogsAcrossStateDefinitionAndRollbackUpdates() throws Exception {
        String rule = name("current");
        String group = "/example/cfn-" + owner;
        String id = create(template(rule, group, "5", "ENABLED", false, false));
        assertOutputs(id, rule);
        assertEquals("ENABLED", rules().get(rule).state());
        assertEquals(owner, logs.listTagsLogGroup(request -> request.logGroupName(group)).tags().get("audit-owner"));
        assertEquals(List.of("events"), logs.describeLogStreams(request -> request.logGroupName(group))
                .logStreams().stream().map(stream -> stream.logStreamName()).toList());
        put(group, 1000, "before", "500");
        assertEquals(1.0, report(rule).aggregateValue());
        update(id, template(rule, group, "4", "ENABLED", false, false), "UPDATE_COMPLETE");
        assertOutputs(id, rule);
        assertEquals(definition(group, "4"), rules().get(rule).definition());
        assertEquals(1.0, report(rule).aggregateValue());
        put(group, 2000, "after", "400");
        put(group, 3000, "old-filter", "500");
        assertEquals(2.0, report(rule).aggregateValue());
        update(id, template(rule, group, "4", "DISABLED", false, false), "UPDATE_COMPLETE");
        assertEquals("DISABLED", rules().get(rule).state());
        put(group, 4000, "disabled", "400");
        assertEquals(2.0, report(rule).aggregateValue());
        update(id, template(rule, group, "4", "ENABLED", false, false), "UPDATE_COMPLETE");
        put(group, 5000, "reenabled", "400");
        assertEquals(3.0, report(rule).aggregateValue());
        List<InsightRuleContributor> before = report(rule).contributors();
        update(id, template(rule, group, "5", "ENABLED", true, false), "UPDATE_ROLLBACK_COMPLETE");
        assertOutputs(id, rule);
        assertEquals(definition(group, "4"), rules().get(rule).definition());
        assertEquals("ENABLED", rules().get(rule).state());
        assertEquals(before, report(rule).contributors());
        put(group, 6000, "after-rollback", "400");
        assertEquals(Set.of(List.of("before"), List.of("after"), List.of("reenabled"), List.of("after-rollback")),
                report(rule).contributors().stream().map(InsightRuleContributor::keys).collect(Collectors.toSet()));
        deleteStack(id);
        assertFalse(rules().containsKey(rule));
        assertFalse(groups().containsKey(group));
        assertRuleAbsent(rule);
    }

    @Test
    void retainedReplacementKeepsOldCountsAndStartsNewIdentityWithEmptyHistory() throws Exception {
        String first = name("first");
        String next = name("next");
        String group = "/example/cfn-" + owner;
        String id = create(template(first, group, "5", "ENABLED", false, true));
        put(group, 1000, "first-history", "500");
        assertEquals(1.0, report(first).aggregateValue());
        update(id, template(next, group, "4", "ENABLED", false, true), "UPDATE_COMPLETE");
        assertOutputs(id, next);
        assertEquals(1.0, report(first).aggregateValue());
        assertEquals(0.0, report(next).aggregateValue());
        assertEquals(owner, tags(first).get("audit-owner"));
        put(group, 2000, "new-history", "400");
        put(group, 3000, "retained-still-enabled", "500");
        assertEquals(1.0, report(next).aggregateValue());
        assertEquals(2.0, report(first).aggregateValue());
        deleteStack(id);
        assertFalse(rules().containsKey(next));
        assertFalse(groups().containsKey(group));
        assertRuleAbsent(next);
        assertEquals(2.0, report(first).aggregateValue());
        assertEquals("ENABLED", rules().get(first).state());
    }

    private void put(String group, long offset, String key, String status) {
        PutLogEventsResponse response = logs.putLogEvents(request -> request.logGroupName(group)
                .logStreamName("events").logEvents(InputLogEvent.builder().timestamp(base.toEpochMilli() + offset)
                        .message("{\"client\":\"" + key + "\",\"status\":\"" + status + "\"}").build()));
        assertNull(response.rejectedLogEventsInfo());
        assertNull(response.rejectedEntityInfo());
    }

    private GetInsightRuleReportResponse report(String name) {
        return cloudWatch.getInsightRuleReport(request -> request.ruleName(name).startTime(base)
                .endTime(base.plusSeconds(180)).period(60).maxContributorCount(100).orderBy("Sum")
                .metrics("SampleCount", "Sum", "UniqueContributors"));
    }

    private void assertRuleAbsent(String name) {
        CloudWatchException absent = assertThrows(CloudWatchException.class, () -> report(name));
        assertEquals(400, absent.statusCode());
        assertEquals("InvalidParameterValue", absent.awsErrorDetails().errorCode());
        assertEquals("RULE_NAME: " + name, absent.awsErrorDetails().errorMessage());
    }

    private Map<String, LogGroup> groups() {
        Map<String, LogGroup> result = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        String token = null;
        for (int page = 0; page < 100; page++) {
            String current = token;
            DescribeLogGroupsResponse response = logs.describeLogGroups(request -> request.nextToken(current));
            for (LogGroup group : response.logGroups()) {
                assertNull(result.put(group.logGroupName(), group));
            }
            token = response.nextToken();
            if (token == null) {
                return result;
            }
            assertTrue(seen.add(token));
        }
        throw new AssertionError("Log group inventory did not terminate");
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

    private String template(String name, String group, String prefix, String state, boolean fail, boolean retain)
            throws JsonProcessingException {
        ownedNames.add(name);
        ownedGroups.add(group);
        List<Map<String, String>> tags = List.of(Map.of("Key", "audit-owner", "Value", owner));
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("Group", Map.of("Type", "AWS::Logs::LogGroup", "Properties",
                Map.of("LogGroupName", group, "Tags", tags)));
        resources.put("Stream", Map.of("Type", "AWS::Logs::LogStream", "Properties",
                Map.of("LogGroupName", Map.of("Ref", "Group"), "LogStreamName", "events")));
        resources.put("Rule", Map.of("Type", "AWS::CloudWatch::InsightRule", "DependsOn", "Stream",
                "UpdateReplacePolicy", retain ? "Retain" : "Delete",
                "Properties", Map.of("RuleName", name, "RuleState", state, "RuleBody", definition(group, prefix),
                        "Tags", tags)));
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

    private static String definition(String group, String prefix) {
        return "{\"Schema\":{\"Name\":\"CloudWatchLogRule\",\"Version\":1},"
                + "\"LogGroupNames\":[\"" + group + "\"],\"LogFormat\":\"JSON\","
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
