package io.github.hectorvent.floci.services.apigateway;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)

class RestCustomResourcePolicyIntegrationTest {

    private static final Logger LOG = Logger.getLogger(RestCustomResourcePolicyIntegrationTest.class);

    private static final String OWNER = "000000000000";

    @Inject
    IamService iam;

    @InjectMock
    LambdaService lambda;

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void absentPrincipalFlagDenyPrecedesAuthorizerAndIsRecheckedOnCacheHit() throws Exception {
        Fixture f = fixture("GET");
        authorizer("Allow", f.arn() + "other");
        String condition = ",\"Condition\":{\"Null\":{\"aws:PrincipalIsAWSService\":\"true\"}}";
        policy(f, resourcePolicy("Deny", f.arn(), condition));
        invoke(f, Map.of(), 403);
        verifyNoInteractions(lambda);
        policy(f, resourcePolicy("Allow", f.arn(), condition));
        invoke(f, Map.of("Authorization", "token"), 200);
        policy(f, resourcePolicy("Deny", f.arn(), condition));
        invoke(f, Map.of("Authorization", "token"), 403);
        verify(lambda, times(1)).invokeTarget(anyString(), anyString(), any(byte[].class), any());
        assertEquals(1, f.hits().get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "ANY"})
    void preDenyThenCachedUnionAndRevocation(String routeKey) throws Exception {
        Fixture f = fixture(routeKey);
        authorizer("Allow", f.arn() + "other");
        policy(f, resourcePolicy("Deny", f.arn(), ""));
        invoke(f, Map.of(), 403);
        verifyNoInteractions(lambda);
        policy(f, resourcePolicy("Allow", f.arn(), ""));
        invoke(f, Map.of("Authorization", "token"), 200);
        policy(f, resourcePolicy("Deny", f.arn(), ""));
        invoke(f, Map.of("Authorization", "token"), 403);
        policy(f, "{\"Statement\":[]}");
        invoke(f, Map.of("Authorization", "token"), 403);
        policy(f, resourcePolicy("Allow", f.arn(), ""));
        invoke(f, Map.of("Authorization", "token"), 200);
        verify(lambda, times(1)).invokeTarget(anyString(), anyString(), any(byte[].class), any());
        f.api().flushStageAuthorizersCache(request -> request.restApiId(f.apiId()).stageName("prod"));
        authorizer("Deny", f.arn());
        invoke(f, Map.of("Authorization", "token"), 403);
        verify(lambda, times(2)).invokeTarget(anyString(), anyString(), any(byte[].class), any());
        assertEquals(2, f.hits().get());
    }

    @Test
    void allowResourceDoesNotBypassIdentityOrAuthorizerDeny() throws Exception {
        Fixture f = fixture("GET");
        policy(f, resourcePolicy("Allow", f.arn(), ""));
        invoke(f, Map.of(), 401);
        verifyNoInteractions(lambda);
        authorizer("Deny", f.arn());
        invoke(f, Map.of("Authorization", "token"), 403);
        assertEquals(0, f.hits().get());
    }

    @Test
    void authorizerGrantWorksWithNeutralPolicyAndConditionalDenyStopsCacheHit() throws Exception {
        Fixture f = fixture("GET");
        authorizer("Allow", f.arn());
        policy(f, "{\"Statement\":[]}");
        invoke(f, Map.of("Authorization", "token"), 200);
        policy(f, resourcePolicy("Deny", f.arn(),
                ",\"Condition\":{\"IpAddress\":{\"aws:SourceIp\":\"127.0.0.0/8\"}}"));
        invoke(f, Map.of("Authorization", "token", "X-Forwarded-For", "192.0.2.1"), 403);
        verify(lambda, times(1)).invokeTarget(anyString(), anyString(), any(byte[].class), any());
        assertEquals(1, f.hits().get());
    }

    private void authorizer(String effect, String resource) {
        String payload = "{\"principalId\":\"custom-user\",\"policyDocument\":{\"Statement\":{\"Effect\":\""
                + effect + "\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + resource + "\"}}}";
        when(lambda.invokeTarget(anyString(), anyString(), any(byte[].class), any())).thenReturn(
                new InvokeResult(200, null, payload.getBytes(StandardCharsets.UTF_8), null, "request"));
    }

    private static String resourcePolicy(String effect, String resource, String condition) {
        return "{\"Statement\":{\"Effect\":\"" + effect + "\",\"Principal\":\"*\","
                + "\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + resource + "\"" + condition + "}}";
    }

    private void policy(Fixture fixture, String document) {
        fixture.api().updateRestApi(request -> request.restApiId(fixture.apiId()).patchOperations(patch ->
                patch.op("replace").path("/policy").value(document)));
    }

    private void invoke(Fixture fixture, Map<String, String> headers, int status) {
        given().headers(headers).get("/execute-api/" + fixture.apiId() + "/prod/items").then().statusCode(status);
    }

    private Fixture fixture(String routeKey) throws Exception {
        HttpServer backend = HttpServer.create();
        cleanup.register(() -> backend.stop(0));
        backend.bind(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        backend.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (exchange) {
                exchange.getResponseBody().write(response);
            }
        });
        backend.start();
        LOG.infov("REST route IAM fixture listening on local port {0}", backend.getAddress().getPort());
        ApiGatewayClient api = ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
        cleanup.register(api::close);
        String id = api.createRestApi(request -> request.name("rest-custom-policy-" + UUID.randomUUID())).id();
        cleanup.register(() -> api.deleteRestApi(request -> request.restApiId(id)));
        String root = api.getResources(request -> request.restApiId(id)).items().getFirst().id();
        String resourceId = api.createResource(request -> request.restApiId(id).parentId(root).pathPart("items")).id();
        String auth = api.createAuthorizer(request -> request.restApiId(id).name("auth").type("TOKEN")
                .authorizerUri("arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:"
                        + OWNER + ":function:custom-policy-authorizer/invocations")
                .identitySource("method.request.header.Authorization").authorizerResultTtlInSeconds(300)).id();
        api.putMethod(request -> request.restApiId(id).resourceId(resourceId).httpMethod(routeKey)
                .authorizationType("CUSTOM").authorizerId(auth));
        api.putIntegration(request -> request.restApiId(id).resourceId(resourceId).httpMethod(routeKey)
                .type("HTTP_PROXY").integrationHttpMethod("GET")
                .uri("http://127.0.0.1:" + backend.getAddress().getPort()));
        api.createDeployment(request -> request.restApiId(id).stageName("prod"));
        return new Fixture(id, "arn:aws:execute-api:us-east-1:" + OWNER + ":" + id + "/prod/GET/items", hits, api);
    }

    private record Fixture(String apiId, String arn, AtomicInteger hits, ApiGatewayClient api) { }
}
