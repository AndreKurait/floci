package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.EpochSeconds;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ssm.model.SsmAssociation;
import io.github.hectorvent.floci.services.ssm.model.SsmDocument;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discovery and execution reports used by the stock agent; the agent executes the document. */
@ApplicationScoped
public class SsmAgentAssociationService {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final SsmService associations;
    private final SsmCommandService managedNodes;
    private final Ec2Service ec2;
    private final RegionResolver regionResolver;

    @Inject
    public SsmAgentAssociationService(SsmService associations, SsmCommandService managedNodes,
                                      Ec2Service ec2, RegionResolver regionResolver) {
        this.associations = associations;
        this.managedNodes = managedNodes;
        this.ec2 = ec2;
        this.regionResolver = regionResolver;
    }

    public ObjectNode list(JsonNode request, String region) {
        String instanceId = requiredText(request, "InstanceId");
        managedNodes.requireManagedInstance(instanceId, region);
        Map<String, String> tags = instanceTags(instanceId, region);
        List<SsmAssociation> selected = associations.listAssociations(region).stream()
                .filter(association -> targets(association, instanceId, tags)).toList();
        Integer requestedLimit = Pagination.parseMaxResults(request, "MaxResults", "ValidationException");
        int limit = requestedLimit == null ? 50 : requestedLimit;
        if (limit < 1) {
            throw invalid("MaxResults must be positive.");
        }
        String namespace = "ssm-associations:" + regionResolver.getAccountId() + ":" + region + ":" + instanceId;
        PaginatedResult<SsmAssociation> page = Pagination.paginate(selected, SsmAssociation::getAssociationId,
                limit, optionalText(request, "NextToken"), namespace,
                token -> new AwsException("InvalidNextToken", "Invalid association pagination token.", 400));
        ObjectNode result = JSON.objectNode();
        ArrayNode items = result.putArray("Associations");
        for (SsmAssociation association : page.items()) {
            items.add(summary(association, instanceId, region));
        }
        if (page.nextToken() != null) {
            result.put("NextToken", page.nextToken());
        }
        return result;
    }

    public void updateStatus(JsonNode request, String region) {
        String instanceId = requiredText(request, "InstanceId");
        managedNodes.requireManagedInstance(instanceId, region);
        SsmAssociation association = associations.describeAssociation(
                requiredText(request, "AssociationId"), null, null, region);
        if (!targets(association, instanceId, instanceTags(instanceId, region))) {
            throw new AwsException("AssociationDoesNotExist",
                    "The association does not target this managed node.", 400);
        }
        String version = optionalText(request, "AssociationVersion");
        String currentVersion = SsmService.associationVersion(association);
        if (version != null && !version.equals(currentVersion)) {
            throw new AwsException("InvalidAssociationVersion",
                    "The association version must match the latest version.", 400);
        }
        JsonNode execution = request.path("ExecutionResult");
        if (!execution.isObject()) {
            if (!execution.isMissingNode() && !execution.isNull()) {
                throw SsmJsonDeserialization.serializationException(execution, "a structure");
            }
            throw invalid("ExecutionResult must be an object.");
        }
        String status = requiredText(execution, "Status");
        String summary = optionalText(execution, "ExecutionSummary");
        if (summary == null) {
            throw invalid("ExecutionSummary is required.");
        }
        JsonNode output = execution.get("OutputUrl");
        if (output != null && !output.isNull() && !output.isObject()) {
            throw SsmJsonDeserialization.serializationException(output, "a structure");
        }
        SsmAssociation.InstanceExecution reported = new SsmAssociation.InstanceExecution(
                currentVersion, status, executionDate(execution.get("ExecutionDate")),
                summary, optionalText(execution, "ErrorCode"), output == null ? null : output.deepCopy());
        Set<String> targeted = new HashSet<>();
        managedNodes.describeInstanceInformation(region).forEach(node -> {
            if (targets(association, node.getInstanceId(), instanceTags(node.getInstanceId(), region))) {
                targeted.add(node.getInstanceId());
            }
        });
        associations.recordAssociationExecution(association.getAssociationId(), currentVersion,
                instanceId, reported, targeted, region);
    }

    private ObjectNode summary(SsmAssociation association, String instanceId, String region) {
        SsmDocument document = associations.getDocument(association.getName(), region);
        String version = SsmService.resolveDocumentVersion(document, association.getDocumentVersion());
        ObjectNode item = JSON.objectNode();
        item.put("AssociationId", association.getAssociationId());
        String associationVersion = SsmService.associationVersion(association);
        item.put("AssociationVersion", associationVersion);
        item.put("InstanceId", instanceId);
        item.put("Name", association.getName());
        item.put("DocumentVersion", version);
        item.put("Checksum", Digests.sha256Hex(document.getContentForVersion(version)));
        item.put("ScheduleExpression", association.getScheduleExpression() == null ? "" : association.getScheduleExpression());
        ObjectNode parameters = item.putObject("Parameters");
        if (association.getParameters() != null) {
            association.getParameters().forEach((name, values) -> {
                ArrayNode array = parameters.putArray(name);
                values.forEach(array::add);
            });
        }
        ArrayNode targets = item.putArray("Targets");
        if (association.getTargets() != null) {
            for (SsmAssociation.Target target : association.getTargets()) {
                ArrayNode values = targets.addObject().put("Key", target.getKey()).putArray("Values");
                target.getValues().forEach(values::add);
            }
        }
        SsmAssociation.InstanceExecution execution = association.getInstanceExecutions().get(instanceId);
        boolean current = execution != null && associationVersion.equals(execution.associationVersion());
        String status = current ? execution.status() : "Pending";
        item.put("Status", status);
        item.put("DetailedStatus", status);
        if (current) {
            item.put("LastExecutionDate", EpochSeconds.fromInstant(execution.executionDate()));
        }
        return item;
    }

    private Map<String, String> instanceTags(String instanceId, String region) {
        Map<String, String> tags = new HashMap<>();
        Instance instance = ec2.getInstance(region, instanceId).orElse(null);
        if (instance != null && instance.getTags() != null) {
            for (Tag tag : instance.getTags()) {
                tags.put(tag.getKey(), tag.getValue());
            }
        }
        return tags;
    }

    private static boolean targets(SsmAssociation association, String instanceId, Map<String, String> tags) {
        if (association.getInstanceId() != null && !association.getInstanceId().isBlank()) {
            return association.getInstanceId().equals(instanceId);
        }
        if (association.getTargets() == null || association.getTargets().isEmpty()) {
            return false;
        }
        for (SsmAssociation.Target target : association.getTargets()) {
            List<String> values = target.getValues() == null ? List.of() : target.getValues();
            if ("InstanceIds".equals(target.getKey())) {
                if (!values.contains(instanceId) && !values.contains("*")) {
                    return false;
                }
            } else if (target.getKey() != null && target.getKey().startsWith("tag:")) {
                String value = tags.get(target.getKey().substring(4));
                if (value == null || !values.contains(value)) {
                    return false;
                }
            } else {
                throw new AwsException("UnsupportedOperation",
                        "Agent association discovery supports InstanceIds and EC2 tag targets.", 400);
            }
        }
        return true;
    }

    private static Instant executionDate(JsonNode value) {
        if (value == null || value.isNull()) {
            throw invalid("ExecutionDate must be an epoch-seconds number.");
        }
        if (!value.isNumber()) {
            throw SsmJsonDeserialization.serializationException(value, "a timestamp");
        }
        try {
            BigDecimal seconds = value.decimalValue();
            long whole = seconds.toBigInteger().longValueExact();
            long nanos = seconds.remainder(BigDecimal.ONE).movePointRight(9).longValueExact();
            return Instant.ofEpochSecond(whole, nanos);
        } catch (ArithmeticException | DateTimeException error) {
            throw invalid("ExecutionDate is outside the supported timestamp range.");
        }
    }

    private static String requiredText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw SsmJsonDeserialization.serializationException(value, "a String");
        }
        return value.textValue();
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
