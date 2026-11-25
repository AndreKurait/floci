package io.floci.aws.testing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Reads an atomically replaced ISO-8601 instant from a shared test directory. */
final class FileClock extends Clock {
    private static final int MAX_TIMESTAMP_BYTES = 128;
    private final Path file;
    private final ZoneId zone;

    FileClock(Path file) {
        this(file, ZoneOffset.UTC);
    }

    private FileClock(Path file, ZoneId zone) {
        this.file = Objects.requireNonNull(file);
        this.zone = Objects.requireNonNull(zone);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
        return new FileClock(file, requestedZone);
    }

    @Override
    public Instant instant() {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_TIMESTAMP_BYTES + 1);
            if (bytes.length > MAX_TIMESTAMP_BYTES) {
                throw new IllegalStateException("AppConfig compatibility timestamp exceeds 128 bytes: " + file);
            }
            return Instant.parse(new String(bytes, StandardCharsets.UTF_8).strip());
        } catch (DateTimeParseException error) {
            // The configured file might contain something other than a timestamp.
            // Do not include its contents in a parse exception or application log.
            throw new IllegalStateException("Invalid AppConfig compatibility timestamp: " + file);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read AppConfig compatibility clock: " + file, error);
        }
    }
}
