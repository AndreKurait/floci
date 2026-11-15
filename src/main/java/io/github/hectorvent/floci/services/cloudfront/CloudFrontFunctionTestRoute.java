package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.IamActionRegistry;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The single TestFunction route, including the matrix parameters the HTTP router ignores. */
public final class CloudFrontFunctionTestRoute {

    private static final Pattern PATH = Pattern.compile(
            "^/2020-05-31(?:;[^/]*)?/function(?:;[^/]*)?/([^/;]+)(?:;[^/]*)?/test(?:;[^/]*)?$");

    private CloudFrontFunctionTestRoute() {}

    public static boolean matches(ContainerRequestContext context) {
        return "POST".equalsIgnoreCase(context.getMethod())
                && PATH.matcher(IamActionRegistry.routedPath(context)).matches();
    }

    public static String name(ContainerRequestContext context) {
        Matcher matcher = PATH.matcher(IamActionRegistry.routedPath(context));
        if (!"POST".equalsIgnoreCase(context.getMethod()) || !matcher.matches()) {
            throw new AwsException("InvalidArgument", "The function test route is invalid.", 400);
        }
        try {
            return URLDecoder.decode(matcher.group(1).replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            throw new AwsException("InvalidArgument", "The function test route is invalid.", 400);
        }
    }
}
