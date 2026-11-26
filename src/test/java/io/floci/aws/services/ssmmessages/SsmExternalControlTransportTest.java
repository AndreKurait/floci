package io.floci.aws.services.ssmmessages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.ControlTransport;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SsmExternalControlTransportTest {
    private final Vertx vertx = Vertx.vertx();
    private final ObjectMapper mapper = new ObjectMapper();
    private final SsmMessageGateway gateway = new SsmMessageGateway(mapper, vertx, Clock.systemUTC());
    private final Node node = new Node("123456789012", "us-east-1", "mi-0123456789abcdef0");
    private final UUID connection = UUID.randomUUID();
    private final RecordingTransport transport = new RecordingTransport();

    @AfterEach
    void close() throws Exception {
        gateway.clear();
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void authenticatedExternalTransportReceivesSessionDispatch() throws Exception {
        assertTrue(gateway.attachControl(node, connection, transport, gateway.generation()));
        assertTrue(gateway.isConnected(node));

        String token = gateway.startSession(request("session-one"), ignored -> {});

        assertFalse(token.isBlank());
        assertEquals(1, transport.messages.size());
        AgentMessage frame = AgentMessage.decode(transport.messages.getFirst().getBytes());
        assertEquals("interactive_shell", frame.messageType());
        JsonNode payload = mapper.readTree(frame.payloadAsString());
        assertEquals("session-one", payload.path("TaskId").asText());
        assertEquals("session-one", mapper.readTree(payload.path("Content").asText()).path("SessionId").asText());
    }

    @Test
    void staleDetachCannotRemoveTheReplacementConnection() throws Exception {
        gateway.attachControl(node, connection, transport, gateway.generation());
        RecordingTransport replacement = new RecordingTransport();
        UUID next = UUID.randomUUID();
        gateway.attachControl(node, next, replacement, gateway.generation());
        gateway.detachControl(node, connection);

        assertTrue(gateway.isConnected(node));
        gateway.startSession(request("replacement-session"), ignored -> {});
        assertEquals(1, replacement.messages.size());
        transport.closed.get(10, TimeUnit.SECONDS);
        assertTrue(transport.messages.isEmpty());
        gateway.detachControl(node, next);
        assertFalse(gateway.isConnected(node));
    }

    @Test
    void aClosedOrStaleGenerationTransportCannotAttach() throws Exception {
        long generation = gateway.generation();
        gateway.clear();
        assertFalse(gateway.attachControl(node, connection, transport, generation));
        transport.closed.get(10, TimeUnit.SECONDS);
        assertFalse(gateway.isConnected(node));
        RecordingTransport closed = new RecordingTransport();
        closed.open = false;
        assertFalse(gateway.attachControl(node, UUID.randomUUID(), closed, gateway.generation()));
        assertThrows(AwsException.class, () -> gateway.startSession(request("unconnected"), ignored -> {}));
    }

    @Test
    void completionIsAcknowledgedAndDeliveredToTheSessionOwner() throws Exception {
        CompletableFuture<SessionState> state = new CompletableFuture<>();
        gateway.attachControl(node, connection, transport, gateway.generation());
        gateway.startSession(request("session-one"), state::complete);

        assertTrue(gateway.receiveControl(node, connection, completion("session-one")));

        assertEquals(SessionState.TERMINATED, state.get(10, TimeUnit.SECONDS));
        assertEquals("agent_task_acknowledge", AgentMessage.decode(transport.messages.getLast().getBytes()).messageType());
        assertEquals(2, transport.messages.size());
    }

    @Test
    void anotherNodeCannotCompleteTheSession() {
        gateway.attachControl(node, connection, transport, gateway.generation());
        gateway.startSession(request("session-one"), ignored -> {});
        Node other = new Node(node.accountId(), node.region(), "mi-fffffffffffffffff");
        RecordingTransport foreign = new RecordingTransport();
        UUID otherConnection = UUID.randomUUID();
        gateway.attachControl(other, otherConnection, foreign, gateway.generation());

        assertThrows(IllegalArgumentException.class,
                () -> gateway.receiveControl(other, otherConnection, completion("session-one")));
        assertTrue(foreign.messages.isEmpty());
        assertTrue(gateway.sessionTarget("session-one").isPresent());
    }

    @Test
    void nonSessionMessagesStayWithTheSocketOwner() {
        gateway.attachControl(node, connection, transport, gateway.generation());
        assertFalse(gateway.receiveControl(node, connection, frame("agent_job_reply", Map.of("jobId", "command"))));
        assertFalse(gateway.receiveControl(node, connection, completion("legacy-session")));
        assertTrue(transport.messages.isEmpty());
    }

    @Test
    void failedWritesDoNotLeaveARegisteredSession() {
        transport.failWrites = true;
        gateway.attachControl(node, connection, transport, gateway.generation());
        assertThrows(AwsException.class, () -> gateway.startSession(request("failed-session"), ignored -> {}));
        assertTrue(gateway.sessionTarget("failed-session").isEmpty());
    }

    @Test
    void resetRetiresTheTransportAndRejectsItsOldMessages() throws Exception {
        gateway.attachControl(node, connection, transport, gateway.generation());
        gateway.startSession(request("session-one"), ignored -> {});
        gateway.clear();
        transport.closed.get(10, TimeUnit.SECONDS);
        assertFalse(gateway.isConnected(node));
        assertFalse(gateway.receiveControl(node, connection, completion("session-one")));
        assertTrue(gateway.sessionTarget("session-one").isEmpty());
    }

    private SessionRequest request(String id) {
        return new SessionRequest(id, "arn:aws:ssm:us-east-1:123456789012:session/" + id,
                "arn:aws:iam::123456789012:user/operator", node, "SSM-SessionManagerRunShell",
                mapper.createObjectNode(), Map.of());
    }

    private Buffer completion(String id) {
        return frame("agent_task_complete", Map.of("TaskId", id, "Topic", "StartSession", "FinalTaskStatus", "Success"));
    }

    private Buffer frame(String type, Object payload) {
        try {
            return Buffer.buffer(new AgentMessage(type, 1, 0, 0, 3, UUID.randomUUID(), 0,
                    mapper.writeValueAsBytes(payload)).encode());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static class RecordingTransport implements ControlTransport {
        private final List<Buffer> messages = new ArrayList<>();
        private final CompletableFuture<Void> closed = new CompletableFuture<>();
        private volatile boolean open = true;
        private boolean failWrites;

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public Future<Void> send(Buffer frame) {
            if (failWrites) {
                return Future.failedFuture("Injected write failure");
            }
            messages.add(frame);
            return Future.succeededFuture();
        }

        @Override
        public void close() {
            open = false;
            closed.complete(null);
        }
    }
}
