package io.github.hectorvent.floci.core.common;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/** Inflates DynamoDB JSON after wire authentication and before IAM reads table resources and keys. */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class DynamoDbGzipRequestFilter implements ContainerRequestFilter {

    private static final int MAX_BODY_BYTES = 16_000_000;

    private final ResourceInfo resourceInfo;
    private final ResolvedServiceCatalog catalog;

    @Inject
    public DynamoDbGzipRequestFilter(@Context ResourceInfo resourceInfo, ResolvedServiceCatalog catalog) {
        this.resourceInfo = resourceInfo;
        this.catalog = catalog;
    }

    @Override
    public void filter(ContainerRequestContext context) {
        String target = context.getHeaderString("X-Amz-Target");
        boolean dynamoDb = catalog.matchTarget(target)
                .filter(match -> "dynamodb".equals(match.descriptor().externalKey()))
                .filter(match -> match.prefix().startsWith("DynamoDB_"))
                .isPresent();
        MediaType type = context.getMediaType();
        if (resourceInfo.getResourceClass() != AwsJsonController.class || !dynamoDb || type == null
                || !type.isCompatible(MediaType.valueOf(AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0))
                || !isGzip(context.getHeaderString(HttpHeaders.CONTENT_ENCODING))) {
            return;
        }
        context.setEntityStream(new ByteArrayInputStream(
                AwsJsonGzipBody.decode(context.getEntityStream(), MAX_BODY_BYTES)));
    }

    private static boolean isGzip(String header) {
        if (header == null) {
            return false;
        }
        return "gzip".equals(header.split(",", 2)[0].trim().toLowerCase(Locale.ROOT));
    }
}
