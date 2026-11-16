package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2PrivateLinkService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpointServiceConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::VPCEndpointService} and
 * {@code AWS::EC2::VPCEndpointServicePermissions}. Every property of both is updated in place, except
 * the permissions' {@code ServiceId}, which replaces; {@code AllowedPrincipals} is the whole set.
 */
@ApplicationScoped
public class Ec2VpcEndpointServiceCfnProvisioner implements CfnResourceProvisioner {

    private static final String SERVICE = "AWS::EC2::VPCEndpointService";
    private static final String PERMISSIONS = "AWS::EC2::VPCEndpointServicePermissions";
    private static final String NOT_FOUND = "InvalidVpcEndpointServiceId.NotFound";
    private static final String DECLARED_TAG_KEYS_ATTR = "__FlociDeclaredTagKeys";

    private final Ec2PrivateLinkService privateLink;
    private final Ec2Service ec2Service;

    @Inject
    public Ec2VpcEndpointServiceCfnProvisioner(Ec2PrivateLinkService privateLink, Ec2Service ec2Service) {
        this.privateLink = privateLink;
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(SERVICE, PERMISSIONS);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case "AWS::EC2::VPCEndpointService" -> provisionService(r, props, ctx);
            case "AWS::EC2::VPCEndpointServicePermissions" -> provisionPermissions(r, props, ctx);
            default -> throw new IllegalStateException("Ec2VpcEndpointServiceCfnProvisioner cannot handle "
                    + r.getResourceType());
        }
    }

    private void provisionService(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        String acceptance = ctx.resolveOptional(props, "AcceptanceRequired");
        Ec2PrivateLinkService.ServiceSpec spec = new Ec2PrivateLinkService.ServiceSpec(
                acceptance == null || acceptance.isBlank() ? null : Boolean.parseBoolean(acceptance),
                ctx.resolveStringList(props, "NetworkLoadBalancerArns"),
                ctx.resolveStringList(props, "GatewayLoadBalancerArns"),
                blankToNull(ctx.resolveOptional(props, "PrivateDnsName")),
                ctx.resolveStringList(props, "SupportedIpAddressTypes"),
                ctx.resolveStringList(props, "SupportedRegions"),
                Ec2Tags.validatedTags(tags), null);
        VpcEndpointServiceConfiguration service;
        if (ctx.isUpdate()) {
            privateLink.update(region, ctx.priorPhysicalId(), spec);
            Ec2Tags.reconcileOwned(ec2Service, region, ctx.priorPhysicalId(),
                    Ec2Tags.ownedKeys(r, DECLARED_TAG_KEYS_ATTR, ctx), tags);
            service = privateLink.describeOne(region, ctx.priorPhysicalId());
        } else {
            service = privateLink.create(region, spec);
        }
        Ec2Tags.recordKeys(r, DECLARED_TAG_KEYS_ATTR, tags);
        r.setPhysicalId(service.getServiceId());
        r.getAttributes().put("ServiceId", service.getServiceId());
        for (String member : List.of("Name", "State", "Type", "Value")) {
            r.getAttributes().remove("PrivateDnsNameConfiguration." + member);
        }
        if (service.getPrivateDnsName() != null) {
            r.getAttributes().put("PrivateDnsNameConfiguration.Name", service.getPrivateDnsVerificationName());
            r.getAttributes().put("PrivateDnsNameConfiguration.State",
                    Ec2PrivateLinkService.privateDnsVerificationState(service));
            r.getAttributes().put("PrivateDnsNameConfiguration.Type", "TXT");
            r.getAttributes().put("PrivateDnsNameConfiguration.Value", service.getPrivateDnsVerificationValue());
        }
    }

    private void provisionPermissions(StackResource r, JsonNode props, ProvisionContext ctx) {
        String serviceId = blankToNull(ctx.resolveOptional(props, "ServiceId"));
        if (serviceId == null) {
            throw new AwsException("ValidationError", "Property ServiceId cannot be empty.", 400);
        }
        String prior = ctx.isUpdate() ? ctx.priorPhysicalId() : null;
        privateLink.replacePermissions(ctx.region(), serviceId, ctx.resolveStringList(props, "AllowedPrincipals"));
        if (prior != null && !prior.equals(serviceId)) {
            revokeAll(prior, ctx.region());
        }
        r.setPhysicalId(serviceId);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (SERVICE.equals(resourceType)) {
            CfnDeletes.safeDelete("VPC endpoint service", physicalId, () -> {
                List<Ec2PrivateLinkService.Unsuccessful> failed = privateLink.delete(region, List.of(physicalId));
                if (!failed.isEmpty()) {
                    throw new AwsException(failed.getFirst().code(), failed.getFirst().message(), 400);
                }
            }, NOT_FOUND);
        } else if (PERMISSIONS.equals(resourceType)) {
            revokeAll(physicalId, region);
        }
    }

    private void revokeAll(String serviceId, String region) {
        CfnDeletes.safeDelete("VPC endpoint service permissions", serviceId,
                () -> privateLink.replacePermissions(region, serviceId, List.of()), NOT_FOUND);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
