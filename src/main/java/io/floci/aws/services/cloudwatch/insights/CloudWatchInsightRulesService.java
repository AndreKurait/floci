package io.floci.aws.services.cloudwatch.insights;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.insights.model.InsightContribution;
import io.floci.aws.services.cloudwatch.insights.model.InsightRule;
import io.floci.aws.services.cloudwatch.logs.LogEventsIngested;
import io.floci.aws.services.cloudwatch.logs.model.LogEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static io.floci.aws.services.cloudwatch.insights.InsightRuleDefinition.invalid;
import static io.floci.aws.services.cloudwatch.insights.InsightRuleDefinition.unsupported;

@ApplicationScoped
public class CloudWatchInsightRulesService implements Resettable {
    private final StorageBackend<String, InsightRule> rules;
    private final RegionResolver regions;
    private final LongSupplier clock;
    private boolean paused;

    @Inject
    public CloudWatchInsightRulesService(StorageFactory storageFactory, RegionResolver regions) {
        this(storageFactory.create("cloudwatchmetrics", "cwinsightrules.json",
                new TypeReference<Map<String, InsightRule>>() {}), regions);
    }

    public CloudWatchInsightRulesService(StorageBackend<String, InsightRule> rules, RegionResolver regions) {
        this(rules, regions, System::currentTimeMillis);
    }

    public CloudWatchInsightRulesService(StorageBackend<String, InsightRule> rules, RegionResolver regions,
                                        LongSupplier clock) {
        this.rules = rules;
        this.regions = regions;
        this.clock = clock;
    }

    public synchronized InsightRule put(String name, String definition, String state, Boolean transformed,
                                        Map<String, String> tags, String region) {
        validate(name, definition, state, transformed);
        InsightRule previous = rules.get(key(region, name)).orElse(null);
        validateHistoryKeys(previous, definition);
        Map<String, String> selectedTags = previous == null ? validateTags(tags) : previous.tags();
        String selectedState = state;
        if (selectedState == null) {
            selectedState = previous == null ? "ENABLED" : previous.state();
        }
        InsightRule next = new InsightRule(name, arn(name, region), definition, selectedState,
                selectedTags, previous == null ? null : previous.ownerToken(),
                previous == null ? List.of() : previous.history());
        commit(next, region);
        return next;
    }

    /**
     * A stack cannot adopt an existing native rule. A replacing update keeps the logical owner's
     * token so cleanup can refuse a same-named native rule recreated after an out-of-band delete.
     */
    public synchronized InsightRule putOwned(String name, String definition, String state, Boolean transformed,
            Map<String, String> tags, Set<String> previouslyOwnedTags, String owner, boolean update,
            Consumer<InsightRule> beforeCommit, String region) {
        validate(name, definition, state, transformed);
        if (owner == null || owner.isEmpty()) {
            throw new IllegalArgumentException("CloudFormation owner token is required");
        }
        InsightRule previous = rules.get(key(region, name)).orElse(null);
        if (previous != null && (!update || !owner.equals(previous.ownerToken()))) {
            throw new AwsException("AlreadyExistsException", "Insight rule already exists: " + name, 400);
        }
        validateHistoryKeys(previous, definition);
        Map<String, String> reconciled = new LinkedHashMap<>(previous == null ? Map.of() : previous.tags());
        previouslyOwnedTags.forEach(reconciled::remove);
        reconciled.putAll(tags);
        InsightRule next = new InsightRule(name, arn(name, region), definition, state == null ? "ENABLED" : state,
                validateTags(reconciled), owner, previous == null ? List.of() : previous.history());
        beforeCommit.accept(previous);
        commit(next, region);
        return next;
    }

    public synchronized PaginatedResult<InsightRule> describe(Integer maximum, String token, String region) {
        int limit = maximum == null ? 500 : maximum;
        if (limit < 1 || limit > 500) {
            throw invalid("MaxResults must be between 1 and 500.");
        }
        return Pagination.paginate(rules.scan(key -> key.startsWith(region + "::")), InsightRule::name,
                limit, token, "insight-rules:" + regions.getAccountId() + ":" + region,
                ignored -> new AwsException("InvalidNextToken", "Invalid NextToken.", 400));
    }

    public synchronized List<Failure> change(List<String> names, String operation, String region) {
        if (names == null || names.isEmpty()) {
            throw new AwsException("MissingParameter", "RuleNames is required.", 400);
        }
        names.forEach(CloudWatchInsightRulesService::validateName);
        List<Failure> failures = new ArrayList<>();
        for (String name : names) {
            InsightRule rule = rules.get(key(region, name)).orElse(null);
            if (rule == null) {
                if (!"DeleteInsightRules".equals(operation)) {
                    failures.add(new Failure(name, "ResourceNotFoundException",
                            "RULE_NAME", "Rule " + name + " does not exist."));
                }
                continue;
            }
            if ("DeleteInsightRules".equals(operation)) {
                rules.deleteAllAtomically(Set.of(key(region, name)));
            } else {
                commit(rule.withState("EnableInsightRules".equals(operation) ? "ENABLED" : "DISABLED"), region);
            }
        }
        return List.copyOf(failures);
    }

    public record Failure(String resource, String exceptionType, String code, String description) {}

    public synchronized InsightRuleReport report(String name, Instant start, Instant end, Integer period,
                                                 Integer maximum, String order, List<String> metrics, String region) {
        validateName(name);
        InsightRule rule = rules.get(key(region, name))
                .orElseThrow(() -> invalid("RULE_NAME: " + name));
        return InsightRuleReport.create(rule, start, end, period, maximum, order, metrics,
                Instant.ofEpochMilli(clock.getAsLong()));
    }

    public synchronized void ingest(LogEventsIngested batch) {
        if (paused || batch.events().isEmpty()) {
            return;
        }
        String account = batch.accountId() == null || batch.accountId().isBlank()
                ? regions.getAccountId() : batch.accountId();
        AccountAwareStorageBackend<InsightRule> scoped = accountStore();
        if (scoped == null && !account.equals(regions.getAccountId())) {
            return;
        }
        List<InsightRule> candidates = scoped == null
                ? rules.scan(key -> key.startsWith(batch.region() + "::"))
                : scoped.scanForAccount(account, key -> key.startsWith(batch.region() + "::"));
        Map<String, InsightRule> changes = new LinkedHashMap<>();
        long oldest = clock.getAsLong() - 15 * 86400000L;
        for (InsightRule rule : candidates) {
            if (!"ENABLED".equals(rule.state())) {
                continue;
            }
            InsightRuleDefinition definition = InsightRuleDefinition.parse(rule.definition());
            Map<ContributionKey, Long> counts = new LinkedHashMap<>();
            for (InsightContribution previous : rule.history()) {
                if (previous.timestamp() >= oldest) {
                    counts.put(new ContributionKey(previous.timestamp(), previous.keys()), previous.count());
                }
            }
            boolean changed = false;
            for (LogEvent event : batch.events()) {
                List<String> keys = definition.contribution(batch.logGroupName(), event.getMessage());
                if (!keys.isEmpty() && event.getTimestamp() >= oldest) {
                    long bucket = Math.floorDiv(event.getTimestamp(), 60000) * 60000;
                    counts.merge(new ContributionKey(bucket, keys), 1L, Math::addExact);
                    changed = true;
                }
            }
            if (changed) {
                List<InsightContribution> history = counts.entrySet().stream()
                        .map(entry -> new InsightContribution(entry.getKey().timestamp(),
                                entry.getKey().keys(), entry.getValue())).toList();
                changes.put(key(batch.region(), rule.name()), rule.withHistory(history));
            }
        }
        if (!changes.isEmpty()) {
            if (scoped == null) {
                rules.putAllAtomically(changes);
            } else {
                scoped.putAllAtomicallyForAccount(account, changes);
            }
        }
    }

    private record ContributionKey(long timestamp, List<String> keys) {}

    @SuppressWarnings("unchecked")
    private AccountAwareStorageBackend<InsightRule> accountStore() {
        return rules instanceof AccountAwareStorageBackend<?> scoped
                ? (AccountAwareStorageBackend<InsightRule>) scoped : null;
    }

    public synchronized Map<String, String> listTags(String resourceArn, String region) {
        return requireArn(resourceArn, region).tags();
    }

    public synchronized void tag(String resourceArn, Map<String, String> tags, String region) {
        InsightRule rule = requireArn(resourceArn, region);
        Map<String, String> next = new LinkedHashMap<>(rule.tags());
        next.putAll(validateTags(tags));
        commit(rule.withTags(validateTags(next)), region);
    }

    public synchronized void untag(String resourceArn, List<String> keys, String region) {
        InsightRule rule = requireArn(resourceArn, region);
        Map<String, String> next = new LinkedHashMap<>(rule.tags());
        keys.forEach(next::remove);
        commit(rule.withTags(next), region);
    }

    public synchronized void deleteOwned(String resourceArn, String owner, String region) {
        InsightRule current = findArn(resourceArn, region);
        if (current == null) {
            return;
        }
        requireOwner(current, owner);
        rules.deleteAllAtomically(Set.of(key(region, current.name())));
    }

    public synchronized void restoreOwned(String name, InsightRule previous, String owner, String region) {
        InsightRule current = rules.get(key(region, name)).orElse(null);
        if (current != null) {
            requireOwner(current, owner);
        }
        if (previous == null) {
            if (current != null) {
                rules.deleteAllAtomically(Set.of(key(region, name)));
            }
        } else {
            requireOwner(previous, owner);
            commit(current == null ? previous : previous.withHistory(current.history()), region);
        }
    }

    private static void requireOwner(InsightRule rule, String owner) {
        if (owner == null || !owner.equals(rule.ownerToken())) {
            throw new AwsException("ResourceConflictException",
                    "Insight rule is no longer owned by this CloudFormation resource.", 400);
        }
    }

    private static void validateHistoryKeys(InsightRule previous, String definition) {
        if (previous != null && !previous.history().isEmpty()
                && !InsightRuleDefinition.parse(previous.definition()).keys()
                        .equals(InsightRuleDefinition.parse(definition).keys())) {
            throw unsupported("Changing contributor key paths after ingestion is not supported.");
        }
    }

    public static boolean isInsightRuleArn(String value) {
        if (!AwsArnUtils.isArn(value)) {
            return false;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(value);
        return "cloudwatch".equals(arn.service()) && arn.resource().startsWith("insight-rule/");
    }

    public String arn(String name, String region) {
        return regions.buildArn("cloudwatch", region, "insight-rule/" + name);
    }

    private InsightRule requireArn(String resourceArn, String region) {
        InsightRule rule = findArn(resourceArn, region);
        if (rule == null) {
            throw new AwsException("ResourceNotFoundException",
                    "No CloudWatch resource matches the ARN " + resourceArn + ".", 404);
        }
        return rule;
    }

    private InsightRule findArn(String resourceArn, String region) {
        return rules.scan(key -> key.startsWith(region + "::")).stream()
                .filter(rule -> Objects.equals(rule.arn(), resourceArn)).findFirst().orElse(null);
    }

    private void commit(InsightRule rule, String region) {
        rules.putAllAtomically(Map.of(key(region, rule.name()), rule));
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }

    private static void validate(String name, String definition, String state, Boolean transformed) {
        validateName(name);
        InsightRuleDefinition.parse(definition);
        if (state != null && !"ENABLED".equals(state) && !"DISABLED".equals(state)) {
            throw invalid("RuleState must be ENABLED or DISABLED.");
        }
        if (Boolean.TRUE.equals(transformed)) {
            throw unsupported("ApplyOnTransformedLogs is not supported.");
        }
    }

    private static void validateName(String name) {
        if (name == null) {
            throw new AwsException("MissingParameter", "RuleName is required.", 400);
        }
        if (name.isEmpty() || name.length() > 128
                || name.chars().anyMatch(character -> character < 32 || character > 126)) {
            throw invalid("RuleName must contain 1 to 128 printable ASCII characters.");
        }
    }

    private static Map<String, String> validateTags(Map<String, String> tags) {
        if (tags == null) {
            return Map.of();
        }
        if (tags.size() > 50) {
            throw invalid("An insight rule can have at most 50 tags.");
        }
        tags.forEach((key, value) -> {
            if (key == null || key.isEmpty() || key.length() > 128 || value == null || value.length() > 256) {
                throw invalid("Tag keys must have 1 to 128 characters and values at most 256.");
            }
        });
        return Map.copyOf(tags);
    }

    @Override
    public synchronized void beforeReset() {
        paused = true;
    }

    @Override
    public synchronized void clear() {
        // StorageFactory clears the single metadata-and-history store before invoking this hook.
    }

    @Override
    public synchronized void afterReset() {
        paused = false;
    }
}
