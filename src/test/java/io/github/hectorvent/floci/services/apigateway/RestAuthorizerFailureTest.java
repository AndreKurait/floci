package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.github.hectorvent.floci.services.apigateway.model.Integration;
import io.github.hectorvent.floci.services.apigateway.model.MethodConfig;
import io.github.hectorvent.floci.services.apigateway.model.Stage;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testutil.LogCapture;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestAuthorizerFailureTest {
    private static final String OWNER = "111122223333";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:" + OWNER + ":function:backend:live";
    private Authorizer authorizer;
    private LambdaService lambda;
    private ServiceCallAuthorizer direct;
    private ApiGatewayExecuteController controller;
    private HttpHeaders headers;
    private UriInfo uri;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);
        direct = mock(ServiceCallAuthorizer.class);
        ApiGatewayService gateway = mock(ApiGatewayService.class);
        lambda = mock(LambdaService.class);
        Integration integration = new Integration();
        integration.setType("MOCK");
        integration.setRequestTemplates(Map.of("application/json", "{\"statusCode\":200}"));
        integration.setUri("backend:live");
        MethodConfig method = new MethodConfig();
        method.setAuthorizationType("CUSTOM");
        method.setAuthorizerId("auth");
        authorizer = new Authorizer();
        authorizer.setId("auth");
        authorizer.setType("TOKEN");
        authorizer.setAuthorizerUri("arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
                + FUNCTION + "/invocations");
        authorizer.setAuthorizerCredentials(null);
        authorizer.setAuthorizerResultTtlInSeconds("0");
        authorizer.setIdentitySource("method.request.header.Authorization");
        when(gateway.getAuthorizer("us-east-1", "api", "auth")).thenReturn(authorizer);
        RestLambdaAuthorizer cache = new RestLambdaAuthorizer(mapper, new AuthorizerPolicyEvaluator(evaluator));
        method.setMethodIntegration(integration);
        ApiGatewayResource resource = new ApiGatewayResource();
        resource.setId("root");
        resource.setPath("/");
        resource.setResourceMethods(Map.of("POST", method));
        when(gateway.resolveRestApiRegion("us-east-1", "api")).thenReturn("us-east-1");
        Stage stage = new Stage();
        when(gateway.getStage("us-east-1", "api", "prod")).thenReturn(stage);
        when(gateway.getResources("us-east-1", "api")).thenReturn(List.of(resource));
        headers = mock(HttpHeaders.class);
        MultivaluedHashMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        requestHeaders.putSingle("Authorization", "token");
        when(headers.getRequestHeaders()).thenReturn(requestHeaders);
        when(headers.getHeaderString("Authorization")).thenReturn("token");
        uri = mock(UriInfo.class);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost/execute-api/api/prod/"));
        when(lambda.invokeTarget(anyString(), anyString(), any(byte[].class), any())).thenReturn(
                new InvokeResult(200, null, "{\"principalId\":\"caller\",\"policyDocument\":{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"*\"}]}}".getBytes(StandardCharsets.UTF_8), null, "request"));
        controller = new ApiGatewayExecuteController(gateway, null, null, lambda,
                new RegionResolver("us-east-1", OWNER), mapper, null, null, null, null, null,
                new ApiGatewayExecuteRouteContext(), null, new RequestContext(), null, cache, new AuthorizerPolicyEvaluator(evaluator), direct);
    }

    @ParameterizedTest
    @ValueSource(strings = {"service-policy", "service-invocation", "credential-invocation"})
    void unexpectedAuthorizerFailureRetainsOriginalCause(String failureStage) {
        RuntimeException cause = new IllegalStateException("authorizer transport cause");
        RuntimeException failure = new IllegalStateException("authorizer failure detail", cause);
        if ("credential-invocation".equals(failureStage)) {
            authorizer.setAuthorizerCredentials("arn:aws:iam::" + OWNER + ":role/authorizer");
        } else {
            authorizer.setAuthorizerCredentials(null);
            when(direct.authorize(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(IamPolicyEvaluator.Decision.ALLOW);
        }
        if ("service-policy".equals(failureStage)) {
            when(direct.authorize(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(failure);
        } else {
            when(lambda.invokeTarget(anyString(), anyString(), any(byte[].class), any())).thenThrow(failure);
        }
        List<LogRecord> records = LogCapture.capture(ApiGatewayExecuteController.class,
                () -> assertEquals(500, invoke()));
        verify(lambda, times("service-policy".equals(failureStage) ? 0 : 1)).invokeTarget(
                eq("us-east-1"), eq(FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse));
        LogRecord warning = records.stream().filter(record -> "Authorizer failure: {0}".equals(record.getMessage()))
                .findFirst().orElseThrow();
        assertArrayEquals(new Object[]{failure.getMessage()}, warning.getParameters());
        assertSame(failure, warning.getThrown());
        assertSame(cause, warning.getThrown().getCause());
    }

    @Test
    void modeledPermissionDenialDoesNotLogUnexpectedAuthorizerFailure() {
        authorizer.setAuthorizerCredentials(null);
        when(direct.authorize(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(IamPolicyEvaluator.Decision.DENY);
        List<LogRecord> records = LogCapture.capture(ApiGatewayExecuteController.class,
                () -> assertEquals(500, invoke()));
        verifyNoInteractions(lambda);
        assertTrue(records.stream().noneMatch(record -> "Authorizer failure: {0}".equals(record.getMessage())));
    }

    private int invoke() {
        try (Response response = controller.dispatch("POST", "api", "prod", "", headers, uri, new byte[0])) {
            return response.getStatus();
        }
    }
}
