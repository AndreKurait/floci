package io.floci.aws.services.cloudformation;

import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.floci.aws.services.cloudformation.provisioners.UpdateCleanupResult;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudFormationDeleteFailureTokenRaceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryWaitsUntilFailurePublicationFinishes(boolean publicationFails) throws Exception {
        Stack stack = new Stack();
        stack.setStackName("delete-failure-token");
        stack.setStackId("arn:aws:cloudformation:us-east-1:000000000000:stack/delete-failure-token/id");
        stack.setAccountId(ACCOUNT);
        stack.setRegion(REGION);
        stack.setStatus("CREATE_COMPLETE");
        stack.setTemplateBody("{\"Resources\":{}}");
        StackResource resource = new StackResource();
        resource.setLogicalId("Resource");
        resource.setPhysicalId("resource");
        resource.setResourceType("AWS::Test::Resource");
        resource.setStatus("CREATE_COMPLETE");
        stack.getResources().put("Resource", resource);
        InMemoryStorage<String, Stack> backing = new InMemoryStorage<>();
        backing.put(stack.getStackName() + ":" + REGION, stack);
        InMemoryStorageFactory storage = new InMemoryStorageFactory(ACCOUNT)
                .withStore("cloudformation-stacks.json", backing);
        CfnResourceDispatcher dispatcher = mock(CfnResourceDispatcher.class);
        when(dispatcher.completeDeleteCleanup(any(StackResource.class))).thenReturn(UpdateCleanupResult.notApplicable());
        doThrow(new IllegalStateException("owned delete failure"))
                .doNothing().when(dispatcher).delete(any(StackResource.class), eq(REGION));
        CloudFormationEventPublisher publisher = mock(CloudFormationEventPublisher.class);
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishing.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "failure publication was not released");
            assertEquals("first-token", stack.getClientRequestToken());
            if (publicationFails) {
                throw new IllegalStateException("owned publication failure");
            }
            return null;
        }).when(publisher).stackStatusChanged(eq(stack), eq(ACCOUNT), eq("DELETE_FAILED"), anyString());
        CloudFormationService service = CloudFormationServiceFixture.create(
                dispatcher, mock(S3Service.class), Clock.systemUTC(), storage, publisher);
        service.loadPersistedState();
        Future<?> first = null;
        try {
            first = service.deleteStackForRequest(stack.getStackName(), List.of(), REGION, "first-token");
            assertTrue(publishing.await(10, TimeUnit.SECONDS), "delete did not reach failure publication");
            Future<?> duplicate = service.deleteStackForRequest(stack.getStackName(), List.of(), REGION, "duplicate-token");
            duplicate.get(10, TimeUnit.SECONDS);
            assertEquals("DELETE_IN_PROGRESS", stack.getStatus());
            assertEquals("first-token", stack.getClientRequestToken());
            verify(dispatcher, times(1)).delete(any(StackResource.class), eq(REGION));
            release.countDown();
            Future<?> failed = first;
            ExecutionException failure = assertThrows(ExecutionException.class, () -> failed.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains(publicationFails ? "publication failure" : "Resource"));
            assertEquals("DELETE_FAILED", stack.getStatus());
            assertEquals("first-token", stack.getClientRequestToken());
            assertTrue(stack.eventsSnapshot().stream()
                    .filter(event -> "DELETE_FAILED".equals(event.getResourceStatus()))
                    .allMatch(event -> "first-token".equals(event.getClientRequestToken())));
            service.deleteStackForRequest(stack.getStackName(), List.of(), REGION, "retry-token")
                    .get(10, TimeUnit.SECONDS);
            assertEquals("DELETE_COMPLETE", stack.getStatus());
            assertTrue(stack.eventsSnapshot().stream()
                    .filter(event -> "DELETE_COMPLETE".equals(event.getResourceStatus()))
                    .allMatch(event -> "retry-token".equals(event.getClientRequestToken())));
        } finally {
            release.countDown();
            try {
                if (first != null) {
                    try {
                        first.get(10, TimeUnit.SECONDS);
                    } catch (ExecutionException expected) {
                        // The first deletion deliberately fails; it must finish before fixture shutdown.
                    }
                }
            } finally {
                service.stop();
            }
        }
    }
}
