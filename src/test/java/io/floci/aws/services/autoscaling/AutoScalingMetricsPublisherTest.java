package io.floci.aws.services.autoscaling;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.lifecycle.EmulatorInfoController;
import io.floci.aws.services.autoscaling.model.AsgInstance;
import io.floci.aws.services.autoscaling.model.AutoScalingGroup;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.Dimension;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import io.floci.aws.testing.StorageFactories;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoScalingMetricsPublisherTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-west-2";
    private static final Instant TIME = Instant.parse("2026-11-13T00:00:00Z");
    private static final String NAMESPACE = "AWS/AutoScaling";

    @TempDir Path directory;
    private StorageFactory factory;
    private AutoScalingService service;
    private CloudWatchMetricsService metrics;
    private AutoScalingMetricsPublisher publisher;

    @BeforeEach
    void open() {
        open("memory");
    }

    @AfterEach
    void close() {
        publisher.onStop(null);
        factory.shutdownAll();
    }

    @Test
    void countsLifecycleSubstatesAndKeepsStandbyOutsideTheDocumentedTotal() {
        AutoScalingGroup group = group("states");
        group.setMinSize(2);
        group.setMaxSize(12);
        group.setDesiredCapacity(9);
        for (String state : List.of("InService", "InService", "Pending", "Pending:Wait", "Pending:Proceed",
                "Standby", "Terminating", "Terminating:Wait", "Terminating:Proceed", "EnteringStandby",
                "Detached", "Terminated", "Warmed:Running", "Retained", "Unrecognized")) {
            AsgInstance instance = new AsgInstance();
            instance.setLifecycleState(state);
            group.getInstances().add(instance);
        }
        group.getInstances().add(new AsgInstance());
        service.enableMetricsCollection(REGION, "states", null, "1Minute");

        publisher.tick(TIME.plusSeconds(47));

        assertEquals(Map.of("GroupMinSize", 2.0, "GroupMaxSize", 12.0, "GroupDesiredCapacity", 9.0,
                "GroupInServiceInstances", 2.0, "GroupPendingInstances", 3.0, "GroupStandbyInstances", 1.0,
                "GroupTerminatingInstances", 3.0, "GroupTotalInstances", 8.0), values("states"));
        for (MetricDatum datum : rows("states")) {
            assertDatum(datum, "states", TIME);
        }
    }

    @Test
    void selectedAndAdditiveMetricsPublishZeroValuesWithoutInventingCapacityOrWarmPoolSamples() {
        group("selected");
        service.enableMetricsCollection(REGION, "selected",
                List.of("GroupMinSize", "GroupInServiceCapacity", "WarmPoolDesiredCapacity"), "1Minute");
        publisher.tick(TIME);
        assertEquals(Map.of("GroupMinSize", 0.0), values("selected"));
        service.enableMetricsCollection(REGION, "selected", List.of("GroupDesiredCapacity"), "1Minute");
        publisher.tick(TIME.plusSeconds(20));
        assertEquals(Map.of("GroupMinSize", 0.0, "GroupDesiredCapacity", 0.0), values("selected"));
    }

    @Test
    void repeatedTicksKeepOneSnapshotAndDoNotBackfillMissedMinutes() {
        AutoScalingGroup group = group("repeat");
        service.enableMetricsCollection(REGION, "repeat", List.of("GroupDesiredCapacity"), "1Minute");
        publisher.tick(TIME);
        group.setDesiredCapacity(3);
        publisher.tick(TIME.plusSeconds(59));
        assertEquals(List.of(0.0), rows("repeat").stream().map(MetricDatum::getValue).toList());
        publisher.tick(TIME.plusSeconds(240));
        assertEquals(Set.of(TIME.getEpochSecond(), TIME.plusSeconds(240).getEpochSecond()),
                rows("repeat").stream().map(MetricDatum::getTimestamp).collect(Collectors.toSet()));
        assertEquals(Set.of(0.0, 3.0),
                rows("repeat").stream().map(MetricDatum::getValue).collect(Collectors.toSet()));
        assertTrue(rows("repeat").stream().allMatch(datum -> datum.getSampleCount() == 1));
    }

    @Test
    void selectiveAndAllDisableStopFutureSamplesAndKeepPriorHistory() {
        group("disable");
        service.enableMetricsCollection(REGION, "disable", List.of("GroupMinSize", "GroupMaxSize"), "1Minute");
        publisher.tick(TIME);
        service.disableMetricsCollection(REGION, "disable", List.of("GroupMaxSize"));
        publisher.tick(TIME.plusSeconds(60));
        assertEquals(3, rows("disable").size());
        service.disableMetricsCollection(REGION, "disable", null);
        publisher.tick(TIME.plusSeconds(120));
        assertEquals(3, rows("disable").size());
        assertEquals(1, rows("disable").stream().filter(datum -> "GroupMaxSize".equals(datum.getMetricName())).count());
    }

    @Test
    void sameNameRecreationAtIdenticalCreationTimeHasIndependentPublicationOwnership() {
        AutoScalingGroup first = group("recreated");
        service.enableMetricsCollection(REGION, "recreated", List.of("GroupMinSize"), "1Minute");
        publisher.tick(TIME);
        service.deleteAutoScalingGroup(REGION, "recreated", true);
        publisher.tick(TIME.plusSeconds(1));
        assertEquals(1, rows("recreated").size());
        AutoScalingGroup second = group("recreated");
        assertEquals(first.getCreatedTime(), second.getCreatedTime());
        assertEquals(first.getAutoScalingGroupArn(), second.getAutoScalingGroupArn());
        assertNotEquals(first.getMetricsIncarnation(), second.getMetricsIncarnation());
        assertTrue(second.getEnabledMetrics().isEmpty());
        publisher.tick(TIME.plusSeconds(2));
        assertEquals(1, rows("recreated").size());
        service.enableMetricsCollection(REGION, "recreated", List.of("GroupMinSize"), "1Minute");
        publisher.tick(TIME.plusSeconds(3));
        assertEquals(2, rows("recreated").size());
        publisher.tick(TIME.plusSeconds(4));
        assertEquals(2, rows("recreated").size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedOrAmbiguousWriteRetriesWithoutDuplicationAndDoesNotSuppressAnotherGroup(boolean afterWrite) {
        group("failing");
        group("sibling");
        service.enableMetricsCollection(REGION, "failing", List.of("GroupMinSize", "GroupMaxSize"), "1Minute");
        service.enableMetricsCollection(REGION, "sibling", List.of("GroupMinSize"), "1Minute");
        CloudWatchMetricsService faulty = spy(metrics);
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(call -> {
            MetricDatum datum = call.getArgument(2);
            if ("failing".equals(datum.getDimensions().getFirst().value())
                    && "GroupMaxSize".equals(datum.getMetricName()) && fail.getAndSet(false)) {
                if (afterWrite) {
                    call.callRealMethod();
                }
                throw new IllegalStateException("owned metric write failed");
            }
            return call.callRealMethod();
        }).when(faulty).publishMetricForAccount(anyString(), anyString(), any(), anyString(), anyString());
        publisher = new AutoScalingMetricsPublisher(service, faulty, false);

        publisher.tick(TIME);
        assertEquals(1, rows("sibling").size());
        assertEquals(afterWrite ? 2 : 1, rows("failing").size());
        publisher.tick(TIME.plusSeconds(1));
        assertEquals(2, rows("failing").size());
        assertEquals(1, rows("sibling").size());
        assertTrue(rows("failing").stream().allMatch(datum -> datum.getSampleCount() == 1));
    }

    @Test
    void disableWaitsForCapturedPublicationAndPreventsAnyLaterWrite() throws Exception {
        group("locked");
        service.enableMetricsCollection(REGION, "locked", List.of("GroupMinSize"), "1Minute");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch disabling = new CountDownLatch(1);
        CloudWatchMetricsService blocking = spy(metrics);
        doAnswer(call -> {
            entered.countDown();
            await(release);
            return call.callRealMethod();
        }).when(blocking).publishMetricForAccount(anyString(), anyString(), any(), anyString(), anyString());
        publisher = new AutoScalingMetricsPublisher(service, blocking, false);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> publication = threads.submit(() -> publisher.tick(TIME));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> disable = threads.submit(() -> {
                disabling.countDown();
                service.disableMetricsCollection(REGION, "locked", null);
            });
            assertTrue(disabling.await(5, TimeUnit.SECONDS));
            assertFalse(disable.isDone());
            release.countDown();
            publication.get(5, TimeUnit.SECONDS);
            disable.get(5, TimeUnit.SECONDS);
            assertEquals(1, rows("locked").size());
            publisher.tick(TIME.plusSeconds(60));
            assertEquals(1, rows("locked").size());
        } finally {
            release.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualDeleteAndRecreateAfterEnumerationRejectsTheCapturedOldObject() {
        AutoScalingGroup old = group("captured");
        service.enableMetricsCollection(REGION, "captured", List.of("GroupMinSize"), "1Minute");
        AutoScalingService enumerating = spy(service);
        AtomicBoolean replace = new AtomicBoolean(true);
        doAnswer(call -> {
            if (replace.getAndSet(false)) {
                service.deleteAutoScalingGroup(REGION, "captured", true);
                group("captured");
            }
            return call.callRealMethod();
        }).when(enumerating).reconcileIfPresent(same(old), any());
        publisher = new AutoScalingMetricsPublisher(enumerating, metrics, false);
        publisher.tick(TIME);
        assertTrue(rows("captured").isEmpty());
        service.enableMetricsCollection(REGION, "captured", List.of("GroupMinSize"), "1Minute");
        publisher.tick(TIME.plusSeconds(1));
        assertEquals(1, rows("captured").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "wal"})
    void restartPreservesNewIncarnationAndLegacyFallbackContributionIdentity(String mode) {
        factory.shutdownAll();
        open(mode);
        AutoScalingGroup modern = group("modern");
        AutoScalingGroup legacy = group("legacy");
        legacy.setMetricsIncarnation(null);
        service.enableMetricsCollection(REGION, "modern", List.of("GroupMinSize"), "1Minute");
        service.enableMetricsCollection(REGION, "legacy", List.of("GroupMinSize"), "1Minute");
        String incarnation = modern.getMetricsIncarnation();
        publisher.tick(TIME);
        assertEquals(1, rows("modern").size());
        assertEquals(1, rows("legacy").size());
        factory.shutdownAll();
        open(mode);
        assertEquals(incarnation, service.requireGroup(REGION, "modern").getMetricsIncarnation());
        assertNull(service.requireGroup(REGION, "legacy").getMetricsIncarnation());
        publisher.tick(TIME);
        assertEquals(1, rows("modern").size());
        assertEquals(1, rows("legacy").size());
        publisher.tick(TIME.plusSeconds(60));
        assertEquals(2, rows("modern").size());
        assertEquals(2, rows("legacy").size());
    }

    @ParameterizedTest
    @CsvSource({"true,true,true,true", "false,true,true,false", "true,false,true,false", "true,true,false,false"})
    void schedulerRequiresAllThreeConfigurationSwitches(boolean asg, boolean collection,
                                                       boolean cloudWatch, boolean expected) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().autoscaling().enabled()).thenReturn(asg);
        when(config.services().autoscaling().cloudwatchMetricsEnabled()).thenReturn(collection);
        when(config.services().cloudwatchmetrics().enabled()).thenReturn(cloudWatch);
        AutoScalingMetricsPublisher configured = new AutoScalingMetricsPublisher(service, metrics, config);
        try {
            assertEquals(expected, configured.active());
        } finally {
            configured.onStop(null);
        }
    }

    @Test
    void epochZeroRemainsTheExplicitPublicationTimestamp() {
        group("epoch");
        service.enableMetricsCollection(REGION, "epoch", List.of("GroupMinSize"), "1Minute");
        publisher.tick(Instant.EPOCH);
        assertEquals(0, rows("epoch").getFirst().getTimestamp());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualResetDrainsTheWriteAndPausesNewTicksUntilStorageWipeFinishes(boolean afterWrite) throws Exception {
        group("reset");
        service.enableMetricsCollection(REGION, "reset", List.of("GroupMinSize"), "1Minute");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch reachedPause = new CountDownLatch(1);
        CountDownLatch wiping = new CountDownLatch(1);
        CountDownLatch finishWipe = new CountDownLatch(1);
        CloudWatchMetricsService blocking = spy(metrics);
        doAnswer(call -> {
            Object result = afterWrite ? call.callRealMethod() : null;
            entered.countDown();
            await(release);
            return afterWrite ? result : call.callRealMethod();
        }).when(blocking).publishMetricForAccount(anyString(), anyString(), any(), anyString(), anyString());
        publisher = new AutoScalingMetricsPublisher(service, blocking, false);
        Resettable observed = new Resettable() {
            @Override public void beforeReset() { reachedPause.countDown(); publisher.beforeReset(); }
            @Override public void clear() { publisher.clear(); }
            @Override public void afterReset() { publisher.afterReset(); }
        };
        StorageFactory resetStorage = spy(factory);
        doAnswer(call -> {
            wiping.countDown();
            await(finishWipe);
            return call.callRealMethod();
        }).when(resetStorage).clearAll();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> publication = threads.submit(() -> publisher.tick(TIME));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> reset = threads.submit(() -> controller(resetStorage, List.of(observed)).reset());
            assertTrue(reachedPause.await(5, TimeUnit.SECONDS));
            assertFalse(reset.isDone());
            release.countDown();
            publication.get(5, TimeUnit.SECONDS);
            assertTrue(wiping.await(5, TimeUnit.SECONDS));
            publisher.tick(TIME.plusSeconds(60));
            assertEquals(1, rows("reset").size());
            finishWipe.countDown();
            reset.get(5, TimeUnit.SECONDS);
            assertTrue(rows("reset").isEmpty());
            publisher.tick(TIME.plusSeconds(120));
            assertTrue(rows("reset").isEmpty());
            group("reset");
            service.enableMetricsCollection(REGION, "reset", List.of("GroupMinSize"), "1Minute");
            publisher.tick(TIME.plusSeconds(180));
            assertEquals(1, rows("reset").size());
        } finally {
            release.countDown();
            finishWipe.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedResetResumesTheSamePublisherAndPreservesExistingSamples(boolean beforePublisherHook) {
        group("failure-resume");
        service.enableMetricsCollection(REGION, "failure-resume", List.of("GroupMinSize"), "1Minute");
        publisher.tick(TIME);
        StorageFactory resetStorage = spy(factory);
        List<Resettable> resettables = new ArrayList<>();
        if (beforePublisherHook) {
            resettables.add(new Resettable() {
                @Override public void beforeReset() { throw new IllegalStateException("earlier hook failed"); }
                @Override public void clear() {}
            });
        } else {
            doThrow(new IllegalStateException("wipe refused")).when(resetStorage).clearAll();
        }
        resettables.add(publisher);
        assertThrows(IllegalStateException.class, () -> controller(resetStorage, resettables).reset());
        publisher.tick(TIME);
        assertEquals(1, rows("failure-resume").size());
        publisher.tick(TIME.plusSeconds(60));
        assertEquals(2, rows("failure-resume").size());
    }

    private void open(String mode) {
        factory = StorageFactories.open(directory, mode, ACCOUNT);
        service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, ACCOUNT);
        service.storageFactory = factory;
        service.initializeStorage();
        metrics = new CloudWatchMetricsService(factory, service.regionResolver);
        publisher = new AutoScalingMetricsPublisher(service, metrics, false);
    }

    private AutoScalingGroup group(String name) {
        AutoScalingGroup group = service.createAutoScalingGroup(REGION, name, "unit-launch-config", null, null,
                null, null, 0, 0, 0, 300, List.of(REGION + "a"), null, null, null,
                "EC2", 0, null, Map.of(), Map.of(), null);
        // Force the same public time even across delete/recreate; only the internal UUID can distinguish them.
        group.setCreatedTime(TIME);
        service.saveAutoScalingGroupIfPresent(group);
        assertNotNull(group.getMetricsIncarnation());
        return group;
    }

    private List<MetricDatum> rows(String name) {
        AccountAwareStorageBackend<MetricDatum> store = factory.create("cloudwatchmetrics", "cwmetrics.json",
                new TypeReference<Map<String, MetricDatum>>() {});
        return store.scanAllAccountEntries(key -> true).stream().filter(entry -> ACCOUNT.equals(entry.accountId()))
                .map(AccountAwareStorageBackend.AccountEntry::value)
                .filter(datum -> NAMESPACE.equals(datum.getNamespace())
                        && List.of(new Dimension("AutoScalingGroupName", name)).equals(datum.getDimensions()))
                .toList();
    }

    private Map<String, Double> values(String name) {
        return rows(name).stream().collect(Collectors.toMap(MetricDatum::getMetricName, MetricDatum::getValue));
    }

    private void assertDatum(MetricDatum datum, String name, Instant minute) {
        assertEquals(NAMESPACE, datum.getNamespace());
        assertEquals(List.of(new Dimension("AutoScalingGroupName", name)), datum.getDimensions());
        assertEquals("None", datum.getUnit());
        assertEquals(minute.getEpochSecond(), datum.getTimestamp());
        assertEquals(1, datum.getSampleCount());
        assertEquals(datum.getValue(), datum.getSum());
        assertEquals(datum.getValue(), datum.getMinimum());
        assertEquals(datum.getValue(), datum.getMaximum());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private EmulatorInfoController controller(StorageFactory storage, List<Resettable> services) {
        Instance<Resettable> resettables = mock(Instance.class);
        when(resettables.iterator()).thenAnswer(call -> services.iterator());
        Instance<ContainerTeardown> teardowns = mock(Instance.class);
        when(teardowns.iterator()).thenAnswer(call -> List.<ContainerTeardown>of().iterator());
        return new EmulatorInfoController(null, null, storage, resettables, teardowns, null, null, null);
    }
}
