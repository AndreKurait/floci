package io.floci.aws.services.cloudfront;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.iam.IamActionRegistry;
import jakarta.ws.rs.container.ContainerRequestContext;

/** Reads the name from the generated route already resolved as TestFunction. */
public final class CloudFrontFunctionTestRoute {

    private CloudFrontFunctionTestRoute() {}

    public static String name(ContainerRequestContext context) {
        String name = IamActionRegistry.pathLabels("cloudfront", context).get("Name");
        if (name == null) {
            throw new AwsException("InvalidArgument", "The function test route is invalid.", 400);
        }
        return name;
    }
}
