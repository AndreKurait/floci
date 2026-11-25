package io.floci.aws.services.cloudwatch.logs;

import io.floci.aws.core.common.AwsException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Private local layout: complete UTF-8 records, bounded chunks, and task-owned keys. */
final class LogExportPlan {
    static final int CHUNK_BYTES = 1_048_576;
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    record Event(String stream, long timestamp, String message) {}

    record Chunk(String key, byte[] bytes) {
        Chunk {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private LogExportPlan() {}

    static List<Chunk> build(String prefix, String taskId, List<Event> events) {
        Map<String, List<Event>> streams = new LinkedHashMap<>();
        for (Event event : events) {
            streams.computeIfAbsent(event.stream(), ignored -> new ArrayList<>()).add(event);
        }
        List<Chunk> result = new ArrayList<>();
        for (Map.Entry<String, List<Event>> entry : streams.entrySet()) {
            String stream = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream chunk = new ByteArrayOutputStream();
            int number = 0;
            for (Event event : entry.getValue()) {
                byte[] line = (TIMESTAMP.format(Instant.ofEpochMilli(event.timestamp())) + " "
                        + event.message() + "\n").getBytes(StandardCharsets.UTF_8);
                if (chunk.size() > 0 && (long) chunk.size() + line.length > CHUNK_BYTES) {
                    result.add(compress(key(prefix, taskId, stream, number++), chunk.toByteArray()));
                    chunk.reset();
                }
                chunk.writeBytes(line);
            }
            if (chunk.size() > 0) {
                result.add(compress(key(prefix, taskId, stream, number), chunk.toByteArray()));
            }
        }
        return List.copyOf(result);
    }

    static String emptyPermissionKey(String prefix, String taskId) {
        return key(prefix, taskId, "empty", 0);
    }

    private static String key(String prefix, String taskId, String stream, int number) {
        String key = (prefix.isEmpty() ? "" : prefix + "/") + taskId + "/" + stream + "/" + number + ".gz";
        if (key.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new AwsException("InvalidParameterException",
                    "The destination prefix and generated export key exceed 1024 UTF-8 bytes.", 400);
        }
        return key;
    }

    private static Chunk compress(String key, byte[] bytes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Chunk(key, output.toByteArray());
    }
}
