package io.floci.aws.services.lambda;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.model.LambdaRuntimeManagementConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Wraps the storage backend for Lambda functions with region-aware key logic.
 */
@ApplicationScoped
public class LambdaFunctionStore implements Resettable {

    private final StorageBackend<String, LambdaFunction> backend;
    private final StorageBackend<String, LambdaRuntimeManagementConfig> runtimeManagement;
    private final ConcurrentHashMap<String, LambdaFunction> urlIdIndex = new ConcurrentHashMap<>();
    // Retain locks across deletion and reset so a queued operation cannot race a newly created lock.
    private final ConcurrentHashMap<String, Object> concurrencyOpLocks = new ConcurrentHashMap<>();

    @Inject
    public LambdaFunctionStore(StorageFactory storageFactory) {
        this.backend = storageFactory.create("lambda", "lambda-functions.json",
                new TypeReference<>() {
                });
        this.runtimeManagement = storageFactory.create("lambda", "lambda-runtime-management.json",
                new TypeReference<>() {
                });
        loadIndex();
    }

    LambdaFunctionStore(StorageBackend<String, LambdaFunction> backend) {
        this(backend, null);
    }

    LambdaFunctionStore(StorageBackend<String, LambdaFunction> backend,
                        StorageBackend<String, LambdaRuntimeManagementConfig> runtimeManagement) {
        this.backend = backend;
        this.runtimeManagement = runtimeManagement;
        loadIndex();
    }

    Object lockForConcurrencyOp(String functionArn) {
        return concurrencyOpLocks.computeIfAbsent(functionArn, key -> new Object());
    }

    private Object lockForSave(String accountId, String region, LambdaFunction function) {
        String arn = function.getFunctionArn();
        String key = arn == null || arn.isBlank()
                ? "legacy::" + Objects.toString(accountId, "") + "::"
                        + regionKey(region, function.getFunctionName(), function.getVersion())
                : arn;
        return lockForConcurrencyOp(key);
    }

    private void loadIndex() {
        List<LambdaFunction> all = backend instanceof AccountAwareStorageBackend<LambdaFunction> aware
                ? aware.scanAllAccounts()
                : backend.scan(key -> true);
        all.forEach(this::indexFunction);
    }

    public void clear() {
        urlIdIndex.clear();
    }

    private void indexFunction(LambdaFunction fn) {
        if (fn.getUrlConfig() != null && fn.getUrlConfig().getFunctionUrl() != null) {
            String urlId = extractUrlId(fn.getUrlConfig().getFunctionUrl());
            if (urlId != null) {
                urlIdIndex.put(urlId, fn);
            }
        }
    }

    private void deindexFunction(LambdaFunction fn) {
        if (fn.getUrlConfig() != null && fn.getUrlConfig().getFunctionUrl() != null) {
            String urlId = extractUrlId(fn.getUrlConfig().getFunctionUrl());
            if (urlId != null) {
                urlIdIndex.remove(urlId);
            }
        }
    }

    private String extractUrlId(String url) {
        // http://urlId.lambda-url.region.baseHost/
        int start = url.indexOf("://");
        if (start < 0) return null;
        int end = url.indexOf(".", start + 3);
        if (end < 0) return null;
        return url.substring(start + 3, end);
    }

    public void save(String region, LambdaFunction fn) {
        String accountId = backend instanceof AccountAwareStorageBackend<LambdaFunction> aware
                ? aware.accountId() : fn.getAccountId();
        synchronized (lockForSave(accountId, region, fn)) {
            requireCurrentGeneration(get(region, fn.getFunctionName(), fn.getVersion()), fn);
            // Remove old index entry if URL changed or was removed
            get(region, fn.getFunctionName(), fn.getVersion()).ifPresent(this::deindexFunction);

            backend.put(regionKey(region, fn.getFunctionName(), fn.getVersion()), fn);
            indexFunction(fn);
        }
    }

    /** The final create commit shares the retained lock with updates and deletion. */
    public void create(String region, LambdaFunction fn) {
        synchronized (lockForConcurrencyOp(fn.getFunctionArn())) {
            if (get(region, fn.getFunctionName()).isPresent()) {
                throw new AwsException("ResourceConflictException",
                        "Function already exist: " + fn.getFunctionName(), 409);
            }
            fn.setRuntimeManagementGeneration(UUID.randomUUID().toString());
            backend.putAllAtomically(Map.of(regionKey(region, fn.getFunctionName(), fn.getVersion()), fn));
            indexFunction(fn);
        }
    }

    private static void requireCurrentGeneration(Optional<LambdaFunction> stored, LambdaFunction candidate) {
        String generation = candidate.getRuntimeManagementGeneration();
        String current = stored.map(LambdaFunction::getRuntimeManagementGeneration).orElse(null);
        if (!Objects.equals(generation, current)) {
            throw new AwsException("ResourceConflictException", "Function generation has changed", 409);
        }
    }

    /**
     * Saves into {@code accountId}'s partition rather than the ambient caller's. Async
     * paths (CDI observers, pollers) run with no {@code RequestContext} and would
     * otherwise write every account's function into the default account's partition.
     */
    public void saveForAccount(String accountId, String region, LambdaFunction fn) {
        synchronized (lockForSave(accountId, region, fn)) {
            requireCurrentGeneration(getReadOnlyForAccount(accountId, region,
                    fn.getFunctionName(), fn.getVersion()), fn);
            getForAccount(accountId, region, fn.getFunctionName(), fn.getVersion()).ifPresent(this::deindexFunction);

            if (backend instanceof AccountAwareStorageBackend<LambdaFunction> aware) {
                aware.putForAccount(accountId, regionKey(region, fn.getFunctionName(), fn.getVersion()), fn);
            } else {
                backend.put(regionKey(region, fn.getFunctionName(), fn.getVersion()), fn);
            }
            indexFunction(fn);
        }
    }

    public Optional<LambdaFunction> get(String region, String functionName) {
        return get(region, functionName, "$LATEST");
    }

    public Optional<LambdaFunction> get(String region, String functionName, String version) {
        return backend.get(regionKey(region, functionName, version));
    }

    public Optional<LambdaFunction> getForAccount(String accountId, String region, String functionName) {
        return getForAccount(accountId, region, functionName, "$LATEST");
    }

    public Optional<LambdaFunction> getForAccount(
            String accountId, String region, String functionName, String version) {
        if (backend instanceof AccountAwareStorageBackend<LambdaFunction> aware) {
            return aware.getForAccountMigratingLegacy(
                    accountId,
                    regionKey(region, functionName, version),
                    function -> belongsToAccount(function, accountId));
        }
        return backend.get(regionKey(region, functionName, version));
    }

    /** Scoped reads and default-account legacy visibility, without migration or initialization. */
    public Optional<LambdaFunction> getReadOnlyForAccount(
            String accountId, String region, String functionName, String version) {
        String key = regionKey(region, functionName, version);
        Optional<LambdaFunction> result;
        if (backend instanceof AccountAwareStorageBackend<LambdaFunction> aware) {
            result = aware.getForAccount(accountId, key)
                    .or(() -> aware.getLegacyForAccount(accountId, key));
        } else {
            result = backend.get(key);
        }
        return result.filter(fn -> belongsToAccount(fn, accountId))
                .filter(fn -> functionName.equals(fn.getFunctionName()) && version.equals(fn.getVersion()))
                .filter(fn -> {
                    String expected = AwsArnUtils.Arn.of("lambda", region, accountId,
                            "function:" + functionName).toString();
                    return ("$LATEST".equals(version) ? expected : expected + ":" + version)
                            .equals(fn.getFunctionArn());
                });
    }

    LambdaRuntimeManagementConfig runtimeManagementConfig(
            String account, String region, LambdaFunction latest, String version) {
        if (latest.getRuntimeManagementGeneration() == null || runtimeManagement == null) {
            return LambdaRuntimeManagementConfig.DEFAULT;
        }
        String key = runtimeManagementKey(region, latest.getRuntimeManagementGeneration(), version);
        Optional<LambdaRuntimeManagementConfig> result =
                runtimeManagement instanceof AccountAwareStorageBackend<LambdaRuntimeManagementConfig> aware
                        ? aware.getForAccount(account, key) : runtimeManagement.get(key);
        return result.orElse(LambdaRuntimeManagementConfig.DEFAULT);
    }

    void putRuntimeManagementConfig(String account, String region, LambdaFunction latest, String version,
                                    LambdaRuntimeManagementConfig configuration) {
        if (runtimeManagement == null) {
            throw new IllegalStateException("Runtime management storage is unavailable");
        }
        if (latest.getRuntimeManagementGeneration() == null) {
            // Authorized migration is a separate commit; the generation write leaves URL indexes intact.
            getForAccount(account, region, latest.getFunctionName(), "$LATEST")
                    .orElseThrow(() -> LambdaRuntimeManagementReference.missing(latest.getFunctionName()));
            latest.setRuntimeManagementGeneration(UUID.randomUUID().toString());
            try {
                Map<String, LambdaFunction> entry = Map.of(
                        regionKey(region, latest.getFunctionName(), "$LATEST"), latest);
                if (backend instanceof AccountAwareStorageBackend<LambdaFunction> aware) {
                    aware.putAllAtomicallyForAccount(account, entry);
                } else {
                    backend.putAllAtomically(entry);
                }
            } catch (RuntimeException failure) {
                latest.setRuntimeManagementGeneration(null);
                throw failure;
            }
        }
        Map<String, LambdaRuntimeManagementConfig> entry = Map.of(
                runtimeManagementKey(region, latest.getRuntimeManagementGeneration(), version), configuration);
        if (runtimeManagement instanceof AccountAwareStorageBackend<LambdaRuntimeManagementConfig> aware) {
            aware.putAllAtomicallyForAccount(account, entry);
        } else {
            runtimeManagement.putAllAtomically(entry);
        }
    }

    private static String runtimeManagementKey(String region, String generation, String version) {
        return "runtime::" + region + "::" + generation + "::" + version;
    }

    private void deleteRuntimeManagement(String region, LambdaFunction latest, String version) {
        if (runtimeManagement == null || latest.getRuntimeManagementGeneration() == null) {
            return;
        }
        String prefix = runtimeManagementKey(region, latest.getRuntimeManagementGeneration(), "");
        String account = AwsArnUtils.accountOrDefault(latest.getFunctionArn(), "");
        Set<String> keys = runtimeManagement instanceof AccountAwareStorageBackend<LambdaRuntimeManagementConfig> aware
                ? aware.keysForAccount(account) : runtimeManagement.keys();
        Set<String> selected = keys.stream()
                .filter(key -> version == null ? key.startsWith(prefix) : key.equals(prefix + version))
                .collect(Collectors.toSet());
        if (selected.isEmpty()) {
            return;
        }
        if (runtimeManagement instanceof AccountAwareStorageBackend<LambdaRuntimeManagementConfig> aware) {
            aware.deleteAllAtomicallyForAccounts(Map.of(account, selected));
        } else {
            runtimeManagement.deleteAllAtomically(selected);
        }
    }

    private static boolean belongsToAccount(LambdaFunction function, String accountId) {
        if (function.getAccountId() != null && !function.getAccountId().isBlank()) {
            return accountId.equals(function.getAccountId());
        }
        return accountId.equals(AwsArnUtils.accountOrDefault(function.getFunctionArn(), ""));
    }

    public Optional<LambdaFunction> getByUrlId(String urlId) {
        return Optional.ofNullable(urlIdIndex.get(urlId));
    }

    public List<LambdaFunction> list(String region) {
        String prefix = "lambda::" + region + "::";
        return backend.scan(key -> key.startsWith(prefix) && key.endsWith("::$LATEST"));
    }

    /** Lists the caller's {@code $LATEST} functions without relying on request context. */
    public List<LambdaFunction> listForAccount(String accountId, String region) {
        String prefix = "lambda::" + region + "::";
        if (backend instanceof AccountAwareStorageBackend<LambdaFunction> aware) {
            return aware.scanForAccount(accountId,
                    key -> key.startsWith(prefix) && key.endsWith("::$LATEST"));
        }
        return list(region).stream()
                .filter(function -> belongsToAccount(function, accountId))
                .toList();
    }

    public List<LambdaFunction> listVersions(String region, String functionName) {
        String prefix = "lambda::" + region + "::" + functionName + "::";
        List<LambdaFunction> versions = new ArrayList<>(backend.scan(key -> key.startsWith(prefix)));
        versions.sort(Comparator.comparingLong(LambdaFunctionStore::versionRank));
        return versions;
    }

    private static long versionRank(LambdaFunction fn) {
        String version = fn.getVersion();
        if (version == null || version.isEmpty() || !version.chars().allMatch(Character::isDigit)) {
            return -1L;
        }
        return Long.parseLong(version);
    }

    /**
     * Like {@link #list(String)}, but across every account's partition. For async callers
     * that have no {@code RequestContext} and so would otherwise see only the default
     * account's functions.
     */
    public List<LambdaFunction> listAllAccounts(String region) {
        String prefix = "lambda::" + region + "::";
        Predicate<String> filter = key -> key.startsWith(prefix) && key.endsWith("::$LATEST");
        if (backend instanceof AccountAwareStorageBackend<LambdaFunction> aware) {
            return aware.scanAllAccountEntries(filter).stream()
                    .map(AccountAwareStorageBackend.AccountEntry::value)
                    .toList();
        }
        return backend.scan(filter);
    }

    public List<LambdaFunction> listAll() {
        return backend instanceof AccountAwareStorageBackend<LambdaFunction> aware
                ? aware.scanAllAccounts()
                : backend.scan(key -> true);
    }

    public void delete(String region, String functionName) {
        // Clear settings first. A later function-store failure may leave default settings, never stale pins.
        get(region, functionName).ifPresent(fn -> deleteRuntimeManagement(region, fn, null));
        // Delete all versions
        listVersions(region, functionName).forEach(fn -> {
            deindexFunction(fn);
            backend.delete(regionKey(region, functionName, fn.getVersion()));
        });
    }

    /** Deletes one published version, leaving {@code $LATEST} and the other versions in place. */
    public void deleteVersion(String region, String functionName, String version) {
        get(region, functionName).ifPresent(fn -> deleteRuntimeManagement(region, fn, version));
        get(region, functionName, version).ifPresent(fn -> {
            deindexFunction(fn);
            backend.delete(regionKey(region, functionName, version));
        });
    }

    private static String regionKey(String region, String functionName, String version) {
        return "lambda::" + region + "::" + functionName + "::" + (version != null ? version : "$LATEST");
    }
}
