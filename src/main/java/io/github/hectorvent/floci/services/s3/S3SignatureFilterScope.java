package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsQueryController;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;

final class S3SignatureFilterScope {

    private S3SignatureFilterScope() {
    }

    static boolean verifiesSignatures(S3Service s3Service, PreSignedUrlGenerator presignGenerator) {
        return (s3Service != null && s3Service.isAuthEnforced())
                || (presignGenerator != null && presignGenerator.shouldValidateSignatures());
    }

    static boolean routedToS3(ResourceInfo resourceInfo, ContainerRequestContext context) {
        return resourceInfo != null && S3Controller.class.equals(resourceInfo.getResourceClass())
                && !AwsQueryController.isQueryGet(context);
    }
}
