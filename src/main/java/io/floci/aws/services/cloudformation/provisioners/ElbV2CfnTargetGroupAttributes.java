package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.elbv2.ElbV2Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code TargetGroupAttributes} of an {@code AWS::ElasticLoadBalancingV2::TargetGroup}, applied within the provisioner's rollback guard: only the declared keys are written, an update
 * keeps the previous values of those keys until it commits, and a rollback puts them back.
 */
final class ElbV2CfnTargetGroupAttributes {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROPERTY = "TargetGroupAttributes";
    private static final String SHAPE = CfnPropertyTypes.propertyShape(
            "AWS::ElasticLoadBalancingV2::TargetGroup", PROPERTY);
    private static final String SNAPSHOT = CfnRollback.TARGET_GROUP_ATTRIBUTES_UPDATE_SNAPSHOT_ATTR;
    private static final TypeReference<Map<String, String>> ATTRIBUTE_MAP = new TypeReference<>() {};
    private final ElbV2Service service;

    ElbV2CfnTargetGroupAttributes(ElbV2Service service) {
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

    void apply(StackResource resource, Map<String, String> desired, ProvisionContext ctx, boolean created) {
        if (desired.isEmpty()) {
            return;
        }
        String arn = resource.getPhysicalId();
        if (!created) {
            Map<String, String> current = service.describeTargetGroupAttributes(ctx.region(), arn);
            Map<String, String> previous = new LinkedHashMap<>();
            desired.keySet().forEach(key -> previous.put(key, current.get(key)));
            resource.getAttributes().put(SNAPSHOT, MAPPER.valueToTree(previous).toString());
        }
        service.modifyTargetGroupAttributes(ctx.region(), arn, desired);
    }

    void restore(StackResource resource) {
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, SNAPSHOT);
        if (snapshot == null) {
            return;
        }
        String arn = resource.getPhysicalId();
        service.restoreTargetGroupAttributes(AwsArnUtils.parse(arn).region(), arn,
                MAPPER.convertValue(snapshot, ATTRIBUTE_MAP));
        resource.getAttributes().remove(SNAPSHOT);
    }
}
