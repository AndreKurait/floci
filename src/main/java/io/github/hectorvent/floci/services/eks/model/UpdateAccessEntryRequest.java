package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** Optional update fields. A null member is omitted; an empty group list is a replacement. */
@RegisterForReflection
public record UpdateAccessEntryRequest(String username, List<String> kubernetesGroups, String clientRequestToken) {
    public static UpdateAccessEntryRequest fromJson(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw invalid("Request must be a JSON object");
        }
        String username = string(body, "username");
        String token = string(body, "clientRequestToken");
        List<String> groups = null;
        JsonNode groupNode = body.get("kubernetesGroups");
        if (groupNode != null && !groupNode.isNull()) {
            if (!groupNode.isArray()) {
                throw invalid("kubernetesGroups must be an array of strings");
            }
            groups = new ArrayList<>();
            for (JsonNode group : groupNode) {
                if (!group.isTextual()) {
                    throw invalid("kubernetesGroups must contain strings");
                }
                groups.add(group.textValue());
            }
            groups = List.copyOf(groups);
        }
        return new UpdateAccessEntryRequest(username, groups, token);
    }

    private static String string(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return value.textValue();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
