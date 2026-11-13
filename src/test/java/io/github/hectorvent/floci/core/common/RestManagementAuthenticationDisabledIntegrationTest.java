package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.ValidateSignaturesProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.lambda.LambdaClient;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class RestManagementAuthenticationDisabledIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void anonymousLambdaSdkRemainsAvailableWhenIamEnforcementIsDisabled() {
        try (LambdaClient client = LambdaClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(AnonymousCredentialsProvider.create()).build()) {
            assertEquals(200, client.listLayers().sdkHttpResponse().statusCode());
        }
    }

    @Test
    void anonymousSdkManagementRemainsAvailableWhenIamEnforcementIsDisabled() {
        try (ApiGatewayClient client = ApiGatewayClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(AnonymousCredentialsProvider.create()).build()) {
            String id = client.createRestApi(request -> request.name("anonymous-management-" + UUID.randomUUID())).id();
            try {
                assertEquals(id, client.getRestApi(request -> request.restApiId(id)).id());
            } finally {
                client.deleteRestApi(request -> request.restApiId(id));
            }
        }
    }
}
