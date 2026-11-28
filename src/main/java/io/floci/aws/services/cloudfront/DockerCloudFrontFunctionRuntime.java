package io.floci.aws.services.cloudfront;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.WaitResponse;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@ApplicationScoped
class DockerCloudFrontFunctionRuntime implements CloudFrontFunctionRuntime, ContainerTeardown, Resettable {

    static final String OWNER_LABEL = "floci.cloudfront-test.owner";
    static final String INVOCATION_LABEL = "floci.cloudfront-test.invocation";
    private static final Logger LOG = Logger.getLogger(DockerCloudFrontFunctionRuntime.class);
    private static final int MAX_ACTIVE = 4;
    private static final int MAX_FRAME_BYTES = 128 * 1024;
    private static final long EXECUTION_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final Object monitor = new Object();
    private final Map<String, Invocation> invocations = new LinkedHashMap<>();
    private final String owner = UUID.randomUUID().toString();
    private final ContainerBuilder builder;
    private final ContainerLifecycleManager lifecycle;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;
    private long epoch;
    private boolean accepting = true;

    @Inject
    DockerCloudFrontFunctionRuntime(ContainerBuilder builder, ContainerLifecycleManager lifecycle,
                                    EmulatorConfig config, ObjectMapper mapper) {
        this.builder = builder;
        this.lifecycle = lifecycle;
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public Result evaluate(Supplier<CloudFrontFunctionSnapshot> capture, String event) {
        Invocation invocation = admit();
        Result result;
        RuntimeException failure = null;
        try {
            CloudFrontFunctionSnapshot snapshot = capture.get();
            requireCurrent(invocation);
            String image = lifecycle.resolveImageForLaunch(
                    builder.resolveImage(config.services().cloudfront().functionRuntimeImage()),
                    ImagePullBehavior.PREFER_CACHED, null).imageId();
            ContainerSpec spec = spec(image, invocation, snapshot, event);
            requireCurrent(invocation);
            synchronized (monitor) {
                invocation.createStarted = true;
            }
            String id = lifecycle.create(spec);
            if (id == null || id.isBlank()) {
                throw failed("The local container create returned no identity.");
            }
            synchronized (monitor) {
                invocation.containerId = id;
                invocation.createCompleted = true;
                invocation.creationFinished = true;
                monitor.notifyAll();
            }
            synchronized (monitor) {
                requireCurrent(invocation);
                invocation.deadline = System.nanoTime() + EXECUTION_NANOS;
                Thread.ofVirtual().name("cloudfront-test-deadline-" + invocation.id)
                        .start(() -> enforceDeadline(invocation));
            }
            requireCurrent(invocation);
            lifecycle.startCreated(id, spec);
            requireCurrent(invocation);
            Evaluation evaluation = collect(invocation, id);
            synchronized (monitor) {
                requireCurrent(invocation);
                invocation.executionFinished = true;
            }
            result = new Result(snapshot, evaluation);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure = failed("The function test was interrupted.");
            result = null;
        } catch (AwsException expected) {
            failure = expected;
            result = null;
        } catch (Exception unexpected) {
            LOG.debug("CloudFront function runtime failed", unexpected);
            failure = failed("The local function runtime could not complete the test.");
            result = null;
        } finally {
            synchronized (monitor) {
                invocation.creationFinished = true;
                monitor.notifyAll();
            }
            try {
                remove(invocation);
                invocation.gone.get(30, TimeUnit.SECONDS);
            } catch (Exception cleanupFailure) {
                if (cleanupFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.warn("CloudFront function container cleanup is incomplete", cleanupFailure);
                if (failure != null) {
                    cleanupFailure.addSuppressed(failure);
                }
                failure = failed("The local function container could not be removed.");
            }
        }
        if (failure != null) {
            throw failure;
        }
        requireCurrent(invocation);
        return result;
    }

    private Invocation admit() {
        synchronized (monitor) {
            if (!accepting || invocations.size() >= MAX_ACTIVE) {
                throw failed("The local function runtime is busy or is being reset.");
            }
            String id = UUID.randomUUID().toString();
            Invocation invocation = new Invocation(id, "floci-cloudfront-test-" + owner + "-" + id, epoch);
            invocations.put(id, invocation);
            return invocation;
        }
    }

    private void requireCurrent(Invocation invocation) {
        synchronized (monitor) {
            if (!accepting || invocation.cancelled || invocation.epoch != epoch) {
                throw failed(invocation.deadlineExpired ? "The local function container exceeded its time limit."
                        : "The function test was cancelled by an emulator reset.");
            }
        }
    }

    private ContainerSpec spec(String image, Invocation invocation, CloudFrontFunctionSnapshot snapshot,
                               String event) throws IOException {
        String script;
        try (InputStream resource = DockerCloudFrontFunctionRuntime.class
                .getResourceAsStream("/cloudfront/cloudfront-function-runtime.cjs")) {
            if (resource == null) {
                throw failed("The bundled function runtime is unavailable.");
            }
            script = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        String payload = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(
                Map.of("code", snapshot.code(), "event", event)));
        List<String> command = new ArrayList<>(List.of("--max-old-space-size=32", "--stack-size=512", "--no-addons",
                "--disable-proto=throw", "-e", script, "--"));
        for (int offset = 0; offset < payload.length(); offset += 32768) {
            command.add(payload.substring(offset, Math.min(offset + 32768, payload.length())));
        }
        return builder.newContainer(image).withName(invocation.name)
                .withEntrypoint(List.of("node"))
                .withCmd(command)
                .withNetworkMode("none").withUser("1000:1000").withReadonlyRootfs()
                .withCapDrop("ALL").withNoNewPrivileges().withPidsLimit(64)
                .withMemoryMb(128).withMemorySwapBytes(128L * 1024 * 1024).withCpuUnits(1024)
                .withLabel(OWNER_LABEL, owner).withLabel(INVOCATION_LABEL, invocation.id)
                .withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE,
                        Map.of("max-size", "1m", "max-file", "1")))
                .build();
    }

    private void enforceDeadline(Invocation invocation) {
        try {
            long remaining = invocation.deadline - System.nanoTime();
            if (remaining > 0) {
                invocation.gone.get(remaining, TimeUnit.NANOSECONDS);
                return;
            }
        } catch (TimeoutException expired) {
            // Removal runs independently of a blocked start response or log attachment.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException unexpected) {
            LOG.warn("Function container completion failed", unexpected);
        }
        synchronized (monitor) {
            if (invocation.gone.isDone() || invocation.executionFinished) {
                return;
            }
            invocation.deadlineExpired = true;
            invocation.cancelled = true;
        }
        try {
            remove(invocation);
        } catch (RuntimeException cleanupFailure) {
            LOG.warn("Function deadline expired but container removal is not confirmed", cleanupFailure);
        }
    }

    private Evaluation collect(Invocation invocation, String id) throws InterruptedException, IOException {
        DockerClient docker = lifecycle.getDockerClient();
        long deadline = invocation.deadline;
        try (BoundedLogs logs = new BoundedLogs(); ContainerExit exited = new ContainerExit()) {
            docker.logContainerCmd(id).withStdOut(true).withStdErr(true).withFollowStream(true).withTailAll().exec(logs);
            docker.waitContainerCmd(id).exec(exited);
            while (!exited.code.isDone()) {
                requireCurrent(invocation);
                if (logs.invalid() || System.nanoTime() >= deadline) {
                    throw failed("The local function container exceeded its time or output limit.");
                }
                Thread.sleep(25);
            }
            requireCurrent(invocation);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !logs.awaitCompletion(remaining, TimeUnit.NANOSECONDS) || logs.invalid()) {
                throw failed("The local function container did not provide a complete bounded result.");
            }
            if (exited.code.join() != 0) {
                throw failed("The local function process terminated before returning a result.");
            }
            return parse(logs.stdout());
        }
    }

    private Evaluation parse(String output) throws IOException {
        JsonNode root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(output);
        if (root == null || !root.isObject() || !root.path("protocol").isIntegralNumber()
                || !root.path("protocol").canConvertToInt() || root.path("protocol").intValue() != 1
                || !root.path("logs").isArray() || root.path("logs").size() > 100) {
            throw failed("The local function runtime returned an invalid result.");
        }
        List<String> logs = new ArrayList<>();
        int bytes = 0;
        for (JsonNode line : root.path("logs")) {
            if (!line.isTextual()) {
                throw failed("The local function runtime returned invalid logs.");
            }
            bytes += line.textValue().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 8192) {
                throw failed("The local function runtime exceeded its log limit.");
            }
            logs.add(line.textValue());
        }
        JsonNode value = root.get("output");
        JsonNode error = root.get("error");
        if (value == null || error == null || (!value.isNull() && !value.isTextual())
                || (!error.isNull() && !error.isTextual()) || (value.isNull() == error.isNull())) {
            throw failed("The local function runtime returned an invalid outcome.");
        }
        String result = value.isTextual() ? value.textValue() : null;
        String failure = error.isTextual() ? error.textValue() : null;
        if ((result != null && result.getBytes(StandardCharsets.UTF_8).length > 40960)
                || (failure != null && failure.getBytes(StandardCharsets.UTF_8).length > 4096)) {
            throw failed("The local function runtime exceeded its result limit.");
        }
        return new Evaluation(result, failure, logs);
    }

    private void remove(Invocation invocation) {
        synchronized (monitor) {
            if (invocation.gone.isDone()) {
                return;
            }
            if (!invocation.creationFinished || invocation.removing) {
                return;
            }
            invocation.removing = true;
        }
        boolean gone = false;
        try {
            String id = invocation.containerId;
            if (id == null && invocation.createStarted) {
                try {
                    InspectContainerResponse inspected = lifecycle.getDockerClient()
                            .inspectContainerCmd(invocation.name).exec();
                    Map<String, String> labels = inspected.getConfig().getLabels();
                    if (labels == null || !owner.equals(labels.get(OWNER_LABEL))
                            || !invocation.id.equals(labels.get(INVOCATION_LABEL))) {
                        throw new IllegalStateException("The container name is not owned by this invocation.");
                    }
                    id = inspected.getId();
                    if (id == null || id.isBlank()) {
                        throw new IllegalStateException("The owned container has no identity.");
                    }
                    synchronized (monitor) {
                        invocation.containerId = id;
                    }
                } catch (NotFoundException expected) {
                    if (!invocation.createCompleted) {
                        throw new IllegalStateException("The container create outcome remains uncertain.", expected);
                    }
                }
            }
            if (id != null) {
                lifecycle.stopAndRemoveStrict(id, null, 0, true);
            }
            gone = true;
        } finally {
            synchronized (monitor) {
                invocation.removing = false;
                if (gone) {
                    invocations.remove(invocation.id, invocation);
                    invocation.gone.complete(null);
                }
                monitor.notifyAll();
            }
        }
    }

    @Override
    public void stopManagedContainers() {
        List<Invocation> pending;
        synchronized (monitor) {
            accepting = false;
            epoch++;
            pending = List.copyOf(invocations.values());
            pending.forEach(invocation -> invocation.cancelled = true);
        }
        RuntimeException failure = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (Invocation invocation : pending) {
            try {
                remove(invocation);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("Function container cleanup timed out.");
                }
                invocation.gone.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = new IllegalStateException("Interrupted while removing function containers.", interrupted);
                break;
            } catch (Exception cleanupFailure) {
                if (failure == null) {
                    failure = new IllegalStateException("Function containers are not all confirmed absent.", cleanupFailure);
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void beforeReset() {
        stopManagedContainers();
    }

    @Override
    public void clear() {
        synchronized (monitor) {
            if (!invocations.isEmpty()) {
                throw new IllegalStateException("Function containers remain during reset.");
            }
        }
    }

    @Override
    public void afterReset() {
        synchronized (monitor) {
            if (invocations.isEmpty()) {
                accepting = true;
            }
        }
    }

    boolean acceptingRequests() {
        synchronized (monitor) {
            return accepting;
        }
    }

    Map<String, String> activeContainers() {
        synchronized (monitor) {
            Map<String, String> active = new LinkedHashMap<>();
            invocations.forEach((id, invocation) -> {
                if (invocation.containerId != null) {
                    active.put(id, invocation.containerId);
                }
            });
            return Map.copyOf(active);
        }
    }

    private static AwsException failed(String message) {
        return new AwsException("TestFunctionFailed", message, 500);
    }

    private static final class Invocation {
        private final String id;
        private final String name;
        private final long epoch;
        private final CompletableFuture<Void> gone = new CompletableFuture<>();
        private volatile String containerId;
        private volatile long deadline;
        private boolean createStarted;
        private boolean createCompleted;
        private boolean executionFinished;
        private boolean deadlineExpired;
        private boolean creationFinished;
        private boolean cancelled;
        private boolean removing;

        private Invocation(String id, String name, long epoch) {
            this.id = id;
            this.name = name;
            this.epoch = epoch;
        }
    }

    private static final class ContainerExit extends ResultCallback.Adapter<WaitResponse> {
        private final CompletableFuture<Integer> code = new CompletableFuture<>();
        private Integer status;

        @Override
        public void onNext(WaitResponse response) {
            status = response.getStatusCode();
        }

        @Override
        public void onComplete() {
            if (status == null) {
                code.completeExceptionally(new IOException("The container wait stream supplied no exit status."));
            } else {
                code.complete(status);
            }
            super.onComplete();
        }

        @Override
        public void onError(Throwable failure) {
            code.completeExceptionally(failure);
            super.onError(failure);
        }
    }

    private static final class BoundedLogs extends ResultCallback.Adapter<Frame> {
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private int bytes;
        private boolean invalid;

        @Override
        public synchronized void onNext(Frame frame) {
            byte[] payload = frame.getPayload();
            if (invalid || payload.length > MAX_FRAME_BYTES - bytes) {
                invalid = true;
                return;
            }
            bytes += payload.length;
            if (frame.getStreamType() == StreamType.STDOUT) {
                stdout.writeBytes(payload);
            }
        }

        @Override
        public synchronized void onError(Throwable failure) {
            invalid = true;
            super.onError(failure);
        }

        synchronized boolean invalid() {
            return invalid;
        }

        synchronized String stdout() {
            return stdout.toString(StandardCharsets.UTF_8);
        }
    }
}
