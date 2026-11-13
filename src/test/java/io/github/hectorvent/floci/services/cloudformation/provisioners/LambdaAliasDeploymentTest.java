package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LambdaAliasDeploymentTest {
    private static final String REGION = "us-west-2";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeDeployService service = mock(CodeDeployService.class);
    private final TestTime time = TestTime.create();
    private final List<Duration> waits = new ArrayList<>();
    private final LambdaAliasDeployment runner = new LambdaAliasDeployment(service, time::nanoTime, delay -> {
        waits.add(delay);
        time.sleepAdvancing(delay);
    });

    private JsonNode policy() throws Exception {
        return mapper.readTree("""
                {"ApplicationName":"application","DeploymentGroupName":"group",
                 "BeforeAllowTrafficHook":"before","AfterAllowTrafficHook":"after"}
                """);
    }

    private LambdaAlias alias() {
        LambdaAlias alias = new LambdaAlias();
        alias.setFunctionName("function");
        alias.setName("live");
        alias.setFunctionVersion("1");
        return alias;
    }

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Alias");
        resource.setResourceType("AWS::Lambda::Alias");
        return resource;
    }

    private Deployment state(String status) {
        Deployment deployment = new Deployment();
        deployment.setDeploymentId("deployment");
        deployment.setStatus(status);
        return deployment;
    }

    private void start() {
        when(service.createDeployment(eq(REGION), eq("application"), eq("group"),
                isNull(), anyMap(), anyString())).thenReturn("deployment");
    }

    @Test
    void waitsForDeploymentSuccessAndSuppliesBothHooks() throws Exception {
        start();
        when(service.getDeployment(REGION, "deployment"))
                .thenReturn(state("Queued"), state("InProgress"), state("Succeeded"));
        List<StackEvent> events = new ArrayList<>();
        StackResource resource = resource();
        runner.shift(resource, REGION, alias(), "2", policy(), events::add);
        assertEquals(TestTime.DEFAULT_START.plusMillis(400), time.now());
        assertEquals("deployment", resource.getAttributes().get(LambdaAliasDeployment.DEPLOYMENT_ATTR));
        assertTrue(events.stream().allMatch(event -> event.getResourceStatus().equals("UPDATE_IN_PROGRESS")));
        ArgumentCaptor<Map<String, Object>> revision = ArgumentCaptor.captor();
        verify(service).createDeployment(eq(REGION), eq("application"), eq("group"),
                isNull(), revision.capture(), anyString());
        JsonNode spec = mapper.readTree(mapper.valueToTree(revision.getValue())
                .path("appSpecContent").path("content").asText());
        assertEquals("1", spec.at("/Resources/0/Function/Properties/CurrentVersion").asText());
        assertEquals("2", spec.at("/Resources/0/Function/Properties/TargetVersion").asText());
        assertEquals("before", spec.at("/Hooks/0/BeforeAllowTraffic").asText());
        assertEquals("after", spec.at("/Hooks/1/AfterAllowTraffic").asText());
    }

    @Test
    void unchangedVersionDoesNotStartADeployment() throws Exception {
        runner.shift(resource(), REGION, alias(), "1", policy(), event -> {});
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Failed", "Stopped"})
    void terminalFailureDoesNotBecomeSuccessfulProvisioning(String terminal) throws Exception {
        start();
        when(service.getDeployment(REGION, "deployment")).thenReturn(state(terminal));
        JsonNode policy = policy();
        AwsException failure = assertThrows(AwsException.class, () ->
                runner.shift(resource(), REGION, alias(), "2", policy, event -> {}));
        assertTrue(failure.getMessage().contains("deployment ended in " + terminal));
    }

    @Test
    void restoreWaitsForTheStoppedWorkerBeforeReturning() {
        StackResource resource = resource();
        resource.getAttributes().put(LambdaAliasDeployment.DEPLOYMENT_ATTR, "deployment");
        when(service.getDeployment(REGION, "deployment"))
                .thenReturn(state("InProgress"), state("InProgress"), state("Stopped"));
        runner.stopBeforeRestore(resource, REGION, event -> {});
        verify(service).stopDeployment(REGION, "deployment");
        assertEquals(TestTime.DEFAULT_START.plusMillis(200), time.now());
    }

    @Test
    void aDeploymentStillRunningAfterTwentyFourHoursIsStoppedAndRetainedForCleanup() throws Exception {
        start();
        deploymentIsNextSeenAfter(Duration.ofHours(24).minusNanos(1), "InProgress");
        StackResource resource = resource();
        JsonNode policy = policy();
        AwsException failure = assertThrows(AwsException.class,
                () -> runner.shift(resource, REGION, alias(), "2", policy, event -> {}));
        assertEquals(List.of(Duration.ofNanos(1)), waits, "one nanosecond before the deadline the wait goes on");
        assertTrue(failure.getMessage().contains("did not finish within 86400 seconds."), failure.getMessage());
        assertEquals(TestTime.DEFAULT_START.plus(Duration.ofHours(24)), time.now());
        verify(service).stopDeployment(REGION, "deployment");
        assertEquals("deployment", resource.getAttributes().get(LambdaAliasDeployment.DEPLOYMENT_ATTR));
    }

    @Test
    void aWaitThatHasAlreadyExpiredDoesNotSleepAgain() throws Exception {
        start();
        deploymentIsNextSeenAfter(Duration.ofHours(24), "InProgress");
        StackResource resource = resource();
        JsonNode policy = policy();
        assertThrows(AwsException.class, () -> runner.shift(resource, REGION, alias(), "2", policy, event -> {}));
        assertEquals(List.of(), waits, "Expired wait must not sleep");
        verify(service).stopDeployment(REGION, "deployment");
        assertEquals("deployment", resource.getAttributes().get(LambdaAliasDeployment.DEPLOYMENT_ATTR));
    }

    @Test
    void aDeploymentThatSucceedsAtTheTwentyFourHourDeadlineIsAccepted() throws Exception {
        start();
        deploymentIsNextSeenAfter(Duration.ofHours(24).minusNanos(1), "Succeeded");
        runner.shift(resource(), REGION, alias(), "2", policy(), event -> {});
        assertEquals(TestTime.DEFAULT_START.plus(Duration.ofHours(24)), time.now());
        verify(service, never()).stopDeployment(REGION, "deployment");
    }

    /**
     * The first status read returns InProgress only after {@code stalled} has passed, as a read held up
     * for that long would; every later read returns {@code then}.
     */
    private void deploymentIsNextSeenAfter(Duration stalled, String then) {
        AtomicBoolean first = new AtomicBoolean(true);
        when(service.getDeployment(REGION, "deployment")).thenAnswer(call -> {
            if (first.compareAndSet(true, false)) {
                time.advance(stalled);
                return state("InProgress");
            }
            return state(then);
        });
    }

    @Test
    void interruptionRequestsStopAndPreservesTheInterrupt() throws Exception {
        start();
        when(service.getDeployment(REGION, "deployment")).thenReturn(state("InProgress"));
        LambdaAliasDeployment interrupted = new LambdaAliasDeployment(
                service, time::nanoTime, delay -> {
                    throw new InterruptedException("cancelled");
                });
        JsonNode policy = policy();
        try {
            assertThrows(IllegalStateException.class, () ->
                    interrupted.shift(resource(), REGION, alias(), "2", policy, event -> {}));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(service).stopDeployment(REGION, "deployment");
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"invalid\"",
            "{\"CodeDeployLambdaAliasUpdate\":\"invalid\"}",
            "{\"CodeDeployLambdaAliasUpdate\":{}}",
            "{\"CodeDeployLambdaAliasUpdate\":{\"ApplicationName\":\"app\",\"DeploymentGroupName\":false}}",
            "{\"CodeDeployLambdaAliasUpdate\":{\"ApplicationName\":\"app\",\"DeploymentGroupName\":\"group\","
                    + "\"BeforeAllowTrafficHook\":null}}"
    })
    void invalidPoliciesFailBeforeStartingADeployment(String raw) throws Exception {
        JsonNode update = mapper.readTree(raw);
        assertThrows(AwsException.class, () -> LambdaAliasDeployment.resolvedPolicy(update));
        verifyNoInteractions(service);
    }
}
