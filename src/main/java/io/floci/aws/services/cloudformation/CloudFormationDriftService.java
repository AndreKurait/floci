package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.SignedMarkers;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudformation.model.StackDriftRecord;
import io.floci.aws.services.cloudformation.model.StackDriftRecord.Detection;
import io.floci.aws.services.cloudformation.model.StackDriftRecord.Difference;
import io.floci.aws.services.cloudformation.model.StackDriftRecord.ResourceDrift;
import io.floci.aws.services.cloudformation.provisioners.CfnDriftProjection;
import io.floci.aws.services.cloudformation.provisioners.CfnDriftProjection.Permission;
import io.floci.aws.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Native drift results derived from declared properties and an explicitly supported provider read. */
@ApplicationScoped
public class CloudFormationDriftService {
    private static final Set<String> DETECTABLE = Set.of(
            "CREATE_COMPLETE", "UPDATE_COMPLETE", "UPDATE_ROLLBACK_COMPLETE");
    private static final Set<String> FILTERS = Set.of(
            "IN_SYNC", "MODIFIED", "DELETED", "NOT_CHECKED", "UNKNOWN", "UNSUPPORTED");
    private static final Set<String> ACTIONS = Set.of(
            "cloudformation:DetectStackDrift", "cloudformation:DetectStackResourceDrift",
            "cloudformation:DescribeStackDriftDetectionStatus", "cloudformation:DescribeStackResourceDrifts");
    private final CloudFormationService stacks;
    private final CloudFormationResourceRegistry providers;
    private final ObjectMapper json;
    private final Clock clock;
    private final AccountAwareStorageBackend<StackDriftRecord> records;
    private final SignedMarkers markers = new SignedMarkers(1024);

    @Inject
    Instance<CloudFormationDriftRequest> request;

    @Inject
    public CloudFormationDriftService(CloudFormationService stacks, CloudFormationResourceRegistry providers,
                                     ObjectMapper json, Clock clock, StorageFactory storage) {
        this(stacks, providers, json, clock, storage.create(
                "cloudformation", "cloudformation-drift.json", new TypeReference<Map<String, StackDriftRecord>>() {}));
    }

    CloudFormationDriftService(CloudFormationService stacks, CloudFormationResourceRegistry providers,
                              ObjectMapper json, Clock clock, AccountAwareStorageBackend<StackDriftRecord> records) {
        this.stacks = stacks;
        this.providers = providers;
        this.json = json;
        this.clock = clock;
        this.records = records;
    }

    public static boolean handles(String action) {
        return action != null && ACTIONS.contains(action);
    }

    public CloudFormationDriftSnapshot authorizationTarget(String name, String region, String account) {
        if (request != null && request.isResolvable()) {
            return request.get().stack(name, region, account, () -> stacks.captureDriftStack(name, region, account));
        }
        return stacks.captureDriftStack(name, region, account);
    }

    public List<Permission> readPermissions(String name, List<String> selected, String region, String account) {
        CloudFormationDriftSnapshot stack = authorizationTarget(name, region, account);
        return projections(stack, selected).stream().map(CfnDriftProjection::permission).toList();
    }

    public ResourceDrift detectResource(String name, String logicalId, String region) {
        if (logicalId == null || logicalId.isEmpty() || logicalId.length() > 255) {
            throw invalid("LogicalResourceId must contain between 1 and 255 characters");
        }
        CloudFormationDriftSnapshot stack = authorizationTarget(name, region, records.accountId());
        ResourceDrift result = compare(stack, projections(stack, List.of(logicalId)).getFirst(), clock.instant());
        publish(stack, List.of(result), null);
        return result;
    }

    public String detectStack(String name, List<String> selected, String region) {
        CloudFormationDriftSnapshot stack = authorizationTarget(name, region, records.accountId());
        Instant timestamp = clock.instant();
        List<ResourceDrift> results = projections(stack, selected).stream()
                .map(projection -> compare(stack, projection, timestamp)).toList();
        int changed = (int) results.stream().filter(result -> !"IN_SYNC".equals(result.status())).count();
        Detection detection = new Detection(stack.stackId(), UUID.randomUUID().toString(),
                changed == 0 ? "IN_SYNC" : "DRIFTED", timestamp, changed);
        publish(stack, results, detection);
        return detection.id();
    }

    public Detection detection(String id, String region) {
        if (id == null || id.isBlank() || id.length() > 36) {
            throw invalid("Invalid StackDriftDetectionId: " + id);
        }
        String account = records.accountId();
        for (String key : records.keysForAccount(account)) {
            if (!key.startsWith(region + ":")) {
                continue;
            }
            StackDriftRecord record = records.getForAccount(account, key).orElse(null);
            if (record == null) {
                continue;
            }
            validateRecord(record, account, region, key);
            Detection found = record.detections().get(id);
            if (found != null) {
                if (!id.equals(found.id()) || !record.stackId().equals(found.stackId())) {
                    throw invalid("Stored drift detection identity is inconsistent");
                }
                return found;
            }
        }
        throw invalid("Stack drift detection " + id + " does not exist");
    }

    public PaginatedResult<ResourceDrift> resources(String name, List<String> filters, Integer limit,
                                                    String token, String region) {
        if (limit != null && (limit < 1 || limit > 100)) {
            throw invalid("MaxResults must be between 1 and 100");
        }
        if (filters == null || !FILTERS.containsAll(filters) || token != null && token.length() > 1024) {
            throw invalid("Invalid drift status filter or NextToken");
        }
        CloudFormationDriftSnapshot stack = authorizationTarget(name, region, records.accountId());
        stack.requirePresent();
        StackDriftRecord record = readRecord(stack);
        List<String> canonicalFilters = filters.stream().distinct().sorted().toList();
        List<ResourceDrift> matches = record.resources().values().stream()
                .filter(result -> canonicalFilters.isEmpty() || canonicalFilters.contains(result.status()))
                .sorted(Comparator.comparing(ResourceDrift::logicalId)).toList();
        String scope = jsonText(List.of(stack.accountId(), region, stack.stackId(),
                record.revision(), canonicalFilters));
        return Pagination.slice(matches, ResourceDrift::logicalId, limit == null ? 100 : limit,
                token, markers, scope, ignored -> invalid("Invalid or stale NextToken"));
    }

    private List<CfnDriftProjection> projections(CloudFormationDriftSnapshot stack, List<String> selected) {
        stack.requirePresent();
        if (!DETECTABLE.contains(stack.status()) || stack.operationInProgress()) {
            throw invalid("Drift detection is not supported while stack is in " + stack.status());
        }
        if (stack.parentId() != null || stack.roleArn() != null) {
            throw invalid("Drift detection for nested or execution-role stacks is not supported by Floci");
        }
        if (selected == null || selected.size() > 200
                || selected.stream().anyMatch(id -> id == null || id.isEmpty() || id.length() > 255)) {
            throw invalid("LogicalResourceIds must contain at most 200 valid logical identifiers");
        }
        List<String> ids = selected.isEmpty() ? stack.resources().keySet().stream().sorted().toList()
                : selected.stream().distinct().sorted().toList();
        if (request != null && request.isResolvable()) {
            return request.get().projections(ids, () -> captureProjections(stack, ids));
        }
        return captureProjections(stack, ids);
    }

    private List<CfnDriftProjection> captureProjections(CloudFormationDriftSnapshot stack, List<String> ids) {
        JsonNode template;
        try {
            template = new CloudFormationTemplateParser(json).parse(stack.templateBody());
        } catch (CloudFormationTemplateParser.TemplateFormatException invalidTemplate) {
            throw invalid("Stored stack template cannot be read for drift");
        }
        if (!template.isObject() || !stack.parameters().isEmpty()
                || template.has("Parameters") || template.has("Transform") || template.has("Conditions")) {
            throw invalid("Drift detection currently supports literal templates without parameters or transforms");
        }
        List<CfnDriftProjection> results = new ArrayList<>();
        for (String id : ids) {
            CloudFormationDriftSnapshot.Resource resource = stack.resources().get(id);
            JsonNode definition = template.path("Resources").path(id);
            if (resource == null || !definition.isObject()) {
                throw invalid("Logical resource " + id + " does not exist in stack " + stack.stackId());
            }
            if (!resource.type().equals(definition.path("Type").asText()) || definition.has("Condition")) {
                throw invalid("Stored resource identity or condition is not supported for drift");
            }
            JsonNode properties = definition.path("Properties");
            results.add(providers.forType(resource.type()).orElseThrow(() ->
                    invalid("Drift detection is not implemented for " + resource.type()))
                    .readDrift(resource, properties, stack.accountId(), stack.region()));
        }
        return List.copyOf(results);
    }

    private ResourceDrift compare(CloudFormationDriftSnapshot stack, CfnDriftProjection projection, Instant timestamp) {
        CloudFormationDriftSnapshot.Resource resource = projection.resource();
        if (projection.deleted()) {
            return new ResourceDrift(stack.stackId(), resource.logicalId(), resource.physicalId(), resource.type(),
                    null, null, "DELETED", timestamp, List.of());
        }
        List<Difference> differences = new ArrayList<>();
        for (Map.Entry<String, String> expected : new TreeMap<>(projection.expected()).entrySet()) {
            String actual = projection.actual().get(expected.getKey());
            if (actual == null) {
                throw invalid("Provider did not return declared property " + expected.getKey());
            }
            if (!expected.getValue().equals(actual)) {
                differences.add(new Difference("/" + expected.getKey(), expected.getValue(), actual));
            }
        }
        return new ResourceDrift(stack.stackId(), resource.logicalId(), resource.physicalId(), resource.type(),
                jsonText(new TreeMap<>(projection.expected())), jsonText(new TreeMap<>(projection.actual())),
                differences.isEmpty() ? "IN_SYNC" : "MODIFIED", timestamp, differences);
    }

    private void publish(CloudFormationDriftSnapshot stack, List<ResourceDrift> results, Detection detection) {
        stacks.withCurrentDriftStack(stack, () -> {
            StackDriftRecord before = readRecord(stack);
            Map<String, ResourceDrift> resourceResults = new LinkedHashMap<>(before.resources());
            results.forEach(result -> resourceResults.put(result.logicalId(), result));
            Map<String, Detection> detections = new LinkedHashMap<>(before.detections());
            if (detection != null) {
                detections.put(detection.id(), detection);
            }
            StackDriftRecord after = new StackDriftRecord(stack.accountId(), stack.region(), stack.stackId(),
                    Math.addExact(before.revision(), 1), resourceResults, detections);
            records.putAllAtomicallyForAccount(stack.accountId(), Map.of(key(stack.region(), stack.stackId()), after));
        });
    }

    private StackDriftRecord readRecord(CloudFormationDriftSnapshot stack) {
        String key = key(stack.region(), stack.stackId());
        StackDriftRecord found = records.getForAccount(stack.accountId(), key).orElse(null);
        if (found == null) {
            return new StackDriftRecord(stack.accountId(), stack.region(), stack.stackId(), 0, Map.of(), Map.of());
        }
        validateRecord(found, stack.accountId(), stack.region(), key);
        return found;
    }

    private static void validateRecord(StackDriftRecord record, String account, String region, String key) {
        boolean valid;
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(record.stackId());
            valid = account.equals(record.accountId()) && region.equals(record.region())
                    && account.equals(arn.accountId()) && region.equals(arn.region())
                    && AwsRegions.partitionFor(region).equals(arn.partition())
                    && "cloudformation".equals(arn.service()) && arn.resource().startsWith("stack/")
                    && key.equals(key(region, record.stackId())) && record.revision() >= 0
                    && record.resources().entrySet().stream().allMatch(entry ->
                            entry.getKey().equals(entry.getValue().logicalId())
                                    && Objects.equals(record.stackId(), entry.getValue().stackId()));
        } catch (IllegalArgumentException | NullPointerException invalidRecord) {
            valid = false;
        }
        if (!valid) {
            throw invalid("Stored drift record does not match its account, Region or stack lifetime");
        }
    }

    private static String key(String region, String stackId) {
        return region + ":" + stackId;
    }

    private String jsonText(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize drift result", failure);
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }
}
