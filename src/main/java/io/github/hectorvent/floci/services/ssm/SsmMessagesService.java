package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.ssm.SsmChannelAuthentication.Caller;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Ephemeral authenticated channels deliver the persisted Run Command invocations. */
@ApplicationScoped
public class SsmMessagesService implements Resettable {
    private static final Logger LOG = Logger.getLogger(SsmMessagesService.class);
    private static final long TOKEN_LIFETIME_SECONDS = 60;
    private static final long REDELIVERY_MILLIS = 5000;

    private final SsmCommandService commands;
    private final SsmChannelAuthentication authentication;
    private final ObjectMapper mapper;
    private final Vertx vertx;
    private final Clock clock;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Map<Owner, Channel> channels = new ConcurrentHashMap<>();
    private final Set<Channel> sockets = ConcurrentHashMap.newKeySet();

    @Inject
    public SsmMessagesService(SsmCommandService commands, SsmChannelAuthentication authentication,
                              ObjectMapper mapper, Vertx vertx, Clock clock) {
        this.commands = commands;
        this.authentication = authentication;
        this.mapper = mapper;
        this.vertx = vertx;
        this.clock = clock;
    }

    String create(Caller caller, String instanceId, JsonNode request) {
        validateInstanceId(instanceId);
        validateSchema(request);
        authentication.authorizeNode(caller, instanceId);
        authentication.authorize(caller, "CreateControlChannel");
        Instant now = clock.instant();
        tokens.entrySet().removeIf(entry -> !entry.getValue().expires().isAfter(now));
        if (tokens.size() >= 10_000) {
            throw new AwsException("ThrottlingException", "Too many pending control channels.", 429);
        }
        String value = UUID.randomUUID().toString() + UUID.randomUUID();
        tokens.put(value, new Token(owner(caller, instanceId), caller.arn(),
                now.plusSeconds(TOKEN_LIFETIME_SECONDS)));
        return value;
    }

    void consumeToken(Caller caller, String instanceId, String value) {
        Token token = tokens.get(value);
        if (token == null || !token.owner().equals(owner(caller, instanceId))
                || !token.callerArn().equals(caller.arn()) || !token.expires().isAfter(clock.instant())) {
            throw SsmChannelAuthentication.denied("Invalid control-channel token.");
        }
        authentication.authorize(caller, "OpenControlChannel");
        if (!tokens.remove(value, token)) {
            throw SsmChannelAuthentication.denied("The control-channel token was already consumed.");
        }
    }

    void open(Caller caller, String instanceId, ServerWebSocket socket) {
        validateInstanceId(instanceId);
        Channel channel = new Channel(caller, instanceId, socket);
        sockets.add(channel);
        channel.start();
    }

    @Override
    @PreDestroy
    public void clear() {
        tokens.clear();
        List.copyOf(sockets).forEach(channel -> channel.close((short) 1001, "The service is stopping."));
        channels.clear();
    }

    static void validateInstanceId(String instanceId) {
        if (instanceId == null || !instanceId.matches("(?:i-(?:[0-9a-f]{8}|[0-9a-f]{17})|mi-[0-9a-f]{17})")) {
            throw new AwsException("ValidationException", "Invalid managed-node identifier.", 400);
        }
    }

    private static void validateSchema(JsonNode request) {
        if (request == null || !"1.0".equals(request.path("MessageSchemaVersion").asText())
                || !request.path("RequestId").isTextual()
                || request.path("RequestId").asText().length() < 16) {
            throw new AwsException("ValidationException", "Invalid control-channel request.", 400);
        }
    }

    private static Owner owner(Caller caller, String instanceId) {
        return new Owner(caller.account(), caller.region(), instanceId);
    }

    private record Owner(String account, String region, String instanceId) {}
    private record Token(Owner owner, String callerArn, Instant expires) {}
    private record Delivery(SsmAgentMessage message, long sentAt) {}

    private final class Channel {
        private final UUID id = UUID.randomUUID();
        private final Caller caller;
        private final Owner owner;
        private final ServerWebSocket socket;
        private final Context context;
        private final Map<String, Delivery> delivered = new HashMap<>();
        private boolean authenticated;
        private boolean polling;
        private boolean closed;
        private long timer = -1;
        private long handshakeTimer = -1;

        private Channel(Caller caller, String instanceId, ServerWebSocket socket) {
            this.caller = caller;
            this.owner = owner(caller, instanceId);
            this.socket = socket;
            this.context = Vertx.currentContext();
        }

        private void start() {
            handshakeTimer = vertx.setTimer(TOKEN_LIFETIME_SECONDS * 1000,
                    ignored -> close((short) 1008, "The channel handshake expired."));
            socket.textMessageHandler(this::handshake);
            socket.binaryMessageHandler(this::receive);
            socket.closeHandler(ignored -> retired());
            socket.exceptionHandler(error -> {
                LOG.debugv(error, "SSM control channel failed for node {0}", owner.instanceId());
                close((short) 1011, "The channel failed.");
            });
        }

        private void handshake(String text) {
            try {
                if (authenticated || text.length() > 4096) {
                    throw SsmChannelAuthentication.denied("Invalid channel handshake.");
                }
                JsonNode request = mapper.readTree(text);
                validateSchema(request);
                consumeToken(caller, owner.instanceId(), request.path("TokenValue").asText(""));
                authenticated = true;
                vertx.cancelTimer(handshakeTimer);
                Channel previous;
                synchronized (channels) {
                    RequestScopes.runAs(owner.account(), owner.region(), () ->
                            commands.messageGatewayOpened(owner.instanceId(), owner.region(), id));
                    previous = channels.put(owner, this);
                }
                if (previous != null) {
                    previous.close((short) 1000, "The node opened a replacement control channel.");
                }
                timer = vertx.setPeriodic(250, ignored -> poll());
                LOG.infov("SSM control channel opened for account={0} region={1} node={2}",
                        owner.account(), owner.region(), owner.instanceId());
                poll();
            } catch (Exception invalid) {
                LOG.debugv(invalid, "SSM control-channel handshake rejected for node {0}", owner.instanceId());
                close((short) 1008, "The channel handshake was rejected.");
            }
        }

        private boolean active() {
            return authenticated && !closed && channels.get(owner) == this;
        }

        private void poll() {
            if (!active() || polling) {
                return;
            }
            polling = true;
            vertx.executeBlocking(() -> RequestScopes.callAs(owner.account(), owner.region(),
                    () -> commands.pendingAgentCommands(owner.instanceId(), owner.region())))
                    .onSuccess(pending -> {
                        try {
                            if (!active()) {
                                return;
                            }
                            if (!pending.isEmpty()) {
                                authentication.authorizeNode(caller, owner.instanceId());
                                authentication.authorize(caller, "OpenControlChannel");
                            }
                            for (SsmCommandService.AgentCommand command : pending) {
                                Delivery previous = delivered.get(command.jobId());
                                if (previous != null && clock.millis() - previous.sentAt() < REDELIVERY_MILLIS) {
                                    continue;
                                }
                                RequestScopes.runAs(owner.account(), owner.region(), () ->
                                        commands.deliverAgentCommand(owner.instanceId(), command.jobId(), owner.region(),
                                                current -> {
                                                    ObjectNode job = mapper.createObjectNode()
                                                            .put("Content", current.content()).put("JobId", current.jobId())
                                                            .put("Topic", current.topic()).put("SchemaVersion", 1);
                                                    SsmAgentMessage message = previous == null
                                                            ? message("agent_job", job) : previous.message();
                                                    delivered.put(current.jobId(), new Delivery(message, clock.millis()));
                                                    send(message);
                                                }));
                            }
                            Set<String> pendingIds = pending.stream().map(SsmCommandService.AgentCommand::jobId)
                                    .collect(Collectors.toSet());
                            delivered.keySet().retainAll(pendingIds);
                        } catch (Exception error) {
                            LOG.debugv(error, "SSM command delivery rejected for node {0}", owner.instanceId());
                            close((short) 1008, "Command delivery was rejected.");
                        }
                    })
                    .onFailure(error -> {
                        LOG.debugv(error, "SSM command delivery failed for node {0}", owner.instanceId());
                        close((short) 1011, "Command delivery failed.");
                    })
                    .onComplete(ignored -> polling = false);
        }

        private void receive(Buffer bytes) {
            if (!active()) {
                close((short) 1008, "Authenticate the control channel first.");
                return;
            }
            try {
                SsmAgentMessage message = SsmAgentMessage.decode(bytes.getBytes());
                if (!"agent_job_ack".equals(message.type()) && !"agent_job_reply".equals(message.type())) {
                    LOG.debugv("Ignoring unsupported SSM control-channel message type {0}", message.type());
                    return;
                }
                JsonNode payload = mapper.readTree(message.payload());
                if ("agent_job_ack".equals(message.type())) {
                    String jobId = payload.path("jobId").asText();
                    Delivery delivery = delivered.get(jobId);
                    if (delivery == null) {
                        return;
                    }
                    if (!delivery.message().id().toString()
                            .equals(payload.path("acknowledgedMessageId").asText())) {
                        throw new IllegalArgumentException("The acknowledgement does not identify a delivered job.");
                    }
                    if (!"200".equals(payload.path("statusCode").asText())) {
                        LOG.warnv("SSM agent rejected job {0}: {1}", jobId, payload.path("errorMessage").asText());
                        return;
                    }
                    RequestScopes.runAs(owner.account(), owner.region(),
                            () -> commands.acknowledgeAgentCommand(owner.instanceId(), jobId, owner.region()));
                    delivered.remove(jobId);
                } else if ("agent_job_reply".equals(message.type())) {
                    String jobId = payload.path("jobId").asText();
                    JsonNode reply = mapper.readTree(payload.path("content").asText());
                    RequestScopes.runAs(owner.account(), owner.region(),
                            () -> commands.agentReply(owner.instanceId(), jobId, reply, owner.region()));
                    send(message("agent_job_reply_ack", mapper.createObjectNode()
                            .put("jobId", jobId).put("acknowledgedMessageId", message.id().toString())));
                }
            } catch (Exception invalid) {
                LOG.debugv(invalid, "Invalid SSM control-channel message for node {0}", owner.instanceId());
                close((short) 1002, "Invalid control-channel message.");
            }
        }

        private void send(SsmAgentMessage message) {
            socket.writeBinaryMessage(Buffer.buffer(message.encode())).onFailure(error -> {
                LOG.debugv(error, "SSM control-channel write failed for node {0}", owner.instanceId());
                close((short) 1011, "The channel write failed.");
            });
        }

        private void close(short code, String reason) {
            if (Vertx.currentContext() != context) {
                context.runOnContext(ignored -> close(code, reason));
                return;
            }
            if (!closed) {
                retired();
                socket.close(code, reason);
            }
        }

        private void retired() {
            closed = true;
            if (timer >= 0) {
                vertx.cancelTimer(timer);
            }
            if (handshakeTimer >= 0) {
                vertx.cancelTimer(handshakeTimer);
            }
            synchronized (channels) {
                channels.remove(owner, this);
                RequestScopes.runAs(owner.account(), owner.region(), () ->
                        commands.messageGatewayClosed(owner.instanceId(), owner.region(), id));
            }
            sockets.remove(this);
            delivered.clear();
        }
    }

    private SsmAgentMessage message(String type, JsonNode payload) {
        try {
            return SsmAgentMessage.create(type, mapper.writeValueAsBytes(payload));
        } catch (Exception invalid) {
            throw new IllegalStateException("Could not encode an SSM control-channel message.", invalid);
        }
    }
}
