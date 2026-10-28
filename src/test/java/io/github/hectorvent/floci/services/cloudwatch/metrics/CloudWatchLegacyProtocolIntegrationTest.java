package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudWatchLegacyProtocolIntegrationTest {

    private static final String MODERN_TARGET = "GraniteServiceVersion20100801.";
    private static final String LEGACY_TARGET = "CloudWatchVersion20130116.";

    @BeforeAll
    static void configureAwsJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetDataset", "GetResourceMetricsConfiguration", "ListAlarmMuteRules",
            "DescribeAnomalyDetectors", "ListMetricStreams", "DescribeAlarmContributors",
            "GetOTelEnrichment", "ListManagedInsightRules", "ListTagsForResource",
            "DescribeInsightRules", "PutLogAlarm", "FlociReviewUnknownOperation"})
    void unavailableLegacyJsonOperationsUseTheAcceptedConsoleErrorContract(String action) {
        given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", LEGACY_TARGET + action).body("{}")
                .post("/").then().statusCode(400)
                .body("__type", equalTo("UnsupportedOperation"));
        given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", LEGACY_TARGET + action).body("[]")
                .post("/").then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @ParameterizedTest
    @CsvSource({"GET,ListMetricStreams", "POST,ListMetricStreams",
            "GET,DescribeAnomalyDetectors", "POST,DescribeAnomalyDetectors",
            "GET,ListTagsForResource", "POST,ListTagsForResource",
            "GET,GetResourceMetricsConfiguration", "POST,GetResourceMetricsConfiguration"})
    void unavailableLegacyQueryOperationsStayInvalid(String method, String action) {
        queryRequest(method, action).request(method, "/")
                .then().statusCode(404).body("ErrorResponse.Error.Code", equalTo("InvalidAction"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void commonQueryReadsUseTheLegacyVersion(String method) throws Exception {
        String xml = queryRequest(method, "GetMetricStatistics")
                .param("Namespace", "LegacyProtocol")
                .param("MetricName", "Unused")
                .param("StartTime", "2026-10-20T00:00:00Z")
                .param("EndTime", "2026-10-20T00:15:00Z")
                .param("Period", "300")
                .param("Statistics.member.1", "Average")
                .request(method, "/").then().statusCode(200)
                .body(containsString("GetMetricStatisticsResponse")).extract().asString();
        assertEquals(AwsNamespaces.CW_2013, XmlParser.parseDocument(xml)
                .getDocumentElement().getNamespaceURI());
    }

    @ParameterizedTest
    @CsvSource({"GET,ListMetrics", "POST,ListMetrics", "GET,DescribeAlarms", "POST,DescribeAlarms",
            "GET,ListDashboards", "POST,ListDashboards"})
    void commonLegacyReadsUseTheRequestedXmlNamespace(String method, String action) throws Exception {
        String xml = queryRequest(method, action).request(method, "/").then().statusCode(200)
                .body(containsString(action + "Response")).extract().asString();

        assertEquals(AwsNamespaces.CW_2013, XmlParser.parseDocument(xml)
                .getDocumentElement().getNamespaceURI());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void modeledLegacyAlarmHistoryUsesTheRequestedNamespace(String method) throws Exception {
        String xml = queryRequest(method, "DescribeAlarmHistory").request(method, "/")
                .then().statusCode(200)
                .body(containsString("DescribeAlarmHistoryResponse")).extract().asString();
        assertEquals(AwsNamespaces.CW_2013, XmlParser.parseDocument(xml)
                .getDocumentElement().getNamespaceURI());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void legacyMetricDataDoesNotRunTheModernQueryContract(String method) {
        queryRequest(method, "GetMetricData").request(method, "/")
                .then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("UnsupportedOperation"))
                .body("ErrorResponse.Error.Message", containsString("2013-01-16"));
    }

    @ParameterizedTest
    @ValueSource(strings = {MODERN_TARGET, LEGACY_TARGET})
    void onlyTheModernTargetRunsModernMetricData(String prefix) {
        ValidatableResponse response = given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", prefix + "GetMetricData")
                .body("{\"StartTime\":1792454400,\"EndTime\":1792455300,\"MetricDataQueries\":["
                        + "{\"Id\":\"probe\",\"MetricStat\":{\"Metric\":{\"Namespace\":\"LegacyProtocol\","
                        + "\"MetricName\":\"Unused\"},\"Period\":300,\"Stat\":\"Average\"}}]}")
                .post("/").then();
        if (MODERN_TARGET.equals(prefix)) {
            response.statusCode(200).body("MetricDataResults[0].Id", equalTo("probe"));
        } else {
            response.statusCode(400).body("__type", equalTo("UnsupportedOperation"))
                    .body("message", containsString("CloudWatchVersion20130116"));
        }
    }

    private static RequestSpecification queryRequest(String method, String action) {
        RequestSpecification request = given().header("Authorization",
                "AWS4-HMAC-SHA256 Credential=test/20261020/us-east-1/monitoring/aws4_request,"
                        + " SignedHeaders=host;x-amz-date, Signature=unused");
        if ("POST".equals(method)) {
            request.contentType("application/x-www-form-urlencoded")
                    .formParam("Action", action).formParam("Version", "2013-01-16");
        } else {
            request.queryParam("Action", action).queryParam("Version", "2013-01-16");
        }
        return request;
    }
}
