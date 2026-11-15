package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersion;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppConfigLambdaValidatorTest {
    private static final String ACCOUNT = "111111111111";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:111111111111:function:validator";
    private final LambdaService lambda = mock(LambdaService.class);
    private final ServiceCallAuthorizer authorizer = mock(ServiceCallAuthorizer.class);
    private final ConfigurationProfile profile = profile();
    private final HostedConfigurationVersion version = version();
    private AppConfigLambdaValidator validator;

    @AfterEach
    void closeOnlyTheOwnedWorkerExecutor() {
        if (validator != null) {
            validator.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "{\"statusCode\":500,\"error\":\"ordinary\"}", "null", "not-json"})
    void ordinaryReturnBodiesDoNotIndicateAFunctionError(String payload) throws Exception {
        initialize(new ManualExecutor(true), System::nanoTime, Duration.ofSeconds(15), 2,
                new RegionResolver("us-east-1", ACCOUNT));
        when(lambda.invokeArn(eq(FUNCTION), any(), eq(InvocationType.RequestResponse)))
                .thenReturn(result(null, payload));
        assertDoesNotThrow(() -> validator.validate(FUNCTION, profile, version));
        ArgumentCaptor<byte[]> event = ArgumentCaptor.forClass(byte[].class);
        verify(lambda).invokeArn(eq(FUNCTION), event.capture(), eq(InvocationType.RequestResponse));
        JsonNode actual = new ObjectMapper().readTree(event.getValue());
        assertEquals(new ObjectMapper().valueToTree(Map.of(
                "applicationId", "app1234", "configurationProfileId", "profile",
                "configurationVersion", "17", "uri", "hosted",
                "content", Base64.getEncoder().encodeToString(version.getContent()))), actual);
        actual.properties().forEach(entry -> assertTrue(entry.getValue().isTextual()));
        assertEquals(0, validator.pendingInvocations());
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "cn-north-1,aws-cn", "us-gov-west-1,aws-us-gov",
            "unpublished-region-1,aws-cn", "unpublished-region-1,aws-us-gov"})
    void servicePermissionReceivesTheExactCapturedProfileScope(String region, String partition) {
        initialize(new ManualExecutor(true), System::nanoTime, Duration.ofSeconds(15), 2,
                new RegionResolver(region, ACCOUNT, partition));
        assertDoesNotThrow(() -> validator.validate(FUNCTION, profile, version));
        verify(authorizer).authorize("appconfig", "lambda:InvokeFunction", FUNCTION,
                "arn:" + partition + ":appconfig:" + region + ":" + ACCOUNT
                        + ":application/app1234/configurationprofile/profile", ACCOUNT);
    }

    @Test
    void permissionRefusalPrecedesAnyFunctionInvocation() {
        initialize(new ManualExecutor(true), System::nanoTime, Duration.ofSeconds(15), 2,
                new RegionResolver("us-east-1", ACCOUNT));
        when(authorizer.authorize(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Decision.DENY);
        assertEquals("BadRequestException", assertThrows(AwsException.class,
                () -> validator.validate(FUNCTION, profile, version)).getErrorCode());
        verifyNoInteractions(lambda);
        assertEquals(0, validator.pendingInvocations());
    }

    @Test
    void aFunctionErrorRejectsEvenWhenTheInvokeTransportSucceeded() {
        initialize(new ManualExecutor(true), System::nanoTime, Duration.ofSeconds(15), 2,
                new RegionResolver("us-east-1", ACCOUNT));
        when(lambda.invokeArn(eq(FUNCTION), any(), eq(InvocationType.RequestResponse)))
                .thenReturn(result("Unhandled", "{\"errorType\":\"ValueError\",\"errorMessage\":\"rejected\"}"));
        AwsException failure = assertThrows(AwsException.class,
                () -> validator.validate(FUNCTION, profile, version));
        assertEquals("BadRequestException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("Lambda Function Error"));
        assertTrue(failure.getMessage().contains("rejected"));
    }

    @Test
    void timeSpentBeforeTheWorkerRunsConsumesTheSameDeadline() {
        AtomicLong time = new AtomicLong();
        ManualExecutor executor = new ManualExecutor(true);
        executor.beforeExecute = () -> time.set(Duration.ofSeconds(16).toNanos());
        initialize(executor, time::get, Duration.ofSeconds(15), 2, new RegionResolver("us-east-1", ACCOUNT));
        assertTrue(assertThrows(AwsException.class,
                () -> validator.validate(FUNCTION, profile, version)).getMessage().contains("timed out"));
        verifyNoInteractions(authorizer, lambda);
        assertEquals(0, validator.pendingInvocations());
    }

    @Test
    void aLateAuthorizationDecisionCannotStartTheFunction() {
        AtomicLong time = new AtomicLong();
        initialize(new ManualExecutor(true), time::get, Duration.ofSeconds(15), 2,
                new RegionResolver("us-east-1", ACCOUNT));
        when(authorizer.authorize(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    time.set(Duration.ofSeconds(16).toNanos());
                    return Decision.ALLOW;
                });
        assertThrows(AwsException.class, () -> validator.validate(FUNCTION, profile, version));
        verifyNoInteractions(lambda);
        assertEquals(0, validator.pendingInvocations());
    }

    @Test
    void aResultCompletedAfterTheDeadlineCannotBeAccepted() {
        AtomicLong time = new AtomicLong();
        initialize(new ManualExecutor(true), time::get, Duration.ofSeconds(15), 2,
                new RegionResolver("us-east-1", ACCOUNT));
        when(lambda.invokeArn(eq(FUNCTION), any(), eq(InvocationType.RequestResponse))).thenAnswer(call -> {
            time.set(Duration.ofSeconds(16).toNanos());
            return result(null, "false");
        });
        assertTrue(assertThrows(AwsException.class,
                () -> validator.validate(FUNCTION, profile, version)).getMessage().contains("timed out"));
        verify(lambda).invokeArn(eq(FUNCTION), any(), eq(InvocationType.RequestResponse));
        assertEquals(0, validator.pendingInvocations());
    }

    @Test
    void abandonedQueuedWorkKeepsItsSlotUntilTheWorkerAcknowledgesCancellation() {
        ManualExecutor executor = new ManualExecutor(false);
        initialize(executor, System::nanoTime, Duration.ofMillis(20), 1, new RegionResolver("us-east-1", ACCOUNT));
        assertThrows(AwsException.class, () -> validator.validate(FUNCTION, profile, version));
        assertEquals(1, validator.pendingInvocations());
        assertTrue(assertThrows(AwsException.class,
                () -> validator.validate(FUNCTION, profile, version)).getMessage().contains("capacity"));
        assertEquals("ConflictException", assertThrows(AwsException.class, validator::checkReset).getErrorCode());
        assertEquals("ConflictException", assertThrows(AwsException.class, validator::beforeReset).getErrorCode());
        validator.afterReset();
        executor.runQueued();
        verifyNoInteractions(authorizer, lambda);
        assertEquals(0, validator.pendingInvocations());
        assertDoesNotThrow(validator::checkReset);
    }

    @Test
    void interruptingTheCallerAbandonsQueuedWorkAndPreservesItsInterruptFlag() throws Exception {
        ManualExecutor executor = new ManualExecutor(false);
        CountDownLatch submitted = new CountDownLatch(1);
        executor.beforeExecute = submitted::countDown;
        initialize(executor, System::nanoTime, Duration.ofSeconds(15), 1, new RegionResolver("us-east-1", ACCOUNT));
        AtomicReference<AwsException> failure = new AtomicReference<>();
        AtomicBoolean interruptFlag = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            failure.set(assertThrows(AwsException.class, () -> validator.validate(FUNCTION, profile, version)));
            interruptFlag.set(Thread.currentThread().isInterrupted());
        });
        assertTrue(submitted.await(2, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(2000);
        assertFalse(caller.isAlive());
        assertTrue(failure.get().getMessage().contains("interrupted"));
        assertTrue(interruptFlag.get());
        assertEquals(1, validator.pendingInvocations());
        executor.runQueued();
        verifyNoInteractions(authorizer, lambda);
        assertEquals(0, validator.pendingInvocations());
    }

    @Test
    void aRunningFunctionContinuesAfterTheCallerTimesOutAndRetainsItsCapacity() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        initialize(Executors.newVirtualThreadPerTaskExecutor(), System::nanoTime, Duration.ofMillis(250), 1,
                new RegionResolver("us-east-1", ACCOUNT));
        when(lambda.invokeArn(eq(FUNCTION), any(), eq(InvocationType.RequestResponse))).thenAnswer(call -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                interrupted.set(true);
                throw failure;
            }
            return result(null, "false");
        });
        CompletableFuture<AwsException> caller = CompletableFuture.supplyAsync(() ->
                assertThrows(AwsException.class, () -> validator.validate(FUNCTION, profile, version)));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(caller.get(2, TimeUnit.SECONDS).getMessage().contains("timed out"));
            assertEquals(1, validator.pendingInvocations());
            assertFalse(interrupted.get());
            assertThrows(AwsException.class, validator::checkReset);
            assertTrue(assertThrows(AwsException.class,
                    () -> validator.validate(FUNCTION, profile, version)).getMessage().contains("capacity"));
        } finally {
            release.countDown();
        }
        awaitDrained();
        assertFalse(interrupted.get());
        verify(lambda).invokeArn(eq(FUNCTION), any(), eq(InvocationType.RequestResponse));
        assertDoesNotThrow(validator::checkReset);
    }

    @Test
    void resetAdmissionClosesAndResumesWithoutInvokingDuringTheWipe() {
        initialize(new ManualExecutor(true), System::nanoTime, Duration.ofSeconds(15), 1,
                new RegionResolver("us-east-1", ACCOUNT));
        validator.beforeReset();
        validator.clear();
        assertThrows(AwsException.class, () -> validator.validate(FUNCTION, profile, version));
        verifyNoInteractions(authorizer, lambda);
        validator.afterReset();
        assertDoesNotThrow(() -> validator.validate(FUNCTION, profile, version));
        assertEquals(0, validator.pendingInvocations());
    }

    @Test
    void rejectedSubmissionReleasesOnlyItsUnstartedOwner() {
        ManualExecutor executor = new ManualExecutor(false);
        executor.shutdown();
        initialize(executor, System::nanoTime, Duration.ofSeconds(15), 1,
                new RegionResolver("us-east-1", ACCOUNT));
        assertThrows(AwsException.class, () -> validator.validate(FUNCTION, profile, version));
        assertEquals(0, validator.pendingInvocations());
        verifyNoInteractions(authorizer, lambda);
    }

    @Test
    void aRefusedBeforeResetDoesNotPauseLaterAdmission() {
        ManualExecutor executor = new ManualExecutor(false);
        initialize(executor, System::nanoTime, Duration.ofMillis(20), 1, new RegionResolver("us-east-1", ACCOUNT));
        assertThrows(AwsException.class, () -> validator.validate(FUNCTION, profile, version));
        assertEquals("ConflictException", assertThrows(AwsException.class, validator::beforeReset).getErrorCode());
        executor.runQueued();
        assertEquals(0, validator.pendingInvocations());
        // No afterReset is needed for a beforeReset that refused before changing admission.
        AwsException second = assertThrows(AwsException.class,
                () -> validator.validate(FUNCTION, profile, version));
        assertTrue(second.getMessage().contains("timed out"));
        assertEquals(1, validator.pendingInvocations(), "The second request was actually admitted");
        executor.runQueued();
        assertEquals(0, validator.pendingInvocations());
        verifyNoInteractions(authorizer, lambda);
    }

    @Test
    void shutdownAcknowledgesNeverStartedWorkWithoutInvokingIt() {
        ManualExecutor executor = new ManualExecutor(false);
        initialize(executor, System::nanoTime, Duration.ofMillis(20), 1, new RegionResolver("us-east-1", ACCOUNT));
        assertThrows(AwsException.class, () -> validator.validate(FUNCTION, profile, version));
        assertEquals(1, validator.pendingInvocations());
        validator.shutdown();
        assertEquals(0, validator.pendingInvocations());
        assertTrue(executor.isTerminated());
        assertThrows(AwsException.class, () -> validator.validate(FUNCTION, profile, version));
        verifyNoInteractions(authorizer, lambda);
    }

    @Test
    void shutdownInterruptsRunningOwnersSeparatelyFromAnOrdinaryDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        initialize(Executors.newVirtualThreadPerTaskExecutor(), System::nanoTime, Duration.ofSeconds(15), 1,
                new RegionResolver("us-east-1", ACCOUNT));
        when(lambda.invokeArn(eq(FUNCTION), any(), eq(InvocationType.RequestResponse))).thenAnswer(call -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                interrupted.set(true);
                throw failure;
            }
            return result(null, "false");
        });
        CompletableFuture<AwsException> caller = CompletableFuture.supplyAsync(() ->
                assertThrows(AwsException.class, () -> validator.validate(FUNCTION, profile, version)));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            validator.shutdown();
            assertTrue(interrupted.get(), "Explicit shutdown requests interruption of running workers");
            assertEquals("BadRequestException", caller.get(2, TimeUnit.SECONDS).getErrorCode());
            assertEquals(0, validator.pendingInvocations(), "A cooperative worker reached its finally block");
            assertThrows(AwsException.class, () -> validator.validate(FUNCTION, profile, version));
        } finally {
            release.countDown();
        }
    }

    private void initialize(ExecutorService executor, LongSupplier time, Duration budget, int capacity,
                            RegionResolver regions) {
        when(authorizer.authorize(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Decision.ALLOW);
        when(lambda.invokeArn(eq(FUNCTION), any(), eq(InvocationType.RequestResponse))).thenReturn(result(null, "true"));
        validator = new AppConfigLambdaValidator(lambda, authorizer, regions, executor, time, budget, capacity);
    }

    private void awaitDrained() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (validator.pendingInvocations() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(0, validator.pendingInvocations(), "Actual worker completion releases its owner");
    }

    private static ConfigurationProfile profile() {
        ConfigurationProfile profile = new ConfigurationProfile();
        profile.setApplicationId("app1234");
        profile.setId("profile");
        profile.setLocationUri("hosted");
        return profile;
    }

    private static HostedConfigurationVersion version() {
        HostedConfigurationVersion version = new HostedConfigurationVersion();
        version.setApplicationId("app1234");
        version.setConfigurationProfileId("profile");
        version.setVersionNumber(17);
        version.setContent(" {\"message\":\"café λ\"}\n".getBytes(StandardCharsets.UTF_8));
        return version;
    }

    private static InvokeResult result(String error, String payload) {
        return new InvokeResult(200, error, payload.getBytes(StandardCharsets.UTF_8), null, "owned-test");
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final List<Runnable> queued = new ArrayList<>();
        private final boolean inline;
        private boolean closed;
        private Runnable beforeExecute = () -> {};

        private ManualExecutor(boolean inline) {
            this.inline = inline;
        }

        @Override
        public void execute(Runnable command) {
            if (closed) {
                throw new RejectedExecutionException();
            }
            beforeExecute.run();
            if (inline) {
                command.run();
            } else {
                queued.add(command);
            }
        }

        private void runQueued() {
            List<Runnable> accepted = List.copyOf(queued);
            queued.clear();
            accepted.forEach(Runnable::run);
        }

        @Override
        public void shutdown() {
            closed = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            closed = true;
            List<Runnable> pending = List.copyOf(queued);
            queued.clear();
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return closed;
        }

        @Override
        public boolean isTerminated() {
            return closed && queued.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
