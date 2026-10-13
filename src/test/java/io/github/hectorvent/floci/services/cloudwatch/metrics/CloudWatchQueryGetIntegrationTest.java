package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class CloudWatchQueryGetIntegrationTest {

    private static String authorization(String service) {
        return "AWS4-HMAC-SHA256 Credential=test/20261013/us-east-1/" + service + "/aws4_request";
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "authorization", "AUTHORIZATION", "aUtHoRiZaTiOn"})
    void getReadsMetricWrittenThroughPublicPost(String headerName) {
        Instant now = Instant.now();
        String namespace = "Example/QueryGet/" + headerName;
        given().header(headerName, authorization("monitoring"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "PutMetricData").formParam("Version", "2010-08-01")
                .formParam("Namespace", namespace)
                .formParam("MetricData.member.1.MetricName", "Requests")
                .formParam("MetricData.member.1.Value", "17")
                .formParam("MetricData.member.1.Timestamp", now.toString())
                .post("/").then().statusCode(200);
        given().header(headerName, authorization("monitoring"))
                .queryParam("Action", "GetMetricStatistics").queryParam("Version", "2010-08-01")
                .queryParam("Namespace", namespace).queryParam("MetricName", "Requests")
                .queryParam("StartTime", now.minusSeconds(120).toString())
                .queryParam("EndTime", now.plusSeconds(120).toString()).queryParam("Period", "60")
                .queryParam("Statistics.member.1", "Sum")
                .get("/").then().statusCode(200)
                .body("GetMetricStatisticsResponse.GetMetricStatisticsResult.Datapoints.member.Sum", equalTo("17.0"));
    }

    @Test
    void operationFallbackUsesTheSamePublicHandler() {
        given().header("Authorization", authorization("monitoring"))
                .queryParam("Operation", "ListMetrics")
                .get("/").then().statusCode(200).body(containsString("ListMetricsResponse"));
    }

    @Test
    void s3ScopeAndUnrelatedQueryKeepBucketListing() {
        given().header("Authorization", authorization("s3"))
                .queryParam("Action", "GetMetricStatistics")
                .get("/").then().statusCode(200).body(containsString("ListAllMyBucketsResult"));
        given().queryParam("X-Amz-Credential", "test/20261013/us-east-1/s3/aws4_request")
                .queryParam("Action", "GetMetricStatistics")
                .get("/").then().statusCode(200).body(containsString("ListAllMyBucketsResult"));
        given().queryParam("Action", "UnknownAction")
                .get("/").then().statusCode(200).body(containsString("ListAllMyBucketsResult"));
        given().queryParam("Action", "TagResource")
                .get("/").then().statusCode(200).body(containsString("ListAllMyBucketsResult"));
    }

    @Test
    void cloudWatchValidationStillReturnsQueryErrors() {
        given().header("Authorization", authorization("monitoring"))
                .queryParam("Action", "PutDashboard")
                .get("/").then().statusCode(400).body(containsString("ErrorResponse"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UnknownAction", ""})
    void signedUnknownCloudWatchActionsReturnAwsErrors(String action) {
        given().header("Authorization", authorization("monitoring"))
                .queryParam("Action", action).queryParam("Version", "2010-08-01")
                .get("/").then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("InvalidAction"));
    }

    @Test
    void signedCloudWatchGetWithoutAnActionReturnsUnknownOperation() {
        given().header("Authorization", authorization("monitoring"))
                .queryParam("Version", "2010-08-01")
                .get("/").then().statusCode(404).body(containsString("<UnknownOperationException/>"));
    }
}
