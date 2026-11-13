package io.github.hectorvent.floci.services.amazonmq;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.amazonmq.model.ConfigurationState;
import io.github.hectorvent.floci.services.amazonmq.model.ConfigurationState.Configuration;
import io.github.hectorvent.floci.services.amazonmq.model.ConfigurationState.Revision;
import io.github.hectorvent.floci.services.amazonmq.model.ConfigurationState.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmazonMqConfigurationServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
    private AccountAwareStorageBackend<ConfigurationState> storage;
    private AmazonMqConfigurationService service;
    private AtomicInteger ids;

    @BeforeEach
    void setup() {
        storage = AccountAwareStorageBackend.inMemory(ACCOUNT);
        ids = new AtomicInteger();
        service = service("us-east-1", ACCOUNT);
    }

    @Test
    void authorizationSeesExactCandidateBeforeAnyPublicationAndDenialLeavesNoRow() {
        Map<String, String> supplied = new HashMap<>(Map.of("team", "audit"));
        AmazonMqConfigurationService.Create request = request("tagged", supplied);
        supplied.put("team", "changed");
        AtomicReference<String> candidate = new AtomicReference<>();
        AwsException denied = assertThrows(AwsException.class, () -> service.create(request, arn -> {
            candidate.set(arn);
            assertTrue(storage.isEmpty());
            throw new AwsException("AccessDeniedException", "denied", 403);
        }));
        assertEquals(403, denied.getHttpStatus());
        assertEquals("arn:aws:mq:us-east-1:" + ACCOUNT + ":configuration:" + id(1), candidate.get());
        assertTrue(storage.isEmpty());
        Configuration created = service.create(request, arn -> assertEquals(id(2), arn.substring(arn.lastIndexOf(':') + 1)));
        assertEquals(Map.of("team", "audit"), created.tags());
        assertThrows(UnsupportedOperationException.class, () -> created.tags().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> created.revisions().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.tagsForAuthorization(created.id()).clear());
    }

    @Test
    void malformedCreateOrUpdateCannotPublishOrAdvance() {
        assertError(400, () -> service.create(request("has space", Map.of()), null));
        assertTrue(storage.isEmpty());
        Configuration current = create("accepted");
        for (String invalid : List.of("", "not!base64", " ", "a")) {
            assertError(400, () -> service.update(current.id(), invalid, "bad"));
            assertEquals(current, service.describe(current.id()));
        }
        assertError(400, () -> service.update(current.id(), encode("valid"), "x".repeat(1025)));
        assertEquals(current, service.describe(current.id()));
    }

    @Test
    void defaultVersionAndCanonicalEnumsAreStoredWithoutEngineWork() {
        Configuration created = service.create(new AmazonMqConfigurationService.Create("metadata", "RABBITMQ",
                null, null, Map.of()), null);
        assertEquals("3.13", created.engineVersion());
        assertEquals("RABBITMQ", created.engineType());
        assertEquals("SIMPLE", created.authenticationStrategy());
        assertNull(created.latest().data());
        assertError(400, () -> service.create(new AmazonMqConfigurationService.Create("lower", "RabbitMQ",
                "3.13", null, Map.of()), null));
        assertError(400, () -> service.create(new AmazonMqConfigurationService.Create("other", "RABBITMQ",
                "3.13", "LDAP", Map.of()), null));
        assertEquals(List.of(created), service.list(null, null).items());
    }

    @Test
    void immutableLineageAndDeletedSiblingRemainIndependent() {
        Configuration a = create("same");
        Configuration b = create("same");
        assertNotEquals(a.id(), b.id());
        Revision first = service.revision(a.id(), "1");
        Configuration updated = service.update(a.id(), " " + encode("heartbeat = 120\n") + " ", "second");
        assertEquals(a.created(), updated.created());
        assertEquals(first, service.revision(a.id(), "1"));
        assertEquals(encode("heartbeat = 120\n"), updated.latest().data());
        service.delete(a.id());
        assertError(404, () -> service.describe(a.id()));
        assertError(409, () -> service.update(a.id(), encode("value"), "refused"));
        assertEquals(b, service.describe(b.id()));
        assertTrue(service.tagsForAuthorization(a.id()).isEmpty());
    }

    @Test
    void pageDefaultAndBoundsAndResumeAfterDeletedCursorAreDefined() {
        for (int index = 0; index < 23; index++) {
            create("page-" + index);
        }
        AmazonMqConfigurationService.Page<Configuration> first = service.list(null, null);
        assertEquals(20, first.items().size());
        assertNotNull(first.nextToken());
        String removed = first.items().getLast().id();
        service.delete(removed);
        assertEquals(List.of(id(21), id(22), id(23)), service.list(null, first.nextToken()).items().stream()
                .map(Configuration::id).toList());
        assertError(400, () -> service.list(4, null));
        assertError(400, () -> service.list(101, null));
        assertError(400, () -> service.list(5, first.nextToken()));
        assertError(400, () -> service.list(null, first.nextToken() + "x"));
    }

    @Test
    void revisionMarkersBindParentOperationPageSizeAndRestartSecret() {
        Configuration a = create("a");
        Configuration b = create("b");
        for (int number = 2; number <= 8; number++) {
            service.update(a.id(), encode("v" + number), "v" + number);
        }
        AmazonMqConfigurationService.Page<Revision> first = service.listRevisions(a.id(), 5, null);
        assertEquals(List.of(1, 2, 3, 4, 5), first.items().stream().map(Revision::revision).toList());
        assertEquals(List.of(6, 7, 8), service.listRevisions(a.id(), 5, first.nextToken()).items().stream()
                .map(Revision::revision).toList());
        assertError(400, () -> service.listRevisions(b.id(), 5, first.nextToken()));
        assertError(400, () -> service.list(5, first.nextToken()));
        assertError(400, () -> service.listRevisions(a.id(), 6, first.nextToken()));
        AmazonMqConfigurationService reopened = service("us-east-1", ACCOUNT);
        assertError(400, () -> reopened.listRevisions(a.id(), 5, first.nextToken()));
        assertEquals(service.describe(a.id()), reopened.describe(a.id()));
    }

    @Test
    void accountRegionAndPartitionScopesCannotSeeEachOthersRowsOrTokens() {
        for (int index = 0; index < 6; index++) {
            create("scope-" + index);
        }
        String marker = service.list(5, null).nextToken();
        for (AmazonMqConfigurationService other : List.of(service("us-east-1", "111122223333"),
                service("us-west-2", ACCOUNT), service("cn-north-1", ACCOUNT))) {
            assertTrue(other.list(5, null).items().isEmpty());
            assertError(404, () -> other.describe(id(1)));
            assertError(400, () -> other.list(5, marker));
        }
        Configuration china = service("cn-north-1", ACCOUNT).create(request("china", Map.of()), null);
        assertTrue(china.arn().startsWith("arn:aws-cn:mq:cn-north-1:" + ACCOUNT + ":configuration:"));
        assertEquals(6, service.list(null, null).items().size());
    }

    @Test
    void malformedEmbeddedParentOrIdentityFailsClosedWithoutRepairingStorage() {
        Configuration a = create("valid");
        Scope scope = service.scope();
        ConfigurationState corrupt = new ConfigurationState(new Scope(scope.partition(), scope.accountId(), "us-west-2"),
                Map.of(a.id(), a), Map.of());
        storage.putAllAtomicallyForAccount(ACCOUNT, Map.of(scope.key(), corrupt));
        assertError(500, () -> service.describe(a.id()));
        assertError(500, () -> service.tagsForAuthorization(a.id()));
        assertError(500, () -> service.update(a.id(), encode("x"), "x"));
        assertEquals(corrupt, storage.getForAccount(ACCOUNT, scope.key()).orElseThrow());
        ConfigurationState wrongKey = new ConfigurationState(scope, Map.of(id(99), a), Map.of());
        storage.putAllAtomicallyForAccount(ACCOUNT, Map.of(scope.key(), wrongKey));
        assertError(500, () -> service.list(null, null));
        assertEquals(wrongKey, storage.getForAccount(ACCOUNT, scope.key()).orElseThrow());
    }

    @Test
    void readOnlyAuthorizationDoesNotMigrateLegacyUnprefixedState() {
        InMemoryStorage<String, ConfigurationState> inner = new InMemoryStorage<>();
        Scope scope = service.scope();
        inner.put(scope.key(), new ConfigurationState(scope, Map.of(), Map.of()));
        AccountAwareStorageBackend<ConfigurationState> legacy = new AccountAwareStorageBackend<>(inner, null, ACCOUNT);
        AmazonMqConfigurationService isolated = new AmazonMqConfigurationService(legacy,
                new RegionResolver("us-east-1", ACCOUNT), CLOCK, () -> id(42));
        assertTrue(isolated.tagsForAuthorization(id(42)).isEmpty());
        assertEquals(Set.of(scope.key()), inner.keys());
    }

    @Test
    void concurrentAppendsReceiveDistinctContiguousRevisions() throws Exception {
        Configuration a = create("concurrent");
        try (ExecutorService workers = Executors.newFixedThreadPool(4)) {
            List<CompletableFuture<Configuration>> jobs = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                int value = index;
                jobs.add(CompletableFuture.supplyAsync(() -> service.update(a.id(), encode("v" + value), "v" + value),
                        workers));
            }
            CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            assertEquals(12, jobs.stream().map(job -> job.join().latest().revision()).distinct().count());
        }
        assertEquals(13, service.describe(a.id()).revisions().size());
        assertEquals(12, service.describe(a.id()).revisions().stream().filter(row -> row.data() != null)
                .map(Revision::data).distinct().count());
    }

    @Test
    void resetWaitsForAdmittedCommitThenClosesAllAdmissionUntilStorageClear() throws Exception {
        CountDownLatch authorizing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch resetAttempted = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Configuration> writing = CompletableFuture.supplyAsync(() -> service.create(
                    request("admitted", Map.of("x", "y")), arn -> {
                        authorizing.countDown();
                        await(release);
                    }), workers);
            assertTrue(authorizing.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> resetting = CompletableFuture.runAsync(() -> {
                resetAttempted.countDown();
                service.beforeReset();
            }, workers);
            assertTrue(resetAttempted.await(5, TimeUnit.SECONDS));
            assertFalse(resetting.isDone());
            release.countDown();
            Configuration created = writing.get(5, TimeUnit.SECONDS);
            resetting.get(5, TimeUnit.SECONDS);
            assertError(500, () -> service.describe(created.id()));
            assertError(500, () -> service.create(request("closed", Map.of()), null));
            assertEquals(1, storage.getForAccount(ACCOUNT, service.scope().key()).orElseThrow().configurations().size());
            storage.clear();
            service.clear();
            service.afterReset();
            assertTrue(service.list(null, null).items().isEmpty());
        } finally {
            release.countDown();
            service.afterReset();
        }
    }

    @Test
    void abortedResetReopensAdmissionAndSuccessfulClearInvalidatesTokens() {
        for (int index = 0; index < 6; index++) {
            create("reset-" + index);
        }
        String marker = service.list(5, null).nextToken();
        service.beforeReset();
        assertError(500, () -> service.list(null, null));
        service.afterReset();
        assertEquals(1, service.list(5, marker).items().size());
        service.afterReset();
        service.beforeReset();
        storage.clear();
        service.clear();
        service.afterReset();
        assertError(400, () -> service.list(5, marker));
        assertTrue(service.list(null, null).items().isEmpty());
    }

    @Test
    void configuredLocalCapacityRefusalsLeaveLastAcceptedSnapshot() {
        Configuration a = create("limit");
        String document = encode("x");
        for (int revision = 2; revision <= 300; revision++) {
            service.update(a.id(), document, "");
        }
        Configuration full = service.describe(a.id());
        assertError(429, () -> service.update(a.id(), document, "rejected"));
        assertEquals(full, service.describe(a.id()));
        assertError(400, () -> service.update(a.id(), encode("x".repeat(65537)), "oversize"));
        for (int count = 1; count < 50; count++) {
            create("capacity-" + count);
        }
        assertError(429, () -> create("excess"));
        assertEquals(50, service.list(100, null).items().size());
    }

    @Test
    void concurrentDeleteAndAppendCannotResurrectIdentityOrChangeSibling() throws Exception {
        Configuration a = create("race-a");
        Configuration b = create("race-b");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Integer> append = CompletableFuture.supplyAsync(() -> {
                ready.countDown();
                await(start);
                try {
                    service.update(a.id(), encode("new"), "new");
                    return 200;
                } catch (AwsException retired) {
                    return retired.getHttpStatus();
                }
            }, workers);
            CompletableFuture<Void> deletion = CompletableFuture.runAsync(() -> {
                ready.countDown();
                await(start);
                service.delete(a.id());
            }, workers);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int result = append.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
            assertTrue(result == 200 || result == 409);
        } finally {
            start.countDown();
        }
        assertError(404, () -> service.describe(a.id()));
        assertError(409, () -> service.update(a.id(), encode("later"), "later"));
        assertEquals(List.of(b), service.list(null, null).items());
    }

    @Test
    void retiredIdentityMemoryIsBoundedAndOnlyEvictedIdentityLosesConflictStatus() {
        for (int index = 0; index < 1001; index++) {
            service.delete(create("retired-" + index).id());
        }
        assertError(404, () -> service.update(id(1), encode("x"), "evicted"));
        assertError(409, () -> service.update(id(2), encode("x"), "retained"));
        assertError(409, () -> service.update(id(1001), encode("x"), "retained-latest"));
        assertEquals(1000, storage.getForAccount(ACCOUNT, service.scope().key()).orElseThrow().retired().size());
        assertTrue(service.list(null, null).items().isEmpty());
    }

    private AmazonMqConfigurationService service(String region, String account) {
        return new AmazonMqConfigurationService(storage, new RegionResolver(region, account), CLOCK,
                () -> id(ids.incrementAndGet()));
    }

    private Configuration create(String name) {
        return service.create(request(name, Map.of()), null);
    }

    private static AmazonMqConfigurationService.Create request(String name, Map<String, String> tags) {
        return new AmazonMqConfigurationService.Create(name, "RABBITMQ", "3.13", null, tags);
    }

    private static String id(int value) {
        return "c-" + new UUID(0, value);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertError(int status, Runnable operation) {
        assertEquals(status, assertThrows(AwsException.class, operation::run).getHttpStatus());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for controlled admission");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
