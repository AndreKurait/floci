package io.floci.aws.services.ssm;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ssmmessages.DataChannelHandler;
import io.floci.aws.services.ssmmessages.DataChannelRouter;
import io.floci.aws.testing.ParallelLocks;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class SsmDataChannelRoutingTest {
    private final Vertx vertx = Vertx.vertx();
    private final HttpClient client = vertx.createHttpClient();
    private final SsmChannelAuthentication authentication = mock(SsmChannelAuthentication.class);
    private final SsmSessionService sessions = mock(SsmSessionService.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        when(config.services().ssm().enabled()).thenReturn(true);
        when(sessions.holds("ssm-session")).thenReturn(true);
        when(sessions.holds("duplicate-session")).thenReturn(true);
        SsmDataChannelController controller = new SsmDataChannelController(sessions,
                authentication, null, config, null);
        Router router = Router.router(vertx);
        new DataChannelRouter(List.of(controller, new RejectingHandler())).init(router);
        router.route().handler(context -> context.response().setStatusCode(204).end());
        port = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1")).actualPort();
    }

    @AfterEach
    void closeVertx() throws Exception {
        await(vertx.close());
    }

    @Test
    void ownedSsmSessionRetainsRoleValidation() {
        assertEquals(400, rejectedUpgradeStatus("ssm-session"));
    }

    @Test
    void ecsSessionReachesItsOwnHandler() {
        assertEquals(403, rejectedUpgradeStatus("ecs-session"));
    }

    @Test
    void unknownSessionReturnsNotFound() {
        assertEquals(404, rejectedUpgradeStatus("unknown-session"));
    }

    @Test
    void duplicateOwnershipIsRejectedBeforeEitherHandlerRuns() {
        assertEquals(500, rejectedUpgradeStatus("duplicate-session"));
    }

    @Test
    void disabledSsmDoesNotClaimSessions() {
        when(config.services().ssm().enabled()).thenReturn(false);
        assertEquals(404, rejectedUpgradeStatus("ssm-session"));
        assertEquals(403, rejectedUpgradeStatus("ecs-session"));
    }

    @Test
    void invalidSignedClientCannotUpgradeItsSession() {
        when(authentication.verify(anyString(), any(), any(), any(), any(), anyString(), anyBoolean()))
                .thenThrow(new AwsException("AccessDeniedException", "Invalid signature.", 403));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> await(client.webSocket(port, "127.0.0.1",
                        path("ssm-session") + "?role=publish_subscribe&X-Amz-Signature=invalid")));
        assertEquals(403, assertInstanceOf(UpgradeRejectedException.class, failure.getCause()).getStatus());
    }

    @Test
    void ordinaryHttpDoesNotEnterWebSocketHandler() throws Exception {
        HttpClientResponse response = await(client.request(HttpMethod.POST, port, "127.0.0.1",
                path("ssm-session")).compose(HttpClientRequest::send));
        assertEquals(204, response.statusCode());
    }

    private int rejectedUpgradeStatus(String sessionId) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> await(client.webSocket(port, "127.0.0.1", path(sessionId))));
        return assertInstanceOf(UpgradeRejectedException.class, failure.getCause()).getStatus();
    }

    private static String path(String sessionId) {
        return DataChannelRouter.CHANNEL_PATH_PREFIX + sessionId;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static class RejectingHandler implements DataChannelHandler {
        @Override
        public boolean supportsSession(String sessionId) {
            return "ecs-session".equals(sessionId) || "duplicate-session".equals(sessionId);
        }

        @Override
        public void open(String sessionId, RoutingContext context) {
            context.response().setStatusCode(403).end();
        }
    }
}
