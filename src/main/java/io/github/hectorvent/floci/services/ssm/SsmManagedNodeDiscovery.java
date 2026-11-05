package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ssm.model.InstanceInformation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Selection and pagination for the public managed-node discovery API. */
@ApplicationScoped
public class SsmManagedNodeDiscovery {
    private static final Set<String> SUPPORTED = Set.of(
            "AgentVersion", "InstanceIds", "PingStatus", "PlatformTypes", "ResourceType");
    private static final Set<String> OTHER_KEYS = Set.of(
            "ActivationIds", "AssociationStatus", "IamRole", "SourceIds", "SourceTypes", "tag-key");
    private static final Map<String, Set<String>> ENUMS = Map.of(
            "PingStatus", Set.of("Online", "ConnectionLost", "Inactive"),
            "PlatformTypes", Set.of("Windows", "Linux", "MacOS"),
            "ResourceType", Set.of("EC2Instance", "ManagedInstance"));
    private final SsmCommandService managedNodes;
    private final Ec2Service ec2;
    private final RegionResolver regions;

    @Inject
    public SsmManagedNodeDiscovery(SsmCommandService managedNodes, Ec2Service ec2, RegionResolver regions) {
        this.managedNodes = managedNodes;
        this.ec2 = ec2;
        this.regions = regions;
    }

    public PaginatedResult<InstanceInformation> describe(JsonNode request, String region) {
        int limit = SsmJsonDeserialization.optionalInteger(request.path("MaxResults"), 10);
        if (limit < 5 || limit > 50) {
            throw SmithyValidationErrors.validationException("MaxResults must be between 5 and 50.");
        }
        String token = text(request.path("NextToken"), false);
        Map<String, Set<String>> filters = filters(request);
        for (String id : filters.getOrDefault("InstanceIds", Set.of())) {
            managedNodes.requireManagedInstance(id, region);
        }
        String account = regions.getAccountId();
        List<InstanceInformation> selected = managedNodes.describeInstanceInformation(region).stream()
                .filter(node -> ec2.findInstanceForAccount(account, region, node.getInstanceId())
                        .map(instance -> !Set.of("stopped", "shutting-down", "terminated")
                                .contains(instance.getState().getName()))
                        .orElse(true))
                .filter(node -> filters.entrySet().stream().allMatch(filter ->
                        filter.getValue().contains(value(node, filter.getKey())))).toList();
        ObjectNode scope = JsonNodeFactory.instance.objectNode();
        filters.forEach((key, values) -> values.forEach(scope.putArray(key)::add));
        String namespace = "ssm-managed-nodes:" + account + ":" + region + ":" + Digests.sha256Hex(scope.toString());
        return Pagination.paginate(selected, InstanceInformation::getInstanceId, limit, token, namespace,
                ignored -> new AwsException("InvalidNextToken", "The specified token isn't valid.", 400));
    }

    private static Map<String, Set<String>> filters(JsonNode request) {
        boolean legacy = request.hasNonNull("InstanceInformationFilterList");
        if (legacy && request.hasNonNull("Filters")) {
            throw new AwsException("InvalidFilterKey", "Filters and InstanceInformationFilterList cannot be combined.", 400);
        }
        JsonNode input = request.path(legacy ? "InstanceInformationFilterList" : "Filters");
        Map<String, Set<String>> result = new TreeMap<>();
        if (input.isMissingNode() || input.isNull()) {
            return result;
        }
        if (!input.isArray()) {
            throw SsmJsonDeserialization.serializationException(input, "a list");
        }
        for (JsonNode filter : input) {
            if (!filter.isObject()) {
                throw SsmJsonDeserialization.serializationException(filter, "a structure");
            }
            String key = text(filter.path(legacy ? "key" : "Key"), true);
            if (!SUPPORTED.contains(key)) {
                if (OTHER_KEYS.contains(key) || key.startsWith("tag:")) {
                    throw new AwsException("UnsupportedOperation", "Managed-node filter is not implemented: " + key, 400);
                }
                throw new AwsException("InvalidFilterKey", "The specified filter key isn't valid: " + key, 400);
            }
            JsonNode inputValues = filter.path(legacy ? "valueSet" : "Values");
            if (!inputValues.isArray()) {
                throw SsmJsonDeserialization.serializationException(inputValues, "a list");
            }
            if (inputValues.isEmpty() || inputValues.size() > 100) {
                throw invalidValue("Filter values must contain between 1 and 100 entries.");
            }
            Set<String> values = new TreeSet<>();
            for (JsonNode inputValue : inputValues) {
                String value = text(inputValue, true);
                if (ENUMS.containsKey(key) && !ENUMS.get(key).contains(value)) {
                    throw invalidValue("Invalid value for " + key + ": " + value);
                }
                values.add(value);
            }
            if (result.putIfAbsent(key, values) != null) {
                throw new AwsException("InvalidFilterKey", "The same filter key cannot be specified twice.", 400);
            }
        }
        return result;
    }

    private static String value(InstanceInformation node, String key) {
        return switch (key) {
            case "AgentVersion" -> node.getAgentVersion();
            case "InstanceIds" -> node.getInstanceId();
            case "PingStatus" -> node.getPingStatus();
            case "PlatformTypes" -> node.getPlatformType();
            case "ResourceType" -> node.getResourceType();
            default -> throw new IllegalArgumentException("Unsupported managed-node filter " + key);
        };
    }

    private static String text(JsonNode node, boolean required) {
        if (node.isMissingNode() || node.isNull()) {
            if (required) {
                throw invalidValue("Filter keys and values are required.");
            }
            return null;
        }
        if (!node.isTextual()) {
            throw SsmJsonDeserialization.serializationException(node, "a String");
        }
        if (required && node.textValue().isEmpty()) {
            throw invalidValue("Filter keys and values cannot be empty.");
        }
        return node.textValue();
    }

    private static AwsException invalidValue(String message) {
        return new AwsException("InvalidInstanceInformationFilterValue", message, 400);
    }
}
