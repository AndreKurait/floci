package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceCallAuthorizer;
import io.floci.aws.services.appconfig.model.Application;
import io.floci.aws.services.appconfig.model.ConfigurationProfile;
import io.floci.aws.services.appconfig.model.Deployment;
import io.floci.aws.services.appconfig.model.HostedConfigurationVersion;
import io.floci.aws.services.appconfig.model.Validator;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.ParkingStore;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppConfigNativeProfileUpdateConcurrencyTest {
    private static final String ACCOUNT = "111111111111";
    private static final String OLD_FUNCTION = "arn:aws:lambda:us-east-1:111111111111:function:old-validator";
    private static final String NEW_FUNCTION = "arn:aws:lambda:us-east-1:111111111111:function:new-validator";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

    @Test
    void nativeProfileUpdateFinishesBeforeConcurrentDeletionAndCannotResurrectTheProfile() throws Exception {
        ParkingStore<ConfigurationProfile> profiles = new ParkingStore<>();
        InMemoryStorageFactory storage = new InMemoryStorageFactory(ACCOUNT)
                .withStore("appconfig-profiles.json", profiles);
        AppConfigService service = new AppConfigService(storage, new RegionResolver("us-east-1", ACCOUNT));
        Application app = service.createApplication(Map.of("Name", "owned-native-update-app"));
        ConfigurationProfile original = service.createConfigurationProfile(app.getId(),
                Map.of("Name", "owned-profile", "LocationUri", "hosted", "Description", "before"));
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            profiles.parkNextWrite();
            AtomicReference<Thread> updateThread = new AtomicReference<>();
            Future<ConfigurationProfile> updating = callers.submit(() -> {
                updateThread.set(Thread.currentThread());
                return service.updateConfigurationProfile(app.getId(), original.getId(),
                        Map.of("Name", "renamed", "Description", "after"));
            });
            assertTrue(profiles.awaitParked(), "native Map update reached its profile-store write");
            AtomicReference<Thread> deleteThread = new AtomicReference<>();
            Future<?> deleting = callers.submit(() -> {
                deleteThread.set(Thread.currentThread());
                service.deleteConfigurationProfile(app.getId(), original.getId());
            });
            assertBlockedOnService(deleteThread, deleting, updateThread, service);
            assertEquals("owned-profile", service.getConfigurationProfile(app.getId(), original.getId()).getName(),
                    "the parked write has not replaced the stored profile");
            assertEquals("before", original.getDescription(), "the native update changed a detached copy");

            profiles.release();
            ConfigurationProfile updated = updating.get(5, TimeUnit.SECONDS);
            deleting.get(5, TimeUnit.SECONDS);
            assertEquals(original.getId(), updated.getId());
            assertEquals("renamed", updated.getName());
            assertEquals("after", updated.getDescription());
            assertTrue(service.findConfigurationProfile(original.getId()).isEmpty());
            assertTrue(service.listConfigurationProfiles(app.getId()).isEmpty());
            AwsException missing = assertThrows(AwsException.class,
                    () -> service.getConfigurationProfile(app.getId(), original.getId()));
            assertEquals("ResourceNotFoundException", missing.getErrorCode());
            assertEquals(404, missing.getHttpStatus());
            assertEquals(app.getId(), service.getApplication(app.getId()).getId());
        } finally {
            profiles.release();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS), "both owned callers drained");
        }
    }

    @Test
    void nativeValidatorUpdateWaitsForDeploymentValidationAndPublicationThenAppliesToTheNextValidation()
            throws Exception {
        LambdaService lambda = mock(LambdaService.class);
        ServiceCallAuthorizer authorizer = mock(ServiceCallAuthorizer.class);
        RegionResolver regions = new RegionResolver("us-east-1", ACCOUNT);
        ParkingStore<String> active = new ParkingStore<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        ExecutorService invocations = Executors.newVirtualThreadPerTaskExecutor();
        AppConfigLambdaValidator validator = new AppConfigLambdaValidator(lambda, authorizer, regions,
                invocations, System::nanoTime, Duration.ofSeconds(30), 2);
        try {
            when(authorizer.authorize(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Decision.ALLOW);
            when(lambda.invokeArn(eq(OLD_FUNCTION), any(), eq(InvocationType.RequestResponse)))
                    .thenReturn(success());
            when(lambda.invokeArn(eq(NEW_FUNCTION), any(), eq(InvocationType.RequestResponse)))
                    .thenReturn(success());
            InMemoryStorageFactory storage = new InMemoryStorageFactory(ACCOUNT)
                    .withStore("appconfig-active-configs.json", active);
            AppConfigService service = new AppConfigService(storage, regions, CLOCK, validator);
            String app = service.createApplication(Map.of("Name", "owned-validator-update-app")).getId();
            String environment = service.createEnvironment(app, Map.of("Name", "owned-environment")).getId();
            ConfigurationProfile original = service.createConfigurationProfile(app, Map.of(
                    "Name", "owned-profile", "Description", "preserved", "LocationUri", "hosted",
                    "Type", "AWS.Freeform",
                    "Validators", List.of(Map.of("Type", "LAMBDA", "Content", OLD_FUNCTION))));
            String profile = original.getId();
            String strategy = service.createDeploymentStrategy(Map.of(
                    "Name", "owned-immediate-strategy", "DeploymentDurationInMinutes", 0,
                    "FinalBakeTimeInMinutes", 0, "GrowthFactor", 100, "GrowthType", "LINEAR",
                    "ReplicateTo", "NONE")).getId();
            HostedConfigurationVersion first = service.createHostedConfigurationVersion(app, profile,
                    "{\"version\":1}".getBytes(StandardCharsets.UTF_8), "application/json", "first");
            HostedConfigurationVersion second = service.createHostedConfigurationVersion(app, profile,
                    "{\"version\":2}".getBytes(StandardCharsets.UTF_8), "application/json", "second");
            assertEquals(1, start(service, app, environment, profile, strategy, first).getDeploymentNumber());
            assertEquals(Integer.toString(first.getVersionNumber()), service.getActiveVersion(environment, profile));
            JsonNode firstDeploymentBefore = MAPPER.valueToTree(service.getDeployment(app, environment, 1));
            JsonNode hostedBefore = hostedVersions(service, app, profile);
            ObjectNode expectedProfile = MAPPER.valueToTree(original);
            List<Validator> newDeclaration = List.of(new Validator("LAMBDA", NEW_FUNCTION));
            expectedProfile.set("Validators", MAPPER.valueToTree(newDeclaration));
            Map<String, String> activeBefore = rawActiveSnapshot(active);
            assertEquals(List.of(Integer.toString(first.getVersionNumber())), List.copyOf(activeBefore.values()));
            awaitDrained(validator);
            clearInvocations(lambda);

            when(lambda.invokeArn(eq(OLD_FUNCTION), any(), eq(InvocationType.RequestResponse))).thenAnswer(call -> {
                entered.countDown();
                assertTrue(releaseValidation.await(20, TimeUnit.SECONDS), "test released the old validator");
                return success();
            });
            active.parkNextWrite();
            AtomicReference<Thread> deploymentThread = new AtomicReference<>();
            Future<Deployment> starting = callers.submit(() -> {
                deploymentThread.set(Thread.currentThread());
                return start(service, app, environment, profile, strategy, second);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS), "deployment entered the old Lambda declaration");
            AtomicReference<Thread> updateThread = new AtomicReference<>();
            AtomicReference<String> activeWhenUpdateReturned = new AtomicReference<>();
            Future<ConfigurationProfile> updating = callers.submit(() -> {
                updateThread.set(Thread.currentThread());
                ConfigurationProfile updated = service.updateConfigurationProfile(app, profile,
                        Map.of("Validators", List.of(Map.of("Type", "LAMBDA", "Content", NEW_FUNCTION))));
                activeWhenUpdateReturned.set(service.getActiveVersion(environment, profile));
                return updated;
            });
            assertBlockedOnService(updateThread, updating, deploymentThread, service);
            assertEquals(1, validator.pendingInvocations());
            assertEquals(List.of(new Validator("LAMBDA", OLD_FUNCTION)),
                    service.getConfigurationProfile(app, profile).getValidators());
            assertEquals(activeBefore, rawActiveSnapshot(active), "pending validation kept the previous active version");
            assertEquals(hostedBefore, hostedVersions(service, app, profile));
            verify(lambda, never()).invokeArn(eq(NEW_FUNCTION), any(), eq(InvocationType.RequestResponse));

            releaseValidation.countDown();
            assertTrue(active.awaitParked(), "validated deployment reached the actual active-store publication");
            assertFalse(starting.isDone());
            assertBlockedOnService(updateThread, updating, deploymentThread, service);
            assertEquals(List.of(new Validator("LAMBDA", OLD_FUNCTION)),
                    service.getConfigurationProfile(app, profile).getValidators());
            assertEquals(activeBefore, rawActiveSnapshot(active), "active replacement is still parked before its write");
            assertEquals(hostedBefore, hostedVersions(service, app, profile));
            active.release();

            Deployment published = starting.get(5, TimeUnit.SECONDS);
            ConfigurationProfile updated = updating.get(5, TimeUnit.SECONDS);
            String secondVersion = Integer.toString(second.getVersionNumber());
            assertEquals(2, published.getDeploymentNumber());
            assertEquals("COMPLETE", published.getState());
            assertEquals(secondVersion, published.getConfigurationVersion());
            assertEquals(secondVersion, activeWhenUpdateReturned.get());
            assertEquals(secondVersion, service.getActiveVersion(environment, profile));
            assertEquals(expectedProfile, MAPPER.valueToTree(updated));
            assertEquals(expectedProfile, MAPPER.valueToTree(service.getConfigurationProfile(app, profile)));
            assertEquals(List.of(new Validator("LAMBDA", OLD_FUNCTION)), original.getValidators());
            assertEquals(hostedBefore, hostedVersions(service, app, profile), "both prior hosted byte arrays are intact");
            assertEquals(firstDeploymentBefore, MAPPER.valueToTree(service.getDeployment(app, environment, 1)));
            verify(lambda, times(1)).invokeArn(eq(OLD_FUNCTION), any(), eq(InvocationType.RequestResponse));
            verify(lambda, never()).invokeArn(eq(NEW_FUNCTION), any(), eq(InvocationType.RequestResponse));
            awaitDrained(validator);

            JsonNode beforeNextValidation = snapshot(service, app, environment, profile);
            service.validateConfiguration(app, profile, secondVersion);
            verify(lambda, times(1)).invokeArn(eq(OLD_FUNCTION), any(), eq(InvocationType.RequestResponse));
            verify(lambda, times(1)).invokeArn(eq(NEW_FUNCTION), any(), eq(InvocationType.RequestResponse));
            assertEquals(beforeNextValidation, snapshot(service, app, environment, profile),
                    "the new declaration validates without changing the published deployment or active bytes");
        } finally {
            releaseValidation.countDown();
            active.release();
            callers.shutdownNow();
            validator.shutdown();
            assertAll(
                    () -> assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS), "both owned callers drained"),
                    () -> assertTrue(invocations.awaitTermination(5, TimeUnit.SECONDS), "owned Lambda workers drained"),
                    () -> assertEquals(0, validator.pendingInvocations(), "no validator invocation owner remains"));
        }
    }

    private static void assertBlockedOnService(AtomicReference<Thread> waitingThread, Future<?> waiting,
                                               AtomicReference<Thread> ownerThread, AppConfigService service)
            throws InterruptedException {
        awaitBlockedOrDone(waitingThread, waiting);
        assertFalse(waiting.isDone(), "concurrent operation must wait for the service monitor");
        Thread waiter = waitingThread.get();
        Thread owner = ownerThread.get();
        assertNotNull(waiter);
        assertNotNull(owner);
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(waiter.threadId());
        assertNotNull(info);
        assertEquals(Thread.State.BLOCKED, info.getThreadState());
        assertEquals(owner.threadId(), info.getLockOwnerId(), "the first caller owns the blocking monitor");
        assertNotNull(info.getLockInfo());
        assertEquals(AppConfigService.class.getName(), info.getLockInfo().getClassName());
        assertEquals(System.identityHashCode(service), info.getLockInfo().getIdentityHashCode(),
                "the waiter is blocked on this exact AppConfig service");
    }

    private static Deployment start(AppConfigService service, String app, String environment, String profile,
                                    String strategy, HostedConfigurationVersion version) {
        return service.startDeployment(app, environment, Map.of(
                "ConfigurationProfileId", profile, "ConfigurationVersion", Integer.toString(version.getVersionNumber()),
                "DeploymentStrategyId", strategy));
    }

    private static Map<String, String> rawActiveSnapshot(ParkingStore<String> active) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : active.keys()) {
            values.put(key, active.get(key).orElseThrow());
        }
        return values;
    }

    private static JsonNode hostedVersions(AppConfigService service, String app, String profile) {
        return MAPPER.valueToTree(service.listHostedConfigurationVersions(app, profile).stream()
                .map(version -> service.getHostedConfigurationVersion(app, profile, version.getVersionNumber()))
                .toList());
    }

    private static JsonNode snapshot(AppConfigService service, String app, String environment, String profile) {
        AppConfigService.DeploymentPage deployments = service.listDeployments(app, environment, 50, null);
        assertNull(deployments.nextToken());
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("applications", service.listApplications());
        state.put("environments", service.listEnvironments(app));
        state.put("profiles", service.listConfigurationProfiles(app));
        state.put("strategies", service.listDeploymentStrategies());
        state.put("versions", hostedVersions(service, app, profile));
        state.put("deployments", deployments.items().stream()
                .map(item -> service.getDeployment(app, environment, item.getDeploymentNumber())).toList());
        String activeVersion = service.getActiveVersion(environment, profile);
        state.put("activeVersion", activeVersion);
        state.put("activeContent", activeVersion == null ? null
                : service.getHostedConfigurationVersion(app, profile, Integer.parseInt(activeVersion)));
        return MAPPER.valueToTree(state);
    }

    private static InvokeResult success() {
        return new InvokeResult(200, null, "false".getBytes(StandardCharsets.UTF_8), null, "owned-success");
    }

    private static void awaitDrained(AppConfigLambdaValidator validator) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (validator.pendingInvocations() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(0, validator.pendingInvocations());
    }
}
