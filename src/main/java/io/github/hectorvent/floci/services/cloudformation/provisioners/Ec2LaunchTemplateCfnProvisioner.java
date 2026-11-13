package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.Ec2UserDataDecoder;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::LaunchTemplate} (issue #1971).
 */
@ApplicationScoped
public class Ec2LaunchTemplateCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(Ec2LaunchTemplateCfnProvisioner.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .build();

    private final Ec2Service ec2Service;

    @Inject
    public Ec2LaunchTemplateCfnProvisioner(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::EC2::LaunchTemplate");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String previousId = r.getPhysicalId();
        LaunchTemplate existing = previousId == null ? null : findExisting(ctx.region(), previousId);
        String declaredName = ctx.resolveOptional(props, "LaunchTemplateName");
        String name;
        if (declaredName != null && !declaredName.isBlank()) {
            name = declaredName;
        } else if (existing != null) {
            // Keep the name generated at create time; generating a fresh one on every update is
            // what made an unnamed template multiply.
            name = existing.getLaunchTemplateName();
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), 128, false);
        }
        LaunchTemplateData data = launchTemplateData(props, ctx);
        // UpdateStack re-provisions every resource. Creating unconditionally meant an explicit
        // LaunchTemplateName hit InvalidLaunchTemplateName.AlreadyExistsException, while an
        // omitted one minted a second randomly named template and orphaned the first. A template
        // whose name has not changed is updated in place by publishing a new version, which is how
        // AWS::EC2::LaunchTemplate behaves when only its LaunchTemplateData changes.
        LaunchTemplate lt;
        if (existing != null && name.equals(existing.getLaunchTemplateName())) {
            String priorLatest = existing.getLatestVersionNumber();
            String priorDefault = existing.getDefaultVersionNumber();
            lt = ec2Service.createLaunchTemplateVersion(ctx.region(), previousId, null, null, data);
            LaunchTemplateSnapshot snapshot = new LaunchTemplateSnapshot(
                    ctx.region(), lt.getLaunchTemplateId(), lt.getLatestVersionNumber(), priorLatest, priorDefault);
            try {
                r.getAttributes().put(CfnRollback.LAUNCH_TEMPLATE_UPDATE_SNAPSHOT_ATTR, MAPPER.writeValueAsString(snapshot));
            } catch (JsonProcessingException e) {
                LOG.warnv("Could not serialize Launch Template update snapshot for {0}: {1}",
                        r.getLogicalId(), e.getMessage());
            }
        } else {
            lt = ec2Service.createLaunchTemplate(ctx.region(), name, data, null);
            // A changed name is a replacement: drop the template the previous execution created,
            // once the new one exists, so the old one is not left behind.
            if (existing != null) {
                try {
                    ec2Service.deleteLaunchTemplate(ctx.region(), previousId, null);
                } catch (RuntimeException ignored) {
                    // already gone — nothing to clean up
                }
            }
        }
        r.setPhysicalId(lt.getLaunchTemplateId());
        r.getAttributes().put("LaunchTemplateId", lt.getLaunchTemplateId());
        r.getAttributes().put("LatestVersionNumber", lt.getLatestVersionNumber());
        r.getAttributes().put("DefaultVersionNumber", lt.getDefaultVersionNumber());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        ec2Service.deleteLaunchTemplate(region, physicalId, null);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String rawSnapshot = resource.getAttributes().remove(CfnRollback.LAUNCH_TEMPLATE_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot != null) {
            try {
                LaunchTemplateSnapshot snapshot = MAPPER.readValue(rawSnapshot, LaunchTemplateSnapshot.class);
                if (snapshot.createdVersion() != null) {
                    ec2Service.deleteLaunchTemplateVersion(snapshot.region(), snapshot.launchTemplateId(), snapshot.createdVersion());
                }
                resource.getAttributes().put("LatestVersionNumber", snapshot.priorLatestVersion());
                resource.getAttributes().put("DefaultVersionNumber", snapshot.priorDefaultVersion());
                return true;
            } catch (Exception e) {
                LOG.errorv("Could not restore Launch Template update snapshot for {0}: {1}",
                        resource.getLogicalId(), e.getMessage());
                return false;
            }
        }
        return false;
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.LAUNCH_TEMPLATE_UPDATE_SNAPSHOT_ATTR);
    }

    public record LaunchTemplateSnapshot(
            String region,
            String launchTemplateId,
            String createdVersion,
            String priorLatestVersion,
            String priorDefaultVersion
    ) {}

    /** The template a previous execution created, or null when it is gone. */
    private LaunchTemplate findExisting(String region, String launchTemplateId) {
        try {
            return ec2Service.describeLaunchTemplates(region, List.of(launchTemplateId), List.of(), Map.of())
                    .stream().findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LaunchTemplateData.CapacityReservationSpecification capacityReservationSpecification(
            JsonNode raw, CloudFormationTemplateEngine engine) {
        CfnPropertyTypes.Reader types = new CfnPropertyTypes.Reader(engine);
        String shape = CfnPropertyTypes.memberShape(
                CfnPropertyTypes.propertyShape("AWS::EC2::LaunchTemplate", "LaunchTemplateData"),
                "CapacityReservationSpecification");
        JsonNode block = types.read("LaunchTemplateData/CapacityReservationSpecification", raw, shape);
        types.finish();
        if (block == null || block.isEmpty()) {
            return null;
        }
        LaunchTemplateData.CapacityReservationSpecification specification =
                new LaunchTemplateData.CapacityReservationSpecification();
        specification.setCapacityReservationPreference(block.path("CapacityReservationPreference").asText(null));
        JsonNode targetNode = block.path("CapacityReservationTarget");
        if (targetNode.isObject()) {
            LaunchTemplateData.CapacityReservationTarget target = new LaunchTemplateData.CapacityReservationTarget();
            target.setCapacityReservationId(targetNode.path("CapacityReservationId").asText(null));
            target.setCapacityReservationResourceGroupArn(
                    targetNode.path("CapacityReservationResourceGroupArn").asText(null));
            specification.setCapacityReservationTarget(target);
        }
        return specification;
    }

    private LaunchTemplateData launchTemplateData(JsonNode props, ProvisionContext ctx) {
        JsonNode node = props == null ? null : ctx.engine().resolveNodeOmittingNoValue(props.get("LaunchTemplateData"));
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new LaunchTemplateData();
        }
        try {
            JsonNode written = ctx.engine().omitNoValue(props.get("LaunchTemplateData"));
            JsonNode metadataOptions = written != null && written.isObject()
                    && !CloudFormationTemplateEngine.callsIntrinsic(written) ? written.get("MetadataOptions") : null;
            LaunchTemplateData.MetadataOptions metadata = Ec2CfnMetadataOptions.read(
                    "LaunchTemplateData/MetadataOptions", metadataOptions,
                    Ec2CfnMetadataOptions.LAUNCH_TEMPLATE_SHAPE, ctx.engine());
            JsonNode dataNode = node.deepCopy();
            if (dataNode instanceof ObjectNode object) {
                object.remove("MetadataOptions");
                object.remove("CapacityReservationSpecification");
            }
            LaunchTemplateData data = MAPPER.treeToValue(dataNode, LaunchTemplateData.class);
            data.setMetadataOptions(metadata);
            JsonNode capacity = written != null && written.isObject()
                    && !CloudFormationTemplateEngine.callsIntrinsic(written)
                    ? written.get("CapacityReservationSpecification") : null;
            data.setCapacityReservationSpecification(capacityReservationSpecification(capacity, ctx.engine()));
            String encodedUserData = node.path("UserData").asText(null);
            data.setEncodedUserData(encodedUserData);
            data.setUserData(Ec2UserDataDecoder.decode(encodedUserData));
            return data;
        } catch (JsonProcessingException e) {
            throw new AwsException("ValidationError", "LaunchTemplateData has invalid properties: "
                    + e.getOriginalMessage(), 400);
        }
    }
}
