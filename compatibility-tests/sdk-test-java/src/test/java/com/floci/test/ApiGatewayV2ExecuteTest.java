package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.AuthorizerType;
import software.amazon.awssdk.services.apigatewayv2.model.CreateApiRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateApiResponse;
import software.amazon.awssdk.services.apigatewayv2.model.CreateAuthorizerRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateAuthorizerResponse;
import software.amazon.awssdk.services.apigatewayv2.model.CreateDeploymentRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateIntegrationRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateIntegrationResponse;
import software.amazon.awssdk.services.apigatewayv2.model.CreateRouteRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateStageRequest;
import software.amazon.awssdk.services.apigatewayv2.model.DeleteApiRequest;
import software.amazon.awssdk.services.apigatewayv2.model.IntegrationType;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("API Gateway V2 Execute")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2ExecuteTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    private static final String STAGE = "prod";

    private static ApiGatewayV2Client apiGatewayV2;
    private static LambdaClient lambda;
    private static HttpClient http;

    private static String apiId;
    private static String functionName;
    private static String baseUrl;
    private static boolean lambdaDispatchAvailable;
    private static HttpServer issuerServer;
    private static String issuer;
    private static PrivateKey signingKey;
    private static final AtomicInteger jwksRequests = new AtomicInteger();
    private static final String VERIFIED_RESPONSE = UUID.randomUUID().toString();
    // Per run, so a Floci that cached an earlier run's JWKS for a reused port must refetch.
    private static final String KEY_ID = "sdk-test-" + UUID.randomUUID();

    @BeforeAll
    static void setup() throws Exception {
        apiGatewayV2 = TestFixtures.apiGatewayV2Client();
        lambda = TestFixtures.lambdaClient();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        startIssuer();
        functionName = TestFixtures.uniqueName("http-api-fn");

        try {
            String functionArn = lambda.createFunction(CreateFunctionRequest.builder()
                    .functionName(functionName)
                    .runtime(Runtime.NODEJS20_X)
                    .role(ROLE)
                    .handler("index.handler")
                    .timeout(30)
                    .memorySize(256)
                    .code(FunctionCode.builder()
                            .zipFile(SdkBytes.fromByteArray(v2EchoHandlerZip()))
                            .build())
                    .build()).functionArn();

            CreateApiResponse api = apiGatewayV2.createApi(CreateApiRequest.builder()
                    .name(TestFixtures.uniqueName("http-api"))
                    .protocolType("HTTP")
                    .build());
            apiId = api.apiId();

            CreateIntegrationResponse integration = apiGatewayV2.createIntegration(CreateIntegrationRequest.builder()
                    .apiId(apiId)
                    .integrationType(IntegrationType.AWS_PROXY)
                    .integrationUri(functionArn)
                    .payloadFormatVersion("2.0")
                    .build());

            CreateAuthorizerResponse authorizer = apiGatewayV2.createAuthorizer(CreateAuthorizerRequest.builder()
                    .apiId(apiId)
                    .name("jwt-auth")
                    .authorizerType(AuthorizerType.JWT)
                    .identitySource("$request.header.Authorization")
                    .jwtConfiguration(b -> b
                            .issuer(issuer)
                            .audience("my-audience"))
                    .build());

            apiGatewayV2.createRoute(CreateRouteRequest.builder()
                    .apiId(apiId)
                    .routeKey("POST /echo/{proxy+}")
                    .target("integrations/" + integration.integrationId())
                    .build());

            // Signature verification has its own positive path even when Lambda is unavailable.
            CreateIntegrationResponse verified = apiGatewayV2.createIntegration(CreateIntegrationRequest.builder()
                    .apiId(apiId)
                    .integrationType(IntegrationType.HTTP_PROXY)
                    .integrationMethod("GET")
                    .integrationUri(issuer + "/verified")
                    .payloadFormatVersion("1.0")
                    .build());
            apiGatewayV2.createRoute(CreateRouteRequest.builder()
                    .apiId(apiId)
                    .routeKey("GET /verify")
                    .authorizationType("JWT")
                    .authorizerId(authorizer.authorizerId())
                    .target("integrations/" + verified.integrationId())
                    .build());

            apiGatewayV2.createRoute(CreateRouteRequest.builder()
                    .apiId(apiId)
                    .routeKey("GET /secure")
                    .authorizationType("JWT")
                    .authorizerId(authorizer.authorizerId())
                    .target("integrations/" + integration.integrationId())
                    .build());

            String deploymentId = apiGatewayV2.createDeployment(CreateDeploymentRequest.builder()
                    .apiId(apiId)
                    .build()).deploymentId();

            apiGatewayV2.createStage(CreateStageRequest.builder()
                    .apiId(apiId)
                    .stageName(STAGE)
                    .deploymentId(deploymentId)
                    .autoDeploy(false)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Could not configure the HTTP API execution fixture", e);
        }

        baseUrl = TestFixtures.endpoint() + "/execute-api/" + apiId + "/" + STAGE;

        // Probe result is memoized in TestFixtures; skip warmup if dispatch is unavailable
        if (!TestFixtures.isLambdaDispatchAvailable()) {
            lambdaDispatchAvailable = false;
            return;
        }

        // Warm the API GW → Lambda dispatch path and verify the response carries
        // the event returned as the Lambda proxy response body. The direct
        // probe above only tests raw invoke; APIGW integration may behave differently.
        try {
            HttpResponse<String> warmup = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/echo/warmup"))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(warmup.statusCode()).as("HTTP API warmup response: %s", warmup.body()).isEqualTo(200);
            JsonNode warmupBody = JSON.readTree(warmup.body());
            assertThat(warmupBody.path("version").asText()).isEqualTo("2.0");
            lambdaDispatchAvailable = true;
        } catch (HttpTimeoutException | ConnectException e) {
            // Transport-level failure: endpoint unreachable or timed out
            lambdaDispatchAvailable = false;
        }
    }

    @AfterAll
    static void cleanup() {
        if (issuerServer != null) {
            issuerServer.stop(0);
        }
        if (apiGatewayV2 != null) {
            if (apiId != null) {
                try {
                    apiGatewayV2.deleteApi(DeleteApiRequest.builder().apiId(apiId).build());
                } catch (Exception ignored) {
                    // A failed setup can leave a partially created or already removed API.
                }
            }
            apiGatewayV2.close();
        }
        if (lambda != null) {
            if (functionName != null) {
                try {
                    lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(functionName).build());
                } catch (Exception ignored) {
                    // Keep a setup or test failure as the primary failure during cleanup.
                }
            }
            lambda.close();
        }
    }

    @Test
    @Order(1)
    void dispatchesHttpApiRouteToLambdaWithV2EventShape() throws Exception {
        Assumptions.assumeTrue(lambdaDispatchAvailable,
                "Lambda dispatch unavailable in this environment");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/echo/child/path?color=blue"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-Test-Header", "hello")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hi\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode event = JSON.readTree(response.body());

        assertThat(event.path("version").asText()).isEqualTo("2.0");
        assertThat(event.path("routeKey").asText()).isEqualTo("POST /echo/{proxy+}");
        assertThat(event.path("rawPath").asText()).isEqualTo("/echo/child/path");
        assertThat(event.path("requestContext").path("stage").asText()).isEqualTo(STAGE);
        assertThat(event.path("requestContext").path("http").path("method").asText()).isEqualTo("POST");
        assertThat(event.path("headers").path("x-test-header").asText()).isEqualTo("hello");
        assertThat(event.path("queryStringParameters").path("color").asText()).isEqualTo("blue");
        assertThat(event.path("body").asText()).isEqualTo("{\"message\":\"hi\"}");
    }

    @Test
    @Order(2)
    void jwtProtectedRouteRejectsMissingToken() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/secure"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Unauthorized");
    }

    @Test
    @Order(3)
    void jwtSignatureVerificationDoesNotDependOnLambda() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/verify"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + jwt(issuer, "my-audience",
                                Instant.now().plusSeconds(300).getEpochSecond()))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("Floci must reach the JWT fixture at %s. Start Floci with "
                                + "FLOCI_SECURITY_ALLOW_PRIVATE_JWT_TARGETS=true; if it runs in a container or on "
                                + "another host, set FLOCI_TEST_JWT_ISSUER_BIND_ADDRESS and "
                                + "FLOCI_TEST_JWT_ISSUER_HOST for this suite: %s",
                        issuer, response.body())
                .isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("verified").asText()).isEqualTo(VERIFIED_RESPONSE);
        assertThat(jwksRequests.get()).as("Floci must fetch the fixture's public key").isPositive();
    }

    @Test
    @Order(4)
    void jwtProtectedRouteRejectsWrongAudience() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/verify"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + jwt(issuer, "wrong-audience",
                                Instant.now().plusSeconds(300).getEpochSecond()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Unauthorized");
    }

    @Test
    @Order(5)
    void jwtProtectedRouteRejectsWrongSignature() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PrivateKey wrongKey = generator.generateKeyPair().getPrivate();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/verify"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + jwt(issuer, "my-audience",
                                Instant.now().plusSeconds(300).getEpochSecond(), wrongKey))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Unauthorized");
    }

    @Test
    @Order(6)
    void jwtProtectedRouteRejectsUnsignedToken() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/verify"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + unsignedJwt(issuer, "my-audience",
                                Instant.now().plusSeconds(300).getEpochSecond()))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("alg none token: %s", response.body()).isEqualTo(401);
        assertThat(response.body()).contains("Unauthorized");
    }

    @Test
    @Order(7)
    void jwtProtectedRouteRejectsExpiredToken() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/verify"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + jwt(issuer, "my-audience",
                                Instant.now().minusSeconds(300).getEpochSecond()))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("expired token: %s", response.body()).isEqualTo(401);
    }

    @Test
    @Order(8)
    void jwtProtectedRouteInvokesLambdaForValidToken() throws Exception {
        Assumptions.assumeTrue(lambdaDispatchAvailable,
                "Lambda dispatch not available in this environment");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/secure"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + jwt(issuer, "my-audience",
                                Instant.now().plusSeconds(300).getEpochSecond()))
                        .header("User-Agent", "sdk-test-java")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode event = JSON.readTree(response.body());

        assertThat(event.path("routeKey").asText()).isEqualTo("GET /secure");
        assertThat(event.path("rawPath").asText()).isEqualTo("/secure");
        assertThat(event.path("headers").path("authorization").asText()).contains("Bearer ");
        assertThat(event.path("requestContext").path("http").path("method").asText()).isEqualTo("GET");
    }

    private static void startIssuer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        signingKey = pair.getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        InetAddress address;
        URI endpoint = TestFixtures.endpoint();
        String bindAddress = System.getenv("FLOCI_TEST_JWT_ISSUER_BIND_ADDRESS");
        if (bindAddress != null && !bindAddress.isBlank()) {
            address = InetAddress.getByName(bindAddress);
        } else {
            int port = endpoint.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
            }
            // A connected TCP socket reports the source address of the route to Floci; a UDP
            // probe can report the wildcard address on macOS.
            try (Socket route = new Socket()) {
                route.connect(new InetSocketAddress(endpoint.getHost(), port), 5000);
                address = route.getLocalAddress();
            } catch (IOException e) {
                throw new IllegalStateException("Floci endpoint is not reachable at " + endpoint, e);
            }
        }
        assertThat(address.isAnyLocalAddress())
                .as("JWT fixture must bind one specific local address, not the wildcard %s; set "
                        + "FLOCI_TEST_JWT_ISSUER_BIND_ADDRESS to an interface Floci can reach and "
                        + "FLOCI_TEST_JWT_ISSUER_HOST to the name Floci should call it by", address)
                .isFalse();
        issuerServer = HttpServer.create(new InetSocketAddress(address, 0), 0);
        String advertisedHost = System.getenv("FLOCI_TEST_JWT_ISSUER_HOST");
        if (advertisedHost == null || advertisedHost.isBlank()) {
            advertisedHost = address.getHostAddress();
        }
        issuer = new URI("http", null, advertisedHost, issuerServer.getAddress().getPort(),
                null, null, null).toString();
        issuerServer.createContext("/.well-known/openid-configuration", exchange ->
                serveJson(exchange, Map.of("issuer", issuer, "jwks_uri", issuer + "/jwks")));
        issuerServer.createContext("/jwks", exchange -> {
            jwksRequests.incrementAndGet();
            serveJson(exchange, Map.of("keys", List.of(Map.of(
                    "kty", "RSA", "kid", KEY_ID, "alg", "RS256", "use", "sig",
                    "n", unsignedBase64(publicKey.getModulus()), "e", unsignedBase64(publicKey.getPublicExponent())))));
        });
        issuerServer.createContext("/verified", exchange -> serveJson(exchange, Map.of("verified", VERIFIED_RESPONSE)));
        issuerServer.start();
    }

    private static void serveJson(HttpExchange exchange, Object document) throws IOException {
        try (exchange) {
            byte[] bytes = JSON.writeValueAsBytes(document);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static String unsignedBase64(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return base64Url(bytes);
    }

    private static String jwt(String issuer, String audience, long exp) throws Exception {
        return jwt(issuer, audience, exp, signingKey);
    }

    private static String jwt(String issuer, String audience, long exp, PrivateKey key) throws Exception {
        String signingInput = signingInput(Map.of("alg", "RS256", "typ", "JWT", "kid", KEY_ID),
                issuer, audience, exp);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(signature.sign());
    }

    // Valid claims and no signature: an authorizer that only checks claims would accept it.
    private static String unsignedJwt(String issuer, String audience, long exp) throws Exception {
        return signingInput(Map.of("alg", "none", "typ", "JWT"), issuer, audience, exp) + ".";
    }

    private static String signingInput(Map<String, String> header, String issuer, String audience, long exp)
            throws Exception {
        String payload = base64Url(JSON.writeValueAsBytes(Map.of(
                "iss", issuer,
                "aud", audience,
                "exp", exp
        )));
        return base64Url(JSON.writeValueAsBytes(header)) + "." + payload;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] v2EchoHandlerZip() {
        String code = """
                exports.handler = async (event) => {
                    return {
                        statusCode: 200,
                        headers: { "content-type": "application/json" },
                        body: JSON.stringify(event)
                    };
                };
                """;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry("index.js"));
                zos.write(code.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build API Gateway v2 Lambda ZIP", e);
        }
    }
}
