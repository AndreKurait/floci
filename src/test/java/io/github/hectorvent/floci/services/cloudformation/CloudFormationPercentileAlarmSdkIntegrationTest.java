package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.CloudWatchException;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationPercentileAlarmSdkIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"eu-west-1", "us-gov-east-1"})
    void percentilePolicySurvivesNativeStackCreateUpdateAndDelete(String region) {
        String name = "percentile-" + UUID.randomUUID();
        URI endpoint = URI.create("http://localhost:" + RestAssured.port);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("830011110019", "test"));
        try (CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint)
                .region(Region.of(region)).credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             CloudWatchClient cloudWatch = CloudWatchClient.builder().endpointOverride(endpoint)
                .region(Region.of(region)).credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            cfn.createStack(request -> request.stackName(name).templateBody(template(name, "ignore")));
            try {
                awaitStack(cfn, name, "CREATE_COMPLETE");
                MetricAlarm alarm = cloudWatch.describeAlarms(request -> request.alarmNames(name)).metricAlarms().getFirst();
                assertEquals("ignore", alarm.evaluateLowSampleCountPercentile());
                assertEquals("p99", alarm.extendedStatistic());
                String physicalId = cfn.describeStackResource(request -> request.stackName(name)
                        .logicalResourceId("Latency")).stackResourceDetail().physicalResourceId();
                assertEquals(alarm.alarmArn(), cfn.describeStacks(request -> request.stackName(name))
                        .stacks().getFirst().outputs().getFirst().outputValue());
                cfn.updateStack(request -> request.stackName(name).templateBody(template(name, "evaluate")));
                awaitStack(cfn, name, "UPDATE_COMPLETE");
                assertEquals(physicalId, cfn.describeStackResource(request -> request.stackName(name)
                        .logicalResourceId("Latency")).stackResourceDetail().physicalResourceId());
                assertEquals("evaluate", cloudWatch.describeAlarms(request -> request.alarmNames(name))
                        .metricAlarms().getFirst().evaluateLowSampleCountPercentile());
                CloudWatchException invalid = assertThrows(CloudWatchException.class,
                        () -> cloudWatch.putMetricAlarm(request -> request.alarmName(name)
                                .namespace("Example/Latency").metricName("Latency").extendedStatistic("p99")
                                .period(60).evaluationPeriods(1).threshold(100.0)
                                .comparisonOperator("GreaterThanThreshold").evaluateLowSampleCountPercentile("invalid")));
                assertEquals("InvalidParameterValue", invalid.awsErrorDetails().errorCode());
                assertEquals("evaluate", cloudWatch.describeAlarms(request -> request.alarmNames(name))
                        .metricAlarms().getFirst().evaluateLowSampleCountPercentile());
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
                await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
                    CloudFormationException missing = assertThrows(CloudFormationException.class,
                            () -> cfn.describeStacks(request -> request.stackName(name)));
                    assertEquals("ValidationError", missing.awsErrorDetails().errorCode());
                });
            }
            assertTrue(cloudWatch.describeAlarms(request -> request.alarmNames(name)).metricAlarms().isEmpty());
        }
    }

    private static void awaitStack(CloudFormationClient cfn, String name, String status) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50)).untilAsserted(
                () -> assertEquals(status, cfn.describeStacks(request -> request.stackName(name))
                        .stacks().getFirst().stackStatusAsString()));
    }

    private static String template(String name, String policy) {
        return """
                {"Resources":{"Latency":{"Type":"AWS::CloudWatch::Alarm","Properties":{
                  "AlarmName":"%s","Namespace":"Example/Latency","MetricName":"Latency",
                  "ExtendedStatistic":"p99","Period":60,"EvaluationPeriods":1,"Threshold":100,
                  "ComparisonOperator":"GreaterThanThreshold","EvaluateLowSampleCountPercentile":"%s"
                }}},"Outputs":{"Arn":{"Value":{"Fn::GetAtt":["Latency","Arn"]}}}}
                """.formatted(name, policy);
    }
}
