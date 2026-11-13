package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmPatchBaselineService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns an SSM patch baseline's configuration, group bindings and registration as a default. */
@ApplicationScoped
public class SsmPatchBaselineCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::SSM::PatchBaseline";
    private static final String UPDATE_SNAPSHOT = "__FlociSsmPatchBaselineUpdate";
    private static final String TAG_KEYS = CfnRollback.USES_ATTR_PREFIX + "SsmPatchBaselineTagKeys";
    private static final String DEFAULT_DECLARED = CfnRollback.USES_ATTR_PREFIX + "SsmPatchBaselineDefault";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> CONFIGURATION = List.of(
            "Name", "OperatingSystem", "GlobalFilters", "ApprovalRules", "ApprovedPatches",
            "ApprovedPatchesComplianceLevel", "ApprovedPatchesEnableNonSecurity", "RejectedPatches",
            "RejectedPatchesAction", "Description", "Sources", "AvailableSecurityUpdatesComplianceStatus");

    private final SsmPatchBaselineService baselines;

    public SsmPatchBaselineCfnProvisioner(SsmPatchBaselineService baselines) {
        this.baselines = baselines;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        if (resource.getAttributes().containsKey(UPDATE_SNAPSHOT)) {
            rollbackUpdate(resource);
        }
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(resource);
        }
        CfnPropertyTypes.Reader reader = new CfnPropertyTypes.Reader(ctx.engine());
        JsonNode props = reader.read("", properties == null || properties.isNull()
                ? MAPPER.createObjectNode() : properties, TYPE);
        reader.finish();
        Map<String, String> before = new HashMap<>(resource.getAttributes());
        boolean existing = ctx.isUpdate() && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), before);
        ObjectNode current = existing ? baselines.get(address(ctx.priorPhysicalId()), ctx.region()) : null;
        String os = props.path("OperatingSystem").asText("WINDOWS");
        boolean update = existing && os.equals(current.path("OperatingSystem").asText());
        boolean desiredDefault = props.path("DefaultBaseline").asBoolean(false);
        boolean previousDefault = before.containsKey(DEFAULT_DECLARED)
                ? Boolean.parseBoolean(before.get(DEFAULT_DECLARED))
                : ctx.priorProperties() != null && ctx.priorProperties().path("DefaultBaseline").asBoolean(false);
        Map<String, String> tags = tags(props.path("Tags"));
        Set<String> groups = strings(props.path("PatchGroups"));
        String id;
        if (update) {
            id = ctx.priorPhysicalId();
            snapshot(resource, current, before, tags.keySet(), previousDefault, desiredDefault, ctx.region());
            baselines.update(updateRequest(props, id), ctx.region());
            reconcileGroups(id, strings(current.path("PatchGroups")), groups, ctx.region());
            reconcileTags(id, tagKeys(before), tags, ctx.region());
            // AWS's update handler registers only on false -> true. False never unregisters.
            if (desiredDefault && !previousDefault) {
                baselines.registerDefault(address(id), ctx.region());
            }
        } else {
            ObjectNode request = configuration(props);
            if (props.has("Tags")) {
                request.set("Tags", props.get("Tags"));
            }
            id = baselines.create(request, ctx.region()).path("BaselineId").asText();
            resource.setPhysicalId(id);
            resource.getAttributes().put("Id", id);
            String created = id;
            CfnRollback.guarded(resource, ctx, true, () -> delete(TYPE, created, ctx.region()), () -> { }, () -> {
                reconcileGroups(created, Set.of(), groups, ctx.region());
                if (desiredDefault) {
                    baselines.registerDefault(address(created), ctx.region());
                }
            });
        }
        resource.setPhysicalId(id);
        resource.getAttributes().put("Id", id);
        resource.getAttributes().put(TAG_KEYS, MAPPER.valueToTree(tags.keySet()).toString());
        resource.getAttributes().put(DEFAULT_DECLARED, Boolean.toString(desiredDefault));
        ReplacementCleanup.record(resource, ctx, before);
    }

    private static ObjectNode configuration(JsonNode props) {
        ObjectNode request = MAPPER.createObjectNode();
        for (String property : CONFIGURATION) {
            if (props.hasNonNull(property)) {
                request.set(property, props.get(property));
            }
        }
        return request;
    }

    private static ObjectNode updateRequest(JsonNode props, String id) {
        ObjectNode request = configuration(props);
        request.remove("OperatingSystem");
        return request.put("BaselineId", id).put("Replace", true);
    }

    private static ObjectNode address(String id) {
        return MAPPER.createObjectNode().put("BaselineId", id);
    }

    private static ObjectNode tagAddress(String id) {
        return MAPPER.createObjectNode().put("ResourceType", "PatchBaseline").put("ResourceId", id);
    }

    private static Set<String> strings(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static Map<String, String> tags(JsonNode values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode tag : values) {
            if (!tag.hasNonNull("Key") || !tag.hasNonNull("Value")) {
                throw new AwsException("InvalidRequest", "Each tag requires both Key and Value.", 400);
            }
            result.put(tag.get("Key").asText(), tag.get("Value").asText());
        }
        return result;
    }

    private static Set<String> tagKeys(Map<String, String> attributes) {
        String raw = attributes.get(TAG_KEYS);
        if (raw == null) {
            return Set.of();
        }
        try {
            return strings(MAPPER.readTree(raw));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read the SSM patch baseline's template tag keys.", error);
        }
    }

    private void reconcileGroups(String id, Set<String> current, Set<String> desired, String region) {
        for (String group : current) {
            if (!desired.contains(group)) {
                baselines.deregisterGroup(address(id).put("PatchGroup", group), region);
            }
        }
        for (String group : desired) {
            if (!current.contains(group)) {
                baselines.registerGroup(address(id).put("PatchGroup", group), region);
            }
        }
    }

    private void reconcileTags(String id, Set<String> managed, Map<String, String> desired, String region) {
        ObjectNode remove = tagAddress(id);
        ArrayNode keys = remove.putArray("TagKeys");
        managed.stream().filter(key -> !desired.containsKey(key)).forEach(keys::add);
        if (!keys.isEmpty()) {
            baselines.resourceTags("RemoveTagsFromResource", remove, region);
        }
        if (!desired.isEmpty()) {
            ObjectNode add = tagAddress(id);
            ArrayNode values = add.putArray("Tags");
            desired.forEach((key, value) -> values.addObject().put("Key", key).put("Value", value));
            baselines.resourceTags("AddTagsToResource", add, region);
        }
    }

    private void snapshot(StackResource resource, ObjectNode current, Map<String, String> before,
                          Set<String> desiredTagKeys, boolean previousDefault, boolean desiredDefault,
                          String region) {
        String id = current.path("BaselineId").asText();
        ObjectNode snapshot = address(id).put("region", region);
        snapshot.set("configuration", current);
        snapshot.put("previousDefault", previousDefault).put("desiredDefault", desiredDefault);
        ObjectNode attributes = snapshot.putObject("attributes");
        before.forEach((key, value) -> {
            if (restorable(key) && value != null) {
                attributes.put(key, value);
            }
        });
        Set<String> managed = new LinkedHashSet<>(tagKeys(before));
        managed.addAll(desiredTagKeys);
        snapshot.set("tagKeys", MAPPER.valueToTree(managed));
        Map<String, String> currentTags = tags(
                baselines.resourceTags("ListTagsForResource", tagAddress(id), region).path("TagList"));
        ObjectNode savedTags = snapshot.putObject("tags");
        managed.stream().filter(currentTags::containsKey).forEach(key -> savedTags.put(key, currentTags.get(key)));
        resource.getAttributes().put(UPDATE_SNAPSHOT, snapshot.toString());
    }

    private static boolean restorable(String key) {
        return !key.startsWith("__Floci") || key.startsWith(CfnRollback.USES_ATTR_PREFIX);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("SSM patch baseline", physicalId, () -> {
            ObjectNode current = baselines.get(address(physicalId), region);
            reconcileGroups(physicalId, strings(current.path("PatchGroups")), Set.of(), region);
            baselines.delete(address(physicalId), region);
        }, "DoesNotExistException");
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(UPDATE_SNAPSHOT);
    }

    @Override
    public boolean requiresUpdateRecovery(StackResource resource) {
        return resource.getAttributes().containsKey(UPDATE_SNAPSHOT);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, UPDATE_SNAPSHOT);
        if (snapshot == null) {
            return false;
        }
        String id = snapshot.path("BaselineId").asText();
        String region = snapshot.path("region").asText();
        ObjectNode current = baselines.get(address(id), region);
        JsonNode prior = snapshot.path("configuration");
        baselines.update(updateRequest(prior, id), region);
        reconcileGroups(id, strings(current.path("PatchGroups")), strings(prior.path("PatchGroups")), region);
        Map<String, String> priorTags = new LinkedHashMap<>();
        snapshot.path("tags").properties().forEach(entry -> priorTags.put(entry.getKey(), entry.getValue().asText()));
        reconcileTags(id, strings(snapshot.path("tagKeys")), priorTags, region);
        if (snapshot.path("previousDefault").asBoolean() && !snapshot.path("desiredDefault").asBoolean()) {
            baselines.registerDefault(address(id), region);
        }
        resource.getAttributes().keySet().removeIf(SsmPatchBaselineCfnProvisioner::restorable);
        snapshot.path("attributes").properties().forEach(
                entry -> resource.getAttributes().put(entry.getKey(), entry.getValue().asText()));
        resource.getAttributes().remove(UPDATE_SNAPSHOT);
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
        resource.getAttributes().remove(UPDATE_SNAPSHOT);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(UPDATE_SNAPSHOT);
        ReplacementCleanup.clear(resource);
    }
}
