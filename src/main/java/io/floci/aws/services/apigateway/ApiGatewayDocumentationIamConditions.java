package io.floci.aws.services.apigateway;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.iam.ResourceArnBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Documentation children inherit live RestApi tags for ABAC, without exposing inherited GetTags. */
@ApplicationScoped
public class ApiGatewayDocumentationIamConditions {
    private final ApiGatewayService service;
    private final RequestContext requestContext;
    private final EmulatorConfig config;

    @Inject
    public ApiGatewayDocumentationIamConditions(ApiGatewayService service, RequestContext requestContext,
                                                EmulatorConfig config) {
        this.service = service;
        this.requestContext = requestContext;
        this.config = config;
    }

    public Map<String, List<String>> resolve(String action, ContainerRequestContext context) {
        String[] path = ResourceArnBuilder.apiGatewayResourcePath(context).split("/", -1);
        if ((path.length != 5 && path.length != 6) || !"restapis".equals(path[1])
                || !"documentation".equals(path[3]) || !"versions".equals(path[4])
                || path[2].isEmpty() || path.length == 6 && path[5].isEmpty()
                || !("apigateway:" + context.getMethod()).equals(action)) {
            return null;
        }
        Set<String> methods = path.length == 5 ? Set.of("GET", "POST") : Set.of("GET", "PATCH", "DELETE");
        if (!methods.contains(context.getMethod())) {
            return null;
        }
        String apiId = URLDecoder.decode(path[2].replace("+", "%2B"), StandardCharsets.UTF_8);
        String account = requestContext.getAccountId() == null ? config.defaultAccountId() : requestContext.getAccountId();
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        service.documentationResourceTags(account, region, apiId)
                .forEach((key, value) -> conditions.put("aws:ResourceTag/" + key, List.of(value)));
        return conditions;
    }
}
