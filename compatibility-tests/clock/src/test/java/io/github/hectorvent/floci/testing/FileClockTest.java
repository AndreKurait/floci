package io.github.hectorvent.floci.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileClockTest {
    @TempDir
    Path directory;

    @Test
    void observesAtomicAdvancesThroughEveryZoneView() throws Exception {
        Path file = directory.resolve("instant");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Files.writeString(file, start.toString());
        FileClock clock = new FileClock(file);
        Clock otherZone = clock.withZone(ZoneId.of("America/Chicago"));
        assertEquals(start, clock.instant());
        Path next = directory.resolve("next");
        Files.writeString(next, start.plusSeconds(120).toString());
        Files.move(next, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        assertEquals(start.plusSeconds(120), clock.instant());
        assertEquals(clock.instant(), otherZone.instant());
        assertEquals(ZoneId.of("America/Chicago"), otherZone.getZone());
    }

    @Test
    void missingOrInvalidClockFailsInsteadOfSilentlyUsingWallTime() throws Exception {
        Path file = directory.resolve("instant");
        FileClock clock = new FileClock(file);
        assertThrows(IllegalStateException.class, clock::instant);
        Files.writeString(file, "invalid");
        assertThrows(IllegalStateException.class, clock::instant);
    }
}
