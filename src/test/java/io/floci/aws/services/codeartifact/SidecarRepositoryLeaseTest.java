package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.docker.PerKeyContainerPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
