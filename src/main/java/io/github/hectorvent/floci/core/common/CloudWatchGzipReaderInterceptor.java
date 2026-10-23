package io.github.hectorvent.floci.core.common;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/** Decompresses CloudWatch publication after request filters authenticate the wire bytes. */
@Provider
@Priority(Priorities.ENTITY_CODER)
public class CloudWatchGzipReaderInterceptor implements ReaderInterceptor {

    private static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

    private final ResourceInfo resourceInfo;
    private final ResolvedServiceCatalog catalog;

    @Inject
    public CloudWatchGzipReaderInterceptor(@Context ResourceInfo resourceInfo, ResolvedServiceCatalog catalog) {
        this.resourceInfo = resourceInfo;
        this.catalog = catalog;
    }

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
        String target = context.getHeaders().getFirst("X-Amz-Target");
        boolean publication = catalog.matchTarget(target)
                .filter(match -> "monitoring".equals(match.descriptor().externalKey()))
                .filter(match -> "PutMetricData".equals(match.action()))
                .isPresent();
        MediaType type = context.getMediaType();
        if (resourceInfo.getResourceClass() != AwsJsonController.class || !publication || type == null
                || !type.isCompatible(MediaType.valueOf(AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0))
                || !"gzip".equalsIgnoreCase(context.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING))) {
            return context.proceed();
        }

        InputStream original = context.getInputStream();
        try (GZIPInputStream compressed = new GZIPInputStream(original)) {
            byte[] body = compressed.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new AwsException("RequestEntityTooLargeException",
                        "Request size exceeded " + MAX_BODY_BYTES + " bytes", 413);
            }
            context.setInputStream(new ByteArrayInputStream(body));
        } catch (IOException error) {
            throw new AwsException("InvalidRequestException", "Failed to decode request body", 400);
        }
        try {
            return context.proceed();
        } finally {
            context.setInputStream(original);
        }
    }
}
