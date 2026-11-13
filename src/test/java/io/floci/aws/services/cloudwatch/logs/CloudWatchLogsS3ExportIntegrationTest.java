package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateExportTaskRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.ExportTask;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudWatchLogsS3ExportIntegrationTest {
    private static final String ACCOUNT = "246802468024";
    private static final Region REGION = Region.US_EAST_1;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED", "FAILED");

    @Test
    void allEventsProduceRealGzipAndLeaveTheSourceUnchanged() throws IOException {
        try (Fixture fixture = new Fixture()) {
            fixture.export(null, fixture.start, fixture.start + 1_000, fixture.prefix, fixture.events);
        }
    }

    @Test
    void streamPrefixSelectsOnlyMatchingNativeStreams() throws IOException {
        try (Fixture fixture = new Fixture()) {
            fixture.export("selected-", fixture.start, fixture.start + 1_000,
                    fixture.prefix, fixture.events.subList(0, 2));
        }
    }

    @Test
    void interiorTimeWindowSelectsOnlyTheExpectedEvents() throws IOException {
        try (Fixture fixture = new Fixture()) {
            fixture.export(null, fixture.start + 150, fixture.start + 450,
                    fixture.prefix, fixture.events.subList(1, 3));
        }
    }

    @Test
    void emptySelectionCompletesWithoutCreatingAnObject() throws IOException {
        try (Fixture fixture = new Fixture()) {
            fixture.export(null, fixture.start + 500, fixture.start + 900, fixture.prefix, List.of());
        }
    }

    @Test
    void omittedDestinationPrefixUsesTheDocumentedDefault() throws IOException {
        try (Fixture fixture = new Fixture()) {
            fixture.export(null, fixture.start, fixture.start + 1_000, null, fixture.events);
        }
    }

    @Test
    void completedTaskMetadataIsStableAndTerminalCancellationIsRefused() throws IOException {
        try (Fixture fixture = new Fixture()) {
            ExportTask task = fixture.export(null, fixture.start + 500, fixture.start + 900,
                    fixture.prefix, List.of());
            assertEquals(task, fixture.task(task.taskId()));
            CloudWatchLogsException error = assertThrows(CloudWatchLogsException.class,
                    () -> fixture.logs.cancelExportTask(request -> request.taskId(task.taskId())));
            assertEquals("InvalidOperationException", error.awsErrorDetails().errorCode());
            assertEquals(400, error.statusCode());
            assertEquals(task, fixture.task(task.taskId()));
        }
    }

    @Test
    void unknownTaskIdReturnsAnEmptyDescribeResult() {
        try (CloudWatchLogsClient logs = logs()) {
            assertTrue(logs.describeExportTasks(request -> request.taskId(UUID.randomUUID().toString()))
                    .exportTasks().isEmpty());
        }
    }

    @Test
    void unknownTaskCancellationUsesTheModeledMissingResourceError() {
        try (CloudWatchLogsClient logs = logs()) {
            CloudWatchLogsException error = assertThrows(CloudWatchLogsException.class,
                    () -> logs.cancelExportTask(request -> request.taskId(UUID.randomUUID().toString())));
            assertEquals("ResourceNotFoundException", error.awsErrorDetails().errorCode());
            assertEquals(400, error.statusCode());
        }
    }

    private static CloudWatchLogsClient logs() {
        return AwsTestClients.create(CloudWatchLogsClient.builder(), ACCOUNT, REGION);
    }

    private record Event(long timestamp, String message) {
    }

    private static final class Fixture implements AutoCloseable {
        private final String marker = UUID.randomUUID().toString();
        private final String groupName = "/audit/logs-export/" + marker;
        private final String bucketName = "logs-export-" + marker;
        private final String prefix = "owned/" + marker;
        private final String taskName = "export-" + marker;
        private final CloudWatchLogsClient logs = logs();
        private final S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), ACCOUNT, REGION);
        private final List<String> taskIds = new ArrayList<>();
        private final Set<String> settled = new HashSet<>();
        private final Set<String> outputPrefixes = new HashSet<>();
        private Bucket bucket;
        private LogGroup group;
        private long start;
        private List<Event> events;
        private List<FilteredLogEvent> sourceBefore;

        private Fixture() {
            try {
                s3.createBucket(request -> request.bucket(bucketName));
                bucket = currentBucket();
                s3.putBucketTagging(request -> request.bucket(bucketName).tagging(Tagging.builder()
                        .tagSet(Tag.builder().key("OwnedExport").value(marker).build()).build()));
                logs.createLogGroup(request -> request.logGroupName(groupName).tags(Map.of("OwnedExport", marker)));
                group = currentGroup();
                assertNotNull(group.creationTime());
                assertNotNull(group.arn());
                String sourceArn = group.arn().endsWith(":*")
                        ? group.arn().substring(0, group.arn().length() - 2) : group.arn();
                String bucketArn = "arn:aws:s3:::" + bucketName;
                Map<String, Object> source = Map.of(
                        "StringEquals", Map.of("aws:SourceAccount", ACCOUNT),
                        "ArnLike", Map.of("aws:SourceArn", List.of(sourceArn, sourceArn + ":*")));
                Map<String, Object> writeSource = Map.of(
                        "StringEquals", Map.of("aws:SourceAccount", ACCOUNT,
                                "s3:x-amz-acl", "bucket-owner-full-control"),
                        "ArnLike", Map.of("aws:SourceArn", List.of(sourceArn, sourceArn + ":*")));
                String policy = json(Map.of("Version", "2012-10-17", "Statement", List.of(
                        Map.of("Effect", "Allow", "Principal", Map.of("Service", "logs.us-east-1.amazonaws.com"),
                                "Action", "s3:GetBucketAcl", "Resource", bucketArn, "Condition", source),
                        Map.of("Effect", "Allow", "Principal", Map.of("Service", "logs.us-east-1.amazonaws.com"),
                                "Action", "s3:PutObject", "Resource", bucketArn + "/*", "Condition", writeSource))));
                s3.putBucketPolicy(request -> request.bucket(bucketName).policy(policy));
                assertEquals(policy, s3.getBucketPolicy(request -> request.bucket(bucketName)).policy());
                logs.createLogStream(request -> request.logGroupName(groupName).logStreamName("selected-one"));
                logs.createLogStream(request -> request.logGroupName(groupName).logStreamName("sibling-two"));
                start = Math.max(group.creationTime(), System.currentTimeMillis());
                events = List.of(new Event(start + 100, marker + " first"),
                        new Event(start + 200, marker + " second"),
                        new Event(start + 300, marker + " sibling"));
                logs.putLogEvents(request -> request.logGroupName(groupName).logStreamName("selected-one")
                        .logEvents(events.subList(0, 2).stream().map(event -> InputLogEvent.builder()
                                .timestamp(event.timestamp()).message(event.message()).build()).toList()));
                logs.putLogEvents(request -> request.logGroupName(groupName).logStreamName("sibling-two")
                        .logEvents(InputLogEvent.builder().timestamp(events.get(2).timestamp())
                                .message(events.get(2).message()).build()));
                await().atMost(Duration.ofSeconds(3)).until(() -> System.currentTimeMillis() > start + 350);
                sourceBefore = source();
                assertEquals(events, sourceBefore.stream().map(event -> new Event(event.timestamp(), event.message()))
                        .sorted(Comparator.comparingLong(Event::timestamp)).toList());
                assertTrue(objects().isEmpty());
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private ExportTask export(String streamPrefix, long from, long to, String requestedPrefix,
                                  List<Event> expected) throws IOException {
            String actualPrefix = requestedPrefix == null ? "exportedlogs" : requestedPrefix;
            outputPrefixes.add(actualPrefix + "/");
            String id = logs.createExportTask(CreateExportTaskRequest.builder().logGroupName(groupName)
                    .taskName(taskName).destination(bucketName).destinationPrefix(requestedPrefix)
                    .logStreamNamePrefix(streamPrefix).from(from).to(to).build()).taskId();
            assertNotNull(id);
            assertFalse(id.isBlank());
            taskIds.add(id);
            ExportTask completed = terminal(id);
            assertEquals("COMPLETED", completed.status().codeAsString(), completed.status().message());
            assertEquals(groupName, completed.logGroupName());
            assertEquals(bucketName, completed.destination());
            assertEquals(actualPrefix, completed.destinationPrefix());
            assertEquals(taskName, completed.taskName());
            assertEquals(from, completed.from());
            assertEquals(to, completed.to());
            assertNotNull(completed.executionInfo());
            assertNotNull(completed.executionInfo().creationTime());
            assertNotNull(completed.executionInfo().completionTime());
            assertTrue(completed.executionInfo().completionTime() >= completed.executionInfo().creationTime());
            List<S3Object> objects = objects();
            List<Event> decoded = new ArrayList<>();
            for (S3Object object : objects) {
                assertTrue(object.key().startsWith(actualPrefix + "/"));
                assertTrue(object.key().endsWith(".gz"));
                ResponseBytes<GetObjectResponse> downloaded = s3.getObjectAsBytes(request ->
                        request.bucket(bucketName).key(object.key()));
                assertEquals(object.size(), downloaded.response().contentLength());
                assertEquals(object.eTag(), downloaded.response().eTag());
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(downloaded.asByteArray()))) {
                    String text = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
                    for (String line : text.lines().toList()) {
                        int separator = line.indexOf(' ');
                        assertTrue(separator > 0, line);
                        decoded.add(new Event(Instant.parse(line.substring(0, separator)).toEpochMilli(),
                                line.substring(separator + 1)));
                    }
                }
            }
            assertEquals(expected.stream().sorted(Comparator.comparingLong(Event::timestamp)).toList(),
                    decoded.stream().sorted(Comparator.comparingLong(Event::timestamp)).toList());
            assertEquals(expected.isEmpty(), objects.isEmpty());
            assertEquals(sourceBefore, source());
            assertEquals(completed, task(id));
            return completed;
        }

        private ExportTask task(String id) {
            List<ExportTask> tasks = logs.describeExportTasks(request -> request.taskId(id)).exportTasks();
            assertEquals(1, tasks.size());
            assertEquals(id, tasks.getFirst().taskId());
            return tasks.getFirst();
        }

        private ExportTask terminal(String id) {
            AtomicReference<ExportTask> result = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25)).untilAsserted(() -> {
                ExportTask current = task(id);
                assertTrue(TERMINAL.contains(current.status().codeAsString()), current.status().toString());
                result.set(current);
            });
            settled.add(id);
            return result.get();
        }

        private List<FilteredLogEvent> source() {
            List<FilteredLogEvent> result = new ArrayList<>();
            Set<String> tokens = new HashSet<>();
            String token = null;
            do {
                String current = token;
                FilterLogEventsResponse page = logs.filterLogEvents(request ->
                        request.logGroupName(groupName).nextToken(current));
                result.addAll(page.events());
                token = page.nextToken();
                if (token != null && !token.isEmpty()) {
                    assertTrue(tokens.add(token), "Source pagination must progress");
                    assertTrue(tokens.size() < 20, "Bounded fixture inventory must finish");
                }
            } while (token != null && !token.isEmpty());
            return result.stream().sorted(Comparator.comparing(FilteredLogEvent::eventId)).toList();
        }

        private List<S3Object> objects() {
            List<S3Object> result = new ArrayList<>();
            Set<String> tokens = new HashSet<>();
            String token = null;
            do {
                String current = token;
                ListObjectsV2Response page = s3.listObjectsV2(request ->
                        request.bucket(bucketName).continuationToken(current));
                result.addAll(page.contents());
                if (!page.isTruncated()) {
                    break;
                }
                token = page.nextContinuationToken();
                assertNotNull(token);
                assertTrue(tokens.add(token), "Object pagination must progress");
                assertTrue(tokens.size() < 20, "Bounded fixture inventory must finish");
            } while (true);
            return result;
        }

        private Bucket currentBucket() {
            return s3.listBuckets().buckets().stream().filter(value -> bucketName.equals(value.name()))
                    .findFirst().orElseThrow();
        }

        private LogGroup currentGroup() {
            return logs.describeLogGroups(request -> request.logGroupNamePrefix(groupName)).logGroups().stream()
                    .filter(value -> groupName.equals(value.logGroupName())).findFirst().orElseThrow();
        }

        private void verifyBucket() {
            Bucket current = currentBucket();
            assertEquals(bucket.name(), current.name());
            assertEquals(bucket.creationDate(), current.creationDate());
            assertTrue(s3.getBucketTagging(request -> request.bucket(bucketName)).tagSet().stream()
                    .anyMatch(tag -> "OwnedExport".equals(tag.key()) && marker.equals(tag.value())));
        }

        @Override
        public void close() {
            try {
                for (String id : taskIds) {
                    if (!settled.contains(id)) {
                        ExportTask current = task(id);
                        assertEquals(taskName, current.taskName());
                        assertEquals(groupName, current.logGroupName());
                        assertEquals(bucketName, current.destination());
                        if (!TERMINAL.contains(current.status().codeAsString())) {
                            try {
                                logs.cancelExportTask(request -> request.taskId(id));
                            } catch (CloudWatchLogsException finished) {
                                assertEquals("InvalidOperationException", finished.awsErrorDetails().errorCode());
                                assertTrue(TERMINAL.contains(task(id).status().codeAsString()));
                            }
                        }
                        terminal(id);
                    }
                }
                if (group != null) {
                    LogGroup current = currentGroup();
                    assertEquals(group.arn(), current.arn());
                    assertEquals(group.creationTime(), current.creationTime());
                    assertEquals(marker, logs.listTagsLogGroup(request -> request.logGroupName(groupName))
                            .tags().get("OwnedExport"));
                    logs.deleteLogGroup(request -> request.logGroupName(groupName));
                    assertTrue(logs.describeLogGroups(request -> request.logGroupNamePrefix(groupName)).logGroups()
                            .stream().noneMatch(value -> groupName.equals(value.logGroupName())));
                }
                if (bucket != null) {
                    verifyBucket();
                    for (S3Object object : objects()) {
                        assertTrue(outputPrefixes.stream().anyMatch(object.key()::startsWith),
                                "Refusing an object outside this fixture's export prefixes");
                        HeadObjectResponse current = s3.headObject(request -> request.bucket(bucketName).key(object.key()));
                        assertEquals(object.eTag(), current.eTag());
                        assertEquals(object.size(), current.contentLength());
                        s3.deleteObject(request -> request.bucket(bucketName).key(object.key()));
                    }
                    assertTrue(objects().isEmpty());
                    verifyBucket();
                    s3.deleteBucket(request -> request.bucket(bucketName));
                    assertEquals(404, assertThrows(S3Exception.class,
                            () -> s3.headBucket(request -> request.bucket(bucketName))).statusCode());
                }
            } finally {
                logs.close();
                s3.close();
            }
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
