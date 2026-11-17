package io.github.hectorvent.floci.services.ec2.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VpcRoutingGuardedExecTest {
    @TempDir
    Path directory;

    @Test
    void guardFailurePreventsPolicyExecution() throws Exception {
        assertEquals(19, execute(19, "printf policy >> \"$EVENTS\"\n"));
        assertEquals("guard\n", Files.readString(directory.resolve("events")));
    }

    @Test
    void policyFollowsSuccessfulGuardAndPreservesItsFailure() throws Exception {
        assertEquals(23, execute(0, "printf policy >> \"$EVENTS\"\nexit 23\n"));
        assertEquals("guard\npolicy", Files.readString(directory.resolve("events")));
    }

    @Test
    void successRunsBothStagesInOrder() throws Exception {
        assertEquals(0, execute(0, "printf policy >> \"$EVENTS\"\n"));
        assertEquals("guard\npolicy", Files.readString(directory.resolve("events")));
    }

    private int execute(int guardExit, String policy) throws Exception {
        Path nft = directory.resolve("nft");
        Files.writeString(nft, "#!/bin/sh\ncat >/dev/null\nprintf 'guard\\n' >> \"$EVENTS\"\nexit "
                + guardExit + "\n");
        assertTrue(nft.toFile().setExecutable(true));
        ProcessBuilder builder = new ProcessBuilder("sh", "-c",
                VpcRoutingCommands.guardedEndpoint(List.of("127.0.0.1"), policy));
        builder.environment().put("PATH", directory + ":/usr/bin:/bin");
        builder.environment().put("EVENTS", directory.resolve("events").toString());
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("output").toFile());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Guarded script did not finish");
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }
}
