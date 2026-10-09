package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class ApiGatewayImportedIntegrationTypeIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CREDENTIALS = "arn:aws:iam::000000000000:role/integration";
    private static final String LAMBDA_URI = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/"
            + "functions/arn:aws:lambda:us-east-1:000000000000:function:fixture:live/invocations";

    @ParameterizedTest
    @CsvSource({
        "aws, AWS",
        "aws_proxy, AWS_PROXY",
        "http, HTTP",
        "http_proxy, HTTP_PROXY",
        "mock, MOCK",
        "AWS_PROXY, AWS_PROXY",
        "custom_type, custom_type"
    })
    void importMergeAndOverwriteExposeCanonicalMetadataWithoutChangingOtherFields(
            String importedType, String expectedType) throws Exception {
        Map<String, Object> integration = integration(importedType);
        String apiId = importApi(document("get", integration));
        try {
            assertMetadata(apiId, "GET", integration, expectedType);

            for (String mode : List.of("merge", "overwrite")) {
                Map<String, Object> replacement = new LinkedHashMap<>(integration);
                replacement.put("timeoutInMillis", "merge".equals(mode) ? 14000 : 15000);
                if (replacement.containsKey("uri")) {
                    replacement.put("uri", replacement.get("uri").toString().replace("live", "next")
                            .replace("/original", "/replacement"));
                }
                given().contentType(ContentType.JSON).body(document("get", replacement))
                        .put("/restapis/" + apiId + "?mode=" + mode)
                        .then().statusCode(200);
                assertMetadata(apiId, "GET", replacement, expectedType);
            }
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void anyMethodUsesTheSameImportNormalization() throws Exception {
        Map<String, Object> integration = integration("mock");
        String apiId = importApi(document("x-amazon-apigateway-any-method", integration));
        try {
            assertMetadata(apiId, "ANY", integration, "MOCK");
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void directPutIntegrationKeepsItsExistingTypeHandling() throws Exception {
        String apiId = importApi(document("get", integration("MOCK")));
        try {
            Map<String, Object> direct = integration("aws_proxy");
            given().contentType(ContentType.JSON).body(direct)
                    .put(methodPath(apiId, "GET") + "/integration")
                    .then().statusCode(201);
            assertMetadata(apiId, "GET", direct, "aws_proxy");
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    private static Map<String, Object> integration(String type) {
        Map<String, Object> integration = new LinkedHashMap<>();
        integration.put("type", type);
        integration.put("httpMethod", "POST");
        integration.put("timeoutInMillis", 12000);
        integration.put("passthroughBehavior", "when_no_match");
        if ("aws".equals(type) || "aws_proxy".equals(type) || "AWS_PROXY".equals(type)) {
            integration.put("uri", LAMBDA_URI);
            integration.put("credentials", CREDENTIALS);
        } else if (!"mock".equals(type) && !"MOCK".equals(type)) {
            integration.put("uri", "https://example.test/original");
        }
        return integration;
    }

    private static Map<String, Object> document(String verb, Map<String, Object> integration) {
        return Map.of(
                "openapi", "3.0.2",
                "info", Map.of("title", "integration-type", "version", "1"),
                "components", Map.of("securitySchemes", Map.of(
                        "sigv4", Map.of("type", "apiKey", "name", "Authorization", "in", "header",
                                "x-amazon-apigateway-authtype", "awsSigv4"),
                        "key", Map.of("type", "apiKey", "name", "x-api-key", "in", "header"))),
                "security", List.of(Map.of("sigv4", List.of()), Map.of("key", List.of())),
                "paths", Map.of("/status", Map.of(verb, Map.of(
                        "responses", Map.of("200", Map.of("description", "ok")),
                        "x-amazon-apigateway-integration", integration))));
    }

    private static String importApi(Map<String, Object> document) {
        return given().contentType(ContentType.JSON).body(document)
                .post("/restapis?mode=import").then().statusCode(201).extract().path("id");
    }

    private static String methodPath(String apiId, String verb) throws Exception {
        JsonNode resources = get("/restapis/" + apiId + "/resources").path("item");
        for (JsonNode resource : resources) {
            if ("/status".equals(resource.path("path").asText())) {
                return "/restapis/" + apiId + "/resources/" + resource.path("id").asText() + "/methods/" + verb;
            }
        }
        throw new AssertionError("Imported status resource is missing");
    }

    private static void assertMetadata(String apiId, String verb, Map<String, Object> expected,
                                       String expectedType) throws Exception {
        String path = methodPath(apiId, verb);
        JsonNode method = get(path);
        JsonNode integration = get(path + "/integration");
        assertEquals("AWS_IAM", method.path("authorizationType").asText());
        assertEquals(true, method.path("apiKeyRequired").asBoolean());
        assertEquals(expectedType, integration.path("type").asText());
        assertEquals(integration, method.path("methodIntegration"));
        for (Map.Entry<String, Object> field : expected.entrySet()) {
            if (!"type".equals(field.getKey())) {
                assertEquals(MAPPER.valueToTree(field.getValue()), integration.get(field.getKey()), field.getKey());
            }
        }
    }

    private static JsonNode get(String path) throws Exception {
        return MAPPER.readTree(given().get(path).then().statusCode(200).extract().asString());
    }
}
