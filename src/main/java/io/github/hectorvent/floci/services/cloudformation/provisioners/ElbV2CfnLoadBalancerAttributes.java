package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;

import java.util.LinkedHashMap;
import java.util.Map;

final class ElbV2CfnLoadBalancerAttributes {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROPERTY = "LoadBalancerAttributes";
    private static final String SHAPE = CfnPropertyTypes.propertyShape(
            "AWS::ElasticLoadBalancingV2::LoadBalancer", PROPERTY);
    private static final String SNAPSHOT = CfnRollback.LOAD_BALANCER_ATTRIBUTES_UPDATE_SNAPSHOT_ATTR;
    private static final TypeReference<Map<String, String>> ATTRIBUTE_MAP = new TypeReference<>() {};
    private final ElbV2Service service;

    ElbV2CfnLoadBalancerAttributes(ElbV2Service service) {
        this.service = service;
    }

    static Map<String, String> declared(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has(PROPERTY)) {
            return Map.of();
        }
        CfnPropertyTypes.Reader types = new CfnPropertyTypes.Reader(ctx.engine());
        JsonNode rows = types.read(PROPERTY, props.get(PROPERTY), SHAPE);
        types.finish();
        Map<String, String> result = new LinkedHashMap<>();
        if (rows == null) {
            return result;
        }
        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            for (String member : new String[]{"Key", "Value"}) {
                if (!row.hasNonNull(member)) {
                    throw new AwsException("ValidationError",
                            PROPERTY + "/" + index + " must include " + member, 400);
                }
            }
            result.put(row.path("Key").asText(), row.path("Value").asText());
        }
        return result;
    }

    void apply(StackResource resource, Map<String, String> desired, ProvisionContext ctx,
               boolean created, Runnable restoreSubnets) {
        if (desired.isEmpty()) {
            return;
        }
        String arn = resource.getPhysicalId();
        CfnRollback.guarded(resource, ctx, created,
                () -> service.deleteLoadBalancer(ctx.region(), arn),
                () -> {
                    restore(resource);
                    restoreSubnets.run();
                },
                () -> {
                    if (!created) {
                        Map<String, String> current = service.describeLoadBalancerAttributes(ctx.region(), arn);
                        Map<String, String> previous = new LinkedHashMap<>();
                        desired.keySet().forEach(key -> previous.put(key, current.get(key)));
                        resource.getAttributes().put(SNAPSHOT, MAPPER.valueToTree(previous).toString());
                    }
                    service.modifyLoadBalancerAttributes(ctx.region(), arn, desired);
                });
        if (!ctx.isUpdate()) {
            resource.getAttributes().remove(SNAPSHOT);
        }
    }

    void restore(StackResource resource) {
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, SNAPSHOT);
        if (snapshot == null) {
            return;
        }
        String arn = resource.getPhysicalId();
        service.restoreLoadBalancerAttributes(AwsArnUtils.parse(arn).region(), arn,
                MAPPER.convertValue(snapshot, ATTRIBUTE_MAP));
        resource.getAttributes().remove(SNAPSHOT);
    }
}
