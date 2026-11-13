package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.DhcpConfiguration;
import io.github.hectorvent.floci.services.ec2.model.DhcpOptions;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Creates real EC2 DHCP options and keeps their VPC associations through replacement and rollback. */
@ApplicationScoped
public class Ec2DhcpCfnProvisioner implements CfnResourceProvisioner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRIOR_ATTR = "__FlociEc2DhcpPrior";

    private final Ec2Service ec2;

    public Ec2DhcpCfnProvisioner(Ec2Service ec2) {
        this.ec2 = ec2;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::EC2::DHCPOptions", "AWS::EC2::VPCDHCPOptionsAssociation");
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        Map<String, String> before = Map.copyOf(resource.getAttributes());
        switch (resource.getResourceType()) {
            case "AWS::EC2::DHCPOptions" -> provisionOptions(resource, props, ctx);
            case "AWS::EC2::VPCDHCPOptionsAssociation" -> provisionAssociation(resource, props, ctx);
            default -> throw new IllegalArgumentException("Unsupported DHCP resource: " + resource.getResourceType());
        }
        ReplacementCleanup.record(resource, ctx, before);
    }

    private void provisionOptions(StackResource resource, JsonNode props, ProvisionContext ctx) {
        List<DhcpConfiguration> desired = configurations(props, ctx);
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        Ec2Tags.validatedTags(tags);
        boolean applyTags = props.has("Tags") || ctx.previouslyDeclared("Tags");
        DhcpOptions existing = existingOptions(resource, ctx);
        boolean reused = existing != null && configurationMap(existing.getConfigurations()).equals(configurationMap(desired));
        DhcpOptions options = reused ? existing : ec2.createDhcpOptions(ctx.region(), desired);
        String id = options.getDhcpOptionsId();
        resource.setPhysicalId(id);
        resource.getAttributes().put("DhcpOptionsId", id);
        CfnRollback.guarded(resource, ctx, !reused, () -> ec2.deleteDhcpOptions(ctx.region(), id),
                () -> undoInPlace(resource), () -> {
                    if (!applyTags) {
                        return;
                    }
                    if (reused) {
                        Map<String, String> current = Ec2Tags.current(ec2, ctx.region(), id);
                        if (!current.equals(tags)) {
                            ObjectNode snapshot = MAPPER.createObjectNode().put("kind", "tags")
                                    .put("region", ctx.region()).put("id", id);
                            ObjectNode priorTags = snapshot.putObject("tags");
                            current.forEach(priorTags::put);
                            resource.getAttributes().put(PRIOR_ATTR, snapshot.toString());
                        }
                    }
                    Ec2Tags.reconcile(ec2, ctx.region(), id, tags);
                });
    }

    private DhcpOptions existingOptions(StackResource resource, ProvisionContext ctx) {
        if (!ctx.isUpdate() || CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), resource.getAttributes())) {
            return null;
        }
        try {
            return ec2.describeDhcpOptions(ctx.region(), List.of(ctx.priorPhysicalId()), List.of(), 0, null)
                    .items().stream().findFirst().orElse(null);
        } catch (AwsException failure) {
            if ("InvalidDhcpOptionsID.NotFound".equals(failure.getErrorCode())) {
                return null;
            }
            throw failure;
        }
    }

    private static List<DhcpConfiguration> configurations(JsonNode props, ProvisionContext ctx) {
        List<DhcpConfiguration> options = new ArrayList<>();
        addScalar(options, "domain-name", ctx.resolveOptional(props, "DomainName"));
        addList(options, "domain-name-servers", ctx.resolveStringList(props, "DomainNameServers"));
        addList(options, "ntp-servers", ctx.resolveStringList(props, "NtpServers"));
        List<String> netbios = ctx.resolveStringList(props, "NetbiosNameServers");
        addList(options, "netbios-name-servers", netbios);
        String nodeType = ctx.resolveOptional(props, "NetbiosNodeType");
        addScalar(options, "netbios-node-type", nodeType);
        addScalar(options, "ipv6-address-preferred-lease-time",
                ctx.resolveOptional(props, "Ipv6AddressPreferredLeaseTime"));
        if (options.stream().noneMatch(option -> Set.of("domain-name-servers", "ntp-servers", "netbios-name-servers")
                .contains(option.getKey()))) {
            throw new AwsException("ValidationError",
                    "AWS::EC2::DHCPOptions requires DomainNameServers, NtpServers or NetbiosNameServers", 400);
        }
        if (!netbios.isEmpty() && (nodeType == null || nodeType.isBlank())) {
            throw new AwsException("ValidationError", "NetbiosNameServers requires NetbiosNodeType", 400);
        }
        return options;
    }

    private static void addScalar(List<DhcpConfiguration> options, String key, String value) {
        if (value != null && !value.isBlank()) {
            options.add(new DhcpConfiguration(key, List.of(value)));
        }
    }

    private static void addList(List<DhcpConfiguration> options, String key, List<String> values) {
        if (!values.isEmpty()) {
            options.add(new DhcpConfiguration(key, values));
        }
    }

    private static Map<String, List<String>> configurationMap(List<DhcpConfiguration> configurations) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        configurations.forEach(configuration -> result.put(configuration.getKey(), configuration.getValues()));
        return result;
    }

    private void provisionAssociation(StackResource resource, JsonNode props, ProvisionContext ctx) {
        String vpcId = required(props, "VpcId", ctx);
        String optionsId = required(props, "DhcpOptionsId", ctx);
        String previous = ec2.associateDhcpOptionsAndGetPrevious(ctx.region(), optionsId, vpcId);
        if (ctx.isUpdate() && !Objects.equals(previous, optionsId)) {
            ObjectNode snapshot = MAPPER.createObjectNode().put("kind", "association")
                    .put("region", ctx.region()).put("vpc", vpcId)
                    .put("expected", optionsId).put("previous", previous);
            resource.getAttributes().put(PRIOR_ATTR, snapshot.toString());
        }
        // The schema's primary identifier is the DHCP options/VPC pair. Keeping the options ID
        // first also lets replacement cleanup retire an old association before its old options.
        resource.setPhysicalId(optionsId + "|" + vpcId);
    }

    private static String required(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError",
                    "Property " + name + " is required for AWS::EC2::VPCDHCPOptionsAssociation", 400);
        }
        return value;
    }

    @Override
    public void delete(String type, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        if ("AWS::EC2::DHCPOptions".equals(type)) {
            CfnDeletes.safeDelete("DHCP options", physicalId,
                    () -> ec2.deleteDhcpOptions(region, physicalId), "InvalidDhcpOptionsID.NotFound");
        } else if ("AWS::EC2::VPCDHCPOptionsAssociation".equals(type)) {
            String[] pair = physicalId.split("\\|", -1);
            if (pair.length == 2) {
                CfnDeletes.safeDelete("VPC DHCP association", physicalId,
                        () -> ec2.associateDhcpOptionsIfCurrent(region, "default", pair[1], pair[0]),
                        "InvalidVpcID.NotFound");
            }
        }
    }

    private void undoInPlace(StackResource resource) {
        String encoded = resource.getAttributes().get(PRIOR_ATTR);
        if (encoded == null) {
            return;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(encoded);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unreadable DHCP rollback snapshot", failure);
        }
        String region = snapshot.path("region").asText();
        if ("tags".equals(snapshot.path("kind").asText())) {
            Map<String, String> tags = new LinkedHashMap<>();
            snapshot.path("tags").fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
            Ec2Tags.reconcile(ec2, region, snapshot.path("id").asText(), tags);
        } else if ("association".equals(snapshot.path("kind").asText())) {
            String vpc = snapshot.path("vpc").asText();
            String previous = snapshot.path("previous").asText();
            boolean restored = ec2.associateDhcpOptionsIfCurrent(region, previous, vpc,
                    snapshot.path("expected").asText());
            if (!restored && !previous.equals(ec2.requireVpc(region, vpc).getDhcpOptionsId())) {
                throw new IllegalStateException("The DHCP association changed before rollback for " + vpc);
            }
        } else {
            throw new IllegalStateException("Unknown DHCP rollback snapshot kind");
        }
        resource.getAttributes().remove(PRIOR_ATTR);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        undoInPlace(resource);
        ReplacementCleanup.rollback(resource, this::delete);
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
        resource.getAttributes().remove(PRIOR_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(PRIOR_ATTR);
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean requiresUpdateRecovery(StackResource resource) {
        return resource.getAttributes().containsKey(PRIOR_ATTR);
    }

    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        String snapshot = attempted.getAttributes().get(PRIOR_ATTR);
        if (snapshot != null) {
            previous.getAttributes().putIfAbsent(PRIOR_ATTR, snapshot);
        }
    }
}
