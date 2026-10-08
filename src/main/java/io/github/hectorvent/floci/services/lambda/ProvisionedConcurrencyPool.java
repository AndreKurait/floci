package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaRuntimeLauncher;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns initialized environments separately from the on-demand idle pool.
 * Persisted requests never imply readiness: every new process must finish real initialization.
 */
@ApplicationScoped
public class ProvisionedConcurrencyPool implements ContainerTeardown, Resettable {
    private static final Logger LOG = Logger.getLogger(ProvisionedConcurrencyPool.class);
    private final Object lock = new Object();
    private final LambdaRuntimeLauncher launcher;
    private final LambdaFunctionStore functions;
    private final LambdaAliasStore aliases;
    private final StorageBackend<String, Configuration> storage;
    private final Clock clock;
    private final int maxPerFunction;
    private final int maxTotal;
    private final boolean ephemeral;
    private final Map<String, Allocation> allocations = new HashMap<>();
    private final Map<ContainerHandle, Allocation> leases = new HashMap<>();
    private final Map<ContainerHandle, Configuration> owned = new HashMap<>();
    private final Set<ContainerHandle> pendingTeardown = new HashSet<>();
    private final Set<ContainerHandle> stopping = new HashSet<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("lambda-provisioned-concurrency").factory());
    private boolean stopped;

    @RegisterForReflection
    public record Configuration(String account, String region, String name, String functionArn,
                                String qualifier, String version, String revision,
                                int requested, String lastModified) {
        String key() {
            return functionArn + ":" + qualifier;
        }
    }

    public record Status(int requested, int allocated, int available, String status,
                         String reason, String lastModified) {}

    private static final class Allocation {
        final Configuration configuration;
        final Set<ContainerHandle> handles = new HashSet<>();
        final ArrayDeque<ContainerHandle> idle = new ArrayDeque<>();
        int launching;
        String status = "IN_PROGRESS";
        String reason;

        Allocation(Configuration configuration) {
            this.configuration = configuration;
        }
    }

    @Inject
    public ProvisionedConcurrencyPool(LambdaRuntimeLauncher launcher, LambdaFunctionStore functions,
                                     LambdaAliasStore aliases, StorageFactory storageFactory,
                                     EmulatorConfig config, Clock clock) {
        this(launcher, functions, aliases, storageFactory.create("lambda",
                        "lambda-provisioned-concurrency.json", new TypeReference<>() {}),
                clock, WarmPool.resolveMaxPerFunction(config), WarmPool.resolveMaxTotal(config),
                config.services().lambda().ephemeral());
    }

    ProvisionedConcurrencyPool(LambdaRuntimeLauncher launcher, LambdaFunctionStore functions,
                              LambdaAliasStore aliases, StorageBackend<String, Configuration> storage,
                              Clock clock, int maxPerFunction, int maxTotal, boolean ephemeral) {
        this.launcher = launcher;
        this.functions = functions;
        this.aliases = aliases;
        this.storage = storage;
        this.clock = clock;
        this.maxPerFunction = maxPerFunction;
        this.maxTotal = maxTotal;
        this.ephemeral = ephemeral;
    }

    @PostConstruct
    void start() {
        synchronized (lock) {
            List<Configuration> stored = storage instanceof AccountAwareStorageBackend<Configuration> aware
                    ? aware.scanAllAccounts() : storage.scan(ignored -> true);
            for (Configuration configuration : stored) {
                allocations.put(configuration.key(), new Allocation(configuration));
            }
        }
        scheduler.scheduleWithFixedDelay(this::reconcileSafely, 0, 1, TimeUnit.SECONDS);
    }

    public Status put(String region, String name, String qualifier, int count, String account) {
        if (count < 1) {
            throw invalid("ProvisionedConcurrentExecutions must be a positive integer");
        }
        Configuration target = resolve(region, name, qualifier, account, count);
        if (ephemeral || !launcher.supportsProvisionedConcurrency()) {
            throw invalid("Provisioned concurrency requires the non-ephemeral Docker Lambda executor");
        }
        LambdaFunction fn = currentFunction(target);
        if (fn.isHotReload() || fn.isDurable()) {
            throw invalid("Provisioned concurrency is not supported for hot-reload or durable functions");
        }
        List<ContainerHandle> retired = List.of();
        Status result;
        synchronized (lock) {
            if (stopped) {
                throw new AwsException("ResourceConflictException", "Lambda executor is stopping", 409);
            }
            if (pendingTeardown.stream().anyMatch(handle ->
                    target.key().equals(owned.get(handle).key()))) {
                throw new AwsException("ResourceConflictException",
                        "The previous provisioned allocation is still being removed", 409);
            }
            Allocation previous = allocations.get(target.key());
            int otherFunction = 0;
            int otherTotal = 0;
            for (Allocation entry : allocations.values()) {
                if (entry == previous) {
                    continue;
                }
                otherTotal += entry.configuration.requested();
                if (target.functionArn().equals(entry.configuration.functionArn())) {
                    otherFunction += entry.configuration.requested();
                }
            }
            if ((long) otherFunction + count > maxPerFunction
                    || (maxTotal > 0 && (long) otherTotal + count > maxTotal)) {
                throw invalid("Requested provisioned concurrency exceeds the configured local warm-pool capacity");
            }
            LambdaFunction latest = functions.getForAccount(target.account(), region, target.name()).orElseThrow(
                    () -> missing("Function no longer exists"));
            Integer reserved = latest.getReservedConcurrentExecutions();
            if (reserved != null && (long) otherFunction + count > reserved) {
                throw invalid("Provisioned concurrency cannot exceed the function's reserved concurrency");
            }
            if (previous != null && sameTarget(previous.configuration, target)
                    && previous.configuration.requested() == count && !"FAILED".equals(previous.status)) {
                return status(previous);
            }
            write(target);
            Allocation next = new Allocation(target);
            allocations.put(target.key(), next);
            if (previous != null) {
                retired = retire(previous);
            }
            result = status(next);
        }
        stop(retired);
        reconcileSafely();
        return result;
    }

    public Status get(String region, String name, String qualifier, String account) {
        Configuration target = resolve(region, name, qualifier, account, 1);
        reconcileSafely();
        synchronized (lock) {
            Allocation allocation = allocations.get(target.key());
            if (allocation == null) {
                throw new AwsException("ProvisionedConcurrencyConfigNotFoundException",
                        "No provisioned concurrency configuration for this qualifier", 404);
            }
            return status(allocation);
        }
    }

    public void delete(String region, String name, String qualifier, String account) {
        Configuration target = resolve(region, name, qualifier, account, 1);
        deleteMatching(target.functionArn(), qualifier);
    }

    /** Returns null when the matching allocation has no idle capacity: on-demand execution may spill over. */
    ContainerHandle acquire(LambdaFunction fn, String qualifier) {
        if (qualifier == null) {
            return null;
        }
        String baseArn = baseArn(fn);
        List<ContainerHandle> discarded = new ArrayList<>();
        ContainerHandle selected = null;
        synchronized (lock) {
            Allocation allocation = allocations.get(baseArn + ":" + qualifier);
            if (allocation == null || !allocation.configuration.version().equals(fn.getVersion())) {
                return null;
            }
            try {
                currentFunction(allocation.configuration);
            } catch (RuntimeException changed) {
                return null;
            }
            while (!allocation.idle.isEmpty()) {
                ContainerHandle candidate = allocation.idle.removeFirst();
                if (healthy(candidate) && current(allocation)) {
                    selected = candidate;
                    leases.put(candidate, allocation);
                    candidate.setState(ContainerState.BUSY);
                    break;
                }
                allocation.handles.remove(candidate);
                discarded.add(candidate);
            }
        }
        stop(discarded);
        return selected;
    }

    boolean release(ContainerHandle handle, boolean failed) {
        boolean retire;
        synchronized (lock) {
            Allocation allocation = leases.remove(handle);
            if (allocation == null) {
                return false;
            }
            retire = failed || !current(allocation) || !healthy(handle);
            if (retire) {
                allocation.handles.remove(handle);
            } else {
                handle.setState(ContainerState.WARM);
                allocation.idle.addLast(handle);
            }
        }
        if (retire) {
            stop(List.of(handle));
        }
        return true;
    }

    void deleteFunction(LambdaFunction fn) {
        deleteMatching(baseArn(fn), null);
    }

    void deleteQualifier(LambdaFunction fn, String qualifier) {
        deleteMatching(baseArn(fn), qualifier);
    }

    void validateReservation(LambdaFunction fn, Integer reserved) {
        if (reserved == null) {
            return;
        }
        synchronized (lock) {
            long requested = allocations.values().stream()
                    .filter(entry -> entry.configuration.functionArn().equals(baseArn(fn)))
                    .mapToLong(entry -> entry.configuration.requested()).sum();
            if (requested > reserved) {
                throw invalid("Reserved concurrency cannot be lower than provisioned concurrency");
            }
        }
    }

    private void deleteMatching(String functionArn, String qualifier) {
        List<ContainerHandle> retired = new ArrayList<>();
        synchronized (lock) {
            for (Allocation entry : new ArrayList<>(allocations.values())) {
                Configuration value = entry.configuration;
                if (value.functionArn().equals(functionArn)
                        && (qualifier == null || qualifier.equals(value.qualifier()))) {
                    erase(value);
                    allocations.remove(value.key());
                    retired.addAll(retire(entry));
                }
            }
            for (ContainerHandle handle : pendingTeardown) {
                Configuration value = owned.get(handle);
                if (value.functionArn().equals(functionArn)
                        && (qualifier == null || qualifier.equals(value.qualifier()))) {
                    retired.add(handle);
                }
            }
        }
        stop(retired);
        synchronized (lock) {
            if (retired.stream().anyMatch(owned::containsKey)) {
                throw new AwsException("ServiceException",
                        "Provisioned environment cleanup is incomplete; retry the operation", 500);
            }
        }
    }

    void reconcileSafely() {
        try {
            List<ContainerHandle> retry;
            synchronized (lock) {
                retry = new ArrayList<>(pendingTeardown);
            }
            stop(retry);
            reconcile();
        } catch (RuntimeException failure) {
            LOG.warnv("Provisioned concurrency reconciliation failed: {0}", failure.getClass().getSimpleName());
        }
    }

    void reconcile() {
        List<ContainerHandle> retired = new ArrayList<>();
        synchronized (lock) {
            if (stopped) {
                return;
            }
            for (Allocation allocation : allocations.values()) {
                if ("FAILED".equals(allocation.status)) {
                    continue;
                }
                LambdaFunction fn;
                try {
                    fn = currentFunction(allocation.configuration);
                } catch (RuntimeException failure) {
                    allocation.status = "FAILED";
                    allocation.reason = "The configured function version or alias is no longer available";
                    retired.addAll(retire(allocation));
                    continue;
                }
                for (ContainerHandle handle : new ArrayList<>(allocation.idle)) {
                    if (!healthy(handle)) {
                        allocation.idle.remove(handle);
                        allocation.handles.remove(handle);
                        retired.add(handle);
                    }
                }
                int missing = allocation.configuration.requested()
                        - allocation.handles.size() - allocation.launching;
                if (missing > 0) {
                    allocation.status = "IN_PROGRESS";
                    allocation.launching += missing;
                    for (int i = 0; i < missing; i++) {
                        workers.execute(() -> initialize(allocation, fn));
                    }
                }
            }
        }
        stop(retired);
    }

    private void initialize(Allocation allocation, LambdaFunction fn) {
        ContainerHandle handle = null;
        boolean retained = false;
        List<ContainerHandle> retired = List.of();
        try {
            handle = launcher.launchProvisioned(fn);
            synchronized (lock) {
                owned.put(handle, allocation.configuration);
                allocation.launching--;
                if (!current(allocation)) {
                    return;
                }
                allocation.handles.add(handle);
            }
            long timeoutMs = Math.max(130, fn.getTimeout()) * 1000L;
            handle.getRuntimeApiServer().awaitInitialization(timeoutMs);
            synchronized (lock) {
                currentFunction(allocation.configuration);
                if (current(allocation) && healthy(handle)) {
                    allocation.idle.addLast(handle);
                    retained = true;
                    if (allocation.handles.size() == allocation.configuration.requested()
                            && allocation.handles.stream().allMatch(this::healthy)) {
                        allocation.status = "READY";
                    }
                }
            }
        } catch (Exception failure) {
            synchronized (lock) {
                if (handle == null) {
                    allocation.launching--;
                }
                if (current(allocation)) {
                    allocation.status = "FAILED";
                    allocation.reason = "Execution environment initialization failed: "
                            + failure.getClass().getSimpleName();
                    retired = retire(allocation);
                }
            }
            LOG.warnv("Provisioned Lambda initialization failed: {0}", failure.getClass().getSimpleName());
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            stop(retired);
            if (!retained && handle != null) {
                synchronized (lock) {
                    allocation.handles.remove(handle);
                    allocation.idle.remove(handle);
                }
                stop(List.of(handle));
            }
        }
    }

    private Status status(Allocation allocation) {
        int healthy = (int) allocation.handles.stream().filter(this::healthy).count();
        int available = (int) allocation.idle.stream().filter(this::healthy).count();
        String state = allocation.status;
        if ("READY".equals(state) && healthy != allocation.configuration.requested()) {
            state = "IN_PROGRESS";
        }
        return new Status(allocation.configuration.requested(), healthy, available, state,
                allocation.reason, allocation.configuration.lastModified());
    }

    private boolean healthy(ContainerHandle handle) {
        return handle.getState() != ContainerState.STOPPED
                && handle.getRuntimeApiServer().isInitialized() && launcher.isAlive(handle);
    }

    private boolean current(Allocation allocation) {
        return !stopped && allocations.get(allocation.configuration.key()) == allocation
                && !"FAILED".equals(allocation.status);
    }

    private List<ContainerHandle> retire(Allocation allocation) {
        List<ContainerHandle> result = new ArrayList<>(allocation.handles);
        allocation.handles.clear();
        allocation.idle.clear();
        return result;
    }

    private void stop(List<ContainerHandle> handles) {
        for (ContainerHandle handle : handles) {
            synchronized (lock) {
                if (!owned.containsKey(handle) || !stopping.add(handle)) {
                    continue;
                }
                pendingTeardown.add(handle);
                handle.setState(ContainerState.STOPPED);
            }
            try {
                launcher.stop(handle);
                synchronized (lock) {
                    pendingTeardown.remove(handle);
                    owned.remove(handle);
                }
            } catch (RuntimeException failure) {
                LOG.warnv("Could not stop provisioned Lambda environment {0}: {1}",
                        handle.getContainerId(), failure.getClass().getSimpleName());
            } finally {
                synchronized (lock) {
                    stopping.remove(handle);
                }
            }
        }
    }

    private Configuration resolve(String region, String name, String qualifier, String account, int count) {
        if (qualifier == null || qualifier.isBlank() || qualifier.length() > 128) {
            throw invalid("Qualifier is required and must contain 1 to 128 characters");
        }
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolveWithQualifier(name, qualifier);
        if (ref.region() != null && !region.equals(ref.region())) {
            throw invalid("Function ARN region must match the request region");
        }
        account = ref.account() != null ? ref.account() : account;
        LambdaFunction latest = functions.getForAccount(account, region, ref.name())
                .orElseThrow(() -> missing("Function not found"));
        String version = qualifier;
        if (!qualifier.matches("[0-9]+")) {
            if ("$LATEST".equals(qualifier)) {
                throw invalid("Provisioned concurrency is not supported for $LATEST");
            }
            LambdaAlias alias = aliases.getForAccount(account, region, ref.name(), qualifier)
                    .orElseThrow(() -> missing("Alias not found"));
            if (alias.getRoutingConfig() != null && !alias.getRoutingConfig().isEmpty()) {
                throw invalid("Weighted aliases are not supported for provisioned concurrency");
            }
            version = alias.getFunctionVersion();
        }
        if (version == null || !version.matches("[0-9]+")) {
            throw invalid("Provisioned concurrency requires an immutable published version");
        }
        LambdaFunction fn = functions.getForAccount(account, region, ref.name(), version)
                .orElseThrow(() -> missing("Function version not found"));
        return new Configuration(account, region, ref.name(), baseArn(latest), qualifier,
                version, fn.getRevisionId(), count, Instant.ofEpochMilli(clock.millis()).toString());
    }

    private LambdaFunction currentFunction(Configuration configuration) {
        Configuration now = resolve(configuration.region(), configuration.name(), configuration.qualifier(),
                configuration.account(), configuration.requested());
        if (!sameTarget(configuration, now)) {
            throw new IllegalStateException("Provisioned concurrency target changed");
        }
        return functions.getForAccount(configuration.account(), configuration.region(),
                configuration.name(), configuration.version()).orElseThrow(() -> missing("Version not found"));
    }

    private static boolean sameTarget(Configuration first, Configuration second) {
        return first.key().equals(second.key()) && first.version().equals(second.version())
                && Objects.equals(first.revision(), second.revision());
    }

    private static String baseArn(LambdaFunction fn) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(fn.getFunctionArn());
        return new AwsArnUtils.Arn(arn.partition(), "lambda", arn.region(), arn.accountId(),
                "function:" + fn.getFunctionName()).toString();
    }

    private void write(Configuration value) {
        if (storage instanceof AccountAwareStorageBackend<Configuration> aware) {
            aware.putForAccount(value.account(), value.key(), value);
        } else {
            storage.put(value.key(), value);
        }
    }

    private void erase(Configuration value) {
        if (storage instanceof AccountAwareStorageBackend<Configuration> aware) {
            aware.deleteForAccount(value.account(), value.key());
        } else {
            storage.delete(value.key());
        }
    }

    @Override
    public void clear() {
        stopManagedContainers();
        synchronized (lock) {
            storage.clear();
            stopped = false;
        }
    }

    @Override
    public void stopManagedContainers() {
        List<ContainerHandle> retired = new ArrayList<>();
        synchronized (lock) {
            stopped = true;
            allocations.values().forEach(entry -> retired.addAll(retire(entry)));
            allocations.clear();
            retired.addAll(pendingTeardown);
        }
        stop(retired);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        stopManagedContainers();
        workers.shutdownNow();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    private static AwsException missing(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }
}
