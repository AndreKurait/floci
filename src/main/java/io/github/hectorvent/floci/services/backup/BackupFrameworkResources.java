package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.StoredResourceArns;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The bounded framework routes and their resource identities, shared by IAM readers. */
public final class BackupFrameworkResources {

    private static final Set<String> NAMED = Set.of("backup:CreateFramework", "backup:DescribeFramework",
            "backup:UpdateFramework", "backup:DeleteFramework");
    private static final Set<String> TAGGING = Set.of("backup:TagResource", "backup:UntagResource",
            "backup:ListTags");

    private BackupFrameworkResources() {
    }

    public static boolean supports(String action) {
        return action != null && (NAMED.contains(action) || TAGGING.contains(action) || "backup:ListFrameworks".equals(action));
    }

    public static boolean matches(String action, ContainerRequestContext request, JsonNode body) {
        return supports(action) && (!TAGGING.contains(action)
                || BackupFrameworkService.isFrameworkArn(target(action, request, body)));
    }

    public static String routeAction(String method, String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        if (normalized.equals("/audit/frameworks") || normalized.equals("/audit/frameworks/")) {
            return switch (method) {
                case "POST" -> "backup:CreateFramework";
                case "GET" -> "backup:ListFrameworks";
                default -> null;
            };
        }
        if (normalized.startsWith("/audit/frameworks/")) {
            return switch (method) {
                case "GET" -> "backup:DescribeFramework";
                case "PUT" -> "backup:UpdateFramework";
                case "DELETE" -> "backup:DeleteFramework";
                default -> null;
            };
        }
        if (normalized.startsWith("/tags/")
                && BackupFrameworkService.isFrameworkArn(normalized.substring("/tags/".length()))) {
            return switch (method) {
                case "GET" -> "backup:ListTags";
                case "POST" -> "backup:TagResource";
                default -> null;
            };
        }
        if ("POST".equals(method) && normalized.startsWith("/untag/")
                && BackupFrameworkService.isFrameworkArn(normalized.substring("/untag/".length()))) {
            return "backup:UntagResource";
        }
        return null;
    }

    public static String target(String action, ContainerRequestContext request, JsonNode body) {
        if ("backup:CreateFramework".equals(action)) {
            return body == null ? null : body.path("FrameworkName").asText(null);
        }
        if (request.getUriInfo() == null) {
            return null;
        }
        if (NAMED.contains(action)) {
            return request.getUriInfo().getPathParameters().getFirst("frameworkName");
        }
        if (TAGGING.contains(action)) {
            String arn = request.getUriInfo().getPathParameters().getFirst("arn");
            return arn == null ? request.getUriInfo().getPathParameters().getFirst("resourceArn") : arn;
        }
        return null;
    }

    public static List<String> arns(String action, ContainerRequestContext request, JsonNode body,
                                    String partition, String region, String account, StoredResourceArns stored) {
        String target = target(action, request, body);
        if (target == null) {
            return List.of("*");
        }
        if (TAGGING.contains(action)) {
            return List.of(target);
        }
        if (stored != null) {
            Optional<String> found = stored.find("framework", List.of(target), region);
            if (found.isPresent()) {
                return List.of(found.get());
            }
        }
        return List.of(new AwsArnUtils.Arn(partition, "backup", region, account,
                "framework:" + target + "-*").toString());
    }
}
