package io.github.hectorvent.floci.services.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.SignedMarkers;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.batch.model.StoredSchedulingPolicy;
import io.github.hectorvent.floci.services.batch.model.StoredSchedulingPolicy.Fairshare;
import io.github.hectorvent.floci.services.batch.model.StoredSchedulingPolicy.Quota;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.github.hectorvent.floci.services.batch.BatchSchedulingPolicyReader.invalid;

/** The contextual monitor protects each immutable scoped snapshot and its single publication. */
@ApplicationScoped
public class BatchSchedulingPolicyService implements Resettable, ResourceProvider {
    private static final Fairshare EMPTY_FAIRSHARE = new Fairshare(0, 0, List.of());
    private final AccountAwareStorageBackend<StoredSchedulingPolicy> policies;
    private final RegionResolver regions;
    private final ObjectMapper mapper;
    private SignedMarkers markers = new SignedMarkers(2048);
    private boolean resetting;

    @Inject
    public BatchSchedulingPolicyService(StorageFactory storage, RegionResolver regions, ObjectMapper mapper) {
        policies = storage.create("batch", "batch-scheduling-policies.json",
                new TypeReference<Map<String, StoredSchedulingPolicy>>() {});
        this.regions = regions;
        this.mapper = mapper;
    }

    public synchronized Map<String, String> create(JsonNode request) {
        admit();
        Scope scope = scope();
        String name = BatchSchedulingPolicyTarget.name(BatchSchedulingPolicyReader.text(request, "name", true));
        Configuration configuration = configuration(request);
        Map<String, String> tags = BatchSchedulingPolicyReader.tags(request, false);
        if (read(scope, name) != null) {
            throw new AwsException("ClientException", "A scheduling policy with this name already exists.", 409);
        }
        String arn = BatchSchedulingPolicyTarget.arn(scope.partition(), scope.region(), scope.account(), name);
        long now = Instant.now().toEpochMilli();
        StoredSchedulingPolicy policy = new StoredSchedulingPolicy(1, scope.account(), scope.partition(), scope.region(),
                name, arn, UUID.randomUUID().toString(), 0, configuration.kind(), configuration.fairshare(),
                configuration.quota(), tags, now, now);
        publish(scope, policy);
        return Map.of("name", name, "arn", arn);
    }

    public synchronized Map<String, Object> update(JsonNode request) {
        admit();
        Scope scope = scope();
        String name = selected(scope, BatchSchedulingPolicyReader.text(request, "arn", true));
        Configuration next = configuration(request);
        StoredSchedulingPolicy current = required(scope, name);
        if ("unset".equals(next.kind())) {
            return Map.of();
        }
        if (!"unset".equals(current.kind()) && !current.kind().equals(next.kind())) {
            throw invalid("A scheduling policy cannot change its policy kind.");
        }
        publish(scope, current.replace(next.kind(), next.fairshare(), next.quota(), current.tags(),
                Instant.now().toEpochMilli()));
        return Map.of();
    }

    public synchronized Map<String, Object> delete(JsonNode request) {
        admit();
        Scope scope = scope();
        String name = selected(scope, BatchSchedulingPolicyReader.text(request, "arn", true));
        if (read(scope, name) != null) {
            policies.deleteAllAtomicallyForAccounts(Map.of(scope.account(), Set.of(key(scope, name))));
        }
        return Map.of();
    }

    public synchronized Map<String, Object> describe(JsonNode request) {
        admit();
        Scope scope = scope();
        JsonNode arns = request.get("arns");
        if (arns == null || !arns.isArray() || arns.isEmpty() || arns.size() > 100) {
            throw invalid("arns must contain 1-100 ARN strings.");
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode arn : arns) {
            if (!arn.isTextual()) {
                throw invalid("arns must contain strings.");
            }
            names.add(selected(scope, arn.textValue()));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (String name : names) {
            StoredSchedulingPolicy policy = read(scope, name);
            if (policy != null) {
                results.add(project(policy));
            }
        }
        return Map.of("schedulingPolicies", results);
    }

    public synchronized Map<String, Object> list(JsonNode request) {
        admit();
        Scope scope = scope();
        int size = BatchSchedulingPolicyReader.integer(request, "maxResults", 100, 1, 100);
        String token = BatchSchedulingPolicyReader.text(request, "nextToken", false);
        if (token != null && (token.isEmpty() || token.length() > 2048)) {
            throw invalid("nextToken must be a valid continuation token.");
        }
        List<StoredSchedulingPolicy> inventory = inventory(scope);
        String markerScope = fingerprint(scope, size, inventory);
        PaginatedResult<StoredSchedulingPolicy> page = Pagination.slice(inventory, StoredSchedulingPolicy::name,
                size, token, markers, markerScope, ignored -> invalid("nextToken belongs to a different or changed inventory."));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schedulingPolicies", page.items().stream().map(policy -> Map.of("arn", policy.arn())).toList());
        if (page.nextToken() != null) {
            result.put("nextToken", page.nextToken());
        }
        return result;
    }

    public synchronized Map<String, String> listTags(String arn) {
        admit();
        Scope scope = scope();
        return required(scope, selected(scope, arn)).tags();
    }

    public synchronized void tag(String arn, Map<String, String> added) {
        admit();
        Scope scope = scope();
        StoredSchedulingPolicy current = required(scope, selected(scope, arn));
        Map<String, String> tags = new LinkedHashMap<>(current.tags());
        tags.putAll(BatchSchedulingPolicyReader.tags(added));
        Map<String, String> validated = BatchSchedulingPolicyReader.tags(tags);
        publish(scope, current.replace(current.kind(), current.fairshare(), current.quota(), validated,
                Instant.now().toEpochMilli()));
    }

    public synchronized void untag(String arn, List<String> removed) {
        admit();
        Scope scope = scope();
        StoredSchedulingPolicy current = required(scope, selected(scope, arn));
        List<String> keys = BatchSchedulingPolicyReader.tagKeys(removed);
        Map<String, String> tags = new LinkedHashMap<>(current.tags());
        keys.forEach(tags::remove);
        publish(scope, current.replace(current.kind(), current.fairshare(), current.quota(), tags,
                Instant.now().toEpochMilli()));
    }

    /** Uses the already resolved caller and never migrates or materializes a resource. */
    public synchronized Map<String, String> iamTags(String arn, String partition, String region, String account) {
        admit();
        Scope scope = new Scope(partition, region, account);
        String name;
        try {
            name = selected(scope, arn);
        } catch (AwsException invalidTarget) {
            return Map.of();
        }
        StoredSchedulingPolicy current = read(scope, name);
        return current == null ? Map.of() : current.tags();
    }

    @Override
    public synchronized List<ExplorerResource> getResources() {
        admit();
        return inventory(scope()).stream().map(policy -> new ExplorerResource(policy.arn(),
                "batch:scheduling-policy", "batch", policy.region(), policy.account(),
                Instant.ofEpochMilli(policy.createdAtMillis()), policy.tags())).toList();
    }

    @Override
    public synchronized Set<SupportedResourceType> getSupportedResourceTypes() {
        admit();
        return Set.of(new SupportedResourceType("batch:scheduling-policy", "batch", true));
    }

    @Override
    public synchronized void beforeReset() {
        resetting = true;
    }

    @Override
    public synchronized void clear() {
        markers = new SignedMarkers(2048);
    }

    @Override
    public synchronized void afterReset() {
        markers = new SignedMarkers(2048);
        resetting = false;
    }

    private void admit() {
        if (resetting) {
            throw new AwsException("ServerException", "Scheduling policies are resetting.", 503);
        }
    }

    private static Configuration configuration(JsonNode request) {
        Fairshare fairshare = BatchSchedulingPolicyReader.fairshare(request.get("fairsharePolicy"));
        Quota quota = BatchSchedulingPolicyReader.quota(request.get("quotaSharePolicy"));
        if (fairshare != null && quota != null) {
            throw invalid("Specify fairsharePolicy or quotaSharePolicy, not both.");
        }
        return new Configuration(fairshare != null ? "fairshare" : quota != null ? "quota" : "unset", fairshare, quota);
    }

    private Scope scope() {
        return new Scope(regions.getPartition(), regions.getRegion(), regions.getAccountId());
    }

    private static String selected(Scope scope, String arn) {
        return BatchSchedulingPolicyTarget.selectedName(arn, scope.partition(), scope.region(), scope.account());
    }

    private static String prefix(Scope scope) {
        return scope.partition() + "/" + scope.region() + "/";
    }

    private static String key(Scope scope, String name) {
        return prefix(scope) + name;
    }

    private StoredSchedulingPolicy required(Scope scope, String name) {
        StoredSchedulingPolicy current = read(scope, name);
        if (current == null) {
            throw invalid("Scheduling policy does not exist.");
        }
        return current;
    }

    private StoredSchedulingPolicy read(Scope scope, String name) {
        StoredSchedulingPolicy current = policies.getForAccount(scope.account(), key(scope, name)).orElse(null);
        if (current != null) {
            validateStored(scope, name, current);
        }
        return current;
    }

    private void validateStored(Scope scope, String name, StoredSchedulingPolicy current) {
        try {
            if (current.schemaVersion() != 1 || !scope.account().equals(current.account())
                    || !scope.partition().equals(current.partition()) || !scope.region().equals(current.region())
                    || !name.equals(current.name())
                    || !BatchSchedulingPolicyTarget.arn(scope.partition(), scope.region(), scope.account(), name).equals(current.arn())
                    || current.generation() == null || current.generation().isEmpty() || current.revision() < 0
                    || current.createdAtMillis() < 0 || current.updatedAtMillis() < current.createdAtMillis()) {
                throw corrupt();
            }
            boolean correctKind = switch (current.kind()) {
                case "unset" -> current.fairshare() == null && current.quota() == null;
                case "fairshare" -> current.fairshare() != null && current.quota() == null
                        && current.fairshare().equals(BatchSchedulingPolicyReader.fairshare(mapper.valueToTree(current.fairshare())));
                case "quota" -> current.fairshare() == null && current.quota() != null
                        && current.quota().equals(BatchSchedulingPolicyReader.quota(mapper.valueToTree(current.quota())));
                default -> false;
            };
            if (!correctKind || !current.tags().equals(BatchSchedulingPolicyReader.tags(current.tags()))) {
                throw corrupt();
            }
        } catch (AwsException | IllegalArgumentException | NullPointerException invalidRecord) {
            throw corrupt();
        }
    }

    private static AwsException corrupt() {
        return new AwsException("ServerException", "Unsupported or inconsistent stored scheduling policy.", 500);
    }

    private List<StoredSchedulingPolicy> inventory(Scope scope) {
        List<StoredSchedulingPolicy> result = new ArrayList<>();
        for (String key : policies.keysForAccount(scope.account())) {
            if (key.startsWith(prefix(scope))) {
                String name = key.substring(prefix(scope).length());
                StoredSchedulingPolicy policy = read(scope, name);
                if (policy != null) {
                    result.add(policy);
                }
            }
        }
        return result.stream().sorted(Comparator.comparing(StoredSchedulingPolicy::name)).toList();
    }

    private void publish(Scope scope, StoredSchedulingPolicy policy) {
        policies.putAllAtomicallyForAccount(scope.account(), Map.of(key(scope, policy.name()), policy));
    }

    private static Map<String, Object> project(StoredSchedulingPolicy policy) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", policy.name());
        result.put("arn", policy.arn());
        result.put("tags", policy.tags());
        if ("quota".equals(policy.kind())) {
            result.put("quotaSharePolicy", policy.quota());
        } else {
            result.put("fairsharePolicy", policy.fairshare() == null ? EMPTY_FAIRSHARE : policy.fairshare());
        }
        return result;
    }

    private String fingerprint(Scope scope, int size, List<StoredSchedulingPolicy> inventory) {
        try {
            byte[] payload = mapper.writeValueAsBytes(List.of("ListSchedulingPolicies", scope.partition(),
                    scope.account(), scope.region(), size,
                    inventory.stream().map(policy -> List.of(policy.name(), policy.generation())).toList()));
            return Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot fingerprint scheduling-policy inventory", failure);
        }
    }

    private record Scope(String partition, String region, String account) {}

    private record Configuration(String kind, Fairshare fairshare, Quota quota) {}
}
