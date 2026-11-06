package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LambdaAliasDeploymentTest {
    private static final String REGION = "us-west-2";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeDeployService service = mock(CodeDeployService.class);
    private final AtomicLong nanos = new AtomicLong();
    private final LambdaAliasDeployment runner = new LambdaAliasDeployment(
            service, Duration.ofHours(24), nanos::get, delay -> nanos.addAndGet(delay.toNanos()));

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
        assertEquals(Duration.ofMillis(400).toNanos(), nanos.get());
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
        assertEquals(Duration.ofMillis(200).toNanos(), nanos.get());
    }

    @Test
    void timeoutRequestsStopAndRetainsTheDeploymentForCleanup() throws Exception {
        start();
        when(service.getDeployment(REGION, "deployment")).thenReturn(state("InProgress"));
        LambdaAliasDeployment bounded = new LambdaAliasDeployment(
                service, Duration.ZERO, nanos::get, delay -> fail("Expired wait must not sleep"));
        StackResource resource = resource();
        JsonNode policy = policy();
        assertThrows(AwsException.class, () -> bounded.shift(resource, REGION, alias(), "2", policy, event -> {}));
        verify(service).stopDeployment(REGION, "deployment");
        assertEquals("deployment", resource.getAttributes().get(LambdaAliasDeployment.DEPLOYMENT_ATTR));
    }

    @Test
    void interruptionRequestsStopAndPreservesTheInterrupt() throws Exception {
        start();
        when(service.getDeployment(REGION, "deployment")).thenReturn(state("InProgress"));
        LambdaAliasDeployment interrupted = new LambdaAliasDeployment(
                service, Duration.ofHours(24), nanos::get, delay -> {
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
