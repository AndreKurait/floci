package io.floci.aws.services.elbv2;

import io.floci.aws.services.elbv2.ElbV2VpcNetworkManager.BackendProxy;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.net.NetServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class ElbV2VpcProxyIntegrationTest extends ElbV2BackendConnectionTestSupport {

    @InjectMock
    ElbV2VpcNetworkManager networks;

    @BeforeEach
    void directBackendByDefault() {
        when(networks.proxy(anyString())).thenReturn(BackendProxy.DIRECT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingNamespaceCannotFallBackToTheDirectTarget(boolean upgrade) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startBackend(request -> {
            requests.incrementAndGet();
            request.response().end("direct target");
        });
        when(networks.proxy(anyString())).thenThrow(new IllegalStateException("ALB backend network is not ready"));

        assertEquals(503, given().baseUri("http://127.0.0.1").port(listenerPort)
                .header("Connection", upgrade ? "Upgrade" : "close")
                .header("Upgrade", upgrade ? "websocket" : "unused")
                .get("/unavailable").statusCode());
        assertEquals(0, requests.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableProxyCannotFallBackToTheDirectTarget(boolean upgrade) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startBackend(request -> {
            requests.incrementAndGet();
            request.response().end("direct target");
        });
        NetServer proxy = vertx.createNetServer().connectHandler(socket -> socket.close())
                .listen(ElbV2VpcNetworkManager.PROXY_PORT, "127.0.0.2")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        try {
            when(networks.proxy(anyString())).thenReturn(new BackendProxy("127.0.0.2"));
            assertEquals(502, given().baseUri("http://127.0.0.1").port(listenerPort)
                    .header("Connection", upgrade ? "Upgrade" : "close")
                    .header("Upgrade", upgrade ? "websocket" : "unused")
                    .get("/unavailable").statusCode());
            assertEquals(0, requests.get());
        } finally {
            proxy.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directBackendRetainsOrdinaryAndUpgradeRefusalResponses(boolean upgrade) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startBackend(request -> {
            requests.incrementAndGet();
            request.response().setStatusCode(409).end("target response");
        });

        assertEquals(409, given().baseUri("http://127.0.0.1").port(listenerPort)
                .header("Connection", upgrade ? "Upgrade" : "close")
                .header("Upgrade", upgrade ? "websocket" : "unused")
                .get("/direct").statusCode());
        assertEquals(1, requests.get());
    }
}
