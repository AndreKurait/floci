package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Service Quotas usage alarm, "m1/SERVICE_QUOTA(m1)*100" over the CloudWatch PutMetricData usage
 * metric (CloudWatch User Guide, "Visualizing your service quotas and setting alarms" and
 * "CloudWatch usage metrics"), through PutMetricAlarm, an AWS::CloudWatch::Alarm stack and
 * GetMetricData.
 */
@QuarkusTest
class CloudWatchServiceQuotaAlarmIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<Map<String, String>> PUT_METRIC_DATA = List.of(
            Map.of("Name", "Resource", "Value", "PutMetricData"), Map.of("Name", "Service", "Value", "CloudWatch"),
            Map.of("Name", "Type", "Value", "API"));

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response cloudWatch(String operation, Object body) {
        return given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "GraniteServiceVersion20100801." + operation)
                .body(MAPPER.valueToTree(body).toString()).post("/");
    }

    private static List<Map<String, Object>> quotaQueries(List<Map<String, String>> dimensions) {
        return List.of(
                Map.of("Id", "utilization", "Expression", "(usage/SERVICE_QUOTA(usage))*100",
                        "Label", "PutMetricData utilization", "ReturnData", true),
                Map.of("Id", "usage", "ReturnData", false, "MetricStat", Map.of(
                        "Metric", Map.of("Namespace", "AWS/Usage", "MetricName", "CallCount", "Dimensions", dimensions),
                        "Period", 60, "Stat", "Sum")));
    }

    /** A usage alarm: three of three minutes above 80 percent of the quota, missing data breaching. */
    private static Map<String, Object> alarm(String name, List<Map<String, String>> dimensions) {
        return Map.of("AlarmName", name, "AlarmDescription", "PutMetricData", "ActionsEnabled", false,
                "ComparisonOperator", "GreaterThanThreshold", "DatapointsToAlarm", 3, "EvaluationPeriods", 3,
                "Threshold", 80, "TreatMissingData", "breaching", "Metrics", quotaQueries(dimensions));
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    @Test
    void putMetricAlarmAcceptsAUsageAlarmOnPutMetricData() {
        String name = unique("quota-alarm");
        cleanup.register(() -> cloudWatch("DeleteAlarms", Map.of("AlarmNames", List.of(name))).then().statusCode(200));
        cloudWatch("PutMetricAlarm", alarm(name, PUT_METRIC_DATA)).then().statusCode(200);
        cloudWatch("DescribeAlarms", Map.of("AlarmNames", List.of(name))).then().statusCode(200)
                .body("MetricAlarms[0].Metrics[0].Expression", equalTo("(usage/SERVICE_QUOTA(usage))*100"));
    }

    @Test
    void aStackWithTheUsageAlarmIsCreated() throws Exception {
        String stack = unique("quota-alarm-stack");
        Map<String, Object> properties = alarm(stack + "-alarm", PUT_METRIC_DATA);
        String template = MAPPER.writeValueAsString(Map.of("Resources", Map.of("PutMetricDataQuotaAlarm",
                Map.of("Type", "AWS::CloudWatch::Alarm", "Properties", properties))));
        cleanup.register(() -> given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack").formParam("StackName", stack).post("/").then().statusCode(200));
        given().contentType("application/x-www-form-urlencoded").formParam("Action", "CreateStack")
                .formParam("StackName", stack).formParam("TemplateBody", template).post("/").then().statusCode(200);
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals("CREATE_COMPLETE", state.status(), () -> CfnStackWaits.failedResourceReason(stack));
    }

    @Test
    void getMetricDataReturnsThePutMetricDataQuota() {
        long end = Instant.now().getEpochSecond() / 60 * 60;
        cloudWatch("GetMetricData", Map.of("StartTime", end - 60, "EndTime", end, "MetricDataQueries", List.of(
                Map.of("Id", "quota", "Expression", "SERVICE_QUOTA(usage)", "ReturnData", true),
                quotaQueries(PUT_METRIC_DATA).get(1))))
                .then().statusCode(200)
                .body("MetricDataResults[0].Values[0]", equalTo(500.0f));
    }

    @Test
    void aMetricThatNoQuotaTracksIsRefused() {
        List<Map<String, String>> listMetrics = List.of(
                Map.of("Name", "Resource", "Value", "ListMetrics"), Map.of("Name", "Service", "Value", "CloudWatch"),
                Map.of("Name", "Type", "Value", "API"));
        cloudWatch("PutMetricAlarm", alarm(unique("no-quota"), listMetrics)).then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }
}
