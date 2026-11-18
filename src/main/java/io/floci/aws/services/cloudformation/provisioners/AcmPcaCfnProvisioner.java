package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.acmpca.AcmPcaService;
import io.floci.aws.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::ACMPCA::CertificateAuthority}.
 *
 * <p>The properties become a {@code CreateCertificateAuthority} request, so the authority gets its key pair
 * and a CSR for its subject and waits in {@code PENDING_CERTIFICATE} for its certificate, which a template
 * installs with {@code AWS::ACMPCA::Certificate} and {@code AWS::ACMPCA::CertificateAuthorityActivation} on
 * AWS, or the same API calls made afterwards. {@code Ref} and {@code Arn} are its ARN, and
 * {@code CertificateSigningRequest} is its CSR. {@code Tags} update in place, and so does
 * {@code RevocationConfiguration}, which is checked again through {@code UpdateCertificateAuthority} once the
 * authority is ACTIVE or DISABLED; another value of a create-only property replaces the authority. Deletion disables an ACTIVE
 * authority first: on AWS the resource reference says to disable it before removing the resource
 * ("Otherwise, the action will fail."), which a template does with
 * {@code AWS::ACMPCA::CertificateAuthorityActivation}, a type Floci does not provision.
 */
@ApplicationScoped
public class AcmPcaCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(AcmPcaCfnProvisioner.class);
    private static final String TYPE = "AWS::ACMPCA::CertificateAuthority";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AcmPcaService acmPcaService;
    private final EmulatorConfig config;

    public AcmPcaCfnProvisioner(AcmPcaService acmPcaService, EmulatorConfig config) {
        this.acmPcaService = acmPcaService;
        this.config = config;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public boolean serviceDisabled() {
        return config != null && !config.services().acmPca().enabled();
    }

    @Override
    public String serviceEnabledFlag() {
        return "floci.services.acm-pca.enabled";
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        ObjectNode create = MAPPER.createObjectNode().put("CertificateAuthorityType", required(ctx, props, "Type"));
        ObjectNode configuration = create.putObject("CertificateAuthorityConfiguration")
                .put("KeyAlgorithm", required(ctx, props, "KeyAlgorithm"))
                .put("SigningAlgorithm", required(ctx, props, "SigningAlgorithm"));
        if (!props.has("Subject")) {
            throw new AwsException("ValidationError", TYPE + " requires Subject", 400);
        }
        configuration.set("Subject", ctx.engine().resolveNodeOmittingNoValue(props.get("Subject")));
        if (props.has("CsrExtensions")) {
            configuration.set("CsrExtensions", ctx.engine().resolveNodeOmittingNoValue(props.get("CsrExtensions")));
        }
        putOptional(create, "KeyStorageSecurityStandard", ctx.resolveOptional(props, "KeyStorageSecurityStandard"));
        putOptional(create, "UsageMode", ctx.resolveOptional(props, "UsageMode"));
        JsonNode revocation = props.has("RevocationConfiguration")
                ? ctx.engine().resolveNodeOmittingNoValue(props.get("RevocationConfiguration")) : null;
        if (revocation != null) {
            create.set("RevocationConfiguration", revocation);
        }
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        if (!tags.isEmpty()) {
            create.set("Tags", tagList(tags));
        }

        JsonNode existing = ctx.isUpdate() ? findExisting(ctx.priorPhysicalId(), ctx.region()) : null;
        String arn;
        if (existing != null && !"DELETED".equals(existing.path("Status").asText())
                && sameCreateOnlyProperties(existing, create)) {
            arn = existing.path("Arn").asText();
            reconcileTags(arn, tags, ctx.region());
            if (revocation != null && Set.of("ACTIVE", "DISABLED").contains(existing.path("Status").asText())) {
                ObjectNode update = request(arn);
                update.set("RevocationConfiguration", revocation);
                acmPcaService.update(update, ctx.region());
            }
        } else {
            arn = acmPcaService.create(create, ctx.region()).path("CertificateAuthorityArn").asText();
            if (existing != null) {
                deletePriorOrUnwind(r, existing.path("Arn").asText(), arn, ctx.region());
            }
        }
        r.setPhysicalId(arn);
        r.getAttributes().put("Arn", arn);
        r.getAttributes().put("CertificateSigningRequest", acmPcaService.csr(request(arn), ctx.region()).path("Csr").asText());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("certificate authority", physicalId, () -> {
            String status = acmPcaService.describe(request(physicalId), region)
                    .path("CertificateAuthority").path("Status").asText();
            if ("DELETED".equals(status)) {
                return;
            }
            if ("ACTIVE".equals(status)) {
                acmPcaService.update(request(physicalId).put("Status", "DISABLED"), region);
            }
            acmPcaService.delete(request(physicalId), region);
        }, "ResourceNotFoundException");
    }

    /**
     * Removes the authority a replacement supersedes. If that fails, the update fails and the previous
     * StackResource is restored without the new ARN, so the authority this attempt created is removed here.
     */
    private void deletePriorOrUnwind(StackResource r, String priorArn, String newArn, String region) {
        try {
            delete(r.getResourceType(), priorArn, region);
        } catch (AwsException failure) {
            LOG.warnv("Could not delete certificate authority {0} replaced by {1}, removing the replacement: {2}",
                    priorArn, newArn, failure.getMessage());
            delete(r.getResourceType(), newArn, region);
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
            throw failure;
        }
    }

    private JsonNode findExisting(String arn, String region) {
        if (arn == null) {
            return null;
        }
        try {
            return acmPcaService.describe(request(arn), region).path("CertificateAuthority");
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode()) && !"InvalidArnException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Certificate authority {0} from the previous execution is gone, creating a new one", arn);
            return null;
        }
    }

    /** Type, KeyAlgorithm, SigningAlgorithm, Subject, KeyStorageSecurityStandard and UsageMode, with their defaults. */
    private static boolean sameCreateOnlyProperties(JsonNode existing, ObjectNode desired) {
        JsonNode stored = existing.path("CertificateAuthorityConfiguration");
        JsonNode wanted = desired.path("CertificateAuthorityConfiguration");
        return desired.path("CertificateAuthorityType").asText().equals(existing.path("Type").asText())
                && wanted.path("KeyAlgorithm").asText().equals(stored.path("KeyAlgorithm").asText())
                && wanted.path("SigningAlgorithm").asText().equals(stored.path("SigningAlgorithm").asText())
                && wanted.path("Subject").equals(stored.path("Subject"))
                && desired.path("KeyStorageSecurityStandard").asText("FIPS_140_2_LEVEL_3_OR_HIGHER")
                        .equals(existing.path("KeyStorageSecurityStandard").asText("FIPS_140_2_LEVEL_3_OR_HIGHER"))
                && desired.path("UsageMode").asText("GENERAL_PURPOSE").equals(existing.path("UsageMode").asText());
    }

    private void reconcileTags(String arn, Map<String, String> desired, String region) {
        Map<String, String> current = new LinkedHashMap<>();
        for (JsonNode tag : acmPcaService.listTags(request(arn), region).path("Tags")) {
            current.put(tag.path("Key").asText(), tag.path("Value").asText());
        }
        List<String> stale = ProvisionContext.staleTagKeys(current, desired);
        if (!stale.isEmpty()) {
            ArrayNode keys = MAPPER.createArrayNode();
            stale.forEach(key -> keys.addObject().put("Key", key));
            ObjectNode untag = request(arn);
            untag.set("Tags", keys);
            acmPcaService.untag(untag, region);
        }
        if (!desired.isEmpty() && !desired.equals(current)) {
            ObjectNode tag = request(arn);
            tag.set("Tags", tagList(desired));
            acmPcaService.tag(tag, region);
        }
    }

    private static String required(ProvisionContext ctx, JsonNode props, String name) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires " + name, 400);
        }
        return value;
    }

    /** Blank counts as absent: the engine resolves {@code AWS::NoValue} to an empty string. */
    private static void putOptional(ObjectNode request, String field, String value) {
        if (value != null && !value.isBlank()) {
            request.put(field, value);
        }
    }

    private static ArrayNode tagList(Map<String, String> tags) {
        ArrayNode list = MAPPER.createArrayNode();
        tags.forEach((key, value) -> list.addObject().put("Key", key).put("Value", value));
        return list;
    }

    private static ObjectNode request(String arn) {
        return MAPPER.createObjectNode().put("CertificateAuthorityArn", arn);
    }
}
