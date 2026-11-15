package io.github.hectorvent.floci.services.applicationautoscaling;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.applicationautoscaling.model.PredefinedMetricSpecification;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalableTarget;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalingActivity;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalingPolicy;
import io.github.hectorvent.floci.services.applicationautoscaling.model.TargetTrackingConfiguration;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmHistoryItem;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService.ScalableCapacityDecision;
import io.github.hectorvent.floci.testing.StorageFactories;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongUnaryOperator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real policy and CloudWatch stores exercise ownership and observed compensation, with a controlled native boundary. */
@QuarkusTest
@TestProfile(DynamoDbPolicyOwnershipTest.OwnerProfile.class)
@Timeout(30)
class DynamoDbPolicyOwnershipTest {
    private static final String ACCOUNT = "740000000001";
    private static final String REGION = "us-east-1";
    private static final String RESOURCE = "table/owned";
    private static final String DIMENSION = "dynamodb:table:WriteCapacityUnits";
    private static final boolean LEGACY_STEP_DELETES = true;
    @TempDir
    Path directory;
    @Inject
    RegionResolver regions;
    @Inject
    Instance<RequestContext> requestContexts;
    private final DynamoDbService dynamo = mock(DynamoDbService.class);
    private final Map<String, Long> capacities = new ConcurrentHashMap<>();
    private StorageFactory factory;
    private StorageBackend<String, ScalableTarget> targets;
    private StorageBackend<String, ScalingPolicy> policies;
    private CloudWatchMetricsService metrics;
    private ApplicationAutoScalingService service;
    private ScalingPolicyAlarmActionHandler handler;

    @BeforeEach
    void useOwnedRealPolicyAndAlarmStores() throws ReflectiveOperationException {
        factory = StorageFactories.open(directory, "memory", ACCOUNT);
        Field requestContextField = StorageFactory.class.getDeclaredField("requestContextInstance");
        requestContextField.setAccessible(true);
        requestContextField.set(factory, requestContexts);
        targets = factory.create("applicationautoscaling", "application-autoscaling-targets.json",
                new TypeReference<Map<String, ScalableTarget>>() {});
        policies = spy(factory.create("applicationautoscaling", "application-autoscaling-policies.json",
                new TypeReference<Map<String, ScalingPolicy>>() {}));
        StorageBackend<String, ScalingActivity> activities = factory.create(
                "applicationautoscaling", "application-autoscaling-activities.json",
                new TypeReference<Map<String, ScalingActivity>>() {});
        metrics = spy(new CloudWatchMetricsService(factory, regions));
        service = new ApplicationAutoScalingService(targets, policies, activities,
                regions, metrics, mock(LambdaCapacityAdjuster.class), dynamo);
        when(dynamo.decideScalableCapacity(anyString(), anyString(), anyString(), any(LongUnaryOperator.class)))
                .thenAnswer(invocation -> {
                    String resource = invocation.getArgument(0);
                    String dimension = invocation.getArgument(1);
                    String region = invocation.getArgument(2);
                    assertTrue(Thread.holdsLock(targetLock(region, "dynamodb", resource, dimension)),
                            "Both snapshot and decision require the owning target lock");
                    String key = regions.getAccountId() + "/" + region;
                    long before = capacities.getOrDefault(key, 3L);
                    LongUnaryOperator decision = invocation.getArgument(3);
                    long next = decision.applyAsLong(before);
                    capacities.put(key, next);
                    return new ScalableCapacityDecision(true, before, next);
                });
        @SuppressWarnings("unchecked")
        Instance<CapacityAdjuster> adjusters = mock(Instance.class);
        when(adjusters.iterator()).thenAnswer(ignored -> List.<CapacityAdjuster>of().iterator());
        handler = new ScalingPolicyAlarmActionHandler(service, adjusters);
        target("dynamodb", RESOURCE, DIMENSION, REGION);
    }

    private Object targetLock(String region, String namespace, String resource, String dimension) {
        try {
            Method method = ApplicationAutoScalingService.class.getDeclaredMethod(
                    "targetLock", String.class, String.class, String.class, String.class);
            method.setAccessible(true);
            return method.invoke(service, region, namespace, resource, dimension);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot observe the service's actual owning target lock", failure);
        }
    }

    @AfterEach
    void closeEveryFactoryBackend() {
        if (factory != null) {
            factory.shutdownAll();
        }
    }

    @Test
    void identicalPolicyUpdateRetainsFourAlarmIdentitiesStateAndTags() {
        ScalingPolicy first = put(70);
        Map<String, String> identities = identities(REGION);
        assertEquals(4, identities.size());
        for (Map.Entry<String, String> alarm : identities.entrySet()) {
            metrics.setAlarmState(alarm.getKey(), "ALARM", "owned prior state", null, REGION);
            metrics.tagResource(alarm.getValue(), Map.of("owned", "retained"), REGION);
        }
        PaginatedResult<AlarmHistoryItem> before = history();
        assertNull(before.nextToken());
        assertFalse(before.items().isEmpty());
        clearInvocations(metrics);
        ScalingPolicy second = put(70);
        assertNotSame(first, second);
        assertEquals(first.getPolicyArn(), second.getPolicyArn());
        assertEquals(first.getCreationTime(), second.getCreationTime());
        assertEquals(70.0, first.getTargetTrackingConfiguration().getTargetValue());
        assertEquals(identities, identities(REGION));
        assertThresholds(REGION, 3, 70);
        for (MetricAlarm alarm : alarms(REGION)) {
            assertEquals("ALARM", alarm.getStateValue());
            assertEquals("owned prior state", alarm.getStateReason());
            assertEquals(Map.of("owned", "retained"), metrics.listTagsForResource(alarm.getAlarmArn(), REGION));
        }
        PaginatedResult<AlarmHistoryItem> after = history();
        assertNull(after.nextToken());
        assertTrue(after.items().containsAll(before.items()), "Every original complete history record remains");
        verify(metrics, never()).deleteAlarms(anyList(), anyString());
        verify(metrics, never()).putMetricAlarm(any(), anyString());
    }

    @Test
    void genericUpdatesRetainTheBranchesOriginalExactlyOnceDeletionBehavior() {
        String resource = "service/owned/service";
        String dimension = "ecs:service:DesiredCount";
        target("ecs", resource, dimension, REGION);
        TargetTrackingConfiguration configuration = configuration(70);
        configuration.getPredefinedMetricSpecification().setPredefinedMetricType("ECSServiceAverageCPUUtilization");
        service.putScalingPolicy("generic", "TargetTrackingScaling", "ecs", resource, dimension,
                configuration, null, REGION);
        clearInvocations(metrics);
        service.putScalingPolicy("generic", "TargetTrackingScaling", "ecs", resource, dimension,
                configuration, null, REGION);
        verify(metrics, times(2)).deleteAlarms(anyList(), eq(REGION));
        clearInvocations(metrics);
        service.putScalingPolicy("generic", "StepScaling", "ecs", resource, dimension, null, null, REGION);
        verify(metrics, times(LEGACY_STEP_DELETES ? 2 : 0)).deleteAlarms(anyList(), eq(REGION));
    }

    @Test
    void maximumLengthTableAndIndexComponentsStillProduceAcceptedBoundedAlarmNames() {
        String resource = "table/" + "a".repeat(255) + "/index/" + "b".repeat(255);
        String dimension = "dynamodb:index:WriteCapacityUnits";
        target("dynamodb", resource, dimension, REGION);
        ScalingPolicy result = service.putScalingPolicy("long-owned", "TargetTrackingScaling",
                "dynamodb", resource, dimension, configuration(70), null, REGION);
        assertEquals(4, result.getAlarms().size());
        assertTrue(result.getAlarms().stream().allMatch(alarm -> alarm.getAlarmName().length() <= 255));
        assertEquals(4, alarms(REGION).size());
        assertTrue(alarms(REGION).stream().allMatch(alarm ->
                alarm.getAlarmActions().equals(List.of(result.getPolicyArn()))));
    }

    @Test
    void firstAlarmWriteFailureAfterItsWriteRemovesEveryNewIdentity() {
        AtomicInteger puts = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("observed second alarm write");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (puts.incrementAndGet() == 2) {
                throw failure;
            }
            return null;
        }).when(metrics).putMetricAlarm(any(), eq(REGION));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> put(70)));
        assertTrue(policies.keys().isEmpty());
        assertTrue(alarms(REGION).isEmpty());
        assertEquals(1, targets.keys().size());
        assertEquals(3, capacity(REGION));
    }

    @Test
    void failedExistingAlarmUpdateRestoresThresholdsWithoutLosingStateTagsOrIdentities() {
        ScalingPolicy original = put(70);
        Map<String, String> identities = identities(REGION);
        for (Map.Entry<String, String> alarm : identities.entrySet()) {
            metrics.setAlarmState(alarm.getKey(), "OK", "retained", null, REGION);
            metrics.tagResource(alarm.getValue(), Map.of("owned", "retained"), REGION);
        }
        AtomicInteger puts = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("existing alarm update");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (puts.incrementAndGet() == 2) {
                throw failure;
            }
            return null;
        }).when(metrics).putMetricAlarm(any(), eq(REGION));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> put(80)));
        assertSame(original, service.findPolicyByArn(original.getPolicyArn(), REGION).orElseThrow());
        assertEquals(identities, identities(REGION));
        assertThresholds(REGION, 3, 70);
        for (MetricAlarm alarm : alarms(REGION)) {
            assertEquals("OK", alarm.getStateValue());
            assertEquals(Map.of("owned", "retained"), metrics.listTagsForResource(alarm.getAlarmArn(), REGION));
        }
    }

    @Test
    void observedPolicyCommitFailureRestoresTheDetachedPreviousPolicyAndAlarmConfiguration() {
        ScalingPolicy original = put(70);
        Map<String, String> identities = identities(REGION);
        AtomicInteger commits = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("policy write after mutation");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (commits.incrementAndGet() == 1) {
                throw failure;
            }
            return null;
        }).when(policies).put(anyString(), any(ScalingPolicy.class));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> put(80)));
        assertEquals(2, commits.get());
        assertSame(original, service.findPolicyByArn(original.getPolicyArn(), REGION).orElseThrow());
        assertEquals(70.0, original.getTargetTrackingConfiguration().getTargetValue());
        assertEquals(identities, identities(REGION));
        assertThresholds(REGION, 3, 70);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dispatch", "reconcile"})
    void aDeletedPolicyCannotBeRepublishedByAnAlreadySelectedBackgroundCandidate(String operation) throws Exception {
        ScalingPolicy policy = put(70);
        MetricAlarm alarm = high(REGION);
        Object lock = targetLock(REGION, "dynamodb", RESOURCE, DIMENSION);
        AtomicReference<Thread> queued = new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> pending;
            synchronized (lock) {
                pending = executor.submit(() -> {
                    queued.set(Thread.currentThread());
                    if ("dispatch".equals(operation)) {
                        handler.handle(policy.getPolicyArn(), alarm, 9000, REGION);
                    } else {
                        service.reconcileDynamoDbPolicies(Instant.now());
                    }
                });
                blockedOnThisThread(queued);
                service.deleteScalingPolicy("owned", "dynamodb", RESOURCE, DIMENSION, REGION);
                clearInvocations(dynamo, metrics);
            }
            pending.get(5, TimeUnit.SECONDS);
        }
        verifyNoInteractions(dynamo);
        verify(metrics, never()).putMetricAlarm(any(), anyString());
        assertTrue(policies.keys().isEmpty());
        assertTrue(alarms(REGION).isEmpty());
        assertEquals(3, capacity(REGION));
    }

    @Test
    void dispatchRejectsTheRetiredAlarmAndUsesTheCurrentOwnedAlarmUnderTheSameTargetLock() throws Exception {
        ScalingPolicy first = put(70);
        MetricAlarm alarm = high(REGION);
        Object lock = targetLock(REGION, "dynamodb", RESOURCE, DIMENSION);
        AtomicReference<Thread> queued = new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> pending;
            synchronized (lock) {
                pending = executor.submit(() -> {
                    queued.set(Thread.currentThread());
                    handler.handle(first.getPolicyArn(), alarm, 9000, REGION);
                });
                blockedOnThisThread(queued);
                put(80);
                clearInvocations(dynamo);
            }
            pending.get(5, TimeUnit.SECONDS);
        }
        verifyNoInteractions(dynamo);
        assertEquals(3, capacity(REGION), "A selected alarm from the retired configuration cannot scale");
        assertThresholds(REGION, 3, 80);
        handler.handle(first.getPolicyArn(), high(REGION), 9000, REGION);
        assertEquals(188, capacity(REGION), "ceil(9000 / (60 * .80)) uses the current policy");
        assertThresholds(REGION, 188, 80);
    }

    @Test
    void backgroundTraversalReinstallsEachAccountAndRegionBeforeThresholdPublication() {
        Map<String, Map<String, String>> identities = new ConcurrentHashMap<>();
        for (String account : List.of(ACCOUNT, "740000000002")) {
            for (String region : List.of(REGION, "us-west-2")) {
                RequestScopes.runAs(account, region, () -> {
                    assertEquals(account, regions.getAccountId(), "The setup must install the actual request account");
                    assertEquals(region, regions.getRegion(), "The setup must install the actual request Region");
                    target("dynamodb", RESOURCE, DIMENSION, region);
                    service.putScalingPolicy("owned", "TargetTrackingScaling", "dynamodb", RESOURCE, DIMENSION,
                            configuration(70), null, region);
                    identities.put(account + "/" + region, identities(region));
                    assertTrue(identities(region).values().stream().allMatch(arn ->
                            arn.contains(":" + region + ":" + account + ":alarm:")),
                            "Setup must retain alarm identities for the actual request owner and Region");
                });
                capacities.put(account + "/" + region, 17L);
            }
        }
        assertEquals(16L, identities.values().stream().flatMap(value -> value.values().stream()).distinct().count(),
                "Four owner/Region setups must retain sixteen distinct stored alarm identities before traversal");
        service.reconcileDynamoDbPolicies(Instant.now());
        for (String account : List.of(ACCOUNT, "740000000002")) {
            for (String region : List.of(REGION, "us-west-2")) {
                RequestScopes.runAs(account, region, () -> {
                    assertThresholds(region, 17, 70);
                    assertEquals(identities.get(account + "/" + region), identities(region));
                    assertTrue(alarms(region).stream().allMatch(alarm ->
                            alarm.getAlarmArn().contains(":" + region + ":" + account + ":alarm:")));
                });
            }
        }
        assertEquals(ACCOUNT, regions.getAccountId());
        assertEquals(REGION, regions.getRegion());
    }

    private ScalingPolicy put(double target) {
        return service.putScalingPolicy("owned", "TargetTrackingScaling", "dynamodb", RESOURCE, DIMENSION,
                configuration(target), null, REGION);
    }

    private void target(String namespace, String resource, String dimension, String region) {
        ScalableTarget target = new ScalableTarget();
        target.setServiceNamespace(namespace);
        target.setResourceId(resource);
        target.setScalableDimension(dimension);
        target.setMinCapacity(1);
        target.setMaxCapacity(300);
        targets.put(region + "::" + namespace + "::" + dimension + "::" + resource, target);
    }

    private static TargetTrackingConfiguration configuration(double target) {
        TargetTrackingConfiguration config = new TargetTrackingConfiguration();
        config.setTargetValue(target);
        PredefinedMetricSpecification spec = new PredefinedMetricSpecification();
        spec.setPredefinedMetricType("DynamoDBWriteCapacityUtilization");
        config.setPredefinedMetricSpecification(spec);
        return config;
    }

    private long capacity(String region) {
        return capacities.getOrDefault(regions.getAccountId() + "/" + region, 3L);
    }

    private List<MetricAlarm> alarms(String region) {
        return metrics.describeAlarms(null, null, region);
    }

    private PaginatedResult<AlarmHistoryItem> history() {
        return metrics.describeAlarmHistory(null, List.of("MetricAlarm"), null, null, null,
                100, null, "TimestampAscending", REGION);
    }

    private Map<String, String> identities(String region) {
        return alarms(region).stream().collect(Collectors.toMap(MetricAlarm::getAlarmName, MetricAlarm::getAlarmArn));
    }

    private MetricAlarm high(String region) {
        return alarms(region).stream().filter(alarm -> "ConsumedWriteCapacityUnits".equals(alarm.getMetricName())
                && "GreaterThanThreshold".equals(alarm.getComparisonOperator())).findFirst().orElseThrow();
    }

    private void assertThresholds(String region, long capacity, double target) {
        assertEquals(4, alarms(region).size());
        for (MetricAlarm alarm : alarms(region)) {
            boolean consumed = "ConsumedWriteCapacityUnits".equals(alarm.getMetricName());
            boolean high = "GreaterThanThreshold".equals(alarm.getComparisonOperator());
            assertEquals(consumed ? capacity * 60.0 * (high ? target : target - 20) / 100 : capacity,
                    alarm.getThreshold());
            assertEquals(consumed ? "Sum" : "Average", alarm.getStatistic());
            assertEquals(consumed ? 60 : 300, alarm.getPeriod());
            assertEquals(consumed ? high ? 2 : 15 : 3, alarm.getEvaluationPeriods());
        }
    }

    private static void blockedOnThisThread(AtomicReference<Thread> queued) {
        long owner = Thread.currentThread().threadId();
        Awaitility.await().pollInSameThread().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertNotNull(queued.get());
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(queued.get().threadId());
            assertNotNull(info);
            assertEquals(Thread.State.BLOCKED, info.getThreadState());
            assertEquals(owner, info.getLockOwnerId());
        });
    }

    public static final class OwnerProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.default-account-id", ACCOUNT, "floci.default-region", REGION);
        }
    }
}
