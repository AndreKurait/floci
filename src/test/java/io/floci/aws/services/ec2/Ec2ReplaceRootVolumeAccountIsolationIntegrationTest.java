package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeReplaceRootVolumeTasksRequest;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.ReplaceRootVolumeTask;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2ReplaceRootVolumeAccountIsolationIntegrationTest {

    @Test
    void anotherAccountCannotDescribeATaskByListingFilteringOrId() {
        try (Ec2Client owner = AwsTestClients.create(Ec2Client.builder(), "111122223333", Region.US_EAST_1);
             Ec2Client other = AwsTestClients.create(Ec2Client.builder(), "444455556666", Region.US_EAST_1)) {
            String instance = launch(owner);
            try {
                ReplaceRootVolumeTask task = owner.createReplaceRootVolumeTask(request -> request
                        .instanceId(instance)).replaceRootVolumeTask();
                String taskId = task.replaceRootVolumeTaskId();
                assertEquals(instance, owner.describeReplaceRootVolumeTasks(request -> request
                        .replaceRootVolumeTaskIds(taskId)).replaceRootVolumeTasks().getFirst().instanceId());
                assertTrue(other.describeReplaceRootVolumeTasks(DescribeReplaceRootVolumeTasksRequest.builder().build())
                        .replaceRootVolumeTasks().stream()
                        .noneMatch(candidate -> taskId.equals(candidate.replaceRootVolumeTaskId())));
                assertTrue(other.describeReplaceRootVolumeTasks(request -> request.filters(Filter.builder()
                        .name("instance-id").values(instance).build())).replaceRootVolumeTasks().isEmpty());
                Ec2Exception error = assertThrows(Ec2Exception.class, () -> other.describeReplaceRootVolumeTasks(
                        request -> request.replaceRootVolumeTaskIds(taskId)));
                assertEquals("InvalidReplaceRootVolumeTaskId.NotFound", error.awsErrorDetails().errorCode());
            } finally {
                owner.terminateInstances(request -> request.instanceIds(instance));
            }
        }
    }

    @Test
    void launchStateReplacementAcceptsDeleteReplacedRootVolume() {
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "111122223333", Region.US_EAST_1)) {
            String instance = launch(client);
            try {
                ReplaceRootVolumeTask task = assertDoesNotThrow(() -> client.createReplaceRootVolumeTask(
                        request -> request.instanceId(instance).deleteReplacedRootVolume(true)))
                        .replaceRootVolumeTask();
                assertEquals(Boolean.TRUE, task.deleteReplacedRootVolume());
            } finally {
                client.terminateInstances(request -> request.instanceIds(instance));
            }
        }
    }

    @Test
    void aClientTokenCannotBeReusedWithDifferentDeletionBehavior() {
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "111122223333", Region.US_EAST_1)) {
            String instance = launch(client);
            String token = UUID.randomUUID().toString();
            try {
                client.createReplaceRootVolumeTask(request -> request.instanceId(instance).clientToken(token)
                        .deleteReplacedRootVolume(false));
                Ec2Exception error = assertThrows(Ec2Exception.class, () -> client.createReplaceRootVolumeTask(
                        request -> request.instanceId(instance).clientToken(token).deleteReplacedRootVolume(true)));
                assertEquals("IdempotentParameterMismatch", error.awsErrorDetails().errorCode());
            } finally {
                client.terminateInstances(request -> request.instanceIds(instance));
            }
        }
    }

    @Test
    void differentAccountsCanUseTheSameClientTokenForTheirOwnInstances() {
        try (Ec2Client first = AwsTestClients.create(Ec2Client.builder(), "111122223333", Region.US_EAST_1);
             Ec2Client second = AwsTestClients.create(Ec2Client.builder(), "444455556666", Region.US_EAST_1)) {
            String firstInstance = launch(first);
            String secondInstance = launch(second);
            String token = UUID.randomUUID().toString();
            try {
                String firstTask = first.createReplaceRootVolumeTask(request -> request.instanceId(firstInstance)
                        .clientToken(token)).replaceRootVolumeTask().replaceRootVolumeTaskId();
                String secondTask = second.createReplaceRootVolumeTask(request -> request.instanceId(secondInstance)
                        .clientToken(token)).replaceRootVolumeTask().replaceRootVolumeTaskId();
                assertNotEquals(firstTask, secondTask);
                assertEquals(firstTask, first.createReplaceRootVolumeTask(request -> request.instanceId(firstInstance)
                        .clientToken(token)).replaceRootVolumeTask().replaceRootVolumeTaskId());
                assertEquals(secondTask, second.createReplaceRootVolumeTask(request -> request.instanceId(secondInstance)
                        .clientToken(token)).replaceRootVolumeTask().replaceRootVolumeTaskId());
            } finally {
                first.terminateInstances(request -> request.instanceIds(firstInstance));
                second.terminateInstances(request -> request.instanceIds(secondInstance));
            }
        }
    }

    private static String launch(Ec2Client client) {
        String instance = client.runInstances(request -> request.imageId("ami-amazonlinux2023")
                .instanceType("t2.micro").minCount(1).maxCount(1)).instances().getFirst().instanceId();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("running",
                client.describeInstances(request -> request.instanceIds(instance)).reservations().getFirst()
                        .instances().getFirst().state().nameAsString()));
        return instance;
    }
}
