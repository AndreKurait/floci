package io.floci.aws.services.codedeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.ServiceRoleCallAuthorizer;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@QuarkusTest
class CodeDeployLambdaScopeIntegrationTest {

    @Inject
    CodeDeployService service;

    @Inject
    RegionResolver regions;

    @Inject
    ObjectMapper mapper;

    @InjectMock
    LambdaService lambda;

    @InjectMock
    ServiceRoleCallAuthorizer roles;

    @ParameterizedTest
    @ValueSource(strings = {"us-west-2", "cn-north-1"})
    void trafficShiftAndBothHooksKeepTheDeploymentOwner(String region) {
        runDeployment(region, "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetAlias", "before", "UpdateAlias", "after"})
    void deniedRoleStopsBeforeTheProtectedLambdaOperation(String denied) {
        runDeployment("us-west-2", denied);
    }

    private void runDeployment(String region, String denied) {
        String account = "123456789012";
        String app = "lambda-scope-" + UUID.randomUUID();
        List<String> observations = new CopyOnWriteArrayList<>();
        List<String> checks = new CopyOnWriteArrayList<>();
        when(roles.authorizeWithoutSourceContext(eq("codedeploy"), anyString(), anyString(),
                anyString(), eq(account), anyString())).thenAnswer(invocation -> {
                    String action = invocation.getArgument(2);
                    String arn = invocation.getArgument(3);
                    String target = action.equals("lambda:InvokeFunction")
                            ? arn.substring(arn.lastIndexOf(':') + 1) : action.substring(7);
                    assertEquals(regions.buildGlobalArn("iam", "role/deploy"), invocation.getArgument(1));
                    assertEquals(regions.getPartition(), invocation.getArgument(5));
                    assertEquals(regions.buildArn("lambda", region, "function:"
                            + (target.equals("before") || target.equals("after") ? target : "function:live")), arn);
                    checks.add(target);
                    return target.equals(denied) ? Decision.DENY : Decision.ALLOW;
                });
        when(lambda.getAlias(eq(region), eq("function"), eq("live"))).thenAnswer(invocation -> {
            observations.add("lookup");
            return null;
        });
        when(lambda.invoke(eq(region), anyString(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    observations.add(invocation.getArgument(1) + ":" + regions.getAccountId()
                            + ":" + regions.getRegion() + ":" + regions.getPartition());
                    JsonNode payload = mapper.readTree((byte[]) invocation.getArgument(2));
                    service.putLifecycleEventHookExecutionStatus(payload.path("DeploymentId").asText(),
                            payload.path("LifecycleEventHookExecutionId").asText(), "Succeeded");
                    return new InvokeResult(200, null, new byte[0], null, "hook");
                });
        when(lambda.updateAlias(eq(region), eq("function"), eq("live"), eq("2"), eq(null), eq(Map.of())))
                .thenAnswer(invocation -> {
                    observations.add("traffic:" + regions.getAccountId()
                            + ":" + regions.getRegion() + ":" + regions.getPartition());
                    return null;
                });
        try {
            String deployment = RequestScopes.callAs(account, region, () -> {
                service.createApplication(region, app, "Lambda", null);
                service.createDeploymentGroup(region, app, "group",
                        "CodeDeployDefault.LambdaAllAtOnce", regions.buildGlobalArn("iam", "role/deploy"), null, null);
                return service.createDeployment(region, app, "group", null, revision(), null);
            });
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    RequestScopes.runAs(account, region, () -> assertEquals(
                            denied.isEmpty() ? "Succeeded" : "Failed", service.getDeployment(region, deployment).getStatus())));
            String owner = RequestScopes.callAs(account, region, () ->
                    ":" + account + ":" + region + ":" + regions.getPartition());
            List<String> allChecks = List.of("GetAlias", "before", "UpdateAlias", "after");
            int allowed = denied.isEmpty() ? 4 : allChecks.indexOf(denied);
            assertEquals(allChecks.subList(0, denied.isEmpty() ? 4 : allowed + 1), checks);
            assertEquals(List.of("lookup", "before" + owner, "traffic" + owner, "after" + owner)
                    .subList(0, allowed), observations);
            if (!denied.isEmpty()) {
                RequestScopes.runAs(account, region, () -> assertEquals("IAM_ROLE_PERMISSIONS",
                        service.getDeployment(region, deployment).getErrorInformation().get("code")));
            }
        } finally {
            RequestScopes.runAs(account, region, () -> service.deleteApplication(region, app));
        }
    }

    private Map<String, Object> revision() {
        String content = """
                {
                  "version": "0.0",
                  "Resources": [
                    {"function": {"Type": "AWS::Lambda::Function", "Properties": {
                      "Name": "function", "Alias": "live", "CurrentVersion": "1", "TargetVersion": "2"
                    }}}
                  ],
                  "Hooks": [{"BeforeAllowTraffic": "before"}, {"AfterAllowTraffic": "after"}]
                }
                """;
        return Map.of("revisionType", "AppSpecContent", "appSpecContent", Map.of("content", content));
    }
}
