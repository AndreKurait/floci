package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
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

    @ParameterizedTest
    @ValueSource(strings = {"us-west-2", "cn-north-1"})
    void trafficShiftAndBothHooksKeepTheDeploymentOwner(String region) {
        String account = "123456789012";
        String app = "lambda-scope-" + UUID.randomUUID();
        List<String> observations = new CopyOnWriteArrayList<>();
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
                        "CodeDeployDefault.LambdaAllAtOnce", regions.buildGlobalArn("iam", "role/deploy"), null);
                return service.createDeployment(region, app, "group", null, revision(), null);
            });
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    RequestScopes.runAs(account, region, () -> assertEquals(
                            "Succeeded", service.getDeployment(region, deployment).getStatus())));
            String owner = RequestScopes.callAs(account, region, () ->
                    ":" + account + ":" + region + ":" + regions.getPartition());
            assertEquals(List.of("before" + owner, "traffic" + owner, "after" + owner), observations);
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
