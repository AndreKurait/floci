package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.CfnRequests;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsResponse;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Operation tokens belong to stored events, including resource progress and historical events. */
@QuarkusTest
@Timeout(90)
class CloudFormationEventRequestTokenSdkTest {
    private static final Set<String> TERMINAL = Set.of("CREATE_COMPLETE", "CREATE_FAILED",
            "ROLLBACK_COMPLETE", "ROLLBACK_FAILED", "UPDATE_COMPLETE", "UPDATE_ROLLBACK_COMPLETE",
            "UPDATE_ROLLBACK_FAILED", "DELETE_COMPLETE", "DELETE_FAILED");

    @Test
    void createUpdateAndDeleteKeepEachOperationsTokenOnStackAndResourceEvents() {
        try (Owned owned = new Owned()) {
            List<StackEvent> created = owned.create("create-token", owned.template(7), "CREATE_COMPLETE");
            assertToken(created, "create-token");
            assertTrue(created.stream().anyMatch(event -> "Group".equals(event.logicalResourceId())));

            List<StackEvent> updated = owned.update("update-token", owned.template(14));
            assertToken(updated, "update-token");
            assertTrue(updated.stream().anyMatch(event -> "Group".equals(event.logicalResourceId())));
            owned.assertHistoryUnchanged(created);

            List<StackEvent> deleted = owned.delete("delete-token");
            assertToken(deleted, "delete-token");
            assertTrue(deleted.stream().anyMatch(event -> "Group".equals(event.logicalResourceId())));
            owned.assertHistoryUnchanged(created);
            owned.assertHistoryUnchanged(updated);
        }
    }

    @Test
    void changeSetClientTokenIsSeparateFromTheExecutionRequestToken() {
        try (Owned owned = new Owned()) {
            owned.attempted = true;
            owned.stackId = owned.cfn.createChangeSet(request -> request.stackName(owned.name)
                    .changeSetName("initial").changeSetType("CREATE")
                    .clientToken("change-set-token").templateBody(owned.template(7))).stackId();
            owned.awaitChangeSet("initial");
            List<StackEvent> review = owned.events();
            assertFalse(review.isEmpty());
            assertTrue(review.stream().allMatch(event -> event.clientRequestToken() == null));

            Set<String> before = ids(review);
            owned.cfn.executeChangeSet(request -> request.stackName(owned.stackId)
                    .changeSetName("initial").clientRequestToken("execute-create-token"));
            List<StackEvent> created = owned.awaitEvents(before, "CREATE_COMPLETE");
            assertToken(created, "execute-create-token");
            owned.assertHistoryUnchanged(review);

            owned.cfn.createChangeSet(request -> request.stackName(owned.stackId)
                    .changeSetName("next").changeSetType("UPDATE")
                    .clientToken("next-change-set-token").templateBody(owned.template(14)));
            owned.awaitChangeSet("next");
            Set<String> beforeUpdate = ids(owned.events());
            owned.cfn.executeChangeSet(request -> request.stackName(owned.stackId)
                    .changeSetName("next").clientRequestToken("execute-update-token"));
            assertToken(owned.awaitEvents(beforeUpdate, "UPDATE_COMPLETE"), "execute-update-token");
            owned.assertHistoryUnchanged(created);
        }
    }

    @Test
    void failedCreateAndRollbackEventsKeepTheCreateToken() {
        try (Owned owned = new Owned()) {
            String template = """
                    {"Resources":{"MissingGroupStream":{"Type":"AWS::Logs::LogStream",
                    "Properties":{"LogGroupName":"%s-missing","LogStreamName":"stream"}}}}
                    """.formatted(owned.group);
            List<StackEvent> events = owned.create("failed-create-token", template, "ROLLBACK_COMPLETE");
            assertToken(events, "failed-create-token");
            assertTrue(events.stream().anyMatch(event -> "MissingGroupStream".equals(event.logicalResourceId())
                    && "CREATE_FAILED".equals(event.resourceStatusAsString())));
            assertTrue(events.stream().anyMatch(event -> "ROLLBACK_IN_PROGRESS"
                    .equals(event.resourceStatusAsString())));
        }
    }

    @Test
    void anUpdateWithoutATokenDoesNotReuseThePreviousOperationsToken() {
        try (Owned owned = new Owned()) {
            List<StackEvent> created = owned.create("previous-token", owned.template(7), "CREATE_COMPLETE");
            Set<String> before = ids(owned.events());
            // A form request proves the optional token was omitted, without SDK token generation.
            CfnRequests.cfn("UpdateStack", owned.stackId).formParam("TemplateBody", owned.template(14))
                    .when().post("/").then().statusCode(200);
            List<StackEvent> updated = owned.awaitEvents(before, "UPDATE_COMPLETE");
            assertFalse(updated.isEmpty());
            for (StackEvent event : updated) {
                assertNull(event.clientRequestToken(), event.toString());
            }
            assertToken(created, "previous-token");
            owned.assertHistoryUnchanged(created);
        }
    }

    private static void assertToken(List<StackEvent> events, String expected) {
        assertFalse(events.isEmpty());
        for (StackEvent event : events) {
            assertEquals(expected, event.clientRequestToken(), event.toString());
        }
    }

    private static Set<String> ids(List<StackEvent> events) {
        return events.stream().map(StackEvent::eventId).collect(Collectors.toSet());
    }

    private static void pause() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the owned stack", interrupted);
        }
    }

    private static final class Owned implements AutoCloseable {
        private final CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder()
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(5)).retryPolicy(RetryPolicy.none())),
                "000000000000", Region.US_EAST_1);
        private final CloudWatchLogsClient logs = AwsTestClients.create(CloudWatchLogsClient.builder()
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(5)).retryPolicy(RetryPolicy.none())),
                "000000000000", Region.US_EAST_1);
        private final String name = "event-token-" + UUID.randomUUID().toString().replace("-", "");
        private final String group = "/" + name;
        private String stackId;
        private boolean attempted;
        private boolean deleted;

        private String template(int retention) {
            return """
                    {"Resources":{"Group":{"Type":"AWS::Logs::LogGroup",
                    "Properties":{"LogGroupName":"%s","RetentionInDays":%d}}}}
                    """.formatted(group, retention);
        }

        private List<StackEvent> create(String token, String template, String expected) {
            attempted = true;
            stackId = cfn.createStack(request -> request.stackName(name).templateBody(template)
                    .clientRequestToken(token)).stackId();
            // Floci's existing internal change-set placeholder precedes the create operation.
            return awaitEvents(Set.of(), expected).stream()
                    .filter(event -> !"REVIEW_IN_PROGRESS".equals(event.resourceStatusAsString())).toList();
        }

        private List<StackEvent> update(String token, String template) {
            Set<String> before = ids(events());
            cfn.updateStack(request -> request.stackName(stackId).templateBody(template).clientRequestToken(token));
            return awaitEvents(before, "UPDATE_COMPLETE");
        }

        private List<StackEvent> delete(String token) {
            Set<String> before = ids(events());
            cfn.deleteStack(request -> request.stackName(stackId).clientRequestToken(token));
            List<StackEvent> result = awaitEvents(before, "DELETE_COMPLETE");
            assertMissingByName();
            assertTrue(logs.describeLogGroups(request -> request.logGroupNamePrefix(group)).logGroups().stream()
                    .noneMatch(value -> group.equals(value.logGroupName())));
            deleted = true;
            return result;
        }

        private void awaitChangeSet(String changeSet) {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                String status = cfn.describeChangeSet(request -> request.stackName(stackId)
                        .changeSetName(changeSet)).statusAsString();
                if ("CREATE_COMPLETE".equals(status)) {
                    return;
                }
                assertFalse("FAILED".equals(status), "Change set failed");
                pause();
            }
            fail("Owned change set did not settle in ten seconds");
        }

        private List<StackEvent> events() {
            List<StackEvent> result = new ArrayList<>();
            Set<String> tokens = new HashSet<>();
            String cursor = null;
            for (int page = 0; page < 20; page++) {
                String next = cursor;
                DescribeStackEventsResponse response = cfn.describeStackEvents(request ->
                        request.stackName(stackId).nextToken(next));
                result.addAll(response.stackEvents());
                cursor = response.nextToken();
                if (cursor == null) {
                    assertEquals(result.size(), ids(result).size(), "Duplicate event ids");
                    return result;
                }
                assertTrue(tokens.add(cursor), "Repeated event cursor");
            }
            throw new AssertionError("Owned event history exceeded twenty pages");
        }

        private List<StackEvent> awaitEvents(Set<String> before, String expected) {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            List<StackEvent> latest = List.of();
            while (System.nanoTime() < deadline) {
                latest = events().stream().filter(event -> !before.contains(event.eventId())).toList();
                for (StackEvent event : latest) {
                    if (stackId.equals(event.physicalResourceId())
                            && name.equals(event.logicalResourceId())
                            && "AWS::CloudFormation::Stack".equals(event.resourceType())
                            && TERMINAL.contains(event.resourceStatusAsString())) {
                        assertEquals(expected, event.resourceStatusAsString(), latest.toString());
                        if ("DELETE_COMPLETE".equals(expected)
                                || expected.equals(cfn.describeStacks(request -> request.stackName(stackId))
                                        .stacks().getFirst().stackStatusAsString())) {
                            return latest;
                        }
                    }
                }
                pause();
            }
            throw new AssertionError("Owned stack did not reach " + expected + ": " + latest);
        }

        private void assertHistoryUnchanged(List<StackEvent> previous) {
            Map<String, StackEvent> current = events().stream()
                    .collect(Collectors.toMap(StackEvent::eventId, event -> event));
            for (StackEvent event : previous) {
                assertEquals(event, current.get(event.eventId()), "Historical event changed");
            }
        }

        private void assertMissingByName() {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    cfn.describeStacks(request -> request.stackName(name));
                } catch (CloudFormationException missing) {
                    assertEquals(400, missing.statusCode());
                    assertEquals("ValidationError", missing.awsErrorDetails().errorCode());
                    assertEquals("Stack with id " + name + " does not exist",
                            missing.awsErrorDetails().errorMessage());
                    return;
                }
                pause();
            }
            fail("Owned stack is still visible by name after deletion");
        }

        @Override
        public void close() {
            try {
                if (attempted && !deleted) {
                    if (stackId == null) {
                        try {
                            stackId = cfn.describeStacks(request -> request.stackName(name))
                                    .stacks().getFirst().stackId();
                        } catch (CloudFormationException missing) {
                            assertEquals(400, missing.statusCode());
                            assertEquals("ValidationError", missing.awsErrorDetails().errorCode());
                            assertEquals("Stack with id " + name + " does not exist",
                                    missing.awsErrorDetails().errorMessage());
                            deleted = true;
                        }
                    }
                    if (!deleted) {
                        delete("cleanup-token");
                    }
                }
            } finally {
                try {
                    logs.close();
                } finally {
                    cfn.close();
                }
            }
        }
    }
}
