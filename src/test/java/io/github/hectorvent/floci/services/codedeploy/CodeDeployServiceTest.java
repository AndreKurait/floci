package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.ssm.SsmCommandService;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeDeployServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/CodeDeployRole";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Duration STOP_POLL = Duration.ofMillis(200);
    private static final Duration SSM_POLL = Duration.ofMillis(500);

    /** Deployment workers wait on this timeline, so a test decides when an interval or deadline passes. */
    private final TestTime time = TestTime.create();
    private LambdaService lambdaService;
    private SsmCommandService ssmCommandService;
    private ServiceRoleCallAuthorizer roleAuthorizer;
    private CodeDeployService service;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        ssmCommandService = mock(SsmCommandService.class);
        roleAuthorizer = mock(ServiceRoleCallAuthorizer.class);
        when(roleAuthorizer.authorizeWithoutSourceContext(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Decision.ALLOW);
        service = newService(Duration.ofHours(1));
    }

    private CodeDeployService newService(Duration hookCallbackTimeout) {
        return newService(hookCallbackTimeout, time.clock());
    }

    private CodeDeployService newService(Duration hookCallbackTimeout, Clock clock) {
        CodeDeployService svc = new CodeDeployService(lambdaService, mock(EcsService.class),
                mock(ElbV2Service.class), ssmCommandService, mock(Ec2Service.class), new ObjectMapper(),
                new RegionResolver(REGION, ACCOUNT_ID), null, hookCallbackTimeout, clock,
                mock(CodeDeployAgentDeployment.class), roleAuthorizer, null, new TimelineTimer(time));
        svc.initializeStorage();
        return svc;
    }

    /** The service as production builds it: system clock, real sleeps and real future waits. */
    private CodeDeployService realTimeService() {
        CodeDeployService svc = new CodeDeployService(lambdaService, mock(EcsService.class),
                mock(ElbV2Service.class), ssmCommandService, mock(Ec2Service.class), new ObjectMapper(),
                new RegionResolver(REGION, ACCOUNT_ID), null, mock(CodeDeployAgentDeployment.class), roleAuthorizer);
        svc.initializeStorage();
        return svc;
    }

    @Test
    void deploymentHistoryAndStopRequestsAreScopedToTheirAccountAndRegion() {
        AtomicReference<String> account = new AtomicReference<>(ACCOUNT_ID);
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenAnswer(ignored -> account.get());
        CodeDeployService scoped = new CodeDeployService(lambdaService, mock(EcsService.class),
                mock(ElbV2Service.class), ssmCommandService, mock(Ec2Service.class), new ObjectMapper(),
                resolver, null, mock(CodeDeployAgentDeployment.class), roleAuthorizer);
        scoped.createApplication(REGION, "account-app", "Server", null);
        scoped.createDeploymentGroup(REGION, "account-app", "group", null, ROLE_ARN, Map.of());
        String id = scoped.createDeployment(REGION, "account-app", "group", null,
                Map.of("appSpecContent", Map.of("content", "{\"version\":0.0,\"os\":\"linux\"}")), null);
        assertEquals(id, scoped.getDeployment(REGION, id).getDeploymentId());
        assertThrows(AwsException.class, () -> scoped.getDeployment("us-west-2", id));
        account.set("999999999999");
        assertTrue(scoped.listDeployments(REGION, null, null, null).isEmpty());
        assertThrows(AwsException.class, () -> scoped.getDeployment(REGION, id));
        assertThrows(AwsException.class, () -> scoped.listDeploymentTargets(REGION, id));
        assertThrows(AwsException.class, () -> scoped.getDeploymentTarget(REGION, id, "instance-1"));
        assertThrows(AwsException.class, () -> scoped.stopDeployment(REGION, id));
        account.set(ACCOUNT_ID);
        assertEquals(id, scoped.getDeployment(REGION, id).getDeploymentId());
    }

    // ---- Server platform (SSM) hooks ---------------------------------------------------

    @Test
    void missingSsmInstanceFailsHook() {
        service.registerOnPremisesInstance(REGION, "instance-1", "arn:aws:sts::000000000000:session/s",
                "arn:aws:iam::000000000000:user/u");
        when(ssmCommandService.isInstanceRegistered("instance-1", REGION)).thenReturn(false);

        String deploymentId = createServerDeployment("app-missing-ssm", "group-missing-ssm", 30);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("HEALTH_CONSTRAINTS", deployment.getErrorInformation().get("code"));
        Map<String, Object> event = instanceTargetEvent(deploymentId, "instance-1", "ApplicationStart");
        assertEquals(service.batchGetDeploymentTargets(REGION, deploymentId, List.of("instance-1")).getFirst(),
                service.getDeploymentTarget(REGION, deploymentId, "instance-1"));
        assertThrows(AwsException.class, () -> service.getDeploymentTarget(REGION, deploymentId, "not-selected"));
        assertThrows(AwsException.class, () -> service.getDeploymentTarget(REGION, deploymentId, null));
        assertThrows(AwsException.class, () -> service.getDeploymentTarget(REGION, null, "instance-1"));
        assertEquals("Failed", event.get("status"));
        Map<String, String> diagnostics = diagnosticsOf(event);
        assertEquals("UnknownError", diagnostics.get("errorCode"));
        assertEquals("CodeDeploy agent was not able to receive the lifecycle event. Check the CodeDeploy agent "
                        + "logs on your host and make sure the agent is running and can connect to the CodeDeploy server.",
                diagnostics.get("message"));
    }

    @Test
    void ssmCommandFailureFailsHook() {
        service.registerOnPremisesInstance(REGION, "instance-1", "arn:aws:sts::000000000000:session/s",
                "arn:aws:iam::000000000000:user/u");
        when(ssmCommandService.isInstanceRegistered("instance-1", REGION)).thenReturn(true);
        when(ssmCommandService.sendCommandToInstance(eq("instance-1"), anyString(), any(), eq(30), eq(REGION)))
                .thenReturn("cmd-1");
        when(ssmCommandService.getCommandInvocationStatus("cmd-1", "instance-1", REGION)).thenReturn("Failed");

        String deploymentId = createServerDeployment("app-ssm-failure", "group-ssm-failure", 30);
        time.awaitSleepers(1);
        assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());
        time.advance(SSM_POLL);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("HEALTH_CONSTRAINTS", deployment.getErrorInformation().get("code"));
        Map<String, Object> event = instanceTargetEvent(deploymentId, "instance-1", "ApplicationStart");
        assertEquals("Failed", event.get("status"));
        Map<String, String> diagnostics = diagnosticsOf(event);
        assertEquals("ScriptFailed", diagnostics.get("errorCode"));
        assertEquals("Script at specified location: scripts/start_server.sh failed with SSM command status Failed",
                diagnostics.get("message"));
    }

    @Test
    void ssmScriptTimeoutFailsHook() {
        service.registerOnPremisesInstance(REGION, "instance-1", "arn:aws:sts::000000000000:session/s",
                "arn:aws:iam::000000000000:user/u");
        when(ssmCommandService.isInstanceRegistered("instance-1", REGION)).thenReturn(true);
        when(ssmCommandService.sendCommandToInstance(eq("instance-1"), anyString(), any(), eq(300), eq(REGION)))
                .thenReturn("cmd-timeout");
        when(ssmCommandService.getCommandInvocationStatus("cmd-timeout", "instance-1", REGION))
                .thenReturn("InProgress");

        String deploymentId = createServerDeployment("app-ssm-timeout", "group-ssm-timeout", 300);
        time.awaitSleepers(1);

        time.advance(Duration.ofSeconds(300).minusMillis(1));
        assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());
        assertEquals("InProgress", instanceTargetEvent(deploymentId, "instance-1", "ApplicationStart").get("status"));

        time.advance(Duration.ofMillis(1));
        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        Map<String, Object> event = instanceTargetEvent(deploymentId, "instance-1", "ApplicationStart");
        Map<String, String> diagnostics = diagnosticsOf(event);
        assertEquals("ScriptTimedOut", diagnostics.get("errorCode"));
        assertEquals("Script at specified location: scripts/start_server.sh failed to complete in 300 seconds",
                diagnostics.get("message"));
        assertEquals(300.0, (double) event.get("endTime") - (double) event.get("startTime"));
        verify(ssmCommandService, times(600)).getCommandInvocationStatus("cmd-timeout", "instance-1", REGION);
    }

    @Test
    void ssmScriptRunningPastThirtySecondsWithinDeclaredTimeoutSucceeds() {
        service.registerOnPremisesInstance(REGION, "instance-1", "arn:aws:sts::000000000000:session/s",
                "arn:aws:iam::000000000000:user/u");
        when(ssmCommandService.isInstanceRegistered("instance-1", REGION)).thenReturn(true);
        when(ssmCommandService.sendCommandToInstance(eq("instance-1"), anyString(), any(), eq(300), eq(REGION)))
                .thenReturn("cmd-long");
        Instant scriptEnds = time.now().plusSeconds(60);
        when(ssmCommandService.getCommandInvocationStatus("cmd-long", "instance-1", REGION))
                .thenAnswer(invocation -> time.now().isBefore(scriptEnds) ? "InProgress" : "Success");

        String deploymentId = createServerDeployment("app-ssm-long", "group-ssm-long", 300);
        time.awaitSleepers(1);

        time.advance(Duration.ofSeconds(60).minusMillis(1));
        assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());

        time.advance(Duration.ofMillis(1));
        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Succeeded", deployment.getStatus());
        assertEquals(60.0, deployment.getCompleteTime() - deployment.getStartTime());
    }

    @Test
    void ssmScriptFinishingAtItsTimeoutInstantSucceeds() {
        service.registerOnPremisesInstance(REGION, "instance-1", "arn:aws:sts::000000000000:session/s",
                "arn:aws:iam::000000000000:user/u");
        when(ssmCommandService.isInstanceRegistered("instance-1", REGION)).thenReturn(true);
        when(ssmCommandService.sendCommandToInstance(eq("instance-1"), anyString(), any(), eq(300), eq(REGION)))
                .thenReturn("cmd-edge");
        Instant scriptEnds = time.now().plusSeconds(300);
        when(ssmCommandService.getCommandInvocationStatus("cmd-edge", "instance-1", REGION))
                .thenAnswer(invocation -> time.now().isBefore(scriptEnds) ? "InProgress" : "Success");

        String deploymentId = createServerDeployment("app-ssm-edge", "group-ssm-edge", 300);
        time.awaitSleepers(1);
        time.advance(Duration.ofSeconds(300));

        assertEquals("Succeeded", awaitTerminal(deploymentId, Duration.ofSeconds(5)).getStatus());
    }

    @ParameterizedTest
    @CsvSource({
            "CodeDeployDefault.LambdaCanary10Percent5Minutes, 5",
            "CodeDeployDefault.LambdaLinear10PercentEvery1Minute, 1"
    })
    void lambdaTrafficIntervalRemainsPendingAndStopsWithoutAnotherShift(String config, int intervalMinutes) {
        createLambdaAppAndGroup("timed-app", "timed-group");
        String deploymentId = service.createDeployment(REGION, "timed-app", "timed-group", config,
                lambdaRevision(null, null), "traffic interval");
        time.awaitSleepers(1);
        verify(lambdaService).updateAlias(eq(REGION), eq("myFunc"), eq("live"),
                eq("1"), isNull(), eq(Map.of("2", 0.1)));

        time.advance(Duration.ofMinutes(intervalMinutes).minusMillis(1));
        assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());
        assertEquals("InProgress", lambdaTargetEvent(deploymentId, "AllowTraffic").get("status"));
        verify(lambdaService, times(1)).updateAlias(anyString(), anyString(), anyString(), anyString(), any(), any());

        // Stopped one millisecond before the next shift is due: the interval ends without shifting.
        service.stopDeployment(REGION, deploymentId);
        time.advance(Duration.ofMillis(1));

        assertEquals("Stopped", awaitTerminal(deploymentId, Duration.ofSeconds(5)).getStatus());
        assertEquals("Skipped", lambdaTargetEvent(deploymentId, "AllowTraffic").get("status"));
        verify(lambdaService, times(1)).updateAlias(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aStopDuringATrafficIntervalIsNoticedAtTheNextStopPoll() {
        createLambdaAppAndGroup("stop-app", "stop-group");
        String deploymentId = service.createDeployment(REGION, "stop-app", "stop-group",
                "CodeDeployDefault.LambdaCanary10Percent5Minutes", lambdaRevision(null, null), "stop poll");
        time.awaitSleepers(1);
        time.advance(Duration.ofSeconds(90));

        service.stopDeployment(REGION, deploymentId);
        time.advance(STOP_POLL.minusMillis(1));
        assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());

        time.advance(Duration.ofMillis(1));
        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));
        assertEquals("Stopped", deployment.getStatus());
        assertEquals(90.2, deployment.getCompleteTime() - deployment.getStartTime(), 1e-6);
        verify(lambdaService, never()).updateAlias(eq(REGION), eq("myFunc"), eq("live"), eq("2"), any(), any());
    }

    @Test
    void lambdaCanaryShiftsTheRestExactlyWhenItsIntervalEnds() {
        createLambdaAppAndGroup("canary-app", "canary-group");
        String deploymentId = service.createDeployment(REGION, "canary-app", "canary-group",
                "CodeDeployDefault.LambdaCanary10Percent5Minutes", lambdaRevision(null, null), "canary");
        time.awaitSleepers(1);
        verify(lambdaService).updateAlias(REGION, "myFunc", "live", "1", null, Map.of("2", 0.1));

        time.advance(Duration.ofMinutes(5).minusMillis(1));
        assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());
        verify(lambdaService, never()).updateAlias(eq(REGION), eq("myFunc"), eq("live"), eq("2"), any(), any());

        time.advance(Duration.ofMillis(1));
        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));
        assertEquals("Succeeded", deployment.getStatus());
        verify(lambdaService).updateAlias(REGION, "myFunc", "live", "2", null, Map.of());
        Map<String, Object> allowTraffic = lambdaTargetEvent(deploymentId, "AllowTraffic");
        assertEquals("Succeeded", allowTraffic.get("status"));
        assertEquals(300.0, (double) allowTraffic.get("endTime") - (double) allowTraffic.get("startTime"));
        assertEquals(300.0, deployment.getCompleteTime() - deployment.getStartTime());
    }

    @Test
    void lambdaLinearShiftsTenPercentAtEachMinuteAndCompletesAtTheNinth() {
        createLambdaAppAndGroup("linear-app", "linear-group");
        String deploymentId = service.createDeployment(REGION, "linear-app", "linear-group",
                "CodeDeployDefault.LambdaLinear10PercentEvery1Minute", lambdaRevision(null, null), "linear");
        time.awaitSleepers(1);

        for (int step = 1; step <= 9; step++) {
            verify(lambdaService).updateAlias(REGION, "myFunc", "live", "1", null, Map.of("2", step * 10 / 100.0));
            verify(lambdaService, times(step))
                    .updateAlias(anyString(), anyString(), anyString(), anyString(), any(), any());
            time.advance(Duration.ofMinutes(1).minusMillis(1));
            assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());
            verify(lambdaService, times(step))
                    .updateAlias(anyString(), anyString(), anyString(), anyString(), any(), any());
            time.advance(Duration.ofMillis(1));
            if (step < 9) {
                time.awaitSleepers(1);
            }
        }

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));
        assertEquals("Succeeded", deployment.getStatus());
        verify(lambdaService).updateAlias(REGION, "myFunc", "live", "2", null, Map.of());
        verify(lambdaService, times(10)).updateAlias(anyString(), anyString(), anyString(), anyString(), any(), any());
        assertEquals(540.0, deployment.getCompleteTime() - deployment.getStartTime());
    }

    @Test
    void lambdaHookInvocationFailureMarksHookFailed() {
        createLambdaAppAndGroup("app-invoke-fail", "group-invoke-fail");
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenThrow(new RuntimeException("function not found"));

        String deploymentId = createLambdaDeployment("app-invoke-fail", "group-invoke-fail", "beforeHook", null);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("HOOK_EXECUTION_FAILURE", deployment.getErrorInformation().get("code"));
        Map<String, Object> event = lambdaTargetEvent(deploymentId, "BeforeAllowTraffic");
        assertEquals("Failed", event.get("status"));
        Map<String, String> diagnostics = diagnosticsOf(event);
        assertEquals("UnknownError", diagnostics.get("errorCode"));
        assertEquals("Lambda function beforeHook could not be invoked: function not found",
                diagnostics.get("message"));
    }

    @Test
    void lambdaHookFunctionErrorMarksHookFailed() {
        createLambdaAppAndGroup("app-function-error", "group-function-error");
        InvokeResult erroring = new InvokeResult(200, "Unhandled", new byte[0], null, "req-1");
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(erroring);

        String deploymentId = createLambdaDeployment("app-function-error", "group-function-error", "beforeHook", null);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("HOOK_EXECUTION_FAILURE", deployment.getErrorInformation().get("code"));
        Map<String, Object> event = lambdaTargetEvent(deploymentId, "BeforeAllowTraffic");
        assertEquals("Failed", event.get("status"));
        Map<String, String> diagnostics = diagnosticsOf(event);
        assertEquals("UnknownError", diagnostics.get("errorCode"));
        assertEquals("Lambda function beforeHook returned a function error: Unhandled",
                diagnostics.get("message"));
    }

    @Test
    void explicitSuccessfulCallbackMarksHookSucceeded() {
        createLambdaAppAndGroup("app-explicit-success", "group-explicit-success");
        InvokeResult cleanInvoke = new InvokeResult(200, null, new byte[0], null, "req-2");
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    byte[] payload = invocation.getArgument(2);
                    service.putLifecycleEventHookExecutionStatus("ignored", executionIdFrom(payload), "Succeeded");
                    return cleanInvoke;
                });

        String deploymentId = createLambdaDeployment("app-explicit-success", "group-explicit-success", "beforeHook", null);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Succeeded", deployment.getStatus());
    }

    @Test
    void lambdaHookCallbackTimeoutFailsHook() {
        createLambdaAppAndGroup("app-hook-timeout", "group-hook-timeout");
        InvokeResult cleanInvoke = new InvokeResult(200, null, new byte[0], null, "req-3");
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(cleanInvoke);

        String deploymentId = service.createDeployment(REGION, "app-hook-timeout",
                "group-hook-timeout", null, lambdaRevision("beforeHook", null), "no callback ever arrives");
        time.awaitSleepers(1);

        time.advance(Duration.ofHours(1).minusMillis(1));
        assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());
        assertEquals("InProgress", lambdaTargetEvent(deploymentId, "BeforeAllowTraffic").get("status"));

        time.advance(Duration.ofMillis(1));
        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("HOOK_EXECUTION_FAILURE", deployment.getErrorInformation().get("code"));
        Map<String, Object> event = lambdaTargetEvent(deploymentId, "BeforeAllowTraffic");
        assertEquals("Failed", event.get("status"));
        Map<String, String> diagnostics = diagnosticsOf(event);
        assertEquals("UnknownError", diagnostics.get("errorCode"));
        assertEquals("Lambda function beforeHook did not call PutLifecycleEventHookExecutionStatus within 3600 seconds",
                diagnostics.get("message"));
        verify(lambdaService, never()).updateAlias(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aHookCallbackArrivingJustInsideTheHourSucceeds() {
        createLambdaAppAndGroup("app-hook-late", "group-hook-late");
        AtomicReference<String> executionId = new AtomicReference<>();
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    executionId.set(executionIdFrom(invocation.getArgument(2)));
                    return new InvokeResult(200, null, new byte[0], null, "req-late");
                });

        String deploymentId = createLambdaDeployment("app-hook-late", "group-hook-late", "beforeHook", null);
        time.awaitSleepers(1);
        time.advance(Duration.ofHours(1).minus(STOP_POLL));
        service.putLifecycleEventHookExecutionStatus(deploymentId, executionId.get(), "Succeeded");
        assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());

        time.advance(STOP_POLL);
        assertEquals("Succeeded", awaitTerminal(deploymentId, Duration.ofSeconds(5)).getStatus());
        assertEquals("Succeeded", lambdaTargetEvent(deploymentId, "BeforeAllowTraffic").get("status"));
    }

    @Test
    void stopDeploymentDuringHookWaitEndsAsStopped() {
        createLambdaAppAndGroup("app-stop-during-wait", "group-stop-during-wait");
        InvokeResult cleanInvoke = new InvokeResult(200, null, new byte[0], null, "req-6");
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(cleanInvoke);

        String deploymentId = createLambdaDeployment("app-stop-during-wait", "group-stop-during-wait",
                "beforeHook", null);
        // The worker is parked in the callback wait, so the stop lands during it.
        time.awaitSleepers(1);
        verify(lambdaService).invoke(eq(REGION), eq("beforeHook"), any(byte[].class), any());
        time.advance(Duration.ofMinutes(10));
        service.stopDeployment(REGION, deploymentId);
        time.advance(STOP_POLL.minusMillis(1));
        assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());
        time.advance(Duration.ofMillis(1));

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Stopped", deployment.getStatus());
        Map<String, Object> event = lambdaTargetEvent(deploymentId, "BeforeAllowTraffic");
        assertEquals("Skipped", event.get("status"));
    }

    /** Wiring: the service as production builds it polls for a stop on the system timer. */
    @Test
    void realTimerNoticesAStopDuringTheHookWait() {
        service = realTimeService();
        createLambdaAppAndGroup("app-stop-during-wait", "group-stop-during-wait");
        InvokeResult cleanInvoke = new InvokeResult(200, null, new byte[0], null, "req-6");
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(cleanInvoke);

        String deploymentId = createLambdaDeployment("app-stop-during-wait", "group-stop-during-wait",
                "beforeHook", null);

        // Wait until the hook has actually been invoked so the stop lands during the callback wait.
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                verify(lambdaService).invoke(eq(REGION), eq("beforeHook"), any(byte[].class), any()));
        service.stopDeployment(REGION, deploymentId);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Stopped", deployment.getStatus());
        Map<String, Object> event = lambdaTargetEvent(deploymentId, "BeforeAllowTraffic");
        assertEquals("Skipped", event.get("status"));
    }

    @Test
    void failedBeforeAllowTrafficHookPreventsTrafficShift() {
        createLambdaAppAndGroup("app-no-progress", "group-no-progress");
        InvokeResult cleanInvoke = new InvokeResult(200, null, new byte[0], null, "req-4");
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    byte[] payload = invocation.getArgument(2);
                    service.putLifecycleEventHookExecutionStatus("ignored", executionIdFrom(payload), "Failed");
                    return cleanInvoke;
                });

        String deploymentId = createLambdaDeployment("app-no-progress", "group-no-progress", "beforeHook", "afterHook");

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("HOOK_EXECUTION_FAILURE", deployment.getErrorInformation().get("code"));
        Map<String, Object> beforeAllowTrafficEvent = lambdaTargetEvent(deploymentId, "BeforeAllowTraffic");
        assertEquals("Failed", beforeAllowTrafficEvent.get("status"));
        assertEquals("Lambda function beforeHook reported that lifecycle event BeforeAllowTraffic failed",
                diagnosticsOf(beforeAllowTrafficEvent).get("message"));
        verify(lambdaService, never()).invoke(eq(REGION), eq("afterHook"), any(byte[].class), any());
        verify(lambdaService, never()).updateAlias(anyString(), anyString(), anyString(), anyString(), any(), any());
        List<String> recordedEvents = lambdaTargetEvents(deploymentId).stream()
                .map(e -> (String) e.get("lifecycleEventName"))
                .toList();
        assertTrue(recordedEvents.contains("BeforeAllowTraffic"));
        assertFalse(recordedEvents.contains("AllowTraffic"), "AllowTraffic must not run after a failed hook");
        assertFalse(recordedEvents.contains("AfterAllowTraffic"), "AfterAllowTraffic must not run after a failed hook");
    }

    @Test
    void lateLifecycleCallbackAfterDeploymentTerminalIsNoOp() {
        createLambdaAppAndGroup("app-late-callback", "group-late-callback");
        InvokeResult cleanInvoke = new InvokeResult(200, null, new byte[0], null, "req-5");
        AtomicReference<String> capturedExecutionId = new AtomicReference<>();
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    byte[] payload = invocation.getArgument(2);
                    String executionId = executionIdFrom(payload);
                    capturedExecutionId.set(executionId);
                    service.putLifecycleEventHookExecutionStatus("ignored", executionId, "Succeeded");
                    return cleanInvoke;
                });

        String deploymentId = createLambdaDeployment("app-late-callback", "group-late-callback", "beforeHook", null);
        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));
        assertEquals("Succeeded", deployment.getStatus());

        service.putLifecycleEventHookExecutionStatus("ignored", capturedExecutionId.get(), "Failed");

        assertEquals("Succeeded", service.getDeployment(REGION, deploymentId).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetAlias", "UpdateAlias"})
    void deniedAliasPermissionFailsBeforeTheUnauthorizedCall(String operation) {
        createLambdaAppAndGroup("app-denied-alias", "group-denied-alias");
        String aliasArn = "arn:aws:lambda:us-east-1:000000000000:function:myFunc:live";
        when(roleAuthorizer.authorizeWithoutSourceContext("codedeploy", ROLE_ARN,
                "lambda:" + operation, aliasArn, ACCOUNT_ID, "aws")).thenReturn(Decision.DENY);

        String id = createLambdaDeployment("app-denied-alias", "group-denied-alias", null, null);
        Deployment deployment = awaitTerminal(id, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("IAM_ROLE_PERMISSIONS", deployment.getErrorInformation().get("code"));
        assertTrue(deployment.getErrorInformation().get("message").contains("lambda:" + operation));
        verify(roleAuthorizer).authorizeWithoutSourceContext("codedeploy", ROLE_ARN,
                "lambda:" + operation, aliasArn, ACCOUNT_ID, "aws");
        verify(lambdaService, never()).updateAlias(anyString(), anyString(), anyString(), anyString(), any(), any());
        if ("GetAlias".equals(operation)) {
            verify(lambdaService, never()).getAlias(anyString(), anyString(), anyString());
        } else {
            verify(lambdaService).getAlias(REGION, "myFunc", "live");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deniedHookPermissionPreventsInvocation(boolean beforeTraffic) {
        createLambdaAppAndGroup("app-denied-hook", "group-denied-hook");
        String hook = "arn:aws:lambda:us-east-1:000000000000:function:validation:published";
        when(roleAuthorizer.authorizeWithoutSourceContext("codedeploy", ROLE_ARN,
                "lambda:InvokeFunction", hook, ACCOUNT_ID, "aws")).thenReturn(Decision.DENY);

        String id = createLambdaDeployment("app-denied-hook", "group-denied-hook",
                beforeTraffic ? hook : null, beforeTraffic ? null : hook);
        Deployment deployment = awaitTerminal(id, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("IAM_ROLE_PERMISSIONS", deployment.getErrorInformation().get("code"));
        verify(roleAuthorizer).authorizeWithoutSourceContext("codedeploy", ROLE_ARN,
                "lambda:InvokeFunction", hook, ACCOUNT_ID, "aws");
        verify(lambdaService, never()).invoke(anyString(), anyString(), any(byte[].class), any());
        if (beforeTraffic) {
            verify(lambdaService, never()).updateAlias(
                    anyString(), anyString(), anyString(), anyString(), any(), any());
        } else {
            verify(lambdaService).updateAlias(REGION, "myFunc", "live", "2", null, Map.of());
        }
    }

    @Test
    void runningDeploymentKeepsItsSelectedRoleWhenTheGroupChanges() {
        createLambdaAppAndGroup("app-role-snapshot", "group-role-snapshot");
        String replacementRole = "arn:aws:iam::000000000000:role/ReplacementRole";
        when(roleAuthorizer.authorizeWithoutSourceContext(eq("codedeploy"), eq(replacementRole),
                anyString(), anyString(), anyString(), anyString())).thenReturn(Decision.DENY);
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    service.updateDeploymentGroup(REGION, "app-role-snapshot", "group-role-snapshot",
                            null, null, replacementRole, null);
                    byte[] payload = invocation.getArgument(2);
                    service.putLifecycleEventHookExecutionStatus("ignored", executionIdFrom(payload), "Succeeded");
                    return new InvokeResult(200, null, new byte[0], null, "role-snapshot");
                });

        String id = createLambdaDeployment("app-role-snapshot", "group-role-snapshot", "beforeHook", null);

        assertEquals("Succeeded", awaitTerminal(id, Duration.ofSeconds(5)).getStatus());
        verify(roleAuthorizer).authorizeWithoutSourceContext("codedeploy", ROLE_ARN,
                "lambda:UpdateAlias", "arn:aws:lambda:us-east-1:000000000000:function:myFunc:live", ACCOUNT_ID, "aws");
        verify(roleAuthorizer, never()).authorizeWithoutSourceContext(eq("codedeploy"), eq(replacementRole),
                anyString(), anyString(), anyString(), anyString());
    }

    // ---- Helpers --------------------------------------------------------------------------

    private String createServerDeployment(String appName, String groupName, int timeoutSeconds) {
        return createServerDeployment(service, appName, groupName, timeoutSeconds);
    }

    private String createServerDeployment(CodeDeployService target, String appName, String groupName,
                                          int timeoutSeconds) {
        target.createApplication(REGION, appName, "Server", null);
        target.createDeploymentGroup(REGION, appName, groupName, "CodeDeployDefault.AllAtOnce", ROLE_ARN, null);
        String appSpec = """
                os: linux
                hooks:
                  ApplicationStart:
                    - location: scripts/start_server.sh
                      timeout: %d
                """.formatted(timeoutSeconds);
        Map<String, Object> revision = Map.of("revisionType", "AppSpecContent",
                "appSpecContent", Map.of("content", appSpec));
        return target.createDeployment(REGION, appName, groupName, null, revision, "server hook test");
    }

    private void createLambdaAppAndGroup(String appName, String groupName) {
        service.createApplication(REGION, appName, "Lambda", null);
        service.createDeploymentGroup(REGION, appName, groupName,
                "CodeDeployDefault.LambdaAllAtOnce", ROLE_ARN, null);
    }

    private String createLambdaDeployment(String appName, String groupName, String beforeHook, String afterHook) {
        return service.createDeployment(REGION, appName, groupName, null,
                lambdaRevision(beforeHook, afterHook), "lambda hook test");
    }

    private Map<String, Object> lambdaRevision(String beforeHook, String afterHook) {
        StringBuilder hooks = new StringBuilder("[");
        if (beforeHook != null) {
            hooks.append("{\"BeforeAllowTraffic\":\"").append(beforeHook).append("\"}");
        }
        if (afterHook != null) {
            if (hooks.length() > 1) {
                hooks.append(",");
            }
            hooks.append("{\"AfterAllowTraffic\":\"").append(afterHook).append("\"}");
        }
        hooks.append("]");
        String content = """
                {
                  "version": "0.0",
                  "Resources": [
                    {"myFunc": {"Type": "AWS::Lambda::Function", "Properties": {
                      "Name": "myFunc", "Alias": "live", "CurrentVersion": "1", "TargetVersion": "2"
                    }}}
                  ],
                  "Hooks": %s
                }
                """.formatted(hooks);
        return Map.of("revisionType", "AppSpecContent", "appSpecContent", Map.of("content", content));
    }

    private static String executionIdFrom(byte[] payload) {
        try {
            JsonNode node = JSON.readTree(payload);
            return node.get("LifecycleEventHookExecutionId").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse hook payload", e);
        }
    }

    private Deployment awaitTerminal(String deploymentId, Duration atMost) {
        return awaitTerminal(service, deploymentId, atMost);
    }

    private Deployment awaitTerminal(CodeDeployService target, String deploymentId, Duration atMost) {
        await().atMost(atMost).pollInterval(Duration.ofMillis(25)).until(() ->
                isTerminal(target.getDeployment(REGION, deploymentId).getStatus()));
        return target.getDeployment(REGION, deploymentId);
    }

    private boolean isTerminal(String status) {
        return "Succeeded".equals(status) || "Failed".equals(status) || "Stopped".equals(status);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> instanceTargetEvent(String deploymentId, String targetId, String eventName) {
        Map<String, Object> target = service.batchGetDeploymentTargets(REGION, deploymentId, List.of(targetId)).get(0);
        Map<String, Object> instanceTarget = (Map<String, Object>) target.get("instanceTarget");
        List<Map<String, Object>> events = (List<Map<String, Object>>) instanceTarget.get("lifecycleEvents");
        return events.stream()
                .filter(e -> eventName.equals(e.get("lifecycleEventName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No lifecycle event named " + eventName));
    }

    private Map<String, Object> lambdaTargetEvent(String deploymentId, String eventName) {
        return lambdaTargetEvent(service, deploymentId, eventName);
    }

    private Map<String, Object> lambdaTargetEvent(CodeDeployService target, String deploymentId, String eventName) {
        return lambdaTargetEvents(target, deploymentId).stream()
                .filter(e -> eventName.equals(e.get("lifecycleEventName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No lifecycle event named " + eventName));
    }

    private List<Map<String, Object>> lambdaTargetEvents(String deploymentId) {
        return lambdaTargetEvents(service, deploymentId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lambdaTargetEvents(CodeDeployService target, String deploymentId) {
        Map<String, Object> targetMap = target.batchGetDeploymentTargets(REGION, deploymentId, List.of()).get(0);
        Map<String, Object> lambdaTarget = (Map<String, Object>) targetMap.get("lambdaTarget");
        return (List<Map<String, Object>>) lambdaTarget.get("lifecycleEvents");
    }

    @Test
    void listDeploymentGroupsWithoutApplicationNameThrowsApplicationNameRequired() {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.listDeploymentGroups("us-east-1", null));
        assertEquals("ApplicationNameRequiredException", missing.getErrorCode());
        assertEquals(400, missing.getHttpStatus());
        AwsException empty = assertThrows(AwsException.class,
                () -> service.listDeploymentGroups("us-east-1", ""));
        assertEquals("ApplicationNameRequiredException", empty.getErrorCode());
    }

    @Test
    void listDeploymentGroupsWithWhitespaceApplicationNameThrowsInvalidApplicationName() {
        AwsException blank = assertThrows(AwsException.class,
                () -> service.listDeploymentGroups("us-east-1", " "));
        assertEquals("InvalidApplicationNameException", blank.getErrorCode());
        assertEquals(400, blank.getHttpStatus());
    }

    @Test
    void listDeploymentGroupsForUnknownApplicationStillThrowsDoesNotExist() {
        AwsException unknown = assertThrows(AwsException.class,
                () -> service.listDeploymentGroups("us-east-1", "nope"));
        assertEquals("ApplicationDoesNotExistException", unknown.getErrorCode());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> diagnosticsOf(Map<String, Object> event) {
        return (Map<String, String>) event.get("diagnostics");
    }
}
