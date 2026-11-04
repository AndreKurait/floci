package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elb.model.ClassicHealthCheck;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancerAttributes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** CloudFormation lifecycle for a Classic load balancer, through the Classic service. */
@ApplicationScoped
public class ElbClassicCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::ElasticLoadBalancing::LoadBalancer";
    private static final String SNAPSHOT = "__FlociClassicLoadBalancerUpdateSnapshot";
    private static final String USED_SUBNET = CfnRollback.USES_ATTR_PREFIX + "Subnet.";
    private static final String USED_GROUP = CfnRollback.USES_ATTR_PREFIX + "SecurityGroup.";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ElbClassicService service;

    @Inject
    public ElbClassicCfnProvisioner(ElbClassicService service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public Map<String, Set<String>> jsonAttributes() {
        return Map.of(TYPE, Set.of("SourceSecurityGroup"));
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        JsonNode props = ctx.engine().resolveNodeOmittingNoValue(properties);
        if (props == null || !props.isObject()) {
            throw new AwsException("ValidationError", "Classic load-balancer Properties must be an object.", 400);
        }
        rejectUnsupportedPolicies(props);
        List<ClassicListener> listeners = listeners(props);
        String explicitName = ctx.resolveOptional(props, "LoadBalancerName");
        String name = ctx.stablePhysicalName(explicitName, resource.getLogicalId(), 32, false);
        String scheme = ctx.resolveOrDefault(props, "Scheme", "internet-facing");
        ClassicLoadBalancer prior = ctx.isUpdate() && service.hasLoadBalancer(ctx.region(), ctx.priorPhysicalId())
                ? service.requireLoadBalancer(ctx.region(), ctx.priorPhysicalId()) : null;
        if (prior != null && replacementRequired(prior, props, scheme, ctx)
                && name.equals(ctx.priorPhysicalId())) {
            if (explicitName != null) {
                throw new AwsException("ValidationError",
                        "A custom-named Classic load balancer requires a new name for replacement.", 400);
            }
            name = ctx.generatePhysicalName(resource.getLogicalId(), 32, false);
        }

        Map<String, String> before = Map.copyOf(resource.getAttributes());
        boolean created = prior == null || !name.equals(ctx.priorPhysicalId());
        String physicalName = name;
        if (created) {
            ClassicLoadBalancer loadBalancer = service.createLoadBalancer(ctx.region(), name, listeners,
                    ctx.resolveStringList(props, "AvailabilityZones"), ctx.resolveStringList(props, "Subnets"),
                    ctx.resolveStringList(props, "SecurityGroups"), scheme, ctx.resolveTags(props, "Tags"));
            resource.setPhysicalId(loadBalancer.getLoadBalancerName());
        } else {
            resource.getAttributes().put(SNAPSHOT, snapshot(prior, ctx.region()).toString());
        }
        CfnRollback.guarded(resource, ctx, created,
                () -> service.deleteLoadBalancer(ctx.region(), physicalName),
                () -> restore(resource),
                () -> {
                    apply(physicalName, props, listeners, ctx, created);
                    attributes(resource, service.requireLoadBalancer(ctx.region(), physicalName));
                });
        ReplacementCleanup.record(resource, ctx, before);
    }

    private static boolean replacementRequired(ClassicLoadBalancer prior, JsonNode props, String scheme,
                                                ProvisionContext ctx) {
        if (!Objects.equals(prior.getScheme(), scheme)) {
            return true;
        }
        for (String property : List.of("Subnets", "AvailabilityZones", "HealthCheck")) {
            boolean present = props.hasNonNull(property) && !props.path(property).isEmpty();
            boolean previouslyPresent = ctx.previouslyDeclared(property)
                    && !ctx.priorProperties().path(property).isEmpty();
            if (present != previouslyPresent) {
                return true;
            }
        }
        return false;
    }

    private static void rejectUnsupportedPolicies(JsonNode props) {
        for (String property : List.of("Policies", "AppCookieStickinessPolicy", "LBCookieStickinessPolicy")) {
            if (props.hasNonNull(property) && !props.path(property).isEmpty()) {
                throw new AwsException("UnsupportedOperation",
                        "Classic load-balancer property " + property + " is not supported by Floci.", 400);
            }
        }
        for (JsonNode listener : props.path("Listeners")) {
            if (listener.hasNonNull("PolicyNames") && !listener.path("PolicyNames").isEmpty()) {
                throw new AwsException("UnsupportedOperation",
                        "Classic listener PolicyNames are not supported by Floci.", 400);
            }
        }
    }

    private static List<ClassicListener> listeners(JsonNode props) {
        JsonNode values = props.path("Listeners");
        if (!values.isArray() || values.isEmpty()) {
            throw new AwsException("ValidationError", "At least one Classic listener is required.", 400);
        }
        List<ClassicListener> result = new ArrayList<>();
        for (JsonNode value : values) {
            ClassicListener listener = new ClassicListener();
            listener.setProtocol(required(value, "Protocol"));
            listener.setLoadBalancerPort(integer(value, "LoadBalancerPort", true));
            listener.setInstancePort(integer(value, "InstancePort", true));
            listener.setInstanceProtocol(text(value, "InstanceProtocol"));
            listener.setSslCertificateId(text(value, "SSLCertificateId"));
            if (result.stream().anyMatch(existing ->
                    existing.getLoadBalancerPort().equals(listener.getLoadBalancerPort()))) {
                throw new AwsException("ValidationError", "Only one Classic listener is allowed per port.", 400);
            }
            result.add(listener);
        }
        return result;
    }

    private void apply(String name, JsonNode props, List<ClassicListener> listeners,
                       ProvisionContext ctx, boolean created) {
        String region = ctx.region();
        Set<String> dropped = ctx.droppedProperties(props);
        if (!created) {
            reconcileListeners(region, name, listeners);
            if (props.hasNonNull("Subnets")) {
                List<String> wanted = ctx.resolveStringList(props, "Subnets");
                List<String> current = List.copyOf(service.requireLoadBalancer(region, name).getSubnets());
                List<String> added = difference(wanted, current);
                List<String> removed = difference(current, wanted);
                if (!added.isEmpty()) {
                    service.attachToSubnets(region, name, added);
                }
                if (!removed.isEmpty()) {
                    service.detachFromSubnets(region, name, removed);
                }
            } else if (props.hasNonNull("AvailabilityZones")) {
                List<String> wanted = ctx.resolveStringList(props, "AvailabilityZones");
                List<String> current = List.copyOf(service.requireLoadBalancer(region, name).getAvailabilityZones());
                List<String> added = difference(wanted, current);
                List<String> removed = difference(current, wanted);
                if (!added.isEmpty()) {
                    service.enableAvailabilityZones(region, name, added);
                }
                if (!removed.isEmpty()) {
                    service.disableAvailabilityZones(region, name, removed);
                }
            }
            if (props.hasNonNull("SecurityGroups")) {
                service.applySecurityGroups(region, name, ctx.resolveStringList(props, "SecurityGroups"));
            } else if (dropped.contains("SecurityGroups")) {
                throw new AwsException("UnsupportedOperation",
                        "Removing Classic SecurityGroups from an existing template is not supported by Floci.", 400);
            }
        }
        if (props.hasNonNull("HealthCheck")) {
            JsonNode value = props.path("HealthCheck");
            ClassicHealthCheck health = new ClassicHealthCheck();
            health.setTarget(required(value, "Target"));
            health.setInterval(integer(value, "Interval", true));
            health.setTimeout(integer(value, "Timeout", true));
            health.setHealthyThreshold(integer(value, "HealthyThreshold", true));
            health.setUnhealthyThreshold(integer(value, "UnhealthyThreshold", true));
            service.configureHealthCheck(region, name, health);
        }
        JsonNode logging = props.path("AccessLoggingPolicy");
        JsonNode draining = props.path("ConnectionDrainingPolicy");
        JsonNode connections = props.path("ConnectionSettings");
        ClassicLoadBalancerAttributes defaults = new ClassicLoadBalancerAttributes();
        service.modifyLoadBalancerAttributes(region, name, new ElbClassicService.AttributeUpdate(
                bool(props, "CrossZone", dropped.contains("CrossZone") ? false : null),
                bool(logging, "Enabled", dropped.contains("AccessLoggingPolicy") ? false : null),
                text(logging, "S3BucketName"), text(logging, "S3BucketPrefix"),
                integer(logging, "EmitInterval", false),
                bool(draining, "Enabled", dropped.contains("ConnectionDrainingPolicy") ? false : null),
                draining.hasNonNull("Timeout") ? integer(draining, "Timeout", true)
                        : dropped.contains("ConnectionDrainingPolicy") ? defaults.getConnectionDrainingTimeout() : null,
                connections.hasNonNull("IdleTimeout") ? integer(connections, "IdleTimeout", true)
                        : dropped.contains("ConnectionSettings") ? defaults.getIdleTimeout() : null,
                Map.of()));
        if (props.hasNonNull("Instances") || dropped.contains("Instances")) {
            List<String> wanted = ctx.resolveStringList(props, "Instances");
            List<String> current = List.copyOf(service.requireLoadBalancer(region, name).getInstanceIds());
            List<String> added = difference(wanted, current);
            List<String> removed = difference(current, wanted);
            if (!added.isEmpty()) {
                service.registerInstances(region, name, added);
            }
            if (!removed.isEmpty()) {
                service.deregisterInstances(region, name, removed);
            }
        }
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        List<String> stale = ProvisionContext.staleTagKeys(service.requireLoadBalancer(region, name).getTags(), tags);
        if (!stale.isEmpty()) {
            service.removeTags(region, List.of(name), stale);
        }
        if (!tags.isEmpty()) {
            service.addTags(region, List.of(name), tags);
        }
    }

    private void reconcileListeners(String region, String name, List<ClassicListener> wanted) {
        List<ClassicListener> current = List.copyOf(service.requireLoadBalancer(region, name).getListeners());
        List<Integer> removed = current.stream().filter(old -> wanted.stream()
                        .noneMatch(value -> MAPPER.valueToTree(value).equals(MAPPER.valueToTree(old))))
                .map(ClassicListener::getLoadBalancerPort).toList();
        List<ClassicListener> added = wanted.stream().filter(value -> current.stream()
                .noneMatch(old -> MAPPER.valueToTree(value).equals(MAPPER.valueToTree(old)))).toList();
        if (!removed.isEmpty()) {
            service.deleteLoadBalancerListeners(region, name, removed);
        }
        if (!added.isEmpty()) {
            service.createLoadBalancerListeners(region, name, added);
        }
    }

    private static List<String> difference(List<String> first, List<String> second) {
        return first.stream().filter(value -> !second.contains(value)).toList();
    }

    private static ObjectNode snapshot(ClassicLoadBalancer value, String region) {
        ObjectNode result = MAPPER.createObjectNode().put("region", region).put("name", value.getLoadBalancerName());
        ObjectNode configuration = result.putObject("configuration");
        configuration.set("listeners", MAPPER.valueToTree(value.getListeners()));
        configuration.set("subnets", MAPPER.valueToTree(value.getSubnets()));
        configuration.set("availabilityZones", MAPPER.valueToTree(value.getAvailabilityZones()));
        configuration.set("securityGroups", MAPPER.valueToTree(value.getSecurityGroups()));
        configuration.set("instanceIds", MAPPER.valueToTree(value.getInstanceIds()));
        configuration.set("healthCheck", MAPPER.valueToTree(value.getHealthCheck()));
        configuration.set("attributes", MAPPER.valueToTree(value.getAttributes()));
        configuration.set("tags", MAPPER.valueToTree(value.getTags()));
        return result;
    }

    private boolean restore(StackResource resource) {
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, SNAPSHOT);
        if (snapshot == null) {
            return true;
        }
        service.restoreLoadBalancerConfiguration(snapshot.path("region").asText(), snapshot.path("name").asText(),
                MAPPER.convertValue(snapshot.path("configuration"), ClassicLoadBalancer.class));
        attributes(resource, service.requireLoadBalancer(snapshot.path("region").asText(), snapshot.path("name").asText()));
        resource.getAttributes().remove(SNAPSHOT);
        return true;
    }

    private static void attributes(StackResource resource, ClassicLoadBalancer value) {
        resource.getAttributes().put("DNSName", value.getDnsName());
        resource.getAttributes().put("CanonicalHostedZoneName", value.getCanonicalHostedZoneName());
        resource.getAttributes().put("CanonicalHostedZoneNameID", value.getCanonicalHostedZoneNameId());
        Map<String, String> sourceGroup = new LinkedHashMap<>();
        if (value.getSourceSecurityGroupName() != null) {
            sourceGroup.put("GroupName", value.getSourceSecurityGroupName());
            sourceGroup.put("OwnerAlias", value.getSourceSecurityGroupOwnerAlias());
            resource.getAttributes().put("SourceSecurityGroup.GroupName", value.getSourceSecurityGroupName());
            resource.getAttributes().put("SourceSecurityGroup.OwnerAlias", value.getSourceSecurityGroupOwnerAlias());
        }
        resource.getAttributes().put("SourceSecurityGroup", MAPPER.valueToTree(sourceGroup).toString());
        recordUses(resource, USED_SUBNET, value.getSubnets());
        recordUses(resource, USED_GROUP, value.getSecurityGroups());
    }

    private static void recordUses(StackResource resource, String prefix, List<String> values) {
        resource.getAttributes().keySet().removeIf(key -> key.startsWith(prefix));
        for (int index = 0; index < values.size(); index++) {
            resource.getAttributes().put(prefix + index, values.get(index));
        }
    }

    private static String text(JsonNode props, String key) {
        return props.hasNonNull(key) ? props.path(key).asText() : null;
    }

    private static String required(JsonNode props, String key) {
        String value = text(props, key);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", key + " is required.", 400);
        }
        return value;
    }

    private static Integer integer(JsonNode props, String key, boolean required) {
        String value = required ? required(props, key) : text(props, key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException failure) {
            throw new AwsException("ValidationError", key + " must be an integer.", 400);
        }
    }

    private static Boolean bool(JsonNode props, String key, Boolean absent) {
        String value = text(props, key);
        if (value == null) {
            return absent;
        }
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new AwsException("ValidationError", key + " must be a boolean.", 400);
        }
        return Boolean.valueOf(value);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId != null && !physicalId.isBlank()) {
            service.deleteLoadBalancer(region, physicalId);
        }
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
        if ("UPDATE_COMPLETE".equals(resource.getStatus())) {
            resource.getAttributes().remove(SNAPSHOT);
        }
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete) || restore(resource);
    }

    @Override
    public boolean requiresUpdateRecovery(StackResource resource) {
        return "UPDATE_FAILED".equals(resource.getStatus()) && resource.getAttributes().containsKey(SNAPSHOT);
    }

    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        String snapshot = attempted.getAttributes().get(SNAPSHOT);
        if (snapshot != null) {
            previous.getAttributes().put(SNAPSHOT, snapshot);
        }
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(SNAPSHOT);
        ReplacementCleanup.clear(resource);
    }
}
