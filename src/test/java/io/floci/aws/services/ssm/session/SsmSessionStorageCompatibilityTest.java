package io.floci.aws.services.ssm.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsmSessionStorageCompatibilityTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void readsPreviouslyPersistedGatewayState() throws Exception {
        SsmSession session = mapper.readValue("""
                {"sessionId":"operator-one","target":"i-0123456789abcdef0","owner":"operator",
                 "documentName":"SSM-SessionManagerRunShell","reason":"test","state":"DISCONNECTED",
                 "startDate":"2026-11-27T12:00:00Z","endDate":null}
                """, SsmSession.class);

        assertEquals(SessionState.DISCONNECTED, session.state());
        assertEquals("Disconnected", session.getStatus());
        assertEquals("operator-one", session.getSessionId());
        assertTrue(session.active());
        assertEquals(session, mapper.readValue(mapper.writeValueAsBytes(session), SsmSession.class));
    }

    @Test
    void readsPreviouslyPersistedAgentSessionWithoutLosingItsScopeOrDuration() throws Exception {
        SsmSession session = mapper.readValue("""
                {"sessionId":"operator-two","accountId":"111111111111","region":"us-west-2",
                 "target":"mi-0123456789abcdef0","status":"Connecting",
                 "startDate":"2026-11-27T12:00:00Z","endDate":null,
                 "documentName":"custom-session","owner":"operator","reason":"test",
                 "maxSessionDuration":"30"}
                """, SsmSession.class);

        assertEquals("Connecting", session.status());
        assertEquals("111111111111", session.getAccountId());
        assertEquals("us-west-2", session.getRegion());
        assertEquals("30", session.getMaxSessionDuration());
        assertEquals(session, mapper.readValue(mapper.writeValueAsBytes(session), SsmSession.class));
    }

    @Test
    void aGatewayTransitionRetainsAgentSessionMetadata() {
        SsmSession session = new SsmSession("operator-three", "i-0123456789abcdef0", "operator",
                "custom-session", "test", SessionState.CONNECTED, Instant.parse("2026-11-27T12:00:00Z"), null);
        session.setAccountId("111111111111");
        session.setRegion("us-west-2");
        session.setMaxSessionDuration("30");

        Instant ended = Instant.parse("2026-11-27T12:05:00Z");
        SsmSession terminated = session.transition(SessionState.TERMINATED, ended);

        assertFalse(terminated.active());
        assertEquals(ended, terminated.getEndDate());
        assertEquals("111111111111", terminated.getAccountId());
        assertEquals("us-west-2", terminated.getRegion());
        assertEquals("30", terminated.getMaxSessionDuration());
        assertTrue(session.active());
    }
}
