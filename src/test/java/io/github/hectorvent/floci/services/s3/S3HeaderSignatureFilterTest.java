package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testutil.S3RequestSigner;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3HeaderSignatureFilterTest {

    private S3HeaderSignatureFilter filter;
    private ContainerRequestContext ctx;
    private ResourceInfo resourceInfo;
    private PreSignedUrlGenerator presignGenerator;

    @BeforeEach
    void setUp() {
        resourceInfo = mock(ResourceInfo.class);
        doReturn(S3Controller.class).when(resourceInfo).getResourceClass();
        presignGenerator = mock(PreSignedUrlGenerator.class);
        when(presignGenerator.shouldValidateSignatures()).thenReturn(true);
        filter = new S3HeaderSignatureFilter(null, presignGenerator, null, null, resourceInfo);
        ctx = mock(ContainerRequestContext.class);
    }

    @Test
    void abortsWhenAuthorizationHeaderDoesNotStartWithAlgorithm() throws IOException {
        when(ctx.getHeaderString("Authorization"))
                .thenReturn("X Credential=111122223333/20261001/us-east-1/s3/aws4_request");

        filter.filter(ctx);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        Response response = captor.getValue();
        assertEquals(400, response.getStatus());
        String entity = (String) response.getEntity();
        assertTrue(entity.contains("<Code>AuthorizationHeaderMalformed</Code>"), entity);
        assertTrue(entity.contains("The authorization header you provided is invalid."), entity);
    }

    @Test
    void allowsRequestWhenAuthorizationHeaderAbsent() throws IOException {
        when(ctx.getHeaderString("Authorization")).thenReturn(null);

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void allowsRequestWhenAuthorizationHeaderBlank() throws IOException {
        when(ctx.getHeaderString("Authorization")).thenReturn("   ");

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void ignoresRequestWhenNotRoutedToS3() throws IOException {
        ResourceInfo nonS3Info = mock(ResourceInfo.class);
        doReturn(Object.class).when(nonS3Info).getResourceClass();
        S3HeaderSignatureFilter nonS3Filter =
                new S3HeaderSignatureFilter(null, presignGenerator, null, null, nonS3Info);
        when(ctx.getHeaderString("Authorization")).thenReturn("Malformed Header");

        nonS3Filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void allowsMalformedAuthorizationHeaderWhenValidationDisabled() throws IOException {
        S3HeaderSignatureFilter permissiveFilter =
                new S3HeaderSignatureFilter(null, null, null, null, resourceInfo);
        when(ctx.getHeaderString("Authorization"))
                .thenReturn("X Credential=111122223333/20261001/us-east-1/s3/aws4_request");

        permissiveFilter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void allowsSigV4AAuthorizationHeaderWhenValidationDisabled() throws IOException {
        S3HeaderSignatureFilter permissiveFilter =
                new S3HeaderSignatureFilter(null, null, null, null, resourceInfo);
        when(ctx.getHeaderString("Authorization"))
                .thenReturn("AWS4-ECDSA-P256-SHA256 Credential=111122223333/20261001/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc");

        permissiveFilter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void abortsSigV4AAuthorizationHeaderWhoseScopeStillCarriesARegion() throws IOException {
        when(ctx.getHeaderString("Authorization"))
                .thenReturn("AWS4-ECDSA-P256-SHA256 Credential=111122223333/20261001/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc");

        filter.filter(ctx);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        Response response = captor.getValue();
        assertEquals(400, response.getStatus());
        String entity = (String) response.getEntity();
        assertTrue(entity.contains("<Code>AuthorizationHeaderMalformed</Code>"), entity);
        assertTrue(entity.contains("YYYYMMDD/SERVICE/aws4_request"), entity);
    }

    @Test
    void abortsWhenAuthorizationHeaderContainsDuplicateComponent() throws IOException {
        when(ctx.getHeaderString("Authorization"))
                .thenReturn("AWS4-HMAC-SHA256 Credential=AKID/20261001/us-east-1/s3/aws4_request, "
                        + "SignedHeaders=host, Signature=abc, "
                        + "Credential=AKID2/20261001/us-east-1/s3/aws4_request");

        filter.filter(ctx);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        Response response = captor.getValue();
        assertEquals(400, response.getStatus());
        String entity = (String) response.getEntity();
        assertTrue(entity.contains("<Code>AuthorizationHeaderMalformed</Code>"), entity);
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "forged", "payload-mismatch", "disabled", "anonymous"})
    void onlySuccessfulHeaderAndPayloadVerificationProducesRequestLocalEvidence(String kind) throws Exception {
        URI address = URI.create("http://localhost/source/key");
        byte[] body = "original payload".getBytes(StandardCharsets.UTF_8);
        S3RequestSigner signer = S3RequestSigner.signedAs("test", "test");
        if ("forged".equals(kind)) {
            signer = signer.withSignature("0".repeat(64));
        } else if ("payload-mismatch".equals(kind)) {
            signer = signer.withContentSha256("0".repeat(64));
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (!"anonymous".equals(kind)) {
            headers.putAll(signer.headersFor("PUT", address, body));
            headers.put("Host", "localhost");
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put(S3HeaderSignatureFilter.VERIFIED_SIGV4_PROPERTY,
                new S3HeaderSignatureFilter.VerifiedSigV4("stale-evidence"));
        when(ctx.getProperty(anyString())).thenAnswer(call -> properties.get(call.getArgument(0)));
        doAnswer(call -> {
            properties.remove(call.getArgument(0));
            return null;
        }).when(ctx).removeProperty(anyString());
        doAnswer(call -> {
            properties.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(ctx).setProperty(anyString(), any());
        when(ctx.getHeaderString(anyString())).thenAnswer(call -> headers.get(call.getArgument(0)));
        when(ctx.getMethod()).thenReturn("PUT");
        UriInfo uri = mock(UriInfo.class);
        when(uri.getRequestUri()).thenReturn(address);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(ctx.getUriInfo()).thenReturn(uri);
        AtomicReference<InputStream> entity = new AtomicReference<>(new ByteArrayInputStream(body));
        when(ctx.getEntityStream()).thenAnswer(call -> entity.get());
        doAnswer(call -> {
            entity.set(call.getArgument(0));
            return null;
        }).when(ctx).setEntityStream(any());
        S3HeaderSignatureFilter selected = "disabled".equals(kind)
                ? new S3HeaderSignatureFilter(null, null, null, null, resourceInfo) : filter;
        selected.filter(ctx);
        assertArrayEquals(body, entity.get().readAllBytes(), "Verification must preserve the original payload");
        if ("valid".equals(kind)) {
            assertEquals(new S3HeaderSignatureFilter.VerifiedSigV4("test"),
                    properties.get(S3HeaderSignatureFilter.VERIFIED_SIGV4_PROPERTY));
            verify(ctx, never()).abortWith(any());
        } else {
            assertFalse(properties.containsKey(S3HeaderSignatureFilter.VERIFIED_SIGV4_PROPERTY));
            if ("forged".equals(kind) || "payload-mismatch".equals(kind)) {
                ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
                verify(ctx).abortWith(response.capture());
                assertEquals("forged".equals(kind) ? 403 : 400, response.getValue().getStatus());
                assertTrue(response.getValue().getEntity().toString().contains("forged".equals(kind)
                        ? "<Code>SignatureDoesNotMatch</Code>" : "<Code>XAmzContentSHA256Mismatch</Code>"));
            } else {
                verify(ctx, never()).abortWith(any());
            }
        }
    }
}
