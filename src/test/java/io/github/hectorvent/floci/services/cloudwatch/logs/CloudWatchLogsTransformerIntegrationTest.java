package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.GetQueryResultsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.GetTransformerResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.InvalidParameterException;
import software.amazon.awssdk.services.cloudwatchlogs.model.OutputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.ParseJSON;
import software.amazon.awssdk.services.cloudwatchlogs.model.Processor;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResultField;
import software.amazon.awssdk.services.cloudwatchlogs.model.TransformedLogRecord;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudWatchLogsTransformerIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "111111111111";

    @Test
    void nativeSdkPreservesOriginalsAndEachIngestionTimeConfiguration() throws Exception {
        String group = "/transform/lifecycle-" + UUID.randomUUID();
        long time = System.currentTimeMillis();
        List<String> originals = new ArrayList<>();
        try (CloudWatchLogsClient client = client(Region.US_EAST_1, ACCOUNT)) {
            client.createLogGroup(request -> request.logGroupName(group));
            try {
                client.createLogStream(request -> request.logGroupName(group).logStreamName("owned"));
                emit(client, group, time, originals, "{\"phase\":\"before\",\"native\":{\"label\":\"positive\"}}");
                client.putTransformer(request -> request.logGroupIdentifier(group).transformerConfig(processor("parsed")));
                GetTransformerResponse first = client.getTransformer(request -> request.logGroupIdentifier(group));
                assertEquals("arn:aws:logs:us-east-1:" + ACCOUNT + ":log-group:" + group, first.logGroupIdentifier());
                assertTrue(first.creationTime() >= time);
                assertEquals(first.creationTime(), first.lastModifiedTime());
                assertNull(first.transformerConfig().getFirst().parseJSON().source());
                assertEquals("parsed", first.transformerConfig().getFirst().parseJSON().destination());
                String input = "{\"phase\":\"configured\",\"n\":9007199254740993,\"decimal\":1.234567890123456789,"
                        + "\"nested\":{\"list\":[true,null,\"雪\\\\\\\"value\"]}}";
                TransformedLogRecord preview = client.testTransformer(request -> request
                        .transformerConfig(processor("parsed")).logEventMessages(input)).transformedLogs().getFirst();
                assertEquals(input, preview.eventMessage());
                JsonNode transformed = MAPPER.readTree(preview.transformedEventMessage());
                assertEquals(9007199254740993L, transformed.path("parsed").path("n").longValue());
                assertTrue(preview.transformedEventMessage().contains("1.234567890123456789"));
                assertEquals(MAPPER.readTree(input), transformed.get("parsed"));
                emit(client, group, time, originals, input);

                Processor replacement = Processor.builder().parseJSON(ParseJSON.builder()
                        .source("@message").destination("changed").build()).build();
                client.putTransformer(request -> request.logGroupIdentifier(first.logGroupIdentifier())
                        .transformerConfig(replacement));
                GetTransformerResponse updated = client.getTransformer(request -> request.logGroupIdentifier(group));
                assertEquals(first.creationTime(), updated.creationTime());
                assertTrue(updated.lastModifiedTime() >= first.lastModifiedTime());
                assertEquals(List.of(replacement), updated.transformerConfig());
                emit(client, group, time, originals, "{\"phase\":\"updated\"}");
                assertThrows(InvalidParameterException.class, () -> client.putTransformer(request -> request
                        .logGroupIdentifier(group).transformerConfig(processor("bad..path"))));
                GetTransformerResponse unchanged = client.getTransformer(request -> request.logGroupIdentifier(group));
                assertEquals(updated.lastModifiedTime(), unchanged.lastModifiedTime());
                assertEquals(updated.transformerConfig(), unchanged.transformerConfig());
                emit(client, group, time, originals, "not JSON");
                client.deleteTransformer(request -> request.logGroupIdentifier(group));
                GetTransformerResponse deleted = client.getTransformer(request -> request.logGroupIdentifier(group));
                assertTrue(deleted.transformerConfig().isEmpty());
                assertNull(deleted.creationTime());
                emit(client, group, time, originals, "{\"phase\":\"after\"}");

                assertEquals(originals, client.getLogEvents(request -> request.logGroupName(group)
                        .logStreamName("owned").startFromHead(true)).events().stream()
                        .map(OutputLogEvent::message).toList());
                assertEquals(originals, client.filterLogEvents(request -> request.logGroupName(group)).events().stream()
                        .map(event -> event.message()).toList());
                List<Map<String, String>> rows = query(client, group, time,
                        "fields @message, native.label, parsed.phase, changed.phase, @transformationError"
                                + " | sort @timestamp asc");
                assertEquals(5, rows.size());
                assertEquals("positive", rows.get(0).get("native.label"));
                assertTrue(rows.get(0).getOrDefault("parsed.phase", "").isEmpty());
                assertEquals("configured", rows.get(1).get("parsed.phase"));
                assertTrue(rows.get(1).getOrDefault("changed.phase", "").isEmpty());
                assertEquals("updated", rows.get(2).get("changed.phase"));
                assertTrue(rows.get(2).getOrDefault("parsed.phase", "").isEmpty());
                assertEquals("not JSON", rows.get(3).get("@message"));
                assertTrue(rows.get(3).get("@transformationError").contains("parseJSON"));
                assertTrue(rows.get(3).getOrDefault("changed.phase", "").isEmpty());
                assertTrue(rows.get(4).getOrDefault("parsed.phase", "").isEmpty());
                assertTrue(rows.get(4).getOrDefault("changed.phase", "").isEmpty());
                assertEquals(originals, rows.stream().map(row -> row.get("@message")).toList());
            } finally {
                client.deleteLogGroup(request -> request.logGroupName(group));
            }
            assertThrows(ResourceNotFoundException.class,
                    () -> client.getTransformer(request -> request.logGroupIdentifier(group)));
            client.createLogGroup(request -> request.logGroupName(group));
            try {
                assertTrue(client.getTransformer(request -> request.logGroupIdentifier(group)).transformerConfig().isEmpty());
            } finally {
                client.deleteLogGroup(request -> request.logGroupName(group));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "cn-north-1,aws-cn", "us-gov-west-1,aws-us-gov"})
    void nativeIdentifiersUseTheCredentialAccountAndRegionPartition(String regionName, String partition) {
        Region region = Region.of(regionName);
        String group = "/transform/owner-" + UUID.randomUUID();
        try (CloudWatchLogsClient owner = client(region, ACCOUNT);
                CloudWatchLogsClient foreign = client(region, "222222222222");
                CloudWatchLogsClient otherRegion = client(Region.EU_WEST_1, ACCOUNT)) {
            owner.createLogGroup(request -> request.logGroupName(group));
            foreign.createLogGroup(request -> request.logGroupName(group));
            try {
                owner.putTransformer(request -> request.logGroupIdentifier(group).transformerConfig(processor(null)));
                String arn = "arn:" + partition + ":logs:" + regionName + ":" + ACCOUNT + ":log-group:" + group;
                GetTransformerResponse read = owner.getTransformer(request -> request.logGroupIdentifier(arn));
                assertEquals(arn, read.logGroupIdentifier());
                assertEquals(List.of(processor(null)), read.transformerConfig());
                assertTrue(foreign.getTransformer(request -> request.logGroupIdentifier(group)).transformerConfig().isEmpty());
                assertThrows(ResourceNotFoundException.class,
                        () -> foreign.getTransformer(request -> request.logGroupIdentifier(arn)));
                assertThrows(ResourceNotFoundException.class,
                        () -> otherRegion.deleteTransformer(request -> request.logGroupIdentifier(arn)));
                assertEquals(read.transformerConfig(),
                        owner.getTransformer(request -> request.logGroupIdentifier(group)).transformerConfig());
            } finally {
                owner.deleteLogGroup(request -> request.logGroupName(group));
                foreign.deleteLogGroup(request -> request.logGroupName(group));
            }
        }
    }

    @Test
    void fieldLimitRefusesPreviewAndRetainsOriginalIngestionWithAnError() throws Exception {
        String group = "/transform/fields-" + UUID.randomUUID();
        long time = System.currentTimeMillis();
        ObjectNode object = MAPPER.createObjectNode();
        for (int i = 0; i < 200; i++) {
            object.put("field" + i, i);
        }
        String accepted = object.toString();
        String rejected = object.put("field200", 200).toString();
        try (CloudWatchLogsClient client = client(Region.US_EAST_1, ACCOUNT)) {
            TransformedLogRecord preview = client.testTransformer(request -> request
                    .transformerConfig(processor("parsed")).logEventMessages(accepted)).transformedLogs().getFirst();
            assertEquals(200, MAPPER.readTree(preview.transformedEventMessage()).path("parsed").size());
            assertThrows(InvalidParameterException.class, () -> client.testTransformer(request -> request
                    .transformerConfig(processor("parsed")).logEventMessages(rejected)));
            client.createLogGroup(request -> request.logGroupName(group));
            try {
                client.createLogStream(request -> request.logGroupName(group).logStreamName("owned"));
                client.putTransformer(request -> request.logGroupIdentifier(group).transformerConfig(processor("parsed")));
                List<String> originals = new ArrayList<>();
                emit(client, group, time, originals, accepted);
                emit(client, group, time, originals, rejected);
                assertEquals(originals, client.getLogEvents(request -> request.logGroupName(group)
                        .logStreamName("owned").startFromHead(true)).events().stream().map(OutputLogEvent::message).toList());
                List<Map<String, String>> rows = query(client, group, time,
                        "fields @message, parsed.field199, parsed.field200, @transformationError | sort @timestamp asc");
                assertEquals(2, rows.size());
                assertEquals("199", rows.getFirst().get("parsed.field199"));
                assertTrue(rows.getFirst().getOrDefault("@transformationError", "").isEmpty());
                assertEquals(rejected, rows.getLast().get("@message"));
                assertTrue(rows.getLast().getOrDefault("parsed.field199", "").isEmpty());
                assertTrue(rows.getLast().getOrDefault("parsed.field200", "").isEmpty());
                assertTrue(rows.getLast().get("@transformationError").contains("200"));
            } finally {
                client.deleteLogGroup(request -> request.logGroupName(group));
            }
        }
    }

    @Test
    void previewAndNonstandardGroupRejectUnsupportedInputsWithoutInventingResults() {
        String group = "/transform/infrequent-" + UUID.randomUUID();
        try (CloudWatchLogsClient client = client(Region.US_EAST_1, ACCOUNT)) {
            client.createLogGroup(request -> request.logGroupName(group).logGroupClass("INFREQUENT_ACCESS"));
            try {
                assertThrows(InvalidParameterException.class, () -> client.putTransformer(request -> request
                        .logGroupIdentifier(group).transformerConfig(processor(null))));
                assertTrue(client.getTransformer(request -> request.logGroupIdentifier(group)).transformerConfig().isEmpty());
            } finally {
                client.deleteLogGroup(request -> request.logGroupName(group));
            }
            assertThrows(InvalidParameterException.class, () -> client.testTransformer(request -> request
                    .transformerConfig(processor(null)).logEventMessages("malformed")));
            assertThrows(InvalidParameterException.class, () -> client.testTransformer(request -> request
                    .transformerConfig(processor(null), processor(null)).logEventMessages("{}")));
            assertThrows(InvalidParameterException.class, () -> client.testTransformer(request -> request
                    .transformerConfig(Processor.builder().parseJSON(ParseJSON.builder().source("field").build()).build())
                    .logEventMessages("{}")));
        }
    }

    static Processor processor(String destination) {
        return Processor.builder().parseJSON(ParseJSON.builder().destination(destination).build()).build();
    }

    static CloudWatchLogsClient client(Region region, String account) {
        return AwsTestClients.create(CloudWatchLogsClient.builder(), account, region);
    }

    private static void emit(CloudWatchLogsClient client, String group, long time, List<String> originals, String message) {
        client.putLogEvents(request -> request.logGroupName(group).logStreamName("owned")
                .logEvents(InputLogEvent.builder().timestamp(time + originals.size()).message(message).build()));
        originals.add(message);
    }

    static List<Map<String, String>> query(CloudWatchLogsClient client, String group, long time, String expression) {
        String id = client.startQuery(request -> request.logGroupName(group).startTime(time / 1000 - 60)
                .endTime(time / 1000 + 60).queryString(expression)).queryId();
        AtomicReference<GetQueryResultsResponse> completed = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            GetQueryResultsResponse result = client.getQueryResults(request -> request.queryId(id));
            assertEquals("Complete", result.statusAsString());
            completed.set(result);
        });
        List<Map<String, String>> rows = new ArrayList<>();
        for (List<ResultField> result : completed.get().results()) {
            Map<String, String> row = new LinkedHashMap<>();
            result.forEach(field -> row.put(field.field(), field.value()));
            rows.add(row);
        }
        return rows;
    }
}
