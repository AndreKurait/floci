package io.floci.aws.core.common;

import java.io.IOException;
import java.net.BindException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Private diagnostic overlay only; deliberately absent from production artifacts. */
final class BindFailureCapture {
    static void capture(int port, IOException original) {
        String collector = System.getProperty("floci.diagnostic.collector");
        String directory = System.getProperty("floci.diagnostic.directory");
        if (!(original instanceof BindException) || collector == null || directory == null) {
            return;
        }
        Process capture = null;
        try {
            Path output = Path.of(directory, "bind-" + ProcessHandle.current().pid()
                    + "-" + System.nanoTime() + ".json");
            capture = new ProcessBuilder("python3", collector, "--port", Integer.toString(port),
                    "--output", output.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!capture.waitFor(3, TimeUnit.SECONDS)) {
                capture.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ignored) {
            // Diagnostics must preserve the original bind failure and its cleanup path.
        } finally {
            if (capture != null && capture.isAlive()) {
                capture.destroyForcibly();
            }
        }
    }

    private BindFailureCapture() {
    }
}
