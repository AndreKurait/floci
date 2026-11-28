package io.floci.aws.services.apigateway;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.iam.IamActionRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    /** The routed segments, decoded once without letting an encoded slash become a path separator. */
    private static String[] documentationPath(ContainerRequestContext context) {
        List<String> segments = new ArrayList<>();
        segments.add("");
        String[] raw = IamActionRegistry.routedPath(context).split("/", -1);
        for (int index = 1; index < raw.length; index++) {
            String segment;
            try {
                segment = URLDecoder.decode(raw[index].replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                segment = raw[index];
            }
            if ("..".equals(segment)) {
                if (segments.size() > 1) {
                    segments.removeLast();
                }
            } else if (!segment.isEmpty() && !".".equals(segment)) {
                segments.add(segment.replace("%", "%25").replace("/", "%2F"));
            }
        }
        return segments.toArray(String[]::new);
    }

    public Map<String, List<String>> resolve(String action, ContainerRequestContext context) {
        String[] path = documentationPath(context);
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
