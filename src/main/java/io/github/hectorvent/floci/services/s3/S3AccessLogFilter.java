package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.common.XmlParser.XmlElement;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Observes ordinary S3 HTTP/1 requests without buffering entities or changing their responses. */
@Provider
@Priority(Priorities.AUTHENTICATION - 1)
public class S3AccessLogFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String CAPTURE = S3AccessLogFilter.class.getName() + ".capture";
    private static final Set<String> QUERY_AUTH = Set.of("x-amz-algorithm", "x-amz-credential",
            "x-amz-signature", "x-amz-security-token", "x-amz-signedheaders",
            "awsaccesskeyid", "signature", "securitytoken");
    private static final Set<String> LIST_PARAMETERS = Set.of("list-type", "prefix", "delimiter",
            "max-keys", "marker", "continuation-token", "start-after", "encoding-type", "fetch-owner");
    private static final Set<String> OBJECT_PARAMETERS = Set.of("versionid", "response-content-type",
            "response-content-language", "response-expires", "response-cache-control",
            "response-content-disposition", "response-content-encoding");

    private final S3Service s3;
    private final S3AccessLogDelivery delivery;
    private final CurrentVertxRequest current;
    private final Clock clock;
    private final ResourceInfo resourceInfo;

    @Inject
    public S3AccessLogFilter(S3Service s3, S3AccessLogDelivery delivery, CurrentVertxRequest current,
                             Clock clock, @Context ResourceInfo resourceInfo) {
        this.s3 = s3;
        this.delivery = delivery;
        this.current = current;
        this.clock = clock;
        this.resourceInfo = resourceInfo;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        if (!S3SignatureFilterScope.routedToS3(resourceInfo)) {
            return;
        }
        try {
            RoutingContext routing = current.getCurrent();
            if (routing == null || request.getProperty(CAPTURE) != null) {
                return;
            }
            HttpServerRequest wire = routing.request();
            if (wire.version() != HttpVersion.HTTP_1_0 && wire.version() != HttpVersion.HTTP_1_1) {
                return;
            }
            String bucket = request.getUriInfo().getPathParameters().getFirst("bucket");
            String host = wire.getHeader("Host");
            String forwardedHost = request.getHeaderString("X-Forwarded-Host");
            if (bucket == null || host != null && host.contains("s3-website")
                    || host != null && host.contains("@")
                    || forwardedHost != null && forwardedHost.contains("s3-website")
                    || !wire.uri().startsWith("/")
                    || request.getHeaderString("x-amz-copy-source") != null
                    || hasQueryAuthentication(wire.query())
                    || sensitiveReferer(wire.getHeader("Referer"))) {
                return;
            }
            boolean virtualHost = request.getProperty(S3VirtualHostFilter.ORIGINAL_REQUEST_URI_PROPERTY) instanceof URI;
            String rawKey = rawKey(wire.path(), bucket, virtualHost);
            if (rawKey == null && request.getUriInfo().getPathParameters().containsKey("key")) {
                return;
            }
            String operation = operation(request.getMethod(), rawKey, wire.query());
            if (operation == null) {
                return;
            }
            S3Service.AccessLogTarget target = s3.accessLogTarget(bucket);
            if (target == null) {
                return;
            }
            String versionId = rawKey == null || "PUT".equals(request.getMethod()) ? null
                    : request.getUriInfo().getQueryParameters().getFirst("versionId");
            String protocol = wire.version() == HttpVersion.HTTP_1_0 ? "HTTP/1.0" : "HTTP/1.1";
            Capture capture = new Capture(target, clock.instant(),
                    wire.remoteAddress() == null ? null : wire.remoteAddress().host(), operation,
                    rawKey, request.getMethod() + " " + wire.uri() + " " + protocol,
                    wire.getHeader("Referer"), wire.getHeader("User-Agent"), versionId, host,
                    wire.getHeader("Range") != null);
            request.setProperty(CAPTURE, capture);
        } catch (RuntimeException ignored) {
            // Logging must not change request admission.
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!(request.getProperty(CAPTURE) instanceof Capture capture)) {
            return;
        }
        try {
            RoutingContext routing = current.getCurrent();
            if (routing == null || !capture.registered.compareAndSet(false, true)) {
                return;
            }
            String error = errorCode(response);
            S3HeaderSignatureFilter.VerifiedSigV4 verified =
                    request.getProperty(S3HeaderSignatureFilter.VERIFIED_SIGV4_PROPERTY)
                            instanceof S3HeaderSignatureFilter.VerifiedSigV4 identity ? identity : null;
            String requester = verified == null ? null : s3.accessLogRequester(verified.accessKeyId());
            routing.addEndHandler(outcome -> {
                if (!outcome.succeeded()) {
                    return;
                }
                try {
                    HttpServerResponse sent = routing.response();
                    Long size = objectSize(capture, sent);
                    delivery.submit(capture.target, new S3AccessLogRecord(capture.received,
                            capture.remoteIp, requester, capture.operation, capture.key,
                            capture.requestLine, capture.referer, capture.userAgent, capture.versionId,
                            capture.host, verified != null, sent.getStatusCode(), error,
                            sent.headers().get("x-amz-request-id"), sent.headers().get("x-amz-id-2"),
                            sent.bytesWritten(), size));
                } catch (RuntimeException ignored) {
                    // Disposal reports server-side body accounting, not client receipt.
                }
            });
        } catch (RuntimeException ignored) {
            // A response that cannot be observed is left untouched.
        }
    }

    static boolean hasQueryAuthentication(String query) {
        if (query == null) {
            return false;
        }
        try {
            for (String part : query.split("&", -1)) {
                String name = part.split("=", 2)[0];
                if (QUERY_AUTH.contains(URLDecoder.decode(name, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException malformed) {
            return true;
        }
    }

    private static String queryOf(String uri) {
        if (uri == null) {
            return null;
        }
        int question = uri.indexOf('?');
        return question < 0 ? null : uri.substring(question + 1);
    }

    static boolean sensitiveReferer(String referer) {
        if (referer == null) {
            return false;
        }
        if (hasQueryAuthentication(queryOf(referer))) {
            return true;
        }
        // Inspect the authority without normalizing the header's path or raw quotes.
        int scheme = referer.indexOf("://");
        int start = scheme >= 0 ? scheme + 3 : referer.startsWith("//") ? 2 : -1;
        if (start < 0) {
            return false;
        }
        int end = referer.length();
        for (char delimiter : new char[]{'/', '?', '#'}) {
            int position = referer.indexOf(delimiter, start);
            if (position >= 0) {
                end = Math.min(end, position);
            }
        }
        return referer.substring(start, end).contains("@");
    }

    static String rawKey(String rawPath, String bucket, boolean virtualHost) {
        String key;
        if (virtualHost) {
            key = rawPath.startsWith("/") ? rawPath.substring(1) : null;
        } else {
            String prefix = "/" + bucket + "/";
            int index = rawPath.indexOf(prefix);
            key = index < 0 ? null : rawPath.substring(index + prefix.length());
        }
        if (key == null || key.isEmpty()) {
            return null;
        }
        try {
            S3ObjectKeys.decodePath(key);
            return key;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    static String operation(String method, String key, String query) {
        if (key == null && !Set.of("GET", "HEAD").contains(method)
                || key != null && !Set.of("PUT", "GET", "HEAD", "DELETE").contains(method)) {
            return null;
        }
        if (query != null) {
            for (String part : query.split("&", -1)) {
                String name = URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
                Set<String> parameters = key == null
                        ? "GET".equals(method) ? LIST_PARAMETERS : Set.of()
                        : "PUT".equals(method) ? Set.of() : OBJECT_PARAMETERS;
                if (!name.isEmpty() && !name.startsWith("x-") && !parameters.contains(name)) {
                    return null;
                }
            }
        }
        return "REST." + method + (key == null ? ".BUCKET" : ".OBJECT");
    }

    private static String errorCode(ContainerResponseContext response) {
        if (response.getStatus() < 400 || !(response.getEntity() instanceof String xml) || xml.length() > 16_384) {
            return null;
        }
        XmlElement root = XmlParser.parseDocumentElementTree(xml);
        XmlElement code = root == null || !"Error".equals(root.name()) ? null : root.child("Code");
        return code != null && code.children().isEmpty() && code.text().matches("[A-Za-z0-9]{1,128}")
                ? code.text() : null;
    }

    private static Long objectSize(Capture capture, HttpServerResponse response) {
        if (capture.key == null || response.getStatusCode() < 200 || response.getStatusCode() >= 300
                || !Set.of("REST.GET.OBJECT", "REST.HEAD.OBJECT").contains(capture.operation)) {
            return null;
        }
        String size = response.headers().get("Content-Length");
        if (capture.range || response.getStatusCode() == 206) {
            String range = response.headers().get("Content-Range");
            size = range == null || !range.contains("/") ? null : range.substring(range.lastIndexOf('/') + 1);
        }
        try {
            long value = size == null ? -1 : Long.parseLong(size);
            return value < 0 ? null : value;
        } catch (NumberFormatException unavailable) {
            return null;
        }
    }

    private record Capture(S3Service.AccessLogTarget target, Instant received, String remoteIp,
                           String operation, String key, String requestLine,
                           String referer, String userAgent, String versionId, String host,
                           boolean range, AtomicBoolean registered) {
        Capture(S3Service.AccessLogTarget target, Instant received, String remoteIp,
                String operation, String key, String requestLine,
                String referer, String userAgent, String versionId, String host,
                boolean range) {
            this(target, received, remoteIp, operation, key, requestLine,
                    referer, userAgent, versionId, host, range, new AtomicBoolean());
        }
    }
}
