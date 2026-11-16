package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils.Arn;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.GetMethodResponse;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Capability;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class SamRestApiResourcePolicyIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "000000000000";
    private static final String FIRST_ACCOUNT = "111122223333";
    private static final String SECOND_ACCOUNT = "444455556666";
    private static final String REGION = "us-east-1";

    @Inject
    IamService iam;

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void accountAndStageReferencesReachPolicyAndUpdatesRevokeThePreviousGrant(boolean swagger) throws Exception {
        try (CloudFormationClient cfn = cfn(); ApiGatewayClient api = api()) {
            String template = template(swagger, true);
            String stack = createStack(cfn, template, FIRST_ACCOUNT, "review");
            String id = apiId(CfnStackWaits.awaitStatus(cfn, stack, "CREATE_COMPLETE"));
            assertPolicyAndMethods(api, id, FIRST_ACCOUNT, "review");

            Caller first = caller(FIRST_ACCOUNT, id);
            Caller second = caller(SECOND_ACCOUNT, id);
            invoke(id, "review", "GET", first.access(), 200);
            invoke(id, "review", "GET", second.access(), 403);
            invoke(id, "review", "DELETE", first.access(), 403);
            given().get("/execute-api/" + id + "/review/items").then().statusCode(403);

            RequestScopes.runAs(first.account(), () -> iam.putUserPolicy(first.name(), "invoke", "{\"Statement\":[]}"));
            invoke(id, "review", "GET", first.access(), 403);
            grantInvocation(first, id);
            invoke(id, "review", "GET", first.access(), 200);

            cfn.updateStack(request -> request.stackName(stack).templateBody(template)
                    .capabilities(Capability.CAPABILITY_AUTO_EXPAND)
                    .parameters(parameters(SECOND_ACCOUNT, "preview")));
            assertEquals(id, apiId(CfnStackWaits.awaitStatus(cfn, stack, "UPDATE_COMPLETE")));
            assertPolicyAndMethods(api, id, SECOND_ACCOUNT, "preview");
            invoke(id, "preview", "GET", first.access(), 403);
            invoke(id, "preview", "GET", second.access(), 200);
            invoke(id, "preview", "DELETE", second.access(), 403);

            deleteStack(cfn, stack);
            assertThrows(NotFoundException.class, () -> api.getRestApi(request -> request.restApiId(id)));
        }
    }

    @Test
    void inlineDenyWithoutSamResourcePolicyStillUsesTheNormalSignedStackPath() throws Exception {
        try (CloudFormationClient cfn = cfn(); ApiGatewayClient api = api()) {
            String stack = createStack(cfn, template(false, false), FIRST_ACCOUNT, "control");
            String id = apiId(CfnStackWaits.awaitStatus(cfn, stack, "CREATE_COMPLETE"));
            JsonNode policy = JSON.readTree(decodedPolicy(api, id));
            assertEquals(customDeny("control"), policy.path("Statement"));
            invoke(id, "control", "GET", "test", "test", 200);
            invoke(id, "control", "DELETE", "test", "test", 403);
            given().get("/execute-api/" + id + "/control/items").then().statusCode(403);
            deleteStack(cfn, stack);
            assertThrows(NotFoundException.class, () -> api.getRestApi(request -> request.restApiId(id)));
        }
    }

    private static void assertPolicyAndMethods(ApiGatewayClient api, String id, String account, String stage)
            throws Exception {
        String document = decodedPolicy(api, id);
        JsonNode policy = JSON.readTree(document);
        assertEquals("2012-10-17", policy.path("Version").asText());
        assertTrue(policy.path("Statement").isArray(), document);
        List<JsonNode> statements = new ArrayList<>();
        policy.path("Statement").forEach(statements::add);
        assertEquals(2, statements.size(), document);
        assertTrue(statements.contains(customDeny(stage)), document);
        JsonNode allow = statements.stream().filter(statement -> "Allow".equals(statement.path("Effect").asText()))
                .findFirst().orElseThrow();
        assertEquals("execute-api:Invoke", allow.path("Action").asText());
        assertEquals(JSON.valueToTree(List.of(account)), allow.path("Principal").path("AWS"));
        Set<String> resources = new HashSet<>();
        allow.path("Resource").forEach(resource -> resources.add(resource.asText()));
        assertEquals(Set.of("execute-api:/" + stage + "/GET/items",
                "execute-api:/" + stage + "/DELETE/items"), resources);
        assertFalse(document.contains("Fn::Sub"), document);

        assertEquals(stage, api.getStage(request -> request.restApiId(id).stageName(stage)).stageName());
        String resourceId = api.getResources(request -> request.restApiId(id)).items().stream()
                .filter(resource -> "/items".equals(resource.path())).findFirst().orElseThrow().id();
        for (String method : List.of("GET", "DELETE")) {
            GetMethodResponse response = api.getMethod(request -> request.restApiId(id).resourceId(resourceId)
                    .httpMethod(method));
            assertEquals("AWS_IAM", response.authorizationType());
            assertFalse(response.apiKeyRequired());
        }
    }

    private static String decodedPolicy(ApiGatewayClient api, String id) throws Exception {
        String escaped = api.getRestApi(request -> request.restApiId(id)).policy();
        assertNotNull(escaped);
        return JSON.readValue("\"" + escaped + "\"", String.class);
    }

    private Caller caller(String account, String apiId) {
        String name = "sam-policy-caller-" + UUID.randomUUID();
        RequestScopes.runAs(account, () -> iam.createUser(name, "/"));
        cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteUser(name)));
        AccessKey access = RequestScopes.callAs(account, () -> iam.createAccessKey(name));
        cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteAccessKey(name, access.getAccessKeyId())));
        Caller caller = new Caller(account, name, access);
        grantInvocation(caller, apiId);
        cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteUserPolicy(name, "invoke")));
        return caller;
    }

    private void grantInvocation(Caller caller, String apiId) {
        String resource = Arn.of("execute-api", REGION, OWNER, apiId + "/*/*/items").toString();
        String policy = JSON.valueToTree(Map.of("Statement", Map.of("Effect", "Allow",
                "Action", "execute-api:Invoke", "Resource", resource))).toString();
        RequestScopes.runAs(caller.account(), () -> iam.putUserPolicy(caller.name(), "invoke", policy));
    }

    private void invoke(String id, String stage, String method, AccessKey access, int status) throws Exception {
        invoke(id, stage, method, access.getAccessKeyId(), access.getSecretAccessKey(), status);
    }

    private void invoke(String id, String stage, String method, String accessKey, String secretKey, int status)
            throws Exception {
        String path = "/execute-api/" + id + "/" + stage + "/items";
        Map<String, String> headers = ExecuteApiRequestSigner.signedHeaders(method, path, Map.of(),
                endpoint.getHost() + ":" + endpoint.getPort(), new byte[0],
                accessKey, secretKey, REGION, Instant.now());
        if (status == 200) {
            given().headers(headers).request(method, path).then().statusCode(200).body("accepted", equalTo(true));
        } else {
            given().headers(headers).request(method, path).then().statusCode(status);
        }
    }

    private String createStack(CloudFormationClient cfn, String template, String account, String stage) {
        String name = "sam-resource-policy-" + UUID.randomUUID();
        cfn.createStack(request -> request.stackName(name).templateBody(template)
                .capabilities(Capability.CAPABILITY_AUTO_EXPAND).parameters(parameters(account, stage)));
        cleanup.register(() -> {
            try (CloudFormationClient owner = cfn()) {
                deleteStack(owner, name);
            }
        });
        return name;
    }

    private static List<Parameter> parameters(String account, String stage) {
        return List.of(Parameter.builder().parameterKey("AllowedAccountId").parameterValue(account).build(),
                Parameter.builder().parameterKey("DeploymentStage").parameterValue(stage).build());
    }

    private static String apiId(Stack stack) {
        return stack.outputs().stream().filter(output -> "ApiId".equals(output.outputKey()))
                .findFirst().orElseThrow().outputValue();
    }

    private static void deleteStack(CloudFormationClient cfn, String name) {
        if (stackDeleted(cfn, name)) {
            return;
        }
        cfn.deleteStack(request -> request.stackName(name));
        await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(25))
                .until(() -> stackDeleted(cfn, name));
    }

    private static boolean stackDeleted(CloudFormationClient cfn, String name) {
        try {
            Stack stack = cfn.describeStacks(request -> request.stackName(name)).stacks().getFirst();
            assertNotEquals(StackStatus.DELETE_FAILED, stack.stackStatus(), stack.stackStatusReason());
            return stack.stackStatus() == StackStatus.DELETE_COMPLETE;
        } catch (CloudFormationException expected) {
            if ("ValidationError".equals(expected.awsErrorDetails().errorCode())
                    && expected.awsErrorDetails().errorMessage().contains("does not exist")) {
                return true;
            }
            throw expected;
        }
    }

    private CloudFormationClient cfn() {
        return CloudFormationClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private ApiGatewayClient api() {
        return ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private static JsonNode customDeny(String stage) {
        return JSON.valueToTree(Map.of("Sid", "ProtectDeletes", "Effect", "Deny", "Principal", "*",
                "Action", "execute-api:Invoke", "Resource", "execute-api:/" + stage + "/DELETE/items"));
    }

    private static String template(boolean swagger, boolean whitelist) throws Exception {
        ObjectNode template = (ObjectNode) JSON.readTree("""
                {"Transform":"AWS::Serverless-2016-10-31",
                 "Parameters":{"AllowedAccountId":{"Type":"String"},"DeploymentStage":{"Type":"String"}},
                 "Resources":{"Api":{"Type":"AWS::Serverless::Api","Properties":{
                   "StageName":{"Ref":"DeploymentStage"},"Auth":{"DefaultAuthorizer":"AWS_IAM"},
                   "DefinitionBody":{"openapi":"3.0.1","info":{"title":"SAM resource policy","version":"1"},
                     "x-amazon-apigateway-policy":{"Version":"2012-10-17","Statement":{
                       "Sid":"ProtectDeletes","Effect":"Deny","Principal":"*","Action":"execute-api:Invoke",
                       "Resource":{"Fn::Sub":"execute-api:/${DeploymentStage}/DELETE/items"}}},
                     "paths":{"/items":{"get":{
                       "responses":{"200":{"description":"Accepted"}},
                       "x-amazon-apigateway-integration":{"type":"mock",
                         "requestTemplates":{"application/json":"{\\"statusCode\\":200}"},
                         "responses":{"default":{"statusCode":"200","responseTemplates":{
                           "application/json":"{\\"accepted\\":true}"}}}}
                     }}}
                   }
                 }}},
                 "Outputs":{"ApiId":{"Value":{"Ref":"Api"}}}}
                """);
        ObjectNode properties = (ObjectNode) template.at("/Resources/Api/Properties");
        if (whitelist) {
            ((ObjectNode) properties.get("Auth")).set("ResourcePolicy",
                    JSON.readTree("{\"AwsAccountWhitelist\":[{\"Ref\":\"AllowedAccountId\"}]}"));
        }
        ObjectNode body = (ObjectNode) properties.get("DefinitionBody");
        ObjectNode items = (ObjectNode) body.at("/paths/~1items");
        items.set("delete", items.get("get").deepCopy());
        if (swagger) {
            body.remove("openapi");
            body.put("swagger", "2.0");
            ((ObjectNode) properties.get("Auth")).remove("DefaultAuthorizer");
            ObjectNode signature = JSON.createObjectNode().put("type", "apiKey")
                    .put("name", "Authorization").put("in", "header")
                    .put("x-amazon-apigateway-authtype", "awsSigv4");
            body.set("securityDefinitions", JSON.createObjectNode().set("sigv4", signature));
            JsonNode security = JSON.readTree("[{\"sigv4\":[]}]");
            for (String method : List.of("get", "delete")) {
                ((ObjectNode) items.get(method)).set("security", security.deepCopy());
            }
        }
        return template.toString();
    }

    private record Caller(String account, String name, AccessKey access) { }
}
