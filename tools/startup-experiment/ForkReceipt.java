import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class ForkReceipt {
    public static void premain(String destination, Instrumentation instrumentation) throws Exception {
        String enabled = System.getProperty("quarkus.builder.metrics.enabled");
        if (!"true".equals(enabled)) {
            throw new IllegalStateException("Build metrics missing in actual test JVM");
        }
        Files.writeString(Path.of(destination), "pid=" + ProcessHandle.current().pid()
                + "\nmetrics=" + enabled + "\nstart=" + Instant.now()
                + "\njava=" + System.getProperty("java.version")
                + "\ncpus=" + Runtime.getRuntime().availableProcessors() + "\n");
    }
}
