package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.batch.BatchSchedulingPolicyReader;
import io.floci.aws.services.batch.BatchSchedulingPolicyService;
import io.floci.aws.services.batch.BatchService;
import io.floci.aws.services.batch.BatchTagHandler;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BatchSchedulingPolicyTagDispatchTest {
    private static final String POLICY = "arn:aws:batch:us-east-1:000000000000:scheduling-policy/held";
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpHeaders headers = mock(HttpHeaders.class);
    private final RegionResolver regions = new RegionResolver("us-east-1", "000000000000");

    @Test
    void onlyPolicyOptInRefusesRawDuplicateDataAndKeepsOlderBatchCoercion() {
        BatchService old = mock(BatchService.class);
        BatchSchedulingPolicyService policies = mock(BatchSchedulingPolicyService.class);
        BatchTagHandler owner = new BatchTagHandler(old, policies, new BatchSchedulingPolicyReader(mapper));
        TagDispatcher dispatcher = new TagDispatcher(List.of(owner), regions, mapper);
        String queue = "arn:aws:batch:us-east-1:000000000000:job-queue/held";
        dispatcher.tagResourcePost(headers, queue, "{\"tags\":{\"n\":1,\"n\":2}}", Response.ok().build());
        verify(old).tagResource(queue, Map.of("n", "2"));
        assertThrows(AwsException.class, () -> dispatcher.tagResourcePost(headers, POLICY,
                "{\"tags\":{\"n\":\"a\",\"n\":\"b\"}}", Response.ok().build()));
        verifyNoInteractions(policies);
        dispatcher.tagResourcePost(headers, POLICY, "{\"tags\":{}}", Response.ok().build());
        verify(policies).tag(POLICY, Map.of());
        verifyNoMoreInteractions(old);
    }

    @Test
    void otherDefaultMapAndCapitalizedListOwnersKeepExistingParserShapes() {
        RecordingHandler map = new RecordingHandler("kafka", false, false);
        RecordingHandler list = new RecordingHandler("codeartifact", true, false);
        TagDispatcher dispatcher = new TagDispatcher(List.of(map, list), regions, mapper);
        dispatcher.tagResourcePost(headers, arn("kafka"), "{\"tags\":{\"x\":1}}", Response.ok().build());
        assertEquals(Map.of("x", "1"), map.seen);
        dispatcher.tagResourcePost(headers, arn("codeartifact"),
                "{\"Tags\":[{\"Key\":\"x\",\"Value\":1}]}", Response.ok().build());
        assertEquals(Map.of("x", "1"), list.seen);
        dispatcher.tagResourcePost(headers, arn("kafka"), null, Response.ok().build());
        assertEquals(Map.of(), map.seen);
    }

    @Test
    void methodAndUnknownOwnerAdmissionOccurBeforeRawHook() {
        RecordingHandler put = new RecordingHandler("apigateway", false, true);
        TagDispatcher dispatcher = new TagDispatcher(List.of(put), regions, mapper);
        assertEquals(405, assertThrows(AwsException.class, () -> dispatcher.tagResourcePost(
                headers, arn("apigateway"), "malformed", Response.ok().build())).getHttpStatus());
        assertThrows(AwsException.class, () -> dispatcher.tagResourcePost(
                headers, arn("unknown"), "malformed", Response.ok().build()));
        assertEquals(0, put.hookCalls);
        assertNull(put.seen);
    }

    private static String arn(String service) {
        return "arn:aws:" + service + ":us-east-1:000000000000:resource/example";
    }

    private static final class RecordingHandler implements TagHandler {
        private final String service;
        private final boolean list;
        private final boolean put;
        private int hookCalls;
        private Map<String, String> seen;

        private RecordingHandler(String service, boolean list, boolean put) {
            this.service = service;
            this.list = list;
            this.put = put;
        }

        public String serviceKey() {
            return service;
        }

        public String tagsBodyKey() {
            return list ? "Tags" : "tags";
        }

        public boolean tagsBodyIsList() {
            return list;
        }

        public boolean tagResourceUsesPut() {
            return put;
        }

        public Optional<Map<String, String>> parseTagResourceBody(String arn, String body) {
            hookCalls++;
            return Optional.empty();
        }

        public Map<String, String> listTags(String region, String arn) {
            return Map.of();
        }

        public void tagResource(String region, String arn, Map<String, String> tags) {
            seen = Map.copyOf(tags);
        }

        public void untagResource(String region, String arn, List<String> tagKeys) {}
    }
}
