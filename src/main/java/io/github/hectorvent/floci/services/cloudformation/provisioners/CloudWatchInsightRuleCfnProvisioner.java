package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.insights.CloudWatchInsightRulesService;
import io.github.hectorvent.floci.services.cloudwatch.insights.model.InsightRule;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Stores the ARN as the physical identity, which also supplies this type's documented Ref. */
@ApplicationScoped
public class CloudWatchInsightRuleCfnProvisioner implements CfnResourceProvisioner {
    private static final String TYPE = "AWS::CloudWatch::InsightRule";
    private static final String OWNER = "FlociInsightRuleOwner";
    private static final String TAG_KEYS = "FlociInsightRuleTagKeys";
    private static final String SNAPSHOT = "FlociInsightRuleSnapshot";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final CloudWatchInsightRulesService service;

    public CloudWatchInsightRuleCfnProvisioner(CloudWatchInsightRulesService service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        if (resource.getAttributes().containsKey(SNAPSHOT)) {
            throw new AwsException("ResourceConflictException", "Insight rule update cleanup is pending.", 400);
        }
        String name = required(props, "RuleName", ctx);
        String definition = required(props, "RuleBody", ctx);
        String state = required(props, "RuleState", ctx);
        String transformed = ctx.resolveOptional(props, "ApplyOnTransformedLogs");
        if (transformed != null && !"true".equals(transformed) && !"false".equals(transformed)) {
            throw new AwsException("ValidationError", "ApplyOnTransformedLogs must be a boolean.", 400);
        }
        Map<String, String> priorAttributes = Map.copyOf(resource.getAttributes());
        String owner = priorAttributes.getOrDefault(OWNER, UUID.randomUUID().toString());
        String arn = service.arn(name, ctx.region());
        boolean inPlace = ctx.isUpdate() && arn.equals(ctx.priorPhysicalId());
        Map<String, String> desiredTags = ctx.resolveTags(props, "Tags");
        Set<String> ownedTags = inPlace ? tagKeys(priorAttributes.get(TAG_KEYS)) : Set.of();
        InsightRule rule = service.putOwned(name, definition, state, Boolean.valueOf(transformed),
                desiredTags, ownedTags, owner, inPlace, previous -> {
                    if (inPlace) {
                        ObjectNode snapshot = MAPPER.createObjectNode().put("region", ctx.region())
                                .put("name", name).put("owner", owner);
                        snapshot.set("previous", MAPPER.valueToTree(previous));
                        snapshot.set("attributes", MAPPER.valueToTree(priorAttributes));
                        resource.getAttributes().put(SNAPSHOT, snapshot.toString());
                    }
                }, ctx.region());
        resource.setPhysicalId(rule.arn());
        resource.getAttributes().put("Arn", rule.arn());
        resource.getAttributes().put("RuleName", rule.name());
        resource.getAttributes().put(OWNER, owner);
        resource.getAttributes().put(TAG_KEYS, MAPPER.valueToTree(desiredTags.keySet()).toString());
        ReplacementCleanup.record(resource, ctx, priorAttributes);
    }

    private static String required(JsonNode props, String field, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, field);
        if (value == null || value.isEmpty()) {
            throw new AwsException("ValidationError", field + " is required.", 400);
        }
        return value;
    }

    private static Set<String> tagKeys(String value) {
        if (value == null) {
            return Set.of();
        }
        try {
            JsonNode parsed = MAPPER.readTree(value);
            if (!parsed.isArray()) {
                throw new IllegalStateException("Invalid insight rule tag ownership");
            }
            Set<String> result = new TreeSet<>();
            for (JsonNode key : parsed) {
                if (!key.isTextual()) {
                    throw new IllegalStateException("Invalid insight rule tag ownership");
                }
                result.add(key.textValue());
            }
            return result;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read insight rule tag ownership", error);
        }
    }

    @Override
    public void delete(StackResource resource, String region) {
        service.deleteOwned(resource.getPhysicalId(), resource.getAttributes().get(OWNER), region);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        throw new IllegalStateException("Insight rule deletion requires its CloudFormation owner token");
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(SNAPSHOT);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String owner = resource.getAttributes().get(OWNER);
        if (ReplacementCleanup.rollback(resource,
                (type, id, region) -> service.deleteOwned(id, owner, region))) {
            return true;
        }
        String raw = resource.getAttributes().get(SNAPSHOT);
        if (raw == null) {
            return true;
        }
        try {
            JsonNode snapshot = MAPPER.readTree(raw);
            InsightRule previous = snapshot.path("previous").isNull() ? null
                    : MAPPER.treeToValue(snapshot.path("previous"), InsightRule.class);
            service.restoreOwned(snapshot.path("name").asText(), previous, snapshot.path("owner").asText(),
                    snapshot.path("region").asText());
            resource.getAttributes().clear();
            snapshot.path("attributes").properties().forEach(entry ->
                    resource.getAttributes().put(entry.getKey(), entry.getValue().textValue()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read insight rule rollback snapshot", error);
        }
        return true;
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        if (resource.getAttributes().containsKey(SNAPSHOT) && !ReplacementCleanup.hasReplacement(resource)) {
            return new UpdateCleanupResult(true, true, null, 0, null);
        }
        String owner = resource.getAttributes().get(OWNER);
        return ReplacementCleanup.complete(resource, (type, id, region) -> service.deleteOwned(id, owner, region));
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(SNAPSHOT);
        ReplacementCleanup.clear(resource);
    }
}
