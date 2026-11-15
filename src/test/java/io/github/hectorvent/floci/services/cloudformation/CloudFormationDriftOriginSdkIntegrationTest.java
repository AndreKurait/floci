package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SnsException;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationDriftOriginSdkIntegrationTest {
    @Test
    void genuineNativeNestedCreationHasNoRootProvenanceAndIsNeverDeclaredInSync() {
        String marker = UUID.randomUUID().toString();
        String name = "native-origin-" + marker;
        String bucket = "origin-template-" + marker;
        String body = """
                {"Resources":{"Topic":{"Type":"AWS::SNS::Topic",
                "Properties":{"TopicName":"%s","DisplayName":"owned"}}}}
                """.formatted(name);
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
             S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "test", Region.US_EAST_1);
             SnsClient sns = AwsTestClients.create(SnsClient.builder(), "test", Region.US_EAST_1)) {
            s3.createBucket(request -> request.bucket(bucket));
            try {
                s3.putObject(request -> request.bucket(bucket).key("child.json"), RequestBody.fromString(body));
                String id = cfn.createStack(request -> request.stackName(name).templateBody("""
                        {"Resources":{"Child":{"Type":"AWS::CloudFormation::Stack",
                        "Properties":{"TemplateURL":"http://localhost/%s/child.json"}}}}
                        """.formatted(bucket)).tags(tag -> tag.key("OwnedDrift").value(marker))).stackId();
                Stack root = cfn.describeStacks(request -> request.stackName(id)).stacks().getFirst();
                String topic = null;
                try {
                    assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitStatus(cfn, id, "CREATE_COMPLETE").stackStatusAsString());
                    String child = cfn.describeStackResource(request -> request.stackName(id).logicalResourceId("Child"))
                            .stackResourceDetail().physicalResourceId();
                    assertTrue(cfn.listChangeSets(request -> request.stackName(child)).summaries().isEmpty());
                    String capturedTopic = cfn.describeStackResource(request -> request.stackName(child).logicalResourceId("Topic"))
                            .stackResourceDetail().physicalResourceId();
                    topic = capturedTopic;
                    assertEquals("owned", sns.getTopicAttributes(request -> request.topicArn(capturedTopic))
                            .attributes().get("DisplayName"));
                    CloudFormationException childDenied = assertThrows(CloudFormationException.class,
                            () -> cfn.detectStackDrift(request -> request.stackName(child)));
                    assertEquals("ValidationError", childDenied.awsErrorDetails().errorCode());
                    assertTrue(childDenied.awsErrorDetails().errorMessage().contains("nested"));
                    assertThrows(CloudFormationException.class, () -> cfn.detectStackResourceDrift(
                            request -> request.stackName(child).logicalResourceId("Topic")));
                    assertTrue(cfn.describeStackResourceDrifts(request -> request.stackName(child))
                            .stackResourceDrifts().isEmpty());
                    CloudFormationException parentDenied = assertThrows(CloudFormationException.class,
                            () -> cfn.detectStackDrift(request -> request.stackName(id)));
                    assertTrue(parentDenied.awsErrorDetails().errorMessage().contains("AWS::CloudFormation::Stack"));
                } finally {
                    Stack current = cfn.describeStacks(request -> request.stackName(name)).stacks().getFirst();
                    assertEquals(root.stackId(), current.stackId());
                    assertEquals(root.creationTime(), current.creationTime());
                    assertTrue(current.tags().stream().anyMatch(tag ->
                            "OwnedDrift".equals(tag.key()) && marker.equals(tag.value())));
                    cfn.deleteStack(request -> request.stackName(id));
                    Stack deleted = CfnStackWaits.awaitStatus(cfn, id, "DELETE_COMPLETE");
                    assertEquals(id, deleted.stackId());
                    assertEquals(root.creationTime(), deleted.creationTime());
                    await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
                        CloudFormationException absent = assertThrows(CloudFormationException.class,
                                () -> cfn.describeStacks(request -> request.stackName(name)));
                        assertEquals("ValidationError", absent.awsErrorDetails().errorCode());
                        assertEquals(400, absent.statusCode());
                    });
                    if (topic != null) {
                        String capturedTopic = topic;
                        assertEquals(404, assertThrows(SnsException.class,
                                () -> sns.getTopicAttributes(request -> request.topicArn(capturedTopic))).statusCode());
                    }
                }
                assertEquals(body, s3.getObjectAsBytes(request -> request.bucket(bucket).key("child.json")).asUtf8String());
            } finally {
                s3.deleteObject(request -> request.bucket(bucket).key("child.json"));
                assertTrue(s3.listObjectsV2(request -> request.bucket(bucket)).contents().isEmpty());
                s3.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }

    @Test
    void deletingNativeCreateProvenanceRefusesNewDetectionAndPreservesPriorResultReads() {
        try (CfnDriftNativeFixture fixture = new CfnDriftNativeFixture("test", Region.US_EAST_1)) {
            String detection = fixture.detect();
            fixture.cfn.listChangeSets(request -> request.stackName(fixture.stack.stackId())).summaries()
                    .forEach(change -> fixture.cfn.deleteChangeSet(request ->
                            request.stackName(fixture.stack.stackId()).changeSetName(change.changeSetId())));
            assertTrue(fixture.cfn.listChangeSets(request -> request.stackName(fixture.stack.stackId())).summaries().isEmpty());
            CloudFormationException denied = assertThrows(CloudFormationException.class, fixture::detect);
            assertTrue(denied.awsErrorDetails().errorMessage().contains("retained completed CREATE"));
            assertEquals("IN_SYNC", fixture.cfn.describeStackDriftDetectionStatus(request ->
                    request.stackDriftDetectionId(detection)).stackDriftStatusAsString());
            assertEquals(2, fixture.cfn.describeStackResourceDrifts(request ->
                    request.stackName(fixture.stack.stackId())).stackResourceDrifts().size());
        }
    }
}
