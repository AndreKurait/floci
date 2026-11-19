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

class EcrRegistryAuthenticationHttpTest {
    private Vertx vertx;
    private HttpServer frontend;
    private EcrRegistryManager manager;
    private EcrRegistryAuthorization auth;
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
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
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
        return request(method, path, token, "us-east-1");
    }

    private int request(HttpMethod method, String path, String token, String region) throws Exception {
        HttpClient client = vertx.createHttpClient();
        try {
            HttpClientRequest req = client.request(new RequestOptions().setHost("127.0.0.1").setPort(frontend.actualPort())
                    .setMethod(method).setURI(path)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            req.putHeader("Host", "111111111111.dkr.ecr." + region + ".localhost:4566");
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
        assertEquals(401, request(HttpMethod.GET, "/v2/", token, "us-west-2"));
        assertEquals(401, request(HttpMethod.PUT, "/v2/app/manifests/latest", token, "us-west-2"));
        assertEquals(0, backendRequests.get());
        verifyNoInteractions(manager);
        assertEquals(200, request(HttpMethod.GET, "/v2/", token));
        assertNull(forwardedAuthorization.get());
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

}
