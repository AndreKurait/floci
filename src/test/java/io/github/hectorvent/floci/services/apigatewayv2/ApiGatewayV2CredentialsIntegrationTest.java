package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.CreateAuthorizerResponse;
import software.amazon.awssdk.services.apigatewayv2.model.CreateIntegrationResponse;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ApiGatewayV2CredentialsIntegrationTest {
    private static final String ROLE = "arn:aws:iam::000000000000:role/invoke";
    private static final String OTHER = "arn:aws:iam::000000000000:role/updated";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:000000000000:function:target";

    @TestHTTPResource
    URI endpoint;

    @Inject
    ApiGatewayV2Service service;

    @Inject
    ObjectMapper mapper;

    private ApiGatewayV2Client client() {
        return ApiGatewayV2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "WEBSOCKET"})
    void sdkCredentialsSurviveCreateUpdateListAndOmittedUpdate(String protocol) {
        try (ApiGatewayV2Client client = client()) {
            String api = client.createApi(b -> b.name("credentials").protocolType(protocol)
                    .routeSelectionExpression("$request.body.action")).apiId();
            try {
                CreateIntegrationResponse integration = client.createIntegration(b -> b.apiId(api)
                        .integrationType("AWS_PROXY").integrationUri(FUNCTION).integrationMethod("POST")
                        .payloadFormatVersion("2.0").credentialsArn(ROLE));
                assertEquals(ROLE, integration.credentialsArn());
                String id = integration.integrationId();
                assertEquals(ROLE, client.getIntegration(b -> b.apiId(api).integrationId(id)).credentialsArn());
                assertEquals(ROLE, client.getIntegrations(b -> b.apiId(api)).items().getFirst().credentialsArn());
                assertEquals(OTHER, client.updateIntegration(b -> b.apiId(api).integrationId(id)
                        .credentialsArn(OTHER)).credentialsArn());
                assertEquals(OTHER, client.updateIntegration(b -> b.apiId(api).integrationId(id)
                        .integrationMethod("POST")).credentialsArn());
                assertEquals("arn:aws:iam::*:user/*", client.updateIntegration(b -> b.apiId(api)
                        .integrationId(id).credentialsArn("arn:aws:iam::*:user/*")).credentialsArn());
                CreateAuthorizerResponse authorizer = client.createAuthorizer(b -> b.apiId(api).name("request")
                        .authorizerType("REQUEST").authorizerUri(FUNCTION).authorizerCredentialsArn(ROLE)
                        .authorizerPayloadFormatVersion("2.0").authorizerResultTtlInSeconds(0)
                        .identitySource(List.of("$request.header.Authorization")));
                assertEquals(ROLE, authorizer.authorizerCredentialsArn());
                String auth = authorizer.authorizerId();
                assertEquals(ROLE, client.getAuthorizer(b -> b.apiId(api).authorizerId(auth))
                        .authorizerCredentialsArn());
                assertEquals(ROLE, client.getAuthorizers(b -> b.apiId(api)).items().getFirst()
                        .authorizerCredentialsArn());
                assertEquals(OTHER, client.updateAuthorizer(b -> b.apiId(api).authorizerId(auth)
                        .authorizerCredentialsArn(OTHER)).authorizerCredentialsArn());
                assertEquals(OTHER, client.updateAuthorizer(b -> b.apiId(api).authorizerId(auth)
                        .name("renamed")).authorizerCredentialsArn());
            } finally {
                client.deleteApi(b -> b.apiId(api));
            }
        }
    }

    @Test
    void quickCreateAndRestoreKeepCredentialsAndOldStoredJsonDefaultsToNull() throws Exception {
        String api = service.createApi("us-east-1", Map.of("name", "quick-credentials", "protocolType", "HTTP",
                "target", FUNCTION, "credentialsArn", ROLE)).getApiId();
        try {
            Integration integration = service.getIntegrations("us-east-1", api).getFirst();
            assertEquals(ROLE, integration.getCredentialsArn());
            Integration copy = new Integration(integration);
            service.deleteIntegration("us-east-1", api, integration.getIntegrationId());
            service.restoreIntegration("us-east-1", api, copy);
            assertEquals(ROLE, service.getIntegration("us-east-1", api, copy.getIntegrationId()).getCredentialsArn());
            Authorizer auth = service.createAuthorizer("us-east-1", api, Map.of("name", "request",
                    "authorizerType", "REQUEST", "authorizerCredentialsArn", ROLE));
            service.deleteAuthorizer("us-east-1", api, auth.getAuthorizerId());
            service.restoreAuthorizer("us-east-1", api, auth);
            assertEquals(ROLE, service.getAuthorizer("us-east-1", api, auth.getAuthorizerId())
                    .getAuthorizerCredentialsArn());
            assertEquals(ROLE, mapper.readValue(mapper.writeValueAsString(copy), Integration.class).getCredentialsArn());
            assertEquals(ROLE, mapper.readValue(mapper.writeValueAsString(auth), Authorizer.class)
                    .getAuthorizerCredentialsArn());
            assertNull(mapper.readValue("{}", Integration.class).getCredentialsArn());
            assertNull(mapper.readValue("{}", Authorizer.class).getAuthorizerCredentialsArn());
        } finally {
            service.deleteApi("us-east-1", api);
        }
    }

    @Test
    void jsonCompatibilityPathKeepsCredentials() throws Exception {
        String api = service.createApi("us-east-1", Map.of("name", "json-credentials", "protocolType", "HTTP")).getApiId();
        try {
            JsonNode integration = json("CreateIntegration", Map.of("ApiId", api, "IntegrationType", "AWS_PROXY",
                    "IntegrationUri", FUNCTION, "CredentialsArn", ROLE), 201);
            assertEquals(ROLE, integration.path("CredentialsArn").asText());
            String id = integration.path("IntegrationId").asText();
            assertEquals(OTHER, json("UpdateIntegration", Map.of("ApiId", api, "IntegrationId", id,
                    "CredentialsArn", OTHER), 200).path("CredentialsArn").asText());
            JsonNode auth = json("CreateAuthorizer", Map.of("ApiId", api, "Name", "request", "AuthorizerType",
                    "REQUEST", "AuthorizerCredentialsArn", ROLE), 201);
            assertEquals(ROLE, auth.path("AuthorizerCredentialsArn").asText());
            assertEquals(OTHER, json("UpdateAuthorizer", Map.of("ApiId", api, "AuthorizerId",
                    auth.path("AuthorizerId").asText(), "AuthorizerCredentialsArn", OTHER), 200)
                    .path("AuthorizerCredentialsArn").asText());
        } finally {
            service.deleteApi("us-east-1", api);
        }
    }

    private JsonNode json(String action, Map<String, Object> body, int status) throws Exception {
        String response = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonApiGatewayV2." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261026/us-east-1/apigatewayv2/aws4_request")
                .body(mapper.writeValueAsBytes(body)).post("/").then().statusCode(status).extract().asString();
        return mapper.readTree(response);
    }
}
