package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** Supplies stored REST API policies to the shared IAM evaluator for execute-api resources. */
@ApplicationScoped
public class ApiGatewayResourcePolicyProvider implements ResourcePolicyProvider {

    private final ApiGatewayService service;
    private final ObjectMapper mapper;

    @Inject
    public ApiGatewayResourcePolicyProvider(ApiGatewayService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        if (!AwsArnUtils.isArnFor(resourceArn, "execute-api")) {
            return List.of();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        int slash = arn.resource().indexOf('/');
        if (slash < 1) {
            return List.of();
        }
        String apiId = arn.resource().substring(0, slash);
        return RequestScopes.callAs(arn.accountId(), () -> {
            try {
                String policy = service.getRestApi(arn.region(), apiId).getPolicy();
                return List.of(new ResourcePolicy(expandResources(policy, resourceArn, apiId), arn.accountId()));
            } catch (AwsException expected) {
                if (!"NotFoundException".equals(expected.getErrorCode())) {
                    throw expected;
                }
                // HTTP and WebSocket APIs use the same ARN service, but have no REST API policy.
                return List.of();
            }
        });
    }

    private String expandResources(String policy, String resourceArn, String apiId) {
        if (policy == null || policy.isBlank()) {
            return policy;
        }
        try {
            JsonNode document = mapper.readTree(policy);
            if (document == null || document.isNull()) {
                return policy;
            }
            JsonNode statements = document.path("Statement");
            String prefix = resourceArn.substring(0, resourceArn.length()
                    - AwsArnUtils.parse(resourceArn).resource().length()) + apiId;
            if (statements.isArray()) {
                for (JsonNode statement : statements) {
                    expandStatement(statement, prefix);
                }
            } else {
                expandStatement(statements, prefix);
            }
            return mapper.writeValueAsString(document);
        } catch (JsonProcessingException ignored) {
            // The shared evaluator owns malformed-policy handling.
            return policy;
        }
    }

    private void expandStatement(JsonNode statement, String prefix) {
        if (statement instanceof ObjectNode object) {
            for (String field : List.of("Resource", "NotResource")) {
                JsonNode resource = object.get(field);
                if (resource != null && resource.isTextual()) {
                    object.put(field, expand(resource.asText(), prefix));
                } else if (resource instanceof ArrayNode resources) {
                    for (int i = 0; i < resources.size(); i++) {
                        if (resources.get(i).isTextual()) {
                            resources.set(i, mapper.getNodeFactory().textNode(expand(resources.get(i).asText(), prefix)));
                        }
                    }
                }
            }
        }
    }

    private String expand(String resource, String prefix) {
        return resource.startsWith("execute-api:/") ? prefix + resource.substring("execute-api:".length()) : resource;
    }
}
