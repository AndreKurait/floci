package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepFunctionsExecutionHistoryPaginationTest {

    private static final String ARN = "arn:aws:states:us-east-2:000000000000:execution:history:run";
    private static final long NOW = 1_790_000_000_000L;
    private final ObjectMapper mapper = new ObjectMapper();
    private StepFunctionsJsonHandler handler;
    private Clock clock;

    @BeforeEach
    void setUp() {
        StepFunctionsService service = mock(StepFunctionsService.class);
        List<HistoryEvent> events = LongStream.rangeClosed(1, 132).mapToObj(id -> {
            HistoryEvent event = new HistoryEvent();
            event.setId(id);
            event.setTimestamp(10 + id * 0.001);
            event.setType("ExecutionStarted");
            event.setDetails(Map.of("input", "{}", "roleArn", "execution-role"));
            return event;
        }).toList();
        when(service.getExecutionHistory(ARN)).thenReturn(events);
        clock = mock(Clock.class);
        when(clock.millis()).thenReturn(NOW);
        handler = new StepFunctionsJsonHandler(service, mapper, clock);
    }

    @Test
    void omittedAndZeroUseDefaultPageSize() {
        assertEquals(100, history(request()).path("events").size());
        assertEquals(100, history(request().put("maxResults", 0)).path("events").size());
        assertEquals(132, history(request().put("maxResults", 1000)).path("events").size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void everyEventAppearsOnceAcrossPages(boolean reverse) {
        ObjectNode request = request().put("maxResults", 17).put("reverseOrder", reverse);
        List<Long> ids = new ArrayList<>();
        do {
            JsonNode response = history(request);
            response.path("events").forEach(event -> ids.add(event.path("id").asLong()));
            if (!response.has("nextToken")) {
                break;
            }
            request.put("nextToken", response.path("nextToken").asText());
        } while (true);
        List<Long> expected = LongStream.rangeClosed(1, 132)
                .map(id -> reverse ? 133 - id : id).boxed().toList();
        assertEquals(expected, ids);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 1000})
    void tokensPermitChangingPageSizeAndExecutionDataProjection(int pageSize) {
        String token = history(request().put("maxResults", 1)).path("nextToken").asText();
        JsonNode response = history(request().put("nextToken", token)
                .put("maxResults", pageSize).put("includeExecutionData", false));
        assertEquals(2, response.path("events").get(0).path("id").asLong());
        assertEquals(pageSize == 0 ? 100 : Math.min(pageSize, 131), response.path("events").size());
        JsonNode details = response.path("events").get(0).path("executionStartedEventDetails");
        assertFalse(details.has("input"));
        assertEquals("execution-role", details.path("roleArn").asText());
        JsonNode included = history(request().put("nextToken", token).put("includeExecutionData", true));
        assertEquals("{}", included.path("events").get(0)
                .path("executionStartedEventDetails").path("input").asText());
    }

    @Test
    void tokensRejectAnotherExecutionOrDirection() {
        String token = history(request().put("maxResults", 1)).path("nextToken").asText();
        assertError("InvalidToken", request().put("nextToken", token).put("reverseOrder", true));
        assertError("InvalidToken", request().put("nextToken", token).put("executionArn", ARN + "-other"));
    }

    @Test
    void malformedAndExpiredTokensAreRejected() {
        assertError("ValidationException", request().put("nextToken", ""));
        assertError("InvalidToken", request().put("nextToken", "not-a-token"));
        String token = history(request().put("maxResults", 1)).path("nextToken").asText();
        when(clock.millis()).thenReturn(NOW + Duration.ofHours(24).toMillis());
        assertEquals(2, history(request().put("nextToken", token))
                .path("events").get(0).path("id").asLong());
        when(clock.millis()).thenReturn(NOW + Duration.ofHours(24).toMillis() + 1);
        assertError("InvalidToken", request().put("nextToken", token));
    }

    @Test
    void tokensRejectForgedIssueTimesAndCursors() {
        String token = history(request().put("maxResults", 1)).path("nextToken").asText();
        String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        int signatureSeparator = decoded.lastIndexOf('.');
        String payload = decoded.substring(0, signatureSeparator);
        String signature = decoded.substring(signatureSeparator);
        assertError("InvalidToken", request().put("nextToken", encode(payload)));
        assertError("InvalidToken", request().put("nextToken", encode(payload + ".invalid")));
        String suffix = payload.substring(payload.indexOf(':'));
        String changedIssueTime = (clock.millis() + 1) + suffix;
        String changedCursor = payload.substring(0, payload.lastIndexOf(':') + 1) + "2";
        assertError("InvalidToken", request().put("nextToken", encode(changedIssueTime + signature)));
        assertError("InvalidToken", request().put("nextToken", encode(changedCursor + signature)));
        when(clock.millis()).thenReturn(NOW + Duration.ofHours(25).toMillis());
        String refreshedIssueTime = clock.millis() + suffix;
        assertError("InvalidToken", request().put("nextToken", encode(refreshedIssueTime + signature)));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 1001, 4294967296L})
    void invalidPageSizesAreRejected(long size) {
        assertError("ValidationException", request().put("maxResults", size));
    }

    private ObjectNode request() {
        return mapper.createObjectNode().put("executionArn", ARN);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode history(ObjectNode request) {
        return (JsonNode) handler.handle("GetExecutionHistory", request, "us-east-2").getEntity();
    }

    private void assertError(String code, ObjectNode request) {
        AwsException error = assertThrows(AwsException.class, () -> history(request));
        assertEquals(code, error.getErrorCode());
    }
}
