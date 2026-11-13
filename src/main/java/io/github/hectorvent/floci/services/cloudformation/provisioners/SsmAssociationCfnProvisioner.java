package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.SsmAssociation;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns association configuration and identity; State Manager execution is separate. */
@ApplicationScoped
public class SsmAssociationCfnProvisioner implements CfnResourceProvisioner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UPDATE_SNAPSHOT = "__FlociSsmAssociationUpdate";
    private final SsmService ssm;

    public SsmAssociationCfnProvisioner(SsmService ssm) {
        this.ssm = ssm;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::SSM::Association");
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        resource.getAttributes().remove(UPDATE_SNAPSHOT);
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(resource);
        }
        JsonNode resolved = ctx.engine().resolveNodeOmittingNoValue(props);
        validate(resolved);
        String name = text(resolved, "Name");
        String documentType = ssm.getDocument(name, ctx.region()).getDocumentType();
        if (!Set.of("Command", "Automation").contains(documentType)) {
            throw validation("An association requires a Command or Automation document.");
        }
        Map<String, String> before = new HashMap<>(resource.getAttributes());
        boolean existing = ctx.isUpdate() && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), before);
        SsmAssociation current = existing
                ? ssm.describeAssociation(ctx.priorPhysicalId(), null, null, ctx.region()) : null;
        String instanceId = text(resolved, "InstanceId");
        SsmAssociation association;
        if (existing && Objects.equals(instanceId, current.getInstanceId())) {
            snapshot(resource, current, before, ctx.region());
            try {
                association = update(current.getAssociationId(), resolved, current.getAssociationVersion(), ctx.region());
            } catch (AwsException error) {
                // Service validation and the version guard run before mutation.
                resource.getAttributes().remove(UPDATE_SNAPSHOT);
                throw error;
            }
        } else {
            association = ssm.createAssociation(name, text(resolved, "AssociationName"),
                    documentVersion(resolved), instanceId, targets(resolved), parameters(resolved),
                    text(resolved, "ScheduleExpression"), text(resolved, "MaxErrors"),
                    text(resolved, "MaxConcurrency"), text(resolved, "ComplianceSeverity"), ctx.region());
        }
        resource.setPhysicalId(association.getAssociationId());
        resource.getAttributes().put("AssociationId", association.getAssociationId());
        ReplacementCleanup.record(resource, ctx, before);
    }

    private SsmAssociation update(String id, JsonNode props, String expectedVersion, String region) {
        return ssm.updateAssociation(id, text(props, "Name"), text(props, "AssociationName"),
                documentVersion(props), targets(props), parameters(props), text(props, "ScheduleExpression"),
                text(props, "MaxErrors"), text(props, "MaxConcurrency"), text(props, "ComplianceSeverity"),
                expectedVersion, region);
    }

    private static String documentVersion(JsonNode props) {
        String version = text(props, "DocumentVersion");
        return version == null && text(props, "InstanceId") == null ? "$DEFAULT" : version;
    }

    private static String text(JsonNode props, String key) {
        JsonNode value = props.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw validation(key + " must be a string.");
        }
        return value.textValue();
    }

    private static void validate(JsonNode props) {
        if (props == null || !props.isObject()) {
            throw validation("Properties must contain Name and association targets.");
        }
        String name = text(props, "Name");
        if (name == null || !name.matches("[a-zA-Z0-9_\\-.:/]{3,200}")) {
            throw validation("Name must identify an SSM document.");
        }
        String associationName = text(props, "AssociationName");
        if (associationName != null && !associationName.matches("[a-zA-Z0-9_\\-.]{3,128}")) {
            throw validation("AssociationName must contain 3 to 128 name characters.");
        }
        String instance = text(props, "InstanceId");
        List<SsmAssociation.Target> targets = targets(props);
        boolean hasTargets = targets != null && !targets.isEmpty();
        if (instance == null && !hasTargets || instance != null && hasTargets) {
            throw validation("Specify exactly one of InstanceId or nonempty Targets.");
        }
        if (instance != null) {
            if (!instance.matches("i-(\\w{8}|\\w{17})|mi-\\w{17}")) {
                throw validation("InstanceId must identify a managed instance.");
            }
            for (String property : List.of("AssociationName", "DocumentVersion", "MaxErrors",
                    "MaxConcurrency", "OutputLocation", "ScheduleExpression")) {
                if (props.hasNonNull(property)) {
                    throw validation(property + " requires Targets instead of InstanceId.");
                }
            }
        }
        String version = text(props, "DocumentVersion");
        if (version != null && !version.matches("\\$(LATEST|DEFAULT)|[1-9][0-9]*")) {
            throw validation("DocumentVersion must be $LATEST, $DEFAULT or a positive version.");
        }
        String severity = text(props, "ComplianceSeverity");
        if (severity != null && !Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNSPECIFIED").contains(severity)) {
            throw validation("ComplianceSeverity is not valid.");
        }
        String concurrency = text(props, "MaxConcurrency");
        if (concurrency != null && !concurrency.matches("[1-9][0-9]{0,6}|[1-9][0-9]?%|100%")) {
            throw validation("MaxConcurrency is not valid.");
        }
        String errors = text(props, "MaxErrors");
        if (errors != null && !errors.matches("0|[1-9][0-9]{0,6}|[0-9][0-9]?%|100%")) {
            throw validation("MaxErrors is not valid.");
        }
        String schedule = text(props, "ScheduleExpression");
        if (schedule != null && (schedule.isEmpty() || schedule.length() > 256)) {
            throw validation("ScheduleExpression must contain 1 to 256 characters.");
        }
        parameters(props);
        for (String property : List.of("ApplyOnlyAtCronInterval", "AssociationDispatchAssumeRole",
                "AutomationTargetParameterName", "CalendarNames", "OutputLocation", "ScheduleOffset",
                "SyncCompliance", "Tags", "WaitForSuccessTimeoutSeconds")) {
            JsonNode value = props.get(property);
            if (value != null && !value.isNull() && !(value.isArray() && value.isEmpty())) {
                throw validation("Floci does not yet support " + property + " for AWS::SSM::Association.");
            }
        }
    }

    private static List<SsmAssociation.Target> targets(JsonNode props) {
        JsonNode values = props.get("Targets");
        if (values == null || values.isNull()) {
            return null;
        }
        if (!values.isArray() || values.size() > 5) {
            throw validation("Targets must contain at most five target objects.");
        }
        List<SsmAssociation.Target> targets = new ArrayList<>();
        for (JsonNode target : values) {
            String key = text(target, "Key");
            if (key == null || key.isEmpty() || key.length() > 128) {
                throw validation("Each target requires a Key with 1 to 128 characters.");
            }
            List<String> selected = strings(target.get("Values"), "Targets.Values");
            if (selected.size() > 50) {
                throw validation("Targets.Values must contain at most fifty values.");
            }
            targets.add(new SsmAssociation.Target(key, selected));
        }
        return targets;
    }

    private static Map<String, List<String>> parameters(JsonNode props) {
        JsonNode values = props.get("Parameters");
        if (values == null || values.isNull()) {
            return null;
        }
        if (!values.isObject()) {
            throw validation("Parameters must map names to string arrays.");
        }
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        values.properties().forEach(entry -> {
            if (entry.getKey().isEmpty() || entry.getKey().length() > 255) {
                throw validation("Parameter names must contain 1 to 255 characters.");
            }
            parameters.put(entry.getKey(), strings(entry.getValue(), "Parameters." + entry.getKey()));
        });
        return parameters;
    }

    private static List<String> strings(JsonNode node, String property) {
        if (node == null || !node.isArray()) {
            throw validation(property + " must be a string array.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw validation(property + " must contain strings.");
            }
            values.add(value.textValue());
        }
        return values;
    }

    private static void snapshot(StackResource resource, SsmAssociation current,
                                 Map<String, String> attributes, String region) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region).put("id", current.getAssociationId());
        ObjectNode config = snapshot.putObject("configuration");
        config.put("Name", current.getName()).put("AssociationName", current.getAssociationName())
                .put("DocumentVersion", current.getDocumentVersion()).put("InstanceId", current.getInstanceId())
                .put("ScheduleExpression", current.getScheduleExpression()).put("MaxErrors", current.getMaxErrors())
                .put("MaxConcurrency", current.getMaxConcurrency()).put("ComplianceSeverity", current.getComplianceSeverity());
        config.set("Targets", MAPPER.valueToTree(current.getTargets()));
        config.set("Parameters", MAPPER.valueToTree(current.getParameters()));
        ObjectNode prior = snapshot.putObject("attributes");
        attributes.forEach((key, value) -> {
            if (restorable(key) && value != null) {
                prior.put(key, value);
            }
        });
        resource.getAttributes().put(UPDATE_SNAPSHOT, snapshot.toString());
    }

    private static boolean restorable(String key) {
        return !key.startsWith("__Floci") || key.startsWith(CfnRollback.USES_ATTR_PREFIX);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationError", message, 400);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("SSM association", physicalId,
                () -> ssm.deleteAssociation(physicalId, null, null, region), "AssociationDoesNotExist");
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(UPDATE_SNAPSHOT);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        String raw = resource.getAttributes().get(UPDATE_SNAPSHOT);
        if (raw == null) {
            return false;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read the SSM association update snapshot.", error);
        }
        update(snapshot.path("id").asText(), snapshot.path("configuration"), null, snapshot.path("region").asText());
        resource.getAttributes().keySet().removeIf(SsmAssociationCfnProvisioner::restorable);
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
