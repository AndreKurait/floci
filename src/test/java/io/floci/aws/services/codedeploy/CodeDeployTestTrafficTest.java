package io.floci.aws.services.codedeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.codedeploy.model.Deployment;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ecs.EcsService;
import io.floci.aws.services.ecs.model.TaskSet;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.TargetGroup;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.ssm.SsmCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeDeployTestTrafficTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> HOOKS = List.of(
            "BeforeInstall", "AfterInstall", "AfterAllowTestTraffic", "BeforeAllowTraffic", "AfterAllowTraffic");

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private final CountDownLatch workerStarted = new CountDownLatch(1);
    private CodeDeployService service;
    private ElbV2Service elb;
    private String failedHook;
    private String stoppedHook;
    private boolean includeTestHook = true;

    @BeforeEach
    void setUp() {
        EcsService ecs = mock(EcsService.class);
        elb = mock(ElbV2Service.class);
        LambdaService lambda = mock(LambdaService.class);
        service = new CodeDeployService(lambda, ecs, elb, mock(SsmCommandService.class),
                mock(Ec2Service.class), JSON, new RegionResolver(REGION, "000000000000"), null,
                Duration.ofSeconds(1), Clock.systemUTC(), null, null);
        service.initializeStorage();
        service.createApplication(REGION, "triage", "ECS", null);
        when(ecs.describeTaskSets("cluster", "service", null, REGION)).thenAnswer(invocation -> {
            worker.set(Thread.currentThread());
            workerStarted.countDown();
            return List.of();
        });
        when(ecs.createTaskSet(any(), eq(REGION))).thenAnswer(invocation -> {
            TaskSet taskSet = new TaskSet();
            taskSet.setId("green");
            taskSet.setTaskSetArn("green-task-set");
            taskSet.setStatus("ACTIVE");
            events.add("Install");
            return taskSet;
        });
        for (String name : List.of("blue", "green")) {
            TargetGroup group = new TargetGroup();
            group.setTargetGroupArn(name);
            when(elb.getTargetGroupByName(REGION, name)).thenReturn(group);
        }
        doAnswer(invocation -> {
            events.add("route:" + invocation.getArgument(1));
            return null;
        }).when(elb).shiftListenerForward(eq(REGION), anyString(), eq("blue"), eq("green"), eq(100));
        when(lambda.invoke(eq(REGION), anyString(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    String hook = invocation.getArgument(1);
                    JsonNode payload = JSON.readTree((byte[]) invocation.getArgument(2));
                    events.add(hook);
                    if (hook.equals(stoppedHook)) {
                        service.stopDeployment(REGION, payload.get("DeploymentId").asText());
                    }
                    service.putLifecycleEventHookExecutionStatus(payload.get("DeploymentId").asText(),
                            payload.get("LifecycleEventHookExecutionId").asText(),
                            hook.equals(failedHook) ? "Failed" : "Succeeded");
                    return new InvokeResult();
                });
    }

    @AfterEach
    void workerHasExited() throws InterruptedException {
        Thread thread = worker.get();
        if (thread != null) {
            thread.join(Duration.ofSeconds(5));
            assertFalse(thread.isAlive(), "Owned deployment worker must exit");
        }
    }

    @Test
    void testTrafficHookRunsInDocumentedOrder() throws Exception {
        assertEquals("Succeeded", deploy(true).getStatus());
        assertEquals(HOOKS, events.stream().filter(HOOKS::contains).toList());
        assertEquals(List.of("BeforeInstall", "Install", "AfterInstall", "route:test",
                "AfterAllowTestTraffic", "BeforeAllowTraffic", "route:prod", "AfterAllowTraffic"), events);
    }

    @Test
    void testListenerMovesBeforeProduction() throws Exception {
        assertEquals("Succeeded", deploy(true).getStatus());
        assertEquals(List.of("route:test", "route:prod"),
                events.stream().filter(event -> event.startsWith("route:")).toList());
    }

    @Test
    void failedTestTrafficHookPreventsProductionShift() throws Exception {
        failedHook = "AfterAllowTestTraffic";
        assertEquals("Failed", deploy(true).getStatus());
        verify(elb, never()).shiftListenerForward(REGION, "prod", "blue", "green", 100);
    }

    @Test
    void deploymentWithoutTestRouteStillMovesProduction() throws Exception {
        assertEquals("Succeeded", deploy(false).getStatus());
        assertEquals(List.of("route:prod"),
                events.stream().filter(event -> event.startsWith("route:")).toList());
    }

    @Test
    void existingAfterInstallFailurePreventsProductionShift() throws Exception {
        failedHook = "AfterInstall";
        assertEquals("Failed", deploy(true).getStatus());
        verify(elb, never()).shiftListenerForward(REGION, "prod", "blue", "green", 100);
    }

    @Test
    void testRouteWithoutValidationHookStillMovesBeforeProduction() throws Exception {
        includeTestHook = false;
        assertEquals("Succeeded", deploy(true).getStatus());
        assertEquals(List.of("BeforeInstall", "Install", "AfterInstall", "route:test",
                "BeforeAllowTraffic", "route:prod", "AfterAllowTraffic"), events);
    }

    @Test
    void testRoutingFailurePreventsValidationAndProductionShift() throws Exception {
        doThrow(new IllegalStateException("test listener unavailable")).when(elb)
                .shiftListenerForward(REGION, "test", "blue", "green", 100);
        assertEquals("Failed", deploy(true).getStatus());
        assertFalse(events.contains("AfterAllowTestTraffic"));
        verify(elb, never()).shiftListenerForward(REGION, "prod", "blue", "green", 100);
    }

    @Test
    void missingTestTargetGroupPreventsValidationAndProductionShift() throws Exception {
        when(elb.getTargetGroupByName(REGION, "green")).thenReturn(null);
        assertEquals("Failed", deploy(true).getStatus());
        assertFalse(events.contains("AfterAllowTestTraffic"));
        verify(elb, never()).shiftListenerForward(REGION, "prod", "blue", "green", 100);
    }

    @Test
    void stoppingAtTestHookPreventsProductionShift() throws Exception {
        stoppedHook = "AfterAllowTestTraffic";
        assertEquals("Stopped", deploy(true).getStatus());
        verify(elb, never()).shiftListenerForward(REGION, "prod", "blue", "green", 100);
    }

    private Deployment deploy(boolean testTraffic) throws Exception {
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("targetGroups", List.of(Map.of("name", "blue"), Map.of("name", "green")));
        pair.put("prodTrafficRoute", Map.of("listenerArns", List.of("prod")));
        if (testTraffic) {
            pair.put("testTrafficRoute", Map.of("listenerArns", List.of("test")));
        }
        service.createDeploymentGroup(REGION, "triage", "group", "CodeDeployDefault.ECSAllAtOnce",
                "arn:aws:iam::000000000000:role/test", Map.of(
                        "ecsServices", List.of(Map.of("clusterName", "cluster", "serviceName", "service")),
                        "loadBalancerInfo", Map.of("targetGroupPairInfoList", List.of(pair))));
        List<Map<String, String>> hooks = new ArrayList<>();
        for (String hook : HOOKS) {
            if ((testTraffic && includeTestHook) || !"AfterAllowTestTraffic".equals(hook)) {
                hooks.add(Map.of(hook, hook));
            }
        }
        String appSpec = JSON.writeValueAsString(Map.of("version", 0.0,
                "Resources", List.of(Map.of("TargetService", Map.of("Type", "AWS::ECS::Service",
                        "Properties", Map.of("TaskDefinition", "task:1")))), "Hooks", hooks));
        String id = service.createDeployment(REGION, "triage", "group", null,
                Map.of("appSpecContent", Map.of("content", appSpec)), null);
        assertTrue(workerStarted.await(5, TimeUnit.SECONDS), "Deployment must start its worker");
        Thread thread = worker.get();
        assertNotNull(thread, "Deployment must start its worker");
        thread.join(Duration.ofSeconds(5));
        assertFalse(thread.isAlive(), "Deployment must finish");
        return service.getDeployment(REGION, id);
    }
}
