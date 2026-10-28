package io.github.hectorvent.floci.services.apigatewayv2;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ApiGatewayV2ImportRolesTest {

    private final ApiGatewayV2OpenApiImporter importer =
            new ApiGatewayV2OpenApiImporter(mock(ApiGatewayV2Service.class));

    @Test
    void onlyAwsIntegrationsAndRequestAuthorizersAssignExecutionRoles() {
        String document = """
                {"openapi":"3.0.1","info":{"title":"test","version":"1"},
                 "components":{"securitySchemes":{
                   "jwt":{"type":"http","scheme":"bearer","x-amazon-apigateway-authorizer":{
                     "type":"jwt","authorizerCredentials":"jwt-unused"}},
                   "request":{"type":"apiKey","name":"Authorization","in":"header",
                     "x-amazon-apigateway-authorizer":{"type":"request","authorizerCredentials":"request-role"}}
                 }},
                 "paths":{"/items":{
                   "get":{"x-amazon-apigateway-integration":{"type":"AWS","credentials":"aws-role"}},
                   "post":{"x-amazon-apigateway-integration":{"type":"http_proxy","credentials":"http-unused"}},
                   "x-amazon-apigateway-any-method":{"x-amazon-apigateway-integration":{
                     "type":"aws_proxy","credentials":"any-role"}}
                 }}}
                """;
        assertEquals(List.of("request-role", "aws-role", "any-role"),
                importer.importedHttpApiRoles(document, "us-east-1"));
    }

    @Test
    void unsupportedAuthorizerCannotAssignRolesFromAnInvalidPlan() {
        String document = """
                {"openapi":"3.0.1","info":{"title":"test","version":"1"},"paths":{},
                 "components":{"securitySchemes":{"bad":{"type":"apiKey","name":"token","in":"header",
                   "x-amazon-apigateway-authorizer":{"type":"token","authorizerCredentials":"unused"}}}}}
                """;
        assertEquals(List.of(), importer.importedHttpApiRoles(document, "us-east-1"));
        assertEquals(List.of(), importer.importedHttpApiRoles("not an OpenAPI document", "us-east-1"));
    }
}
