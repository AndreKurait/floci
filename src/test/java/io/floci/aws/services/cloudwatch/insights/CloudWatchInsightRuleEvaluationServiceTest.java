package io.floci.aws.services.cloudwatch.insights;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.cloudwatch.insights.model.InsightRule;
import io.floci.aws.services.cloudwatch.logs.LogEventsIngested;
import io.floci.aws.services.cloudwatch.logs.model.LogEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchInsightRuleEvaluationServiceTest {
    private static final String ACCOUNT = "111122223333";
    private static final String OTHER = "444455556666";
    private static final String REGION = "us-east-1";
    private static final String GROUP = "/example/service";
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final TypeReference<Map<String, InsightRule>> TYPE = new TypeReference<>() {};
    private static final List<String> METRICS = List.of("UniqueContributors", "MaxContributorValue", "SampleCount",
            "Sum", "Minimum", "Maximum", "Average");

    @TempDir
    Path temporary;

    @Test
    void countBucketsAndMaximumRankingKeepFullPopulationStatistics() {
        CloudWatchInsightRulesService service = service(new InMemoryStorage<>(), ACCOUNT);
        service.put("rule", definition("5"), null, false, Map.of(), REGION);
        service.ingest(batch(ACCOUNT, REGION, GROUP, List.of(event(59999, "alpha", "500"),
                event(60000, "alpha", "503"), event(60001, "beta", "500"), event(60002, "ignored", "200"),
                event(119999, "beta", "503"), event(120000, "gamma", "500"))));
        InsightRuleReport report = report(service, "rule");
        assertEquals(5, report.aggregate());
        assertEquals(3, report.unique());
        assertEquals(List.of(2.0, 2.0, 1.0), report.contributors().stream()
                .map(InsightRuleReport.Contributor::aggregate).toList());
        assertEquals(List.of(1.0, 3.0, 1.0), report.metrics().stream()
                .map(point -> point.values().get("SampleCount")).toList());
        assertEquals(Map.of("UniqueContributors", 2.0, "MaxContributorValue", 2.0, "SampleCount", 3.0,
                "Sum", 3.0, "Minimum", 1.0, "Maximum", 1.0, "Average", 1.0), report.metrics().get(1).values());
        InsightRuleReport maximum = service.report("rule", BASE, BASE.plusSeconds(180), 60, 2,
                "Maximum", METRICS, REGION);
        assertEquals(5, maximum.aggregate());
        assertEquals(3, maximum.unique());
        assertEquals(List.of(List.of("beta"), List.of("alpha")),
                maximum.contributors().stream().map(InsightRuleReport.Contributor::keys).toList());
        assertEquals(List.of(2.0, 1.0), maximum.contributors().stream()
                .map(InsightRuleReport.Contributor::aggregate).toList());
        assertEquals(report.metrics(), maximum.metrics());
        InsightRuleReport twoMinutes = service.report("rule", BASE.plusSeconds(60), BASE.plusSeconds(180), 120,
                100, "Sum", METRICS, REGION);
        assertEquals(4, twoMinutes.aggregate());
        assertEquals(List.of(BASE.plusSeconds(60).toEpochMilli()), twoMinutes.metrics().stream()
                .map(InsightRuleReport.MetricPoint::timestamp).toList());
        assertEquals(4.0, twoMinutes.metrics().getFirst().values().get("SampleCount"));
        assertEquals(Set.of(BASE.plusSeconds(60).toEpochMilli(), BASE.plusSeconds(120).toEpochMilli()),
                Set.copyOf(twoMinutes.contributors().stream().flatMap(contributor -> contributor.points().stream())
                        .map(InsightRuleReport.Point::timestamp).toList()));
        assertEquals(3, service.report("rule", BASE.plusSeconds(60), BASE.plusSeconds(120), 60,
                100, "Sum", METRICS, REGION).aggregate());
        assertEquals(4, service.report("rule", BASE.plusMillis(60001), BASE.plusMillis(120001), 60,
                100, "Sum", METRICS, REGION).aggregate());
        assertThrows(UnsupportedOperationException.class, () -> report.contributors().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.metrics().getFirst().values().clear());
    }

    @Test
    void stateAndDefinitionChangesPreservePriorCountsWithoutReplayingHistoricalLogs() {
        CloudWatchInsightRulesService service = service(new InMemoryStorage<>(), ACCOUNT);
        service.put("rule", definition("5"), "DISABLED", false, Map.of("team", "first"), REGION);
        service.ingest(batch(ACCOUNT, REGION, GROUP, List.of(event(1000, "disabled", "500"))));
        assertEquals(0, report(service, "rule").aggregate());
        InsightRule disabled = service.put("rule", definition("5"), null, false, Map.of("team", "ignored"), REGION);
        assertEquals("DISABLED", disabled.state());
        assertEquals(Map.of("team", "first"), disabled.tags());
        assertEquals(0, report(service, "rule").aggregate());
        assertEquals("RULE_NAME", service.change(List.of("rule", "missing"), "EnableInsightRules", REGION)
                .getFirst().code());
        service.ingest(batch(ACCOUNT, REGION, GROUP, List.of(event(2000, "before", "500"))));
        InsightRuleReport before = report(service, "rule");
        service.put("rule", definition("4"), null, false, Map.of("team", "ignored"), REGION);
        assertEquals(before, report(service, "rule"));
        service.ingest(batch(ACCOUNT, REGION, GROUP,
                List.of(event(3000, "after", "400"), event(4000, "old-filter", "500"))));
        assertEquals(2, report(service, "rule").aggregate());
        service.change(List.of("rule"), "DisableInsightRules", REGION);
        service.ingest(batch(ACCOUNT, REGION, GROUP, List.of(event(5000, "disabled-again", "400"))));
        assertEquals(2, report(service, "rule").aggregate());
        service.change(List.of("rule"), "EnableInsightRules", REGION);
        service.ingest(batch(ACCOUNT, REGION, GROUP, List.of(event(6000, "reenabled", "400"))));
        assertEquals(List.of(List.of("after"), List.of("before"), List.of("reenabled")),
                report(service, "rule").contributors().stream().map(InsightRuleReport.Contributor::keys).toList());
        assertEquals(Map.of("team", "first"), service.listTags(service.arn("rule", REGION), REGION));
        service.change(List.of("rule"), "DeleteInsightRules", REGION);
        assertEquals("RULE_NAME: rule", assertThrows(AwsException.class, () -> report(service, "rule")).getMessage());
        service.put("rule", definition("4"), null, false, Map.of(), REGION);
        assertEquals(0, report(service, "rule").aggregate());
    }

    @Test
    void actualPersistentReloadRetainsAccountRegionIsolationAndHistory() {
        Path path = temporary.resolve("rules.json");
        PersistentStorage<String, InsightRule> backend = new PersistentStorage<>(path, TYPE);
        CloudWatchInsightRulesService first = service(backend, ACCOUNT);
        CloudWatchInsightRulesService other = service(backend, OTHER);
        first.put("rule", definition("5"), null, false, Map.of(), REGION);
        first.put("rule", definition("5"), null, false, Map.of(), "eu-west-1");
        other.put("rule", definition("5"), null, false, Map.of(), REGION);
        first.ingest(batch(OTHER, REGION, GROUP, List.of(event(1000, "other-account", "500"))));
        first.ingest(batch(ACCOUNT, "eu-west-1", GROUP, List.of(event(2000, "other-region", "500"))));
        first.ingest(batch(ACCOUNT, REGION, "/example/unselected", List.of(event(3000, "wrong-group", "500"))));
        assertEquals(0, report(first, "rule").aggregate());
        Map<String, InsightRule> before = snapshot(backend);
        PersistentStorage<String, InsightRule> loaded = new PersistentStorage<>(path, TYPE);
        loaded.load();
        assertEquals(before, snapshot(loaded));
        assertEquals(List.of("other-account"), report(service(loaded, OTHER), "rule")
                .contributors().getFirst().keys());
        assertEquals(List.of("other-region"), service(loaded, ACCOUNT).report("rule", BASE,
                BASE.plusSeconds(180), 60, 100, "Sum", METRICS, "eu-west-1").contributors().getFirst().keys());
    }

    @Test
    void failedRealAtomicCountCommitLeavesEveryRuleAndPersistedByteUnchanged() throws Exception {
        Path path = temporary.resolve("rules.json");
        PersistentStorage<String, InsightRule> backend = new PersistentStorage<>(path, TYPE);
        CloudWatchInsightRulesService service = service(backend, ACCOUNT);
        for (String name : List.of("one", "two")) {
            service.put(name, definition("5"), null, false, Map.of(), REGION);
        }
        service.ingest(batch(ACCOUNT, REGION, GROUP, List.of(event(1000, "first", "500"))));
        Map<String, InsightRule> before = snapshot(backend);
        byte[] bytes = Files.readAllBytes(path);
        Files.delete(path);
        Files.createDirectory(path);
        Path blocker = path.resolve("owned-blocker");
        Files.writeString(blocker, "unchanged");
        assertThrows(UncheckedIOException.class,
                () -> service.ingest(batch(ACCOUNT, REGION, GROUP, List.of(event(2000, "failed", "500")))));
        assertEquals(before, snapshot(backend));
        assertEquals("unchanged", Files.readString(blocker));
        Files.delete(blocker);
        Files.delete(path);
        Files.write(path, bytes);
        PersistentStorage<String, InsightRule> loaded = new PersistentStorage<>(path, TYPE);
        loaded.load();
        assertEquals(before, snapshot(loaded));
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    @Test
    void stackRollbackRestoresMetadataWithoutDiscardingCommittedConcurrentHistory() {
        CloudWatchInsightRulesService service = service(new InMemoryStorage<>(), ACCOUNT);
        InsightRule original = service.putOwned("rule", definition("5"), "ENABLED", false, Map.of(),
                Set.of(), "stack-owner", false, ignored -> {}, REGION);
        service.putOwned("rule", definition("4"), "ENABLED", false, Map.of(), Set.of(),
                "stack-owner", true, ignored -> {}, REGION);
        service.ingest(batch(ACCOUNT, REGION, GROUP, List.of(event(1000, "during-update", "400"))));
        service.restoreOwned("rule", original, "stack-owner", REGION);
        assertEquals(definition("5"), service.describe(null, null, REGION).items().getFirst().definition());
        assertEquals(1, report(service, "rule").aggregate());
        service.ingest(batch(ACCOUNT, REGION, GROUP, List.of(event(2000, "after-rollback", "500"))));
        assertEquals(2, report(service, "rule").aggregate());
    }

    @Test
    void rejectedReportAndKeyChangesLeaveCompleteMetadataAndHistoryUntouched() {
        InMemoryStorage<String, InsightRule> backend = new InMemoryStorage<>();
        CloudWatchInsightRulesService service = service(backend, ACCOUNT);
        service.put("rule", definition("5"), null, false, Map.of(), REGION);
        service.ingest(batch(ACCOUNT, REGION, GROUP, List.of(event(1000, "original", "500"))));
        Map<String, InsightRule> before = snapshot(backend);
        for (int period : List.of(1, 59, 61, 86460)) {
            assertThrows(AwsException.class, () -> service.report("rule", BASE, BASE.plusSeconds(180),
                    period, 100, "Sum", METRICS, REGION));
        }
        assertThrows(AwsException.class, () -> service.report("rule", BASE.plusMillis(60001),
                BASE.plusMillis(119999), 60, 100, "Sum", METRICS, REGION));
        assertThrows(AwsException.class, () -> service.put("rule",
                definition("5").replace("$.client", "$.other"), null, false, Map.of(), REGION));
        assertEquals(before, snapshot(backend));
    }

    @Test
    void concurrentBatchesCountOnceEachAndResetCannotResurrectHistory() throws Exception {
        InMemoryStorage<String, InsightRule> backend = new InMemoryStorage<>();
        CloudWatchInsightRulesService service = service(backend, ACCOUNT);
        service.put("rule", definition("5"), null, false, Map.of(), REGION);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            tasks.add(() -> {
                service.ingest(batch(ACCOUNT, REGION, GROUP, List.of(event(1000, "same", "500"))));
                return null;
            });
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            for (Future<Void> result : executor.invokeAll(tasks)) {
                result.get();
            }
        }
        assertEquals(20, report(service, "rule").aggregate());
        service.beforeReset();
        backend.clear();
        service.ingest(batch(ACCOUNT, REGION, GROUP, List.of(event(2000, "paused", "500"))));
        service.clear();
        service.afterReset();
        assertTrue(backend.keys().isEmpty());
        service.put("rule", definition("5"), null, false, Map.of(), REGION);
        assertEquals(0, report(service, "rule").aggregate());
    }

    private static String definition(String prefix) {
        return "{\"Schema\":{\"Name\":\"CloudWatchLogRule\",\"Version\":1},\"LogGroupNames\":[\"" + GROUP
                + "\"],\"LogFormat\":\"JSON\",\"Contribution\":{\"Keys\":[\"$.client\"],"
                + "\"Filters\":[{\"Match\":\"$.status\",\"StartsWith\":[\"" + prefix
                + "\"]}]},\"AggregateOn\":\"Count\"}";
    }

    private static LogEvent event(long offset, String key, String status) {
        LogEvent event = new LogEvent();
        event.setTimestamp(BASE.toEpochMilli() + offset);
        event.setMessage("{\"client\":\"" + key + "\",\"status\":\"" + status + "\"}");
        return event;
    }

    private static LogEventsIngested batch(String account, String region, String group, List<LogEvent> events) {
        return new LogEventsIngested(account, region, group, "events", events);
    }

    private static InsightRuleReport report(CloudWatchInsightRulesService service, String name) {
        return service.report(name, BASE, BASE.plusSeconds(180), 60, 100, "Sum", METRICS, REGION);
    }

    private static CloudWatchInsightRulesService service(StorageBackend<String, InsightRule> backend, String account) {
        return new CloudWatchInsightRulesService(new AccountAwareStorageBackend<>(backend, null, account),
                new RegionResolver(REGION, account), () -> BASE.plusSeconds(180).toEpochMilli());
    }

    private static Map<String, InsightRule> snapshot(StorageBackend<String, InsightRule> backend) {
        Map<String, InsightRule> result = new HashMap<>();
        backend.keys().forEach(key -> result.put(key, backend.get(key).orElseThrow()));
        return result;
    }
}
