package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    private final List<String> stacks = new ArrayList<>();

    @AfterEach
    void deleteStacks() {
        for (String stack : stacks) {
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
