package io.floci.aws.services.s3;

import io.floci.aws.services.s3.model.Bucket;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3AccessLogFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"caf%C3%A9", "a%20b", "a%2Fb", "/leading//plus+%2B", "quote%22key"})
    void encodedKeysHaveTheSameWireSpellingInPathAndVirtualHostRequests(String key) {
        assertEquals(key, S3AccessLogFilter.rawKey("/source/" + key, "source", false));
        assertEquals(key, S3AccessLogFilter.rawKey("/" + key, "source", true));
        assertEquals("REST.GET.OBJECT", S3AccessLogFilter.operation("GET", key, "versionId=v1"));
        assertEquals("REST.PUT.OBJECT", S3AccessLogFilter.operation("PUT", key, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Amz-Signature=secret", "x-%61mz-signature=secret", "AWSAccessKeyId=key",
            "Signature=secret", "X-Amz-Security-Token=secret", "broken%=name"})
    void queryCredentialsAreRejectedBeforeCapture(String query) {
        assertTrue(S3AccessLogFilter.hasQueryAuthentication(query));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://user:password@example.test/path", "//user:password@example.test/path",
            "https://example.test/a?X-Amz-Signature=secret"})
    void credentialBearingRefererIsOutsideTheCapturedSubset(String referer) {
        assertTrue(S3AccessLogFilter.sensitiveReferer(referer));
        Fixture fixture = new Fixture();
        when(fixture.wire.getHeader("Referer")).thenReturn(referer);
        fixture.filter.filter(fixture.request);
        verify(fixture.s3, never()).accessLogTarget(anyString());
    }

    @Test
    void ordinaryQuotedHeadersAndEncodedCustomQueryValuesAreNotRewritten() {
        assertFalse(S3AccessLogFilter.sensitiveReferer("https://example.test/a\"quoted"));
        assertFalse(S3AccessLogFilter.hasQueryAuthentication("x-note=Signature%3Dordinary"));
        assertEquals("REST.GET.BUCKET", S3AccessLogFilter.operation("GET", null, "prefix=a%26logging"));
        assertNull(S3AccessLogFilter.operation("GET", null, "logging"));
        assertNull(S3AccessLogFilter.operation("PUT", "key", "uploadId=part"));
        assertNull(S3AccessLogFilter.operation("PUT", "key", "versionId=v1"));
    }

    @Test
    void actualFinalStatusHeaderIdsAndXmlErrorAreRecordedOnceWithoutReadingEntityStreams() {
        Fixture fixture = new Fixture();
        fixture.filter.filter(fixture.request);
        when(fixture.response.getStatus()).thenReturn(404);
        when(fixture.response.getEntity()).thenReturn("<Error><Code>NoSuchKey</Code><RequestId>different-xml-id</RequestId></Error>");
        fixture.filter.filter(fixture.request, fixture.response);
        fixture.filter.filter(fixture.request, fixture.response);
        Handler<AsyncResult<Void>> end = fixture.handler();
        when(fixture.sent.getStatusCode()).thenReturn(404);
        when(fixture.sent.bytesWritten()).thenReturn(91L);
        fixture.headers.set("x-amz-request-id", "actual-header-id").set("x-amz-id-2", "actual-extended-id");
        end.handle(Future.succeededFuture());
        ArgumentCaptor<S3AccessLogRecord> record = ArgumentCaptor.forClass(S3AccessLogRecord.class);
        verify(fixture.delivery, times(1)).submit(eq(fixture.target), record.capture());
        assertEquals(404, record.getValue().status());
        assertEquals("NoSuchKey", record.getValue().errorCode());
        assertEquals("actual-header-id", record.getValue().requestId());
        assertEquals("actual-extended-id", record.getValue().hostId());
        assertEquals(91, record.getValue().bytesSent());
        assertNull(record.getValue().objectSize());
        verify(fixture.request, never()).getEntityStream();
    }

    @Test
    void aResetDoesNotClaimACompletedResponse() {
        Fixture fixture = new Fixture();
        fixture.filter.filter(fixture.request);
        fixture.filter.filter(fixture.request, fixture.response);
        fixture.handler().handle(Future.failedFuture("connection reset"));
        verify(fixture.delivery, never()).submit(any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 403, 404})
    void verifiedHeaderIdentityIsCapturedAfterAuthenticationRegardlessOfResponseStatus(int status) {
        Fixture fixture = new Fixture();
        fixture.filter.filter(fixture.request);
        fixture.properties.put(S3HeaderSignatureFilter.VERIFIED_SIGV4_PROPERTY,
                new S3HeaderSignatureFilter.VerifiedSigV4("verified-key"));
        when(fixture.s3.accessLogRequester("verified-key")).thenReturn("verified-requester");
        when(fixture.sent.getStatusCode()).thenReturn(status);
        fixture.filter.filter(fixture.request, fixture.response);
        fixture.handler().handle(Future.succeededFuture());
        ArgumentCaptor<S3AccessLogRecord> record = ArgumentCaptor.forClass(S3AccessLogRecord.class);
        verify(fixture.delivery).submit(eq(fixture.target), record.capture());
        assertTrue(record.getValue().headerVerified());
        assertEquals("verified-requester", record.getValue().requester());
        assertEquals(status, record.getValue().status());
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 403, 404})
    void aHeaderAndStatusWithoutVerifierEvidenceCannotClaimAuthentication(int status) {
        Fixture fixture = new Fixture();
        when(fixture.request.getHeaderString("Authorization")).thenReturn("AWS4-HMAC-SHA256 unverified");
        fixture.filter.filter(fixture.request);
        when(fixture.sent.getStatusCode()).thenReturn(status);
        fixture.filter.filter(fixture.request, fixture.response);
        fixture.handler().handle(Future.succeededFuture());
        ArgumentCaptor<S3AccessLogRecord> record = ArgumentCaptor.forClass(S3AccessLogRecord.class);
        verify(fixture.delivery).submit(eq(fixture.target), record.capture());
        assertFalse(record.getValue().headerVerified());
        assertNull(record.getValue().requester());
        verify(fixture.s3, never()).accessLogRequester(anyString());
    }

    @Test
    void streamEntityRemainsUnreadAndRangeReportsFullObjectSize() {
        Fixture fixture = new Fixture();
        when(fixture.wire.getHeader("Range")).thenReturn("bytes=1-3");
        fixture.filter.filter(fixture.request);
        when(fixture.response.getEntity()).thenReturn(mock(InputStream.class));
        fixture.filter.filter(fixture.request, fixture.response);
        when(fixture.sent.getStatusCode()).thenReturn(206);
        when(fixture.sent.bytesWritten()).thenReturn(3L);
        fixture.headers.set("Content-Length", "3").set("Content-Range", "bytes 1-3/100");
        fixture.handler().handle(Future.succeededFuture());
        ArgumentCaptor<S3AccessLogRecord> record = ArgumentCaptor.forClass(S3AccessLogRecord.class);
        verify(fixture.delivery).submit(eq(fixture.target), record.capture());
        assertEquals(100L, record.getValue().objectSize());
        assertEquals(3L, record.getValue().bytesSent());
        verify(fixture.response, never()).getEntity();
    }

    @Test
    void virtualHostMarkerPreservesEncodedObjectAndOriginalRequestUri() {
        Fixture fixture = new Fixture();
        fixture.properties.put(S3VirtualHostFilter.ORIGINAL_REQUEST_URI_PROPERTY,
                URI.create("http://source.localhost/caf%C3%A9%20a%2Fb"));
        when(fixture.wire.path()).thenReturn("/caf%C3%A9%20a%2Fb");
        when(fixture.wire.uri()).thenReturn("/caf%C3%A9%20a%2Fb");
        fixture.filter.filter(fixture.request);
        fixture.filter.filter(fixture.request, fixture.response);
        fixture.handler().handle(Future.succeededFuture());
        ArgumentCaptor<S3AccessLogRecord> record = ArgumentCaptor.forClass(S3AccessLogRecord.class);
        verify(fixture.delivery).submit(eq(fixture.target), record.capture());
        assertEquals("caf%C3%A9%20a%2Fb", record.getValue().key());
        assertEquals("REST.GET.OBJECT", record.getValue().operation());
        assertEquals("GET /caf%C3%A9%20a%2Fb HTTP/1.1", record.getValue().requestLine());
    }

    private static final class Fixture {
        final S3Service s3 = mock(S3Service.class);
        final S3AccessLogDelivery delivery = mock(S3AccessLogDelivery.class);
        final ContainerRequestContext request = mock(ContainerRequestContext.class);
        final ContainerResponseContext response = mock(ContainerResponseContext.class);
        final HttpServerRequest wire = mock(HttpServerRequest.class);
        final HttpServerResponse sent = mock(HttpServerResponse.class);
        final RoutingContext routing = mock(RoutingContext.class);
        final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        final Map<String, Object> properties = new HashMap<>();
        final S3Service.AccessLogTarget target = new S3Service.AccessLogTarget("000000000000", "us-east-1", "source",
                new Bucket("source"), "<BucketLoggingStatus><LoggingEnabled><TargetBucket>target</TargetBucket>"
                        + "<TargetPrefix>logs/</TargetPrefix></LoggingEnabled></BucketLoggingStatus>",
                new S3AccessLogConfiguration("target", "logs/", S3AccessLogConfiguration.DateSource.SIMPLE),
                new Bucket("target"));
        final S3AccessLogFilter filter;

        Fixture() {
            CurrentVertxRequest current = mock(CurrentVertxRequest.class);
            ResourceInfo resource = mock(ResourceInfo.class);
            UriInfo uri = mock(UriInfo.class);
            MultivaluedHashMap<String, String> paths = new MultivaluedHashMap<>();
            paths.putSingle("bucket", "source");
            paths.putSingle("key", "key");
            when(uri.getPathParameters()).thenReturn(paths);
            when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
            when(request.getUriInfo()).thenReturn(uri);
            when(request.getMethod()).thenReturn("GET");
            when(request.getProperty(anyString())).thenAnswer(call -> properties.get(call.getArgument(0)));
            doAnswer(call -> {
                properties.put(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(request).setProperty(anyString(), any());
            when(resource.getResourceClass()).thenAnswer(call -> S3Controller.class);
            when(current.getCurrent()).thenReturn(routing);
            when(routing.request()).thenReturn(wire);
            when(routing.response()).thenReturn(sent);
            when(wire.version()).thenReturn(HttpVersion.HTTP_1_1);
            when(wire.path()).thenReturn("/source/key");
            when(wire.uri()).thenReturn("/source/key");
            when(wire.getHeader("Host")).thenReturn("localhost");
            when(sent.headers()).thenReturn(headers);
            when(sent.getStatusCode()).thenReturn(200);
            when(response.getStatus()).thenReturn(200);
            when(s3.accessLogTarget("source")).thenReturn(target);
            filter = new S3AccessLogFilter(s3, delivery, current,
                    Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC), resource);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        Handler<AsyncResult<Void>> handler() {
            ArgumentCaptor<Handler<AsyncResult<Void>>> handler = ArgumentCaptor.forClass((Class) Handler.class);
            verify(routing, times(1)).addEndHandler(handler.capture());
            return handler.getValue();
        }
    }
}
