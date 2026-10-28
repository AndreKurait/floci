package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.CreateAuthorizerResponse;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RestAuthorizerCredentialsIntegrationTest {
    private static final String ROLE = "arn:aws:iam::000000000000:role/authorizer";
    private static final String OTHER = "arn:aws:iam::000000000000:role/replacement";
    private static final String URI_VALUE = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
            + "arn:aws:lambda:us-east-1:000000000000:function:auth/invocations";

    @TestHTTPResource
    URI endpoint;

    @Inject
    ApiGatewayService service;

    @Inject
    ObjectMapper mapper;

    private ApiGatewayClient client() {
        return ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @Test
    void sdkRetainsCredentialsAndReplacesOrClearsThemAtomically() {
        try (ApiGatewayClient client = client()) {
            String api = client.createRestApi(b -> b.name("authorizer-credentials")).id();
            try {
                CreateAuthorizerResponse created = client.createAuthorizer(b -> b.restApiId(api).name("token")
                        .type("TOKEN").authorizerUri(URI_VALUE).identitySource("method.request.header.Authorization")
                        .authorizerCredentials(ROLE));
                String id = created.id();
                assertEquals(ROLE, created.authorizerCredentials());
                assertEquals(ROLE, client.getAuthorizer(b -> b.restApiId(api).authorizerId(id)).authorizerCredentials());
                assertEquals(ROLE, client.getAuthorizers(b -> b.restApiId(api)).items().getFirst().authorizerCredentials());
                assertEquals(ROLE, client.updateAuthorizer(b -> b.restApiId(api).authorizerId(id)
                        .patchOperations(patch("replace", "/name", "renamed"))).authorizerCredentials());
                assertEquals(OTHER, client.updateAuthorizer(b -> b.restApiId(api).authorizerId(id)
                        .patchOperations(patch("replace", "/authorizerCredentials", OTHER))).authorizerCredentials());
                assertThrows(BadRequestException.class, () -> client.updateAuthorizer(b -> b.restApiId(api)
                        .authorizerId(id).patchOperations(patch("replace", "/authorizerCredentials", ROLE),
                                patch("replace", "/authorizerResultTtlInSeconds", "invalid"))));
                assertEquals(OTHER, client.getAuthorizer(b -> b.restApiId(api).authorizerId(id)).authorizerCredentials());
                for (String op : new String[]{"add", "remove"}) {
                    assertThrows(BadRequestException.class, () -> client.updateAuthorizer(b -> b.restApiId(api)
                            .authorizerId(id).patchOperations(patch(op, "/authorizerCredentials", ROLE))));
                }
                assertNull(client.updateAuthorizer(b -> b.restApiId(api).authorizerId(id)
                        .patchOperations(patch("replace", "/authorizerCredentials", null))).authorizerCredentials());
                assertNull(client.getAuthorizer(b -> b.restApiId(api).authorizerId(id)).authorizerCredentials());
            } finally {
                client.deleteRestApi(b -> b.restApiId(api));
            }
        }
    }

    @Test
    void storedAuthorizerJsonKeepsCredentialsAndReadsLegacyRecords() throws Exception {
        String api = service.createRestApi("us-east-1", Map.of("name", "stored-credentials")).getId();
        try {
            Authorizer authorizer = service.createAuthorizer("us-east-1", api,
                    Map.of("name", "token", "type", "TOKEN", "authorizerCredentials", ROLE));
            assertEquals(ROLE, mapper.readValue(mapper.writeValueAsString(authorizer), Authorizer.class)
                    .getAuthorizerCredentials());
            assertNull(mapper.readValue("{}", Authorizer.class).getAuthorizerCredentials());
        } finally {
            service.deleteRestApi("us-east-1", api);
        }
    }

    @Test
    void importedCredentialsPreserveOmittedMergeClearExplicitNullAndRollbackInvalidType() {
        String spec = """
                {"openapi":"3.0.1","info":{"title":"credentials","version":"1"},"paths":{},
                 "components":{"securitySchemes":{"Token":{"type":"apiKey","name":"Authorization",
                   "in":"header","x-amazon-apigateway-authorizer":{"type":"token",
                     "authorizerUri":"%s","authorizerCredentials":"%s"}}}}}
                """.formatted(URI_VALUE, ROLE);
        String api = service.importRestApi("us-east-1", spec).getId();
        try {
            assertEquals(ROLE, service.getAuthorizers("us-east-1", api).getFirst().getAuthorizerCredentials());
            String omitted = spec.replace(",\"authorizerCredentials\":\"" + ROLE + "\"", "");
            service.putRestApi("us-east-1", api, "merge", omitted);
            assertEquals(ROLE, service.getAuthorizers("us-east-1", api).getFirst().getAuthorizerCredentials());
            String invalid = spec.replace("\"" + ROLE + "\"", "42");
            assertThrows(AwsException.class, () -> service.putRestApi("us-east-1", api, "merge", invalid));
            assertEquals(ROLE, service.getAuthorizers("us-east-1", api).getFirst().getAuthorizerCredentials());
            service.putRestApi("us-east-1", api, "merge", spec.replace("\"" + ROLE + "\"", "null"));
            assertNull(service.getAuthorizers("us-east-1", api).getFirst().getAuthorizerCredentials());
        } finally {
            service.deleteRestApi("us-east-1", api);
        }
    }

    private PatchOperation patch(String operation, String path, String value) {
        return PatchOperation.builder().op(operation).path(path).value(value).build();
    }
}
