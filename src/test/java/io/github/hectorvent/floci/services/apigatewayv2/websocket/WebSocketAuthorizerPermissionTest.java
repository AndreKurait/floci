package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.services.apigateway.AuthorizerPolicyEvaluator;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testutil.LogCapture;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketAuthorizerPermissionTest {
    private static final String OWNER = "111122223333";
    private static final String REGION = "us-east-1";
    private static final String SOURCE = "arn:aws:execute-api:us-east-1:" + OWNER + ":api/authorizers/auth";
    private static final String TARGET = "arn:aws:lambda:us-west-2:444455556666:function:auth:live";
    private final ObjectMapper mapper = spy(new ObjectMapper());
    private final Authorizer authorizer = new Authorizer();
    private final LambdaService lambda = mock(LambdaService.class);
    private final EmulatorConfig.IamServiceConfig iam = mock(EmulatorConfig.IamServiceConfig.class);
    private String policy;
    private RuntimeException policyFailure;
    private WebSocketAuthorizerService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iam);
        when(iam.enforcementEnabled()).thenReturn(true);
        ResourcePolicyProvider provider = (scope, arn) -> {
            if (policyFailure != null) {
                throw policyFailure;
            }
            assertEquals("lambda", scope);
            assertEquals(TARGET, arn);
            return policy == null ? List.of() : List.of(new ResourcePolicyProvider.ResourcePolicy(policy,
                    "444455556666"));
        };
        @SuppressWarnings("unchecked")
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(invocation -> List.of(provider).iterator());
        ApiGatewayV2Service gateway = mock(ApiGatewayV2Service.class);
        authorizer.setAuthorizerType("REQUEST");
        authorizer.setAuthorizerUri("arn:aws:apigateway:us-west-2:lambda:path/2015-03-31/functions/"
                + TARGET + "/invocations");
        when(gateway.getAuthorizer(REGION, "api", "auth")).thenReturn(authorizer);
        WebSocketProxyEventBuilder builder = new WebSocketProxyEventBuilder();
        builder.objectMapper = mapper;
        builder.regionResolver = new RegionResolver(REGION, OWNER);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);
        service = new WebSocketAuthorizerService(gateway, lambda, builder, mapper,
                new AuthorizerPolicyEvaluator(evaluator), new ServiceCallAuthorizer(config, evaluator, providers),
                builder.regionResolver);
        when(lambda.invokeTarget(eq(REGION), eq(TARGET), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, """
                        {"principalId":"user","policyDocument":{"Version":"2012-10-17",
                        "Statement":[{"Effect":"Allow","Action":"execute-api:Invoke","Resource":"*"}]}}
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
    @ValueSource(strings = {"principal", "sourceAccount", "api", "authorizer", "region", "qualifier"})
    void mismatchedGrantRejectsBeforeInvocation(String mismatch) {
        policy = switch (mismatch) {
            case "principal" -> grant().replace("apigateway.amazonaws.com", "sns.amazonaws.com");
            case "sourceAccount" -> grant().replace("AWS:SourceAccount\":\"" + OWNER,
                    "AWS:SourceAccount\":\"999900001111");
            case "api" -> grant().replace("api/authorizers", "other/authorizers");
            case "authorizer" -> grant().replace("authorizers/auth", "authorizers/other");
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
        authorizer.setAuthorizerCredentialsArn("arn:aws:iam::" + OWNER + ":role/authorizer");
        assertEquals(200, invoke());
    }

    @Test
    void missingIdentityStillReturnsUnauthorizedBeforePermissionCheck() {
        authorizer.setIdentitySource(List.of("$request.header.Authorization"));
        assertEquals(401, invoke());
        verifyNoInteractions(lambda);
    }

    @ParameterizedTest
    @ValueSource(strings = {"service-policy", "service-invocation", "configured-invocation", "policy-parsing"})
    void unexpectedAuthorizerFailureRetainsOriginalCause(String failureStage) throws Exception {
        RuntimeException cause = new IllegalStateException("authorizer underlying failure");
        RuntimeException failure = new IllegalStateException("authorizer failure detail", cause);
        policy = grant();
        if ("configured-invocation".equals(failureStage)) {
            authorizer.setAuthorizerCredentialsArn("arn:aws:iam::" + OWNER + ":role/authorizer");
        }
        if ("service-policy".equals(failureStage)) {
            policyFailure = failure;
        } else if ("policy-parsing".equals(failureStage)) {
            doThrow(failure).when(mapper).readTree(any(byte[].class));
        } else {
            when(lambda.invokeTarget(anyString(), anyString(), any(byte[].class), any())).thenThrow(failure);
        }
        List<LogRecord> records = LogCapture.capture(WebSocketAuthorizerService.class,
                () -> assertEquals(500, invoke()));
        verify(lambda, times("service-policy".equals(failureStage) ? 0 : 1)).invokeTarget(
                eq(REGION), eq(TARGET), any(byte[].class), eq(InvocationType.RequestResponse));
        String template = "policy-parsing".equals(failureStage)
                ? "Failed to parse authorizer policy for API {0}: {1}"
                : "Lambda authorizer invocation failed for API {0}: {1}";
        LogRecord warning = records.stream().filter(record -> template.equals(record.getMessage()))
                .findFirst().orElseThrow();
        assertArrayEquals(new Object[]{"api", failure.getMessage()}, warning.getParameters());
        assertSame(failure, warning.getThrown());
        assertSame(cause, warning.getThrown().getCause());
    }

    @Test
    void modeledPermissionDenialDoesNotLogUnexpectedAuthorizerFailure() {
        List<LogRecord> records = LogCapture.capture(WebSocketAuthorizerService.class,
                () -> assertEquals(500, invoke()));
        verifyNoInteractions(lambda);
        assertTrue(records.stream().noneMatch(record -> record.getThrown() != null
                || "Lambda authorizer invocation failed for API {0}: {1}".equals(record.getMessage())
                || "Failed to parse authorizer policy for API {0}: {1}".equals(record.getMessage())));
    }

    private int invoke() {
        return service.invokeAndEvaluate(REGION, "api", "prod", "auth", "connection", 0,
                Map.of(), Map.of(), "127.0.0.1", false, "agent", Map.of()).statusCode();
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
