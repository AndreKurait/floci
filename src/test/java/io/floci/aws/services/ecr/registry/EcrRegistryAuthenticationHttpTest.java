package io.floci.aws.services.ecr.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ecr.EcrService;
import io.floci.aws.services.ecr.model.Repository;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.testing.ParallelLocks;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class EcrRegistryAuthenticationHttpTest {
    private Vertx vertx;
    private HttpServer frontend;
    private EcrRegistryManager manager;
    private EcrRegistryAuthorization auth;
    private EmulatorConfig config;
    private IamService iam;
    private final AtomicInteger backendRequests = new AtomicInteger();
    private final AtomicReference<String> forwardedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> forwardedUri = new AtomicReference<>();
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"ecr:*\",\"Resource\":\"*\"}}";

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        HttpServer backend = vertx.createHttpServer().requestHandler(r -> {
            backendRequests.incrementAndGet();
            forwardedAuthorization.set(r.getHeader("Authorization"));
            forwardedUri.set(r.uri());
            r.body().onSuccess(body -> r.response().setStatusCode(200).end("{}"));
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        manager = mock(EcrRegistryManager.class);
        when(manager.tryAcquireWriteAdmission()).thenReturn(true);
        when(manager.httpClient()).thenReturn(new RegistryHttpClient("http://127.0.0.1:" + backend.actualPort()));
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecr().enabled()).thenReturn(true);
        when(config.services().ecr().uriStyle()).thenReturn("hostname");
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        when(config.defaultAccountId()).thenReturn("111111111111");
        RequestContext request = mock(RequestContext.class);
        when(request.getAccessKeyId()).thenReturn("ISSUED");
        when(request.isSignatureVerifiedFor("ISSUED")).thenReturn(true);
        iam = mock(IamService.class);
        String arn = "arn:aws:iam::111111111111:user/publisher";
        when(iam.resolveCallerArns("ISSUED")).thenReturn(Optional.of(new IamService.CallerArns(
                arn, arn, "User", "AIDAISSUED", "publisher", false, Map.of())));
        when(iam.resolveCallerContext("ISSUED")).thenReturn(CallerContext.of(List.of(ALLOW)));
        when(iam.resolveCallerContext(eq("ISSUED"), any())).thenReturn(CallerContext.of(List.of(ALLOW)));
        auth = new EcrRegistryAuthorization(AccountAwareStorageBackend.inMemory("111111111111"),
                iam, new IamPolicyEvaluator(new ObjectMapper()), request, config, Clock.systemUTC());
        EcrService ecr = mock(EcrService.class);
        when(ecr.registryRepositoryName(anyString(), anyString(), anyString())).thenAnswer(call ->
                call.getArgument(1) + "/" + call.getArgument(2) + "/" + call.getArgument(0));
        when(ecr.tryLockRepositoryWrites(anyString())).thenReturn(true);
        when(ecr.registryRepository(anyString(), anyString(), anyString())).thenAnswer(call -> {
            Repository repository = new Repository();
            repository.setRepositoryName(call.getArgument(0));
            return Optional.of(repository);
        });
        EcrRegistryDataPlane plane = new EcrRegistryDataPlane(manager, ecr, config, vertx);
        plane.authorization = auth;
        Router router = Router.router(vertx);
        plane.register(router);
        frontend = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private int request(HttpMethod method, String path, String token) throws Exception {
        return request(method, path, token, "111111111111.dkr.ecr.us-east-1.localhost:4566");
    }

    private int request(HttpMethod method, String path, String token, String host) throws Exception {
        return requestToHost(method, path, token, host);
    }

    private int requestToHost(HttpMethod method, String path, String token, String host) throws Exception {
        HttpClient client = vertx.createHttpClient();
        try {
            HttpClientRequest req = client.request(new RequestOptions().setHost("127.0.0.1").setPort(frontend.actualPort())
                    .setMethod(method).setURI(path)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            req.putHeader("Host", host);
            if (token != null) {
                req.putHeader("Authorization", token);
            }
            HttpClientResponse response = req.send(Buffer.buffer()).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            response.body().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            if (response.statusCode() == 401) {
                assertTrue(response.getHeader("WWW-Authenticate").startsWith("Basic "));
            }
            return response.statusCode();
        } finally {
            client.close();
        }
    }

    @Test
    void anonymousAndWrongTokenCannotStartRegistryOrForwardBodies() throws Exception {
        assertEquals(401, request(HttpMethod.GET, "/v2/", null));
        assertEquals(401, request(HttpMethod.PUT, "/v2/app/manifests/latest", "Basic QVdTOmZsb2Np"));
        assertEquals(0, backendRequests.get());
        verifyNoInteractions(manager);
    }

    @Test
    void malformedQueryNamesRetainTheLoginChallenge() throws Exception {
        for (String name : List.of("%zz", "%", "%2")) {
            assertEquals(401, requestToHost(HttpMethod.GET, "/v2/?" + name + "=1", null, "localhost:4566"));
        }
        assertEquals(0, backendRequests.get());
        verifyNoInteractions(manager);
    }

    @Test
    void malformedNamespaceValuesRetainTheLoginChallenge() throws Exception {
        for (String value : List.of("%zz", "%", "%2")) {
            assertEquals(401, requestToHost(HttpMethod.GET, "/v2/?ns=" + value, null, "localhost:4566"));
        }
        assertEquals(0, backendRequests.get());
        verifyNoInteractions(manager);
    }

    @Test
    void malformedQueryNamesDoNotBreakIamOffRequests() throws Exception {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        for (String name : List.of("%zz", "%", "%2")) {
            assertEquals(200, requestToHost(HttpMethod.GET, "/v2/?" + name + "=1", null, "localhost:4566"));
        }
        assertEquals(3, backendRequests.get());
    }

    @Test
    void malformedMountQueryNamesDoNotBreakAuthorizedUploads() throws Exception {
        String token = "Basic " + auth.issue("us-east-1").authorizationToken();
        for (String name : List.of("%zz", "%", "%2")) {
            assertEquals(200, request(HttpMethod.POST,
                    "/v2/app/blobs/uploads/?" + name + "=1&from=source&mount=sha256%3Aabc", token));
            assertEquals("/v2/111111111111/us-east-1/app/blobs/uploads/"
                    + "?from=111111111111%2Fus-east-1%2Fsource&mount=sha256%3Aabc", forwardedUri.get());
        }
        assertEquals(3, backendRequests.get());
        assertNull(forwardedAuthorization.get());
    }

    @Test
    void encodedNamespaceNameStillSelectsTheRegistry() throws Exception {
        String token = "Basic " + auth.issue("us-east-1").authorizationToken();
        assertEquals(200, requestToHost(HttpMethod.GET,
                "/v2/app/manifests/latest?%6es=111111111111.dkr.ecr.us-east-1.localhost",
                token, "localhost:4566"));
        assertEquals(1, backendRequests.get());
        assertNull(forwardedAuthorization.get());
    }

    @Test
    void encodedMountNamesStillUseTheAuthorizedStorageNamespace() throws Exception {
        String token = "Basic " + auth.issue("us-east-1").authorizationToken();
        assertEquals(200, request(HttpMethod.POST,
                "/v2/app/blobs/uploads/?%66rom=source&%6dount=sha256%3Aabc", token));
        assertEquals("/v2/111111111111/us-east-1/app/blobs/uploads/"
                + "?from=111111111111%2Fus-east-1%2Fsource&mount=sha256%3Aabc", forwardedUri.get());
        assertNull(forwardedAuthorization.get());
    }

    @Test
    void manifestDeletionIsUnsupportedBeforeBackendInEnforcedMode() throws Exception {
        String token = "Basic " + auth.issue("us-east-1").authorizationToken();
        assertEquals(403, request(HttpMethod.DELETE, "/v2/app/manifests/latest", token));
        assertEquals(0, backendRequests.get());
        verifyNoInteractions(manager);
    }

    @Test
    void uploadStatusAndCancellationAreUnsupportedBeforeBackend() throws Exception {
        String token = "Basic " + auth.issue("us-east-1").authorizationToken();
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.DELETE)) {
            assertEquals(403, request(method, "/v2/app/blobs/uploads/id", token));
        }
        assertEquals(0, backendRequests.get());
        verifyNoInteractions(manager);
    }

    @Test
    void issuedTokenPermitsOrdinaryUploadPostAndIsNeverForwarded() throws Exception {
        String token = "Basic " + auth.issue("us-east-1").authorizationToken();
        assertEquals(200, request(HttpMethod.GET, "/v2/", token));
        assertEquals(200, request(HttpMethod.POST, "/v2/app/blobs/uploads/", token));
        assertEquals("/v2/111111111111/us-east-1/app/blobs/uploads/", forwardedUri.get());
        assertNull(forwardedAuthorization.get());
    }

    @Test
    void crossRegionPasswordCannotReachRegistryButRemainsUsableInItsRegion() throws Exception {
        String token = "Basic " + auth.issue("us-east-1").authorizationToken();
        assertEquals(401, request(HttpMethod.GET, "/v2/", token, "111111111111.dkr.ecr.us-west-2.localhost:4566"));
        assertEquals(401, request(HttpMethod.PUT, "/v2/app/manifests/latest", token, "111111111111.dkr.ecr.us-west-2.localhost:4566"));
        assertEquals(0, backendRequests.get());
        verifyNoInteractions(manager);
        assertEquals(200, request(HttpMethod.GET, "/v2/", token));
        assertNull(forwardedAuthorization.get());
    }

    @Test
    void pathStyleLoginAcceptsRegionalPasswordWithoutWeakeningRepositoryScope() throws Exception {
        when(config.services().ecr().uriStyle()).thenReturn("path");
        String token = "Basic " + auth.issue("us-west-2").authorizationToken();
        String host = "localhost.floci.io:4566";
        assertEquals(200, requestToHost(HttpMethod.GET, "/v2/", token, host));
        assertEquals(200, requestToHost(HttpMethod.GET, "/v2", token, host));
        assertNull(forwardedAuthorization.get());
        assertEquals(2, backendRequests.get());
        assertEquals(401, requestToHost(HttpMethod.PUT,
                "/v2/111111111111/us-east-1/app/manifests/latest", token, host));
        assertEquals(401, requestToHost(HttpMethod.POST, "/v2/", token, host));
        assertEquals(2, backendRequests.get());
        assertEquals(200, requestToHost(HttpMethod.GET,
                "/v2/111111111111/us-west-2/app/manifests/latest", token, host));
        assertNull(forwardedAuthorization.get());
    }

    @Test
    void pathStyleLoginStillRejectsMissingWrongAndRevokedPasswords() throws Exception {
        when(config.services().ecr().uriStyle()).thenReturn("path");
        String host = "localhost.floci.io:4566";
        assertEquals(401, requestToHost(HttpMethod.GET, "/v2/", null, host));
        assertEquals(401, requestToHost(HttpMethod.GET, "/v2/", "Basic QVdTOmZsb2Np", host));
        String token = "Basic " + auth.issue("us-west-2").authorizationToken();
        when(iam.isInactiveAccessKey("ISSUED")).thenReturn(true);
        assertEquals(401, requestToHost(HttpMethod.GET, "/v2/", token, host));
        assertEquals(0, backendRequests.get());
        verifyNoInteractions(manager);
    }

    @Test
    void currentExplicitDenyBlocksManifestMutationBeforeBackend() throws Exception {
        String token = "Basic " + auth.issue("us-east-1").authorizationToken();
        when(iam.resolveCallerContext(eq("ISSUED"), any())).thenReturn(CallerContext.of(List.of(
                "{\"Statement\":{\"Effect\":\"Deny\",\"Action\":\"ecr:PutImage\",\"Resource\":\"*\"}}")));
        assertEquals(403, request(HttpMethod.PUT, "/v2/app/manifests/latest", token));
        assertEquals(0, backendRequests.get());
        verifyNoInteractions(manager);
    }
    @Test
    void optionalMountDeniedSourceFallsBackToAuthorizedOrdinaryUpload() throws Exception {
        String token = "Basic " + auth.issue("us-east-1").authorizationToken();
        when(iam.resolveCallerContext(eq("ISSUED"), any())).thenReturn(CallerContext.of(List.of(
                "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"ecr:InitiateLayerUpload\",\"Resource\":\"*\"},"
                        + "{\"Effect\":\"Deny\",\"Action\":\"ecr:GetDownloadUrlForLayer\",\"Resource\":\"*\"}]}")));
        assertEquals(200, request(HttpMethod.POST, "/v2/app/blobs/uploads/?mount=sha256:abc&from=source", token));
        assertEquals("/v2/111111111111/us-east-1/app/blobs/uploads/", forwardedUri.get());
        assertEquals(200, request(HttpMethod.POST, "/v2/app/blobs/uploads/?%6dount=sha256:abc&%66rom=source", token));
        assertEquals("/v2/111111111111/us-east-1/app/blobs/uploads/", forwardedUri.get());
    }

    @Test
    void permittedMountUsesTheExactAuthorizedSourceStorageNamespace() throws Exception {
        String token = "Basic " + auth.issue("us-east-1").authorizationToken();
        assertEquals(200, request(HttpMethod.POST, "/v2/app/blobs/uploads/?mount=sha256:abc&from=team/source", token));
        assertTrue(forwardedUri.get().contains("from=111111111111%2Fus-east-1%2Fteam%2Fsource"));
        assertNull(forwardedAuthorization.get());
    }

    @Test
    void authorizedMountKeepsItsSourceAfterCatalogRecovery() throws Exception {
        when(manager.isCatalogReconciled()).thenReturn(false);
        String token = "Basic " + auth.issue().authorizationToken();
        assertEquals(200, request(HttpMethod.POST,
                "/v2/app/blobs/uploads/?mount=sha256:abc&from=team/source", token));
        assertTrue(forwardedUri.get().contains("from=111111111111%2Fus-east-1%2Fteam%2Fsource"));
        verify(manager).ensureCatalogReconciled();
        assertNull(forwardedAuthorization.get());
    }

    @Test
    void awsMirrorNamespaceStillChecksTheIssuedCallersCurrentPermission() throws Exception {
        String token = "Basic " + auth.issue().authorizationToken();
        String path = "/v2/app/manifests/latest?ns=111111111111.dkr.ecr.us-east-1.amazonaws.com";
        assertEquals(200, request(HttpMethod.GET, path, token, "floci:4566"));
        assertEquals("/v2/111111111111/us-east-1/app/manifests/latest", forwardedUri.get());
        assertNull(forwardedAuthorization.get());
        int accepted = backendRequests.get();
        clearInvocations(manager);
        when(iam.resolveCallerContext(eq("ISSUED"), any())).thenReturn(CallerContext.of(List.of(
                "{\"Statement\":{\"Effect\":\"Deny\",\"Action\":\"ecr:BatchGetImage\",\"Resource\":\"*\"}}")));
        assertEquals(403, request(HttpMethod.GET, path, token, "floci:4566"));
        assertEquals(accepted, backendRequests.get());
        verifyNoInteractions(manager);
    }

}
