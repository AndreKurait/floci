package io.floci.aws.services.cloudfront;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.WaitResponse;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.lambda.launcher.ImageCacheService.LaunchImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudFrontFunctionLifecycleTest {

    private static final String FRAME = "{\"protocol\":1,\"output\":\"{\\\"request\\\":{}}\",\"error\":null,\"logs\":[]}";
    private final AtomicReference<ContainerSpec> captured = new AtomicReference<>();
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final DockerClient docker = mock(DockerClient.class);
    private final LogContainerCmd logs = mock(LogContainerCmd.class, RETURNS_SELF);
    private final WaitContainerCmd wait = mock(WaitContainerCmd.class, RETURNS_SELF);
    private final InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    private DockerCloudFrontFunctionRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudfront().functionRuntimeImage()).thenReturn("node:22-alpine");
        ContainerBuilder builder = new ContainerBuilder(config, null, null);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.resolveImageForLaunch(anyString(), any(), isNull()))
                .thenReturn(new LaunchImage("sha256:owned-test-image", null));
        when(lifecycle.create(any())).thenAnswer(call -> {
            captured.set(call.getArgument(0));
            return "owned-container-id";
        });
        when(docker.logContainerCmd(anyString())).thenReturn(logs);
        when(docker.waitContainerCmd(anyString())).thenReturn(wait);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("not present yet"));
        frame(FRAME, 0);
        runtime = new DockerCloudFrontFunctionRuntime(builder, lifecycle, config, new ObjectMapper());
    }

    @Test
    void ambiguousCreateAbsenceRetainsCapacityUntilAnOwnedLateContainerIsRemoved() {
        when(lifecycle.create(any())).thenAnswer(call -> {
            captured.set(call.getArgument(0));
            throw new IllegalStateException("lost create response");
        });
        for (int i = 0; i < 4; i++) {
            assertEquals(500, assertThrows(AwsException.class, this::evaluate).getHttpStatus());
        }
        assertEquals(500, assertThrows(AwsException.class, this::evaluate).getHttpStatus());
        verify(lifecycle, times(4)).create(any());
        assertThrows(IllegalStateException.class, runtime::beforeReset);
        assertThrows(IllegalStateException.class, runtime::clear);
        runtime.afterReset();
        assertThrows(AwsException.class, this::evaluate);
        when(docker.inspectContainerCmd(anyString())).thenAnswer(call -> {
            String name = call.getArgument(0);
            InspectContainerCmd command = mock(InspectContainerCmd.class);
            InspectContainerResponse found = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
            String id = name.substring(name.lastIndexOf('-') + 1);
            when(found.getId()).thenReturn("late-" + id);
            // Every unique name contains the owner followed by the complete invocation UUID.
            String owner = name.substring("floci-cloudfront-test-".length(), "floci-cloudfront-test-".length() + 36);
            String invocation = name.substring(name.length() - 36);
            when(found.getConfig().getLabels()).thenReturn(Map.of(
                    DockerCloudFrontFunctionRuntime.OWNER_LABEL, owner,
                    DockerCloudFrontFunctionRuntime.INVOCATION_LABEL, invocation));
            when(command.exec()).thenReturn(found);
            return command;
        });
        runtime.beforeReset();
        runtime.clear();
        runtime.afterReset();
        verify(lifecycle, times(4)).stopAndRemoveStrict(anyString(), isNull(), eq(0), eq(true));
        doReturn("next-owned-id").when(lifecycle).create(any());
        assertEquals("{\"request\":{}}", evaluate().evaluation().output());
    }

    @Test
    void observedIdentitySurvivesFailedRemovalAndSubsequentNameDisappearance() {
        when(lifecycle.create(any())).thenAnswer(call -> {
            captured.set(call.getArgument(0));
            throw new IllegalStateException("lost create response");
        });
        doAnswer(call -> owned("observed-id")).when(inspect).exec();
        doThrow(new IllegalStateException("lost remove response"))
                .when(lifecycle).stopAndRemoveStrict("observed-id", null, 0, true);
        assertThrows(AwsException.class, this::evaluate);
        assertEquals(List.of("observed-id"), List.copyOf(runtime.activeContainers().values()));
        doThrow(new NotFoundException("the unique name disappeared")).when(inspect).exec();
        doNothing().when(lifecycle).stopAndRemoveStrict("observed-id", null, 0, true);
        runtime.beforeReset();
        assertTrue(runtime.activeContainers().isEmpty());
        verify(inspect, times(1)).exec();
        verify(lifecycle, times(2)).stopAndRemoveStrict("observed-id", null, 0, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void blankCreateIdentityRemainsUncertain(String id) {
        when(lifecycle.create(any())).thenReturn(id);
        assertThrows(AwsException.class, this::evaluate);
        assertThrows(IllegalStateException.class, runtime::beforeReset);
        assertThrows(IllegalStateException.class, runtime::clear);
        verify(lifecycle, never()).startCreated(anyString(), any());
    }

    @Test
    void foreignNameDoesNotAuthorizeCleanup() {
        when(lifecycle.create(any())).thenAnswer(call -> {
            captured.set(call.getArgument(0));
            throw new IllegalStateException("lost response");
        });
        doAnswer(call -> {
            InspectContainerResponse found = owned("foreign-id");
            when(found.getConfig().getLabels()).thenReturn(Map.of("foreign", "resource"));
            return found;
        }).when(inspect).exec();
        assertThrows(AwsException.class, this::evaluate);
        assertThrows(IllegalStateException.class, runtime::beforeReset);
        verify(lifecycle, never()).stopAndRemoveStrict(anyString(), isNull(), eq(0), eq(true));
    }

    @Test
    void resetWaitsForLateCreateAndPreventsItsStart() throws Exception {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(lifecycle.create(any())).thenAnswer(call -> {
            creating.countDown();
            await(release);
            return "late-created-id";
        });
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> request = workers.submit(() -> assertThrows(AwsException.class, this::evaluate));
            await(creating);
            Future<?> reset = workers.submit(runtime::beforeReset);
            awaitClosedAdmission(workers);
            assertFalse(reset.isDone());
            release.countDown();
            request.get(10, TimeUnit.SECONDS);
            reset.get(10, TimeUnit.SECONDS);
            verify(lifecycle, never()).startCreated(anyString(), any());
            verify(lifecycle).stopAndRemoveStrict("late-created-id", null, 0, true);
            runtime.clear();
            runtime.afterReset();
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "logs"})
    void independentWatchdogRemovesAlreadyCreatedChildWhileTransportIsBlocked(String blocked) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch removed = new CountDownLatch(1);
        if (blocked.equals("start")) {
            when(lifecycle.startCreated(anyString(), any())).thenAnswer(call -> {
                entered.countDown();
                await(release);
                throw new IllegalStateException("lost already-started response");
            });
        } else {
            doAnswer(call -> {
                entered.countDown();
                await(release);
                return call.getArgument(0);
            }).when(logs).exec(any());
        }
        doAnswer(call -> { removed.countDown(); return null; })
                .when(lifecycle).stopAndRemoveStrict("owned-container-id", null, 0, true);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> request = workers.submit(() -> assertThrows(AwsException.class, this::evaluate));
            await(entered);
            assertTrue(removed.await(8, TimeUnit.SECONDS), "watchdog must not wait for the blocked transport response");
            assertFalse(request.isDone());
            release.countDown();
            request.get(10, TimeUnit.SECONDS);
            assertTrue(runtime.activeContainers().isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    void fourCapturedRequestsOccupyCapacityAndResetClosesAdmission() throws Exception {
        CountDownLatch capturedAll = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> requests = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                requests.add(workers.submit(() -> assertThrows(AwsException.class, () -> runtime.evaluate(() -> {
                    capturedAll.countDown();
                    await(release);
                    return snapshot();
                }, "{}"))));
            }
            await(capturedAll);
            assertThrows(AwsException.class, this::evaluate);
            Future<?> reset = workers.submit(runtime::beforeReset);
            awaitClosedAdmission(workers);
            release.countDown();
            for (Future<?> request : requests) {
                request.get(10, TimeUnit.SECONDS);
            }
            reset.get(10, TimeUnit.SECONDS);
            verify(lifecycle, never()).create(any());
            runtime.afterReset();
            assertEquals("{\"request\":{}}", evaluate().evaluation().output());
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"protocol\":\"1\",\"output\":\"{}\",\"error\":null,\"logs\":[]}",
            "{\"protocol\":1.0,\"output\":\"{}\",\"error\":null,\"logs\":[]}",
            "{\"protocol\":4294967297,\"output\":\"{}\",\"error\":null,\"logs\":[]}",
            "{\"protocol\":1,\"output\":\"{}\",\"error\":null,\"logs\":[3]}",
            "{\"protocol\":1,\"output\":null,\"error\":null,\"logs\":[]}",
            "{\"protocol\":1,\"output\":\"{}\",\"error\":\"also-error\",\"logs\":[]}",
            "{\"protocol\":1,\"output\":\"{}\",\"error\":null,\"logs\":[]}{}"
    })
    void malformedHarnessFramesAreInfrastructureFailures(String output) throws Exception {
        frame(output, 0);
        assertEquals(500, assertThrows(AwsException.class, this::evaluate).getHttpStatus());
        verify(lifecycle).stopAndRemoveStrict("owned-container-id", null, 0, true);
    }

    @Test
    void nonzeroStartupExitIsAnInfrastructureFailure() throws Exception {
        frame("", 127);
        assertEquals("TestFunctionFailed", assertThrows(AwsException.class, this::evaluate).getErrorCode());
        verify(lifecycle).stopAndRemoveStrict("owned-container-id", null, 0, true);
    }

    @Test
    void cleanupFailureOverridesAValidResultUntilResetCanConfirmRemoval() {
        doThrow(new IllegalStateException("remove failed"))
                .when(lifecycle).stopAndRemoveStrict("owned-container-id", null, 0, true);
        assertThrows(AwsException.class, this::evaluate);
        assertEquals(1, runtime.activeContainers().size());
        assertThrows(IllegalStateException.class, runtime::beforeReset);
        doNothing().when(lifecycle).stopAndRemoveStrict("owned-container-id", null, 0, true);
        runtime.beforeReset();
        runtime.clear();
        runtime.afterReset();
        assertEquals("{\"request\":{}}", evaluate().evaluation().output());
    }

    @Test
    void completedExecutionDoesNotExpireWhileStrictCleanupIsStillAwaitingConfirmation() throws Exception {
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> { cleaning.countDown(); await(release); return null; })
                .when(lifecycle).stopAndRemoveStrict("owned-container-id", null, 0, true);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CloudFrontFunctionRuntime.Result> request = workers.submit(this::evaluate);
            await(cleaning);
            assertEquals(1, runtime.activeContainers().size());
            assertThrows(TimeoutException.class, () -> request.get(6, TimeUnit.SECONDS));
            assertEquals(1, runtime.activeContainers().size());
            release.countDown();
            assertEquals("{\"request\":{}}", request.get(5, TimeUnit.SECONDS).evaluation().output());
            assertTrue(runtime.activeContainers().isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    void aTrueResetStillCancelsCompletedExecutionWhileItsCleanupIsUnconfirmed() throws Exception {
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> { cleaning.countDown(); await(release); return null; })
                .when(lifecycle).stopAndRemoveStrict("owned-container-id", null, 0, true);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AwsException> request = workers.submit(() -> assertThrows(AwsException.class, this::evaluate));
            await(cleaning);
            Future<?> reset = workers.submit(runtime::beforeReset);
            awaitClosedAdmission(workers);
            assertFalse(reset.isDone());
            assertThrows(IllegalStateException.class, runtime::clear);
            runtime.afterReset();
            assertFalse(runtime.acceptingRequests());
            release.countDown();
            assertTrue(request.get(5, TimeUnit.SECONDS).getMessage().contains("emulator reset"));
            reset.get(5, TimeUnit.SECONDS);
            runtime.clear();
            runtime.afterReset();
            assertTrue(runtime.acceptingRequests());
        } finally {
            release.countDown();
        }
    }

    private void frame(String output, int status) throws Exception {
        doAnswer(call -> {
            ResultCallback<Frame> callback = call.getArgument(0);
            callback.onStart(() -> {});
            callback.onNext(new Frame(StreamType.STDOUT, output.getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        }).when(logs).exec(any());
        doAnswer(call -> {
            ResultCallback<WaitResponse> callback = call.getArgument(0);
            callback.onStart(() -> {});
            WaitResponse response = mock(WaitResponse.class);
            when(response.getStatusCode()).thenReturn(status);
            callback.onNext(response);
            callback.onComplete();
            return callback;
        }).when(wait).exec(any());
    }

    private InspectContainerResponse owned(String id) {
        InspectContainerResponse found = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(found.getId()).thenReturn(id);
        when(found.getConfig().getLabels()).thenReturn(captured.get().labels());
        return found;
    }

    private CloudFrontFunctionRuntime.Result evaluate() {
        return runtime.evaluate(CloudFrontFunctionLifecycleTest::snapshot, "{}");
    }

    private static CloudFrontFunctionSnapshot snapshot() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new CloudFrontFunctionSnapshot("000000000000", "aws", "owned-function-arn", "owned", "DEVELOPMENT",
                "captured-etag", now, now, "UNPUBLISHED", "cloudfront-js-2.0", "owned", "function handler(e){return e;}",
                List.of());
    }

    private void awaitClosedAdmission(ExecutorService workers) throws Exception {
        Future<?> refused = workers.submit(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runtime.acceptingRequests() && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertFalse(runtime.acceptingRequests(), "reset must close admission before pending creates finish");
        });
        refused.get(8, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "bounded interleaving latch");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
