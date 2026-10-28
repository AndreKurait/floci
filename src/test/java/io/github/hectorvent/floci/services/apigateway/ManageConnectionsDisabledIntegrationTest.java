package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;

import java.net.URI;
import java.util.UUID;

import static io.restassured.RestAssured.given;

@QuarkusTest
class ManageConnectionsDisabledIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void unsignedLocalRequestsReachGoneConnectionWhenEnforcementIsDisabled() {
        try (ApiGatewayV2Client api = ApiGatewayV2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build()) {
            String id = api.createApi(b -> b.name("connections-disabled-" + UUID.randomUUID())
                    .protocolType("WEBSOCKET").routeSelectionExpression("$request.body.action")).apiId();
            try {
                api.createStage(b -> b.apiId(id).stageName("prod"));
                String path = "/execute-api/" + id + "/prod/@connections/absent";
                given().get(path).then().statusCode(410);
                given().body("message").post(path).then().statusCode(410);
                given().delete(path).then().statusCode(410);
            } finally {
                api.deleteApi(b -> b.apiId(id));
            }
        }
    }
}
