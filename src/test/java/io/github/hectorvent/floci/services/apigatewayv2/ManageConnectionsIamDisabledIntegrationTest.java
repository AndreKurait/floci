package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.services.apigatewayv2.websocket.ConnectionInfo;
import io.github.hectorvent.floci.services.apigatewayv2.websocket.WebSocketConnectionManager;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.http.ServerWebSocket;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;

import java.net.URI;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.*;

@QuarkusTest
class ManageConnectionsIamDisabledIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    WebSocketConnectionManager connections;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void unsignedConnectionManagementRemainsAvailableWithIamDisabled() {
        ApiGatewayV2Client api = ApiGatewayV2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
        cleanup.register(api::close);
        String id = api.createApi(request -> request.name("connections-unsigned-" + UUID.randomUUID())
                .protocolType("WEBSOCKET").routeSelectionExpression("$request.body.action")).apiId();
        cleanup.register(() -> api.deleteApi(request -> request.apiId(id)));
        api.createStage(request -> request.apiId(id).stageName("prod"));
        String connectionId = UUID.randomUUID().toString();
        ServerWebSocket socket = mock(ServerWebSocket.class);
        cleanup.register(() -> connections.unregister(connectionId));
        connections.register(connectionId, new ConnectionInfo(connectionId, id, "prod", "us-east-1",
                0, 0, "127.0.0.1", "fixture"), socket);
        String path = "/execute-api/" + id + "/prod/@connections/" + connectionId;
        given().get(path).then().statusCode(200);
        given().body("hello").post(path).then().statusCode(200);
        given().delete(path).then().statusCode(204);
        verify(socket).writeTextMessage("hello");
        verify(socket).close();
    }
}
