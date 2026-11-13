package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.EpochSeconds;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.StarWildcard;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssm.model.PatchBaselineIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import static io.github.hectorvent.floci.services.ssm.SsmPatchRequestShapes.invalid;

/** Customer baseline configuration and bindings; this does not execute patches on managed nodes. */
@ApplicationScoped
public class SsmPatchBaselineService implements Resettable {

    private static final List<String> CONFIGURATION = List.of(
            "Name", "OperatingSystem", "GlobalFilters", "ApprovalRules", "ApprovedPatches",
            "ApprovedPatchesComplianceLevel", "ApprovedPatchesEnableNonSecurity", "RejectedPatches",
            "RejectedPatchesAction", "Description", "Sources", "AvailableSecurityUpdatesComplianceStatus");

    private final StorageBackend<String, ObjectNode> store;
    private final SsmService ssm;
    private final RegionResolver regions;
    private final Clock clock;

    @Inject
    public SsmPatchBaselineService(StorageFactory factory, SsmService ssm, RegionResolver regions) {
        this(factory.create("ssm", "ssm-patch-baselines.json",
                new TypeReference<Map<String, ObjectNode>>() {}), ssm, regions, Clock.systemUTC());
    }

    SsmPatchBaselineService(StorageBackend<String, ObjectNode> store, SsmService ssm,
                            RegionResolver regions, Clock clock) {
        this.store = store;
        this.ssm = ssm;
        this.regions = regions;
        this.clock = clock;
    }

    public synchronized ObjectNode create(JsonNode request, String region) {
        SsmPatchRequestShapes.check("CreatePatchBaseline", request);
        ObjectNode configuration = configuration(request);
        validateRules(configuration);
        ObjectNode tags = tags(request.path("Tags"));
        if (tags.size() > 50) {
            throw new AwsException("TooManyTagsError", "A baseline supports at most 50 tags.", 400);
        }
        ObjectNode fingerprint = object().set("configuration", configuration.deepCopy());
        fingerprint.set("tags", tags);
        String token = request.path("ClientToken").asText(null);
        if (token != null) {
            ObjectNode prior = store.get(key(region, "token", token)).orElse(null);
            if (prior != null) {
                if (!prior.get("request").equals(fingerprint)) {
                    throw new AwsException("IdempotentParameterMismatch",
                            "ClientToken was already used with different parameters.", 400);
                }
                return object().put("BaselineId", prior.path("BaselineId").asText());
            }
        }
        String id = "pb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        double now = EpochSeconds.fromInstant(clock.instant());
        configuration.put("BaselineId", id).put("CreatedDate", now).put("ModifiedDate", now);
        configuration.putArray("PatchGroups");
        ObjectNode stored = object().set("configuration", configuration);
        stored.set("tags", tags);
        Map<String, ObjectNode> writes = new LinkedHashMap<>();
        writes.put(key(region, "baseline", id), stored);
        if (token != null) {
            stored.put("clientToken", token);
            ObjectNode selection = object().put("BaselineId", id);
            selection.set("request", fingerprint);
            writes.put(key(region, "token", token), selection);
        }
        store.putAllAtomically(writes);
        return object().put("BaselineId", id);
    }

    public synchronized ObjectNode get(JsonNode request, String region) {
        SsmPatchRequestShapes.check("GetPatchBaseline", request);
        return body(customer(region, request.path("BaselineId").asText()));
    }

    public synchronized ObjectNode update(JsonNode request, String region) {
        SsmPatchRequestShapes.check("UpdatePatchBaseline", request);
        String id = id(request.path("BaselineId").asText(), region);
        ObjectNode stored = customer(region, id);
        ObjectNode before = body(stored);
        boolean replace = request.path("Replace").asBoolean(false);
        if (replace && !request.hasNonNull("Name")) {
            throw invalid("Name is required when Replace is true.");
        }
        ObjectNode updated = replace ? configuration(request) : before.deepCopy();
        if (!replace) {
            for (String member : CONFIGURATION) {
                if (!member.equals("OperatingSystem") && request.hasNonNull(member)) {
                    updated.set(member, request.get(member).deepCopy());
                }
            }
        }
        updated.set("OperatingSystem", before.get("OperatingSystem"));
        updated.set("BaselineId", before.get("BaselineId"));
        updated.set("CreatedDate", before.get("CreatedDate"));
        updated.set("PatchGroups", before.get("PatchGroups"));
        updated.put("ModifiedDate", EpochSeconds.fromInstant(clock.instant()));
        validateRules(updated);
        stored.set("configuration", updated);
        store.putAllAtomically(Map.of(key(region, "baseline", id), stored));
        ObjectNode response = updated.deepCopy();
        response.remove("PatchGroups");
        return response;
    }

    public synchronized ObjectNode delete(JsonNode request, String region) {
        SsmPatchRequestShapes.check("DeletePatchBaseline", request);
        String id = id(request.path("BaselineId").asText(), region);
        ObjectNode stored = store.get(key(region, "baseline", id)).orElse(null);
        if (stored != null) {
            ObjectNode configuration = body(stored);
            if (!configuration.path("PatchGroups").isEmpty()) {
                throw new AwsException("ResourceInUseException",
                        "Deregister all patch groups before deleting the baseline.", 400);
            }
            Set<String> removals = new HashSet<>();
            removals.add(key(region, "baseline", id));
            if (stored.has("clientToken")) {
                removals.add(key(region, "token", stored.get("clientToken").asText()));
            }
            String os = configuration.path("OperatingSystem").asText();
            if (defaultId(os, region).equals(id)) {
                removals.add(key(region, "default", os));
            }
            store.deleteAllAtomically(removals);
        }
        return object().put("BaselineId", id);
    }

    public synchronized ObjectNode getDefault(JsonNode request, String region) {
        SsmPatchRequestShapes.check("GetDefaultPatchBaseline", request);
        String os = request.path("OperatingSystem").asText("WINDOWS");
        return object().put("BaselineId", defaultId(os, region)).put("OperatingSystem", os);
    }

    public synchronized ObjectNode registerDefault(JsonNode request, String region) {
        SsmPatchRequestShapes.check("RegisterDefaultPatchBaseline", request);
        String id = id(request.path("BaselineId").asText(), region);
        String os = body(customer(region, id)).path("OperatingSystem").asText();
        store.putAllAtomically(Map.of(key(region, "default", os), object().put("BaselineId", id)));
        return object().put("BaselineId", id);
    }

    public synchronized ObjectNode registerGroup(JsonNode request, String region) {
        SsmPatchRequestShapes.check("RegisterPatchBaselineForPatchGroup", request);
        String id = id(request.path("BaselineId").asText(), region);
        String group = request.path("PatchGroup").asText();
        ObjectNode stored = customer(region, id);
        ObjectNode configuration = body(stored);
        String os = configuration.path("OperatingSystem").asText();
        for (ObjectNode other : baselines(region)) {
            ObjectNode candidate = body(other);
            if (!candidate.path("BaselineId").asText().equals(id)
                    && candidate.path("OperatingSystem").asText().equals(os)
                    && contains(candidate.path("PatchGroups"), group)) {
                throw new AwsException("AlreadyExistsException",
                        "Patch group already has a baseline for " + os + ".", 400);
            }
        }
        if (!contains(configuration.path("PatchGroups"), group)) {
            configuration.withArray("PatchGroups").add(group);
            stored.set("configuration", configuration);
            store.putAllAtomically(Map.of(key(region, "baseline", id), stored));
        }
        return object().put("BaselineId", id).put("PatchGroup", group);
    }

    public synchronized ObjectNode deregisterGroup(JsonNode request, String region) {
        SsmPatchRequestShapes.check("DeregisterPatchBaselineForPatchGroup", request);
        String id = id(request.path("BaselineId").asText(), region);
        String group = request.path("PatchGroup").asText();
        ObjectNode existing = store.get(key(region, "baseline", id)).orElse(null);
        if (existing != null) {
            ObjectNode stored = existing.deepCopy();
            ObjectNode configuration = body(stored);
            ArrayNode remaining = JsonNodeFactory.instance.arrayNode();
            configuration.path("PatchGroups").forEach(value -> {
                if (!value.asText().equals(group)) {
                    remaining.add(value);
                }
            });
            configuration.set("PatchGroups", remaining);
            stored.set("configuration", configuration);
            store.putAllAtomically(Map.of(key(region, "baseline", id), stored));
        }
        return object().put("BaselineId", id).put("PatchGroup", group);
    }

    public synchronized ObjectNode getForGroup(JsonNode request, String region) {
        SsmPatchRequestShapes.check("GetPatchBaselineForPatchGroup", request);
        String group = request.path("PatchGroup").asText();
        String os = request.path("OperatingSystem").asText("WINDOWS");
        String selected = baselines(region).stream().map(SsmPatchBaselineService::body)
                .filter(value -> value.path("OperatingSystem").asText().equals(os))
                .filter(value -> contains(value.path("PatchGroups"), group))
                .map(value -> value.path("BaselineId").asText())
                .findFirst().orElseGet(() -> defaultId(os, region));
        return object().put("BaselineId", selected).put("PatchGroup", group).put("OperatingSystem", os);
    }

    public synchronized ObjectNode describe(JsonNode request, String region) {
        SsmPatchRequestShapes.check("DescribePatchBaselines", request);
        Map<String, List<String>> filters = filters(request, Set.of("OWNER", "OPERATING_SYSTEM", "NAME_PREFIX"));
        List<String> owners = filters.getOrDefault("OWNER", List.of());
        if (owners.stream().anyMatch(owner -> !Set.of("AWS", "Self", "All").contains(owner))) {
            throw invalid("OWNER must be AWS, Self or All.");
        }
        List<ObjectNode> items = new ArrayList<>();
        if (owners.isEmpty() || owners.contains("AWS") || owners.contains("All")) {
            for (PatchBaselineIdentity predefined : ssm.describePatchBaselines(filters)) {
                ObjectNode value = object().put("BaselineId", predefined.baselineId())
                        .put("BaselineName", predefined.baselineName())
                        .put("OperatingSystem", predefined.operatingSystem())
                        .put("BaselineDescription", predefined.baselineDescription())
                        .put("DefaultBaseline", defaultId(predefined.operatingSystem(), region)
                                .equals(predefined.baselineId()));
                items.add(value);
            }
        }
        if (owners.isEmpty() || owners.contains("Self") || owners.contains("All")) {
            for (ObjectNode stored : baselines(region)) {
                ObjectNode configuration = body(stored);
                String os = configuration.path("OperatingSystem").asText();
                String name = configuration.path("Name").asText();
                if (matches(filters, "OPERATING_SYSTEM", os, false)
                        && matches(filters, "NAME_PREFIX", name, true)) {
                    items.add(identity(configuration, region));
                }
            }
        }
        return page("DescribePatchBaselines", "BaselineIdentities", items,
                value -> value.path("BaselineId").asText(), request, region, filters);
    }

    public synchronized ObjectNode describeGroups(JsonNode request, String region) {
        SsmPatchRequestShapes.check("DescribePatchGroups", request);
        Map<String, List<String>> filters = filters(request, Set.of("NAME_PREFIX", "OPERATING_SYSTEM"));
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stored : baselines(region)) {
            ObjectNode configuration = body(stored);
            if (!matches(filters, "OPERATING_SYSTEM", configuration.path("OperatingSystem").asText(), false)) {
                continue;
            }
            for (JsonNode group : configuration.path("PatchGroups")) {
                if (matches(filters, "NAME_PREFIX", group.asText(), true)) {
                    ObjectNode mapping = object().put("PatchGroup", group.asText());
                    mapping.set("BaselineIdentity", identity(configuration, region));
                    items.add(mapping);
                }
            }
        }
        return page("DescribePatchGroups", "Mappings", items,
                value -> value.path("PatchGroup").asText() + "|"
                        + value.path("BaselineIdentity").path("OperatingSystem").asText(),
                request, region, filters);
    }

    public synchronized ObjectNode resourceTags(String action, JsonNode request, String region) {
        SsmPatchRequestShapes.check(action, request);
        String id = id(request.path("ResourceId").asText(), region);
        ObjectNode stored = customer(region, id);
        ObjectNode tags = (ObjectNode) stored.get("tags");
        if (action.equals("AddTagsToResource")) {
            tags.setAll(tags(request.path("Tags")));
            if (tags.size() > 50) {
                throw new AwsException("TooManyTagsError", "A baseline supports at most 50 tags.", 400);
            }
            store.putAllAtomically(Map.of(key(region, "baseline", id), stored));
        } else if (action.equals("RemoveTagsFromResource")) {
            request.path("TagKeys").forEach(value -> tags.remove(value.asText()));
            store.putAllAtomically(Map.of(key(region, "baseline", id), stored));
        }
        ObjectNode response = object();
        if (action.equals("ListTagsForResource")) {
            ArrayNode list = response.putArray("TagList");
            tags.properties().forEach(entry -> list.add(object().put("Key", entry.getKey())
                    .put("Value", entry.getValue().asText())));
        }
        return response;
    }

    private ObjectNode identity(ObjectNode configuration, String region) {
        String id = configuration.path("BaselineId").asText();
        String os = configuration.path("OperatingSystem").asText();
        ObjectNode result = object().put("BaselineId", id).put("BaselineName", configuration.path("Name").asText())
                .put("OperatingSystem", os).put("DefaultBaseline", id.equals(defaultId(os, region)));
        if (configuration.has("Description")) {
            result.set("BaselineDescription", configuration.get("Description"));
        }
        return result;
    }

    private ObjectNode page(String operation, String member, List<ObjectNode> values,
                            Function<ObjectNode, String> cursor, JsonNode request, String region,
                            Map<String, List<String>> filters) {
        ObjectNode scope = object();
        filters.forEach((name, valuesForFilter) -> valuesForFilter.forEach(scope.putArray(name)::add));
        String namespace = operation + ":" + regions.getAccountId() + ":" + region + ":" + scope;
        PaginatedResult<ObjectNode> result = Pagination.paginate(values, cursor,
                request.path("MaxResults").asInt(100), request.path("NextToken").asText(null), namespace,
                token -> new AwsException("InvalidNextToken", "Invalid NextToken.", 400));
        ObjectNode response = object();
        ArrayNode list = response.putArray(member);
        result.items().forEach(list::add);
        if (result.nextToken() != null) {
            response.put("NextToken", result.nextToken());
        }
        return response;
    }

    private String defaultId(String os, String region) {
        return store.get(key(region, "default", os)).map(value -> value.path("BaselineId").asText())
                .orElseGet(() -> ssm.getDefaultPatchBaseline(os));
    }

    private List<ObjectNode> baselines(String region) {
        String prefix = key(region, "baseline", "");
        return store.scan(value -> value.startsWith(prefix));
    }

    private ObjectNode customer(String region, String suppliedId) {
        String id = id(suppliedId, region);
        return store.get(key(region, "baseline", id)).map(ObjectNode::deepCopy)
                .orElseThrow(() -> new AwsException("DoesNotExistException",
                        "Patch baseline " + suppliedId + " does not exist.", 400));
    }

    private String id(String value, String region) {
        if (!AwsArnUtils.looksLikeArn(value)) {
            return value;
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(value);
        } catch (IllegalArgumentException error) {
            throw invalid("Invalid patch baseline ARN.");
        }
        if (!parsed.partition().equals(AwsRegions.partitionFor(region)) || !parsed.service().equals("ssm")
                || !parsed.region().equals(region) || !parsed.accountId().equals(regions.getAccountId())
                || !parsed.resource().startsWith("patchbaseline/")) {
            throw new AwsException("DoesNotExistException", "Patch baseline " + value + " does not exist.", 400);
        }
        return parsed.resource().substring("patchbaseline/".length());
    }

    private static ObjectNode configuration(JsonNode request) {
        ObjectNode result = object().put("OperatingSystem", "WINDOWS")
                .put("ApprovedPatchesComplianceLevel", "UNSPECIFIED")
                .put("ApprovedPatchesEnableNonSecurity", false)
                .put("RejectedPatchesAction", "ALLOW_AS_DEPENDENCY");
        for (String member : CONFIGURATION) {
            if (request.hasNonNull(member)) {
                result.set(member, request.get(member).deepCopy());
            }
        }
        return result;
    }

    private static void validateRules(JsonNode configuration) {
        String os = configuration.path("OperatingSystem").asText();
        boolean autoApproval = !os.equals("DEBIAN") && !os.equals("UBUNTU");
        for (JsonNode rule : configuration.path("ApprovalRules").path("PatchRules")) {
            if (!autoApproval) {
                if (rule.hasNonNull("ApproveAfterDays") || rule.hasNonNull("ApproveUntilDate")) {
                    throw invalid("Date-based patch approval is not supported for " + os + ".");
                }
                continue;
            }
            if (rule.hasNonNull("ApproveAfterDays") == rule.hasNonNull("ApproveUntilDate")) {
                throw invalid("Each patch rule requires exactly one of ApproveAfterDays and ApproveUntilDate.");
            }
            if (rule.hasNonNull("ApproveUntilDate")) {
                try {
                    LocalDate.parse(rule.get("ApproveUntilDate").asText());
                } catch (DateTimeParseException error) {
                    throw invalid("ApproveUntilDate must be a calendar date in YYYY-MM-DD format.");
                }
            }
        }
    }

    private static ObjectNode tags(JsonNode values) {
        ObjectNode result = object();
        for (JsonNode tag : values) {
            result.put(tag.path("Key").asText(), tag.path("Value").asText());
        }
        return result;
    }

    private static Map<String, List<String>> filters(JsonNode request, Set<String> allowed) {
        Map<String, List<String>> result = new TreeMap<>();
        for (JsonNode filter : request.path("Filters")) {
            String key = filter.path("Key").asText();
            if (!allowed.contains(key)) {
                throw invalid("Unsupported patch filter: " + key);
            }
            List<String> values = new ArrayList<>();
            filter.path("Values").forEach(value -> values.add(value.asText()));
            if (values.isEmpty() || result.put(key, values) != null) {
                throw invalid("Patch filters require values and distinct keys.");
            }
        }
        return result;
    }

    private static boolean matches(Map<String, List<String>> filters, String key, String value, boolean prefix) {
        List<String> choices = filters.getOrDefault(key, List.of());
        return choices.isEmpty() || choices.stream().anyMatch(choice -> prefix
                ? StarWildcard.matches(choice + "*", value) : value.equals(choice));
    }

    private static boolean contains(JsonNode values, String value) {
        for (JsonNode item : values) {
            if (item.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode body(ObjectNode stored) {
        return stored.get("configuration").deepCopy();
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static String key(String region, String kind, String value) {
        return region + "|" + kind + "|" + value;
    }

    @Override
    public synchronized void clear() {
        store.clear();
    }
}
