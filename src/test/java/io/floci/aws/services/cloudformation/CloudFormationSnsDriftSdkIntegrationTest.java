package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackResourceDrift;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SnsException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationSnsDriftSdkIntegrationTest {

    @Test
    void declaredDisplayNameReachesNativeTopicAndUpdatesResetAndRollBack() {
        try (Fixture fixture = Fixture.create("\"DisplayName\":\"initial <&>\",")) {
            assertEquals("initial <&>", fixture.displayName());
            fixture.update("\"DisplayName\":\"changed\",", false, "UPDATE_COMPLETE");
            assertEquals("changed", fixture.displayName());
            fixture.update("\"DisplayName\":\"\",", false, "UPDATE_COMPLETE");
            assertEquals("", fixture.displayName());
            fixture.update("\"DisplayName\":\"before-drop\",", false, "UPDATE_COMPLETE");
            fixture.update("", false, "UPDATE_COMPLETE");
            assertEquals("", fixture.displayName());
            fixture.update("\"DisplayName\":\"committed\",", false, "UPDATE_COMPLETE");
            fixture.update("\"DisplayName\":\"uncommitted\",", true, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals("committed", fixture.displayName());
        }
    }

    @Test
    void detectResourceReadsANativelyProvisionedLiteralTopicName() {
        try (Fixture fixture = Fixture.create("")) {
            StackResourceDrift drift = fixture.cfn.detectStackResourceDrift(request ->
                    request.stackName(fixture.stack.stackId()).logicalResourceId("Topic")).stackResourceDrift();
            assertEquals(fixture.stack.stackId(), drift.stackId());
            assertEquals("Topic", drift.logicalResourceId());
            assertEquals(fixture.topicArn, drift.physicalResourceId());
            assertEquals("AWS::SNS::Topic", drift.resourceType());
            assertEquals("IN_SYNC", drift.stackResourceDriftStatusAsString());
            assertEquals("{\"TopicName\":\"" + fixture.topicName + "\"}", drift.expectedProperties());
            assertEquals(drift.expectedProperties(), drift.actualProperties());
            assertTrue(drift.propertyDifferences().isEmpty());
            assertNotNull(drift.timestamp());
        }
    }

    @Test
    void detectWholeStackCreatesARealDetectionIdentifier() {
        try (Fixture fixture = Fixture.create("")) {
            String id = fixture.cfn.detectStackDrift(request -> request.stackName(fixture.stack.stackId()))
                    .stackDriftDetectionId();
            assertNotNull(id);
            assertDoesNotThrow(() -> UUID.fromString(id));
        }
    }

    @Test
    void resourceDriftListingStartsEmptyBeforeAnyDetection() {
        try (Fixture fixture = Fixture.create("")) {
            assertTrue(fixture.cfn.describeStackResourceDrifts(request ->
                    request.stackName(fixture.stack.stackId()).maxResults(1)).stackResourceDrifts().isEmpty());
        }
    }

    @Test
    void unknownDetectionIdentifierHasTheNativeValidationEnvelope() {
        try (CloudFormationClient cfn = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            String id = UUID.randomUUID().toString();
            CloudFormationException error = assertThrows(CloudFormationException.class, () ->
                    cfn.describeStackDriftDetectionStatus(request -> request.stackDriftDetectionId(id)));
            assertEquals(400, error.statusCode());
            assertEquals("ValidationError", error.awsErrorDetails().errorCode());
            assertTrue(error.awsErrorDetails().errorMessage().contains(id));
            assertFalse(error.awsErrorDetails().errorMessage().contains("Unsupported"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final CloudFormationClient cfn;
        private final SnsClient sns;
        private final String name;
        private final String marker;
        private final String topicName;
        private final Stack stack;
        private final String topicArn;

        private Fixture(CloudFormationClient cfn, SnsClient sns, String name, String marker,
                        String topicName, Stack stack, String topicArn) {
            this.cfn = cfn;
            this.sns = sns;
            this.name = name;
            this.marker = marker;
            this.topicName = topicName;
            this.stack = stack;
            this.topicArn = topicArn;
        }

        private static Fixture create(String property) {
            CloudFormationClient cfn = AwsTestClients.create(
                    CloudFormationClient.builder(), "test", Region.US_EAST_1);
            SnsClient sns = AwsTestClients.create(SnsClient.builder(), "test", Region.US_EAST_1);
            String marker = UUID.randomUUID().toString();
            String name = "cfn-sns-drift-" + marker;
            String topicName = name + "-topic";
            String id = cfn.createStack(request -> request.stackName(name)
                    .templateBody(template(topicName, property, false))
                    .tags(tag -> tag.key("DriftFixture").value(marker))).stackId();
            Stack stack = CfnStackWaits.awaitStatus(cfn, id, "CREATE_COMPLETE");
            String topicArn = cfn.describeStackResource(request -> request.stackName(id)
                    .logicalResourceId("Topic")).stackResourceDetail().physicalResourceId();
            assertEquals(topicArn, sns.getTopicAttributes(request -> request.topicArn(topicArn))
                    .attributes().get("TopicArn"));
            return new Fixture(cfn, sns, name, marker, topicName, stack, topicArn);
        }

        private static String template(String topicName, String property, boolean failAfterTopic) {
            return """
                    {"Resources":{"Topic":{"Type":"AWS::SNS::Topic",
                      "Properties":{%s"TopicName":"%s"}}%s}}
                    """.formatted(property, topicName,
                    failAfterTopic ? """
                            ,"Later":{"Type":"AWS::EC2::Instance","DependsOn":"Topic","Properties":{
                              "LaunchTemplate":{"LaunchTemplateId":"lt-0123456789abcdef0","Version":"1"}}}
                            """ : "");
        }

        private String displayName() {
            return sns.getTopicAttributes(request -> request.topicArn(topicArn)).attributes().get("DisplayName");
        }

        private void update(String property, boolean failAfterTopic, String status) {
            cfn.updateStack(request -> request.stackName(stack.stackId())
                    .templateBody(template(topicName, property, failAfterTopic)));
            Stack updated = CfnStackWaits.awaitStatus(cfn, stack.stackId(), status);
            assertEquals(stack.stackId(), updated.stackId());
            assertEquals(topicArn, cfn.describeStackResource(request -> request.stackName(stack.stackId())
                    .logicalResourceId("Topic")).stackResourceDetail().physicalResourceId());
        }

        @Override
        public void close() {
            try {
                List<Stack> live = cfn.describeStacks(request -> request.stackName(name)).stacks();
                assertEquals(1, live.size());
                Stack current = live.getFirst();
                assertEquals(stack.stackId(), current.stackId());
                assertEquals(stack.creationTime(), current.creationTime());
                assertTrue(current.tags().stream().anyMatch(tag ->
                        "DriftFixture".equals(tag.key()) && marker.equals(tag.value())));
                cfn.deleteStack(request -> request.stackName(stack.stackId()));
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
                    CloudFormationException absent = assertThrows(CloudFormationException.class,
                            () -> cfn.describeStacks(request -> request.stackName(name)));
                    assertEquals("ValidationError", absent.awsErrorDetails().errorCode());
                    assertEquals(400, absent.statusCode());
                });
                SnsException absent = assertThrows(SnsException.class,
                        () -> sns.getTopicAttributes(request -> request.topicArn(topicArn)));
                assertEquals(404, absent.statusCode());
                assertEquals("NotFound", absent.awsErrorDetails().errorCode());
            } finally {
                cfn.close();
                sns.close();
            }
        }
    }
}
