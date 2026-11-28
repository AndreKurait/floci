package io.floci.aws.services.ssmmessages;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.Node;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionRequest;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionState;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsmExternalControlTransportTest {
    private static final Node NODE = new Node("000000000000", "us-east-1", "i-0123456789abcdef0");
    private static final String SESSION = "operator-external";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Vertx vertx = Vertx.vertx();
    private final SsmMessageGateway gateway = new SsmMessageGateway(mapper, vertx, Clock.systemUTC());
    private final BlockingQueue<SessionState> states = new LinkedBlockingQueue<>();

    @AfterEach
    void close() throws Exception {
        gateway.shutdown();
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void dispatchesTheSessionOverTheAuthenticatedControlConnection() {
        UUID owner = UUID.randomUUID();
        List<Buffer> frames = new ArrayList<>();
        gateway.registerExternalControl(NODE, owner, frame -> {
            frames.add(frame);
            return Future.succeededFuture();
        });

        assertTrue(gateway.isConnected(NODE));
        gateway.startSession(request(), states::add);

        assertEquals(1, frames.size());
        assertEquals("interactive_shell", AgentMessage.decode(frames.getFirst().getBytes()).messageType());
        assertTrue(gateway.sessionTarget(SESSION).isPresent());
        gateway.unregisterExternalControl(NODE, owner);
        assertFalse(gateway.isConnected(NODE));
    }

    @Test
    void aRetiredConnectionCannotRemoveOrCompleteItsReplacementSession() throws Exception {
        UUID retired = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        gateway.registerExternalControl(NODE, retired, frame -> Future.succeededFuture());
        gateway.registerExternalControl(NODE, replacement, frame -> Future.succeededFuture());
        gateway.startSession(request(), states::add);

        gateway.unregisterExternalControl(NODE, retired);
        assertTrue(gateway.isConnected(NODE));
        assertFalse(gateway.receiveExternalControl(NODE, retired, completed()));
        assertTrue(gateway.sessionTarget(SESSION).isPresent());

        assertTrue(gateway.receiveExternalControl(NODE, replacement, completed()));
        assertEquals(SessionState.TERMINATED, states.poll(5, TimeUnit.SECONDS));
        assertTrue(gateway.sessionTarget(SESSION).isEmpty());
    }

    @Test
    void anotherAccountCannotCompleteTheSession() throws Exception {
        UUID owner = UUID.randomUUID();
        Node other = new Node("111111111111", NODE.region(), NODE.instanceId());
        gateway.registerExternalControl(NODE, owner, frame -> Future.succeededFuture());
        gateway.registerExternalControl(other, owner, frame -> Future.succeededFuture());
        gateway.startSession(request(), states::add);

        assertThrows(IllegalArgumentException.class,
                () -> gateway.receiveExternalControl(other, owner, completed()));
        assertTrue(gateway.sessionTarget(SESSION).isPresent());
        assertTrue(states.isEmpty());
    }

    @Test
    void unownedCompletionsRemainAvailableToTheOtherSessionBackend() throws Exception {
        UUID owner = UUID.randomUUID();
        gateway.registerExternalControl(NODE, owner, frame -> Future.succeededFuture());

        assertFalse(gateway.receiveExternalControl(NODE, owner, completed()));
    }

    @Test
    void resetRemovesTheConnectionAndRejectsRegistrationUntilFinished() {
        UUID owner = UUID.randomUUID();
        gateway.registerExternalControl(NODE, owner, frame -> Future.succeededFuture());
        gateway.beforeReset();
        gateway.clear();

        assertFalse(gateway.isConnected(NODE));
        assertThrows(AwsException.class,
                () -> gateway.registerExternalControl(NODE, owner, frame -> Future.succeededFuture()));
        gateway.afterReset();
        assertFalse(gateway.isConnected(NODE));
    }

    @Test
    void failedDispatchDoesNotLeaveALiveSession() {
        gateway.registerExternalControl(NODE, UUID.randomUUID(),
                frame -> Future.failedFuture(new IllegalStateException("closed transport")));

        assertThrows(AwsException.class, () -> gateway.startSession(request(), states::add));
        assertTrue(gateway.sessionTarget(SESSION).isEmpty());
    }

    private SessionRequest request() {
        return new SessionRequest(SESSION, "arn:aws:ssm:us-east-1:000000000000:session/" + SESSION,
                "arn:aws:iam::000000000000:user/operator", NODE, "SSM-SessionManagerRunShell",
                mapper.createObjectNode(), Map.of());
    }

    private Buffer completed() throws Exception {
        return Buffer.buffer(AgentMessage.outbound("agent_task_complete", 0, 0,
                mapper.writeValueAsBytes(Map.of("TaskId", SESSION, "Topic", "StartSession",
                        "FinalTaskStatus", "Success"))).encode());
    }
}
