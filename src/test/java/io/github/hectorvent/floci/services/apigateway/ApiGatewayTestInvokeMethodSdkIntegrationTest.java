package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;
import software.amazon.awssdk.services.apigateway.model.TestInvokeMethodRequest;
import software.amazon.awssdk.services.apigateway.model.TestInvokeMethodResponse;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayTestInvokeMethodSdkIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TestHTTPResource("/")
    URI endpoint;

    private ApiGatewayClient client;
    private String apiId;
    private String rootId;
    private String resourceId;

    @BeforeEach
    void createOwnedApi() {
        client = client(Region.US_EAST_1);
        apiId = client.createRestApi(r -> r.name("test-invoke-" + UUID.randomUUID())).id();
        rootId = client.getResources(r -> r.restApiId(apiId)).items().stream()
                .filter(resource -> "/".equals(resource.path())).findFirst().orElseThrow().id();
        resourceId = client.createResource(r -> r.restApiId(apiId).parentId(rootId).pathPart("probe")).id();
    }

    @AfterEach
    void removeOwnedApi() {
        try {
            if (apiId != null) {
                client.deleteRestApi(r -> r.restApiId(apiId));
            }
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private ApiGatewayClient client(Region region) {
        return ApiGatewayClient.builder().endpointOverride(endpoint).region(region)
                .httpClientBuilder(Apache5HttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private void configure(String id, String requestTemplate, String responseTemplate, Map<String, String> headers) {
        client.putMethod(r -> r.restApiId(apiId).resourceId(id).httpMethod("GET").authorizationType("NONE"));
        client.putIntegration(r -> r.restApiId(apiId).resourceId(id).httpMethod("GET")
                .type(IntegrationType.MOCK).requestTemplates(Map.of("application/json", requestTemplate)));
        Map<String, Boolean> declaredHeaders = new HashMap<>();
        headers.keySet().forEach(name -> declaredHeaders.put(name, false));
        for (String status : List.of("200", "201")) {
            client.putMethodResponse(r -> r.restApiId(apiId).resourceId(id).httpMethod("GET").statusCode(status)
                    .responseParameters(declaredHeaders));
            client.putIntegrationResponse(r -> r.restApiId(apiId).resourceId(id).httpMethod("GET").statusCode(status)
                    .selectionPattern("201".equals(status) ? "201" : "")
                    .responseTemplates(Map.of("application/json", responseTemplate)).responseParameters(headers));
        }
    }

    private TestInvokeMethodRequest.Builder request(String id) {
        return TestInvokeMethodRequest.builder().restApiId(apiId).resourceId(id).httpMethod("GET")
                .pathWithQueryString("/probe").body("{}")
                .headers(Map.of("Content-Type", "application/json"));
    }

    private static String header(TestInvokeMethodResponse response, String name) {
        return response.headers().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    @Test
    void sdkExecutesCurrentMappingsWithoutADeployment() throws Exception {
        configure(resourceId, "{\"statusCode\":200}", "{\"phase\":\"initial\",\"nested\":{\"n\":1}}",
                Map.of("method.response.header.X-Phase", "'initial'",
                        "method.response.header.X-Drop", "'old'"));
        TestInvokeMethodResponse first = client.testInvokeMethod(request(resourceId).build());
        assertEquals(200, first.sdkHttpResponse().statusCode());
        assertEquals(200, first.status());
        assertEquals(JSON.readTree("{\"phase\":\"initial\",\"nested\":{\"n\":1}}"), JSON.readTree(first.body()));
        assertEquals("initial", header(first, "X-Phase"));
        assertEquals("old", header(first, "X-Drop"));
        assertEquals(List.of("initial"), first.multiValueHeaders().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("X-Phase")).findFirst().orElseThrow().getValue());
        assertTrue(first.log().contains("MOCK mapping execution completed"));
        assertTrue(first.log().contains("Execution status: 200"));
        assertTrue(first.latency() >= 0);

        String updated = "{\"phase\":\"quoted \\\"value\\\"\",\"nested\":{\"n\":2},\"enabled\":true}";
        client.putIntegrationResponse(r -> r.restApiId(apiId).resourceId(resourceId).httpMethod("GET")
                .statusCode("200").responseTemplates(Map.of("application/json", updated))
                .responseParameters(Map.of("method.response.header.X-Phase", "'updated'")));
        TestInvokeMethodResponse second = client.testInvokeMethod(request(resourceId).build());
        assertEquals(JSON.readTree(updated), JSON.readTree(second.body()));
        assertEquals("updated", header(second, "X-Phase"));
        assertFalse(second.headers().keySet().stream().anyMatch(name -> name.equalsIgnoreCase("X-Drop")));
        assertNotEquals(first.log().lines().findFirst(), second.log().lines().findFirst());
        assertTrue(client.getStages(r -> r.restApiId(apiId)).item().isEmpty());
    }

    @Test
    void simulatedBodyHeadersQueryPathAndStageVariablesStaySeparateFromManagementInputs() throws Exception {
        String child = client.createResource(r -> r.restApiId(apiId).parentId(resourceId).pathPart("{id}")).id();
        String template = """
                {"body":$input.json('$'),"header":"$input.params('result')","query":"$input.params('q')",
                 "path":"$input.params('id')","stage":"$stageVariables.flag",
                 "requestId":"$context.requestId","accountId":"$context.accountId"}
                """;
        configure(child, "{\"statusCode\":$stageVariables.code}", template, Map.of());
        String payload = "{\"text\":\"quoted \\\"x\\\"\\nnext\",\"items\":[1,2],\"nested\":{\"yes\":true}}";
        String outerId = UUID.randomUUID().toString();
        TestInvokeMethodResponse first = client.testInvokeMethod(request(child)
                .body(payload).pathWithQueryString("/probe/first?q=simulated%20query")
                .multiValueHeaders(Map.of("result", List.of("simulated-header")))
                .stageVariables(Map.of("code", "201", "flag", "simulated-stage"))
                .overrideConfiguration(c -> c.putHeader("result", "management-header")
                        .putHeader("x-amzn-RequestId", outerId).putRawQueryParameter("q", "management-query"))
                .build());
        JsonNode body = JSON.readTree(first.body());
        assertEquals(201, first.status());
        assertEquals(JSON.readTree(payload), body.get("body"));
        assertEquals("simulated-header", body.path("header").asText());
        assertEquals("simulated query", body.path("query").asText());
        assertEquals("first", body.path("path").asText());
        assertEquals("simulated-stage", body.path("stage").asText());
        assertEquals("000000000000", body.path("accountId").asText());
        assertNotEquals(outerId, body.path("requestId").asText());
        UUID.fromString(body.path("requestId").asText());

        TestInvokeMethodResponse second = client.testInvokeMethod(request(child)
                .body("{\"next\":2}").pathWithQueryString("/probe/second?q=second-query")
                .headers(Map.of("Content-Type", "application/json", "result", "second-header"))
                .stageVariables(Map.of("code", "200", "flag", "second-stage")).build());
        JsonNode secondBody = JSON.readTree(second.body());
        assertEquals(200, second.status());
        assertEquals(JSON.readTree("{\"next\":2}"), secondBody.get("body"));
        assertEquals("second-header", secondBody.path("header").asText());
        assertEquals("second-query", secondBody.path("query").asText());
        assertEquals("second", secondBody.path("path").asText());
        assertEquals("second-stage", secondBody.path("stage").asText());
        assertNotEquals(body.path("requestId"), secondBody.path("requestId"));
        assertTrue(client.getStages(r -> r.restApiId(apiId)).item().isEmpty());
    }

    @Test
    void testStageVariablesDoNotChangeTheLiveExecutePath() {
        configure(resourceId, "{\"statusCode\":$stageVariables.code}",
                "{\"flag\":\"$stageVariables.flag\"}", Map.of());
        String deployment = client.createDeployment(r -> r.restApiId(apiId)).id();
        client.createStage(r -> r.restApiId(apiId).deploymentId(deployment).stageName("live")
                .variables(Map.of("code", "200", "flag", "live")));
        String path = "/execute-api/" + apiId + "/live/probe";
        given().get(path).then().statusCode(200).body("flag", equalTo("live"));

        TestInvokeMethodResponse tested = client.testInvokeMethod(request(resourceId)
                .stageVariables(Map.of("code", "201", "flag", "simulated")).build());
        assertEquals(201, tested.status());
        assertTrue(tested.body().contains("simulated"));
        given().get(path).then().statusCode(200).body("flag", equalTo("live"));
        assertEquals(Map.of("code", "200", "flag", "live"),
                client.getStage(r -> r.restApiId(apiId).stageName("live")).variables());
        assertEquals(List.of("live"), client.getStages(r -> r.restApiId(apiId)).item().stream()
                .map(stage -> stage.stageName()).toList());
    }

    @Test
    void exactApiResourceMethodAndRegionLookupsRefuseMissingResources() {
        configure(resourceId, "{\"statusCode\":200}", "{\"ok\":true}", Map.of());
        String otherApi = client.createRestApi(r -> r.name("test-invoke-independent")).id();
        try (ApiGatewayClient west = client(Region.US_WEST_2)) {
            String otherRoot = client.getResources(r -> r.restApiId(otherApi)).items().get(0).id();
            assertEquals(404, assertThrows(NotFoundException.class, () -> client.testInvokeMethod(
                    request(resourceId).httpMethod("DELETE").build())).statusCode());
            assertEquals(404, assertThrows(NotFoundException.class, () -> client.testInvokeMethod(
                    request(otherRoot).build())).statusCode());
            assertEquals(404, assertThrows(NotFoundException.class, () -> west.testInvokeMethod(
                    request(resourceId).build())).statusCode());
            assertEquals(200, client.testInvokeMethod(request(resourceId).build()).status());
        } finally {
            client.deleteRestApi(r -> r.restApiId(otherApi));
        }
    }

    @Test
    void mappingFailuresAndMissingOutputMappingsUseTheExecutionResultEnvelope() {
        configure(resourceId, "#if(true)", "{\"ok\":true}", Map.of());
        TestInvokeMethodResponse malformed = client.testInvokeMethod(request(resourceId).build());
        assertEquals(200, malformed.sdkHttpResponse().statusCode());
        assertEquals(500, malformed.status());
        assertTrue(malformed.log().contains("Execution status: 500"));
        assertNotNull(malformed.body());

        client.putIntegration(r -> r.restApiId(apiId).resourceId(resourceId).httpMethod("GET")
                .type(IntegrationType.MOCK).requestTemplates(Map.of("application/json", "{\"statusCode\":200}")));
        TestInvokeMethodResponse bare = client.testInvokeMethod(request(resourceId).build());
        assertEquals(200, bare.sdkHttpResponse().statusCode());
        assertEquals(500, bare.status());
        assertTrue(bare.log().contains("MOCK configuration failed"));
    }

    @Test
    void unsupportedIntegrationAndClientCertificateInputsAreExplicitlyRefused() {
        configure(resourceId, "{\"statusCode\":200}", "{\"ok\":true}", Map.of());
        assertEquals(400, assertThrows(BadRequestException.class, () -> client.testInvokeMethod(
                request(resourceId).clientCertificateId("certificate-not-supported").build())).statusCode());
        client.putIntegration(r -> r.restApiId(apiId).resourceId(resourceId).httpMethod("GET")
                .type(IntegrationType.HTTP_PROXY).integrationHttpMethod("GET").uri("http://127.0.0.1:9/unused"));
        assertEquals(400, assertThrows(BadRequestException.class, () -> client.testInvokeMethod(
                request(resourceId).build())).statusCode());
    }

    @Test
    void malformedInputTypesAreRefusedWhileAStringContainingJsonStillExecutes() {
        configure(resourceId, "{\"statusCode\":200}", "$input.json('$')", Map.of());
        String path = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
        for (String body : List.of("[]", "{\"body\":3}", "{\"headers\":[]}",
                "{\"headers\":{\"test\":3}}", "{\"multiValueHeaders\":{\"test\":[3]}}",
                "{\"stageVariables\":{\"code\":200}}")) {
            given().contentType("application/json").body(body).post(path).then().statusCode(400);
        }
        TestInvokeMethodResponse result = client.testInvokeMethod(request(resourceId).body("3").build());
        assertEquals(200, result.status());
        assertEquals("3", result.body());
    }

    @Test
    void methodApiKeyAndIamViewerGatesRemainActiveOnlyOnTheLivePath() {
        configure(resourceId, "{\"statusCode\":200}", "{\"executed\":true}", Map.of());
        client.updateMethod(r -> r.restApiId(apiId).resourceId(resourceId).httpMethod("GET")
                .patchOperations(PatchOperation.builder().op("replace").path("/apiKeyRequired").value("true").build()));
        String deployment = client.createDeployment(r -> r.restApiId(apiId)).id();
        client.createStage(r -> r.restApiId(apiId).deploymentId(deployment).stageName("key"));
        String keyPath = "/execute-api/" + apiId + "/key/probe";
        given().get(keyPath).then().statusCode(403);
        assertEquals(200, client.testInvokeMethod(request(resourceId).build()).status());
        given().get(keyPath).then().statusCode(403);

        client.updateMethod(r -> r.restApiId(apiId).resourceId(resourceId).httpMethod("GET")
                .patchOperations(PatchOperation.builder().op("replace").path("/apiKeyRequired").value("false").build(),
                        PatchOperation.builder().op("replace").path("/authorizationType").value("AWS_IAM").build()));
        String iamDeployment = client.createDeployment(r -> r.restApiId(apiId)).id();
        client.createStage(r -> r.restApiId(apiId).deploymentId(iamDeployment).stageName("iam"));
        String iamPath = "/execute-api/" + apiId + "/iam/probe";
        given().get(iamPath).then().statusCode(403);
        assertEquals(200, client.testInvokeMethod(request(resourceId).build()).status());
        given().get(iamPath).then().statusCode(403);
    }

    @Test
    void currentRequestValidatorsUseSimulatedParametersAndTypedBody() {
        configure(resourceId, "{\"statusCode\":200}", "$input.json('$')", Map.of());
        client.createModel(r -> r.restApiId(apiId).name("TypedInput").contentType("application/json")
                .schema("{\"type\":\"object\",\"required\":[\"count\"],\"properties\":{\"count\":{\"type\":\"integer\"}}}"));
        String validator = client.createRequestValidator(r -> r.restApiId(apiId).name("required-input")
                .validateRequestBody(true).validateRequestParameters(true)).id();
        client.updateMethod(r -> r.restApiId(apiId).resourceId(resourceId).httpMethod("GET")
                .patchOperations(PatchOperation.builder().op("replace").path("/requestValidatorId")
                                .value(validator).build(),
                        PatchOperation.builder().op("add").path("/requestModels/application~1json")
                                .value("TypedInput").build(),
                        PatchOperation.builder().op("add").path("/requestParameters/method.request.querystring.required")
                                .value("true").build(),
                        PatchOperation.builder().op("add").path("/requestParameters/method.request.header.X-Required")
                                .value("true").build()));
        TestInvokeMethodRequest.Builder valid = request(resourceId).body("{\"count\":2}")
                .pathWithQueryString("/probe?required=yes")
                .headers(Map.of("Content-Type", "application/json", "X-Required", "simulated"));
        TestInvokeMethodResponse missingQuery = client.testInvokeMethod(valid.build().toBuilder()
                .pathWithQueryString("/probe").overrideConfiguration(c -> c
                        .putRawQueryParameter("required", "management-only")).build());
        assertEquals(200, missingQuery.sdkHttpResponse().statusCode());
        assertEquals(400, missingQuery.status());
        assertTrue(missingQuery.log().contains("Request validation failed"));
        TestInvokeMethodResponse missingHeader = client.testInvokeMethod(valid.build().toBuilder()
                .headers(Map.of("Content-Type", "application/json")).overrideConfiguration(c -> c
                        .putHeader("X-Required", "management-only")).build());
        assertEquals(400, missingHeader.status());
        assertEquals(400, client.testInvokeMethod(valid.build().toBuilder().body("{\"count\":\"wrong\"}").build()).status());
        assertEquals(200, client.testInvokeMethod(valid.build()).status());
        assertTrue(client.getStages(r -> r.restApiId(apiId)).item().isEmpty());
    }

    @Test
    void ambiguousHeaderMapsAndNonlocalPathsAreExplicitlyRefused() {
        configure(resourceId, "{\"statusCode\":200}", "{\"ok\":true}", Map.of());
        assertEquals(400, assertThrows(BadRequestException.class, () -> client.testInvokeMethod(request(resourceId)
                .headers(Map.of("result", "single")).multiValueHeaders(Map.of("RESULT", List.of("multiple")))
                .build())).statusCode());
        for (String path : List.of("https://example.invalid/probe", "//example.invalid/probe", "/probe#fragment")) {
            assertEquals(400, assertThrows(BadRequestException.class, () -> client.testInvokeMethod(
                    request(resourceId).pathWithQueryString(path).build())).statusCode());
        }
        assertEquals(200, client.testInvokeMethod(request(resourceId).build()).status());
    }
}
