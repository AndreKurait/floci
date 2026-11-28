package io.floci.aws.services.cloudwatch.insights;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.cloudwatch.insights.model.InsightRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchInsightRulesServiceTest {
    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "us-east-1";
    private static final String BODY = "{\"Schema\":{\"Name\":\"CloudWatchLogRule\",\"Version\":1},"
            + "\"LogGroupNames\":[\"/example/service\"],\"LogFormat\":\"JSON\","
            + "\"Contribution\":{\"Keys\":[\"$.client\"],\"Filters\":[{\"Match\":\"$.status\","
            + "\"StartsWith\":[\"5\"]}]},\"AggregateOn\":\"Count\"}";
    private static final TypeReference<Map<String, InsightRule>> TYPE = new TypeReference<>() {};

    @TempDir
    Path temporary;

    @Test
    void callerTagsReturnedRulesAndPaginationCannotMutateTheStore() {
        StorageBackend<String, InsightRule> backend = new InMemoryStorage<>();
        CloudWatchInsightRulesService service = service(backend, ACCOUNT);
        Map<String, String> tags = new HashMap<>(Map.of("team", "original"));
        InsightRule original = service.put("rule", BODY, "DISABLED", false, tags, REGION);
        tags.put("team", "caller-change");
        assertThrows(UnsupportedOperationException.class, () -> original.tags().put("team", "returned-change"));
        assertThrows(UnsupportedOperationException.class,
                () -> service.describe(null, null, REGION).items().getFirst().tags().clear());
        assertEquals(Map.of("team", "original"), service.listTags(original.arn(), REGION));
        assertEquals(original, backend.get(ACCOUNT + "/" + REGION + "::rule").orElseThrow());
    }

    @Test
    void realPersistentReloadKeepsRuleAndTagOwnershipAcrossAccountsAndRegions() {
        Path path = temporary.resolve("rules.json");
        PersistentStorage<String, InsightRule> backend = new PersistentStorage<>(path, TYPE);
        backend.load();
        CloudWatchInsightRulesService first = service(backend, ACCOUNT);
        CloudWatchInsightRulesService second = service(backend, "444455556666");
        InsightRule owned = first.putOwned("shared", BODY, "DISABLED", false, Map.of("team", "first"), Set.of(),
                "stack-owner", false, ignored -> {}, REGION);
        second.put("shared", BODY, "DISABLED", false, Map.of("team", "second"), REGION);
        first.put("shared", BODY, "DISABLED", false, Map.of("team", "other-region"), "eu-west-1");
        PersistentStorage<String, InsightRule> loaded = new PersistentStorage<>(path, TYPE);
        loaded.load();
        assertEquals(backend.keys(), loaded.keys());
        for (String key : backend.keys()) {
            assertEquals(backend.get(key), loaded.get(key));
        }
        CloudWatchInsightRulesService reloaded = service(loaded, ACCOUNT);
        assertEquals(owned, reloaded.describe(null, null, REGION).items().getFirst());
        AwsException ownership = assertThrows(AwsException.class,
                () -> reloaded.deleteOwned(owned.arn(), "different-owner", REGION));
        assertEquals("ResourceConflictException", ownership.getErrorCode());
        reloaded.deleteOwned(owned.arn(), "stack-owner", REGION);
        assertTrue(reloaded.describe(null, null, REGION).items().isEmpty());
        assertEquals("second", service(loaded, "444455556666").describe(null, null, REGION)
                .items().getFirst().tags().get("team"));
        assertEquals("other-region", reloaded.describe(null, null, "eu-west-1").items().getFirst().tags().get("team"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realAtomicWriteFailureLeavesAllInMemoryValuesAndBlockedDiskStateUnchanged(boolean deleting) throws Exception {
        Path path = temporary.resolve("rules.json");
        PersistentStorage<String, InsightRule> backend = new PersistentStorage<>(path, TYPE);
        CloudWatchInsightRulesService service = service(backend, ACCOUNT);
        InsightRule original = service.put("rule", BODY, "DISABLED", false, Map.of("team", "keep"), REGION);
        byte[] before = Files.readAllBytes(path);
        Map<String, InsightRule> beforeStore = snapshot(backend);
        Files.delete(path);
        Files.createDirectory(path);
        Path sentinel = path.resolve("owned-blocker");
        Files.writeString(sentinel, "unchanged");
        assertThrows(UncheckedIOException.class, () -> {
            if (deleting) {
                service.change(List.of("rule"), "DeleteInsightRules", REGION);
            } else {
                service.put("rule", BODY.replace("\"5\"", "\"4\""), "DISABLED", false, Map.of(), REGION);
            }
        });
        assertEquals(beforeStore, snapshot(backend));
        assertEquals("unchanged", Files.readString(sentinel));
        assertEquals(original, service.describe(null, null, REGION).items().getFirst());
        Files.delete(sentinel);
        Files.delete(path);
        Files.write(path, before);
        PersistentStorage<String, InsightRule> loaded = new PersistentStorage<>(path, TYPE);
        loaded.load();
        assertEquals(beforeStore, snapshot(loaded));
    }

    @Test
    void concurrentStackCreatorsCannotAdoptOneAnothersRule() throws Exception {
        InMemoryStorage<String, InsightRule> backend = new InMemoryStorage<>();
        CloudWatchInsightRulesService service = service(backend, ACCOUNT);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Callable<String>> attempts = new ArrayList<>();
        for (String owner : List.of("owner-one", "owner-two")) {
            attempts.add(() -> {
                barrier.await();
                try {
                    return service.putOwned("rule", BODY, "DISABLED", false, Map.of(), Set.of(),
                            owner, false, ignored -> {}, REGION).ownerToken();
                } catch (AwsException error) {
                    assertEquals("AlreadyExistsException", error.getErrorCode());
                    return "refused";
                }
            });
        }
        List<String> outcomes = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (Future<String> result : executor.invokeAll(attempts)) {
                outcomes.add(result.get());
            }
        }
        assertEquals(1, outcomes.stream().filter("refused"::equals).count());
        assertEquals(1, backend.keys().size());
        String winner = service.describe(null, null, REGION).items().getFirst().ownerToken();
        assertTrue(outcomes.contains(winner));
    }

    @Test
    void recreatedNativeRuleCannotBeDeletedOrResurrectedByStaleStackOwnership() {
        InMemoryStorage<String, InsightRule> backend = new InMemoryStorage<>();
        CloudWatchInsightRulesService service = service(backend, ACCOUNT);
        InsightRule original = service.putOwned("rule", BODY, "DISABLED", false, Map.of("owner", "stack"),
                Set.of(), "stack-token", false, ignored -> {}, REGION);
        service.change(List.of("rule"), "DeleteInsightRules", REGION);
        InsightRule nativeRule = service.put("rule", BODY.replace("\"5\"", "\"4\""), "DISABLED", false,
                Map.of("owner", "native"), REGION);
        Map<String, InsightRule> before = snapshot(backend);
        assertThrows(AwsException.class, () -> service.deleteOwned(original.arn(), "stack-token", REGION));
        assertThrows(AwsException.class, () -> service.restoreOwned("rule", original, "stack-token", REGION));
        assertEquals(before, snapshot(backend));
        assertEquals(nativeRule, service.describe(null, null, REGION).items().getFirst());
    }

    private static CloudWatchInsightRulesService service(StorageBackend<String, InsightRule> backend, String account) {
        return new CloudWatchInsightRulesService(new AccountAwareStorageBackend<>(backend, null, account),
                new RegionResolver(REGION, account));
    }

    private static Map<String, InsightRule> snapshot(StorageBackend<String, InsightRule> backend) {
        Map<String, InsightRule> result = new HashMap<>();
        backend.keys().forEach(key -> result.put(key, backend.get(key).orElseThrow()));
        return result;
    }
}
