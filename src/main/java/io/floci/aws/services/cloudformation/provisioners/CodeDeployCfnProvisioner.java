package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.codedeploy.CodeDeployService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * CloudFormation provisioning for CodeDeploy applications and deployment configurations. Previously unhandled, so
 * the type fell through to the generic stub: the stack reported CREATE_COMPLETE and no application
 * existed in CodeDeploy (issue #8626). {@code Ref} is the application name; the registry schema
 * declares no {@code Fn::GetAtt} attributes.
 */
@ApplicationScoped
public class CodeDeployCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::CodeDeploy::Application";
    private static final String CONFIG = "AWS::CodeDeploy::DeploymentConfig";
    private static final String CONFIGURATION_ATTR = CfnRollback.USES_ATTR_PREFIX + "CodeDeployConfiguration";
    private static final TypeReference<Map<String, Object>> FIELDS = new TypeReference<>() {};
    private static final int APPLICATION_NAME_MAX_LENGTH = 100;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The tags an in-place update is about to change, for {@link #rollbackUpdate} to put back. */
    static final String SNAPSHOT_ATTR = "__FlociCodeDeployApplicationUpdateSnapshot";
    /** The tag keys the template that provisioned the application set: the next update drops the ones it omits. */
    static final String TAG_KEYS_ATTR = "__FlociCodeDeployTagKeys";

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

    private final CodeDeployService codeDeployService;

    @Inject
    public CodeDeployCfnProvisioner(CodeDeployService codeDeployService) {
        this.codeDeployService = codeDeployService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE, CONFIG);
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
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement or a stub migration is undone through the cleanup record, and an in-place update
     * from the tag snapshot taken before it. The snapshot is spent only once the restore succeeded,
     * so one that throws leaves it for ContinueUpdateRollback to retry.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, SNAPSHOT_ATTR);
        if (snapshot == null) {
            return false;
        }
        // An application the update created because the prior one was gone is deleted instead.
        if (CfnRollback.undoCreatedByUpdate(resource, snapshot, SNAPSHOT_ATTR, null,
                (createdId, region) -> delete(TYPE, createdId, region))) {
            return true;
        }
        restoreTags(snapshot);
        Ec2Tags.restoreRecord(resource, TAG_KEYS_ATTR, snapshot);
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        return true;
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (CONFIG.equals(r.getResourceType())) {
            if (ctx.isUpdate()) {
                ReplacementCleanup.endRollbackRetry(r);
            }
            Map<String, String> previous = new HashMap<>(r.getAttributes());
            configuration(r, props == null ? MAPPER.createObjectNode()
                    : ctx.engine().resolveNodeOmittingNoValue(props), ctx);
            ReplacementCleanup.record(r, ctx, previous);
            return;
        }
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
        boolean replaces = ctx.isUpdate() && ctx.changes(TYPE, props).requiresReplacement();
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
            codeDeployService.createApplication(ctx.region(), name, ctx.resolveOptionalWritten(props, "ComputePlatform"),
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
            codeDeployService.getDeploymentConfig(ctx.region(), name);
        } else {
            codeDeployService.createDeploymentConfig(ctx.region(), name,
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

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }

    /**
     * The update handler the registry schema gives the type: a tag change and nothing else. Only the
     * keys the previous template or this one sets are the stack's to change or drop, so a tag added
     * outside the stack stays, as on AWS. The tags those keys hold are snapshotted first, so a later
     * resource's failure rolls them back, and a failure here puts them back before it propagates.
     */
    private void updateTagsInPlace(StackResource r, ProvisionContext ctx, String name, Map<String, String> desired) {
        String arn = codeDeployService.applicationArn(ctx.region(), name);
        Map<String, String> current = new HashMap<>();
        codeDeployService.listTagsForResource(arn).forEach(t -> current.put(t.get("Key"), t.get("Value")));
        Set<String> owned = Ec2Tags.ownedKeys(r, TAG_KEYS_ATTR, ctx);
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("name", name);
        snapshot.put("region", ctx.region());
        Ec2Tags.snapshotOwned(snapshot, owned, desired, current, r.getAttributes().get(TAG_KEYS_ATTR));
        r.getAttributes().put(SNAPSHOT_ATTR, snapshot.toString());
        CfnRollback.guarded(r, ctx, false, () -> { }, () -> restoreTags(snapshot),
                () -> reconcileTags(arn, Ec2Tags.touched(owned, desired), desired));
    }

    /** Whether the application an update starts from still exists; any failure but not-found propagates. */
    private boolean applicationExists(String region, String name) {
        try {
            codeDeployService.getApplication(region, name);
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
        String arn = codeDeployService.applicationArn(snapshot.path("region").asText(), snapshot.path("name").asText());
        reconcileTags(arn, touched, CfnRollback.snapshotTags(snapshot));
    }

    private void reconcileTags(String arn, Collection<String> managedKeys, Map<String, String> desired) {
        CfnRollback.reconcileTags(managedKeys, desired, dropped -> codeDeployService.untagResource(arn, dropped),
                added -> codeDeployService.tagResource(arn, toTagList(added)));
    }

    private static List<Map<String, String>> toTagList(Map<String, String> tags) {
        return tags.entrySet().stream().map(e -> Map.of("Key", e.getKey(), "Value", e.getValue())).toList();
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (CONFIG.equals(resourceType)) {
            CfnDeletes.safeDelete("CodeDeploy deployment configuration", physicalId,
                    () -> codeDeployService.deleteDeploymentConfig(region, physicalId),
                    "DeploymentConfigDoesNotExistException");
            return;
        }
        CfnDeletes.safeDelete("CodeDeploy application", physicalId,
                () -> codeDeployService.deleteApplication(region, physicalId), "ApplicationDoesNotExistException");
    }
}
