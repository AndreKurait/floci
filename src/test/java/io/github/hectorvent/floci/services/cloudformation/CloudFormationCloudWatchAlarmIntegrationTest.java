package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

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
