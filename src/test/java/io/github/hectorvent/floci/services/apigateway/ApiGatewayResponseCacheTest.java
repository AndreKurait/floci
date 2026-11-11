package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.CallerCallAuthorizer;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.Integration;
import io.github.hectorvent.floci.services.apigateway.model.MethodConfig;
import io.github.hectorvent.floci.services.apigateway.model.MethodSetting;
import io.github.hectorvent.floci.services.apigateway.model.RestApi;
import io.github.hectorvent.floci.services.apigateway.model.Stage;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The stage response cache of the REST execute path. A backend call is a Lambda invocation here, so a
 * response served from the cache shows as an invocation that did not happen.
 */
class ApiGatewayResponseCacheTest {

    private static final String REGION = "us-east-1";
    private static final String OWNER = "111122223333";
    private static final int TTL_SECONDS = 300;

    private ApiGatewayService gateway;
    private LambdaService lambda;
    private ApiGatewayExecuteController controller;
    private HttpHeaders headers;
    private UriInfo uri;

    @BeforeEach
    void setUp() {
        gateway = mock(ApiGatewayService.class);
        lambda = mock(LambdaService.class);
        when(lambda.invokeTarget(anyString(), anyString(), any(byte[].class), any())).thenReturn(
                new InvokeResult(200, null, "{\"statusCode\":200,\"body\":\"ok\"}".getBytes(StandardCharsets.UTF_8),
                        null, "request"));
        ServiceCallAuthorizer direct = mock(ServiceCallAuthorizer.class);
        when(direct.authorize(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(IamPolicyEvaluator.Decision.ALLOW);
        CallerCallAuthorizer callers = mock(CallerCallAuthorizer.class);
        when(callers.authorizeAnonymousResource(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(IamPolicyEvaluator.Decision.ALLOW);
        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        uri = mock(UriInfo.class);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost/execute-api/api/prod/"));
        controller = new ApiGatewayExecuteController(gateway, null, null, lambda,
                new RegionResolver(REGION, OWNER), new ObjectMapper(), null, null, null, null, null,
                new ApiGatewayExecuteRouteContext(), null, new RequestContext(), null, null, null, direct,
                mock(ServiceRoleCallAuthorizer.class), callers, null);
    }

    @Test
    void aSecondRequestToTheSameStageIsServedFromTheCache() {
        api("api", "root", null, "prod");

        assertEquals(200, get("api", "prod"));
        assertEquals(200, get("api", "prod"));

        verifyBackendCalls(1);
    }

    @Test
    void twoStagesOfOneApiDoNotShareACachedResponse() {
        api("api", "root", null, "prod", "blue");

        assertEquals(200, get("api", "prod"));
        assertEquals(200, get("api", "blue"));

        verifyBackendCalls(2);
    }

    @Test
    void twoApisWithTheSameCacheNamespaceDoNotShareACachedResponse() {
        api("api", "root", "reports", "prod");
        api("other", "other-root", "reports", "prod");

        assertEquals(200, get("api", "prod"));
        assertEquals(200, get("other", "prod"));

        verifyBackendCalls(2);
    }

    /** A REST API whose GET / proxies to a function, deployed to cache-enabled stages. */
    private void api(String apiId, String resourceId, String cacheNamespace, String... stageNames) {
        Integration integration = new Integration();
        integration.setType("AWS_PROXY");
        integration.setUri("backend:live");
        integration.setCacheNamespace(cacheNamespace);
        MethodConfig method = new MethodConfig();
        method.setAuthorizationType("NONE");
        method.setMethodIntegration(integration);
        ApiGatewayResource resource = new ApiGatewayResource();
        resource.setId(resourceId);
        resource.setPath("/");
        resource.setResourceMethods(Map.of("GET", method));
        when(gateway.resolveRestApiRegion(REGION, apiId)).thenReturn(REGION);
        when(gateway.getRestApi(REGION, apiId)).thenReturn(new RestApi());
        when(gateway.getResources(REGION, apiId)).thenReturn(List.of(resource));
        for (String stageName : stageNames) {
            MethodSetting caching = new MethodSetting();
            caching.setCachingEnabled(true);
            caching.setCacheTtlInSeconds(TTL_SECONDS);
            Stage stage = new Stage();
            stage.setStageName(stageName);
            stage.setCacheClusterEnabled(true);
            stage.getMethodSettings().put("*/*", caching);
            when(gateway.getStage(REGION, apiId, stageName)).thenReturn(stage);
        }
    }

    private int get(String apiId, String stageName) {
        try (Response response = controller.dispatch("GET", apiId, stageName, "", headers, uri, new byte[0])) {
            return response.getStatus();
        }
    }

    private void verifyBackendCalls(int expected) {
        verify(lambda, times(expected)).invokeTarget(anyString(), anyString(), any(byte[].class), any());
    }
}
