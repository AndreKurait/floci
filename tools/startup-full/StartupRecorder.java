import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

public final class StartupRecorder {
    private static PrintWriter output;
    private static Path directory;
    private static final AtomicLong sequence = new AtomicLong();
    private static final ThreadLocal<Deque<Frame>> frames = ThreadLocal.withInitial(ArrayDeque::new);
    private static final AtomicLong active = new AtomicLong();
    private static final class Frame {
        long id = sequence.incrementAndGet();
        long start = System.nanoTime();
        String method;
        Path path;
        String before;
        Frame(String method, Path path) { this.method = method; this.path = path; this.before = attributes(path); }
    }
    public static synchronized void open(String destination) throws Exception {
        directory = Path.of(destination); Files.createDirectories(directory);
        output = new PrintWriter(directory.resolve("events.tsv").toFile());
        output.println("kind\tid\tthread\tmethod\tpath\tbefore\tafter\tnanos\tresult\tstartNanos\tendNanos\theapUsedBytes"); output.flush();
        emit("fork\t" + ProcessHandle.current().pid() + "\tmetrics=" + System.getProperty("quarkus.builder.metrics.enabled"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP) {
                    emit("peak-heap-pool\t" + pool.getName() + "\t" + pool.getPeakUsage().getUsed());
                }
            }
            emit("shutdown\t" + active.get());
        }));
    }
    public static synchronized void emit(String line) { output.println(line); output.flush(); }
    public static void transformed(String name, ClassLoader loader, String descriptor) {
        emit("transform\t" + name + "\t" + loader + "\t" + descriptor);
    }
    public static void enter(String method, Path path, Object context, Object profile) {
        Frame frame = new Frame(method, path); frames.get().push(frame); active.incrementAndGet();
        emit("enter\t" + frame.id + "\t" + Thread.currentThread().threadId() + "\t" + method + "\t" + contextIdentity(context) + "\tprofile=" + profile);
    }
    public static void resolved(Path path) {
        Frame frame = frames.get().peek();
        if (frame != null && frame.path == null) { frame.path = path; frame.before = attributes(path); }
    }
    public static void exit(boolean exceptional) {
        Deque<Frame> stack = frames.get(); Frame frame = stack.pop(); active.decrementAndGet();
        if (stack.isEmpty()) { frames.remove(); }
        long end = System.nanoTime();
        long elapsed = end - frame.start;
        String result = exceptional ? "throw" : "return";
        if (!exceptional && frame.method.equals("io/quarkus/builder/BuildMetrics.dumpTo")) {
            try {
                if (frame.path != null && Files.isRegularFile(frame.path)) {
                    Files.copy(frame.path, directory.resolve("metrics-" + frame.id + ".json"));
                } else { result = "return-no-file"; }
            } catch (Exception failure) { result = "capture-error:" + failure.getClass().getName(); }
        }
        emit("exit\t" + frame.id + "\t" + Thread.currentThread().threadId() + "\t" + frame.method
            + "\t" + frame.path + "\t" + frame.before + "\t" + attributes(frame.path) + "\t" + elapsed + "\t" + result + "\t" + frame.start + "\t" + end
            + "\t" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    }
    public static long activeFrames() { return active.get(); }
    private static String contextIdentity(Object context) {
        if (context instanceof Class<?> type) {
            ClassLoader loader = type.getClassLoader();
            return type.getName() + ":loader=" + (loader == null ? "bootstrap"
                : loader.getClass().getName() + "@" + System.identityHashCode(loader));
        }
        return context == null ? "none" : context.toString();
    }
    private static String attributes(Path path) {
        if (path == null) { return "unresolved"; }
        try {
            BasicFileAttributes value = Files.readAttributes(path, BasicFileAttributes.class);
            return value.fileKey() + ":" + value.size() + ":" + value.lastModifiedTime();
        } catch (java.nio.file.NoSuchFileException missing) { return "absent"; }
        catch (Exception failure) { return "error:" + failure.getClass().getName(); }
    }
}
