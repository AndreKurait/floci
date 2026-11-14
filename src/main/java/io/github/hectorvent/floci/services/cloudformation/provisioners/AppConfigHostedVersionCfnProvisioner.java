package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appconfig.AppConfigService;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersion;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** Immutable hosted versions retain their full identity while Ref returns only the version number. */
@ApplicationScoped
public class AppConfigHostedVersionCfnProvisioner implements CfnResourceProvisioner {
    private static final String TYPE = "AWS::AppConfig::HostedConfigurationVersion";
    private static final String PROPERTIES_ATTR = CfnRollback.USES_ATTR_PREFIX + "AppConfigHostedVersionProperties";

    private final AppConfigService service;

    public AppConfigHostedVersionCfnProvisioner(AppConfigService service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public String referenceValue(String physicalId) {
        String[] parts = physicalId.split("\\|", -1);
        // An older stack can still carry a dispatcher stub until it replaces this resource.
        return parts.length == 3 ? parts[2] : physicalId;
    }

    @Override
    public String publicPhysicalId(String physicalId) {
        return physicalId == null ? null : referenceValue(physicalId);
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(resource);
        }
        JsonNode resolved = ctx.engine().resolveNode(properties);
        if (resolved == null || !resolved.isObject()) {
            throw new AwsException("ValidationError", TYPE + " requires Properties.", 400);
        }
        String app = requiredText(resolved, "ApplicationId", false);
        String profile = requiredText(resolved, "ConfigurationProfileId", false);
        String content = requiredText(resolved, "Content", true);
        String contentType = requiredText(resolved, "ContentType", false);
        String description = resolved.hasNonNull("Description") ? requiredText(resolved, "Description", true) : null;
        String label = resolved.hasNonNull("VersionLabel") ? requiredText(resolved, "VersionLabel", true) : null;
        Integer latest = optionalInteger(resolved, "LatestVersionNumber");
        if (contentType.length() > 255 || (description != null && description.length() > 1024)) {
            throw new AwsException("ValidationError", TYPE + " ContentType or Description exceeds its length limit.", 400);
        }
        if (ctx.isUpdate() && resolved.toString().equals(resource.getAttributes().get(PROPERTIES_ATTR))
                && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), resource.getAttributes())) {
            return;
        }
        Map<String, String> before = Map.copyOf(resource.getAttributes());
        // Empty CFN labels create unlabeled versions; keep the original properties for replacement detection.
        HostedConfigurationVersion version = service.createHostedConfigurationVersion(
                app, profile, content.getBytes(StandardCharsets.UTF_8), contentType, description,
                label == null || label.isEmpty() ? null : label, latest);
        resource.setPhysicalId(version.getApplicationId() + "|" + version.getConfigurationProfileId()
                + "|" + version.getVersionNumber());
        resource.getAttributes().put("VersionNumber", Integer.toString(version.getVersionNumber()));
        resource.getAttributes().put(PROPERTIES_ATTR, resolved.toString());
        ReplacementCleanup.record(resource, ctx, before);
    }

    private static String requiredText(JsonNode properties, String name, boolean allowEmpty) {
        JsonNode value = properties.get(name);
        if (value == null || !value.isTextual() || (!allowEmpty && value.asText().isEmpty())) {
            throw new AwsException("ValidationError", TYPE + " requires a string " + name + ".", 400);
        }
        return value.asText();
    }

    private static Integer optionalInteger(JsonNode properties, String name) {
        JsonNode value = properties.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        if ((!value.isIntegralNumber() && !value.isTextual()) || !text.matches("-?\\d+")) {
            throw new AwsException("ValidationError", TYPE + " requires an integer " + name + ".", 400);
        }
        BigInteger number = new BigInteger(text);
        if (number.bitLength() > 31) {
            throw new AwsException("ValidationError", TYPE + " " + name + " exceeds the signed 32-bit integer range.", 400);
        }
        return number.intValue();
    }

    @Override
    public void delete(StackResource resource, String region) {
        if (!CfnResourceDispatcher.isStub(resource.getPhysicalId(), resource.getAttributes())) {
            delete(resource.getResourceType(), resource.getPhysicalId(), region);
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        String[] parts = physicalId.split("\\|", -1);
        if (parts.length != 3) {
            throw new IllegalStateException("Invalid hosted configuration version identity " + physicalId);
        }
        int version = Integer.parseInt(parts[2]);
        CfnDeletes.safeDelete(TYPE, physicalId,
                () -> service.deleteHostedConfigurationVersion(parts[0], parts[1], version),
                "ResourceNotFoundException");
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
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        ReplacementCleanup.rollback(resource, this::delete);
        return true;
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }
}
