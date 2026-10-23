package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.testing.RealClassicElbDataPlaneProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;

/** A public DNS zone must not make Classic ELB own another service's HTTP route. */
@QuarkusTest
@TestProfile(RealClassicElbDataPlaneProfile.class)
class ElbClassicSharedEdgeIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private final String zone = "shared-edge-" + UUID.randomUUID() + ".example.test";
    private final String domain = "api." + zone;
    private String apiId;
    private String zoneId;
    private String record;
    private String bucket;
    private boolean domainCreated;

    @Test
    void apiGatewayCustomDomainRemainsRoutableWithPublicNonElbDnsRecords() {
        apiId = api().body(Map.of("name", zone)).post("/restapis")
                .then().statusCode(201).extract().path("id");
        String rootId = api().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String method = "/restapis/" + apiId + "/resources/" + rootId + "/methods/GET";
        api().body(Map.of("authorizationType", "NONE")).put(method).then().statusCode(201);
        api().body(Map.of()).put(method + "/responses/200").then().statusCode(201);
        api().body(Map.of("type", "MOCK",
                        "requestTemplates", Map.of("application/json", "{\"statusCode\":200}")))
                .put(method + "/integration").then().statusCode(201);
        api().body(Map.of("selectionPattern", "",
                        "responseTemplates", Map.of("application/json", "{\"service\":\"apigateway\"}")))
                .put(method + "/integration/responses/200").then().statusCode(201);
        api().body(Map.of("stageName", "dev")).post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201);
        Response createdDomain = api().body(Map.of("domainName", domain))
                .post("/domainnames").then().statusCode(201).extract().response();
        domainCreated = true;
        api().body(Map.of("basePath", "v1", "restApiId", apiId, "stage", "dev"))
                .post("/domainnames/" + domain + "/basepathmappings").then().statusCode(201);
        assertCustomDomain();

        zoneId = dns().body("<CreateHostedZoneRequest><Name>" + zone + "</Name><CallerReference>"
                        + UUID.randomUUID() + "</CallerReference></CreateHostedZoneRequest>")
                .post("/2013-04-01/hostedzone").then().statusCode(201).extract()
                .path("CreateHostedZoneResponse.HostedZone.Id").toString().replace("/hostedzone/", "");
        // Even an empty public zone is unrelated to Classic ELB ownership.
        assertCustomDomain();

        String regionalName = createdDomain.path("regionalDomainName");
        String regionalZone = createdDomain.path("regionalHostedZoneId");
        for (String candidate : List.of(
                valueRecord("A", "192.0.2.45"),
                valueRecord("CNAME", regionalName + "."),
                "<ResourceRecordSet><Name>" + domain + "</Name><Type>A</Type><AliasTarget>"
                        + "<HostedZoneId>" + regionalZone + "</HostedZoneId><DNSName>" + regionalName
                        + "</DNSName><EvaluateTargetHealth>false</EvaluateTargetHealth></AliasTarget>"
                        + "</ResourceRecordSet>")) {
            changeRecord("CREATE", candidate);
            record = candidate;
            assertCustomDomain();
            changeRecord("DELETE", record);
            record = null;
        }
    }

    @Test
    void s3VirtualHostRemainsRoutableInsideAPublicZone() {
        String newBucket = "shared-edge-" + UUID.randomUUID();
        given().header("Authorization", authorization("s3")).put("/" + newBucket)
                .then().statusCode(200);
        bucket = newBucket;
        given().header("Authorization", authorization("s3")).contentType("text/plain")
                .body("stored-s3-object").put("/" + bucket + "/payload.txt").then().statusCode(200);
        String hostname = bucket + ".s3.us-east-1." + zone;
        given().header("Authorization", authorization("s3")).header("Host", hostname)
                .get("/payload.txt").then().statusCode(200).body(equalTo("stored-s3-object"));

        zoneId = dns().body("<CreateHostedZoneRequest><Name>" + zone + "</Name><CallerReference>"
                        + UUID.randomUUID() + "</CallerReference></CreateHostedZoneRequest>")
                .post("/2013-04-01/hostedzone").then().statusCode(201).extract()
                .path("CreateHostedZoneResponse.HostedZone.Id").toString().replace("/hostedzone/", "");
        given().header("Authorization", authorization("s3")).header("Host", hostname)
                .get("/payload.txt").then().statusCode(200).body(equalTo("stored-s3-object"));
    }

    private void assertCustomDomain() {
        given().header("Host", domain).get("/v1/").then()
                .statusCode(200).body("service", equalTo("apigateway"));
    }

    private String valueRecord(String type, String value) {
        return "<ResourceRecordSet><Name>" + domain + "</Name><Type>" + type + "</Type><TTL>30</TTL>"
                + "<ResourceRecords><ResourceRecord><Value>" + value
                + "</Value></ResourceRecord></ResourceRecords></ResourceRecordSet>";
    }

    private void changeRecord(String action, String value) {
        dns().body("<ChangeResourceRecordSetsRequest><ChangeBatch><Changes><Change><Action>"
                        + action + "</Action>" + value
                        + "</Change></Changes></ChangeBatch></ChangeResourceRecordSetsRequest>")
                .post("/2013-04-01/hostedzone/" + zoneId + "/rrset").then().statusCode(200);
    }

    private RequestSpecification api() {
        return given().header("Authorization", authorization("apigateway")).contentType("application/json");
    }

    private RequestSpecification dns() {
        return given().header("Authorization", authorization("route53")).contentType("application/xml");
    }

    private static String authorization(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261014/us-east-1/" + service + "/aws4_request";
    }

    @AfterEach
    void removeOnlyThisTestsPublicResources() {
        assertAll(
                () -> {
                    if (record != null) {
                        changeRecord("DELETE", record);
                    }
                },
                () -> {
                    if (zoneId != null) {
                        dns().delete("/2013-04-01/hostedzone/" + zoneId).then().statusCode(200);
                    }
                },
                () -> {
                    if (domainCreated) {
                        api().delete("/domainnames/" + domain).then().statusCode(202);
                    }
                },
                () -> {
                    if (apiId != null) {
                        api().delete("/restapis/" + apiId).then().statusCode(202);
                    }
                },
                () -> {
                    if (bucket != null) {
                        given().header("Authorization", authorization("s3"))
                                .delete("/" + bucket + "/payload.txt").then().statusCode(204);
                    }
                },
                () -> {
                    if (bucket != null) {
                        given().header("Authorization", authorization("s3"))
                                .delete("/" + bucket).then().statusCode(204);
                    }
                });
    }
}
