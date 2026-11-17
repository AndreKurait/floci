package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ReservedTags;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.kms.model.KmsKey;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Provisions {@code AWS::KMS::Key} and {@code AWS::KMS::Alias}. */
@ApplicationScoped
public class KmsCfnProvisioner implements CfnResourceProvisioner {

    private static final String KEY = "AWS::KMS::Key";
    private static final String ALIAS = "AWS::KMS::Alias";

    private final KmsService kmsService;

    public KmsCfnProvisioner(KmsService kmsService) {
        this.kmsService = kmsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(KEY, ALIAS);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case KEY -> provisionKey(r, props, ctx);
            case ALIAS -> provisionAlias(r, props, ctx);
            default -> throw new IllegalStateException(
                    "KmsCfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    private void provisionKey(StackResource r, JsonNode props, ProvisionContext ctx) {
        String description = ctx.resolveOptional(props, "Description");
        String spec = ctx.resolveOptional(props, "KeySpec");
        String usage = ctx.resolveOptional(props, "KeyUsage");
        spec = spec == null ? "SYMMETRIC_DEFAULT" : spec;
        usage = usage == null ? "ENCRYPT_DECRYPT" : usage;
        boolean multiRegion = Boolean.parseBoolean(ctx.resolveOptional(props, "MultiRegion"));
        JsonNode policyNode = props == null ? null : props.get("KeyPolicy");
        JsonNode resolvedPolicy = ctx.engine().resolveNode(policyNode);
        String policy = resolvedPolicy == null || resolvedPolicy.isNull() ? null
                : resolvedPolicy.isTextual() ? resolvedPolicy.textValue() : resolvedPolicy.toString();
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, ctx);
        KmsKey key;
        if (ctx.isUpdate() && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), r.getAttributes())) {
            key = kmsService.describeKey(ctx.priorPhysicalId(), ctx.region());
            if ("PendingDeletion".equals(key.getKeyState())) {
                throw new AwsException("KMSInvalidStateException", key.getArn() + " is pending deletion.", 400);
            }
            // KMS rejects immutable property changes, even with UpdateReplacePolicy: Retain.
            // Validate before any mutable property is applied or key material is created.
            if (!key.getKeySpec().name().equals(spec) || !key.getKeyUsage().name().equals(usage)
                    || key.isMultiRegion() != multiRegion) {
                throw new AwsException("InvalidRequest", "KeySpec, KeyUsage and MultiRegion cannot be changed.", 400);
            }
            String overrideId = ReservedTags.extractOverrideKeyId(tags);
            if (overrideId != null && !overrideId.equals(key.getKeyId())) {
                throw new AwsException("InvalidRequest", "The key ID override cannot be changed.", 400);
            }
            tags.remove(ReservedTags.OVERRIDE_ID_KEY);
            ReservedTags.rejectReservedTagsOnUpdate(tags);
            if (description != null || ctx.previouslyDeclared("Description")) {
                kmsService.updateKeyDescription(key.getKeyId(), description == null ? "" : description, ctx.region());
            }
            if (policy != null || ctx.previouslyDeclared("KeyPolicy")) {
                kmsService.putKeyPolicy(key.getKeyId(),
                        policy == null ? kmsService.buildDefaultKeyPolicy() : policy, ctx.region());
            }
            if (props != null && props.has("Tags") || ctx.previouslyDeclared("Tags")) {
                kmsService.untagResource(key.getKeyId(), ProvisionContext.staleTagKeys(key.getTags(), tags), ctx.region());
                kmsService.tagResource(key.getKeyId(), tags, ctx.region());
            }
        } else {
            key = kmsService.createKey(description, usage, spec, policy, tags, null, multiRegion, ctx.region());
        }
        String enabled = ctx.resolveOptional(props, "Enabled");
        boolean desiredEnabled = enabled == null || Boolean.parseBoolean(enabled);
        boolean reconcileEnabled = enabled != null || ctx.previouslyDeclared("Enabled");
        if (reconcileEnabled && desiredEnabled != key.isEnabled()) {
            if (desiredEnabled) {
                kmsService.enableKey(key.getKeyId(), ctx.region());
            } else {
                kmsService.disableKey(key.getKeyId(), ctx.region());
            }
        }
        String rotation = ctx.resolveOptional(props, "EnableKeyRotation");
        if (rotation != null || ctx.previouslyDeclared("EnableKeyRotation")) {
            boolean desiredRotation = Boolean.parseBoolean(rotation);
            if (desiredRotation != kmsService.getKeyRotationStatus(key.getKeyId(), ctx.region())) {
                if (desiredRotation) {
                    kmsService.enableKeyRotation(key.getKeyId(), ctx.region());
                } else {
                    kmsService.disableKeyRotation(key.getKeyId(), ctx.region());
                }
            }
        }
        r.setPhysicalId(key.getKeyId());
        r.getAttributes().put("Arn", key.getArn());
        r.getAttributes().put("KeyId", key.getKeyId());
    }

    private void provisionAlias(StackResource r, JsonNode props, ProvisionContext ctx) {
        String aliasName = ctx.resolveOptional(props, "AliasName");
        String targetKeyId = ctx.resolveOptional(props, "TargetKeyId");
        if (aliasName != null && targetKeyId != null) {
            if (ctx.isUpdate() && aliasName.equals(ctx.priorPhysicalId())) {
                kmsService.updateAlias(aliasName, targetKeyId, ctx.region());
            } else {
                kmsService.createAlias(aliasName, targetKeyId, ctx.region());
            }
        }
        r.setPhysicalId(aliasName != null
                ? aliasName
                : "alias/cfn-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // A KMS key cannot be deleted immediately, only scheduled, so a stack delete leaves it.
        if (ALIAS.equals(resourceType)) {
            kmsService.deleteAlias(physicalId, region);
        }
    }

    /**
     * Kept rather than delegating to {@link ProvisionContext#resolveTags}, which is not
     * equivalent: it skips a blank key this keeps and orders entries by insertion rather than hash.
     */
    private Map<String, String> parseCfnTags(JsonNode tagsNode, ProvisionContext ctx) {
        tagsNode = ctx.engine().resolveNode(tagsNode);
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = ctx.engine().resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }
}
