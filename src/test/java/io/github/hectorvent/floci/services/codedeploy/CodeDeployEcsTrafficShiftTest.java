package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.CreateTaskSetRequest;
import io.github.hectorvent.floci.services.ecs.model.TaskSet;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.ssm.SsmCommandService;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ECS blue/green traffic shifting on a controlled timeline: the emulator's documented waits (a canary
 * interval capped at five seconds, a linear step capped at two) end at their exact instant, and a stop
 * requested during one is noticed at the next stop poll instead of when the whole wait has passed.
 */
class CodeDeployEcsTrafficShiftTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "role/CodeDeployRole";
    private static final String LISTENER = "listener/app/web/1/2";
    private static final String BLUE = "targetgroup/blue/1";
    private static final String GREEN = "targetgroup/green/2";
    private static final Duration STOP_POLL = Duration.ofMillis(200);
    private static final Duration CANARY_WAIT = Duration.ofSeconds(5);
    private static final Duration LINEAR_STEP_WAIT = Duration.ofSeconds(2);

    /** The deployment worker waits on this timeline, so the test decides when a wait ends. */
    private final TestTime time = TestTime.create();
    private ElbV2Service elbV2Service;
    private CodeDeployService service;

    @BeforeEach
    void setUp() {
        EcsService ecsService = mock(EcsService.class);
        TaskSet green = new TaskSet();
        green.setId("ecs-svc/green");
        green.setTaskSetArn("task-set/green");
        green.setStatus("ACTIVE");
        when(ecsService.createTaskSet(any(CreateTaskSetRequest.class), eq(REGION))).thenReturn(green);
        elbV2Service = mock(ElbV2Service.class);
        when(elbV2Service.getTargetGroupByName(REGION, "blue")).thenReturn(targetGroup(BLUE));
        when(elbV2Service.getTargetGroupByName(REGION, "green")).thenReturn(targetGroup(GREEN));
        service = new CodeDeployService(mock(LambdaService.class), ecsService, elbV2Service,
                mock(SsmCommandService.class), mock(Ec2Service.class), new ObjectMapper(),
                new RegionResolver(REGION, ACCOUNT_ID), null, Duration.ofHours(1), time.clock(),
                mock(CodeDeployAgentDeployment.class), mock(ServiceRoleCallAuthorizer.class), null,
                new TimelineTimer(time));
        service.initializeStorage();
        service.createApplication(REGION, "ecs-app", "ECS", null);
        service.createDeploymentGroup(REGION, "ecs-app", "ecs-group", "CodeDeployDefault.ECSAllAtOnce", ROLE,
                Map.of("ecsServices", List.of(Map.of("clusterName", "main", "serviceName", "web")),
                        "loadBalancerInfo", Map.of("targetGroupPairInfoList", List.of(Map.of(
                                "targetGroups", List.of(Map.of("name", "blue"), Map.of("name", "green")),
                                "prodTrafficRoute", Map.of("listenerArns", List.of(LISTENER)))))));
    }

    @Test
    void ecsCanary_shiftsTheRestExactlyWhenItsWaitEnds() {
        String deploymentId = deploy("CodeDeployDefault.ECSCanary10Percent5Minutes");
        time.awaitSleepers(1);
        verify(elbV2Service).shiftListenerForward(REGION, LISTENER, BLUE, GREEN, 10);

        time.advance(CANARY_WAIT.minusMillis(1));
        assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());
        verify(elbV2Service, never()).shiftListenerForward(REGION, LISTENER, BLUE, GREEN, 100);

        time.advance(Duration.ofMillis(1));
        Deployment deployment = awaitTerminal(deploymentId);
        assertEquals("Succeeded", deployment.getStatus());
        verify(elbV2Service).shiftListenerForward(REGION, LISTENER, BLUE, GREEN, 100);
        assertEquals(5.0, deployment.getCompleteTime() - deployment.getStartTime());
    }

    @Test
    void ecsCanary_stopDuringTheWait_isNoticedAtTheNextStopPollAndShiftsNoMoreTraffic() {
        String deploymentId = deploy("CodeDeployDefault.ECSCanary10Percent5Minutes");
        time.awaitSleepers(1);
        time.advance(Duration.ofSeconds(1));

        service.stopDeployment(REGION, deploymentId);
        time.advance(STOP_POLL.minusMillis(1));
        assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());

        time.advance(Duration.ofMillis(1));
        Deployment deployment = awaitTerminal(deploymentId);
        assertEquals("Stopped", deployment.getStatus());
        assertEquals(1.2, deployment.getCompleteTime() - deployment.getStartTime(), 1e-6);
        verify(elbV2Service, times(1)).shiftListenerForward(anyString(), anyString(), anyString(), anyString(),
                anyInt());
    }

    @Test
    void ecsLinear_shiftsTenPercentMoreAtEachStepWaitAndCompletesAtTheNinth() {
        String deploymentId = deploy("CodeDeployDefault.ECSLinear10PercentEvery1Minutes");
        time.awaitSleepers(1);
        verify(elbV2Service).shiftListenerForward(REGION, LISTENER, BLUE, GREEN, 10);

        time.advance(LINEAR_STEP_WAIT.minusMillis(1));
        verify(elbV2Service, never()).shiftListenerForward(REGION, LISTENER, BLUE, GREEN, 20);
        time.advance(Duration.ofMillis(1));
        verify(elbV2Service).shiftListenerForward(REGION, LISTENER, BLUE, GREEN, 20);

        time.advance(LINEAR_STEP_WAIT.multipliedBy(8).minusMillis(1));
        assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());
        verify(elbV2Service, never()).shiftListenerForward(REGION, LISTENER, BLUE, GREEN, 100);

        time.advance(Duration.ofMillis(1));
        Deployment deployment = awaitTerminal(deploymentId);
        assertEquals("Succeeded", deployment.getStatus());
        verify(elbV2Service).shiftListenerForward(REGION, LISTENER, BLUE, GREEN, 100);
        assertEquals(18.0, deployment.getCompleteTime() - deployment.getStartTime());
    }

    @Test
    void ecsLinear_stopDuringAStepWait_isNoticedAtTheNextStopPollAndShiftsNoMoreTraffic() {
        String deploymentId = deploy("CodeDeployDefault.ECSLinear10PercentEvery1Minutes");
        time.awaitSleepers(1);
        time.advance(Duration.ofSeconds(1));

        service.stopDeployment(REGION, deploymentId);
        time.advance(STOP_POLL.minusMillis(1));
        assertEquals("InProgress", service.getDeployment(REGION, deploymentId).getStatus());

        time.advance(Duration.ofMillis(1));
        Deployment deployment = awaitTerminal(deploymentId);
        assertEquals("Stopped", deployment.getStatus());
        assertEquals(1.2, deployment.getCompleteTime() - deployment.getStartTime(), 1e-6);
        verify(elbV2Service, times(1)).shiftListenerForward(anyString(), anyString(), anyString(), anyString(),
                anyInt());
    }

    private String deploy(String configName) {
        String appSpec = """
                {"version": 0.0, "Resources": [{"TargetService": {"Type": "AWS::ECS::Service",
                  "Properties": {"TaskDefinition": "web:2",
                    "LoadBalancerInfo": {"ContainerName": "web", "ContainerPort": 80}}}}]}
                """;
        return service.createDeployment(REGION, "ecs-app", "ecs-group", configName,
                Map.of("revisionType", "AppSpecContent", "appSpecContent", Map.of("content", appSpec)), "shift");
    }

    private Deployment awaitTerminal(String deploymentId) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(25)).until(() ->
                List.of("Succeeded", "Failed", "Stopped")
                        .contains(service.getDeployment(REGION, deploymentId).getStatus()));
        return service.getDeployment(REGION, deploymentId);
    }

    private static TargetGroup targetGroup(String arn) {
        TargetGroup group = new TargetGroup();
        group.setTargetGroupArn(arn);
        return group;
    }
}
