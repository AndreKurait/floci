package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class CloudWatchJsonTargetIntegrationTest {

    private static final String LEGACY_TARGET = "CloudWatchVersion20130116.";

    @BeforeAll
    static void configureAwsJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({"GraniteServiceVersion20100801.,ListMetrics,Metrics",
            "CloudWatchVersion20130116.,ListMetrics,Metrics",
            "GraniteServiceVersion20100801.,DescribeAlarms,MetricAlarms",
            "CloudWatchVersion20130116.,DescribeAlarms,MetricAlarms"})
    void commonJsonReadsUseEitherAwsTarget(String prefix, String action, String resultField) {
        given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", prefix + action).body("{}")
                .post("/").then().statusCode(200).body(resultField, notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CloudWatchVersion20100801.", "CloudWatchVersion19000101."})
    void unknownJsonVersionsStayUnknown(String prefix) {
        given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", prefix + "ListMetrics").body("{}")
                .post("/").then().statusCode(404)
                .body("__type", equalTo("UnknownOperationException"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyJsonAliasDoesNotBecomeACborService(boolean rpcV2) throws Exception {
        byte[] body = new ObjectMapper(new CBORFactory()).writeValueAsBytes(Map.of());
        RequestSpecification request = given().body(body);
        if (rpcV2) {
            request.contentType("application/cbor").header("smithy-protocol", "rpc-v2-cbor")
                    .post("/service/CloudWatchVersion20130116/operation/ListMetrics").then().statusCode(404);
        } else {
            request.contentType("application/x-amz-cbor-1.1")
                    .header("X-Amz-Target", LEGACY_TARGET + "ListMetrics").post("/").then().statusCode(404);
        }
    }

}
