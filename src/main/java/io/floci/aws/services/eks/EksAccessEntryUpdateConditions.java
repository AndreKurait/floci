package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.eks.model.AccessEntry;
import io.floci.aws.services.iam.IamActionRegistry;
import io.floci.aws.services.iam.ResourceArnBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conditions for the native UpdateAccessEntry route only. */
@ApplicationScoped
public class EksAccessEntryUpdateConditions {
    private final EksService eks;
    private final ResourceArnBuilder arnBuilder;

    @Inject
    public EksAccessEntryUpdateConditions(EksService eks, ResourceArnBuilder arnBuilder) {
        this.eks = eks;
        this.arnBuilder = arnBuilder;
    }

    public Map<String, List<String>> resolve(ContainerRequestContext context) {
        String[] path = IamActionRegistry.routedPath(context).substring(1).split("/", 4);
        if (path.length != 4 || !"clusters".equals(path[0]) || !"access-entries".equals(path[2])) {
            return null;
        }
        String cluster = decode(path[1]);
        String principal = decode(path[3]);
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        conditions.put("eks:clusterName", List.of(cluster));
        conditions.put("eks:principalArn", List.of(principal));
        // EKS's held-resource ARN lookup reads only the path, so buffer this action's body explicitly.
        JsonNode body = arnBuilder.readJsonBody(context);
        if (body != null && body.isObject()) {
            JsonNode username = body.get("username");
            if (username != null && username.isTextual()) {
                conditions.put("eks:username", List.of(username.textValue()));
            }
            JsonNode groups = body.get("kubernetesGroups");
            if (groups != null && groups.isArray()) {
                List<String> values = new ArrayList<>();
                for (JsonNode group : groups) {
                    if (group.isTextual()) {
                        values.add(group.textValue());
                    }
                }
                conditions.put("eks:kubernetesGroups", List.copyOf(values));
            }
        }
        eks.findAccessEntryUpdateTarget(cluster, principal).map(AccessEntry::tags).ifPresent(tags ->
                tags.forEach((key, value) -> {
                    if (key != null && value != null) {
                        conditions.put("aws:ResourceTag/" + key, List.of(value));
                    }
                }));
        return conditions;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            // The route's ordinary validation will refuse a malformed principal without inventing another identity.
            return value;
        }
    }
}
