package io.floci.aws.services.cloudwatch;

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
import software.amazon.awssdk.services.cloudwatch.model.GetInsightRuleReportResponse;
import software.amazon.awssdk.services.cloudwatch.model.InsightRule;
import software.amazon.awssdk.services.cloudwatch.model.InsightRuleContributor;
import software.amazon.awssdk.services.cloudwatch.model.InsightRuleMetricDatapoint;
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

/** Calls the public SDKs only, including actual Logs ingestion and native count reports. */
@QuarkusTest
class CloudWatchInsightRuleEvaluationSdkIntegrationTest {
    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "us-east-1";
    private static final List<String> METRICS = List.of("UniqueContributors", "MaxContributorValue", "SampleCount",
            "Sum", "Minimum", "Maximum", "Average");
    private final String owner = UUID.randomUUID().toString();
    private final Instant base = Instant.ofEpochMilli(System.currentTimeMillis() / 60000 * 60000 - 120000);
    private final Deque<Runnable> cleanup = new ArrayDeque<>();
    private final List<Runnable> close = new ArrayList<>();
    private final List<Scope> scopes = new ArrayList<>();

    private record Scope(CloudWatchClient watch, CloudWatchLogsClient logs, String account, String region,
                         Map<String, InsightRule> rules, Map<String, LogGroup> groups) {}

    private record Report(List<String> labels, String statistic, double aggregate, long unique,
                          List<InsightRuleContributor> contributors, List<InsightRuleMetricDatapoint> metrics) {}

    @TestHTTPResource
    URI endpoint;

    @AfterEach
    void cleanupOwnedResourcesRestoreFullInventoriesAndCloseEveryClient() {
        AssertionError failure = null;
        while (!cleanup.isEmpty()) {
            try {
                cleanup.pop().run();
            } catch (RuntimeException | AssertionError error) {
                failure = failure(failure, error);
            }
        }
        for (Scope scope : scopes) {
            try {
                assertEquals(scope.rules(), rules(scope.watch()));
                assertEquals(scope.groups(), groups(scope.logs()));
            } catch (RuntimeException | AssertionError error) {
                failure = failure(failure, error);
            }
        }
        for (Runnable action : close) {
            try {
                action.run();
            } catch (RuntimeException | AssertionError error) {
                failure = failure(failure, error);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Test
    void sdkReportsMatchCountBucketsIntervalsAndMaximumRanking() {
        Scope scope = scope(ACCOUNT, REGION);
        String group = group(scope);
        String rule = rule(scope, "buckets", group, null);
        put(scope, group, List.of(event(59999, "alpha", "500"), event(60000, "alpha", "503"),
                event(60001, "beta", "500"), event(60002, "ignored", "200"),
                event(119999, "beta", "503"), event(120000, "gamma", "500")));
        GetInsightRuleReportResponse report = report(scope, rule);
        assertEquals(List.of("$.client"), report.keyLabels());
        assertEquals("SampleCount", report.aggregationStatistic());
        assertEquals(5.0, report.aggregateValue());
        assertEquals(3L, report.approximateUniqueCount());
        assertEquals(List.of(2.0, 2.0, 1.0), report.contributors().stream()
                .map(InsightRuleContributor::approximateAggregateValue).toList());
        assertEquals(List.of(base, base.plusSeconds(60), base.plusSeconds(120)),
                report.metricDatapoints().stream().map(InsightRuleMetricDatapoint::timestamp).toList());
        InsightRuleMetricDatapoint middle = report.metricDatapoints().get(1);
        assertEquals(2.0, middle.uniqueContributors());
        assertEquals(2.0, middle.maxContributorValue());
        assertEquals(3.0, middle.sampleCount());
        assertEquals(3.0, middle.sum());
        assertEquals(1.0, middle.minimum());
        assertEquals(1.0, middle.maximum());
        assertEquals(1.0, middle.average());
        GetInsightRuleReportResponse maximum = scope.watch().getInsightRuleReport(request -> request.ruleName(rule)
                .startTime(base).endTime(base.plusSeconds(180)).period(60).maxContributorCount(2)
                .orderBy("Maximum").metrics(METRICS));
        assertEquals(5.0, maximum.aggregateValue());
        assertEquals(3L, maximum.approximateUniqueCount());
        assertEquals(List.of(List.of("beta"), List.of("alpha")),
                maximum.contributors().stream().map(InsightRuleContributor::keys).toList());
        assertEquals(List.of(2.0, 1.0), maximum.contributors().stream()
                .map(InsightRuleContributor::approximateAggregateValue).toList());
        assertEquals(report.metricDatapoints(), maximum.metricDatapoints());
        GetInsightRuleReportResponse twoMinutes = scope.watch().getInsightRuleReport(request -> request.ruleName(rule)
                .startTime(base.plusSeconds(60)).endTime(base.plusSeconds(180)).period(120)
                .maxContributorCount(100).metrics(METRICS));
        assertEquals(4.0, twoMinutes.aggregateValue());
        assertEquals(3L, twoMinutes.approximateUniqueCount());
        assertEquals(List.of(base.plusSeconds(60)), twoMinutes.metricDatapoints().stream()
                .map(InsightRuleMetricDatapoint::timestamp).toList());
        assertEquals(4.0, twoMinutes.metricDatapoints().getFirst().sampleCount());
        assertEquals(2.0, twoMinutes.metricDatapoints().getFirst().maxContributorValue());
        assertEquals(Set.of(base.plusSeconds(60), base.plusSeconds(120)), twoMinutes.contributors().stream()
                .flatMap(contributor -> contributor.datapoints().stream())
                .map(point -> point.timestamp()).collect(Collectors.toSet()));
        assertEquals(3.0, scope.watch().getInsightRuleReport(request -> request.ruleName(rule)
                .startTime(base.plusSeconds(60)).endTime(base.plusSeconds(120)).period(60)
                .metrics(METRICS)).aggregateValue());
        assertEquals(4.0, scope.watch().getInsightRuleReport(request -> request.ruleName(rule)
                .startTime(base.plusMillis(60001)).endTime(base.plusMillis(120001)).period(60)
                .metrics(METRICS)).aggregateValue());
        Report before = snapshot(report(scope, rule));
        for (int period : List.of(1, 59, 61, 86460)) {
            CloudWatchException invalid = assertThrows(CloudWatchException.class,
                    () -> scope.watch().getInsightRuleReport(request -> request.ruleName(rule)
                            .startTime(base).endTime(base.plusSeconds(180)).period(period)));
            assertEquals(400, invalid.statusCode());
            assertEquals("InvalidParameterValue", invalid.awsErrorDetails().errorCode());
        }
        CloudWatchException shortRange = assertThrows(CloudWatchException.class,
                () -> scope.watch().getInsightRuleReport(request -> request.ruleName(rule)
                        .startTime(base.plusMillis(60001)).endTime(base.plusMillis(119999)).period(60)));
        assertEquals("InvalidParameterValue", shortRange.awsErrorDetails().errorCode());
        assertEquals(before, snapshot(report(scope, rule)));
    }

    @Test
    void stateAndDefinitionUpdatesPreserveHistoryAndIgnoreNewPutTags() {
        Scope scope = scope(ACCOUNT, REGION);
        String group = group(scope);
        String rule = rule(scope, "state", group, "DISABLED");
        put(scope, group, List.of(event(1000, "disabled", "500")));
        assertEquals(0.0, report(scope, rule).aggregateValue());
        scope.watch().putInsightRule(request -> request.ruleName(rule)
                .ruleDefinition(CloudWatchInsightRuleSdkIntegrationTest.definition(group, "5"))
                .tags(Tag.builder().key("audit-owner").value("ignored").build()));
        assertEquals("DISABLED", rules(scope.watch()).get(rule).state());
        assertEquals(owner, tags(scope, rule).get("audit-owner"));
        assertEquals(0.0, report(scope, rule).aggregateValue());
        String missing = rule + "-absent";
        assertEquals("RULE_NAME", scope.watch().enableInsightRules(request -> request.ruleNames(rule, missing))
                .failures().getFirst().failureCode());
        assertEquals("ENABLED", rules(scope.watch()).get(rule).state());
        put(scope, group, List.of(event(2000, "before", "500")));
        Report original = snapshot(report(scope, rule));
        scope.watch().putInsightRule(request -> request.ruleName(rule)
                .ruleDefinition(CloudWatchInsightRuleSdkIntegrationTest.definition(group, "4"))
                .tags(Tag.builder().key("audit-owner").value("ignored").build()));
        assertEquals("ENABLED", rules(scope.watch()).get(rule).state());
        assertEquals(original, snapshot(report(scope, rule)));
        assertEquals(owner, tags(scope, rule).get("audit-owner"));
        put(scope, group, List.of(event(3000, "after", "400"), event(4000, "old-filter", "500")));
        assertEquals(2.0, report(scope, rule).aggregateValue());
        assertEquals("RULE_NAME", scope.watch().disableInsightRules(request -> request.ruleNames(rule, missing))
                .failures().getFirst().failureCode());
        put(scope, group, List.of(event(5000, "disabled-again", "400")));
        assertEquals(2.0, report(scope, rule).aggregateValue());
        scope.watch().enableInsightRules(request -> request.ruleNames(rule));
        put(scope, group, List.of(event(6000, "reenabled", "400")));
        assertEquals(Set.of(List.of("before"), List.of("after"), List.of("reenabled")),
                report(scope, rule).contributors().stream().map(InsightRuleContributor::keys).collect(Collectors.toSet()));
        assertTrue(scope.watch().deleteInsightRules(request -> request.ruleNames(rule)).failures().isEmpty());
        CloudWatchException absent = assertThrows(CloudWatchException.class, () -> report(scope, rule));
        assertEquals(400, absent.statusCode());
        assertEquals("InvalidParameterValue", absent.awsErrorDetails().errorCode());
        assertEquals("RULE_NAME: " + rule, absent.awsErrorDetails().errorMessage());
        scope.watch().putInsightRule(request -> request.ruleName(rule).ruleState("ENABLED")
                .ruleDefinition(CloudWatchInsightRuleSdkIntegrationTest.definition(group, "5"))
                .tags(Tag.builder().key("audit-owner").value(owner).build()));
        assertEquals(0.0, report(scope, rule).aggregateValue());
    }

    @Test
    void sameNamesStayIsolatedAcrossAccountsAndRegionsWithoutHistoricalReplay() {
        Scope first = scope(ACCOUNT, REGION);
        Scope other = scope("444455556666", REGION);
        Scope region = scope(ACCOUNT, "eu-west-1");
        String commonGroup = "/example/insight-" + owner;
        String commonRule = "count-" + owner + "-shared";
        int index = 0;
        for (Scope scope : List.of(first, other, region)) {
            group(scope, commonGroup);
            put(scope, commonGroup, List.of(event(1000, "historical", "500")));
            rule(scope, commonRule, commonGroup, "ENABLED", true);
            assertEquals(0.0, report(scope, commonRule).aggregateValue());
            put(scope, commonGroup, List.of(event(2000, "scope-" + index, "500")));
            index++;
        }
        index = 0;
        for (Scope scope : List.of(first, other, region)) {
            GetInsightRuleReportResponse report = report(scope, commonRule);
            assertEquals(1.0, report.aggregateValue());
            assertEquals(List.of("scope-" + index), report.contributors().getFirst().keys());
            index++;
        }
    }

    @Test
    void omittedEmptyAndExplicitMetricSelectionsPreserveCountsWithoutUnrequestedFields() {
        Scope scope = scope(ACCOUNT, REGION);
        String group = group(scope);
        String rule = rule(scope, "metrics", group, "ENABLED");
        put(scope, group, List.of(event(1000, "alpha", "500"), event(2000, "beta", "503")));
        GetInsightRuleReportResponse omitted = scope.watch().getInsightRuleReport(request -> request.ruleName(rule)
                .startTime(base).endTime(base.plusSeconds(180)).period(60));
        GetInsightRuleReportResponse empty = scope.watch().getInsightRuleReport(request -> request.ruleName(rule)
                .startTime(base).endTime(base.plusSeconds(180)).period(60).metrics(List.of()));
        assertEquals(2.0, omitted.aggregateValue());
        assertEquals(2L, omitted.approximateUniqueCount());
        assertEquals(snapshot(omitted), snapshot(empty));
        assertFalse(omitted.hasMetricDatapoints());
        assertFalse(empty.hasMetricDatapoints());
        for (InsightRuleMetricDatapoint point : omitted.metricDatapoints()) {
            for (String metric : METRICS) {
                assertTrue(point.getValueForField(metric, Double.class).isEmpty());
            }
        }
        GetInsightRuleReportResponse selected = scope.watch().getInsightRuleReport(request -> request.ruleName(rule)
                .startTime(base).endTime(base.plusSeconds(180)).period(60).metrics("SampleCount"));
        assertEquals(omitted.contributors(), selected.contributors());
        assertEquals(omitted.aggregateValue(), selected.aggregateValue());
        assertEquals(omitted.approximateUniqueCount(), selected.approximateUniqueCount());
        assertEquals(List.of(2.0, 0.0, 0.0), selected.metricDatapoints().stream()
                .map(InsightRuleMetricDatapoint::sampleCount).toList());
        for (InsightRuleMetricDatapoint point : selected.metricDatapoints()) {
            for (String metric : METRICS) {
                assertEquals("SampleCount".equals(metric), point.getValueForField(metric, Double.class).isPresent());
            }
        }
        assertEquals(snapshot(omitted), snapshot(scope.watch().getInsightRuleReport(request -> request.ruleName(rule)
                .startTime(base).endTime(base.plusSeconds(180)).period(60))));
    }

    private Scope scope(String account, String region) {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(account, "test"));
        CloudWatchClient watch = CloudWatchClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))).build();
        close.add(watch::close);
        CloudWatchLogsClient logs = CloudWatchLogsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))).build();
        close.add(logs::close);
        Scope scope = new Scope(watch, logs, account, region, rules(watch), groups(logs));
        scopes.add(scope);
        return scope;
    }

    private String group(Scope scope) {
        String group = "/example/insight-" + owner;
        group(scope, group);
        return group;
    }

    private void group(Scope scope, String group) {
        assertFalse(groups(scope.logs()).containsKey(group));
        cleanup.push(() -> {
            if (groups(scope.logs()).containsKey(group)) {
                assertEquals(owner, scope.logs().listTagsLogGroup(request -> request.logGroupName(group))
                        .tags().get("audit-owner"));
                scope.logs().deleteLogGroup(request -> request.logGroupName(group));
            }
            assertFalse(groups(scope.logs()).containsKey(group));
            assertEquals(400, assertThrows(ResourceNotFoundException.class,
                    () -> scope.logs().describeLogStreams(request -> request.logGroupName(group))).statusCode());
        });
        scope.logs().createLogGroup(request -> request.logGroupName(group).tags(Map.of("audit-owner", owner)));
        scope.logs().createLogStream(request -> request.logGroupName(group).logStreamName("events"));
    }

    private String rule(Scope scope, String suffix, String group, String state) {
        String name = "count-" + owner + "-" + suffix;
        rule(scope, name, group, state, false);
        return name;
    }

    private void rule(Scope scope, String name, String group, String state, boolean wildcard) {
        assertFalse(rules(scope.watch()).containsKey(name));
        cleanup.push(() -> {
            if (rules(scope.watch()).containsKey(name)) {
                assertEquals(owner, tags(scope, name).get("audit-owner"));
                assertTrue(scope.watch().deleteInsightRules(request -> request.ruleNames(name)).failures().isEmpty());
            }
            assertFalse(rules(scope.watch()).containsKey(name));
        });
        scope.watch().putInsightRule(request -> request.ruleName(name).ruleState(state)
                .ruleDefinition(CloudWatchInsightRuleSdkIntegrationTest.definition(group + (wildcard ? "*" : ""), "5"))
                .tags(Tag.builder().key("audit-owner").value(owner).build()));
    }

    private void put(Scope scope, String group, List<InputLogEvent> events) {
        PutLogEventsResponse response = scope.logs().putLogEvents(request -> request.logGroupName(group)
                .logStreamName("events").logEvents(events));
        assertNull(response.rejectedLogEventsInfo());
        assertNull(response.rejectedEntityInfo());
    }

    private InputLogEvent event(long offset, String key, String status) {
        return InputLogEvent.builder().timestamp(base.toEpochMilli() + offset)
                .message("{\"client\":\"" + key + "\",\"status\":\"" + status + "\"}").build();
    }

    private GetInsightRuleReportResponse report(Scope scope, String name) {
        return scope.watch().getInsightRuleReport(request -> request.ruleName(name).startTime(base)
                .endTime(base.plusSeconds(180)).period(60).maxContributorCount(100).orderBy("Sum").metrics(METRICS));
    }

    private static Report snapshot(GetInsightRuleReportResponse value) {
        return new Report(value.keyLabels(), value.aggregationStatistic(), value.aggregateValue(),
                value.approximateUniqueCount(), value.contributors(), value.metricDatapoints());
    }

    private static Map<String, InsightRule> rules(CloudWatchClient client) {
        Map<String, InsightRule> result = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        String token = null;
        for (int page = 0; page < 100; page++) {
            String current = token;
            DescribeInsightRulesResponse response = client.describeInsightRules(request ->
                    request.maxResults(500).nextToken(current));
            for (InsightRule rule : response.insightRules()) {
                assertNull(result.put(rule.name(), rule));
            }
            token = response.nextToken();
            if (token == null) {
                return result;
            }
            assertTrue(seen.add(token));
        }
        throw new AssertionError("Insight rule inventory did not terminate");
    }

    private static Map<String, LogGroup> groups(CloudWatchLogsClient client) {
        Map<String, LogGroup> result = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        String token = null;
        for (int page = 0; page < 100; page++) {
            String current = token;
            DescribeLogGroupsResponse response = client.describeLogGroups(request -> request.nextToken(current));
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

    private Map<String, String> tags(Scope scope, String rule) {
        return scope.watch().listTagsForResource(request -> request.resourceARN("arn:aws:cloudwatch:"
                + scope.region() + ":" + scope.account() + ":insight-rule/" + rule)).tags().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value));
    }

    private static AssertionError failure(AssertionError failure, Throwable error) {
        if (failure == null) {
            failure = new AssertionError("Insight evaluation SDK cleanup failed");
        }
        failure.addSuppressed(error);
        return failure;
    }
}
