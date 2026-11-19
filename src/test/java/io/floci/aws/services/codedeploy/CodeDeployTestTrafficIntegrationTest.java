package io.floci.aws.services.codedeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.ecs.EcsService;
import io.floci.aws.services.ecs.model.TaskSet;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.TargetGroup;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.ComputePlatform;
import software.amazon.awssdk.services.codedeploy.model.DeploymentStatus;
import software.amazon.awssdk.services.codedeploy.model.LifecycleEventStatus;
import software.amazon.awssdk.services.codedeploy.model.RevisionLocationType;
import software.amazon.awssdk.services.codedeploy.model.TargetGroupInfo;
import software.amazon.awssdk.services.codedeploy.model.TargetGroupPairInfo;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@QuarkusTest
class CodeDeployTestTrafficIntegrationTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ELB_ARN = "arn:aws:elasticloadbalancing:us-east-1:000000000000:";
    private static final String BLUE = ELB_ARN + "targetgroup/blue/0123456789abcdef";
    private static final String GREEN = ELB_ARN + "targetgroup/green/fedcba9876543210";
    private static final String PROD_LISTENER = ELB_ARN + "listener/app/deploy/0123456789abcdef/0123456789abcdef";
    private static final String TEST_LISTENER = ELB_ARN + "listener/app/deploy/0123456789abcdef/fedcba9876543210";

    @TestHTTPResource
    URI endpoint;

    @InjectMock
    EcsService ecs;

    @InjectMock
    ElbV2Service elb;

    @InjectMock
    LambdaService lambda;

    @ParameterizedTest
    @CsvSource({"true,false", "true,true", "false,false"})
    void sdkDeploymentHonorsTestTrafficAndValidation(boolean testTraffic, boolean failHook) throws Exception {
        String app = "test-traffic-" + UUID.randomUUID();
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch workerStarted = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        try (CodeDeployClient client = CodeDeployClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10)))
                .build()) {
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
                return taskSet;
            });
            for (String name : List.of("blue", "green")) {
                TargetGroup group = new TargetGroup();
                group.setTargetGroupArn("blue".equals(name) ? BLUE : GREEN);
                when(elb.getTargetGroupByName(REGION, name)).thenReturn(group);
            }
            doAnswer(invocation -> {
                events.add(TEST_LISTENER.equals(invocation.getArgument(1)) ? "route:test" : "route:prod");
                return null;
            }).when(elb).shiftListenerForward(eq(REGION), anyString(), eq(BLUE), eq(GREEN), eq(100));
            when(lambda.invoke(eq(REGION), eq("validate"), any(byte[].class), eq(InvocationType.RequestResponse)))
                    .thenAnswer(invocation -> {
                        events.add("validate");
                        JsonNode payload = JSON.readTree((byte[]) invocation.getArgument(2));
                        client.putLifecycleEventHookExecutionStatus(request -> request
                                .deploymentId(payload.get("DeploymentId").asText())
                                .lifecycleEventHookExecutionId(payload.get("LifecycleEventHookExecutionId").asText())
                                .status(failHook ? LifecycleEventStatus.FAILED : LifecycleEventStatus.SUCCEEDED));
                        return new InvokeResult();
                    });
            client.createApplication(request -> request.applicationName(app).computePlatform(ComputePlatform.ECS));
            try {
                TargetGroupPairInfo.Builder pair = TargetGroupPairInfo.builder()
                        .targetGroups(TargetGroupInfo.builder().name("blue").build(),
                                TargetGroupInfo.builder().name("green").build())
                        .prodTrafficRoute(route -> route.listenerArns(PROD_LISTENER));
                if (testTraffic) {
                    pair.testTrafficRoute(route -> route.listenerArns(TEST_LISTENER));
                }
                client.createDeploymentGroup(request -> request.applicationName(app).deploymentGroupName("group")
                        .deploymentConfigName("CodeDeployDefault.ECSAllAtOnce")
                        .serviceRoleArn("arn:aws:iam::000000000000:role/test")
                        .ecsServices(service -> service.clusterName("cluster").serviceName("service"))
                        .loadBalancerInfo(info -> info.targetGroupPairInfoList(pair.build())));
                try {
                    String appSpec = JSON.writeValueAsString(Map.of("version", 0.0,
                            "Resources", List.of(Map.of("TargetService", Map.of("Type", "AWS::ECS::Service",
                                    "Properties", Map.of("TaskDefinition", "task:1")))),
                            "Hooks", testTraffic ? List.of(Map.of("AfterAllowTestTraffic", "validate")) : List.of()));
                    String deployment = client.createDeployment(request -> request.applicationName(app)
                            .deploymentGroupName("group").revision(revision -> revision
                                    .revisionType(RevisionLocationType.APP_SPEC_CONTENT)
                                    .appSpecContent(content -> content.content(appSpec)))).deploymentId();
                    assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
                    worker.get().join(Duration.ofSeconds(15));
                    assertFalse(worker.get().isAlive(), "Deployment worker must finish");
                    assertEquals(failHook ? DeploymentStatus.FAILED : DeploymentStatus.SUCCEEDED,
                            client.getDeployment(request -> request.deploymentId(deployment)).deploymentInfo().status());
                    assertEquals(testTraffic
                                    ? failHook ? List.of("route:test", "validate")
                                            : List.of("route:test", "validate", "route:prod")
                                    : List.of("route:prod"), events);
                    if (testTraffic) {
                        assertEquals(List.of(TEST_LISTENER), client.getDeploymentGroup(request -> request
                                .applicationName(app).deploymentGroupName("group")).deploymentGroupInfo()
                                .loadBalancerInfo().targetGroupPairInfoList().getFirst()
                                .testTrafficRoute().listenerArns());
                    }
                } finally {
                    Thread thread = worker.get();
                    if (thread != null && thread.isAlive()) {
                        thread.interrupt();
                        thread.join(Duration.ofSeconds(5));
                        assertFalse(thread.isAlive(), "Owned deployment worker must exit before cleanup");
                    }
                    client.deleteDeploymentGroup(request -> request.applicationName(app).deploymentGroupName("group"));
                }
            } finally {
                client.deleteApplication(request -> request.applicationName(app));
            }
        }
    }
}
