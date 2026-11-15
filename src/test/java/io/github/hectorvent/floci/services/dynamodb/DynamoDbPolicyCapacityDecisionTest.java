package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbRegistrationServiceTest.Fixture;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbRegistrationServiceTest.TableStore;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbRegistrationServiceTest.Target;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService.ScalableCapacityDecision;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** The decision entrypoint uses the actual native table lock and selected capacity persistence. */
@Timeout(30)
class DynamoDbPolicyCapacityDecisionTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    @ParameterizedTest
    @EnumSource(Target.class)
    void selectedFieldAndPeakChangeWhileCompleteSiblingMetadataRemains(Target target) {
        TableStore store = new TableStore();
        Fixture fixture = new Fixture(store);
        target.rawWarm(fixture.table, 9);
        fixture.table.setTags(Map.of("original", "retained"));
        TableDefinition expected = JSON.convertValue(fixture.table, TableDefinition.class);
        if (target.read) {
            target.throughput(expected).setReadCapacityUnits(17);
        } else {
            target.throughput(expected).setWriteCapacityUnits(17);
        }
        target.rawWarm(expected, 17);
        AtomicInteger calls = new AtomicInteger();
        ScalableCapacityDecision result = decide(fixture, target, current -> {
            calls.incrementAndGet();
            assertEquals(3, current);
            return 17;
        });
        assertEquals(new ScalableCapacityDecision(true, 3, 17), result);
        assertEquals(1, calls.get());
        assertEquals(JSON.valueToTree(expected), JSON.valueToTree(fixture.table));
        assertSame(fixture.table, store.get(REGION + "::owned").orElseThrow());
        assertEquals(17, target.rawWarm(fixture.table));
        assertEquals(new ScalableCapacityDecision(true, 17, 2), decide(fixture, target, current -> 2));
        assertEquals(17, target.rawWarm(fixture.table), "A capacity decrease retains the observed peak");
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void noOpAndInvalidDecisionNeverPersist(Target target) {
        TableStore store = new TableStore();
        Fixture fixture = new Fixture(store);
        JsonNode before = JSON.valueToTree(fixture.table);
        AtomicInteger puts = new AtomicInteger();
        store.beforePut = ignored -> puts.incrementAndGet();
        assertEquals(new ScalableCapacityDecision(true, 3, 3), decide(fixture, target, value -> value));
        for (long invalid : List.of(0L, -1L, Long.MIN_VALUE)) {
            AwsException refused = assertThrows(AwsException.class, () -> decide(fixture, target, value -> invalid));
            assertEquals("ValidationException", refused.getErrorCode());
            assertEquals(400, refused.getHttpStatus());
        }
        assertEquals(0, puts.get());
        assertEquals(before, JSON.valueToTree(fixture.table));
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void callbackFailureDoesNotPersistOrChangeAnyField(Target target) {
        TableStore store = new TableStore();
        Fixture fixture = new Fixture(store);
        JsonNode before = JSON.valueToTree(fixture.table);
        AtomicInteger puts = new AtomicInteger();
        store.beforePut = ignored -> puts.incrementAndGet();
        IllegalStateException problem = new IllegalStateException("pure decision refused");
        assertSame(problem, assertThrows(IllegalStateException.class, () -> decide(fixture, target, value -> {
            throw problem;
        })));
        assertEquals(0, puts.get());
        assertEquals(before, JSON.valueToTree(fixture.table));
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void inactiveNullableThroughputSkipsDecisionBeforeDereferencingCapacity(Target target) {
        for (String state : List.of("PAY_PER_REQUEST", "CREATING", "DELETING")) {
            TableStore store = new TableStore();
            Fixture fixture = new Fixture(store);
            if ("PAY_PER_REQUEST".equals(state)) {
                fixture.table.setBillingMode(state);
            } else {
                fixture.table.setTableStatus(state);
            }
            if (target.index) {
                target.index(fixture.table).setProvisionedThroughput(null);
            } else {
                fixture.table.setProvisionedThroughput(null);
            }
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger puts = new AtomicInteger();
            store.beforePut = ignored -> puts.incrementAndGet();
            assertEquals(new ScalableCapacityDecision(false, 0, 0), decide(fixture, target, value -> {
                calls.incrementAndGet();
                return 17;
            }));
            assertEquals(0, calls.get());
            assertEquals(0, puts.get());
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void observedPersistenceFailureRestoresOnlySelectedDecodedValues(Target target) {
        for (boolean failRestoration : List.of(false, true)) {
            TableStore store = new TableStore();
            Fixture fixture = new Fixture(store);
            target.rawWarm(fixture.table, 0);
            IllegalStateException first = new IllegalStateException("selected capacity write");
            IllegalStateException second = new IllegalStateException("compensation write");
            AtomicInteger puts = new AtomicInteger();
            store.beforePut = value -> {
                assertTrue(Thread.holdsLock(fixture.service.tableControlLock("owned", REGION)));
                int attempt = puts.incrementAndGet();
                value.setTags(Map.of("concurrent", "preserved"));
                if (attempt == 1) {
                    assertEquals(17, target.capacity(value));
                    throw first;
                }
                if (failRestoration) {
                    throw second;
                }
            };
            assertSame(first, assertThrows(IllegalStateException.class, () -> decide(fixture, target, value -> 17)));
            assertEquals(2, puts.get());
            assertEquals(3, target.capacity(fixture.table));
            assertEquals(0, target.rawWarm(fixture.table));
            assertEquals(Map.of("concurrent", "preserved"), fixture.table.getTags());
            assertEquals(failRestoration ? List.of(second) : List.of(), List.of(first.getSuppressed()));
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void queuedDecisionReadsTheCapacityCommittedUnderTheSameNativeLock(Target target) throws Exception {
        Fixture fixture = new Fixture(new TableStore());
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondSubmitted = new CountDownLatch(1);
        AtomicLong secondRead = new AtomicLong(-1);
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<ScalableCapacityDecision> first = workers.submit(() -> decide(fixture, target, current -> {
                firstThread.set(Thread.currentThread());
                assertEquals(3, current);
                firstInside.countDown();
                await(releaseFirst);
                return 17;
            }));
            try {
                assertTrue(firstInside.await(5, TimeUnit.SECONDS));
                Future<ScalableCapacityDecision> second = workers.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    secondSubmitted.countDown();
                    return decide(fixture, target, current -> {
                        secondRead.set(current);
                        return current + 1;
                    });
                });
                assertTrue(secondSubmitted.await(5, TimeUnit.SECONDS));
                Awaitility.await().pollInSameThread().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(secondThread.get().threadId());
                    assertNotNull(info);
                    assertEquals(Thread.State.BLOCKED, info.getThreadState());
                    assertEquals(firstThread.get().threadId(), info.getLockOwnerId());
                    assertFalse(second.isDone());
                });
                releaseFirst.countDown();
                assertEquals(new ScalableCapacityDecision(true, 3, 17), first.get(5, TimeUnit.SECONDS));
                assertEquals(new ScalableCapacityDecision(true, 17, 18), second.get(5, TimeUnit.SECONDS));
                assertEquals(17, secondRead.get());
                assertEquals(18, target.capacity(fixture.table));
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    @Test
    void missingAndMismatchedResourcesRefuseBeforeDecision() {
        Fixture fixture = new Fixture(new TableStore());
        AtomicInteger calls = new AtomicInteger();
        for (String resource : List.of("table/missing", "table/owned/index/selected", "owned", "table/")) {
            AwsException failure = assertThrows(AwsException.class, () -> fixture.service.decideScalableCapacity(
                    resource, Target.TABLE_READ.dimension(), REGION, value -> calls.incrementAndGet()));
            assertEquals("ValidationException", failure.getErrorCode());
        }
        assertThrows(AwsException.class, () -> fixture.service.decideScalableCapacity(
                "table/owned/index/missing", Target.INDEX_READ.dimension(), REGION, value -> calls.incrementAndGet()));
        assertEquals(0, calls.get());
    }

    private static ScalableCapacityDecision decide(Fixture fixture, Target target, LongUnaryOperator decision) {
        return fixture.service.decideScalableCapacity(target.resource(), target.dimension(), REGION, current -> {
            assertTrue(Thread.holdsLock(fixture.service.tableControlLock("owned", REGION)),
                    "The pure decision must hold the actual canonical table monitor");
            return decision.applyAsLong(current);
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
