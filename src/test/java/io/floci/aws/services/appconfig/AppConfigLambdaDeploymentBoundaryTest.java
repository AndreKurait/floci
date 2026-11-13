package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceCallAuthorizer;
import io.floci.aws.services.appconfig.model.Deployment;
import io.floci.aws.services.appconfig.model.HostedConfigurationVersion;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.ParkingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppConfigLambdaDeploymentBoundaryTest {
    private static final String ACCOUNT = "111111111111";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:111111111111:function:validator";
    private final LambdaService lambda = mock(LambdaService.class);
    private final ServiceCallAuthorizer authorizer = mock(ServiceCallAuthorizer.class);
    private final ParkingStore<String> active = new ParkingStore<>();
    private final ExecutorService callers = Executors.newFixedThreadPool(2);
    private AppConfigLambdaValidator validator;
    private AppConfigService service;
    private String app;
    private String environment;
    private String profile;
    private String strategy;
    private HostedConfigurationVersion first;
    private HostedConfigurationVersion second;

    @BeforeEach
    void createAnIsolatedHostedDeploymentWithTheActualValidatorOwner() {
        RegionResolver regions = new RegionResolver("us-east-1", ACCOUNT);
        when(authorizer.authorize(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Decision.ALLOW);
        when(lambda.invokeArn(eq(FUNCTION), any(), eq(InvocationType.RequestResponse))).thenReturn(success());
        validator = new AppConfigLambdaValidator(lambda, authorizer, regions,
                Executors.newVirtualThreadPerTaskExecutor(), System::nanoTime, Duration.ofMillis(500), 2);
        InMemoryStorageFactory storage = new InMemoryStorageFactory(ACCOUNT)
                .withStore("appconfig-active-configs.json", active);
        Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
        service = new AppConfigService(storage, regions, clock, validator);
        app = service.createApplication(Map.of("Name", "owned-validator-app")).getId();
        environment = service.createEnvironment(app, Map.of("Name", "owned-environment")).getId();
        profile = service.createConfigurationProfile(app, Map.of(
                "Name", "owned-profile", "LocationUri", "hosted", "Type", "AWS.Freeform",
                "Validators", List.of(Map.of("Type", "LAMBDA", "Content", FUNCTION)))).getId();
        strategy = service.createDeploymentStrategy(Map.of(
                "Name", "owned-strategy", "DeploymentDurationInMinutes", 0,
                "FinalBakeTimeInMinutes", 0, "GrowthFactor", 100, "GrowthType", "LINEAR",
                "ReplicateTo", "NONE")).getId();
        first = service.createHostedConfigurationVersion(app, profile,
                "{\"version\":1}".getBytes(StandardCharsets.UTF_8), "application/json", "first");
        second = service.createHostedConfigurationVersion(app, profile,
                "{\"version\":2}".getBytes(StandardCharsets.UTF_8), "application/json", "second");
    }

    @AfterEach
    void releaseOnlyOwnedInMemoryWorkers() {
        active.release();
        callers.shutdownNow();
        validator.shutdown();
    }

    @Test
    void resetWaitsForTheActiveWriteAfterTimelyValidation() throws Exception {
        active.parkNextWrite();
        Future<Deployment> starting = callers.submit(() -> start(first));
        try {
            assertTrue(active.awaitParked());
            AtomicReference<Thread> resetThread = new AtomicReference<>();
            Future<?> resetting = callers.submit(() -> {
                resetThread.set(Thread.currentThread());
                service.beforeReset();
            });
            awaitBlockedOrDone(resetThread, resetting);
            assertFalse(resetting.isDone(), "Reset cannot pass the actual deployment publication interval");
            assertEquals(Thread.State.BLOCKED, resetThread.get().getState());
            active.release();
            assertEquals(1, starting.get(2, TimeUnit.SECONDS).getDeploymentNumber());
            resetting.get(2, TimeUnit.SECONDS);
            assertEquals(Integer.toString(first.getVersionNumber()), service.getActiveVersion(environment, profile));
            JsonNode before = snapshot();
            clearInvocations(lambda, authorizer);
            assertThrows(AwsException.class, () -> start(second));
            assertEquals(before, snapshot());
            verifyNoInteractions(lambda, authorizer);
            service.afterReset();
            assertEquals(2, start(second).getDeploymentNumber());
        } finally {
            active.release();
            service.afterReset();
        }
    }

    @Test
    void lateSuccessCannotPublishADeploymentOrReplaceActiveContent() throws Exception {
        assertEquals(1, start(first).getDeploymentNumber());
        JsonNode before = snapshot();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(lambda.invokeArn(eq(FUNCTION), any(), eq(InvocationType.RequestResponse))).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return success();
        });
        Future<AwsException> starting = callers.submit(() -> assertThrows(AwsException.class, () -> start(second)));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(starting.get(3, TimeUnit.SECONDS).getMessage().contains("timed out"));
            assertEquals(before, snapshot(), "All selected state is unchanged when AppConfig returns");
            assertEquals(1, validator.pendingInvocations());
            assertThrows(AwsException.class, validator::checkReset);
        } finally {
            release.countDown();
        }
        awaitDrained();
        assertEquals(before, snapshot(), "The same invocation's late completion cannot write AppConfig state");
        when(lambda.invokeArn(eq(FUNCTION), any(), eq(InvocationType.RequestResponse))).thenReturn(success());
        assertEquals(2, start(second).getDeploymentNumber(), "The refused request did not consume a deployment number");
    }

    @Test
    void aFunctionErrorPreservesEveryHostedAndDeploymentField() {
        assertEquals(1, start(first).getDeploymentNumber());
        JsonNode before = snapshot();
        when(lambda.invokeArn(eq(FUNCTION), any(), eq(InvocationType.RequestResponse))).thenReturn(
                new InvokeResult(200, "Unhandled", "{\"errorMessage\":\"rejected\"}".getBytes(StandardCharsets.UTF_8),
                        null, "owned-error"));
        assertEquals("BadRequestException", assertThrows(AwsException.class, () -> start(second)).getErrorCode());
        assertEquals(before, snapshot());
        when(lambda.invokeArn(eq(FUNCTION), any(), eq(InvocationType.RequestResponse))).thenReturn(success());
        assertEquals(2, start(second).getDeploymentNumber());
    }

    @Test
    void permissionRefusalPreservesDeploymentStateAndDoesNotInvoke() {
        assertEquals(1, start(first).getDeploymentNumber());
        JsonNode before = snapshot();
        clearInvocations(lambda);
        when(authorizer.authorize(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Decision.DENY);
        assertEquals("BadRequestException", assertThrows(AwsException.class, () -> start(second)).getErrorCode());
        assertEquals(before, snapshot());
        verifyNoInteractions(lambda);
    }

    private Deployment start(HostedConfigurationVersion version) {
        return service.startDeployment(app, environment, Map.of(
                "ConfigurationProfileId", profile, "ConfigurationVersion", Integer.toString(version.getVersionNumber()),
                "DeploymentStrategyId", strategy));
    }

    private JsonNode snapshot() {
        AppConfigService.DeploymentPage deployments = service.listDeployments(app, environment, 50, null);
        assertNull(deployments.nextToken());
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("applications", service.listApplications());
        state.put("environments", service.listEnvironments(app));
        state.put("profiles", service.listConfigurationProfiles(app));
        state.put("strategies", service.listDeploymentStrategies());
        state.put("versions", service.listHostedConfigurationVersions(app, profile));
        state.put("first", service.getHostedConfigurationVersion(app, profile, first.getVersionNumber()));
        state.put("second", service.getHostedConfigurationVersion(app, profile, second.getVersionNumber()));
        state.put("deployments", deployments.items().stream()
                .map(item -> service.getDeployment(app, environment, item.getDeploymentNumber())).toList());
        String activeVersion = service.getActiveVersion(environment, profile);
        state.put("activeVersion", activeVersion);
        state.put("activeContent", activeVersion == null ? null : service.getHostedConfigurationVersion(
                app, profile, Integer.parseInt(activeVersion)));
        return new ObjectMapper().valueToTree(state);
    }

    private void awaitDrained() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (validator.pendingInvocations() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(0, validator.pendingInvocations());
        assertDoesNotThrow(validator::checkReset);
    }

    private static InvokeResult success() {
        return new InvokeResult(200, null, "false".getBytes(StandardCharsets.UTF_8), null, "owned-success");
    }
}
