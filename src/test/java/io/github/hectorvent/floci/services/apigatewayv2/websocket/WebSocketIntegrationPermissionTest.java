package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.CallerCallAuthorizer;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.apigateway.AwsServiceRouter;
import io.github.hectorvent.floci.services.apigateway.VtlTemplateEngine;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketIntegrationPermissionTest {
    private static final String OWNER = "111122223333";
    private static final String REGION = "us-east-1";
    private static final String SOURCE = "arn:aws:execute-api:us-east-1:" + OWNER + ":api/prod/$connect";
    private static final String TARGET = "arn:aws:lambda:us-west-2:444455556666:function:auth:live";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Integration integration = new Integration();
    private final LambdaService lambda = mock(LambdaService.class);
    private final EmulatorConfig.IamServiceConfig iam = mock(EmulatorConfig.IamServiceConfig.class);
    private String policy;
    private Runnable policyRead = () -> {};
    private WebSocketIntegrationInvoker service;
    private String routeKey = "$connect";

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iam);
        when(iam.enforcementEnabled()).thenReturn(true);
        ResourcePolicyProvider provider = (scope, arn) -> {
            assertEquals("lambda", scope);
            assertEquals(TARGET, arn);
            policyRead.run();
            return policy == null ? List.of() : List.of(new ResourcePolicyProvider.ResourcePolicy(policy,
                    "444455556666"));
        };
        @SuppressWarnings("unchecked")
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(invocation -> List.of(provider).iterator());
        integration.setIntegrationType("AWS_PROXY");
        integration.setIntegrationUri("arn:aws:apigateway:us-west-2:lambda:path/2015-03-31/functions/"
                + TARGET + "/invocations");
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);
        service = new WebSocketIntegrationInvoker(lambda, mock(AwsServiceRouter.class), mapper,
                mock(VtlTemplateEngine.class), new ServiceCallAuthorizer(config, evaluator, providers),
                new RegionResolver(REGION, OWNER), mock(ServiceRoleCallAuthorizer.class), mock(CallerCallAuthorizer.class));
        when(lambda.invokeTarget(eq(REGION), eq(TARGET), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, """
                        {"statusCode":200,"body":"ok"}
                        """.getBytes(StandardCharsets.UTF_8), null, "request"));
    }

    @Test
    void absentPermissionRejectsBeforeLambdaInvocation() {
        assertEquals(500, invoke());
        verifyNoInteractions(lambda);
    }

    @Test
    void grantAndRevocationUseForeignQualifiedTargetAndApiRegionSource() {
        policy = grant();
        assertEquals(200, invoke());
        policy = null;
        assertEquals(500, invoke());
        verify(lambda, times(1)).invokeTarget(eq(REGION), eq(TARGET), any(byte[].class),
                eq(InvocationType.RequestResponse));
    }

    @ParameterizedTest
    @ValueSource(strings = {"principal", "sourceAccount", "api", "stage", "route", "region", "qualifier"})
    void mismatchedGrantRejectsBeforeInvocation(String mismatch) {
        policy = switch (mismatch) {
            case "principal" -> grant().replace("apigateway.amazonaws.com", "sns.amazonaws.com");
            case "sourceAccount" -> grant().replace("AWS:SourceAccount\":\"" + OWNER,
                    "AWS:SourceAccount\":\"999900001111");
            case "api" -> grant().replace("api/prod", "other/prod");
            case "stage" -> grant().replace("api/prod", "api/other");
            case "route" -> grant().replace("/$connect", "/$default");
            case "region" -> grant().replace("execute-api:us-east-1", "execute-api:us-west-2");
            case "qualifier" -> grant().replace("function:auth:live", "function:auth:other");
            default -> throw new AssertionError(mismatch);
        };
        assertEquals(500, invoke());
        verifyNoInteractions(lambda);
    }

    @Test
    void explicitDenyOverridesGrant() {
        String deny = grant().replace("\"Effect\":\"Allow\"", "\"Effect\":\"Deny\"");
        policy = grant().replace("}]}", "}," + deny.substring(deny.indexOf("[{") + 1));
        assertEquals(500, invoke());
        verifyNoInteractions(lambda);
    }

    @Test
    void disabledIamRetainsInvocationWithoutGrant() {
        when(iam.enforcementEnabled()).thenReturn(false);
        assertEquals(200, invoke());
    }

    @Test
    void configuredRoleRetainsItsSeparateExecutionPath() {
        integration.setCredentialsArn("arn:aws:iam::" + OWNER + ":role/authorizer");
        assertEquals(200, invoke());
    }

    @ParameterizedTest
    @ValueSource(strings = {"$connect", "$disconnect", "$default", "send"})
    void selectedRouteControlsPermissionForBothLambdaIntegrationTypes(String route) {
        routeKey = route;
        policy = grant().replace("/$connect", "/" + route);
        assertEquals(200, invoke());
        integration.setIntegrationType("AWS");
        assertEquals(200, invoke());
        policy = grant().replace("/$connect", "/wrong");
        assertEquals(500, invoke());
        verify(lambda, times(2)).invokeTarget(eq(REGION), eq(TARGET), any(byte[].class),
                eq(InvocationType.RequestResponse));
    }

    @Test
    void stageVariablesResolveBeforeQualifiedPermissionCheck() {
        integration.setIntegrationUri(TARGET.replace(":live", ":${stageVariables.alias}"));
        policy = grant();
        assertEquals(200, invoke());
    }

    @Test
    void callerPassthroughRemainsSeparateFromServicePrincipalPermissions() {
        integration.setCredentialsArn("arn:aws:iam::*:user/*");
        assertEquals(200, invoke());
    }

    @Test
    void invalidIntegrationUriStillRejectsWithoutInvocation() {
        integration.setIntegrationUri(null);
        assertEquals(500, invoke());
        verifyNoInteractions(lambda);
    }

    @Test
    void permissionCheckAndInvocationUseSameResolvedTarget() {
        policy = grant();
        String otherTarget = TARGET.replace(":live", ":other");
        when(lambda.invokeTarget(eq(REGION), eq(otherTarget), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenReturn(new InvokeResult(200, null,
                        "{\"statusCode\":200}".getBytes(StandardCharsets.UTF_8), null, "request"));
        policyRead = () -> integration.setIntegrationUri(otherTarget);
        assertEquals(200, invoke());
        verify(lambda).invokeTarget(eq(REGION), eq(TARGET), any(byte[].class),
                eq(InvocationType.RequestResponse));
        verify(lambda, never()).invokeTarget(eq(REGION), eq(otherTarget), any(byte[].class),
                eq(InvocationType.RequestResponse));
    }

    @AfterEach
    void close() {
        service.shutdown();
    }

    private int invoke() {
        return service.invoke(REGION, "api", "prod", routeKey, integration, "{}",
                Map.of("alias", "live"), Map.of(), Map.of()).statusCode();
    }

    private String grant() {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"apigateway.amazonaws.com"},"Action":"lambda:InvokeFunction",
                "Resource":"%s","Condition":{"StringEquals":{"AWS:SourceArn":"%s",
                "AWS:SourceAccount":"%s"}}}]}
                """.formatted(TARGET, SOURCE, OWNER);
    }
}
