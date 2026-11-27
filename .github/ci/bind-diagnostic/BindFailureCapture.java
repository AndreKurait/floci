package io.floci.aws.core.common;

import java.io.IOException;
import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

/** Private diagnostic overlay only; deliberately absent from production artifacts. */
final class BindFailureCapture {
    static void capture(int port, IOException original) {
        String collector = System.getProperty("floci-bind-diagnostic.collector");
        String directory = System.getProperty("floci-bind-diagnostic.directory");
        if (!(original instanceof BindException) || collector == null || directory == null) {
            return;
        }
        Process capture = null;
        try {
            Path output = Path.of(directory, "bind-" + ProcessHandle.current().pid()
                    + "-" + System.nanoTime() + ".json");
            writeTrigger(output.resolveSibling(output.getFileName() + ".trigger"), port, original);
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

    private static void writeTrigger(Path output, int port, IOException original) {
        try {
            StringBuilder trigger = new StringBuilder()
                    .append("pid=").append(ProcessHandle.current().pid()).append('\n')
                    .append("threadId=").append(Thread.currentThread().threadId()).append('\n')
                    .append("port=").append(port).append('\n');
            int retained = 0;
            for (StackTraceElement frame : original.getStackTrace()) {
                if (frame.getClassName().startsWith("io.floci.aws.") && retained < 32) {
                    trigger.append("frame=").append(frame.getClassName()).append('#')
                            .append(frame.getMethodName()).append(':').append(frame.getLineNumber()).append('\n');
                    retained++;
                }
            }
            Files.createFile(output, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
            Files.writeString(output, trigger.toString());
        } catch (IOException | RuntimeException ignored) {
            // Trigger metadata is optional and must not prevent the existing socket snapshot.
        }
    }

    private BindFailureCapture() {
    }
}
