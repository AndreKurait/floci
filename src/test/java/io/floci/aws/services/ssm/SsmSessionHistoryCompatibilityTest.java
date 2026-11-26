package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.services.ssm.model.SsmSession;
import io.floci.aws.services.ssm.session.SsmSessionService;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SsmSessionHistoryCompatibilityTest {
    @Test
    void describeSessionsPreservesDevDurationMetadataAndUpstreamOptionalFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SsmSession old = mapper.readValue("""
                {"sessionId":"dev-session","accountId":"123456789012","region":"us-east-1",
                 "target":"mi-0123456789abcdef0","status":"Terminated",
                 "startDate":"2026-11-25T12:00:00Z","endDate":"2026-11-25T12:30:00Z",
                 "documentName":"custom-session","owner":"operator","reason":"diagnosis",
                 "maxSessionDuration":"45"}
                """, SsmSession.class);
        SsmSession upstream = new SsmSession("upstream-session", "i-0123456789abcdef0", "operator",
                "AWS-StartSSHSession", null, SessionState.TERMINATED, old.startDate(), old.endDate());
        SsmSessionService sessions = mock(SsmSessionService.class);
        when(sessions.describe(eq("us-east-1"), eq("History"), anyList(), isNull(), isNull()))
                .thenReturn(new PaginatedResult<>(List.of(old, upstream), "next-page"));
        SsmSessionJsonHandler handler = new SsmSessionJsonHandler(sessions, mapper);

        ObjectNode response = (ObjectNode) handler.handle("DescribeSessions",
                mapper.createObjectNode().put("State", "History"), "us-east-1", null).getEntity();

        assertEquals("dev-session", response.path("Sessions").get(0).path("SessionId").asText());
        assertEquals("45", response.path("Sessions").get(0).path("MaxSessionDuration").asText());
        assertEquals("Terminated", response.path("Sessions").get(0).path("Status").asText());
        assertEquals("diagnosis", response.path("Sessions").get(0).path("Reason").asText());
        assertEquals("Standard", response.path("Sessions").get(0).path("AccessType").asText());
        assertEquals(Instant.parse("2026-11-25T12:00:00Z").getEpochSecond(),
                response.path("Sessions").get(0).path("StartDate").asLong());
        assertEquals(Instant.parse("2026-11-25T12:30:00Z").getEpochSecond(),
                response.path("Sessions").get(0).path("EndDate").asLong());
        assertEquals("upstream-session", response.path("Sessions").get(1).path("SessionId").asText());
        assertFalse(response.path("Sessions").get(1).has("MaxSessionDuration"));
        assertFalse(response.path("Sessions").get(1).has("Reason"));
        assertEquals("next-page", response.path("NextToken").asText());
    }
}
