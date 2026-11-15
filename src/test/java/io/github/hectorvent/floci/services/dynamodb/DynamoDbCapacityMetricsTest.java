package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.ManualClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Timeout(15)
class DynamoDbCapacityMetricsTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String TABLE = "owned";
    private static final String ID = "owned-incarnation";
    private static final String INDEX = "focused";
    private static final String READ = "ConsumedReadCapacityUnits";
    private static final String WRITE = "ConsumedWriteCapacityUnits";
    private final DynamoDbService dynamo = mock(DynamoDbService.class);
    private final ManualClock clock = new ManualClock(Instant.ofEpochSecond(120));
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final ArmedStore store = new ArmedStore();
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private CloudWatchMetricsService metrics;
    private DynamoDbCapacityMetrics publisher;

    @BeforeEach
    void createActualMetricStorageWithAnExplicitOwner() {
        metrics = new CloudWatchMetricsService(new InMemoryStorageFactory(ACCOUNT).withStore("cwmetrics.json", store),
                new RegionResolver(REGION, ACCOUNT));
        publisher = new DynamoDbCapacityMetrics(dynamo, metrics, clock, scheduler, true);
        when(dynamo.currentCapacityOwner(any())).thenAnswer(call -> {
            assertFalse(Thread.holdsLock(publisher), "DynamoDB ownership reads cannot hold the publisher monitor");
            return new DynamoDbService.CapacityOwner(true, Set.of(INDEX));
        });
        when(dynamo.capacitySnapshots()).thenAnswer(call -> {
            assertFalse(Thread.holdsLock(publisher), "DynamoDB snapshots cannot hold the publisher monitor");
            return snapshots(false, ID, 3);
        });
    }

    @AfterEach
    void stopOnlyOwnedWorkers() {
        workers.shutdownNow();
        publisher.stop();
    }

    @ParameterizedTest
    @CsvSource({"false,true,native", "true,false,native", "true,true,local"})
    void disabledServicesAndTheForwardedBackendCannotPublishNativeMetrics(boolean dynamoEnabled,
                                                                         boolean metricsEnabled, String backend) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dynamodb().enabled()).thenReturn(dynamoEnabled);
        when(config.services().cloudwatchmetrics().enabled()).thenReturn(metricsEnabled);
        when(config.services().dynamodb().backend()).thenReturn(backend);
        DynamoDbService untouchedDynamo = mock(DynamoDbService.class);
        CloudWatchMetricsService untouchedMetrics = mock(CloudWatchMetricsService.class);
        DynamoDbCapacityMetrics disabled = new DynamoDbCapacityMetrics(untouchedDynamo, untouchedMetrics, config);
        try {
            disabled.start();
            disabled.record(usage(0, 7, Map.of(), Map.of(), clock.instant()));
            disabled.tick();
            verifyNoInteractions(untouchedDynamo, untouchedMetrics);
        } finally {
            disabled.stop();
        }
    }

    @Test
    void allFourContributionsUseTheExactOwnerRegionAndDimensions() {
        publisher.record(usage(2, 3, Map.of(INDEX, 0.5), Map.of(INDEX, 4.0), clock.instant()));
        assertPoint(READ, null, 120, 2, 1);
        assertPoint(WRITE, null, 120, 3, 1);
        assertPoint(READ, INDEX, 120, 0.5, 1);
        assertPoint(WRITE, INDEX, 120, 4, 1);
        assertEquals(4, store.keys().size());
        assertTrue(store.keys().stream().allMatch(key -> key.startsWith(ACCOUNT + "/" + REGION + "::AWS/DynamoDB::")));
        assertTrue(metrics.getMetricStatistics("AWS/DynamoDB", WRITE,
                List.of(new Dimension("GlobalSecondaryIndexName", INDEX)), Instant.EPOCH,
                Instant.ofEpochSecond(600), 60, List.of("Sum"), null, REGION).isEmpty());
    }

    @Test
    void discoveryDoesNotBackfillAnIncompleteMinuteAndAllFourIdlePointsAreReal() {
        clock.advance(Duration.ofMillis(500));
        publisher.tick();
        clock.advance(Duration.ofMillis(59_500));
        publisher.tick();
        assertTrue(store.keys().isEmpty(), "The minute containing first discovery was not completely observed");
        clock.advance(Duration.ofSeconds(60));
        publisher.tick();
        for (String metric : List.of(READ, WRITE)) {
            assertPoint(metric, null, 180, 0, 1);
            assertPoint(metric, INDEX, 180, 0, 1);
            assertTrue(points(metric, null, 120).stream()
                    .noneMatch(point -> point.timestamp().equals(Instant.ofEpochSecond(120))));
        }
    }

    @Test
    void aBusyMinuteHasNoAdditionalZeroSample() {
        publisher.tick();
        publisher.record(usage(2, 3, Map.of(INDEX, 0.5), Map.of(INDEX, 4.0), clock.instant()));
        clock.advance(Duration.ofSeconds(60));
        publisher.tick();
        assertPoint(READ, null, 120, 2, 1);
        assertPoint(WRITE, null, 120, 3, 1);
        assertPoint(READ, INDEX, 120, 0.5, 1);
        assertPoint(WRITE, INDEX, 120, 4, 1);
    }

    @Test
    void aLateFirstContributionReplacesTheZeroAndDistinctRequestsStillAccumulate() {
        publisher.tick();
        clock.advance(Duration.ofSeconds(60));
        publisher.tick();
        assertPoint(WRITE, null, 120, 0, 1);
        publisher.record(usage(0, 7, Map.of(), Map.of(), Instant.ofEpochSecond(125)));
        assertPoint(WRITE, null, 120, 7, 1);
        publisher.record(usage(0, 2, Map.of(), Map.of(), Instant.ofEpochSecond(126)));
        assertPoint(WRITE, null, 120, 9, 2);
        assertEquals(2, points(WRITE, null, 120).getFirst().minimum());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aStorageFailureBeforeOrAfterCommitRetriesTheExactContributionKey(boolean after) {
        store.after = after;
        store.failValue = 7;
        store.armed.set(true);
        assertDoesNotThrow(() -> publisher.record(usage(0, 7, Map.of(), Map.of(), clock.instant())));
        publisher.tick();
        publisher.tick();
        assertPoint(WRITE, null, 120, 7, 1);
        assertEquals(2, store.failedValueKeys.size());
        assertEquals(1, store.failedValueKeys.stream().distinct().count());
    }

    @Test
    void anObsoleteFailedZeroIsNeverRetriedOverTheLatePositive() {
        publisher.tick();
        store.failValue = 0;
        store.keepFailingZero = true;
        clock.advance(Duration.ofSeconds(60));
        publisher.tick();
        publisher.record(usage(0, 7, Map.of(), Map.of(), Instant.ofEpochSecond(125)));
        store.keepFailingZero = false;
        publisher.tick();
        assertPoint(WRITE, null, 120, 7, 1);
    }

    @Test
    void aNewProducerCannotOverwriteThePreviousProcessesFirstContribution() {
        publisher.record(usage(0, 7, Map.of(), Map.of(), clock.instant()));
        DynamoDbCapacityMetrics successor = new DynamoDbCapacityMetrics(dynamo, metrics, clock,
                mock(ScheduledExecutorService.class), true);
        try {
            successor.record(usage(0, 2, Map.of(), Map.of(), clock.instant()));
            assertPoint(WRITE, null, 120, 9, 2);
        } finally {
            successor.stop();
        }
    }

    @Test
    void removedIndexMembershipDropsOnlyThatIndexContribution() {
        when(dynamo.currentCapacityOwner(any())).thenReturn(new DynamoDbService.CapacityOwner(true, Set.of()));
        publisher.record(usage(0, 3, Map.of(), Map.of(INDEX, 4.0), clock.instant()));
        assertPoint(WRITE, null, 120, 3, 1);
        assertTrue(points(WRITE, INDEX, 120).isEmpty());
    }

    @Test
    void staleTableIncarnationCannotPublishOrMarkItsReplacementBusy() {
        when(dynamo.currentCapacityOwner(any())).thenReturn(new DynamoDbService.CapacityOwner(false, Set.of()));
        publisher.record(usage(0, 3, Map.of(), Map.of(INDEX, 4.0), clock.instant()));
        assertTrue(store.keys().isEmpty());
        when(dynamo.capacitySnapshots()).thenReturn(snapshots(false, "replacement", 3));
        publisher.tick();
        clock.advance(Duration.ofSeconds(60));
        publisher.tick();
        assertPoint(WRITE, null, 120, 0, 1);
    }

    @Test
    void resetDuringTheOwnershipReadRejectsThatEarlierGeneration() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(dynamo.currentCapacityOwner(any())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new DynamoDbService.CapacityOwner(true, Set.of(INDEX));
        });
        Future<?> recording = workers.submit(() -> publisher.record(usage(0, 7, Map.of(), Map.of(), clock.instant())));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            publisher.beforeReset();
            publisher.clear();
            publisher.afterReset();
        } finally {
            release.countDown();
        }
        recording.get(2, TimeUnit.SECONDS);
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void resetDuringCollectionCannotPublishAnOldSnapshot() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(dynamo.capacitySnapshots()).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return snapshots(true, ID, 3);
        });
        Future<?> ticking = workers.submit(publisher::tick);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            publisher.beforeReset();
            publisher.clear();
            publisher.afterReset();
        } finally {
            release.countDown();
        }
        ticking.get(2, TimeUnit.SECONDS);
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void gaugesFollowFiveMinuteBoundariesAndCapacityChangesWithoutDeletingOldHistory() {
        when(dynamo.capacitySnapshots()).thenReturn(snapshots(true, ID, 3));
        publisher.tick();
        assertPoint("ProvisionedReadCapacityUnits", null, 120, 3, 1);
        assertPoint("ProvisionedWriteCapacityUnits", null, 120, 7, 1);
        assertPoint("ProvisionedReadCapacityUnits", INDEX, 120, 5, 1);
        assertPoint("ProvisionedWriteCapacityUnits", INDEX, 120, 11, 1);
        clock.advance(Duration.ofSeconds(179));
        publisher.tick();
        assertTrue(points("ProvisionedReadCapacityUnits", null, 240).isEmpty());
        clock.advance(Duration.ofSeconds(1));
        publisher.tick();
        assertPoint("ProvisionedReadCapacityUnits", null, 300, 3, 1);
        clock.advance(Duration.ofSeconds(60));
        when(dynamo.capacitySnapshots()).thenReturn(snapshots(true, ID, 9));
        publisher.tick();
        assertPoint("ProvisionedReadCapacityUnits", null, 360, 9, 1);
        when(dynamo.capacitySnapshots()).thenReturn(snapshots(false, ID, 9));
        clock.advance(Duration.ofSeconds(240));
        publisher.tick();
        assertTrue(points("ProvisionedReadCapacityUnits", null, 600).isEmpty());
        assertPoint("ProvisionedReadCapacityUnits", null, 120, 3, 1);
    }

    @Test
    void scheduleAndShutdownRetainTheirActualAdmissionBoundary() {
        publisher.start();
        publisher.start();
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(1L), eq(10L), eq(TimeUnit.SECONDS));
        publisher.stop();
        publisher.record(usage(0, 7, Map.of(), Map.of(), clock.instant()));
        publisher.tick();
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void aSustainedStorageOutageRetainsOnlyTheDocumentedBoundedRetryQueue() {
        store.failEveryWrite = true;
        for (int i = 0; i < 4097; i++) {
            publisher.record(usage(0, 1, Map.of(), Map.of(), clock.instant()));
        }
        assertTrue(store.keys().isEmpty());
        store.failEveryWrite = false;
        publisher.tick();
        assertPoint(WRITE, null, 120, 4096, 4096);
    }

    private static List<DynamoDbService.CapacitySnapshot> snapshots(boolean provisioned, String id, long reads) {
        return List.of(new DynamoDbService.CapacitySnapshot(ACCOUNT, REGION, TABLE, id, null, provisioned, reads, 7),
                new DynamoDbService.CapacitySnapshot(ACCOUNT, REGION, TABLE, id, INDEX, provisioned, 5, 11));
    }

    private DynamoDbCapacityUsage usage(double reads, double writes, Map<String, Double> indexReads,
                                        Map<String, Double> indexWrites, Instant timestamp) {
        return new DynamoDbCapacityUsage(ACCOUNT, REGION, TABLE, ID, timestamp, UUID.randomUUID().toString(),
                reads, writes, indexReads, indexWrites);
    }

    private List<CloudWatchMetricsService.Datapoint> points(String metric, String index, long minute) {
        List<Dimension> dimensions = new ArrayList<>(List.of(new Dimension("TableName", TABLE)));
        if (index != null) {
            dimensions.add(new Dimension("GlobalSecondaryIndexName", index));
        }
        return metrics.getMetricStatistics("AWS/DynamoDB", metric, dimensions, Instant.ofEpochSecond(minute),
                Instant.ofEpochSecond(minute + 60), 60, List.of("Sum", "SampleCount", "Minimum", "Maximum"), null, REGION);
    }

    private void assertPoint(String metric, String index, long minute, double sum, double samples) {
        List<CloudWatchMetricsService.Datapoint> values = points(metric, index, minute);
        assertEquals(1, values.size());
        assertEquals(Instant.ofEpochSecond(minute), values.getFirst().timestamp());
        assertEquals(sum, values.getFirst().sum());
        assertEquals(samples, values.getFirst().sampleCount());
    }

    private static final class ArmedStore extends InMemoryStorage<String, MetricDatum> {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final List<String> failedValueKeys = new ArrayList<>();
        private double failValue;
        private boolean after;
        private boolean keepFailingZero;
        private boolean failEveryWrite;

        @Override
        public void put(String key, MetricDatum datum) {
            boolean selected = WRITE.equals(datum.getMetricName()) && datum.getValue() == failValue;
            if (selected) {
                failedValueKeys.add(key);
            }
            boolean fail = WRITE.equals(datum.getMetricName()) && (failEveryWrite
                    || keepFailingZero && datum.getValue() == 0 || selected && armed.compareAndSet(true, false));
            if (fail && !after) {
                throw new IllegalStateException("before metric storage");
            }
            super.put(key, datum);
            if (fail) {
                throw new IllegalStateException("after metric storage");
            }
        }
    }
}
