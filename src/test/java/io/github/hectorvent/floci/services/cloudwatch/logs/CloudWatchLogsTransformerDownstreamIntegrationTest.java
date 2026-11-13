package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.MetricTransformation;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import static io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsTransformerIntegrationTest.processor;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudWatchLogsTransformerDownstreamIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "111111111111";
    private static final Region REGION = Region.US_EAST_1;

    @Test
    void nativeClientsObserveTransformedMetricsAndRealKinesisDelivery() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String group = "/transform/downstream-" + suffix;
        String stream = "transform-" + suffix;
        String role = "transform-" + suffix;
        long timestamp = System.currentTimeMillis();
        try (CloudWatchLogsClient logs = AwsTestClients.create(CloudWatchLogsClient.builder(), ACCOUNT, REGION);
                CloudWatchClient metrics = AwsTestClients.create(CloudWatchClient.builder(), ACCOUNT, REGION);
                KinesisClient kinesis = AwsTestClients.create(KinesisClient.builder(), ACCOUNT, REGION);
                IamClient iam = AwsTestClients.create(IamClient.builder(), ACCOUNT, REGION)) {
            kinesis.createStream(request -> request.streamName(stream).shardCount(1));
            String destination = kinesis.describeStreamSummary(request -> request.streamName(stream))
                    .streamDescriptionSummary().streamARN();
            String roleArn = iam.createRole(request -> request.roleName(role).assumeRolePolicyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"logs.amazonaws.com"},
                    "Action":"sts:AssumeRole"}]}
                    """)).role().arn();
            iam.putRolePolicy(request -> request.roleName(role).policyName("delivery").policyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["kinesis:PutRecord","kinesis:PutRecords"],
                    "Resource":"%s"}]}
                    """.formatted(destination)));
            logs.createLogGroup(request -> request.logGroupName(group));
            try {
                logs.createLogStream(request -> request.logGroupName(group).logStreamName("owned"));
                logs.putTransformer(request -> request.logGroupIdentifier(group).transformerConfig(processor("parsed")));
                logs.putMetricFilter(request -> request.logGroupName(group).filterName("transformed")
                        .filterPattern("{ $.parsed.kind = \"MATCH\" }").applyOnTransformedLogs(true)
                        .metricTransformations(MetricTransformation.builder().metricNamespace(group)
                                .metricName("Transformed").metricValue("$.parsed.value").build()));
                logs.putMetricFilter(request -> request.logGroupName(group).filterName("original")
                        .filterPattern("{ $.kind = \"MATCH\" }").applyOnTransformedLogs(false)
                        .metricTransformations(MetricTransformation.builder().metricNamespace(group)
                                .metricName("Original").metricValue("$.value").build()));
                logs.putMetricFilter(request -> request.logGroupName(group).filterName("wrong-view")
                        .filterPattern("{ $.parsed.kind = \"MATCH\" }").applyOnTransformedLogs(false)
                        .metricTransformations(MetricTransformation.builder().metricNamespace(group)
                                .metricName("WrongView").metricValue("$.parsed.value").build()));
                logs.putSubscriptionFilter(request -> request.logGroupName(group).filterName("transformed")
                        .destinationArn(destination).roleArn(roleArn).applyOnTransformedLogs(true)
                        .filterPattern("{ $.parsed.kind = \"MATCH\" }"));
                assertEquals(true, logs.describeSubscriptionFilters(request -> request.logGroupName(group))
                        .subscriptionFilters().getFirst().applyOnTransformedLogs());

                String raw = "{\"kind\":\"MATCH\",\"value\":7,\"detail\":{\"message\":\"owned\"}}";
                logs.putLogEvents(request -> request.logGroupName(group).logStreamName("owned").logEvents(
                        event(timestamp, raw), event(timestamp + 1, "not JSON"),
                        event(timestamp + 2, "{\"kind\":\"OTHER\",\"value\":99}")));
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertEquals(7, sum(metrics, group, "Transformed", timestamp));
                    assertEquals(7, sum(metrics, group, "Original", timestamp));
                });
                assertEquals(0, sum(metrics, group, "WrongView", timestamp));
                String iterator = kinesis.getShardIterator(request -> request.streamName(stream)
                        .shardId("shardId-000000000000").shardIteratorType("TRIM_HORIZON")).shardIterator();
                AtomicReference<GetRecordsResponse> delivered = new AtomicReference<>();
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    GetRecordsResponse result = kinesis.getRecords(request -> request.shardIterator(iterator));
                    assertEquals(1, result.records().size());
                    delivered.set(result);
                });
                JsonNode payload = decode(delivered.get().records().getFirst().data().asByteArray());
                assertEquals("DATA_MESSAGE", payload.path("messageType").asText());
                assertEquals(group, payload.path("logGroup").asText());
                assertEquals("owned", payload.path("logStream").asText());
                assertEquals(1, payload.path("logEvents").size());
                JsonNode sent = payload.path("logEvents").get(0);
                assertEquals(timestamp, sent.path("timestamp").longValue());
                assertFalse(sent.path("id").asText().isEmpty());
                assertEquals(MAPPER.readTree(raw),
                        MAPPER.readTree(sent.path("message").asText()).path("parsed"));
                assertEquals(List.of(raw, "not JSON", "{\"kind\":\"OTHER\",\"value\":99}"),
                        logs.getLogEvents(request -> request.logGroupName(group).logStreamName("owned")
                                .startFromHead(true)).events().stream().map(value -> value.message()).toList());

                logs.deleteTransformer(request -> request.logGroupIdentifier(group));
                logs.putLogEvents(request -> request.logGroupName(group).logStreamName("owned")
                        .logEvents(event(timestamp + 3, "{\"kind\":\"MATCH\",\"value\":2}")));
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertEquals(9, sum(metrics, group, "Original", timestamp)));
                assertEquals(7, sum(metrics, group, "Transformed", timestamp));
                assertEquals(1, kinesis.getRecords(request -> request.shardIterator(iterator)).records().size());
            } finally {
                logs.deleteLogGroup(request -> request.logGroupName(group));
                kinesis.deleteStream(request -> request.streamName(stream));
                iam.deleteRolePolicy(request -> request.roleName(role).policyName("delivery"));
                iam.deleteRole(request -> request.roleName(role));
            }
        }
    }

    private static InputLogEvent event(long timestamp, String message) {
        return InputLogEvent.builder().timestamp(timestamp).message(message).build();
    }

    private static double sum(CloudWatchClient metrics, String namespace, String name, long time) {
        GetMetricStatisticsResponse response = metrics.getMetricStatistics(request -> request
                .namespace(namespace).metricName(name).period(60).statisticsWithStrings("Sum")
                .startTime(Instant.ofEpochMilli(time).minusSeconds(60))
                .endTime(Instant.ofEpochMilli(time).plusSeconds(60)));
        return response.datapoints().stream().mapToDouble(point -> point.sum()).sum();
    }

    private static JsonNode decode(byte[] bytes) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return MAPPER.readTree(gzip);
        }
    }
}
