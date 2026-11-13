package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Inventory reported by registered managed nodes, including the agent's hash-only refreshes. */
@ApplicationScoped
public class SsmInventoryService implements Resettable {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<String> AWS_TYPES = Set.of(
            "AWS:Application", "AWS:AWSComponent", "AWS:BillingInfo", "AWS:File",
            "AWS:InstanceInformation", "AWS:InstanceDetailedInformation", "AWS:Network",
            "AWS:WindowsRegistry", "AWS:WindowsRole", "AWS:Service", "AWS:WindowsUpdate");
    private static final int MAX_CUSTOM_TYPES = 20;

    private final AccountAwareStorageBackend<ObjectNode> inventories;
    private final SsmCommandService managedNodes;
    private final RegionResolver regionResolver;

    @Inject
    public SsmInventoryService(StorageFactory storageFactory, SsmCommandService managedNodes,
                               RegionResolver regionResolver) {
        this(storageFactory.create("ssm", "ssm-inventory.json",
                new TypeReference<Map<String, ObjectNode>>() {}), managedNodes, regionResolver);
    }

    SsmInventoryService(AccountAwareStorageBackend<ObjectNode> inventories, SsmCommandService managedNodes,
                        RegionResolver regionResolver) {
        this.inventories = inventories;
        this.managedNodes = managedNodes;
        this.regionResolver = regionResolver;
    }

    public synchronized void putInventory(JsonNode request, String region) {
        SsmInventoryRequests.check("PutInventory", request);
        String instanceId = request.get("InstanceId").asText();
        managedNodes.requireManagedInstance(instanceId, region);
        String key = key(region, instanceId);
        ObjectNode current = inventories.get(key).map(ObjectNode::deepCopy).orElseGet(JSON::objectNode);
        ObjectNode items = current.withObject("/Items");
        Set<String> names = new HashSet<>();
        Map<String, Map<String, String>> customAttributes = customAttributes(region);
        for (JsonNode input : request.get("Items")) {
            String type = input.get("TypeName").asText();
            validateType(type);
            if (!names.add(type)) {
                throw invalidContent("An inventory type can occur only once in a request: " + type);
            }
            String version = input.get("SchemaVersion").asText();
            validateVersion(type, version);
            try {
                Instant.parse(input.get("CaptureTime").asText());
            } catch (DateTimeParseException e) {
                throw invalidContent("CaptureTime must name a valid UTC date and time.");
            }
            if (input.hasNonNull("Context") && !input.get("Context").isEmpty()) {
                throw new AwsException("UnsupportedInventoryItemContextException",
                        "Context is not supported for inventory type " + type + ".", 400);
            }
            ObjectNode previous = items.has(type) ? (ObjectNode) items.get(type) : null;
            ObjectNode replacement = input.deepCopy();
            boolean unchanged = previous != null && input.hasNonNull("ContentHash")
                    && input.get("ContentHash").equals(previous.get("ContentHash"))
                    && version.equals(previous.path("SchemaVersion").asText());
            if (unchanged) {
                replacement.set("Content", previous.get("Content").deepCopy());
            } else if (!input.hasNonNull("Content")) {
                throw new AwsException("ItemContentMismatchException",
                        "Inventory content is required when its hash or schema has changed: " + type, 400);
            }
            if (type.startsWith("Custom:")) {
                validateCustomAttributes(type, replacement.get("Content"), customAttributes);
            }
            items.set(type, replacement);
        }
        if (customAttributes.size() > MAX_CUSTOM_TYPES) {
            throw new AwsException("CustomSchemaCountLimitExceededException",
                    "An account can have at most " + MAX_CUSTOM_TYPES + " custom inventory types.", 400);
        }
        current.put("InstanceId", instanceId);
        current.put("Region", region);
        current.put("Revision", UUID.randomUUID().toString());
        inventories.putAllAtomically(Map.of(key, current));
    }

    public synchronized ObjectNode listInventoryEntries(JsonNode request, String region) {
        SsmInventoryRequests.check("ListInventoryEntries", request);
        String instanceId = request.get("InstanceId").asText();
        String type = request.get("TypeName").asText();
        validateType(type);
        managedNodes.requireManagedInstance(instanceId, region);
        ObjectNode inventory = inventories.get(key(region, instanceId)).orElseGet(JSON::objectNode);
        JsonNode item = inventory.path("Items").path(type);
        JsonNode filters = request.path("Filters");
        List<EntryFilter> predicates = filters(type, filters);
        List<IndexedEntry> entries = new ArrayList<>();
        int index = 0;
        for (JsonNode entry : item.path("Content")) {
            if (predicates.stream().allMatch(filter -> filter.matches(entry))) {
                entries.add(new IndexedEntry(index, entry));
            }
            index++;
        }
        String scope = "ssm-inventory:" + regionResolver.getAccountId() + ":" + region + ":" + instanceId
                + ":" + type + ":" + inventory.path("Revision").asText() + ":" + Digests.sha256Hex(filters.toString());
        PaginatedResult<IndexedEntry> page = Pagination.paginate(entries,
                entry -> String.format(Locale.ROOT, "%05d", entry.index()),
                request.path("MaxResults").asInt(50), optionalText(request, "NextToken"), scope,
                token -> new AwsException("InvalidNextToken", "The inventory continuation token is invalid.", 400));
        ObjectNode response = JSON.objectNode().put("InstanceId", instanceId).put("TypeName", type);
        if (!item.isMissingNode()) {
            response.set("CaptureTime", item.get("CaptureTime"));
            response.set("SchemaVersion", item.get("SchemaVersion"));
        }
        ArrayNode results = response.putArray("Entries");
        page.items().forEach(entry -> results.add(entry.value().deepCopy()));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    synchronized List<ObjectNode> inventories(String accountId, String region) {
        return inventories.scanForAccount(accountId, key -> key.startsWith(region + "::")).stream()
                .map(ObjectNode::deepCopy).toList();
    }

    private Map<String, Map<String, String>> customAttributes(String region) {
        Map<String, Map<String, String>> attributes = new LinkedHashMap<>();
        for (ObjectNode inventory : inventories.scan(key -> key.startsWith(region + "::"))) {
            for (Map.Entry<String, JsonNode> item : inventory.path("Items").properties()) {
                if (item.getKey().startsWith("Custom:")) {
                    validateCustomAttributes(item.getKey(), item.getValue().path("Content"), attributes);
                }
            }
        }
        return attributes;
    }

    private static void validateCustomAttributes(String type, JsonNode content,
                                                  Map<String, Map<String, String>> types) {
        Map<String, String> attributes = types.computeIfAbsent(type, ignored -> new HashMap<>());
        for (JsonNode entry : content) {
            for (Map.Entry<String, JsonNode> field : entry.properties()) {
                String name = field.getKey();
                String previous = attributes.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
                if (previous != null && !previous.equals(name)) {
                    throw invalidContent("Inventory attribute casing must remain consistent for " + type + ": " + name);
                }
            }
        }
    }

    private static void validateType(String type) {
        if (!AWS_TYPES.contains(type) && (!type.startsWith("Custom:") || type.length() == "Custom:".length())) {
            throw new AwsException("InvalidTypeNameException", "Unknown inventory type " + type + ".", 400);
        }
    }

    private static void validateVersion(String type, String version) {
        if (type.startsWith("AWS:") && !"1.0".equals(version)
                && !("AWS:Application".equals(type) && "1.1".equals(version))) {
            throw new AwsException("UnsupportedInventorySchemaVersionException",
                    "Unsupported inventory schema version " + version + " for " + type + ".", 400);
        }
    }

    private static List<EntryFilter> filters(String type, JsonNode input) {
        List<EntryFilter> filters = new ArrayList<>();
        for (JsonNode filter : input) {
            String key = filter.get("Key").asText();
            if (key.startsWith(type + ".")) {
                key = key.substring(type.length() + 1);
            }
            String operator = filter.path("Type").asText("Equal");
            if (key.isEmpty() || key.contains(":") || "Exists".equals(operator)) {
                throw new AwsException("InvalidFilter", "The filter is not valid for inventory entries.", 400);
            }
            List<String> values = new ArrayList<>();
            filter.get("Values").forEach(value -> values.add(value.asText()));
            filters.add(new EntryFilter(key, operator, List.copyOf(values)));
        }
        return filters;
    }

    private record IndexedEntry(int index, JsonNode value) {
    }

    private record EntryFilter(String key, String operator, List<String> values) {
        boolean matches(JsonNode entry) {
            if (!entry.has(key)) {
                return false;
            }
            String actual = entry.get(key).asText();
            return "NotEqual".equals(operator)
                    ? values.stream().noneMatch(actual::equals)
                    : values.stream().anyMatch(value -> switch (operator) {
                        case "Equal" -> actual.equals(value);
                        case "BeginWith" -> actual.startsWith(value);
                        case "LessThan" -> actual.compareTo(value) < 0;
                        case "GreaterThan" -> actual.compareTo(value) > 0;
                        default -> false;
                    });
        }
    }

    private static String key(String region, String instanceId) {
        return region + "::" + instanceId;
    }

    private static String optionalText(JsonNode node, String name) {
        return node.hasNonNull(name) ? node.get(name).asText() : null;
    }

    private static AwsException invalidContent(String message) {
        return new AwsException("InvalidItemContentException", message, 400);
    }

    @Override
    public synchronized void clear() {
        inventories.clear();
    }
}
