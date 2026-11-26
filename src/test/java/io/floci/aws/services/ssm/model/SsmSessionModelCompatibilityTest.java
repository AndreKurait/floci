package io.floci.aws.services.ssm.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SsmSessionModelCompatibilityTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void devHistoryRetainsEveryStoredFieldThroughTransitionAndSerialization() throws Exception {
        SsmSession session = mapper.readValue("""
                {"sessionId":"old-session","accountId":"123456789012","region":"us-east-1",
                 "target":"mi-0123456789abcdef0","status":"Disconnected",
                 "startDate":"2026-11-25T12:00:00Z","endDate":null,"documentName":"custom-session",
                 "owner":"arn:aws:iam::123456789012:user/operator","reason":"diagnosis",
                 "maxSessionDuration":"45"}
                """, SsmSession.class);
        assertEquals("Disconnected", session.status());
        assertTrue(session.active());

        Instant end = Instant.parse("2026-11-25T12:30:00Z");
        SsmSession recovered = mapper.readValue(mapper.writeValueAsBytes(
                session.transition(SessionState.FAILED, end)), SsmSession.class);
        assertEquals("old-session", recovered.getSessionId());
        assertEquals("123456789012", recovered.getAccountId());
        assertEquals("us-east-1", recovered.getRegion());
        assertEquals("mi-0123456789abcdef0", recovered.getTarget());
        assertEquals("Failed", recovered.getStatus());
        assertEquals(Instant.parse("2026-11-25T12:00:00Z"), recovered.getStartDate());
        assertEquals(end, recovered.getEndDate());
        assertEquals("custom-session", recovered.getDocumentName());
        assertEquals("arn:aws:iam::123456789012:user/operator", recovered.getOwner());
        assertEquals("diagnosis", recovered.getReason());
        assertEquals("45", recovered.getMaxSessionDuration());
        assertFalse(recovered.active());
    }

    @ParameterizedTest
    @CsvSource({"CONNECTED,Connected", "DISCONNECTED,Disconnected", "TERMINATED,Terminated", "FAILED,Failed"})
    void upstreamEnumSnapshotsRemainReadable(String state, String status) throws Exception {
        SsmSession session = mapper.readValue("""
                {"sessionId":"upstream-session","target":"i-0123456789abcdef0",
                 "owner":"arn:aws:iam::123456789012:root","documentName":"AWS-StartSSHSession",
                 "reason":null,"state":"%s","startDate":"2026-11-25T12:00:00Z",
                 "endDate":"2026-11-25T12:30:00Z"}
                """.formatted(state), SsmSession.class);
        assertEquals(status, session.status());
        assertEquals(SessionState.valueOf(state), session.state());
        SsmSession reloaded = mapper.readValue(mapper.writeValueAsBytes(session), SsmSession.class);
        assertEquals(status, reloaded.status());
        assertEquals(session.startDate(), reloaded.startDate());
        assertEquals(session.endDate(), reloaded.endDate());
        assertEquals(session.target(), reloaded.target());
        assertEquals(session.documentName(), reloaded.documentName());
    }

    @ParameterizedTest
    @CsvSource({"Connecting", "Terminating"})
    void devIntermediateStatusesRemainExact(String status) throws Exception {
        SsmSession session = mapper.readValue("{\"status\":\"" + status + "\"}", SsmSession.class);
        assertEquals(status, session.status());
        assertEquals(SessionState.DISCONNECTED, session.state());
        assertEquals(status, mapper.readValue(mapper.writeValueAsBytes(session), SsmSession.class).status());
    }

    @Test
    void explicitStatusWinsWhenBothHistoricalPropertyNamesArePresent() throws Exception {
        for (String json : new String[] {
                "{\"status\":\"Connecting\",\"state\":\"CONNECTED\"}",
                "{\"state\":\"CONNECTED\",\"status\":\"Connecting\"}"}) {
            assertEquals("Connecting", mapper.readValue(json, SsmSession.class).status());
        }
    }
}
