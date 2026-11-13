package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

/** A delete accepted during continued rollback waits for the last rollback event and keeps its own token. */
@QuarkusTest
class CloudFormationContinueRollbackTokenConcurrencyIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final long TIMEOUT_SECONDS = 10;

    @Inject
    CloudFormationService service;

    @InjectSpy
    CloudWatchDashboardsService dashboardsService;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deleteWaitsForContinuedRollbackAndKeepsOperationTokensSeparate(boolean restoreFailsAgain) throws Exception {
        try (OwnedStack owned = new OwnedStack()) {
            cfn("CreateStack", owned.name)
                    .formParam("TemplateBody", template(owned.name, "Before", false))
                    .formParam("ClientRequestToken", "initial-create-token")
                    .when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStatus(owned.name, "CREATE_COMPLETE");
            String stackId = service.describeStacks(owned.name, REGION).getFirst().getStackId();

            ArgumentMatcher<String> priorBody = body -> body != null && body.contains("Before");
            Mockito.doThrow(new IllegalStateException("first restore fails"))
                    .doAnswer(invocation -> {
                        owned.restoreEntered.countDown();
                        assertTrue(owned.releaseRestore.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                                "the test never released the continued restore");
                        if (restoreFailsAgain) {
                            throw new IllegalStateException("continued restore also fails");
                        }
                        return invocation.callRealMethod();
                    })
                    .when(dashboardsService).putDashboard(eq(owned.name), argThat(priorBody), anyMap(), anyString());
            cfn("UpdateStack", owned.name)
                    .formParam("TemplateBody", template(owned.name, "After", true))
                    .formParam("ClientRequestToken", "failed-update-token")
                    .when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStatus(owned.name, "UPDATE_ROLLBACK_FAILED");
            Set<String> previousIds = service.describeStackEvents(stackId, REGION).stream()
                    .map(StackEvent::getEventId).collect(Collectors.toSet());

            owned.rollback = service.continueUpdateRollback(owned.name, List.of(), false, REGION,
                    null, "continued-rollback-token");
            assertTrue(owned.restoreEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the continued rollback never reached the restore");
            cfn("DeleteStack", owned.name).formParam("ClientRequestToken", "deferred-delete-token")
                    .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_ROLLBACK_IN_PROGRESS", service.describeStacks(stackId, REGION).getFirst().getStatus());
            assertEquals("continued-rollback-token", service.describeStacks(stackId, REGION).getFirst().getClientRequestToken());
            assertFalse(service.describeStackEvents(stackId, REGION).stream()
                    .anyMatch(event -> "deferred-delete-token".equals(event.getClientRequestToken())));

            owned.releaseRestore.countDown();
            owned.rollback.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            CfnStackWaits.awaitStatus(stackId, "DELETE_COMPLETE");
            List<StackEvent> events = new ArrayList<>(service.describeStackEvents(stackId, REGION).stream()
                    .filter(event -> !previousIds.contains(event.getEventId())).toList());
            Collections.reverse(events);
            String rollbackStatus = restoreFailsAgain ? "UPDATE_ROLLBACK_FAILED" : "UPDATE_ROLLBACK_COMPLETE";
            StackEvent rollbackTerminal = events.stream().filter(event -> rollbackStatus.equals(event.getResourceStatus())
                    && "AWS::CloudFormation::Stack".equals(event.getResourceType())).findFirst().orElse(null);
            StackEvent firstDelete = events.stream().filter(event -> "DELETE_IN_PROGRESS".equals(event.getResourceStatus()))
                    .findFirst().orElse(null);
            assertNotNull(rollbackTerminal, events.toString());
            assertNotNull(firstDelete, events.toString());
            assertTrue(events.indexOf(rollbackTerminal) < events.indexOf(firstDelete), events.toString());
            for (StackEvent event : events) {
                assertEquals(event.getResourceStatus().startsWith("DELETE_")
                        ? "deferred-delete-token" : "continued-rollback-token", event.getClientRequestToken());
            }
            assertTrue(events.stream().anyMatch(event -> "Dashboard".equals(event.getLogicalResourceId())
                    && "DELETE_COMPLETE".equals(event.getResourceStatus())));
        }
    }

    private final class OwnedStack implements AutoCloseable {
        private final String name = "continued-token-" + UUID.randomUUID();
        private final CountDownLatch restoreEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRestore = new CountDownLatch(1);
        private Future<?> rollback;

        @Override
        public void close() throws Exception {
            releaseRestore.countDown();
            try {
                if (rollback != null) {
                    rollback.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            } finally {
                service.deleteStack(name, REGION, ACCOUNT).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                CfnStackWaits.awaitStackDeleted(name);
            }
        }
    }

    private static String template(String name, String title, boolean failing) {
        String fail = !failing ? "" : """
                ,
                "ZFail": {"Type": "AWS::CloudFormation::Stack", "DependsOn": "Dashboard", "Properties": {}}""";
        return """
                {"Resources": {
                  "Dashboard": {"Type": "AWS::CloudWatch::Dashboard", "Properties": {
                    "DashboardName": "%s",
                    "DashboardBody": "{\\"widgets\\":[{\\"type\\":\\"text\\",\\"properties\\":{\\"markdown\\":\\"%s\\"}}]}"
                  }}%s
                }}
                """.formatted(name, title, fail);
    }
}
