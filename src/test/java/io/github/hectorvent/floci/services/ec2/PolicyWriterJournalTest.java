package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyWriterJournalTest {
    @TempDir
    Path directory;

    @Test
    void anotherManagerRecoversExactPendingExecAndOnlyItsCompletionClearsIt() {
        PolicyWriterJournal original = journal();
        original.initialize("helper");
        try (PolicyWriterJournal.Lease lease = original.acquire("helper")) {
            assertNull(lease.pendingExecId());
            lease.started("exec-17");
        }
        try (PolicyWriterJournal.Lease lease = journal().acquire("helper")) {
            assertEquals("exec-17", lease.pendingExecId());
            assertThrows(IllegalStateException.class, () -> lease.completed("other-exec"));
            assertThrows(IllegalStateException.class, () -> lease.started("another-exec"));
            lease.completed("exec-17");
        }
        try (PolicyWriterJournal.Lease lease = journal().acquire("helper")) {
            assertNull(lease.pendingExecId());
        }
    }

    @Test
    void absenceIsNotCleanAndInitializationCannotReplaceOwnership() {
        PolicyWriterJournal journal = journal();
        assertThrows(IllegalStateException.class, () -> journal.acquire("helper"));
        journal.initialize("helper");
        assertThrows(IllegalStateException.class, () -> journal.initialize("helper"));
    }

    @Test
    void exactHelperLockExcludesAnotherManagerWhileDifferentHelpersCanProceed() {
        PolicyWriterJournal first = journal();
        first.initialize("helper-a");
        first.initialize("helper-b");
        try (PolicyWriterJournal.Lease a = first.acquire("helper-a");
             PolicyWriterJournal.Lease b = journal().acquire("helper-b")) {
            assertThrows(IllegalStateException.class, () -> journal().acquire("helper-a"));
            a.started("exec-a");
            b.started("exec-b");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "{",
            "{\"version\":4294967297,\"owner\":\"fixture/4566\",\"helperId\":\"helper\",\"state\":\"ready\",\"execId\":null}",
            "{\"version\":1,\"owner\":\"other/4566\",\"helperId\":\"helper\",\"state\":\"ready\",\"execId\":null}",
            "{\"version\":1,\"owner\":\"fixture/4566\",\"helperId\":\"other\",\"state\":\"ready\",\"execId\":null}",
            "{\"version\":1,\"owner\":\"fixture/4566\",\"helperId\":\"helper\",\"state\":\"pending\",\"execId\":null}",
            "{\"version\":1,\"owner\":\"fixture/4566\",\"helperId\":\"helper\",\"state\":\"ready\",\"execId\":\"exec\"}",
            "{\"version\":1,\"owner\":\"fixture/4566\",\"helperId\":\"helper\",\"state\":\"ready\",\"execId\":null} {}",
            "{\"version\":1,\"owner\":\"fixture/4566\",\"helperId\":\"helper\",\"state\":\"ready\",\"execId\":null} broken",
            "{\"version\":1,\"owner\":\"fixture/4566\",\"helperId\":\"helper\",\"state\":\"pending\",\"state\":\"ready\",\"execId\":null}",
            "{\"version\":1,\"owner\":\"fixture/4566\",\"helperId\":\"helper\",\"state\":\"ready\"}"
    })
    void corruptOrMismatchedRecordCannotAuthorizeAWriter(String content) throws Exception {
        journal().initialize("helper");
        Files.writeString(record(), content);
        assertThrows(IllegalStateException.class, () -> journal().acquire("helper"));
    }

    @Test
    void oversizedRecordAndSymlinkCannotAuthorizeAWriter() throws Exception {
        journal().initialize("helper");
        Path record = record();
        Files.writeString(record, " ".repeat(8193));
        assertThrows(IllegalStateException.class, () -> journal().acquire("helper"));
        Files.delete(record);
        Path outside = directory.resolve("outside.json");
        Files.writeString(outside, "{}");
        Files.createSymbolicLink(record, outside);
        assertThrows(IllegalStateException.class, () -> journal().acquire("helper"));
    }

    @Test
    void cleanupFindsInitialWriteCrashDebrisAndRemovesOnlyTheExactHelperFiles() throws Exception {
        PolicyWriterJournal journal = journal();
        journal.initialize("helper");
        Path record = record();
        Files.move(record, record.resolveSibling("helper.tmp"));
        assertEquals(List.of("helper"), journal.helpers());
        journal.forget("helper");
        assertTrue(journal.helpers().isEmpty());
        try (Stream<Path> files = Files.list(record.getParent())) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void invalidIdentityCannotEscapeOwnerDirectory() {
        assertThrows(IllegalStateException.class, () -> journal().initialize("../other"));
        assertThrows(IllegalStateException.class, () -> journal().acquire("bad/id"));
    }

    private PolicyWriterJournal journal() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("fixture"));
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        return new PolicyWriterJournal(config);
    }

    private Path record() throws Exception {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json")).findFirst().orElseThrow();
        }
    }
}
