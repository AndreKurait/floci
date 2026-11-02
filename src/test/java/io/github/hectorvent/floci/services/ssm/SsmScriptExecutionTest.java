package io.github.hectorvent.floci.services.ssm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsmScriptExecutionTest {

    @Test
    void executesTheShebangInterpreterAndPreservesItsArguments() throws Exception {
        Result result = execute("#!/bin/sh -e\nfalse\nprintf 'must not execute'", 5, null);
        assertEquals(1, result.exitCode());
        assertEquals("", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void interpreterFailureDoesNotFallBackToShell() throws Exception {
        Result result = execute("#!/floci-no-such-interpreter\nprintf 'must not execute'", 5, null);
        assertTrue(result.exitCode() != 0);
        assertEquals("", result.stdout());
        assertFalse(result.stderr().isBlank());
    }

    @Test
    void shellFallbackPreservesWorkingDirectoryQuotesOutputAndExitCode(@TempDir Path directory) throws Exception {
        String script = "printf '%s\\n' \"$0\"\npwd\nprintf '%s\\n' 'literal `text` $(nothing) and \"quotes\"'\n"
                + "printf 'diagnostic' >&2\nexit 7";
        Result result = execute(script, 5, directory);
        String[] lines = result.stdout().split("\n");
        assertEquals(7, result.exitCode());
        assertEquals(directory.toRealPath().toString(), lines[1]);
        assertEquals("literal `text` $(nothing) and \"quotes\"", lines[2]);
        assertEquals("diagnostic", result.stderr());
        assertFalse(Files.exists(Path.of(lines[0]).getParent()), "Invocation directory must be removed");
    }

    @Test
    void timeoutCleansTheExecutableScript() throws Exception {
        Result result = execute("#!/bin/sh\nprintf '%s\\n' \"$0\"\nsleep 30", 1, null);
        assertEquals(124, result.exitCode());
        assertFalse(Files.exists(Path.of(result.stdout().strip()).getParent()));
    }

    private Result execute(String script, int timeoutSeconds, Path directory) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "sh", "-c", SsmDirectCommandExecutor.timeoutWrappedScript(script, timeoutSeconds));
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Script wrapper did not finish");
            return new Result(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}
