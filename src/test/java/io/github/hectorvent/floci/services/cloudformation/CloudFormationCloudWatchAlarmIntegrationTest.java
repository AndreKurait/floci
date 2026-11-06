package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    private static void createStack(String stackName, String template) {
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
