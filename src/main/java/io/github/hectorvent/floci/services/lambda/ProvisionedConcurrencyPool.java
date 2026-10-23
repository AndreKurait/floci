package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stores desired capacity. Runtime allocation is introduced by the next stage. */
@ApplicationScoped
public class ProvisionedConcurrencyPool implements Resettable {
    private static final DateTimeFormatter LAST_MODIFIED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZone(ZoneOffset.UTC);
    private final Object lock = new Object();
    private final LambdaFunctionStore functions;
    private final LambdaAliasStore aliases;
    private final StorageBackend<String, Configuration> storage;
    private final Clock clock;
    private final int maxPerFunction;
    private final int maxTotal;
    private final boolean supported;
    private final Map<String, Configuration> configurations = new HashMap<>();

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

    @Inject
    public ProvisionedConcurrencyPool(LambdaFunctionStore functions, LambdaAliasStore aliases,
                                     StorageFactory storageFactory, EmulatorConfig config, Clock clock) {
        this(functions, aliases, storageFactory.create("lambda",
                        "lambda-provisioned-concurrency.json", new TypeReference<>() {}),
                clock, WarmPool.resolveMaxPerFunction(config), WarmPool.resolveMaxTotal(config),
                !config.services().lambda().ephemeral()
                        && "docker".equalsIgnoreCase(config.services().lambda().executor().trim()));
    }

    ProvisionedConcurrencyPool(LambdaFunctionStore functions, LambdaAliasStore aliases,
                              StorageBackend<String, Configuration> storage,
                              Clock clock, int maxPerFunction, int maxTotal) {
        this(functions, aliases, storage, clock, maxPerFunction, maxTotal, true);
    }

    ProvisionedConcurrencyPool(LambdaFunctionStore functions, LambdaAliasStore aliases,
                              StorageBackend<String, Configuration> storage,
                              Clock clock, int maxPerFunction, int maxTotal, boolean supported) {
        this.supported = supported;
        this.functions = functions;
        this.aliases = aliases;
        this.storage = storage;
        this.clock = clock;
        this.maxPerFunction = maxPerFunction;
        this.maxTotal = maxTotal;
    }

    @PostConstruct
    void start() {
        synchronized (lock) {
            List<Configuration> stored = storage instanceof AccountAwareStorageBackend<Configuration> aware
                    ? aware.scanAllAccounts() : storage.scan(ignored -> true);
            for (Configuration configuration : stored) {
                configurations.put(configuration.key(), configuration);
            }
        }
    }

    public Status put(String region, String name, String qualifier, int count, String account) {
        if (count < 1) {
            throw invalidCount(count);
        }
        if ("$LATEST".equals(qualifier)) {
            throw invalid("Provisioned Concurrency Configs cannot be applied to unpublished function versions.");
        }
        Configuration target = resolve(region, name, qualifier, account, count);
        synchronized (functions.lockForConcurrencyOp(target.functionArn())) {
            return put(resolve(region, name, qualifier, account, count));
        }
    }

    private Status put(Configuration target) {
        int count = target.requested();
        if (!supported) {
            throw invalid("Provisioned concurrency requires the non-ephemeral Docker Lambda executor");
        }
        LambdaFunction fn = functions.getForAccount(target.account(), target.region(), target.name(), target.version())
                .orElseThrow(() -> missing("Function no longer exists"));
        if (fn.isHotReload() || fn.isDurable()) {
            throw invalid("Provisioned concurrency is not supported for hot-reload or durable functions");
        }
        synchronized (lock) {
            Configuration previous = configurations.get(target.key());
            long otherFunction = 0;
            long otherTotal = 0;
            boolean versionConflict = false;
            for (Configuration entry : configurations.values()) {
                if (entry == previous) {
                    continue;
                }
                otherTotal += entry.requested();
                if (target.functionArn().equals(entry.functionArn())) {
                    versionConflict |= target.version().equals(pointedVersion(entry));
                    otherFunction += entry.requested();
                }
            }
            if (otherFunction + count > maxPerFunction
                    || (maxTotal > 0 && otherTotal + count > maxTotal)) {
                throw invalid("Requested provisioned concurrency exceeds the configured local warm-pool capacity");
            }
            LambdaFunction latest = functions.getForAccount(target.account(), target.region(), target.name()).orElseThrow(
                    () -> missing("Function no longer exists"));
            Integer reserved = latest.getReservedConcurrentExecutions();
            if (reserved != null && otherFunction + count > reserved) {
                throw invalid("Provisioned concurrency cannot exceed the function's reserved concurrency");
            }
            if (versionConflict) {
                throw new AwsException("ResourceConflictException",
                        target.qualifier().equals(target.version())
                                ? "Version is pointed by a Provisioned Concurrency alias"
                                : "Alias can't be used for Provisioned Concurrency configuration "
                                        + "on an already Provisioned version", 409);
            }
            if (previous != null && sameTarget(previous, target) && previous.requested() == count) {
                return status(previous);
            }
            write(target);
            configurations.put(target.key(), target);
            return status(target);
        }
    }

    public Status get(String region, String name, String qualifier, String account) {
        Target target = resolveTarget(region, name, qualifier, account);
        synchronized (lock) {
            Configuration configuration = configurations.get(target.key());
            if (configuration == null) {
                throw new AwsException("ProvisionedConcurrencyConfigNotFoundException",
                        "No Provisioned Concurrency Config found for this function", 404);
            }
            return status(configuration);
        }
    }

    public void delete(String region, String name, String qualifier, String account) {
        Target target = resolveTarget(region, name, qualifier, account);
        synchronized (functions.lockForConcurrencyOp(target.functionArn())) {
            resolveTarget(region, name, qualifier, account);
            deleteMatching(target.functionArn(), qualifier);
        }
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
            long requested = configurations.values().stream()
                    .filter(entry -> entry.functionArn().equals(baseArn(fn)))
                    .mapToLong(entry -> entry.requested()).sum();
            if (requested > reserved) {
                throw invalid("Reserved concurrency cannot be lower than provisioned concurrency");
            }
        }
    }

    private void deleteMatching(String functionArn, String qualifier) {
        synchronized (lock) {
            for (Configuration value : new ArrayList<>(configurations.values())) {
                if (value.functionArn().equals(functionArn)
                        && (qualifier == null || qualifier.equals(value.qualifier()))) {
                    erase(value);
                    configurations.remove(value.key());
                }
            }
        }
    }

    private Status status(Configuration configuration) {
        return new Status(configuration.requested(), 0, 0, "IN_PROGRESS", null, configuration.lastModified());
    }

    private String pointedVersion(Configuration configuration) {
        if (configuration.qualifier().matches("[0-9]+")) {
            return configuration.version();
        }
        return aliases.getForAccount(configuration.account(), configuration.region(),
                configuration.name(), configuration.qualifier())
                .map(LambdaAlias::getFunctionVersion).orElse(null);
    }

    void validateAliasUpdate(LambdaFunction fn, String qualifier, String version) {
        synchronized (lock) {
            String functionArn = baseArn(fn);
            if (!configurations.containsKey(functionArn + ":" + qualifier)) {
                return;
            }
            if ("$LATEST".equals(version)) {
                throw invalid("Provisioned Concurrency Configs cannot be applied to unpublished function versions.");
            }
            if (version != null && version.matches("[0-9]+") && configurations.values().stream()
                    .anyMatch(entry -> functionArn.equals(entry.functionArn())
                            && !qualifier.equals(entry.qualifier())
                            && version.equals(pointedVersion(entry)))) {
                throw new AwsException("ResourceConflictException",
                        "Alias can't be used for Provisioned Concurrency configuration on an already Provisioned version", 409);
            }
        }
    }

    private record Target(String account, String region, String name, String functionArn,
                          String qualifier, LambdaAlias alias) {
        String key() {
            return functionArn + ":" + qualifier;
        }

        String missingMessage() {
            return (alias == null ? "Function not found: " : "Cannot find alias arn: ") + key();
        }
    }

    private Configuration resolve(String region, String name, String qualifier, String account, int count) {
        Target target = resolveTarget(region, name, qualifier, account);
        LambdaAlias alias = target.alias();
        if (alias != null && alias.getRoutingConfig() != null && !alias.getRoutingConfig().isEmpty()) {
            throw invalid("Weighted aliases are not supported for provisioned concurrency");
        }
        String version = alias == null ? qualifier : alias.getFunctionVersion();
        if (version == null || !version.matches("[0-9]+")) {
            throw invalid("Provisioned concurrency requires an immutable published version");
        }
        LambdaFunction fn = functions.getForAccount(target.account(), region, target.name(), version)
                .orElseThrow(() -> missing(target.missingMessage()));
        return new Configuration(target.account(), region, target.name(), target.functionArn(), qualifier,
                version, fn.getRevisionId(), count, LAST_MODIFIED.format(Instant.ofEpochMilli(clock.millis())));
    }

    private Target resolveTarget(String region, String name, String qualifier, String account) {
        if (qualifier == null || qualifier.isBlank() || qualifier.length() > 128) {
            throw invalid("Qualifier is required and must contain 1 to 128 characters");
        }
        if ("$LATEST".equals(qualifier)) {
            throw invalid("The function resource provided must be an alias or a published version.");
        }
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolveWithQualifier(name, qualifier);
        if (ref.region() != null && !region.equals(ref.region())) {
            throw invalid("Function ARN region must match the request region");
        }
        account = ref.account() != null ? ref.account() : account;
        boolean publishedVersion = qualifier.matches("[0-9]+");
        String qualifiedArn = AwsArnUtils.Arn.of("lambda", region, account,
                "function:" + ref.name() + ":" + qualifier).toString();
        String notFoundMessage = (publishedVersion ? "Function not found: " : "Cannot find alias arn: ")
                + qualifiedArn;
        LambdaFunction latest = functions.getForAccount(account, region, ref.name())
                .orElseThrow(() -> missing(notFoundMessage));
        LambdaAlias alias = null;
        if (publishedVersion) {
            functions.getForAccount(account, region, ref.name(), qualifier)
                    .orElseThrow(() -> missing(notFoundMessage));
        } else {
            alias = aliases.getForAccount(account, region, ref.name(), qualifier)
                    .orElseThrow(() -> missing(notFoundMessage));
        }
        return new Target(account, region, ref.name(), baseArn(latest), qualifier, alias);
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
        synchronized (lock) {
            storage.clear();
            configurations.clear();
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    static AwsException invalidCount(Integer count) {
        String value = count == null ? "null" : "'" + count + "'";
        String constraint = count == null ? "Member must not be null"
                : "Member must have value greater than or equal to 1";
        return new AwsException("ValidationException", "1 validation error detected: Value "
                + value + " at 'provisionedConcurrentExecutions' failed to satisfy constraint: "
                + constraint, 400);
    }

    private static AwsException missing(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }
}
