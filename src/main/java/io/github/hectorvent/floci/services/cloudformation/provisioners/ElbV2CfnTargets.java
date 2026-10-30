package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** CloudFormation owns its declared registrations, independently of targets added by another caller. */
final class ElbV2CfnTargets {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TARGETS = CfnPropertyTypes.propertyShape(
            "AWS::ElasticLoadBalancingV2::TargetGroup", "Targets");
    private static final String MANAGED = "__FlociTargetGroupManagedTargets";
    private static final TypeReference<List<TargetDescription>> TARGET_LIST = new TypeReference<>() {};
    private final ElbV2Service service;

    ElbV2CfnTargets(ElbV2Service service) {
        this.service = service;
    }

    static List<TargetDescription> declared(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("Targets")) {
            return List.of();
        }
        CfnPropertyTypes.Reader types = new CfnPropertyTypes.Reader(ctx.engine());
        JsonNode nodes = types.read("Targets", props.get("Targets"), TARGETS);
        types.finish();
        List<TargetDescription> result = new ArrayList<>();
        if (nodes == null || !nodes.isArray()) {
            return result;
        }
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            if (!node.hasNonNull("Id")) {
                throw new AwsException("InvalidRequest",
                        "Model validation failed (#/Targets/" + index + ": required key [Id] not found)", 400);
            }
            TargetDescription target = new TargetDescription();
            target.setId(node.path("Id").asText());
            target.setPort(node.hasNonNull("Port") ? node.path("Port").intValue() : null);
            target.setAvailabilityZone(node.path("AvailabilityZone").asText(null));
            result.add(target);
        }
        return result;
    }

    void apply(StackResource resource, TargetGroup group, List<TargetDescription> requested,
               ProvisionContext ctx, boolean created) {
        List<TargetDescription> desired = normalize(requested, group);
        List<TargetDescription> previous = ctx.reusesPriorEntity(group.getTargetGroupArn())
                ? read(CfnRollback.updateSnapshot(resource, MANAGED)) : List.of();
        if (desired.isEmpty() && previous.isEmpty()) {
            storeManaged(resource, desired);
            return;
        }
        if (!created) {
            List<TargetDescription> affected = new ArrayList<>(previous);
            affected.addAll(desired);
            Set<Key> keys = keys(affected);
            ObjectNode snapshot = MAPPER.createObjectNode();
            snapshot.set("scope", MAPPER.valueToTree(affected));
            snapshot.set("targets", MAPPER.valueToTree(normalize(group.getTargets(), group).stream()
                    .filter(target -> keys.contains(key(target))).toList()));
            snapshot.set("managed", MAPPER.valueToTree(previous));
            resource.getAttributes().put(CfnRollback.TARGET_GROUP_TARGETS_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
        }
        CfnRollback.guarded(resource, ctx, created,
                () -> service.deleteTargetGroup(ctx.region(), group.getTargetGroupArn()),
                () -> restore(resource),
                () -> reconcile(ctx.region(), group.getTargetGroupArn(), desired, previous));
        storeManaged(resource, desired);
        if (!ctx.isUpdate()) {
            resource.getAttributes().remove(CfnRollback.TARGET_GROUP_TARGETS_UPDATE_SNAPSHOT_ATTR);
        }
    }

    boolean restore(StackResource resource) {
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, CfnRollback.TARGET_GROUP_TARGETS_UPDATE_SNAPSHOT_ATTR);
        if (snapshot == null) {
            return true;
        }
        String arn = resource.getPhysicalId();
        reconcile(AwsArnUtils.parse(arn).region(), arn, read(snapshot.get("targets")), read(snapshot.get("scope")));
        storeManaged(resource, read(snapshot.get("managed")));
        resource.getAttributes().remove(CfnRollback.TARGET_GROUP_TARGETS_UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    private void reconcile(String region, String arn, List<TargetDescription> desired, List<TargetDescription> previous) {
        if (!desired.isEmpty()) {
            service.registerTargets(region, arn, desired);
        }
        Set<Key> retained = keys(desired);
        List<TargetDescription> removed = previous.stream().filter(target -> !retained.contains(key(target))).toList();
        if (!removed.isEmpty()) {
            service.deregisterTargets(region, arn, removed);
        }
    }

    private static List<TargetDescription> normalize(List<TargetDescription> targets, TargetGroup group) {
        return targets.stream().map(target -> {
            TargetDescription copy = new TargetDescription();
            copy.setId(target.getId());
            copy.setPort(target.getPort() == null && !"lambda".equals(group.getTargetType())
                    ? group.getPort() : target.getPort());
            copy.setAvailabilityZone(target.getAvailabilityZone());
            return copy;
        }).toList();
    }

    private static List<TargetDescription> read(JsonNode node) {
        return node == null ? List.of() : MAPPER.convertValue(node, TARGET_LIST);
    }

    private static void storeManaged(StackResource resource, List<TargetDescription> targets) {
        if (targets.isEmpty()) {
            resource.getAttributes().remove(MANAGED);
        } else {
            resource.getAttributes().put(MANAGED, MAPPER.valueToTree(targets).toString());
        }
    }

    private static Set<Key> keys(List<TargetDescription> targets) {
        return targets.stream().map(ElbV2CfnTargets::key).collect(Collectors.toSet());
    }

    private static Key key(TargetDescription target) {
        return new Key(target.getId(), target.getPort());
    }

    private record Key(String id, Integer port) {}
}
