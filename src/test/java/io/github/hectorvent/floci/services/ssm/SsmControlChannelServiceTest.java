package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ssm.SsmChannelAuthentication.Caller;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SsmControlChannelServiceTest {
    private static final String INSTANCE = "i-0123456789abcdef0";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Caller CALLER = new Caller("111111111111", "us-east-1", "access",
            "arn:aws:iam::111111111111:user/agent", "192.0.2.1", true);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SsmChannelAuthentication authentication = mock(SsmChannelAuthentication.class);
    private final Clock clock = mock(Clock.class);
    private SsmMessagesService service;

    @BeforeEach
    void createService() {
        when(clock.instant()).thenReturn(NOW);
        service = new SsmMessagesService(mock(SsmCommandService.class), authentication, mapper,
                mock(Vertx.class), clock);
    }

    @Test
    void aChannelTokenCanOnlyBeConsumedOnce() {
        String token = create();
        service.consumeToken(CALLER, INSTANCE, token);
        assertThrows(AwsException.class, () -> service.consumeToken(CALLER, INSTANCE, token));
        verify(authentication).authorize(CALLER, "CreateControlChannel");
        verify(authentication).authorize(CALLER, "OpenControlChannel");
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "region", "node", "principal"})
    void anUnrelatedCallerCannotConsumeTheOwnersToken(String changed) {
        String token = create();
        Caller wrong = new Caller(
                "account".equals(changed) ? "222222222222" : CALLER.account(),
                "region".equals(changed) ? "us-west-2" : CALLER.region(), CALLER.accessKey(),
                "principal".equals(changed) ? "arn:aws:iam::111111111111:user/other" : CALLER.arn(),
                CALLER.sourceIp(), CALLER.secureTransport());
        String node = "node".equals(changed) ? "i-fedcba98765432100" : INSTANCE;
        assertThrows(AwsException.class, () -> service.consumeToken(wrong, node, token));
        assertDoesNotThrow(() -> service.consumeToken(CALLER, INSTANCE, token));
    }

    @Test
    void tokenExpiresAtItsDeadline() {
        String token = create();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertThrows(AwsException.class, () -> service.consumeToken(CALLER, INSTANCE, token));
        verify(authentication, never()).authorize(any(), eq("OpenControlChannel"));
    }

    @Test
    void deniedOpenDoesNotConsumeTheToken() {
        String token = create();
        doThrow(SsmChannelAuthentication.denied("denied"))
                .when(authentication).authorize(CALLER, "OpenControlChannel");
        assertThrows(AwsException.class, () -> service.consumeToken(CALLER, INSTANCE, token));
        doNothing().when(authentication).authorize(CALLER, "OpenControlChannel");
        assertDoesNotThrow(() -> service.consumeToken(CALLER, INSTANCE, token));
    }

    @Test
    void resetInvalidatesIssuedTokens() {
        String token = create();
        service.clear();
        assertThrows(AwsException.class, () -> service.consumeToken(CALLER, INSTANCE, token));
    }

    private String create() {
        ObjectNode request = mapper.createObjectNode().put("MessageSchemaVersion", "1.0")
                .put("RequestId", UUID.randomUUID().toString());
        return service.create(CALLER, INSTANCE, request);
    }
}
