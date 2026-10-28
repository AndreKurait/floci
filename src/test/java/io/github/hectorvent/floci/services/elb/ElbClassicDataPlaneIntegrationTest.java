package io.github.hectorvent.floci.services.elb;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.testing.RealClassicElbDataPlaneProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.function.Executable;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(RealClassicElbDataPlaneProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbClassicDataPlaneIntegrationTest {

    private static final String ACCOUNT = "321321321321";
    private static final String FOREIGN = "654654654654";
    private static final String REGION = "eu-west-1";
    private final String name = "public-classic-" + UUID.randomUUID().toString().substring(0, 8);
    private final String zone = name + ".example.test";
    private final String host = "search." + zone;
    private final List<String> records = new ArrayList<>();
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicInteger backendRequests = new AtomicInteger();
    private final AtomicReference<String> received = new AtomicReference<>();
    private HttpServer backend;
    private String instanceId;
    private String vpcId;
    private String subnetId;
    private String dnsName;
    private String elbZone;
    private String zoneId;
    private int port;

    @Inject
    Ec2Service ec2;

    @Inject
    ElbClassicService elb;

    @Inject
    Vertx vertx;

    @BeforeEach
    void createActualControlPlaneResourcesAndHttpBackend() throws Exception {
        if (backend != null) {
            return;
        }
        port = RestAssured.port;
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            boolean healthCheck = "/healthcheck".equals(exchange.getRequestURI().getPath());
            byte[] request = exchange.getRequestBody().readAllBytes();
            if (!healthCheck) {
                backendRequests.incrementAndGet();
                received.set(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                        + " " + exchange.getRequestHeaders().getFirst("Host")
                        + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            }
            byte[] response = request.length == 0 ? "native-backend".getBytes(StandardCharsets.UTF_8) : request;
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().add("Set-Cookie", "first=1");
            exchange.getResponseHeaders().add("Set-Cookie", "second=2");
            exchange.sendResponseHeaders(healthCheck && !healthy.get() ? 503 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        backend.start();
        vpcId = ec2Request("CreateVpc").formParam("CidrBlock", "10.81.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        subnetId = ec2Request("CreateSubnet").formParam("VpcId", vpcId).formParam("CidrBlock", "10.81.1.0/24")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        instanceId = ec2Request("RunInstances").formParam("ImageId", "ami-classic-http")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("SubnetId", subnetId).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        // Bind the simulated EC2 instance to the real HTTP fixture, as Docker launch would do.
        Instance instance = ec2.findInstanceById(ACCOUNT, instanceId);
        assertNotNull(instance);
        instance.setContainerBridgeIp("127.0.0.1");
        instance.setState(InstanceState.running());
        dnsName = elb("CreateLoadBalancer").formParam("LoadBalancerName", name)
                .formParam("Subnets.member.1", subnetId)
                .formParam("Listeners.member.1.Protocol", "HTTP").formParam("Listeners.member.1.LoadBalancerPort", "80")
                .formParam("Listeners.member.1.InstancePort", backend.getAddress().getPort())
                .formParam("Listeners.member.2.Protocol", "HTTP").formParam("Listeners.member.2.LoadBalancerPort", "8080")
                .formParam("Listeners.member.2.InstancePort", backend.getAddress().getPort())
                .post("/").then().statusCode(200).extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.DNSName");
        elbZone = elb("DescribeLoadBalancers").formParam("LoadBalancerNames.member.1", name)
                .post("/").then().statusCode(200).extract()
                .path("DescribeLoadBalancersResponse.DescribeLoadBalancersResult"
                        + ".LoadBalancerDescriptions.member.CanonicalHostedZoneNameID");
        zoneId = createZone(ACCOUNT);
        addRecord(alias(host, dnsName, elbZone, "only", 1));
        addRecord("<ResourceRecordSet><Name>chain." + zone + "</Name><Type>CNAME</Type><TTL>30</TTL>"
                + "<ResourceRecords><ResourceRecord><Value>" + host + ".</Value></ResourceRecord></ResourceRecords>"
                + "</ResourceRecordSet>");
        elb("ConfigureHealthCheck").formParam("LoadBalancerName", name)
                .formParam("HealthCheck.Target", "HTTP:" + backend.getAddress().getPort() + "/healthcheck")
                .formParam("HealthCheck.Interval", "5").formParam("HealthCheck.Timeout", "2")
                .formParam("HealthCheck.HealthyThreshold", "2").formParam("HealthCheck.UnhealthyThreshold", "2")
                .post("/").then().statusCode(200);
        register();
        awaitHealth("InService");
    }

    @Test
    @Order(1)
    void existingDnsAndWeightedAliasReachTheRealBackendOutsideTheDefaultAccount() {
        for (String customer : List.of(dnsName, host)) {
            given().header("Host", customer).get("/").then().statusCode(200).body(equalTo("native-backend"));
            assertEquals("GET / " + customer + " null", received.get());
        }
    }

    @Test
    @Order(1)
    void listenerProtocolsAcceptCaseVariationsAndRejectUnsupportedStoredValues() {
        ClassicListener listener = RequestScopes.callAs(ACCOUNT,
                () -> elb.describeLoadBalancers(REGION, List.of(name)).getFirst().getListeners().getFirst());
        try {
            for (String protocol : List.of("http", "HtTp", "https")) {
                listener.setProtocol(protocol);
                listener.setInstanceProtocol("hTtP");
                given().header("Host", host).get("/native").then().statusCode(200);
            }
            listener.setProtocol("TCP");
            given().header("Host", host).get("/native").then().statusCode(502);
            listener.setProtocol(null);
            given().header("Host", host).get("/native").then().statusCode(502);
        } finally {
            listener.setProtocol("HTTP");
            listener.setInstanceProtocol("HTTP");
        }
    }

    @Test
    @Order(1)
    void callerForwardedProtoDoesNotChooseADifferentListener() {
        given().header("Host", host).header("X-Forwarded-Proto", "https")
                .get("/").then().statusCode(200).body(equalTo("native-backend"));
    }

    @Test
    @Order(2)
    void cnameChainPreservesMethodRawPathQueryCredentialsBinaryBodyAndResponseHeaders() {
        String customer = "CHAIN." + zone.toUpperCase() + ".:80";
        byte[] body = {0, 1, 2, 10, 13, 127, (byte) 255};
        Response response = given().header("Host", customer).header("Authorization", "opaque-customer-credential")
                .urlEncodingEnabled(false).body(body).post("/index%2Fname/_doc/1?q=a%2Bb");
        assertEquals(200, response.statusCode());
        assertArrayEquals(body, response.asByteArray());
        assertEquals("POST /index%2Fname/_doc/1?q=a%2Bb " + customer
                + " opaque-customer-credential", received.get());
        assertEquals(2, response.headers().getValues("Set-Cookie").size());
    }

    @Test
    @Order(2)
    void http2AuthorityIsPreservedAsTheHttp1BackendHost() throws Exception {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false));
        try {
            for (int listenerPort : List.of(80, 8080)) {
                String body = client.request(new RequestOptions()
                        .setServer(SocketAddress.inetSocketAddress(port, "127.0.0.1"))
                        .setHost(host).setPort(listenerPort).setMethod(HttpMethod.GET).setURI("/authority"))
                        .compose(request -> request.authority(HostAndPort.create(host, listenerPort)).send())
                        .compose(response -> {
                            assertEquals(HttpVersion.HTTP_2, response.version());
                            assertEquals(200, response.statusCode());
                            return response.body();
                        }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS).toString();
                assertEquals("native-backend", body);
                // The HTTP/2 client omits the default port from :authority, but preserves other ports.
                String authority = listenerPort == 80 ? host : host + ":" + listenerPort;
                assertEquals("GET /authority " + authority + " null", received.get());
            }
        } finally {
            client.close();
        }
    }

    @Test
    @Order(3)
    void unknownAndLookalikeHostsNeverReachTheBackendAndOtherAwsApisStillWork() {
        int before = backendRequests.get();
        given().header("Host", "unknown." + zone).get("/");
        given().header("Host", host + ".outside.invalid").get("/");
        assertEquals(before, backendRequests.get());
        elb("DescribeLoadBalancers").formParam("LoadBalancerNames.member.1", name)
                .post("/").then().statusCode(200);
    }

    @Test
    @Order(3)
    void publicAliasesCannotForwardEmulatorApiCredentialsToAnInstance() {
        String protectedZone = route53(ACCOUNT).body("<CreateHostedZoneRequest><Name>localhost.floci.io</Name>"
                        + "<CallerReference>" + UUID.randomUUID()
                        + "</CallerReference></CreateHostedZoneRequest>")
                .post("/2013-04-01/hostedzone").then().statusCode(201).extract()
                .path("CreateHostedZoneResponse.HostedZone.Id").toString().replace("/hostedzone/", "");
        List<String> ownedRecords = new ArrayList<>();
        int before = backendRequests.get();
        try {
            for (String authority : List.of("localhost.floci.io", "ec2.us-east-1.localhost.floci.io")) {
                String record = alias(authority, dnsName, elbZone, null, 0);
                changeRecord(ACCOUNT, protectedZone, "CREATE", record);
                ownedRecords.add(record);
                ec2Request("DescribeVpcs", FOREIGN).header("Host", authority)
                        .post("/").then().statusCode(200).body(containsString("DescribeVpcsResponse"));
            }
            assertEquals(before, backendRequests.get());
        } finally {
            for (String record : ownedRecords) {
                changeRecord(ACCOUNT, protectedZone, "DELETE", record);
            }
            route53(ACCOUNT).delete("/2013-04-01/hostedzone/" + protectedZone).then().statusCode(200);
        }
    }

    @Test
    @Order(4)
    void malformedAliasTargetsLoopsAndAmbiguousWeightedRecordsAreRefused() {
        int before = backendRequests.get();
        addRecord(alias("wrong-zone." + zone, dnsName, "ZWRONG", null, 0));
        addRecord(alias("lookalike." + zone, dnsName + ".outside.invalid", elbZone, null, 0));
        addRecord(alias("loop." + zone, "loop." + zone, zoneId, null, 0));
        addRecord(alias("ambiguous." + zone, dnsName, elbZone, "first", 1));
        addRecord(alias("ambiguous." + zone, "absent." + zone, zoneId, "second", 1));
        given().header("Host", "wrong-zone." + zone).get("/").then().statusCode(404);
        for (String prefix : List.of("lookalike", "loop", "ambiguous")) {
            given().header("Host", prefix + "." + zone).get("/");
        }
        assertEquals(before, backendRequests.get());
    }

    @Test
    @Order(5)
    void duplicatePublicZonesAreAmbiguousAcrossAccountsAndDeletionRestoresTheRoute() {
        String otherZone = createZone(FOREIGN);
        try {
            int before = backendRequests.get();
            given().header("Host", host).get("/");
            assertEquals(before, backendRequests.get());
        } finally {
            route53(FOREIGN).delete("/2013-04-01/hostedzone/" + otherZone).then().statusCode(200);
        }
        given().header("Host", host).get("/").then().statusCode(200).body(equalTo("native-backend"));
    }

    @Test
    @Order(5)
    void anOverlappingForeignPrivateZoneCannotHideThePublicEndpoint() {
        String foreignVpc = ec2Request("CreateVpc", FOREIGN)
                .formParam("CidrBlock", "10.82.0.0/16").post("/").then().statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");
        String privateZone = null;
        try {
            privateZone = route53(FOREIGN).body("<CreateHostedZoneRequest><Name>" + zone
                    + "</Name><CallerReference>" + UUID.randomUUID() + "</CallerReference><VPC><VPCRegion>"
                    + REGION + "</VPCRegion><VPCId>" + foreignVpc + "</VPCId></VPC></CreateHostedZoneRequest>")
                    .post("/2013-04-01/hostedzone").then().statusCode(201)
                    .body("CreateHostedZoneResponse.HostedZone.Config.PrivateZone", equalTo("true")).extract()
                    .path("CreateHostedZoneResponse.HostedZone.Id").toString().replace("/hostedzone/", "");
            given().header("Host", host).get("/").then().statusCode(200).body(equalTo("native-backend"));
        } finally {
            if (privateZone != null) {
                route53(FOREIGN).delete("/2013-04-01/hostedzone/" + privateZone).then().statusCode(200);
            }
            ec2Request("DeleteVpc", FOREIGN).formParam("VpcId", foreignVpc)
                    .post("/").then().statusCode(200);
        }
    }

    @Test
    @Order(6)
    void publicRoutingUsesTheLoadBalancerOwnerWhileControlPlaneReadsStayAccountScoped() {
        elb("DescribeLoadBalancers", FOREIGN)
                .formParam("LoadBalancerNames.member.1", name).post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
        given().header("Host", host).header("Authorization", auth(FOREIGN, "es"))
                .get("/").then().statusCode(200).body(equalTo("native-backend"));
    }

    @Test
    @Order(6)
    void sameNamedForeignLoadBalancerCannotUseOrClearTheOwnersHealthState() {
        String foreignDns = elb("CreateLoadBalancer", FOREIGN)
                .formParam("LoadBalancerName", name).formParam("AvailabilityZones.member.1", REGION + "a")
                .formParam("Listeners.member.1.Protocol", "HTTP").formParam("Listeners.member.1.LoadBalancerPort", "80")
                .formParam("Listeners.member.1.InstancePort", backend.getAddress().getPort())
                .post("/").then().statusCode(200).extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.DNSName");
        try {
            int before = backendRequests.get();
            given().header("Host", foreignDns).get("/").then().statusCode(503);
            assertEquals(before, backendRequests.get());
        } finally {
            elb("DeleteLoadBalancer", FOREIGN)
                    .formParam("LoadBalancerName", name).post("/").then().statusCode(200);
        }
        elb("DescribeInstanceHealth").formParam("LoadBalancerName", name).post("/").then().statusCode(200)
                .body("DescribeInstanceHealthResponse.DescribeInstanceHealthResult.InstanceStates.member.State",
                        equalTo("InService"));
        given().header("Host", host).get("/").then().statusCode(200).body(equalTo("native-backend"));
    }

    @Test
    @Order(7)
    void deregisteredAndUnhealthyTargetsStopReceivingCustomerTraffic() {
        elb("DeregisterInstancesFromLoadBalancer").formParam("LoadBalancerName", name)
                .formParam("Instances.member.1.InstanceId", instanceId).post("/").then().statusCode(200);
        given().header("Host", host).get("/").then().statusCode(503);
        register();
        awaitHealth("InService");
        healthy.set(false);
        awaitHealth("OutOfService");
        given().header("Host", host).get("/").then().statusCode(503);
        healthy.set(true);
        awaitHealth("InService");
        given().header("Host", host).get("/").then().statusCode(200);
    }

    @Test
    @Order(8)
    void deletingTheLoadBalancerRevokesBothItsDnsNameAndTheStoredAlias() {
        elb("DeleteLoadBalancer").formParam("LoadBalancerName", name).post("/").then().statusCode(200);
        int before = backendRequests.get();
        given().header("Host", host).get("/");
        given().header("Host", dnsName).get("/");
        assertEquals(before, backendRequests.get());
    }

    private void register() {
        elb("RegisterInstancesWithLoadBalancer").formParam("LoadBalancerName", name)
                .formParam("Instances.member.1.InstanceId", instanceId).post("/").then().statusCode(200);
    }

    private void awaitHealth(String state) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                elb("DescribeInstanceHealth").formParam("LoadBalancerName", name).post("/").then().statusCode(200)
                        .body("DescribeInstanceHealthResponse.DescribeInstanceHealthResult.InstanceStates.member.State",
                                equalTo(state)));
    }

    private String createZone(String account) {
        return route53(account).body("<CreateHostedZoneRequest><Name>" + zone + "</Name><CallerReference>"
                + UUID.randomUUID() + "</CallerReference></CreateHostedZoneRequest>")
                .post("/2013-04-01/hostedzone").then().statusCode(201).extract()
                .path("CreateHostedZoneResponse.HostedZone.Id").toString().replace("/hostedzone/", "");
    }

    private void addRecord(String record) {
        changeRecord("CREATE", record);
        records.add(record);
    }

    private void changeRecord(String action, String record) {
        changeRecord(ACCOUNT, zoneId, action, record);
    }

    private void changeRecord(String account, String hostedZoneId, String action, String record) {
        route53(account).body("<ChangeResourceRecordSetsRequest><ChangeBatch><Changes><Change><Action>"
                + action + "</Action>" + record + "</Change></Changes></ChangeBatch></ChangeResourceRecordSetsRequest>")
                .post("/2013-04-01/hostedzone/" + hostedZoneId + "/rrset").then().statusCode(200);
    }

    private static String alias(String hostname, String target, String targetZone, String setId, int weight) {
        return "<ResourceRecordSet><Name>" + hostname + "</Name><Type>A</Type>"
                + (setId == null ? "" : "<SetIdentifier>" + setId + "</SetIdentifier><Weight>" + weight + "</Weight>")
                + "<AliasTarget><HostedZoneId>" + targetZone + "</HostedZoneId><DNSName>" + target
                + "</DNSName><EvaluateTargetHealth>false</EvaluateTargetHealth></AliasTarget></ResourceRecordSet>";
    }

    private RequestSpecification elb(String action) {
        return elb(action, ACCOUNT);
    }

    private RequestSpecification elb(String action, String account) {
        return given().port(port).header("Authorization", auth(account, "elasticloadbalancing"))
                .formParam("Action", action).formParam("Version", "2012-06-01");
    }

    private RequestSpecification ec2Request(String action) {
        return ec2Request(action, ACCOUNT);
    }

    private RequestSpecification ec2Request(String action, String account) {
        return given().port(port).header("Authorization", auth(account, "ec2")).formParam("Action", action);
    }

    private RequestSpecification route53(String account) {
        return given().port(port).header("Authorization", auth(account, "route53")).contentType("application/xml");
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261014/" + REGION + "/" + service + "/aws4_request";
    }

    @AfterAll
    void cleanup() {
        try {
            List<Executable> cleanup = new ArrayList<>();
            cleanup.add(() -> elb("DeleteLoadBalancer").formParam("LoadBalancerName", name)
                    .post("/").then().statusCode(200));
            for (String record : records) {
                cleanup.add(() -> changeRecord("DELETE", record));
            }
            if (zoneId != null) {
                cleanup.add(() -> route53(ACCOUNT).delete("/2013-04-01/hostedzone/" + zoneId)
                        .then().statusCode(200));
            }
            if (instanceId != null) {
                cleanup.add(() -> ec2Request("TerminateInstances").formParam("InstanceId.1", instanceId)
                        .post("/").then().statusCode(200));
            }
            if (subnetId != null) {
                cleanup.add(() -> ec2Request("DeleteSubnet").formParam("SubnetId", subnetId)
                        .post("/").then().statusCode(200));
            }
            if (vpcId != null) {
                cleanup.add(() -> ec2Request("DeleteVpc").formParam("VpcId", vpcId)
                        .post("/").then().statusCode(200));
            }
            assertAll("remove owned load-balancer fixtures", cleanup);
        } finally {
            if (backend != null) {
                backend.stop(0);
            }
        }
    }
}
