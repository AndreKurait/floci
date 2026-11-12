package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check that CloudFormation provisions an AWS::CloudWatch::Alarm for real (into
 * CloudWatchMetricsService) rather than stubbing it. Alarms are metadata (no container), so the
 * test is Docker-free. CloudWatch is the Query API (form-encoded request, XML response).
 */
@QuarkusTest
class CloudFormationCloudWatchAlarmIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String CW_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/monitoring/aws4_request";

    private final List<String> stacks = new ArrayList<>();

    @AfterEach
    void deleteStacks() {
        for (String stack : stacks) {
            CfnStackWaits.awaitTerminal(stack, CFN_AUTH);
            CfnStackWaits.deleteStack(stack, CFN_AUTH);
        }
    }

    @Test
    void createStackProvisionsAlarmVisibleToCloudWatch() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String alarmName = "cfn-alarm-" + suffix;
        String stackName = "cfn-cw-alarm-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Alarm": {
                      "Type": "AWS::CloudWatch::Alarm",
                      "Properties": {
                        "AlarmName": "%s",
                        "Namespace": "AWS/EC2",
                        "MetricName": "CPUUtilization",
                        "Statistic": "Average",
                        "Period": 300,
                        "EvaluationPeriods": 2,
                        "Threshold": 80,
                        "ComparisonOperator": "GreaterThanThreshold"
                      }
                    }
                  },
                  "Outputs": {
                    "AlarmArn": {"Value": {"Fn::GetAtt": ["Alarm", "Arn"]}}
                  }
                }
                """.formatted(alarmName);

        createStack(stackName, template);

        // Stack completes and Fn::GetAtt(Alarm, Arn) resolves to the real alarm ARN.
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString(":alarm:" + alarmName));

        // The alarm really exists in CloudWatch.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_AUTH)
            .formParam("Action", "DescribeAlarms")
            .formParam("AlarmNames.member.1", alarmName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(alarmName))
            .body(containsString("CPUUtilization"));
    }

    /**
     * AWS accepts both documented values of {@code EvaluateLowSampleCountPercentile} on a percentile
     * alarm, so a template carrying either must reach CREATE_COMPLETE with the value kept.
     */
    @ParameterizedTest
    @ValueSource(strings = {"evaluate", "ignore"})
    void percentileAlarmAcceptsEitherLowSampleCountPolicy(String policy) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String alarmName = "cfn-p90-" + policy + "-" + suffix;
        String stackName = "cfn-cw-p90-" + policy + "-" + suffix;

        createStack(stackName, percentileAlarmTemplate(alarmName, policy));

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_AUTH)
            .formParam("Action", "DescribeAlarms")
            .formParam("AlarmNames.member.1", alarmName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ExtendedStatistic>p90</ExtendedStatistic>"))
            .body(containsString("<EvaluateLowSampleCountPercentile>" + policy
                    + "</EvaluateLowSampleCountPercentile>"));
    }

    /** A value outside AWS's two is still refused, so the stack rolls back on the alarm. */
    @Test
    void percentileAlarmWithAnUnknownLowSampleCountPolicyRollsBack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cw-p90-bogus-" + suffix;

        createStack(stackName, percentileAlarmTemplate("cfn-p90-bogus-" + suffix, "sometimes"));

        CfnStackWaits.awaitStatus(stackName, "ROLLBACK_COMPLETE", CFN_AUTH);
        String reason = CfnStackWaits.failedResourceReason(stackName, CFN_AUTH);
        assertTrue(reason != null && reason.contains("EvaluateLowSampleCountPercentile"), reason);
    }

    /**
     * The SQS dead-letter backlog alarm shape CDK emits: a FILL expression over two hidden queue
     * metrics. AWS accepts FILL, so the stack must complete with the expression stored.
     */
    @Test
    void metricMathAlarmWithFillReachesCreateComplete() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String alarmName = "cfn-fill-" + suffix;
        String stackName = "cfn-cw-fill-" + suffix;

        createStack(stackName, """
                {
                  "Resources": {
                    "Alarm": {
                      "Type": "AWS::CloudWatch::Alarm",
                      "Properties": {
                        "AlarmName": "%s",
                        "ComparisonOperator": "GreaterThanThreshold",
                        "EvaluationPeriods": 1,
                        "Threshold": 0,
                        "TreatMissingData": "notBreaching",
                        "Metrics": [
                          {"Id": "expr_1", "Expression": "FILL(visible, 0) + FILL(notVisible, 0)",
                           "Label": "Dead letters"},
                          {"Id": "visible", "ReturnData": false, "MetricStat": {
                            "Metric": {"Namespace": "AWS/SQS", "MetricName": "ApproximateNumberOfMessagesVisible",
                              "Dimensions": [{"Name": "QueueName", "Value": "dead-letters"}]},
                            "Period": 300, "Stat": "Maximum"}},
                          {"Id": "notVisible", "ReturnData": false, "MetricStat": {
                            "Metric": {"Namespace": "AWS/SQS",
                              "MetricName": "ApproximateNumberOfMessagesNotVisible",
                              "Dimensions": [{"Name": "QueueName", "Value": "dead-letters"}]},
                            "Period": 300, "Stat": "Maximum"}}
                        ]
                      }
                    }
                  }
                }
                """.formatted(alarmName));

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CW_AUTH)
            .formParam("Action", "DescribeAlarms")
            .formParam("AlarmNames.member.1", alarmName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Expression>FILL(visible, 0) + FILL(notVisible, 0)</Expression>"));
    }

    private static String percentileAlarmTemplate(String alarmName, String policy) {
        return """
                {
                  "Resources": {
                    "Alarm": {
                      "Type": "AWS::CloudWatch::Alarm",
                      "Properties": {
                        "AlarmName": "%s",
                        "Namespace": "Example/Latency",
                        "MetricName": "Time",
                        "ExtendedStatistic": "p90",
                        "EvaluateLowSampleCountPercentile": "%s",
                        "Period": 300,
                        "EvaluationPeriods": 3,
                        "Threshold": 500000,
                        "ComparisonOperator": "GreaterThanThreshold",
                        "TreatMissingData": "notBreaching",
                        "Unit": "Microseconds"
                      }
                    }
                  }
                }
                """.formatted(alarmName, policy);
    }

    private void createStack(String stackName, String template) {
        stacks.add(stackName);
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
