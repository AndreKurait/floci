package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.services.appconfig.AppConfigService;
import io.github.hectorvent.floci.services.appconfig.DeploymentStopException;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppConfigDeploymentCfnProvisionerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AppConfigService service = mock(AppConfigService.class);
    private final ServiceConfigAccess serviceConfig = mock(ServiceConfigAccess.class);
    private final TestTime time = TestTime.create();
    private final List<Duration> waits = new ArrayList<>();
    private final List<StackEvent> events = new ArrayList<>();
    private final AppConfigDeploymentCfnProvisioner provisioner = new AppConfigDeploymentCfnProvisioner(
            service, serviceConfig, time::nanoTime, delay -> {
                waits.add(delay);
                time.sleepAdvancing(delay);
            });
    private final ProvisionContext context = new ProvisionContext(CloudFormationTemplateEngine.standalone(
            "000000000000", "us-east-1", "deployment", JSON, value -> value),
            "us-east-1", "000000000000", "deployment", null, events::add, null);

    @Test
    void waitsThroughRolloutAndBakeAndSetsStringAttributes() {
        startReturns("DEPLOYING");
        when(service.getDeployment("app0001", "env0001", 7))
                .thenReturn(deployment("DEPLOYING"), deployment("BAKING"), deployment("COMPLETE"));
        StackResource resource = resource();

        provisioner.provision(resource, JSON.valueToTree(properties()), context);

        assertEquals("app0001|env0001|7", resource.getPhysicalId());
        assertEquals("7", provisioner.referenceValue(resource.getPhysicalId()));
        assertEquals("7", provisioner.publicPhysicalId(resource.getPhysicalId()));
        assertEquals("7", resource.getAttributes().get("DeploymentNumber"));
        assertEquals("COMPLETE", resource.getAttributes().get("State"));
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(1)), waits);
        assertEquals(List.of("DEPLOYING (0.0%)", "BAKING (100.0%)"),
                events.stream().map(StackEvent::getResourceStatusReason).toList());
        assertTrue(events.stream().allMatch(event -> "CREATE_IN_PROGRESS".equals(event.getResourceStatus())));
        verify(service, never()).stopDeployment("app0001", "env0001", 7, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Deployment-1234abcd", "7", "app0001|env0001"})
    void nonCompoundPublicIdentitiesRemainUnchanged(String physicalId) {
        assertEquals(physicalId, provisioner.referenceValue(physicalId));
        assertEquals(physicalId, provisioner.publicPhysicalId(physicalId));
        verifyNoInteractions(service);
    }

    @Test
    void publicPhysicalIdPreservesMissingIdentity() {
        assertNull(provisioner.publicPhysicalId(null));
        verifyNoInteractions(service);
    }

    @Test
    void unchangedPropertiesDoNotStartAnotherDeployment() {
        startReturns("COMPLETE");
        when(service.getDeployment("app0001", "env0001", 7)).thenReturn(deployment("COMPLETE"));
        StackResource resource = resource();
        provisioner.provision(resource, JSON.valueToTree(properties()), context);
        ProvisionContext update = new ProvisionContext(context.engine(), context.region(), context.accountId(),
                context.stackName(), resource.getPhysicalId());

        provisioner.provision(resource, JSON.valueToTree(properties()), update);

        verify(service, times(1)).startDeployment(eq("application-name"), eq("environment-name"), anyMap());
        assertEquals("app0001|env0001|7", resource.getPhysicalId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ApplicationId", "EnvironmentId", "ConfigurationProfileId",
            "ConfigurationVersion", "DeploymentStrategyId"})
    void missingPropertiesDoNotStartADeployment(String missing) {
        Map<String, Object> properties = properties();
        properties.remove(missing);
        assertThrows(AwsException.class, () -> provisioner.provision(
                resource(), JSON.valueToTree(properties), context));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"KmsKeyIdentifier", "DynamicExtensionParameters", "Tags"})
    void unsupportedOptionsFailBeforeMutation(String unsupported) {
        Map<String, Object> properties = properties();
        properties.put(unsupported, "unsupported");
        assertThrows(AwsException.class, () -> provisioner.provision(
                resource(), JSON.valueToTree(properties), context));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLLED_BACK", "REVERTED", "ROLLING_BACK", "UNKNOWN"})
    void unsuccessfulDeploymentFailsCreationAndAttemptsCleanup(String state) {
        startReturns("DEPLOYING");
        when(service.getDeployment("app0001", "env0001", 7)).thenReturn(deployment(state));
        StackResource resource = resource();

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, JSON.valueToTree(properties()), context));

        assertTrue(failure.getMessage().contains(state));
        assertEquals(state, resource.getAttributes().get("State"));
        verify(service).stopDeployment("app0001", "env0001", 7, true);
        assertTrue(waits.isEmpty());
        assertNull(resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void aDeploymentStillRunningAfterTwentyFourHoursFailsAndIsReverted() {
        startReturns("DEPLOYING");
        deploymentIsNextSeenAfter(Duration.ofHours(24).minusNanos(1), "DEPLOYING");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), JSON.valueToTree(properties()), context));

        assertEquals(List.of(Duration.ofNanos(1)), waits, "one nanosecond before the deadline the wait goes on");
        assertTrue(failure.getMessage().contains("did not complete within 86400s (state DEPLOYING)."),
                failure.getMessage());
        assertEquals(TestTime.DEFAULT_START.plus(Duration.ofHours(24)), time.now());
        assertEquals(1, events.size(), "Unchanged progress must not flood stack events.");
        verify(service).stopDeployment("app0001", "env0001", 7, true);
    }

    @Test
    void aDeploymentThatCompletesAtTheTwentyFourHourDeadlineIsAccepted() {
        startReturns("DEPLOYING");
        deploymentIsNextSeenAfter(Duration.ofHours(24).minusNanos(1), "COMPLETE");
        StackResource resource = resource();

        provisioner.provision(resource, JSON.valueToTree(properties()), context);

        assertEquals(TestTime.DEFAULT_START.plus(Duration.ofHours(24)), time.now());
        assertEquals("COMPLETE", resource.getAttributes().get("State"));
        verify(service, never()).stopDeployment("app0001", "env0001", 7, true);
    }

    /**
     * The first status read returns DEPLOYING only after {@code stalled} has passed, as a read held up
     * for that long would; every later read returns {@code then}.
     */
    private void deploymentIsNextSeenAfter(Duration stalled, String then) {
        AtomicBoolean first = new AtomicBoolean(true);
        when(service.getDeployment("app0001", "env0001", 7)).thenAnswer(call -> {
            if (first.compareAndSet(true, false)) {
                time.advance(stalled);
                return deployment("DEPLOYING");
            }
            return deployment(then);
        });
    }

    @Test
    void failedCompensationRetainsOwnershipAndTheCleanupError() {
        startReturns("DEPLOYING");
        when(service.getDeployment("app0001", "env0001", 7)).thenReturn(deployment("DEPLOYING"));
        AwsException cleanup = new AwsException("BadRequestException",
                "The previous hosted configuration version is no longer available.", 400);
        doThrow(cleanup).when(service).stopDeployment("app0001", "env0001", 7, true);
        StackResource resource = resource();

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, JSON.valueToTree(properties()), context));

        assertSame(cleanup, failure.getSuppressed()[0]);
        assertEquals("true", resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        assertTrue(resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR)
                .contains("previous hosted configuration"));
    }

    @Test
    void interruptedWaitPreservesInterruptionAndCleansUp() {
        startReturns("DEPLOYING");
        when(service.getDeployment("app0001", "env0001", 7)).thenReturn(deployment("DEPLOYING"));
        AppConfigDeploymentCfnProvisioner interrupted = new AppConfigDeploymentCfnProvisioner(
                service, serviceConfig, time::nanoTime, delay -> {
                    throw new InterruptedException("stop test");
                });
        try {
            assertThrows(IllegalStateException.class, () -> interrupted.provision(
                    resource(), JSON.valueToTree(properties()), context));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(service).stopDeployment("app0001", "env0001", 7, true);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @EnumSource(DeploymentStopException.Reason.class)
    void deletionAcceptsOnlyTheClassifiedNoopConditions(DeploymentStopException.Reason reason) {
        doThrow(new DeploymentStopException(reason, reason.name()))
                .when(service).stopDeployment("app0001", "env0001", 7, true);
        provisioner.delete("AWS::AppConfig::Deployment", "app0001|env0001|7", "us-east-1");
        verify(service).stopDeployment("app0001", "env0001", 7, true);
    }

    @Test
    void deletionIgnoresNotFoundButPropagatesOtherFailures() {
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(service).stopDeployment("app0001", "env0001", 7, true);
        provisioner.delete("AWS::AppConfig::Deployment", "app0001|env0001|7", "us-east-1");
        AwsException unknown = new AwsException("BadRequestException", "cannot safely revert", 400);
        doThrow(unknown).when(service).stopDeployment("app0001", "env0001", 7, true);
        assertSame(unknown, assertThrows(AwsException.class, () -> provisioner.delete(
                "AWS::AppConfig::Deployment", "app0001|env0001|7", "us-east-1")));
    }

    @Test
    void legacyDispatcherStubsAreNotPassedToAppConfig() {
        StackResource resource = resource();
        resource.setPhysicalId("Deployment-1234abcd");
        resource.getAttributes().put("Arn", CfnResourceDispatcher.STUB_ARN_PREFIX + "Deployment");
        provisioner.delete(resource, "us-east-1");
        verifyNoInteractions(service);
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    private void startReturns(String state) {
        when(service.startDeployment(eq("application-name"), eq("environment-name"), anyMap()))
                .thenReturn(deployment(state));
    }

    private static Deployment deployment(String state) {
        Deployment deployment = new Deployment();
        deployment.setApplicationId("app0001");
        deployment.setEnvironmentId("env0001");
        deployment.setDeploymentNumber(7);
        deployment.setState(state);
        deployment.setPercentageComplete("DEPLOYING".equals(state) ? 0.0f : 100.0f);
        return deployment;
    }

    private static Map<String, Object> properties() {
        return new HashMap<>(Map.of("ApplicationId", "application-name", "EnvironmentId", "environment-name",
                "ConfigurationProfileId", "profile-name", "ConfigurationVersion", "1",
                "DeploymentStrategyId", "strategy"));
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Deployment");
        resource.setResourceType("AWS::AppConfig::Deployment");
        return resource;
    }
}
