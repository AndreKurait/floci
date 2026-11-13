package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.SsmDocument;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns the backing SSM document, its version selection and replacement cleanup. */
@ApplicationScoped
public class SsmDocumentCfnProvisioner implements CfnResourceProvisioner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UPDATE_SNAPSHOT = "__FlociSsmDocumentUpdate";
    private static final String NAME_DECLARED = CfnRollback.USES_ATTR_PREFIX + "SsmDocumentNameDeclared";
    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "ApplicationConfiguration", "ApplicationConfigurationSchema", "Automation",
            "Automation.ChangeTemplate", "AutoApprovalPolicy", "ChangeCalendar", "CloudFormation",
            "Command", "DeploymentStrategy", "ManualApprovalPolicy", "Package", "Policy",
            "ProblemAnalysis", "ProblemAnalysisTemplate", "Session");

    private final SsmService ssm;

    public SsmDocumentCfnProvisioner(SsmService ssm) {
        this.ssm = ssm;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::SSM::Document");
    }

    @Override
    public Map<String, CfnReplacement> conditionalReplacements(String resourceType, JsonNode properties) {
        CfnReplacement replacement = switch (properties.path("UpdateMethod").asText("Replace")) {
            case "Replace" -> CfnReplacement.ALWAYS;
            case "NewVersion" -> CfnReplacement.NEVER;
            default -> CfnReplacement.CONDITIONAL;
        };
        return Map.of("Content", replacement, "Attachments", replacement, "VersionName", replacement,
                "DocumentFormat", replacement, "TargetType", replacement, "Requires", replacement);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        resource.getAttributes().remove(UPDATE_SNAPSHOT);
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(resource);
        }
        JsonNode resolved = ctx.engine().resolveNodeOmittingNoValue(props);
        requireSupportedProperties(resolved);
        String content = documentContent(resolved);
        String type = resolved.path("DocumentType").asText("Command");
        if (!DOCUMENT_TYPES.contains(type)) {
            throw validation("DocumentType must be a supported SSM document type.");
        }
        String method = resolved.path("UpdateMethod").asText("Replace");
        if (!Set.of("Replace", "NewVersion").contains(method)) {
            throw validation("UpdateMethod must be Replace or NewVersion.");
        }
        String explicitName = resolved.hasNonNull("Name") ? resolved.path("Name").asText() : null;
        if (explicitName != null) {
            requireName(explicitName);
        }
        Map<String, String> before = new HashMap<>(resource.getAttributes());
        boolean priorIsStub = CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), before);
        boolean nameRemoved = explicitName == null && (ctx.previouslyDeclared("Name")
                || Boolean.parseBoolean(before.get(NAME_DECLARED)));
        String name = nameRemoved || priorIsStub
                ? explicitName != null ? explicitName : ctx.generatePhysicalName(resource.getLogicalId(), 128, false)
                : ctx.stablePhysicalName(explicitName, resource.getLogicalId(), 128, false);
        boolean existing = ctx.reusesPriorEntity(name) && !priorIsStub;
        SsmDocument current = existing ? ssm.getDocument(name, ctx.region()) : null;
        boolean contentChanged = existing && !Objects.equals(
                current.getContentForVersion(Long.toString(current.getDefaultVersion())), content);
        boolean replace = existing && (!type.equals(current.getDocumentType())
                || contentChanged && "Replace".equals(method));
        if (replace) {
            if (explicitName != null) {
                throw validation("Cannot replace the custom-named document " + name
                        + " without changing Name. Use UpdateMethod NewVersion for a content update.");
            }
            name = ctx.generatePhysicalName(resource.getLogicalId(), 128, false);
            existing = false;
        }
        if (existing) {
            snapshot(resource, current, before, ctx.region());
            if (contentChanged) {
                SsmDocument updated = Objects.equals(current.getContent(), content)
                        ? current : ssm.updateDocument(name, content, ctx.region());
                ssm.updateDocumentDefaultVersion(name, Long.toString(updated.getDocumentVersion()), ctx.region());
            }
        } else {
            ssm.createDocument(name, content, type, ctx.region());
        }
        resource.setPhysicalId(name);
        resource.getAttributes().put(NAME_DECLARED, Boolean.toString(explicitName != null));
        ReplacementCleanup.record(resource, ctx, before);
    }

    private static void requireSupportedProperties(JsonNode props) {
        if (props == null || !props.isObject()) {
            throw validation("Properties must contain Content.");
        }
        if (props.has("DocumentFormat") && !"JSON".equals(props.path("DocumentFormat").asText())) {
            throw validation("Floci does not yet support DocumentFormat other than JSON for AWS::SSM::Document.");
        }
        for (String property : List.of("Attachments", "Requires", "Tags", "TargetType", "VersionName")) {
            JsonNode value = props.get(property);
            if (value != null && !value.isNull() && !(value.isArray() && value.isEmpty())) {
                throw validation("Floci does not yet support " + property + " for AWS::SSM::Document.");
            }
        }
    }

    private static String documentContent(JsonNode props) {
        JsonNode content = props.get("Content");
        if (content == null || !(content.isObject() || content.isTextual())) {
            throw validation("Content must be a JSON object or a JSON-encoded string.");
        }
        String encoded = content.isTextual() ? content.textValue() : content.toString();
        try {
            if (encoded.isBlank() || MAPPER.readTree(encoded) == null) {
                throw validation("Content must not be empty.");
            }
        } catch (JsonProcessingException error) {
            throw new AwsException("InvalidDocumentContent", "Content is not valid JSON.", 400);
        }
        return encoded;
    }

    private static void requireName(String name) {
        String lowercase = name.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-zA-Z0-9_\\-.]{3,128}") || lowercase.startsWith("aws")
                || lowercase.startsWith("amazon") || lowercase.startsWith("amzn")) {
            throw validation("Name must be an unreserved SSM document name with 3 to 128 characters.");
        }
    }

    private static void snapshot(StackResource resource, SsmDocument current,
                                 Map<String, String> attributes, String region) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("name", current.getName());
        snapshot.put("defaultVersion", Long.toString(current.getDefaultVersion()));
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
        CfnDeletes.safeDelete("SSM document", physicalId,
                () -> ssm.deleteDocument(physicalId, region), "InvalidDocument");
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
            throw new IllegalStateException("Cannot read the SSM document update snapshot.", error);
        }
        ssm.updateDocumentDefaultVersion(snapshot.path("name").asText(),
                snapshot.path("defaultVersion").asText(), snapshot.path("region").asText());
        resource.getAttributes().keySet().removeIf(SsmDocumentCfnProvisioner::restorable);
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
