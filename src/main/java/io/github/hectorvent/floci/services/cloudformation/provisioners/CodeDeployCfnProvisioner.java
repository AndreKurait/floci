package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import io.github.hectorvent.floci.services.codedeploy.model.DeploymentGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** CodeDeploy applications and groups. Ref returns names; neither schema declares GetAtt attributes. */
@ApplicationScoped
public class CodeDeployCfnProvisioner implements CfnResourceProvisioner {
    private static final String APPLICATION = "AWS::CodeDeploy::Application";
    private static final String GROUP = "AWS::CodeDeploy::DeploymentGroup";
    private static final String APPLICATION_ATTR = CfnRollback.USES_ATTR_PREFIX + "CodeDeployApplication";
    private static final String PLATFORM_ATTR = CfnRollback.USES_ATTR_PREFIX + "CodeDeployPlatform";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> FIELDS = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> TAGS = new TypeReference<>() {};
    private static final List<String> GROUP_FIELDS = List.of(
            "AlarmConfiguration", "AutoRollbackConfiguration", "AutoScalingGroups",
            "BlueGreenDeploymentConfiguration", "DeploymentStyle", "ECSServices", "Ec2TagFilters",
            "Ec2TagSet", "LoadBalancerInfo", "OnPremisesInstanceTagFilters", "OnPremisesTagSet",
            "OutdatedInstancesStrategy", "TerminationHookEnabled", "TriggerConfigurations");
    private static final Set<String> BOOLEAN_FIELDS = Set.of(
            "Enabled", "IgnorePollAlarmFailure", "TerminationHookEnabled");
    private static final Set<String> INTEGER_FIELDS = Set.of("WaitTimeInMinutes", "TerminationWaitTimeInMinutes");

    private final CodeDeployService service;

    @Inject
    public CodeDeployCfnProvisioner(CodeDeployService service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(APPLICATION, GROUP);
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        JsonNode props = properties == null ? MAPPER.createObjectNode()
                : ctx.engine().resolveNodeOmittingNoValue(properties);
        resource.getAttributes().remove(CfnRollback.CODEDEPLOY_UPDATE_SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(resource);
        }
        Map<String, String> previous = new HashMap<>(resource.getAttributes());
        switch (resource.getResourceType()) {
            case APPLICATION -> application(resource, props, ctx);
            case GROUP -> group(resource, props, ctx);
            default -> throw new IllegalArgumentException("Unsupported CodeDeploy type " + resource.getResourceType());
        }
        ReplacementCleanup.record(resource, ctx, previous);
    }

    private void application(StackResource resource, JsonNode props, ProvisionContext ctx) {
        String platform = ctx.resolveOrDefault(props, "ComputePlatform", "Server");
        if (!Set.of("Server", "Lambda", "ECS", "Kubernetes").contains(platform)) {
            throw invalid("ComputePlatform is not supported: " + platform);
        }
        boolean platformChanged = existing(resource, ctx)
                && !platform.equals(resource.getAttributes().get(PLATFORM_ATTR));
        String name = name(resource, props, "ApplicationName", platformChanged, ctx);
        String arn = service.applicationArn(ctx.region(), name);
        Map<String, String> desiredTags = ctx.resolveTags(props, "Tags");
        if (existing(resource, ctx) && ctx.reusesPriorEntity(name)) {
            service.getApplication(ctx.region(), name);
            snapshot(resource, ctx.region(), arn, null);
            CfnRollback.guarded(resource, ctx, false, () -> {}, () -> restore(resource),
                    () -> replaceTags(arn, desiredTags));
        } else {
            service.createApplication(ctx.region(), name, platform, List.of());
            resource.setPhysicalId(name);
            resource.getAttributes().put(PLATFORM_ATTR, platform);
            CfnRollback.guarded(resource, ctx, true, () -> delete(resource, ctx.region()), () -> {},
                    () -> replaceTags(arn, desiredTags));
        }
    }

    private void group(StackResource resource, JsonNode props, ProvisionContext ctx) {
        for (String property : List.of("Deployment", "Ec2DeploymentOptions")) {
            if (props.hasNonNull(property)) {
                throw invalid(property + " requires deployment execution that this CloudFormation handler does not support");
            }
        }
        rejectCombination(props, "Ec2TagFilters", "Ec2TagSet");
        rejectCombination(props, "OnPremisesInstanceTagFilters", "OnPremisesTagSet");
        String application = required(props, "ApplicationName", ctx);
        String role = required(props, "ServiceRoleArn", ctx);
        String config = ctx.resolveOptional(props, "DeploymentConfigName");
        if (config == null && ctx.previouslyDeclared("DeploymentConfigName")) {
            config = "CodeDeployDefault.OneAtATime";
        }
        boolean applicationChanged = existing(resource, ctx)
                && !application.equals(resource.getAttributes().get(APPLICATION_ATTR));
        String name = name(resource, props, "DeploymentGroupName", applicationChanged, ctx);
        String arn = service.deploymentGroupArn(ctx.region(), application, name);
        Map<String, Object> fields = groupFields(props, ctx);
        Map<String, String> desiredTags = ctx.resolveTags(props, "Tags");
        if (existing(resource, ctx) && ctx.reusesPriorEntity(name)) {
            DeploymentGroup current = service.getDeploymentGroup(ctx.region(), application, name);
            snapshot(resource, ctx.region(), arn, current);
            String desiredConfig = config;
            CfnRollback.guarded(resource, ctx, false, () -> {}, () -> restore(resource), () -> {
                service.updateDeploymentGroup(ctx.region(), application, name, null, desiredConfig, role, fields);
                replaceTags(arn, desiredTags);
            });
        } else {
            service.createDeploymentGroup(ctx.region(), application, name, config, role, fields);
            resource.setPhysicalId(name);
            resource.getAttributes().put(APPLICATION_ATTR, application);
            rememberApplication(resource, name, application);
            CfnRollback.guarded(resource, ctx, true, () -> delete(resource, ctx.region()), () -> {},
                    () -> replaceTags(arn, desiredTags));
        }
    }

    private static boolean existing(StackResource resource, ProvisionContext ctx) {
        return ctx.isUpdate() && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), resource.getAttributes());
    }

    private static String name(StackResource resource, JsonNode props, String property,
                               boolean identityChanged, ProvisionContext ctx) {
        String explicit = ctx.resolveOptional(props, property);
        if (explicit != null && !explicit.matches("[A-Za-z0-9+=,.@_-]{1,100}")) {
            throw invalid(property + " must be a valid CodeDeploy name of 1 to 100 characters");
        }
        if (!existing(resource, ctx)) {
            return explicit == null ? ctx.generatePhysicalName(resource.getLogicalId(), 100, false) : explicit;
        }
        String name = ctx.stablePhysicalName(explicit, resource.getLogicalId(), 100, false);
        boolean removedName = explicit == null && ctx.previouslyDeclared(property);
        if (removedName || (identityChanged && ctx.reusesPriorEntity(name))) {
            if (explicit != null) {
                throw invalid("Replacing this resource requires a new " + property);
            }
            return ctx.generatePhysicalName(resource.getLogicalId(), 100, false);
        }
        return name;
    }

    private static String required(JsonNode props, String property, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, property);
        if (value == null || value.isBlank()) {
            throw invalid(property + " is required");
        }
        return value;
    }

    private static void rejectCombination(JsonNode props, String first, String second) {
        if (props.hasNonNull(first) && props.hasNonNull(second)) {
            throw invalid(first + " and " + second + " cannot be combined");
        }
    }

    private static Map<String, Object> groupFields(JsonNode props, ProvisionContext ctx) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Set<String> dropped = ctx.droppedProperties(props);
        for (String property : GROUP_FIELDS) {
            if (props.hasNonNull(property)) {
                fields.put(apiName(property), apiValue(property, props.get(property)));
            } else if (dropped.contains(property)) {
                fields.put(apiName(property), null);
            }
        }
        return fields;
    }

    /** Convert CFN's nested names and tag-set wrappers, preserving the API's capitalized tag fields. */
    private static Object apiValue(String property, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (BOOLEAN_FIELDS.contains(property)) {
            if (!node.isBoolean() && !(node.isTextual()
                    && Set.of("true", "false").contains(node.asText()))) {
                throw invalid(property + " must be a boolean");
            }
            return node.asBoolean();
        }
        if (INTEGER_FIELDS.contains(property)) {
            try {
                return Integer.parseInt(node.asText());
            } catch (NumberFormatException e) {
                throw invalid(property + " must be an integer");
            }
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode child : node) {
                String wrapper = switch (property) {
                    case "Ec2TagSetList" -> "Ec2TagGroup";
                    case "OnPremisesTagSetList" -> "OnPremisesTagGroup";
                    default -> null;
                };
                values.add(apiValue(wrapper == null ? property : wrapper,
                        wrapper == null ? child : child.path(wrapper)));
            }
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            node.properties().forEach(entry ->
                    values.put(apiName(entry.getKey()), apiValue(entry.getKey(), entry.getValue())));
            return values;
        }
        return MAPPER.convertValue(node, Object.class);
    }

    private static String apiName(String name) {
        return switch (name) {
            case "Key", "Value", "Type" -> name;
            case "ECSServices" -> "ecsServices";
            default -> Character.toLowerCase(name.charAt(0)) + name.substring(1);
        };
    }

    private void snapshot(StackResource resource, String region, String arn, DeploymentGroup group) {
        ObjectNode snapshot = MAPPER.createObjectNode().put("region", region).put("arn", arn);
        snapshot.set("tags", MAPPER.valueToTree(tags(arn)));
        if (group != null) {
            snapshot.set("group", MAPPER.valueToTree(group));
        }
        resource.getAttributes().put(CfnRollback.CODEDEPLOY_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    private void restore(StackResource resource) {
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, CfnRollback.CODEDEPLOY_UPDATE_SNAPSHOT_ATTR);
        if (snapshot == null) {
            return;
        }
        JsonNode group = snapshot.get("group");
        if (group != null) {
            Map<String, Object> fields = MAPPER.convertValue(group, FIELDS);
            for (String property : GROUP_FIELDS) {
                fields.putIfAbsent(apiName(property), null);
            }
            service.updateDeploymentGroup(snapshot.path("region").asText(), group.path("applicationName").asText(),
                    group.path("deploymentGroupName").asText(), null, group.path("deploymentConfigName").asText(),
                    group.path("serviceRoleArn").asText(), fields);
        }
        replaceTags(snapshot.path("arn").asText(), MAPPER.convertValue(snapshot.path("tags"), TAGS));
        resource.getAttributes().remove(CfnRollback.CODEDEPLOY_UPDATE_SNAPSHOT_ATTR);
    }

    private Map<String, String> tags(String arn) {
        return service.listTagsForResource(arn).stream()
                .collect(Collectors.toMap(tag -> tag.get("Key"), tag -> tag.get("Value")));
    }

    private void replaceTags(String arn, Map<String, String> desired) {
        List<String> removed = ProvisionContext.staleTagKeys(tags(arn), desired);
        if (!removed.isEmpty()) {
            service.untagResource(arn, removed);
        }
        if (!desired.isEmpty()) {
            service.tagResource(arn, desired.entrySet().stream()
                    .map(entry -> Map.of("Key", entry.getKey(), "Value", entry.getValue())).toList());
        }
    }

    /**
     * Ref is only the group name. Keep its application separately through replacement cleanup,
     * including a failed replacement whose cleanup must be retried after prior attributes return.
     */
    private static ObjectNode applications(StackResource resource) {
        JsonNode saved = CfnRollback.updateSnapshot(resource, CfnRollback.CODEDEPLOY_GROUP_APPLICATIONS_ATTR);
        return saved == null ? MAPPER.createObjectNode() : (ObjectNode) saved;
    }

    private static void rememberApplication(StackResource resource, String group, String application) {
        ObjectNode saved = applications(resource);
        saved.put(group, application);
        resource.getAttributes().put(CfnRollback.CODEDEPLOY_GROUP_APPLICATIONS_ATTR, saved.toString());
    }

    @Override
    public void delete(StackResource resource, String region) {
        delete(resource, resource.getResourceType(), resource.getPhysicalId(), region);
    }

    private void delete(StackResource resource, String type, String id, String region) {
        if (id == null || id.isBlank() || CfnResourceDispatcher.isStub(id, resource.getAttributes())) {
            return;
        }
        CfnDeletes.safeDelete(type, id, () -> {
            if (APPLICATION.equals(type)) {
                service.deleteApplication(region, id);
            } else if (GROUP.equals(type)) {
                String application = applications(resource).path(id).asText(null);
                if (application == null) {
                    throw new IllegalStateException("Missing application identity for CodeDeploy group " + id);
                }
                service.deleteDeploymentGroup(region, application, id);
            } else {
                throw new IllegalArgumentException("Unsupported CodeDeploy resource type " + type);
            }
        }, "ApplicationDoesNotExistException", "DeploymentGroupDoesNotExistException");
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
        return ReplacementCleanup.complete(resource, (type, id, region) -> delete(resource, type, id, region));
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (!ReplacementCleanup.rollback(resource, (type, id, region) -> delete(resource, type, id, region))) {
            restore(resource);
        }
        return true;
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.CODEDEPLOY_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
        if (GROUP.equals(resource.getResourceType())) {
            Set<String> retained = new HashSet<>(ReplacementCleanup.owedPhysicalIds(resource));
            retained.add(resource.getPhysicalId());
            ObjectNode saved = applications(resource);
            saved.retain(retained);
            resource.getAttributes().put(CfnRollback.CODEDEPLOY_GROUP_APPLICATIONS_ATTR, saved.toString());
        }
    }

    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        String snapshot = attempted.getAttributes().get(CfnRollback.CODEDEPLOY_UPDATE_SNAPSHOT_ATTR);
        if (snapshot != null && attempted.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR)) {
            previous.getAttributes().put(CfnRollback.CODEDEPLOY_UPDATE_SNAPSHOT_ATTR, snapshot);
        }
        ObjectNode saved = applications(previous);
        saved.setAll(applications(attempted));
        if (!saved.isEmpty()) {
            previous.getAttributes().put(CfnRollback.CODEDEPLOY_GROUP_APPLICATIONS_ATTR, saved.toString());
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }
}
