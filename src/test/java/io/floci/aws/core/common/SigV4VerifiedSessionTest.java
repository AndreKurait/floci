package io.floci.aws.core.common;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.SessionCredential;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SigV4VerifiedSessionTest {
    private static final String KEY = "ASIAEXAMPLEPROFILE";
    private static final String SECRET = "example-profile-secret";
    private static final String TOKEN = "example-profile-token";
    private static final String TARGET = "CodeDeployCommandService.PollHostCommand";
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final IamService iam = mock(IamService.class);
    private final ResolvedServiceCatalog catalog = mock(ResolvedServiceCatalog.class);
    private final RequestContext context = new RequestContext();
    private final SessionCredential session = new SessionCredential();
    private SigV4HeaderSignatureFilter filter;

    @BeforeEach
    void setup() {
        enableAgent();
        when(config.auth().validateSignatures()).thenReturn(true);
        session.setSecretAccessKey(SECRET);
        session.setSessionToken(TOKEN);
        when(iam.ec2InstanceProfileSession(KEY)).thenReturn(Optional.of(session));
        when(iam.findSecretKey(KEY, TOKEN)).thenReturn(Optional.of(SECRET));
        filter = new SigV4HeaderSignatureFilter(() -> config, iam, catalog, null, context);
        filter.resourceInfo = mock(ResourceInfo.class);
        doReturn(AwsJson11Controller.class).when(filter.resourceInfo).getResourceClass();
    }

    @Test
    void capturesOnlyTheSessionWhoseSignatureVerified() throws Exception {
        ContainerRequestContext request = signed(SECRET, TOKEN, "codedeploy-commands", Instant.now());
        filter.filter(request);
        verify(request, never()).abortWith(any());
        assertTrue(context.isSignatureVerifiedFor(KEY));
        assertFalse(context.isSignatureVerifiedFor("another-key"));
        assertSame(session, context.getVerifiedSession());
    }

    @Test
    void wrongSecretCannotCaptureSession() throws Exception {
        ContainerRequestContext request = signed("wrong-secret", TOKEN, "codedeploy-commands", Instant.now());
        filter.filter(request);
        verify(request).abortWith(any(Response.class));
        assertFalse(context.isSignatureVerifiedFor(KEY));
        assertNull(context.getVerifiedSession());
    }

    @Test
    void wrongTokenCannotCaptureSession() throws Exception {
        ContainerRequestContext request = signed(SECRET, "wrong-token", "codedeploy-commands", Instant.now());
        filter.filter(request);
        verify(request).abortWith(any(Response.class));
        assertNull(context.getVerifiedSession());
    }

    @Test
    void expiredOrRetiredCredentialCannotCaptureSession() throws Exception {
        when(iam.findSecretKey(KEY, TOKEN)).thenReturn(Optional.empty());
        when(iam.ec2InstanceProfileSession(KEY)).thenReturn(Optional.empty());
        ContainerRequestContext request = signed(SECRET, TOKEN, "codedeploy-commands", Instant.now());
        filter.filter(request);
        verify(request).abortWith(any(Response.class));
        assertFalse(context.isSignatureVerifiedFor(KEY));
        assertNull(context.getVerifiedSession());
    }

    @Test
    void signatureExpiryPreventsCapture() throws Exception {
        ContainerRequestContext request = signed(SECRET, TOKEN, "codedeploy-commands", Instant.now().minusSeconds(3600));
        filter.filter(request);
        verify(request).abortWith(any(Response.class));
        assertNull(context.getVerifiedSession());
    }

    @Test
    void changedSecretInvalidatesCapturedSession() throws Exception {
        filter.filter(signed(SECRET, TOKEN, "codedeploy-commands", Instant.now()));
        assertSame(session, context.getVerifiedSession());
        session.setSecretAccessKey("rotated-secret");
        assertNull(context.getVerifiedSession());
    }

    @Test
    void changedTokenInvalidatesCapturedSession() throws Exception {
        filter.filter(signed(SECRET, TOKEN, "codedeploy-commands", Instant.now()));
        assertSame(session, context.getVerifiedSession());
        session.setSessionToken("rotated-token");
        assertNull(context.getVerifiedSession());
    }

    @Test
    void sessionLookupRacingCredentialRotationCannotCaptureReplacement() throws Exception {
        session.setSecretAccessKey("replacement-secret");
        filter.filter(signed(SECRET, TOKEN, "codedeploy-commands", Instant.now()));
        assertTrue(context.isSignatureVerifiedFor(KEY));
        assertNull(context.getVerifiedSession());
    }

    @Test
    void ordinaryRoleSessionDoesNotAcquireInstanceProvenance() throws Exception {
        when(iam.ec2InstanceProfileSession(KEY)).thenReturn(Optional.empty());
        filter.filter(signed(SECRET, TOKEN, "codedeploy-commands", Instant.now()));
        assertTrue(context.isSignatureVerifiedFor(KEY));
        assertNull(context.getVerifiedSession());
    }

    @Test
    void disabledVerificationClearsPriorCapture() throws Exception {
        filter.filter(signed(SECRET, TOKEN, "codedeploy-commands", Instant.now()));
        assertSame(session, context.getVerifiedSession());
        when(config.auth().validateSignatures()).thenReturn(false);
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        ContainerRequestContext next = mock(ContainerRequestContext.class);
        filter.filter(next);
        assertFalse(context.isSignatureVerifiedFor(KEY));
        assertNull(context.getVerifiedSession());
        verifyNoInteractions(next);
    }

    @Test
    void defaultAgentStillVerifiesWhenGlobalFlagIsDisabled() throws Exception {
        enableAgent();
        ContainerRequestContext request = signed(SECRET, TOKEN, "codedeploy-commands", Instant.now());
        filter.filter(request);
        verify(request, never()).abortWith(any());
        assertSame(session, context.getVerifiedSession());
    }

    @Test
    void unsignedDefaultAgentIsRejected() throws Exception {
        enableAgent();
        ContainerRequestContext request = unsigned();
        filter.filter(request);
        verify(request).abortWith(any(Response.class));
        assertNull(context.getVerifiedSession());
    }

    @Test
    void defaultAgentRejectsAnotherSigningService() throws Exception {
        enableAgent();
        ContainerRequestContext request = signed(SECRET, TOKEN, "sts", Instant.now());
        filter.filter(request);
        verify(request).abortWith(any(Response.class));
        assertFalse(context.isSignatureVerifiedFor(KEY));
    }

    @Test
    void unsignedRequestClearsPriorSignatureAndSession() throws Exception {
        filter.filter(signed(SECRET, TOKEN, "codedeploy-commands", Instant.now()));
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        filter.filter(unsigned());
        assertFalse(context.isSignatureVerifiedFor(KEY));
        assertNull(context.getVerifiedSession());
    }

    private void enableAgent() {
        when(config.auth().validateSignatures()).thenReturn(false);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        ServiceDescriptor descriptor = new ServiceDescriptor("codedeploy-commands", "codedeploy", true, false,
                null, null, 0L, null, ServiceProtocol.JSON, Set.of(ServiceProtocol.JSON),
                Set.of("CodeDeployCommandService."), Set.of("codedeploy-commands"), Set.of(), Set.of(), Set.of());
        when(catalog.matchTarget(TARGET)).thenReturn(Optional.of(new ServiceCatalog.TargetMatch(
                descriptor, "CodeDeployCommandService", "PollHostCommand")));
    }

    private ContainerRequestContext unsigned() {
        return request(new MultivaluedHashMap<>());
    }

    private ContainerRequestContext signed(String secret, String token, String service, Instant now) {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        SdkHttpFullRequest signed = Aws4Signer.create().sign(SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST).uri(URI.create("http://localhost:4566/"))
                .putHeader("Content-Type", "application/x-amz-json-1.1").putHeader("X-Amz-Target", TARGET)
                .contentStreamProvider(() -> new ByteArrayInputStream(body)).build(),
                Aws4SignerParams.builder().awsCredentials(AwsSessionCredentials.create(KEY, secret, token))
                        .signingName(service).signingRegion(Region.US_EAST_1)
                        .signingClockOverride(Clock.fixed(now, ZoneOffset.UTC)).build());
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        signed.headers().forEach(headers::put);
        return request(headers);
    }

    private ContainerRequestContext request(MultivaluedMap<String, String> headers) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost:4566/"));
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getHeaderString(anyString())).thenAnswer(call -> {
            String name = call.getArgument(0);
            if (name.equalsIgnoreCase("X-Amz-Target")) {
                return TARGET;
            }
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> String.join(",", entry.getValue())).findFirst().orElse(null);
        });
        when(request.getEntityStream()).thenReturn(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        return request;
    }
}
