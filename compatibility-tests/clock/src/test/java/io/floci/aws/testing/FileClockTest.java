package io.floci.aws.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void acceptsTheOffsetFormatWrittenByPython() throws Exception {
        Path file = directory.resolve("instant");
        Files.writeString(file, "2026-01-01T00:02:03.123456+00:00\n");
        assertEquals(Instant.parse("2026-01-01T00:02:03.123456Z"), new FileClock(file).instant());
    }

    @Test
    void invalidContentIsBoundedAndNotLeakedInAnException() throws Exception {
        Path file = directory.resolve("instant");
        Files.writeString(file, "unexpected-secret");
        IllegalStateException invalid = assertThrows(IllegalStateException.class, new FileClock(file)::instant);
        assertFalse(invalid.getMessage().contains("unexpected-secret"));
        assertNull(invalid.getCause());
        Files.writeString(file, "x".repeat(129));
        assertThrows(IllegalStateException.class, new FileClock(file)::instant);
    }

    @Test
    void fixtureUsesWallTimeUnlessAReadableClockIsConfigured() throws Exception {
        assertEquals(Clock.systemUTC(), AppConfigTestClockProducer.configuredClock(null));
        assertEquals(Clock.systemUTC(), AppConfigTestClockProducer.configuredClock(" "));
        Path file = directory.resolve("instant");
        assertThrows(IllegalStateException.class, () -> AppConfigTestClockProducer.configuredClock(file.toString()));
        Files.writeString(file, "2026-01-01T00:00:00Z");
        assertInstanceOf(FileClock.class, AppConfigTestClockProducer.configuredClock(file.toString()));
    }
}
