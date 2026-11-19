package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendResponseCoordinatorRecoveryTest {

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @Timeout(5)
    void syncPreservesTheNextCycleWhileItsReadyForQueryIsPending(boolean owned, boolean syncBeforeError)
            throws InterruptedException {
        ExtendedQuerySession session = new ExtendedQuerySession();
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);
        BackendResponseCoordinator.Ticket failed = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);
        if (syncBeforeError) {
            coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);
        }
        if (owned) {
            coordinator.completeOwnedExecute(failed, true);
        } else {
            coordinator.onBackendFrame('E', new byte[0]);
        }
        if (!syncBeforeError) {
            BackendResponseCoordinator.Ticket skipped = coordinator.register(
                    BackendResponseCoordinator.Operation.EXECUTE, null);
            assertEquals(BackendResponseCoordinator.GateResult.SKIPPED_AFTER_ERROR,
                    coordinator.awaitTurn(skipped));
            coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);
        }

        CopyStatementParser.S3Statement nextCopy = CopyStatementParser.parse("COPY t FROM 's3://b/next'");
        coordinator.register(BackendResponseCoordinator.Operation.PARSE, session.stageParse("s", nextCopy));
        coordinator.register(BackendResponseCoordinator.Operation.BIND, session.stageBind("p", "s"));
        BackendResponseCoordinator.Ticket next = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);

        assertSame(nextCopy, session.portal("p").orElseThrow(),
                "A Sync already sent to the backend starts a new frontend query cycle");
        assertFalse(coordinator.isDiscardingUntilSync());
        assertFalse(coordinator.canOwnExtendedExecute(next),
                "The new Execute must still wait for the previous Sync response");
        assertFalse(coordinator.awaitIdle(1, TimeUnit.MILLISECONDS));

        coordinator.onBackendFrame('Z', new byte[]{'I'});
        assertSame(nextCopy, session.portal("p").orElseThrow());
        coordinator.onBackendFrame('1', new byte[0]);
        coordinator.onBackendFrame('2', new byte[0]);
        assertEquals(BackendResponseCoordinator.GateResult.READY,
                coordinator.awaitExtendedExecuteTurn(next));
        coordinator.completeOwnedExecute(next, false);
        coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);
        coordinator.onBackendFrame('Z', new byte[]{'I'});
        assertTrue(coordinator.awaitIdle(1, TimeUnit.SECONDS));
    }
}
