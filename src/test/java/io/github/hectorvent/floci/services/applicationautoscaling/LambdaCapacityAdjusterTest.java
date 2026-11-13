package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.ProvisionedConcurrencyPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LambdaCapacityAdjusterTest {

    private static final String REGION = "eu-west-1";
    private static final String ACCOUNT = "222222222222";
    private static final String RESOURCE = "function:worker:live";
    private final ProvisionedConcurrencyPool pool = mock(ProvisionedConcurrencyPool.class);
    private final LambdaCapacityAdjuster adjuster =
            new LambdaCapacityAdjuster(pool, new RegionResolver(REGION, ACCOUNT));

    @Test
    void supportsOnlyLambdaProvisionedConcurrency() {
        assertTrue(adjuster.supports("lambda:function:ProvisionedConcurrency"));
        assertFalse(adjuster.supports("ecs:service:DesiredCount"));
    }

    @Test
    void readsRequestedCapacityDuringInitializationInTheOwningAccount() {
        when(pool.get(REGION, "worker", "live", ACCOUNT))
                .thenReturn(new ProvisionedConcurrencyPool.Status(3, 1, 1, "IN_PROGRESS", null, null));
        assertEquals(3, adjuster.getCurrentCapacity(RESOURCE, REGION));
    }

    @Test
    void registrationCreatesCapacityWhenThePublishedAliasHasNone() {
        when(pool.get(REGION, "worker", "live", ACCOUNT))
                .thenThrow(new AwsException("ProvisionedConcurrencyConfigNotFoundException", "none", 404));
        adjuster.reconcileRegistration(RESOURCE, 2, 4, REGION);
        verify(pool).put(REGION, "worker", "live", 2, ACCOUNT);
    }

    @Test
    void registrationLeavesCapacityWithinBoundsUnchanged() {
        when(pool.get(REGION, "worker", "live", ACCOUNT))
                .thenReturn(new ProvisionedConcurrencyPool.Status(3, 3, 3, "READY", null, null));
        adjuster.reconcileRegistration(RESOURCE, 2, 4, REGION);
        verify(pool).get(REGION, "worker", "live", ACCOUNT);
        verifyNoMoreInteractions(pool);
    }

    @Test
    void registrationClampsToTheNewMaximum() {
        when(pool.get(REGION, "worker", "live", ACCOUNT))
                .thenReturn(new ProvisionedConcurrencyPool.Status(3, 3, 3, "READY", null, null));
        adjuster.reconcileRegistration(RESOURCE, 1, 2, REGION);
        verify(pool).put(REGION, "worker", "live", 2, ACCOUNT);
    }

    @Test
    void zeroCapacityDeletesTheProvisionedAllocation() {
        adjuster.setCapacity(RESOURCE, 0, REGION);
        verify(pool).delete(REGION, "worker", "live", ACCOUNT);
        verifyNoMoreInteractions(pool);
    }

    @Test
    void unavailableResourceCanBeRegisteredWithoutCreatingAnAllocation() {
        when(pool.get(REGION, "worker", "live", ACCOUNT))
                .thenThrow(new AwsException("ResourceNotFoundException", "not active", 404));
        adjuster.reconcileRegistration(RESOURCE, 1, 2, REGION);
        verify(pool).get(REGION, "worker", "live", ACCOUNT);
        verifyNoMoreInteractions(pool);
    }

    @Test
    void otherPoolFailuresAreNotHiddenAsInactiveResources() {
        AwsException failure = new AwsException("ServiceException", "storage unavailable", 500);
        when(pool.get(REGION, "worker", "live", ACCOUNT)).thenThrow(failure);
        assertSame(failure, assertThrows(AwsException.class,
                () -> adjuster.reconcileRegistration(RESOURCE, 1, 2, REGION)));
    }

    @Test
    void invalidBoundsAreRejectedBeforeAccessingCapacity() {
        assertThrows(AwsException.class, () -> adjuster.reconcileRegistration(RESOURCE, -1, 2, REGION));
        assertThrows(AwsException.class, () -> adjuster.reconcileRegistration(RESOURCE, 3, 2, REGION));
        verifyNoInteractions(pool);
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "function:worker", "function::live", "function:worker:",
            "function:worker:$LATEST", "service:worker:live", "function:worker:live:extra",
            "function:worker name:live", "function:worker:live/name"})
    void rejectsInvalidResourceIdsBeforeAccessingCapacity(String resource) {
        assertThrows(AwsException.class, () -> adjuster.getCurrentCapacity(resource, REGION));
        verifyNoInteractions(pool);
    }
}
