package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtendedQueryPortalBindingTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "portal"})
    void rebindToSameStatementSurvivesPriorTransactionEnd(String portalName) {
        ExtendedQuerySession session = new ExtendedQuerySession();
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);
        CopyStatementParser.S3Statement unload = preparedUnload(session);
        session.confirm(session.stageBind(portalName, "unload"));
        coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);

        ExtendedQuerySession.Mutation nextBind = session.stageBind(portalName, "unload");
        coordinator.register(BackendResponseCoordinator.Operation.BIND, nextBind);
        coordinator.onBackendFrame('Z', new byte[]{'I'});

        assertSame(unload, session.portal(portalName).orElse(null));
        coordinator.onBackendFrame('2', new byte[0]);
        session.transactionEnded();
        assertTrue(session.portal(portalName).isEmpty());
        assertSame(unload, session.statement("unload").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "portal"})
    void rejectedRebindDoesNotRestoreExpiredPriorPortal(String portalName) {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement unload = preparedUnload(session);
        session.confirm(session.stageBind(portalName, "unload"));
        ExtendedQuerySession.Mutation nextBind = session.stageBind(portalName, "unload");
        session.transactionEnded();
        assertSame(unload, session.portal(portalName).orElse(null));

        session.rejectFrom(nextBind);

        assertTrue(session.portal(portalName).isEmpty());
        assertSame(unload, session.statement("unload").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "portal"})
    void rejectingEarlierBindPreservesLaterBindToSameStatement(String portalName) {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement unload = preparedUnload(session);
        ExtendedQuerySession.Mutation first = session.stageBind(portalName, "unload");
        ExtendedQuerySession.Mutation second = session.stageBind(portalName, "unload");

        session.rejectFrom(first);

        assertSame(unload, session.portal(portalName).orElse(null));
        session.confirm(second);
        session.transactionEnded();
        assertTrue(session.portal(portalName).isEmpty());
    }

    private static CopyStatementParser.S3Statement preparedUnload(ExtendedQuerySession session) {
        CopyStatementParser.S3Statement unload = CopyStatementParser.parse(
                "UNLOAD ('SELECT 1') TO 's3://bucket/data/' FORMAT AS PARQUET");
        session.confirm(session.stageParse("unload", unload));
        return unload;
    }
}
