package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.CallerCallAuthorizer;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.Integration;
import io.github.hectorvent.floci.services.apigateway.model.MethodConfig;
import io.github.hectorvent.floci.services.apigateway.model.Stage;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.inject.Instance;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestPublicResourcePolicyTest {

    private static final String OWNER = "111122223333";
    private static final String ROLE = "arn:aws:iam::" + OWNER + ":role/service-role/integration";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:" + OWNER + ":function:backend:live";
    private static final String INVOKE = "arn:aws:execute-api:us-east-1:" + OWNER + ":api/prod/POST/";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + INVOKE + "\"}}";
    private static final String USER = "arn:aws:iam::" + OWNER + ":user/caller";
    private static final String KEY = "caller-key";
    private ExecuteApiSigV4Authorizer signature;
    private MethodConfig method;
    private Integration integration;
    private Stage stage;
    private LambdaService lambda;
    private IamService iam;
    private EmulatorConfig config;
    private ScpProvider scps;
    private ResourcePolicyProvider provider;
    private ServiceCallAuthorizer direct;
    private ApiGatewayExecuteController controller;
    private CognitoUserPoolAuthorizer cognito;
    private HttpHeaders headers;
    private UriInfo uri;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        iam = mock(IamService.class);
        provider = mock(ResourcePolicyProvider.class);
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(invocation -> List.of(provider).iterator());
        scps = mock(ScpProvider.class);
        Instance<ScpProvider> scpInstance = mock(Instance.class);
        when(scpInstance.isResolvable()).thenReturn(true);
        when(scpInstance.get()).thenReturn(scps);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);
        ServiceRoleCallAuthorizer roles = mock(ServiceRoleCallAuthorizer.class);
        direct = mock(ServiceCallAuthorizer.class);
        ApiGatewayService gateway = mock(ApiGatewayService.class);
        lambda = mock(LambdaService.class);
        integration = new Integration();
        integration.setType("AWS_PROXY");
        integration.setUri("backend:live");
        integration.setCredentials("*");
        method = new MethodConfig();
        method.setAuthorizationType("NONE");
        method.setMethodIntegration(integration);
        ApiGatewayResource resource = new ApiGatewayResource();
        resource.setId("root");
        resource.setPath("/");
        resource.setResourceMethods(Map.of("POST", method));
        when(gateway.resolveRestApiRegion("us-east-1", "api")).thenReturn("us-east-1");
        stage = new Stage();
        when(gateway.getStage("us-east-1", "api", "prod")).thenReturn(stage);
        when(gateway.getResources("us-east-1", "api")).thenReturn(List.of(resource));
        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        uri = mock(UriInfo.class);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost/execute-api/api/prod/"));
        when(lambda.invokeTarget(anyString(), anyString(), any(byte[].class), any())).thenReturn(
                new InvokeResult(200, null, "{\"statusCode\":200,\"body\":\"ok\"}".getBytes(StandardCharsets.UTF_8), null, "request"));
        signature = mock(ExecuteApiSigV4Authorizer.class);
        when(signature.authorize(anyString(), any(), any(), any(), any())).thenReturn(
                new ExecuteApiSigV4Authorizer.Result(null, null,
                        new ExecuteApiSigV4Authorizer.CallerIdentity(KEY, OWNER, USER, "caller")));
        CallerCallAuthorizer callers = new CallerCallAuthorizer(config, iam, evaluator, providers, scpInstance);
        cognito = mock(CognitoUserPoolAuthorizer.class);
        when(cognito.authorize(anyString(), anyString(), any(), any())).thenReturn(
                new CognitoUserPoolAuthorizer.Result(null, "subject", Map.of()));
        controller = new ApiGatewayExecuteController(gateway, cognito, null, lambda,
                new RegionResolver("us-east-1", OWNER), mapper, null, null, null, null, null,
                new ApiGatewayExecuteRouteContext(), null, new RequestContext(), signature, null, null, direct, roles, callers, null);
    }

    @Test
    void boolIfExistsStillMatchesAnAbsentSignedPrincipalFlag() {
        policy(document("Allow", "*", INVOKE,
                ",\"Condition\":{\"BoolIfExists\":{\"aws:PrincipalIsAWSService\":\"false\"}}"));
        assertEquals(200, invoke());
        policy(document("Deny", "*", INVOKE,
                ",\"Condition\":{\"BoolIfExists\":{\"aws:PrincipalIsAWSService\":\"false\"}}"));
        assertEquals(403, invoke());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "COGNITO_USER_POOLS"})
    void nonIamCallerHasNoSignedPrincipalServiceFlag(String auth) {
        method.setAuthorizationType(auth);
        policy(document("Allow", "*", INVOKE,
                ",\"Condition\":{\"Bool\":{\"aws:PrincipalIsAWSService\":\"false\"}}"));
        assertEquals(403, invoke());
        policy(document("Allow", "*", INVOKE,
                ",\"Condition\":{\"Null\":{\"aws:PrincipalIsAWSService\":\"true\"}}"));
        assertEquals(200, invoke());
        policy(document("Deny", "*", INVOKE,
                ",\"Condition\":{\"Null\":{\"aws:PrincipalIsAWSService\":\"true\"}}"));
        assertEquals(403, invoke());
        verify(lambda, times(1)).invokeTarget(anyString(), anyString(), any(byte[].class), any());
        verifyNoInteractions(signature, iam, scps);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "COGNITO_USER_POOLS"})
    void attachedPolicyRequiresExplicitAnonymousGrant(String auth) {
        method.setAuthorizationType(auth);
        policy("{\"Statement\":[]}");
        assertEquals(403, invoke());
        policy(document("Allow", "*", INVOKE, ""));
        assertEquals(200, invoke());
        policy(document("Deny", "*", INVOKE, ""));
        assertEquals(403, invoke());
        verify(lambda, times(1)).invokeTarget(anyString(), anyString(), any(byte[].class), any());
        verifyNoInteractions(signature, iam, scps);
    }

    @ParameterizedTest
    @ValueSource(strings = {"action", "stage", "method", "path", "account", "region", "partition",
            "principal", "sourceIp", "secure", "principalArn", "invalid"})
    void mismatchedPolicyCannotDispatch(String cause) {
        String document = document("Allow", "*", INVOKE, "");
        document = switch (cause) {
            case "action" -> document.replace("execute-api:Invoke", "lambda:InvokeFunction");
            case "stage" -> document.replace("/prod/", "/other/");
            case "method" -> document.replace("/POST/", "/GET/");
            case "path" -> document.replace("POST/", "POST/other");
            case "account" -> document.replace(OWNER, "444455556666");
            case "region" -> document.replace("us-east-1", "us-west-2");
            case "partition" -> document.replace("arn:aws:", "arn:aws-cn:");
            case "principal" -> document("Allow", "{\"AWS\":\"" + USER + "\"}", INVOKE, "");
            case "sourceIp" -> document("Allow", "*", INVOKE,
                    ",\"Condition\":{\"IpAddress\":{\"aws:SourceIp\":\"192.0.2.0/24\"}}");
            case "secure" -> document("Allow", "*", INVOKE,
                    ",\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"true\"}}");
            case "principalArn" -> document("Allow", "*", INVOKE,
                    ",\"Condition\":{\"StringEquals\":{\"aws:PrincipalArn\":\"" + USER + "\"}}");
            case "invalid" -> "{";
            default -> throw new AssertionError(cause);
        };
        when(headers.getHeaderString("X-Forwarded-For")).thenReturn("192.0.2.42");
        when(headers.getHeaderString("Authorization")).thenReturn("Bearer " + USER);
        policy(document);
        assertEquals(403, invoke());
        verifyNoInteractions(lambda, signature, iam, scps);
    }

    @Test
    void transportAndAnonymousContextAllowWithoutIamIdentity() {
        policy(document("Allow", "*", INVOKE, ",\"Condition\":{"
                + "\"IpAddress\":{\"aws:SourceIp\":\"127.0.0.0/8\"},"
                + "\"Bool\":{\"aws:SecureTransport\":\"false\"},"
                + "\"Null\":{\"aws:PrincipalIsAWSService\":\"true\"},"
                + "\"StringEquals\":{\"aws:PrincipalAccount\":\"anonymous\","
                + "\"aws:ResourceAccount\":\"" + OWNER + "\",\"aws:RequestedRegion\":\"us-east-1\"}}"));
        assertEquals(200, invoke());
        verifyNoInteractions(signature, iam, scps);
    }

    @Test
    void explicitDenyWinsOverAllow() {
        String allow = document("Allow", "*", INVOKE, "");
        String deny = document("Deny", "*", INVOKE, "");
        when(provider.getResourcePolicies("execute-api", INVOKE)).thenReturn(List.of(
                new ResourcePolicyProvider.ResourcePolicy(allow, OWNER),
                new ResourcePolicyProvider.ResourcePolicy(deny, OWNER)));
        assertEquals(403, invoke());
        verifyNoInteractions(lambda);
    }

    @Test
    void cognitoFailurePrecedesResourcePolicy() {
        method.setAuthorizationType("COGNITO_USER_POOLS");
        when(cognito.authorize(anyString(), anyString(), any(), any())).thenReturn(
                new CognitoUserPoolAuthorizer.Result(CognitoUserPoolAuthorizer.Failure.UNAUTHORIZED, null, null));
        policy(document("Deny", "*", INVOKE, ""));
        assertEquals(401, invoke());
        verifyNoInteractions(provider, lambda);
    }

    @Test
    void resourceDenialPrecedesApiKeyAndBackendErrors() {
        method.setApiKeyRequired(true);
        method.setMethodIntegration(null);
        policy(document("Deny", "*", INVOKE, ""));
        try (Response response = controller.handlePost(headers, uri, "api", "prod", "", new byte[0])) {
            assertEquals(403, response.getStatus());
            assertTrue(response.getEntity().toString().contains("User is not authorized to access this resource"));
        }
        verifyNoInteractions(lambda);
    }

    @Test
    void absentPolicyAndDisabledIamRetainPublicAccess() {
        assertEquals(200, invoke());
        policy(document("Deny", "*", INVOKE, ""));
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        assertEquals(200, invoke());
        verifyNoInteractions(signature, iam, scps);
    }

    private static String document(String effect, String principal, String resource, String condition) {
        String value = "*".equals(principal) ? "\"*\"" : principal;
        return "{\"Statement\":{\"Effect\":\"" + effect + "\",\"Principal\":" + value
                + ",\"Action\":\"execute-api:Invoke\",\"Resource\":\"" + resource + "\"" + condition + "}}";
    }

    private void policy(String document) {
        when(provider.getResourcePolicies("execute-api", INVOKE))
                .thenReturn(List.of(new ResourcePolicyProvider.ResourcePolicy(document, OWNER)));
    }

    private int invoke() {
        try (Response response = controller.handlePost(headers, uri, "api", "prod", "", new byte[0])) {
            return response.getStatus();
        }
    }
}
