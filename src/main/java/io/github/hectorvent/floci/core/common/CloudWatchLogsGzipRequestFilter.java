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

/** Inflates log batches after wire authentication and before IAM reads the log stream resource. */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class CloudWatchLogsGzipRequestFilter implements ContainerRequestFilter {

    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
    private static final MediaType AWS_JSON_1_1 =
            MediaType.valueOf(AwsJson11Controller.CONTENT_TYPE_AWS_JSON_1_1);

    private final ResourceInfo resourceInfo;
    private final ResolvedServiceCatalog catalog;

    @Inject
    public CloudWatchLogsGzipRequestFilter(@Context ResourceInfo resourceInfo, ResolvedServiceCatalog catalog) {
        this.resourceInfo = resourceInfo;
        this.catalog = catalog;
    }

    @Override
    public void filter(ContainerRequestContext context) {
        if (resourceInfo.getResourceClass() != AwsJson11Controller.class
                || !"gzip".equalsIgnoreCase(context.getHeaderString(HttpHeaders.CONTENT_ENCODING))) {
            return;
        }
        boolean logPublication = catalog.matchTarget(context.getHeaderString("X-Amz-Target"))
                .filter(match -> "logs".equals(match.descriptor().externalKey()))
                .filter(match -> "PutLogEvents".equals(match.action()))
                .isPresent();
        MediaType type = context.getMediaType();
        if (!logPublication || type == null || !type.isCompatible(AWS_JSON_1_1)) {
            return;
        }
        context.setEntityStream(new ByteArrayInputStream(
                AwsJsonGzipBody.decode(context.getEntityStream(), MAX_BODY_BYTES)));
    }
}
