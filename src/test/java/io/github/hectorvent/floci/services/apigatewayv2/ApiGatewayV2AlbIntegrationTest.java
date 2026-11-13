package io.github.hectorvent.floci.services.apigatewayv2;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/** End-to-end: HTTP API → ALB listener ARN → ELBv2 data plane → registered stub target. */
@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2AlbIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260520/us-east-1/elasticloadbalancing/aws4_request";

    /** Fixed listener port. Distinct from {@code ElbV2LambdaTargetIntegrationTest} (7780). */
    private static final int LISTENER_PORT = 7792;
    /** The listener socket opens asynchronously after the control-plane call returns. */
    private static final Duration LISTENER_READY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration LISTENER_POLL_INTERVAL = Duration.ofMillis(25);

    private static HttpServer stub;
    private static int stubPort;

    private static String lbArn;
    private static String lbDnsName;
    private static String tgArn;
    private static String listenerArn;
    private static String vpcLinkId;
    private static String apiId;
    private static String integrationId;

    @BeforeAll
    static void startStubBackend() throws IOException {
        // Ephemeral-port HTTP stub that echoes the requested path. Acts as the
        // "ECS container" sitting behind the ALB.
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            String reqPath = exchange.getRequestURI().getPath();
            String rawPath = exchange.getRequestURI().getRawPath();
            byte[] body = ("{\"status\":\"ok\",\"path\":\"" + reqPath + "\",\"rawPath\":\"" + rawPath + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stub.start();
        stubPort = stub.getAddress().getPort();
    }

    @AfterAll
    static void stopStubBackend() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    @Order(1)
    void createLoadBalancer() {
        Response response = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "apigw-alb-test")
                .formParam("Type", "application")
                .formParam("Scheme", "internet-facing")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .response();
        lbArn = response.path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
        lbDnsName = response.path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.DNSName");
    }

    @Test
    @Order(2)
    void createTargetGroup() {
        tgArn = given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "apigw-alb-tg")
                .formParam("Protocol", "HTTP")
                .formParam("Port", String.valueOf(stubPort))
                .formParam("TargetType", "ip")
                .formParam("HealthCheckPath", "/")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    @Test
    @Order(3)
    void registerStubAsTarget() {
        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", tgArn)
                .formParam("Targets.member.1.Id", "127.0.0.1")
                .formParam("Targets.member.1.Port", String.valueOf(stubPort))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(4)
    void createListenerOnFixedPort() {
        listenerArn = given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", String.valueOf(LISTENER_PORT))
                .formParam("DefaultActions.member.1.Type", "forward")
                .formParam("DefaultActions.member.1.TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
    }

    @Test
    @Order(5)
    void createVpcLink() {
        vpcLinkId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"alb-vpc-link","subnetIds":["subnet-1"],"securityGroupIds":["sg-1"]}
                        """)
                .when().post("/v2/vpclinks")
                .then()
                .statusCode(201)
                .extract().path("vpcLinkId");
    }

    @Test
    @Order(6)
    void createHttpApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"alb-test-api","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .extract().path("apiId");
    }

    @Test
    @Order(7)
    void createAlbIntegration() {
        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY",
                         "integrationUri":"%s",
                         "connectionType":"VPC_LINK",
                         "connectionId":"%s",
                         "payloadFormatVersion":"1.0",
                         "integrationMethod":"ANY"}
                        """.formatted(listenerArn, vpcLinkId))
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationUri", equalTo(listenerArn))
                .body("connectionType", equalTo("VPC_LINK"))
                .extract().path("integrationId");
    }

    @Test
    @Order(8)
    void createHealthRoute() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /health","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(9)
    void httpProxyToStubDirectlyWorksThroughDispatch() {
        // Baseline: bypass the ALB chain and prove the v2 HTTP_PROXY dispatch path
        // works when integrationUri is a plain http://127.0.0.1 URL. If this hangs,
        // it's a pre-existing pipeline issue independent of the ALB resolver.
        // PATCH the integration to bypass the listener, GET execute-api, then PATCH it back.
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationUri":"http://127.0.0.1:%d/probe"}
                        """.formatted(stubPort))
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then()
                .statusCode(200);

        given()
                .when().get("/execute-api/" + apiId + "/$default/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("ok"));

        // Restore the listener-ARN integrationUri for the ALB tests that follow.
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationUri":"%s"}
                        """.formatted(listenerArn))
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then()
                .statusCode(200);
    }

    @Test
    @Order(10)
    void listenerForwardsDirectlyToStub() {
        // Verifies the ALB data plane independently of the API Gateway path: hit the
        // listener directly. Polls because server.listen() is async — if the listener
        // never accepts a connection we get a fast ConnectException and retry.
        await().alias("listener direct probe")
                .atMost(LISTENER_READY_TIMEOUT)
                .pollDelay(Duration.ZERO)
                .pollInterval(LISTENER_POLL_INTERVAL)
                .ignoreExceptionsInstanceOf(IOException.class)
                .untilAsserted(() -> {
                    RawHttpResponse resp = rawGetWithHost("/health", lbDnsName);
                    assertThat(resp.statusCode(), equalTo(200));
                    assertThat(resp.body(), containsString("\"status\":\"ok\""));
                });
    }

    private static RawHttpResponse rawGetWithHost(String path, String host) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", LISTENER_PORT), 2_000);
            socket.setSoTimeout(3_000);
            OutputStream output = socket.getOutputStream();
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            output.write(request.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            return readRawHttpResponse(socket.getInputStream());
        }
    }

    private static RawHttpResponse readRawHttpResponse(InputStream input) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;
        while ((current = input.read()) != -1) {
            headerBytes.write(current);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                break;
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }

        String[] lines = headerBytes.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        if (lines.length == 0 || !lines[0].startsWith("HTTP/")) {
            throw new IOException("invalid HTTP response");
        }
        int statusCode = Integer.parseInt(lines[0].split(" ", 3)[1]);
        return new RawHttpResponse(statusCode, new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }

    private record RawHttpResponse(int statusCode, String body) {}

    /** Full HttpAlbIntegration happy path: execute-api → ALB resolver → listener → stub returns 200. */
    @Test
    @Order(11)
    void executeApiReachesStubViaAlb() {
        // Now go through the actual HttpAlbIntegration path: dispatchHttpProxyV2 must
        // detect the listener ARN, resolve it to the bound port, and forward to it.
        await().alias("execute-api → ALB → stub")
                .atMost(LISTENER_READY_TIMEOUT)
                .pollDelay(Duration.ZERO)
                .pollInterval(LISTENER_POLL_INTERVAL)
                .untilAsserted(() -> given().when().get("/execute-api/" + apiId + "/$default/health")
                        .then()
                        .statusCode(200)
                        .body("status", equalTo("ok"))
                        .body("path", equalTo("/health")));
    }

    @Test
    @Order(12)
    void albListenerIgnoresUnknownVpcLinkConnectionId() {
        patchConnectionId("vpclink-missing");
        try {
            given().when().get("/execute-api/" + apiId + "/$default/health")
                    .then().statusCode(200).body("path", equalTo("/health"));
        } finally {
            patchConnectionId(vpcLinkId);
        }
    }

    private static void patchConnectionId(String connectionId) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"connectionId":"%s"}
                        """.formatted(connectionId))
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then()
                .statusCode(200);
    }

    @Test
    @Order(13)
    void unresolvableListenerArnReturns502() {
        // Replace the integrationUri with a listener ARN that doesn't exist.
        // The ALB resolver must return a clean 502, not crash.
        String ghostArn = "arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/ghost-lb/0000000000000000/0000000000000000";
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationUri":"%s"}
                        """.formatted(ghostArn))
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then()
                .statusCode(200);

        given()
                .when().get("/execute-api/" + apiId + "/$default/health")
                .then()
                .statusCode(502);
    }

    @Test
    @Order(14)
    void albTargetReceivesEncodedPathAndTrailingSlashAsTheCallerSentThem() {
        given().contentType(ContentType.JSON)
                .body("{\"integrationUri\":\"" + listenerArn + "\"}")
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200);
        given().contentType(ContentType.JSON)
                .body("{\"routeKey\":\"GET /{proxy+}\",\"target\":\"integrations/" + integrationId + "\"}")
                .when().post("/v2/apis/" + apiId + "/routes").then().statusCode(201);

        given().urlEncodingEnabled(false).when().get("/execute-api/" + apiId + "/$default/a%3Fb")
                .then().statusCode(200).body("rawPath", equalTo("/a%3Fb"));
        given().urlEncodingEnabled(false).when().get("/execute-api/" + apiId + "/$default/x/")
                .then().statusCode(200).body("rawPath", equalTo("/x/"));
    }

    @Test
    @Order(15)
    void albTargetReceivesTheNamedStageInThePathButNotTheDefaultStage() {
        given().urlEncodingEnabled(false).when().get("/execute-api/" + apiId + "/prod/a%3Fb")
                .then().statusCode(200).body("rawPath", equalTo("/prod/a%3Fb"));
        given().urlEncodingEnabled(false).when().get("/execute-api/" + apiId + "/prod/x/")
                .then().statusCode(200).body("rawPath", equalTo("/prod/x/"));
        given().urlEncodingEnabled(false).when().get("/execute-api/" + apiId + "/$default/a%3Fb")
                .then().statusCode(200).body("rawPath", equalTo("/a%3Fb"));
    }

    @Test
    @Order(90)
    void cleanup() {
        if (integrationId != null) {
            given().when().delete("/v2/apis/" + apiId + "/integrations/" + integrationId);
        }
        if (apiId != null) {
            given().when().delete("/v2/apis/" + apiId);
        }
        if (vpcLinkId != null) {
            given().when().delete("/v2/vpclinks/" + vpcLinkId);
        }
        if (listenerArn != null) {
            given()
                    .formParam("Action", "DeleteListener")
                    .formParam("ListenerArn", listenerArn)
                    .header("Authorization", AUTH)
                    .when().post("/");
        }
        if (lbArn != null) {
            given()
                    .formParam("Action", "DeleteLoadBalancer")
                    .formParam("LoadBalancerArn", lbArn)
                    .header("Authorization", AUTH)
                    .when().post("/");
        }
    }
}
