package io.floci.aws.services.cloudwatch.logs;

import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class LogExportPlanTest {
    @Test
    void chunksPreserveEveryUtf8RecordBeyondTheQueryCapAndEscapeStreamPaths() throws Exception {
        List<LogExportPlan.Event> input = new ArrayList<>();
        String message = "界🙂".repeat(1000);
        for (int i = 0; i < 1001; i++) {
            input.add(new LogExportPlan.Event("../stream/世界", 1700000000000L + i, message + i));
        }
        List<LogExportPlan.Chunk> chunks = LogExportPlan.build("prefix", "task", input);
        assertTrue(chunks.size() > 1);
        List<String> actual = new ArrayList<>();
        for (LogExportPlan.Chunk chunk : chunks) {
            assertTrue(chunk.key().startsWith("prefix/task/"));
            assertFalse(chunk.key().contains("../"));
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(chunk.bytes()))) {
                String text = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
                actual.addAll(text.lines().map(line -> line.substring(line.indexOf(' ') + 1)).toList());
            }
        }
        assertEquals(input.stream().map(LogExportPlan.Event::message).toList(), actual);
    }

    @Test
    void emptySelectionCreatesNoGzipAndPermissionKeyIsStillBounded() {
        assertTrue(LogExportPlan.build("prefix", "task", List.of()).isEmpty());
        assertEquals("prefix/task/empty/0.gz", LogExportPlan.emptyPermissionKey("prefix", "task"));
        assertThrows(AwsException.class, () -> LogExportPlan.emptyPermissionKey("界".repeat(400), "task"));
    }

    @Test
    void exactMillisecondTimestampAndEmbeddedNewlineSurviveGzip() throws Exception {
        LogExportPlan.Chunk chunk = LogExportPlan.build("", "task",
                List.of(new LogExportPlan.Event("stream", 1700000000010L, "first\nsecond🙂"))).getFirst();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(chunk.bytes()))) {
            assertEquals("2023-11-14T22:13:20.010Z first\nsecond🙂\n",
                    new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
        }
        byte[] changed = chunk.bytes();
        changed[0] = 0;
        assertEquals(0x1f, Byte.toUnsignedInt(chunk.bytes()[0]));
    }
}
