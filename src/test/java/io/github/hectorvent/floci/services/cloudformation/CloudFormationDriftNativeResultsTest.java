package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackDriftDetectionStatusResponse;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourceDriftsResponse;
import software.amazon.awssdk.services.cloudformation.model.StackResourceDrift;
import software.amazon.awssdk.services.cloudformation.model.StackResourceDriftStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationDriftNativeResultsTest {
    @Test
    void nativeMutationDiffRestoreHistoryAndCompleteFilteredPages() {
        try (CfnDriftNativeFixture f = new CfnDriftNativeFixture("test", Region.US_EAST_1)) {
            String baseline = f.detect();
            assertStatus(f, baseline, "IN_SYNC", 0);
            f.display("A", "after <&>");
            StackResourceDrift changed = f.cfn.detectStackResourceDrift(request ->
                    request.stackName(f.stack.stackId()).logicalResourceId("A")).stackResourceDrift();
            assertEquals("MODIFIED", changed.stackResourceDriftStatusAsString());
            assertEquals(1, changed.propertyDifferences().size());
            assertEquals("/DisplayName", changed.propertyDifferences().getFirst().propertyPath());
            assertEquals("before", changed.propertyDifferences().getFirst().expectedValue());
            assertEquals("after <&>", changed.propertyDifferences().getFirst().actualValue());
            String drifted = f.detect();
            assertStatus(f, drifted, "DRIFTED", 1);
            DescribeStackResourceDriftsResponse first = f.cfn.describeStackResourceDrifts(request ->
                    request.stackName(f.stack.stackId()).maxResults(1));
            assertEquals(List.of("A"), first.stackResourceDrifts().stream().map(StackResourceDrift::logicalResourceId).toList());
            assertNotNull(first.nextToken());
            DescribeStackResourceDriftsResponse second = f.cfn.describeStackResourceDrifts(request ->
                    request.stackName(f.name).maxResults(1).nextToken(first.nextToken()));
            assertEquals(List.of("B"), second.stackResourceDrifts().stream().map(StackResourceDrift::logicalResourceId).toList());
            assertNull(second.nextToken());
            assertEquals(List.of("A"), f.cfn.describeStackResourceDrifts(request -> request.stackName(f.name)
                    .stackResourceDriftStatusFilters(StackResourceDriftStatus.MODIFIED)).stackResourceDrifts().stream()
                    .map(StackResourceDrift::logicalResourceId).toList());
            assertThrows(CloudFormationException.class, () -> f.cfn.describeStackResourceDrifts(request ->
                    request.stackName(f.name).nextToken(first.nextToken())
                            .stackResourceDriftStatusFilters(StackResourceDriftStatus.MODIFIED)));
            f.display("A", "before");
            String restored = f.detect();
            assertStatus(f, restored, "IN_SYNC", 0);
            assertStatus(f, drifted, "DRIFTED", 1);
            assertStatus(f, baseline, "IN_SYNC", 0);
            assertThrows(CloudFormationException.class, () -> f.cfn.describeStackResourceDrifts(request ->
                    request.stackName(f.name).nextToken(first.nextToken())));
        }
    }

    @Test
    void omittedDeclaredPropertiesAreNotInferredAndActualDeletionIsReported() {
        try (CfnDriftNativeFixture f = new CfnDriftNativeFixture("test", Region.US_EAST_1)) {
            f.sns.deleteTopic(request -> request.topicArn(f.topics.get("A")));
            StackResourceDrift deleted = f.cfn.detectStackResourceDrift(request ->
                    request.stackName(f.name).logicalResourceId("A")).stackResourceDrift();
            assertEquals("DELETED", deleted.stackResourceDriftStatusAsString());
            assertNull(deleted.expectedProperties());
            assertNull(deleted.actualProperties());
            assertTrue(deleted.propertyDifferences().isEmpty());
            assertStatus(f, f.detect(), "DRIFTED", 1);
        }
    }

    @Test
    void detectionIdentifiersAndNamesAreScopedBySignedAccountRegionAndPartition() {
        try (CfnDriftNativeFixture f = new CfnDriftNativeFixture("111111111111", Region.CN_NORTH_1)) {
            String id = f.detect();
            assertTrue(f.stack.stackId().startsWith("arn:aws-cn:cloudformation:cn-north-1:111111111111:stack/"));
            assertStatus(f, id, "IN_SYNC", 0);
            try (CloudFormationClient foreign = AwsTestClients.create(
                    CloudFormationClient.builder(), "222222222222", Region.CN_NORTH_1);
                 CloudFormationClient region = AwsTestClients.create(
                         CloudFormationClient.builder(), "111111111111", Region.CN_NORTHWEST_1)) {
                for (CloudFormationClient client : List.of(foreign, region)) {
                    assertThrows(CloudFormationException.class, () -> client.describeStackDriftDetectionStatus(
                            request -> request.stackDriftDetectionId(id)));
                    assertThrows(CloudFormationException.class, () -> client.detectStackResourceDrift(
                            request -> request.stackName(f.stack.stackId()).logicalResourceId("A")));
                    assertThrows(CloudFormationException.class, () -> client.describeStackResourceDrifts(
                            request -> request.stackName(f.name)));
                }
            }
        }
    }

    private static void assertStatus(CfnDriftNativeFixture fixture, String id, String status, int changed) {
        DescribeStackDriftDetectionStatusResponse result = fixture.cfn.describeStackDriftDetectionStatus(
                request -> request.stackDriftDetectionId(id));
        assertEquals(fixture.stack.stackId(), result.stackId());
        assertEquals(id, result.stackDriftDetectionId());
        assertEquals("DETECTION_COMPLETE", result.detectionStatusAsString());
        assertEquals(status, result.stackDriftStatusAsString());
        assertEquals(changed, result.driftedStackResourceCount());
        assertNotNull(result.timestamp());
    }
}
