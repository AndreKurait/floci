package io.github.hectorvent.floci.testing;

import java.io.IOException;
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
        try {
            return Instant.parse(Files.readString(file).strip());
        } catch (IOException | DateTimeParseException error) {
            throw new IllegalStateException("Cannot read AppConfig compatibility clock: " + file, error);
        }
    }
}
