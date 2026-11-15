package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogExportTask;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogGroup;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogStream;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CloudWatchLogsExportSnapshotTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private final InMemoryStorage<String, LogGroup> rawGroups = new InMemoryStorage<>();
    private final AccountAwareStorageBackend<LogGroup> groups = new AccountAwareStorageBackend<>(rawGroups, null, ACCOUNT);
    private final AccountAwareStorageBackend<LogStream> streams = AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final AccountAwareStorageBackend<LogEvent> events = AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final CloudWatchLogsService service = new CloudWatchLogsService(groups, streams, events,
            new InMemoryStorage<>(), new InMemoryStorage<>(), 1, new RegionResolver(REGION, ACCOUNT));

    @Test
    void inclusiveSnapshotIsCompleteImmutableAndIndependentOfQueryCapAndLaterDeletion() {
        seed("one", 100, "a");
        seed("one", 200, "b");
        seed("two", 200, "c");
        seed("one", 300, "d");
        Set<String> keys = Set.copyOf(events.keysForAccount(ACCOUNT));
        List<LogExportPlan.Event> selected = service.captureExport(ACCOUNT, REGION, "group", "one", 100, 200);
        assertEquals(List.of(new LogExportPlan.Event("one", 100, "a"), new LogExportPlan.Event("one", 200, "b")), selected);
        assertEquals(keys, events.keysForAccount(ACCOUNT));
        events.getForAccount(ACCOUNT, REGION + "::group::one::000000000000100::id100a").orElseThrow().setMessage("changed");
        service.deleteLogGroup("group", REGION);
        assertEquals("a", selected.getFirst().message());
        assertThrows(UnsupportedOperationException.class, () -> selected.clear());
    }

    @Test
    void explicitAccountAndRegionDoNotBorrowSameNameRows() {
        seed("one", 100, "a");
        assertThrows(AwsException.class, () -> service.captureExport("999999999999", REGION, "group", null, 0, 200));
        assertThrows(AwsException.class, () -> service.captureExport(ACCOUNT, "us-west-2", "group", null, 0, 200));
    }

    @Test
    void legacyTagsAreReadInPlaceButActualExportRequiresNativeSource() {
        LogGroup group = group();
        group.setTags(Map.of("team", "", "owner", "blue"));
        rawGroups.put(REGION + "::group", group);
        assertEquals(group.getTags(), service.readLogExportTags(ACCOUNT, REGION, "group"));
        assertTrue(service.readLogExportTags("999999999999", REGION, "group").isEmpty());
        assertThrows(AwsException.class, () -> service.captureExport(ACCOUNT, REGION, "group", null, 0, 200));
        assertEquals(Set.of(REGION + "::group"), rawGroups.keys());
    }

    @Test
    void corruptEventKeyOrMissingStreamRefusesWholeSnapshotWithoutMutation() {
        seed("one", 100, "a");
        LogEvent corrupt = new LogEvent();
        corrupt.setEventId("wrong");
        corrupt.setMessage("hidden");
        corrupt.setTimestamp(100);
        events.putForAccount(ACCOUNT, REGION + "::group::one::000000000000100::other", corrupt);
        Set<String> keys = Set.copyOf(events.keysForAccount(ACCOUNT));
        assertThrows(AwsException.class, () -> service.captureExport(ACCOUNT, REGION, "group", null, 0, 200));
        assertEquals(keys, events.keysForAccount(ACCOUNT));
        events.deleteForAccount(ACCOUNT, REGION + "::group::one::000000000000100::other");
        streams.deleteForAccount(ACCOUNT, REGION + "::group::one");
        assertThrows(AwsException.class, () -> service.captureExport(ACCOUNT, REGION, "group", null, 0, 200));
    }

    @Test
    void embeddedForeignStreamParentCannotCreateTaskOrTouchDestination() {
        seed("one", 100, "a");
        streams.getForAccount(ACCOUNT, REGION + "::group::one").orElseThrow().setLogGroupName("foreign-parent");
        AccountAwareStorageBackend<LogExportTask> tasks = AccountAwareStorageBackend.inMemory(ACCOUNT);
        S3Service destination = mock(S3Service.class);
        CloudWatchLogsExportService exports = new CloudWatchLogsExportService(tasks, service, destination, null,
                new RegionResolver(REGION, ACCOUNT), Clock.systemUTC(), Executors.newSingleThreadExecutor());
        Set<String> sourceKeys = Set.copyOf(events.keysForAccount(ACCOUNT));
        try {
            assertThrows(AwsException.class, () -> exports.create("group", null, 0, 200, "bucket",
                    "prefix", "name", REGION, null));
            assertTrue(tasks.keysForAccount(ACCOUNT).isEmpty());
            assertEquals(sourceKeys, events.keysForAccount(ACCOUNT));
            assertEquals("foreign-parent", streams.getForAccount(ACCOUNT, REGION + "::group::one")
                    .orElseThrow().getLogGroupName());
            verifyNoInteractions(destination);
        } finally {
            exports.shutdown();
        }
    }

    @Test
    void unsupportedSourceModesAndEndBeforeCreationAreExplicit() {
        seed("one", 100, "a");
        LogGroup group = groups.getForAccount(ACCOUNT, REGION + "::group").orElseThrow();
        group.setCreatedTime(101);
        assertThrows(AwsException.class, () -> service.captureExport(ACCOUNT, REGION, "group", null, 0, 100));
        group.setCreatedTime(1);
        group.setLogGroupClass("INFREQUENT_ACCESS");
        assertThrows(AwsException.class, () -> service.captureExport(ACCOUNT, REGION, "group", null, 0, 100));
        group.setLogGroupClass("STANDARD");
        group.setKmsKeyId("key");
        assertThrows(AwsException.class, () -> service.captureExport(ACCOUNT, REGION, "group", null, 0, 100));
    }

    private void seed(String streamName, long timestamp, String message) {
        groups.putForAccount(ACCOUNT, REGION + "::group", group());
        LogStream stream = new LogStream();
        stream.setLogStreamName(streamName);
        stream.setLogGroupName("group");
        streams.putForAccount(ACCOUNT, REGION + "::group::" + streamName, stream);
        LogEvent event = new LogEvent();
        event.setEventId("id" + timestamp + message);
        event.setTimestamp(timestamp);
        event.setMessage(message);
        events.putForAccount(ACCOUNT, REGION + "::group::" + streamName + "::"
                + String.format("%015d", timestamp) + "::" + event.getEventId(), event);
    }

    private static LogGroup group() {
        LogGroup group = new LogGroup();
        group.setLogGroupName("group");
        group.setCreatedTime(1);
        return group;
    }
}
