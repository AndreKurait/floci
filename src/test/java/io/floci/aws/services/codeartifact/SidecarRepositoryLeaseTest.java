package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.docker.PerKeyContainerPool;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SidecarRepositoryLeaseTest {
    private final PerKeyContainerPool pool = mock(PerKeyContainerPool.class);
    private final PerKeyContainerPool.Starter starter = mock(PerKeyContainerPool.Starter.class);
    private final SidecarRepositoryLease lease = new SidecarRepositoryLease("test", pool);

    @Test
    void evenAnUnleasedCallerCannotRestartASoleAdmittedRequest() {
        lease.registerRepository("repo");
        lease.beginUse("repo");
        lease.ensureReady("repo", starter);
        verify(pool).ensureReady("repo", starter, false);
        lease.endUse("repo");
        lease.ensureReady("repo", starter);
        verify(pool).ensureReady("repo", starter, true);
    }

    @Test
    void directFirstUseRetainsThePublicManagerContract() {
        lease.ensureReady("repo", starter);
        verify(pool).ensureReady("repo", starter, true);
    }

    @Test
    void healthFailureRetiresOnlyAfterTheFinalUseAndPermitsTheNextStart() {
        lease.registerRepository("repo");
        lease.beginUse("repo");
        lease.beginUse("repo");
        when(pool.ensureReady("repo", starter, false)).thenThrow(new IllegalStateException("health timeout"));
        assertThrows(IllegalStateException.class, () -> lease.ensureReady("repo", starter));
        lease.endUse("repo");
        verify(pool, never()).stopContainerStrict("repo");
        lease.endUse("repo");
        verify(pool).stopContainerStrict("repo");
        doReturn("ready").when(pool).ensureReady("repo", starter, false);
        lease.beginUse("repo");
        lease.ensureReady("repo", starter);
        verify(pool, times(2)).ensureReady("repo", starter, false);
    }

    @Test
    void failedRetirementRemainsPendingAndRetriesBeforeANewAdmission() {
        lease.registerRepository("repo");
        lease.beginUse("repo");
        when(pool.ensureReady("repo", starter, false)).thenThrow(new IllegalStateException("health timeout"));
        assertThrows(IllegalStateException.class, () -> lease.ensureReady("repo", starter));
        doThrow(new IllegalStateException("Docker busy")).doNothing().when(pool).stopContainerStrict("repo");
        lease.endUse("repo");
        lease.beginUse("repo");
        verify(pool, times(2)).stopContainerStrict("repo");
    }

    @Test
    void recoveredHealthCancelsPendingRetirement() {
        lease.registerRepository("repo");
        lease.beginUse("repo");
        when(pool.ensureReady("repo", starter, false)).thenThrow(new IllegalStateException("health timeout"))
                .thenReturn("ready");
        assertThrows(IllegalStateException.class, () -> lease.ensureReady("repo", starter));
        lease.ensureReady("repo", starter);
        lease.endUse("repo");
        verify(pool, never()).stopContainerStrict("repo");
    }
    @Test
    void releaseDuringReadinessReturnsWithoutWaitingAndRetiresAfterTheProbe() throws Exception {
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        when(pool.isTracked("repo")).thenReturn(true);
        when(pool.ensureReady("repo", starter, true)).thenAnswer(call -> {
            checking.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            return "ready";
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> probe = executor.submit(() -> lease.ensureReady("repo", starter));
            try {
                assertTrue(checking.await(5, TimeUnit.SECONDS));
                executor.submit(() -> lease.release("repo")).get(2, TimeUnit.SECONDS);
                verify(pool, never()).stopContainerStrict("repo");
            } finally {
                ready.countDown();
            }
            assertEquals("ready", probe.get(5, TimeUnit.SECONDS));
            verify(pool).stopContainerStrict("repo");
            assertThrows(IllegalStateException.class, () -> lease.beginUse("repo"));
        }
    }

    @Test
    void finalUseCanFinishDuringReadinessAndFailedProbeRetiresAfterward() throws Exception {
        lease.registerRepository("repo");
        lease.beginUse("repo");
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        when(pool.ensureReady("repo", starter, false)).thenAnswer(call -> {
            checking.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            throw new IllegalStateException("health timeout");
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> probe = executor.submit(() -> lease.ensureReady("repo", starter));
            try {
                assertTrue(checking.await(5, TimeUnit.SECONDS));
                executor.submit(() -> lease.endUse("repo")).get(2, TimeUnit.SECONDS);
                verify(pool, never()).stopContainerStrict("repo");
            } finally {
                ready.countDown();
            }
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> probe.get(5, TimeUnit.SECONDS));
            assertEquals("health timeout", failure.getCause().getMessage());
            verify(pool).stopContainerStrict("repo");
        }
    }

}
