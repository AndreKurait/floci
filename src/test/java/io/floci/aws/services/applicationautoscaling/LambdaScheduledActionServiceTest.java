package io.floci.aws.services.applicationautoscaling;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.applicationautoscaling.model.ScalableTarget;
import io.floci.aws.services.applicationautoscaling.model.ScalingActivity;
import io.floci.aws.services.applicationautoscaling.model.ScheduledAction;
import io.floci.aws.services.applicationautoscaling.model.ScheduledAction.State;
import io.floci.aws.services.applicationautoscaling.model.SuspendedState;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.floci.aws.services.applicationautoscaling.ScheduledActionFixtures.schedule;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LambdaScheduledActionServiceTest {
    private static final String REGION = "us-east-1";
    private static final String RESOURCE = "function:worker:live";
    private static final String DIMENSION = "lambda:function:ProvisionedConcurrency";
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private ApplicationAutoScalingService service;
    private LambdaCapacityAdjuster lambda;
    private EcsCapacityAdjuster ecs;
    private AtomicInteger capacity;

    @BeforeEach
    void setUp() {
        capacity = new AtomicInteger();
        lambda = mock(LambdaCapacityAdjuster.class);
        ecs = mock(EcsCapacityAdjuster.class);
        when(lambda.supports(DIMENSION)).thenReturn(true);
        when(lambda.getCurrentCapacity(RESOURCE, REGION)).thenAnswer(call -> capacity.get());
        doAnswer(call -> {
            capacity.set(call.getArgument(1));
            return null;
        }).when(lambda).setCapacity(eq(RESOURCE), anyInt(), eq(REGION));
        service = new ApplicationAutoScalingService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"),
                mock(CloudWatchMetricsService.class), ecs, lambda);
        service.registerScalableTarget("lambda", RESOURCE, DIMENSION, 0, 3, null, null, null, REGION);
        clearInvocations(lambda);
    }

    @Test
    void scheduledMinimumScalesOutAndMaximumScalesInThroughLambdaOnly() {
        put("out", 2, null);
        service.runScheduledActions(NOW);
        assertBounds(2, 3, 2);
        assertEquals(State.SUCCEEDED, action("out").getState());
        put("minimum", 0, null);
        service.runScheduledActions(NOW);
        assertBounds(0, 3, 2);
        put("in", null, 0);
        service.runScheduledActions(NOW);
        assertBounds(0, 0, 0);
        verify(lambda).setCapacity(RESOURCE, 2, REGION);
        verify(lambda).setCapacity(RESOURCE, 0, REGION);
        verifyNoInteractions(ecs);
        assertEquals(3, activities().size());
        assertTrue(activities().stream().allMatch(a -> "Successful".equals(a.getStatusCode())));
    }

    @Test
    void inRangeCapacityIsReadWithoutAnUnnecessaryWrite() {
        capacity.set(2);
        put("range", 1, 3);
        service.runScheduledActions(NOW);
        assertBounds(1, 3, 2);
        verify(lambda).getCurrentCapacity(RESOURCE, REGION);
        verify(lambda, never()).setCapacity(anyString(), anyInt(), anyString());
        verifyNoInteractions(ecs);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 429, 500})
    void failedCapacityReadRecordsFailureWithoutRetryingTheOccurrence(int status) {
        doThrow(new AwsException("ResourceNotFoundException", "read failed " + status, status))
                .when(lambda).getCurrentCapacity(RESOURCE, REGION);
        put("failed", 1, 2);
        service.runScheduledActions(NOW);
        service.runScheduledActions(NOW.plusSeconds(1));
        assertEquals(State.FAILED, action("failed").getState());
        assertEquals(1, target().getMinCapacity());
        assertEquals(2, target().getMaxCapacity());
        assertEquals("Failed", activities().getFirst().getStatusCode());
        assertEquals("read failed " + status, activities().getFirst().getStatusMessage());
        verify(lambda, times(1)).getCurrentCapacity(RESOURCE, REGION);
        verify(lambda, never()).setCapacity(anyString(), anyInt(), anyString());
    }

    @Test
    void failedCapacityWriteIsRetainedAsFailureAndCanBeExplicitlyRescheduled() {
        doThrow(new AwsException("TooManyRequestsException", "capacity refused", 429))
                .doAnswer(call -> {
                    capacity.set(call.getArgument(1));
                    return null;
                }).when(lambda).setCapacity(RESOURCE, 1, REGION);
        ScheduledAction original = put("retry", 1, 1);
        service.runScheduledActions(NOW);
        service.runScheduledActions(NOW.plusSeconds(1));
        assertEquals(State.FAILED, action("retry").getState());
        assertEquals("capacity refused", activities().getFirst().getStatusMessage());
        verify(lambda, times(1)).setCapacity(RESOURCE, 1, REGION);
        service.putScheduledAction("lambda", RESOURCE, DIMENSION, "retry", schedule(NOW.plusSeconds(2)),
                false, null, null, null, REGION, NOW);
        service.runScheduledActions(NOW.plusSeconds(2));
        assertBounds(1, 1, 1);
        assertEquals(original.getScheduledActionArn(), action("retry").getScheduledActionArn());
        assertEquals(State.SUCCEEDED, action("retry").getState());
        assertEquals(2, activities().size());
    }

    @Test
    void conflictingSingleBoundDoesNotReadOrWriteLambdaCapacity() {
        put("invalid-at-execution", 4, null);
        service.runScheduledActions(NOW);
        assertBounds(0, 3, 0);
        assertEquals(State.FAILED, action("invalid-at-execution").getState());
        assertEquals("Failed", activities().getFirst().getStatusCode());
        verifyNoInteractions(lambda, ecs);
    }

    @Test
    void scheduledSuspensionSkipsWhileDynamicSuspensionDoesNotBlockSchedules() {
        SuspendedState suspension = new SuspendedState();
        suspension.setScheduledScalingSuspended(true);
        suspension.setDynamicScalingInSuspended(true);
        suspension.setDynamicScalingOutSuspended(true);
        service.registerScalableTarget("lambda", RESOURCE, DIMENSION, null, null, null, suspension, null, REGION);
        clearInvocations(lambda);
        put("skipped", 1, 1);
        service.runScheduledActions(NOW);
        assertEquals(State.SKIPPED, action("skipped").getState());
        assertTrue(activities().isEmpty());
        verifyNoInteractions(lambda);
        suspension.setScheduledScalingSuspended(false);
        service.registerScalableTarget("lambda", RESOURCE, DIMENSION, null, null, null, suspension, null, REGION);
        clearInvocations(lambda);
        put("allowed", 1, 1);
        service.runScheduledActions(NOW);
        assertBounds(1, 1, 1);
        assertEquals(State.SUCCEEDED, action("allowed").getState());
    }

    @Test
    void deletedActionsAndDeregisteredTargetsCannotChangeCapacity() {
        put("deleted", 1, 1);
        service.deleteScheduledAction("lambda", RESOURCE, DIMENSION, "deleted", REGION);
        service.runScheduledActions(NOW);
        put("deregistered", 2, 2);
        service.deregisterScalableTarget("lambda", RESOURCE, DIMENSION, REGION);
        service.runScheduledActions(NOW);
        assertTrue(service.describeScalableTargets("lambda", List.of(RESOURCE), DIMENSION, REGION).isEmpty());
        assertTrue(service.describeScheduledActions("lambda", RESOURCE, DIMENSION, List.of(), null, null, REGION).items().isEmpty());
        verifyNoInteractions(lambda, ecs);
    }

    @Test
    void unsupportedDimensionStillRefusesBeforeWritingAnAction() {
        service.registerScalableTarget("dynamodb", "table/example", "dynamodb:table:ReadCapacityUnits",
                1, 3, null, null, null, REGION);
        AwsException failure = assertThrows(AwsException.class, () -> service.putScheduledAction(
                "dynamodb", "table/example", "dynamodb:table:ReadCapacityUnits", "unsupported",
                schedule(NOW), true, 1, 1, null, REGION, NOW));
        assertEquals("ValidationException", failure.getErrorCode());
        assertTrue(service.describeScheduledActions("dynamodb", "table/example",
                "dynamodb:table:ReadCapacityUnits", List.of(), null, null, REGION).items().isEmpty());
        verifyNoInteractions(ecs);
    }

    private ScheduledAction put(String name, Integer min, Integer max) {
        return service.putScheduledAction("lambda", RESOURCE, DIMENSION, name, schedule(NOW),
                true, min, max, null, REGION, NOW);
    }

    private ScheduledAction action(String name) {
        return service.describeScheduledActions("lambda", RESOURCE, DIMENSION, List.of(name),
                null, null, REGION).items().getFirst();
    }

    private ScalableTarget target() {
        return service.describeScalableTargets("lambda", List.of(RESOURCE), DIMENSION, REGION).getFirst();
    }

    private List<ScalingActivity> activities() {
        return service.describeScalingActivities("lambda", RESOURCE, DIMENSION, REGION);
    }

    private void assertBounds(int min, int max, int count) {
        assertEquals(min, target().getMinCapacity());
        assertEquals(max, target().getMaxCapacity());
        assertEquals(count, capacity.get());
    }
}
