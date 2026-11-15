package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.cloudformation.provisioners.UpdateCleanupResult;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class CloudFormationDeferredDeleteRetryTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryCannotReplaceTheFutureOwnedByADeferredDelete(boolean deleteFails) throws Exception {
        Stack stack = new Stack();
        stack.setStackName("deferred-retry");
        stack.setStackId("arn:aws:cloudformation:us-east-1:000000000000:stack/deferred-retry/id");
        stack.setAccountId(ACCOUNT);
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_ROLLBACK_FAILED");
        stack.setTemplateBody("{\"Resources\":{}}");
        StackResource resource = new StackResource();
        resource.setLogicalId("Resource");
        resource.setPhysicalId("resource");
        resource.setResourceType("AWS::Test::Resource");
        resource.setStatus("CREATE_COMPLETE");
        stack.getResources().put("Resource", resource);
        InMemoryStorage<String, Stack> backing = new InMemoryStorage<>();
        backing.put("deferred-retry:" + REGION, stack);
        InMemoryStorageFactory storage = new InMemoryStorageFactory(ACCOUNT)
                .withStore("cloudformation-stacks.json", backing);
        CfnResourceDispatcher dispatcher = mock(CfnResourceDispatcher.class);
        when(dispatcher.completeDeleteCleanup(any(StackResource.class))).thenReturn(UpdateCleanupResult.notApplicable());
        if (deleteFails) {
            doThrow(new IllegalStateException("owned delete failure")).when(dispatcher).delete(any(StackResource.class), eq(REGION));
        }
        CloudFormationService service = spy(CloudFormationServiceFixture.create(
                dispatcher, mock(S3Service.class), Clock.systemUTC(), storage));
        service.loadPersistedState();
        CountDownLatch terminal = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            terminal.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "rollback completion was not released");
            return null;
        }).when(service).resumeUpdateRollback(eq(stack), any(), eq(false), anyString());
        Future<?> rollback = null;
        Future<?> deletion = null;
        try {
            rollback = service.continueUpdateRollback(stack.getStackName(), List.of("Missing"), false,
                    REGION, null, "rollback-token");
            assertTrue(terminal.await(10, TimeUnit.SECONDS), "rollback did not reach its terminal state");
            assertEquals("UPDATE_ROLLBACK_FAILED", stack.getStatus());
            deletion = service.deleteStackWithRole(stack.getStackName(), List.of(), REGION, null, "delete-token");
            assertFalse(deletion.isDone());
            AwsException refused = assertThrows(AwsException.class, () -> service.continueUpdateRollback(
                    stack.getStackName(), List.of("Missing"), false, REGION, null, "retry-token"));
            assertEquals("ValidationError", refused.getErrorCode());
            assertEquals("rollback-token", stack.getClientRequestToken());
            assertFalse(stack.eventsSnapshot().stream()
                    .anyMatch(event -> "retry-token".equals(event.getClientRequestToken())));
            release.countDown();
            rollback.get(10, TimeUnit.SECONDS);
            Future<?> acceptedDelete = deletion;
            if (deleteFails) {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> acceptedDelete.get(10, TimeUnit.SECONDS));
                assertTrue(failure.getCause().getMessage().contains("Resource"));
                assertEquals("DELETE_FAILED", stack.getStatus());
            } else {
                acceptedDelete.get(10, TimeUnit.SECONDS);
                assertEquals("DELETE_COMPLETE", stack.getStatus());
            }
            assertTrue(stack.eventsSnapshot().stream()
                    .filter(event -> event.getResourceStatus().startsWith("DELETE_"))
                    .allMatch(event -> "delete-token".equals(event.getClientRequestToken())));
        } finally {
            release.countDown();
            try {
                if (rollback != null) {
                    rollback.get(10, TimeUnit.SECONDS);
                }
            } finally {
                service.stop();
            }
        }
    }
}
