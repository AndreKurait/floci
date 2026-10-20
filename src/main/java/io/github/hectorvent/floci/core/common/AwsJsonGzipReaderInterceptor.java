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

/** Decodes AWS JSON entities after request filters have verified the signed wire bytes. */
@Provider
@Priority(Priorities.ENTITY_CODER)
public class AwsJsonGzipReaderInterceptor implements ReaderInterceptor {

    private final ResourceInfo resourceInfo;

    @Inject
    public AwsJsonGzipReaderInterceptor(@Context ResourceInfo resourceInfo) {
        this.resourceInfo = resourceInfo;
    }

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
        Class<?> resource = resourceInfo == null ? null : resourceInfo.getResourceClass();
        if (resource != AwsJsonController.class && resource != AwsJson11Controller.class) {
            return context.proceed();
        }
        MediaType mediaType = context.getMediaType();
        String contentType = mediaType == null ? "" : mediaType.getType() + "/" + mediaType.getSubtype();
        boolean awsJson = AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0.equalsIgnoreCase(contentType)
                || AwsJson11Controller.CONTENT_TYPE_AWS_JSON_1_1.equalsIgnoreCase(contentType)
                || MediaType.APPLICATION_JSON.equalsIgnoreCase(contentType)
                && context.getHeaders().getFirst("X-Amz-Target") != null;
        if (!awsJson || !AwsJsonCborController.isGZipped(context.getHeaders().get(HttpHeaders.CONTENT_ENCODING))) {
            return context.proceed();
        }

        InputStream original = context.getInputStream();
        byte[] body = original.readAllBytes();
        context.setInputStream(new ByteArrayInputStream(AwsJsonCborController.decodeBody(body)));
        try {
            return context.proceed();
        } finally {
            context.setInputStream(original);
        }
    }
}
