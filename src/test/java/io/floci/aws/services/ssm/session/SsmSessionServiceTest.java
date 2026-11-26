package io.floci.aws.services.ssm.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.ssm.model.SsmSession;
import io.floci.aws.services.ssm.session.SessionDocumentResolver.ResolvedDocument;
import io.floci.aws.services.ssmmessages.SsmMessageGateway;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.Node;
import io.floci.aws.testing.MutableClock;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class SsmSessionServiceTest {
    @ParameterizedTest
    @CsvSource({
            "us-east-1, http://ssm.us-east-1.amazonaws.com, wss://ssmmessages.us-east-1.amazonaws.com",
            "cn-north-1, http://ssm.cn-north-1.amazonaws.com.cn, wss://ssmmessages.cn-north-1.amazonaws.com.cn",
            "us-gov-west-1, https://ssm.us-gov-west-1.amazonaws.com, wss://ssmmessages.us-gov-west-1.amazonaws.com",
            "us-east-1, http://127.0.0.1:4566, ws://127.0.0.1:4566",
            "us-east-1, https://floci.example:8443, wss://floci.example:8443"
    })
    void sessionStreamUsesRegionalMessagesOrTheAddressedLocalEndpoint(String region, String requestUri,
                                                                     String streamBase) {
        String account = "123456789012";
        String target = "i-fedcba98765432100";
        SsmMessageGateway gateway = mock(SsmMessageGateway.class);
        SessionDocumentResolver documents = mock(SessionDocumentResolver.class);
        when(gateway.isConnected(new Node(account, region, target))).thenReturn(true);
        when(gateway.startSession(any(), any())).thenReturn("token");
        when(documents.resolve(region, null, Map.of())).thenReturn(new ResolvedDocument(
                "SSM-SessionManagerRunShell", "unused", new ObjectMapper().createObjectNode(), Map.of()));
        SsmSessionService service = new SsmSessionService(AccountAwareStorageBackend.inMemory(account), gateway,
                documents, new RegionResolver(region, account), mock(RequestContext.class), mock(IamService.class),
                Clock.systemUTC(), URI.create("http://localhost:4566"));

        SsmSessionService.SessionConnection connection = service.start(region, target, null, null, Map.of(),
                URI.create(requestUri));

        assertEquals(streamBase + "/v1/data-channel/" + connection.sessionId() + "?role=publish_subscribe",
                connection.streamUrl());
    }

    @Test
    void resolvedCallerInAnotherAccountCannotFallBackToRoot() {
        String account = "123456789012";
        String region = "us-east-1";
        String target = "i-fedcba98765432100";
        RequestContext context = mock(RequestContext.class);
        IamService iam = mock(IamService.class);
        SsmMessageGateway gateway = mock(SsmMessageGateway.class);
        when(context.getAccessKeyId()).thenReturn("foreign-user-key");
        when(iam.resolveCallerArn("foreign-user-key"))
                .thenReturn(Optional.of("arn:aws:iam::999999999999:user/foreign"));
        when(gateway.isConnected(new Node(account, region, target))).thenReturn(true);
        SsmSessionService service = new SsmSessionService(AccountAwareStorageBackend.inMemory(account), gateway,
                mock(SessionDocumentResolver.class), new RegionResolver(region, account), context, iam,
                Clock.systemUTC(), URI.create("http://localhost:4566"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.start(region, target, null, null, Map.of(), null));

        assertEquals("AccessDeniedException", error.getErrorCode());
        assertTrue(service.describe(region, "Active", List.of(), null, null).items().isEmpty());
        assertTrue(service.describe(region, "History", List.of(), null, null).items().isEmpty());
    }

    @Test
    void startingASessionDeletesHistoryOlderThanItsRetention() {
        String account = "123456789012";
        String region = "us-east-1";
        String target = "i-fedcba98765432100";
        RequestContext context = mock(RequestContext.class);
        IamService iam = mock(IamService.class);
        SsmMessageGateway gateway = mock(SsmMessageGateway.class);
        SessionDocumentResolver documents = mock(SessionDocumentResolver.class);
        when(context.getAccessKeyId()).thenReturn("operator-key");
        when(iam.resolveCallerArn("operator-key")).thenReturn(Optional.of("arn:aws:iam::123456789012:user/operator"));
        when(gateway.isConnected(new Node(account, region, target))).thenReturn(true);
        when(gateway.startSession(any(), any())).thenReturn("token");
        when(documents.resolve(region, null, Map.of())).thenReturn(new ResolvedDocument(
                "SSM-SessionManagerRunShell", "arn:aws:ssm:us-east-1:123456789012:document/SSM-SessionManagerRunShell",
                new ObjectMapper().createObjectNode(), Map.of()));
        AccountAwareStorageBackend<SsmSession> storage = AccountAwareStorageBackend.inMemory(account);
        MutableClock clock = new MutableClock();
        SsmSessionService service = new SsmSessionService(storage, gateway, documents,
                new RegionResolver(region, account), context, iam, clock, URI.create("http://localhost:4566"));

        String first = service.start(region, target, null, null, Map.of(), null).sessionId();
        service.terminate(region, first);
        clock.advance(Duration.ofDays(29));
        String second = service.start(region, target, null, null, Map.of(), null).sessionId();
        assertEquals(2, storage.keys().size(), "a session that ended 29 days ago is still History");

        clock.advance(Duration.ofDays(2));
        String third = service.start(region, target, null, null, Map.of(), null).sessionId();

        assertEquals(Set.of(region + "::" + second, region + "::" + third), storage.keys());
    }
    @Test
    void historyReadsBothPersistedKeyFormatsWithoutDiscardingMetadata() {
        String account = "123456789012";
        String region = "us-east-1";
        Instant now = Instant.parse("2026-11-26T00:00:00Z");
        AccountAwareStorageBackend<SsmSession> storage = AccountAwareStorageBackend.inMemory(account);
        SsmSession old = new SsmSession("dev-session", "mi-0123456789abcdef0", "operator",
                "custom-session", "old history", SsmMessageGateway.SessionState.TERMINATED,
                now.minusSeconds(600), now.minusSeconds(300));
        old.setAccountId(account);
        old.setRegion(region);
        old.setMaxSessionDuration("45");
        SsmSession upstream = new SsmSession("upstream-session", "i-0123456789abcdef0", "operator",
                "AWS-StartSSHSession", "upstream history", SsmMessageGateway.SessionState.TERMINATED,
                now.minusSeconds(500), now.minusSeconds(200));
        storage.put(region + "::dev-session", old);
        storage.put(region + "|upstream-session", upstream);
        storage.put("us-west-2::other-region", old);
        SsmSessionService service = new SsmSessionService(storage, mock(SsmMessageGateway.class),
                mock(SessionDocumentResolver.class), new RegionResolver(region, account),
                mock(RequestContext.class), mock(IamService.class), Clock.fixed(now, ZoneOffset.UTC),
                URI.create("http://localhost:4566"));

        List<SsmSession> history = service.describe(region, "History", List.of(), null, null).items();
        assertEquals(Set.of("dev-session", "upstream-session"),
                history.stream().map(SsmSession::sessionId).collect(Collectors.toSet()));
        SsmSession retained = history.stream().filter(row -> row.sessionId().equals("dev-session"))
                .findFirst().orElseThrow();
        assertSame(old, retained);
        assertEquals("45", retained.getMaxSessionDuration());
        assertEquals("old history", retained.reason());
        assertEquals(3, storage.keys().size(), "Reading history must not rewrite or delete existing rows");
    }

    @Test
    void restartClosesRecoveredSessionsInBothKeyFormatsAndPreservesTheirMetadata() {
        String account = "123456789012";
        String region = "us-east-1";
        Instant now = Instant.parse("2026-11-26T00:00:00Z");
        AccountAwareStorageBackend<SsmSession> storage = AccountAwareStorageBackend.inMemory(account);
        for (String key : List.of(region + "::dev-session", region + "|upstream-session")) {
            SsmSession old = new SsmSession(key, "mi-0123456789abcdef0", "operator",
                    "custom-session", "recovered", SsmMessageGateway.SessionState.CONNECTED,
                    now.minusSeconds(600), null);
            old.setStatus("Connecting");
            old.setAccountId(account);
            old.setRegion(region);
            old.setMaxSessionDuration("45");
            storage.put(key, old);
        }
        SsmSessionService service = new SsmSessionService(storage, mock(SsmMessageGateway.class),
                mock(SessionDocumentResolver.class), new RegionResolver(region, account),
                mock(RequestContext.class), mock(IamService.class), Clock.fixed(now, ZoneOffset.UTC),
                URI.create("http://localhost:4566"));

        service.closeRecoveredSessions();

        assertTrue(service.describe(region, "Active", List.of(), null, null).items().isEmpty());
        List<SsmSession> history = service.describe(region, "History", List.of(), null, null).items();
        assertEquals(2, history.size());
        for (SsmSession row : history) {
            assertEquals("Failed", row.status());
            assertEquals(now, row.endDate());
            assertEquals(account, row.getAccountId());
            assertEquals(region, row.getRegion());
            assertEquals("45", row.getMaxSessionDuration());
            assertEquals("recovered", row.reason());
        }
    }

}
