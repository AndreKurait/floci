package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.SignedMarkers;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.logs.model.LogExportTask;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.S3Service.LogExportDestination;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Native Logs snapshots exported by a local worker. No task lock is held across S3 or notification callbacks. */
@ApplicationScoped
public class CloudWatchLogsExportService implements Resettable {
    private static final Logger LOG = Logger.getLogger(CloudWatchLogsExportService.class);
    private static final Set<String> STATUSES = Set.of("PENDING", "RUNNING", "PENDING_CANCEL",
            "CANCELLED", "COMPLETED", "FAILED");
    private final AccountAwareStorageBackend<LogExportTask> tasks;
    private final CloudWatchLogsService logs;
    private final S3Service s3;
    private final IamEnforcementFilter iam;
    private final RegionResolver regions;
    private final Clock clock;
    private final SignedMarkers markers = new SignedMarkers(1024);
    private final Map<String, Scope> scopes = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    private ExecutorService executor;
    private volatile boolean accepting;
    private boolean recovered;
    private boolean resetQuiesced;
    private volatile long resetEpoch;

    private static final class Scope {
        private String inFlight;
    }

    public record Page(List<LogExportTask> tasks, String nextToken) {
        public Page {
            tasks = List.copyOf(tasks);
        }
    }

    @Inject
    public CloudWatchLogsExportService(StorageFactory storage, CloudWatchLogsService logs,
                                       S3Service s3, IamEnforcementFilter iam, RegionResolver regions) {
        this(storage.create("cloudwatchlogs", "cwlogs-export-tasks.json", new TypeReference<>() {}),
                logs, s3, iam, regions, Clock.systemUTC(), newExecutor());
    }

    CloudWatchLogsExportService(AccountAwareStorageBackend<LogExportTask> tasks, CloudWatchLogsService logs,
                               S3Service s3, IamEnforcementFilter iam, RegionResolver regions,
                               Clock clock, ExecutorService executor) {
        this.tasks = tasks;
        this.logs = logs;
        this.s3 = s3;
        this.iam = iam;
        this.regions = regions;
        this.clock = clock;
        this.executor = executor;
    }

    private static ExecutorService newExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("logs-s3-export-", 0).factory());
    }

    public void recoverAfterRestart() {
        synchronized (lifecycle) {
            if (!recovered) {
                failInterruptedTasks("The local export worker restarted; incomplete exports are not replayed.");
                recovered = true;
                accepting = true;
            }
        }
    }

    public String create(String group, String streamPrefix, long from, long to, String destination,
                         String prefix, String name, String region, String authorization) {
        recoverAfterRestart();
        long admittedEpoch = resetEpoch;
        String account = regions.getAccountId();
        List<LogExportPlan.Event> snapshot = logs.captureExport(account, region, group, streamPrefix, from, to);
        String id = UUID.randomUUID().toString();
        List<LogExportPlan.Chunk> chunks = LogExportPlan.build(prefix, id, snapshot);
        List<String> keys = chunks.isEmpty() ? List.of(LogExportPlan.emptyPermissionKey(prefix, id))
                : chunks.stream().map(LogExportPlan.Chunk::key).toList();
        String sourceArn = AwsArnUtils.Arn.of("logs", region, account, "log-group:" + group).toString();
        LogExportDestination receipt = s3.readLogExportDestination(account, region, destination);
        for (String key : keys) {
            if (iam != null) {
                iam.authorizeAdditionalResourceWithPolicies(authorization, "s3:PutObject",
                        receipt.objectArn(key), account, receipt.policyDocuments());
            }
        }
        LogExportDestination admitted = s3.admitLogExportDestination(receipt, sourceArn, keys);
        LogExportTask task = new LogExportTask(account, region, id, name, group, from, to, destination,
                prefix, clock.millis(), null, "PENDING", "The export is pending.");
        Scope scope = scope(account, region);
        synchronized (lifecycle) {
            if (!accepting || admittedEpoch != resetEpoch) {
                throw new AwsException("ServiceUnavailableException", "The export worker is stopping.", 500);
            }
            synchronized (scope) {
                if (ownedTasks(account, region).stream().anyMatch(row -> !row.terminal())) {
                    throw new AwsException("LimitExceededException",
                            "Only one active export task is supported per account and Region.", 400);
                }
                save(task);
                try {
                    executor.execute(() -> RequestScopes.runAs(account, region,
                            () -> export(scope, task, admitted, sourceArn, chunks)));
                } catch (RuntimeException failure) {
                    save(task.transition("FAILED", "The export worker could not accept the task.", clock.millis()));
                    throw failure;
                }
            }
        }
        return id;
    }

    private void export(Scope scope, LogExportTask initial, LogExportDestination destination,
                        String sourceArn, List<LogExportPlan.Chunk> chunks) {
        try {
            synchronized (scope) {
                LogExportTask current = get(initial.accountId(), initial.region(), initial.taskId());
                if (!accepting || current.terminal()) {
                    return;
                }
                save(current.transition("RUNNING", "The export is running.", clock.millis()));
            }
            for (LogExportPlan.Chunk chunk : chunks) {
                synchronized (scope) {
                    LogExportTask current = get(initial.accountId(), initial.region(), initial.taskId());
                    if (!accepting || current.terminal()) {
                        return;
                    }
                    if ("PENDING_CANCEL".equals(current.status())) {
                        save(current.transition("CANCELLED", "The export was cancelled.", clock.millis()));
                        return;
                    }
                    scope.inFlight = initial.taskId();
                }
                try {
                    s3.writeLogExportObject(destination, sourceArn, chunk.key(), chunk.bytes());
                } finally {
                    synchronized (scope) {
                        if (initial.taskId().equals(scope.inFlight)) {
                            scope.inFlight = null;
                        }
                    }
                }
            }
            synchronized (scope) {
                LogExportTask current = get(initial.accountId(), initial.region(), initial.taskId());
                if (!current.terminal()) {
                    String next = "PENDING_CANCEL".equals(current.status()) ? "CANCELLED" : "COMPLETED";
                    save(current.transition(next, "CANCELLED".equals(next)
                            ? "The export was cancelled." : "The export completed.", clock.millis()));
                }
            }
        } catch (RuntimeException failure) {
            recordFailure(initial, failure);
        }
    }

    /** Records only this task's failed claim; another admitted task may already be using the scope. */
    void recordFailure(LogExportTask initial, RuntimeException failure) {
        Scope scope = scope(initial.accountId(), initial.region());
        synchronized (scope) {
            if (initial.taskId().equals(scope.inFlight)) {
                scope.inFlight = null;
            }
            try {
                LogExportTask current = get(initial.accountId(), initial.region(), initial.taskId());
                if (!current.terminal()) {
                    save(current.transition("PENDING_CANCEL".equals(current.status()) ? "CANCELLED" : "FAILED",
                            "The export stopped; any committed objects remain in the destination.", clock.millis()));
                }
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
        }
        LOG.warn("Logs export failed; a nonterminal persisted task continues to occupy its active slot.", failure);
    }

    public void cancel(String id, String region) {
        recoverAfterRestart();
        String account = regions.getAccountId();
        Scope scope = scope(account, region);
        synchronized (scope) {
            LogExportTask task = get(account, region, id);
            if (task.terminal()) {
                throw new AwsException("InvalidOperationException", "The export task is already terminal.", 400);
            }
            String next = id.equals(scope.inFlight) ? "PENDING_CANCEL" : "CANCELLED";
            save(task.transition(next, "The export cancellation was requested.", clock.millis()));
        }
    }

    public Page describe(String id, String status, Integer requestedLimit, String token, String region) {
        recoverAfterRestart();
        String account = regions.getAccountId();
        int limit = requestedLimit == null ? 50 : requestedLimit;
        if (limit < 1 || limit > 50 || status != null && !STATUSES.contains(status)) {
            throw invalid("Invalid export task limit or status.");
        }
        String markerScope = account + "\n" + region + "\n" + (id == null ? "" : id)
                + "\n" + (status == null ? "" : status);
        String after = "";
        long issued = clock.millis();
        if (token != null) {
            String cursor = markers.cursorOf(markerScope, token);
            try {
                int split = cursor == null ? -1 : cursor.indexOf(':');
                if (split < 1) {
                    throw invalid("Invalid export task token.");
                }
                issued = Long.parseLong(cursor.substring(0, split));
                after = cursor.substring(split + 1);
                if (issued > clock.millis() || clock.millis() - issued >= TimeUnit.HOURS.toMillis(24)) {
                    throw invalid("The export task token has expired.");
                }
            } catch (NumberFormatException e) {
                throw invalid("Invalid export task token.");
            }
        }
        List<LogExportTask> selected = new ArrayList<>();
        synchronized (scope(account, region)) {
            for (LogExportTask task : ownedTasks(account, region)) {
                if ((id == null || id.equals(task.taskId())) && (status == null || status.equals(task.status()))
                        && task.taskId().compareTo(after) > 0) {
                    selected.add(task);
                }
            }
        }
        selected.sort(Comparator.comparing(LogExportTask::taskId));
        boolean more = selected.size() > limit;
        List<LogExportTask> page = List.copyOf(selected.subList(0, Math.min(limit, selected.size())));
        return new Page(page, more ? markers.issue(markerScope, issued + ":" + page.getLast().taskId()) : null);
    }

    private Scope scope(String account, String region) {
        return scopes.computeIfAbsent(account + "/" + region, ignored -> new Scope());
    }

    private List<LogExportTask> ownedTasks(String account, String region) {
        List<LogExportTask> result = new ArrayList<>();
        for (String key : tasks.keysForAccount(account)) {
            if (key.startsWith(region + "::")) {
                LogExportTask task = tasks.getForAccount(account, key)
                        .orElseThrow(() -> invalid("The export task inventory changed unexpectedly."));
                validateStored(account, key, task);
                result.add(task);
            }
        }
        return result;
    }

    private LogExportTask get(String account, String region, String id) {
        String key = region + "::" + id;
        LogExportTask task = tasks.getForAccount(account, key).orElseThrow(() ->
                new AwsException("ResourceNotFoundException", "The specified export task does not exist.", 400));
        validateStored(account, key, task);
        return task;
    }

    private static void validateStored(String account, String key, LogExportTask task) {
        if (!account.equals(task.accountId()) || task.region() == null || task.taskId() == null
                || !key.equals(task.region() + "::" + task.taskId()) || !STATUSES.contains(task.status())) {
            throw new AwsException("ServiceUnavailableException", "The export task inventory is invalid.", 500);
        }
    }

    private void save(LogExportTask task) {
        tasks.putAllAtomicallyForAccount(task.accountId(), Map.of(task.region() + "::" + task.taskId(), task));
    }

    private void failInterruptedTasks(String detail) {
        for (AccountAwareStorageBackend.AccountEntry<LogExportTask> entry : tasks.scanAllAccountEntries(key -> true)) {
            LogExportTask task = entry.value();
            validateStored(entry.accountId(), entry.key(), task);
            // Legacy unscoped task rows are never adopted.
            if (tasks.getForAccount(entry.accountId(), entry.key()).isEmpty()) {
                throw new AwsException("ServiceUnavailableException", "Unscoped export task state is unsupported.", 500);
            }
            synchronized (scope(entry.accountId(), task.region())) {
                if (!task.terminal()) {
                    save(task.transition("FAILED", detail, clock.millis()));
                }
            }
        }
    }

    @Override
    public void beforeReset() {
        synchronized (lifecycle) {
            accepting = false;
            resetQuiesced = true;
            resetEpoch++;
            executor.shutdown();
        }
        drain();
    }

    @Override
    public void clear() {
        // StorageFactory owns persisted rows. Retain scope monitors until all old workers have drained.
    }

    @Override
    public void afterReset() {
        synchronized (lifecycle) {
            if (!resetQuiesced) {
                return;
            }
            if (!executor.isTerminated()) {
                throw new IllegalStateException("The export worker has not drained.");
            }
            failInterruptedTasks("The local reset interrupted this export; committed objects are not replayed.");
            executor = newExecutor();
            recovered = true;
            resetQuiesced = false;
            accepting = true;
        }
    }

    public void shutdown() {
        synchronized (lifecycle) {
            accepting = false;
            executor.shutdown();
        }
        drain();
    }

    private void drain() {
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The export worker did not drain before storage reset or shutdown.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while draining the export worker.", e);
        }
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
