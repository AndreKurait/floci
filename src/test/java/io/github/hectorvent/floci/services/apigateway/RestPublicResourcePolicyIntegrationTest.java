package io.github.hectorvent.floci.services.apigateway;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class RestPublicResourcePolicyIntegrationTest {

    private static final Logger LOG = Logger.getLogger(RestPublicResourcePolicyIntegrationTest.class);

    private static final String OWNER = "000000000000";

    @Inject
    IamService iam;

    @Inject
    CognitoService cognito;

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void unsignedPrincipalFlagIsAbsentAndPolicyRevocationStopsBackend() throws Exception {
        Fixture fixture = fixture("GET");
        policy(fixture, resourcePolicy("Allow", fixture.arn(),
                ",\"Condition\":{\"Bool\":{\"aws:PrincipalIsAWSService\":\"false\"}}"));
        invoke(fixture, Map.of(), 403);
        policy(fixture, resourcePolicy("Allow", fixture.arn(),
                ",\"Condition\":{\"Null\":{\"aws:PrincipalIsAWSService\":\"true\"}}"));
        invoke(fixture, Map.of(), 200);
        policy(fixture, resourcePolicy("Deny", fixture.arn(),
                ",\"Condition\":{\"Null\":{\"aws:PrincipalIsAWSService\":\"true\"}}"));
        invoke(fixture, Map.of(), 403);
        assertEquals(1, fixture.hits().get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "ANY"})
    void unsignedRequestNeedsMatchingPolicyAndRevocationStopsBackend(String routeKey) throws Exception {
        Fixture fixture = fixture(routeKey);
        invoke(fixture, Map.of(), 200);
        policy(fixture, "{\"Statement\":[]}");
        invoke(fixture, Map.of(), 403);
        String allow = resourcePolicy("Allow", "execute-api:/prod/GET/items", "");
        policy(fixture, allow);
        invoke(fixture, Map.of(), 200);
        policy(fixture, allow.replace("Allow", "Deny"));
        invoke(fixture, Map.of(), 403);
        assertEquals(2, fixture.hits().get());
    }

    @Test
    void sourceIpComesFromConnectionNotForwardedHeader() throws Exception {
        Fixture fixture = fixture("GET");
        policy(fixture, resourcePolicy("Allow", fixture.arn(),
                ",\"Condition\":{\"IpAddress\":{\"aws:SourceIp\":\"192.0.2.0/24\"}}"));
        invoke(fixture, Map.of("X-Forwarded-For", "192.0.2.42"), 403);
        policy(fixture, resourcePolicy("Allow", fixture.arn(),
                ",\"Condition\":{\"IpAddress\":{\"aws:SourceIp\":\"127.0.0.0/8\"}}"));
        invoke(fixture, Map.of("X-Forwarded-For", "192.0.2.42"), 200);
        assertEquals(1, fixture.hits().get());
    }

    @Test
    void signedHeadersDoNotTurnNoneRouteIntoIamAuthentication() throws Exception {
        Fixture fixture = fixture("GET");
        policy(fixture, resourcePolicy("Allow", fixture.arn(), "")
                .replace("\"Principal\":\"*\"", "\"Principal\":{\"AWS\":\"arn:aws:iam::" + OWNER + ":root\"}"));
        String path = "/execute-api/" + fixture.apiId() + "/prod/items";
        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders("GET", path, Map.of(),
                endpoint.getHost() + ":" + endpoint.getPort(), new byte[0], "test", "test", "us-east-1", Instant.now());
        invoke(fixture, signed, 403);
        assertEquals(0, fixture.hits().get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifiedCognitoTokenStillNeedsResourceGrant() throws Exception {
        Fixture fixture = fixture("GET");
        UserPool pool = cognito.createUserPool(Map.of("PoolName", "rest-policy-" + UUID.randomUUID()), "us-east-1");
        cleanup.register(() -> cognito.deleteUserPool(pool.getId()));
        String username = "user-" + UUID.randomUUID();
        cognito.adminCreateUser(pool.getId(), username, Map.of("email", "caller@example.com"), "TempPass1!");
        cognito.adminSetUserPassword(pool.getId(), username, "Perm1234!", true);
        UserPoolClient client = cognito.createUserPoolClient(pool.getId(), "test-client", false, false,
                List.of(), List.of(), null, List.of(), null,
                List.of("ALLOW_USER_PASSWORD_AUTH"), null, null, List.of(), null, List.of(), null,
                null, null, List.of(), null, null);
        Map<String, Object> result = cognito.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", username, "PASSWORD", "Perm1234!"));
        Map<String, Object> tokens = (Map<String, Object>) result.get("AuthenticationResult");
        String token = (String) tokens.get("IdToken");
        String authorizer = fixture.api().createAuthorizer(request -> request.restApiId(fixture.apiId())
                .name("pool").type("COGNITO_USER_POOLS").providerARNs(pool.getArn())
                .identitySource("method.request.header.Authorization")).id();
        String resource = fixture.api().getResources(request -> request.restApiId(fixture.apiId()))
                .items().stream().filter(item -> "/items".equals(item.path())).findFirst().orElseThrow().id();
        fixture.api().updateMethod(request -> request.restApiId(fixture.apiId()).resourceId(resource).httpMethod("GET")
                .patchOperations(patch -> patch.op("replace").path("/authorizationType").value("COGNITO_USER_POOLS"),
                        patch -> patch.op("replace").path("/authorizerId").value(authorizer)));
        fixture.api().createDeployment(request -> request.restApiId(fixture.apiId()).stageName("prod"));
        policy(fixture, resourcePolicy("Deny", fixture.arn(), ""));
        invoke(fixture, Map.of(), 401);
        invoke(fixture, Map.of("Authorization", "Bearer " + token), 403);
        policy(fixture, "{\"Statement\":[]}");
        invoke(fixture, Map.of("Authorization", "Bearer " + token), 403);
        policy(fixture, resourcePolicy("Allow", fixture.arn(), ""));
        invoke(fixture, Map.of("Authorization", "Bearer " + token), 200);
        cognito.globalSignOut((String) tokens.get("AccessToken"));
        invoke(fixture, Map.of("Authorization", "Bearer " + token), 401);
        assertEquals(1, fixture.hits().get());
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
        String id = api.createRestApi(request -> request.name("rest-public-policy-" + UUID.randomUUID())).id();
        cleanup.register(() -> api.deleteRestApi(request -> request.restApiId(id)));
        String root = api.getResources(request -> request.restApiId(id)).items().getFirst().id();
        String resourceId = api.createResource(request -> request.restApiId(id).parentId(root).pathPart("items")).id();
        api.putMethod(request -> request.restApiId(id).resourceId(resourceId).httpMethod(routeKey)
                .authorizationType("NONE"));
        api.putIntegration(request -> request.restApiId(id).resourceId(resourceId).httpMethod(routeKey)
                .type("HTTP_PROXY").integrationHttpMethod("GET")
                .uri("http://127.0.0.1:" + backend.getAddress().getPort()));
        api.createDeployment(request -> request.restApiId(id).stageName("prod"));
        return new Fixture(id, "arn:aws:execute-api:us-east-1:" + OWNER + ":" + id + "/prod/GET/items", hits, api);
    }

    private record Fixture(String apiId, String arn, AtomicInteger hits, ApiGatewayClient api) { }
}
