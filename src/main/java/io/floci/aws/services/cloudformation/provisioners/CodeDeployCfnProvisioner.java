package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.codedeploy.CodeDeployService;
import io.floci.aws.services.codedeploy.model.DeploymentGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** CodeDeploy applications, groups and configurations. Ref returns names; these types have no GetAtt attributes. */
@ApplicationScoped
public class CodeDeployCfnProvisioner implements CfnResourceProvisioner {
    private static final String TYPE = "AWS::CodeDeploy::Application";
    private static final String APPLICATION = "AWS::CodeDeploy::Application";
    private static final String GROUP = "AWS::CodeDeploy::DeploymentGroup";
    private static final String CONFIG = "AWS::CodeDeploy::DeploymentConfig";
    private static final String APPLICATION_ATTR = CfnRollback.USES_ATTR_PREFIX + "CodeDeployApplication";
    private static final String PLATFORM_ATTR = CfnRollback.USES_ATTR_PREFIX + "CodeDeployPlatform";
    private static final String CONFIGURATION_ATTR = CfnRollback.USES_ATTR_PREFIX + "CodeDeployConfiguration";
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
    private static final Map<String, Set<String>> CONFIG_MEMBERS = Map.of(
            "MinimumHealthyHosts", Set.of("Type", "Value"),
            "MinimumHealthyHostsPerZone", Set.of("Type", "Value"),
            "TrafficRoutingConfig", Set.of("Type", "TimeBasedCanary", "TimeBasedLinear"),
            "TimeBasedCanary", Set.of("CanaryInterval", "CanaryPercentage"),
            "TimeBasedLinear", Set.of("LinearInterval", "LinearPercentage"),
            "ZonalConfig", Set.of("FirstZoneMonitorDurationInSeconds", "MonitorDurationInSeconds",
                    "MinimumHealthyHostsPerZone"));
    private static final Map<String, Set<String>> CONFIG_REQUIRED = Map.of(
            "MinimumHealthyHosts", Set.of("Type", "Value"),
            "MinimumHealthyHostsPerZone", Set.of("Type", "Value"),
            "TrafficRoutingConfig", Set.of("Type"),
            "TimeBasedCanary", Set.of("CanaryInterval", "CanaryPercentage"),
            "TimeBasedLinear", Set.of("LinearInterval", "LinearPercentage"));
    private static final Set<String> CONFIG_INTEGER_FIELDS = Set.of(
            "Value", "CanaryInterval", "CanaryPercentage", "LinearInterval", "LinearPercentage");
    private static final Set<String> CONFIG_LONG_FIELDS = Set.of(
            "FirstZoneMonitorDurationInSeconds", "MonitorDurationInSeconds");

    private final CodeDeployService service;

    @Inject
    public CodeDeployCfnProvisioner(CodeDeployService service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(APPLICATION, GROUP, CONFIG);
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
            case CONFIG -> configuration(resource, props, ctx);
            default -> throw new IllegalArgumentException("Unsupported CodeDeploy type " + resource.getResourceType());
        }
        ReplacementCleanup.record(resource, ctx, previous);
    }

    private void configuration(StackResource resource, JsonNode props, ProvisionContext ctx) {
        String platform = ctx.resolveOrDefault(props, "ComputePlatform", "Server");
        Map<String, Object> fields = new TreeMap<>();
        fields.put("computePlatform", platform);
        for (String property : List.of("MinimumHealthyHosts", "TrafficRoutingConfig", "ZonalConfig")) {
            if (props.hasNonNull(property)) {
                fields.put(Character.toLowerCase(property.charAt(0)) + property.substring(1),
                        configurationValue(props.get(property), property));
            }
        }
        String desired = MAPPER.valueToTree(fields).toString();
        boolean changed = existing(resource, ctx)
                && !desired.equals(resource.getAttributes().get(CONFIGURATION_ATTR));
        String name = name(resource, props, "DeploymentConfigName", changed, ctx);
        if (existing(resource, ctx) && ctx.reusesPriorEntity(name)) {
            service.getDeploymentConfig(ctx.region(), name);
        } else {
            service.createDeploymentConfig(ctx.region(), name,
                    MAPPER.convertValue(fields.get("minimumHealthyHosts"), FIELDS), platform,
                    MAPPER.convertValue(fields.get("trafficRoutingConfig"), FIELDS),
                    MAPPER.convertValue(fields.get("zonalConfig"), FIELDS));
        }
        resource.setPhysicalId(name);
        resource.getAttributes().put(CONFIGURATION_ATTR, desired);
    }

    /** Configuration schemas use lower-camel API keys, including the Type/Value pair used by host counts. */
    private static Object configurationValue(JsonNode value, String property) {
        Set<String> members = CONFIG_MEMBERS.get(property);
        if (members != null) {
            if (!value.isObject()) {
                throw invalid(property + " must be an object");
            }
            for (String required : CONFIG_REQUIRED.getOrDefault(property, Set.of())) {
                if (!value.hasNonNull(required)) {
                    throw invalid(property + "." + required + " is required");
                }
            }
            Map<String, Object> result = new TreeMap<>();
            value.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (!members.contains(key)) {
                    throw invalid(property + " does not support " + key);
                }
                if (!entry.getValue().isNull()) {
                    result.put(Character.toLowerCase(key.charAt(0)) + key.substring(1),
                            configurationValue(entry.getValue(), key));
                }
            });
            return result;
        }
        if (value.isContainerNode() || value.isNull()) {
            throw invalid(property + " must be a scalar");
        }
        try {
            if (CONFIG_INTEGER_FIELDS.contains(property)) {
                return Integer.parseInt(value.asText());
            }
            if (CONFIG_LONG_FIELDS.contains(property)) {
                return Long.parseLong(value.asText());
            }
        } catch (NumberFormatException invalidNumber) {
            throw invalid(property + " must be a valid integer");
        }
        return value.asText();
    }

    private void application(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String explicitName = ctx.resolveOptionalWritten(props, "ApplicationName");
        boolean hasExplicitName = explicitName != null;
        String priorPhysicalId = ctx.priorPhysicalId();

        // ApplicationName and ComputePlatform are create-only; only tags update in place.
        boolean replaces = ctx.isUpdate() && ctx.changes(APPLICATION, props).requiresReplacement();
        if (replaces && hasExplicitName && explicitName.equals(priorPhysicalId)) {
            throw CfnExceptions.customNamedReplacement(explicitName);
        }
        String name;
        if (hasExplicitName) {
            name = explicitName;
        } else if (ctx.isUpdate() && !replaces) {
            name = priorPhysicalId;
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), APPLICATION_NAME_MAX_LENGTH, false);
        }

        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        // A stack from before this type had a provisioner, or deployed while CodeDeploy was disabled,
        // holds the dispatcher's stub, which names no application: taking over its id is a create.
        boolean stub = CfnResourceDispatcher.isStub(priorPhysicalId, attributesBefore);
        r.setPhysicalId(name);
        boolean reuses = ctx.reusesPriorEntity(name) && !stub;
        if (reuses && applicationExists(ctx.region(), name)) {
            updateTagsInPlace(r, ctx, name, tags);
        } else {
            service.createApplication(ctx.region(), name, ctx.resolveOptionalWritten(props, "ComputePlatform"),
                    toTagList(tags));
            if (reuses) {
                // The application the update started from is gone, so this one is the update's to undo.
                CfnRollback.recordCreatedByUpdate(r, ctx, SNAPSHOT_ATTR);
            }
        }
        Ec2Tags.recordKeys(r, TAG_KEYS_ATTR, tags);
        if (stub) {
            // The type has no Arn attribute; a rollback puts the stub's back from the cleanup record.
            r.getAttributes().remove("Arn");
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
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
            service.createDeploymentGroup(ctx.region(), application, name, config, role, fields, List.of());
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
        if (CfnResourceDispatcher.isStub(resource.getPhysicalId(), resource.getAttributes())) {
            return;
        }
        delete(resource, resource.getResourceType(), resource.getPhysicalId(), region);
    }

    private void delete(StackResource resource, String type, String id, String region) {
        if (id == null || id.isBlank()) {
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
            } else if (CONFIG.equals(type)) {
                service.deleteDeploymentConfig(region, id);
            } else {
                throw new IllegalArgumentException("Unsupported CodeDeploy resource type " + type);
            }
        }, "ApplicationDoesNotExistException", "DeploymentGroupDoesNotExistException",
                "DeploymentConfigDoesNotExistException");
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
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        return ReplacementCleanup.complete(resource, (type, id, region) -> delete(resource, type, id, region));
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (APPLICATION.equals(resource.getResourceType())) {
            if (ReplacementCleanup.rollback(resource, (type, id, region) -> delete(resource, type, id, region))) {
                return true;
            }
            JsonNode snapshot = CfnRollback.updateSnapshot(resource, SNAPSHOT_ATTR);
            if (snapshot == null) {
                return false;
            }
            if (CfnRollback.undoCreatedByUpdate(resource, snapshot, SNAPSHOT_ATTR, null,
                    (createdId, region) -> delete(resource, APPLICATION, createdId, region))) {
                return true;
            }
            restoreTags(snapshot);
            Ec2Tags.restoreRecord(resource, TAG_KEYS_ATTR, snapshot);
            resource.getAttributes().remove(SNAPSHOT_ATTR);
            return true;
        }
        if (!ReplacementCleanup.rollback(resource, (type, id, region) -> delete(resource, type, id, region))) {
            restore(resource);
        }
        return true;
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.CODEDEPLOY_UPDATE_SNAPSHOT_ATTR);
        resource.getAttributes().remove(SNAPSHOT_ATTR);
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
        CfnRollback.carryFailedUndoSnapshot(previous, attempted, SNAPSHOT_ATTR);
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


private static final int APPLICATION_NAME_MAX_LENGTH = 100;

static final String SNAPSHOT_ATTR = "__FlociCodeDeployApplicationUpdateSnapshot";

static final String TAG_KEYS_ATTR = "__FlociCodeDeployTagKeys";

private void updateTagsInPlace(StackResource r, ProvisionContext ctx, String name, Map<String, String> desired) {
        String arn = service.applicationArn(ctx.region(), name);
        Map<String, String> current = new HashMap<>();
        service.listTagsForResource(arn).forEach(t -> current.put(t.get("Key"), t.get("Value")));
        Set<String> owned = Ec2Tags.ownedKeys(r, TAG_KEYS_ATTR, ctx);
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("name", name);
        snapshot.put("region", ctx.region());
        Ec2Tags.snapshotOwned(snapshot, owned, desired, current, r.getAttributes().get(TAG_KEYS_ATTR));
        r.getAttributes().put(SNAPSHOT_ATTR, snapshot.toString());
        CfnRollback.guarded(r, ctx, false, () -> { }, () -> restoreTags(snapshot),
                () -> reconcileTags(arn, Ec2Tags.touched(owned, desired), desired));
    }

private boolean applicationExists(String region, String name) {
        try {
            service.getApplication(region, name);
            return true;
        } catch (AwsException e) {
            if ("ApplicationDoesNotExistException".equals(e.getErrorCode())) {
                return false;
            }
            throw e;
        }
    }

private void restoreTags(JsonNode snapshot) {
        List<String> touched = new ArrayList<>();
        snapshot.path("touched").forEach(key -> touched.add(key.asText()));
        String arn = service.applicationArn(snapshot.path("region").asText(), snapshot.path("name").asText());
        reconcileTags(arn, touched, CfnRollback.snapshotTags(snapshot));
    }

private void reconcileTags(String arn, Collection<String> managedKeys, Map<String, String> desired) {
        CfnRollback.reconcileTags(managedKeys, desired, dropped -> service.untagResource(arn, dropped),
                added -> service.tagResource(arn, toTagList(added)));
    }

private static List<Map<String, String>> toTagList(Map<String, String> tags) {
        return tags.entrySet().stream().map(e -> Map.of("Key", e.getKey(), "Value", e.getValue())).toList();
    }
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("CodeDeploy application", physicalId,
                () -> service.deleteApplication(region, physicalId), "ApplicationDoesNotExistException");
    }
}
