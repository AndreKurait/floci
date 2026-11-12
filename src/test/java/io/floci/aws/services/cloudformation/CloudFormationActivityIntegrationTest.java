package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.GetActivityTaskResponse;
import software.amazon.awssdk.services.sfn.model.SfnException;
import software.amazon.awssdk.services.sfn.model.Tag;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class CloudFormationActivityIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"bad:name", "bad/name", "bad name", "bad?name"})
    void activityNamesFollowTheSdkContract(String prefix) {
        String name = prefix + "-" + UUID.randomUUID();
        AtomicReference<String> created = new AtomicReference<>();
        try (SfnClient sfn = AwsTestClients.create(
                SfnClient.builder(), "867530900003", Region.US_WEST_2)) {
            try {
                SfnException invalid = assertThrows(SfnException.class, () ->
                        created.set(sfn.createActivity(request -> request.name(name)).activityArn()));
                assertEquals("InvalidName", invalid.awsErrorDetails().errorCode());
            } finally {
                if (created.get() != null) {
                    sfn.deleteActivity(request -> request.activityArn(created.get()));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void renameCleansUpTheDisplacedActivityUnlessRetained(boolean retain) {
        String stack = "cfn-activity-" + UUID.randomUUID();
        try (CloudFormationClient cfn = AwsTestClients.create(
                CloudFormationClient.builder(), "867530900003", Region.US_WEST_2);
                SfnClient sfn = AwsTestClients.create(SfnClient.builder(), "867530900003", Region.US_WEST_2)) {
            String initial = template(stack + "-old", "initial");
            String updated = template(stack + "-new", "updated");
            if (retain) {
                initial = retainActivity(initial);
                updated = retainActivity(updated);
            }
            String firstTemplate = initial;
            String nextTemplate = updated;
            cfn.createStack(request -> request.stackName(stack).templateBody(firstTemplate)
                    .capabilitiesWithStrings("CAPABILITY_IAM"));
            String prior = null;
            String replacement = null;
            try {
                prior = outputs(cfn, stack, "CREATE_COMPLETE").get("ActivityRef");
                cfn.updateStack(request -> request.stackName(stack).templateBody(nextTemplate)
                        .capabilitiesWithStrings("CAPABILITY_IAM"));
                replacement = outputs(cfn, stack, "UPDATE_COMPLETE").get("ActivityRef");
                assertNotEquals(prior, replacement);
                String oldArn = prior;
                if (retain) {
                    assertEquals(stack + "-old", sfn.describeActivity(request -> request.activityArn(oldArn)).name());
                } else {
                    assertMissing(sfn, oldArn);
                }
                String newArn = replacement;
                assertEquals(stack + "-new", sfn.describeActivity(request -> request.activityArn(newArn)).name());
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                cfn.waiter().waitUntilStackDeleteComplete(request -> request.stackName(stack));
                if (replacement != null) {
                    assertMissing(sfn, replacement);
                }
                if (prior != null) {
                    String oldArn = prior;
                    if (retain) {
                        sfn.deleteActivity(request -> request.activityArn(oldArn));
                    }
                    assertMissing(sfn, oldArn);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-west-2", "us-gov-west-1"})
    void provisionedActivityRunsAWorkerCallbackAndReconcilesTags(String region) {
        String stack = "cfn-activity-" + UUID.randomUUID();
        String name = stack + "-worker";
        try (CloudFormationClient cfn = AwsTestClients.create(
                CloudFormationClient.builder(), "867530900003", Region.of(region));
                SfnClient sfn = AwsTestClients.create(SfnClient.builder(), "867530900003", Region.of(region))) {
            cfn.createStack(request -> request.stackName(stack).templateBody(template(name, "initial"))
                    .capabilitiesWithStrings("CAPABILITY_IAM"));
            String activityArn = null;
            try {
                Map<String, String> outputs = outputs(cfn, stack, "CREATE_COMPLETE");
                activityArn = outputs.get("ActivityRef");
                String activity = activityArn;
                assertEquals(activity, outputs.get("ActivityArn"));
                assertEquals(name, outputs.get("ActivityName"));
                assertEquals(name, sfn.describeActivity(request -> request.activityArn(activity)).name());
                assertEquals(Map.of("marker", "initial", "removed", "old"), tags(sfn, activity));
                String execution = sfn.startExecution(request -> request
                        .stateMachineArn(outputs.get("Machine")).input("{\"nativeWorker\":true}")).executionArn();
                GetActivityTaskResponse task = sfn.getActivityTask(request ->
                        request.activityArn(activity).workerName("native-contract-worker"));
                assertFalse(task.taskToken().isBlank());
                assertEquals("{\"nativeWorker\":true}", task.input());
                sfn.sendTaskSuccess(request -> request.taskToken(task.taskToken()).output("{\"completed\":true}"));
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertEquals("SUCCEEDED", sfn.describeExecution(request -> request
                                .executionArn(execution)).statusAsString()));
                assertEquals("{\"completed\":true}", sfn.describeExecution(request -> request
                        .executionArn(execution)).output());

                cfn.updateStack(request -> request.stackName(stack).templateBody(template(name, "updated"))
                        .capabilitiesWithStrings("CAPABILITY_IAM"));
                assertEquals(outputs, outputs(cfn, stack, "UPDATE_COMPLETE"));
                assertEquals(Map.of("marker", "updated"), tags(sfn, activity));
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                cfn.waiter().waitUntilStackDeleteComplete(request -> request.stackName(stack));
                if (activityArn != null) {
                    String activity = activityArn;
                    SfnException gone = assertThrows(SfnException.class,
                            () -> sfn.describeActivity(request -> request.activityArn(activity)));
                    assertEquals("ActivityDoesNotExist", gone.awsErrorDetails().errorCode());
                }
            }
        }
    }

    private static Map<String, String> tags(SfnClient sfn, String activity) {
        return sfn.listTagsForResource(request -> request.resourceArn(activity)).tags()
                .stream().collect(Collectors.toMap(Tag::key, Tag::value));
    }

    private static void assertMissing(SfnClient sfn, String arn) {
        SfnException gone = assertThrows(SfnException.class,
                () -> sfn.describeActivity(request -> request.activityArn(arn)));
        assertEquals("ActivityDoesNotExist", gone.awsErrorDetails().errorCode());
    }

    private static String retainActivity(String template) {
        return template.replace("\"Activity\":{\"Type\"", "\"Activity\":{\"UpdateReplacePolicy\":\"Retain\",\"Type\"");
    }

    private static Map<String, String> outputs(CloudFormationClient cfn, String stack, String status) {
        return CfnStackWaits.awaitStatus(cfn, stack, status).outputs()
                .stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private static String template(String name, String marker) {
        String removed = marker.equals("initial") ? ",{\"Key\":\"removed\",\"Value\":\"old\"}" : "";
        return """
                {
                  "Resources":{
                    "Activity":{"Type":"AWS::StepFunctions::Activity","Properties":{
                      "Name":"%s","Tags":[{"Key":"marker","Value":"%s"}%s]}},
                    "Role":{"Type":"AWS::IAM::Role","Properties":{
                      "AssumeRolePolicyDocument":{"Version":"2012-10-17","Statement":[{
                        "Effect":"Allow","Principal":{"Service":"states.amazonaws.com"},
                        "Action":"sts:AssumeRole"}]}}},
                    "Machine":{"Type":"AWS::StepFunctions::StateMachine","Properties":{
                      "RoleArn":{"Fn::GetAtt":["Role","Arn"]},
                      "Definition":{"StartAt":"Work","States":{"Work":{
                        "Type":"Task","Resource":{"Ref":"Activity"},"TimeoutSeconds":15,"End":true}}}}}
                  },
                  "Outputs":{
                    "ActivityRef":{"Value":{"Ref":"Activity"}},
                    "ActivityArn":{"Value":{"Fn::GetAtt":["Activity","Arn"]}},
                    "ActivityName":{"Value":{"Fn::GetAtt":["Activity","Name"]}},
                    "Machine":{"Value":{"Ref":"Machine"}}
                  }
                }
                """.formatted(name, marker, removed);
    }
}
