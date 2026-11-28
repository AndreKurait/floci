package io.floci.aws.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsEndpoints;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.EpochSeconds;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.RequestHost;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.ssm.SsmChannelAuthentication.Caller;
import io.floci.aws.services.ssm.session.SsmSession;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Session Manager: the public StartSession, ResumeSession, TerminateSession, DescribeSessions and
 * GetConnectionStatus operations, the session start delivered to the agent over its control channel, and
 * the ssmmessages data channels of the agent and the client.
 *
 * <p>The service relays the agent's and the client's binary messages to each other unchanged: the handshake,
 * acknowledgements and sequence numbers are end to end between them. It reads only the agent's
 * {@code agent_session_state}, which is addressed to the service, and itself sends
 * {@code pause_publication} while no client is attached, {@code start_publication} once one is, and
 * {@code channel_closed} when the session ends.
 */
@ApplicationScoped
public class SsmSessionService implements Resettable {
    private static final Logger LOG = Logger.getLogger(SsmSessionService.class);
    static final long TOKEN_LIFETIME_SECONDS = 60;
    static final String SESSION_ALREADY_TERMINATED = "Session is already terminated";
    private static final int MAX_FRAME_BYTES = SsmAgentMessage.MAX_MESSAGE_BYTES;
    private static final int MAX_BUFFERED_FRAMES = 256;
    private static final int MAX_BUFFERED_BYTES = 4 * 1024 * 1024;
    private static final int DEFAULT_PAGE = 200;
    private static final Pattern TARGET = Pattern.compile("(?:i-(?:[0-9a-f]{8}|[0-9a-f]{17})|mi-[0-9a-f]{17})");
    private static final Pattern DOCUMENT_NAME = Pattern.compile("[a-zA-Z0-9_\\-.:/]{3,128}");
    private static final Set<String> ACTIVE = Set.of("Connected", "Connecting", "Disconnected", "Terminating");
    private static final Set<String> FILTER_KEYS =
            Set.of("InvokedAfter", "InvokedBefore", "Target", "Owner", "Status", "SessionId", "AccessType");

    private final StorageBackend<String, SsmSession> store;
    private final SsmService documents;
    private final SsmCommandService commands;
    private final SsmChannelAuthentication authentication;
    private final RegionResolver regions;
    private final IamService iam;
    private final RequestContext requestContext;
    private final CurrentVertxRequest requests;
    private final ObjectMapper mapper;
    private final Vertx vertx;
    private final Clock clock;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Map<String, Live> live = new ConcurrentHashMap<>();
    private final Map<UUID, Notice> notices = new ConcurrentHashMap<>();

    @Inject
    public SsmSessionService(StorageFactory storageFactory, SsmService documents, SsmCommandService commands,
                             SsmChannelAuthentication authentication, RegionResolver regions, IamService iam,
                             RequestContext requestContext, CurrentVertxRequest requests, ObjectMapper mapper,
                             Vertx vertx, Clock clock) {
        this.store = storageFactory.create("ssm", "ssm-sessions.json", new TypeReference<>() {});
        this.documents = documents;
        this.commands = commands;
        this.authentication = authentication;
        this.regions = regions;
        this.iam = iam;
        this.requestContext = requestContext;
        this.requests = requests;
        this.mapper = mapper;
        this.vertx = vertx;
        this.clock = clock;
    }

    boolean ownsConnectedTarget(String region, String target) {
        return TARGET.matcher(target).matches() && commands.isInstanceRegistered(target, region)
                && commands.messageGatewayConnected(target, region);
    }

    // ── Public API ──────────────────────────────────────────────────────────

    ObjectNode start(JsonNode request, String region) {
        String target = request.path("Target").asText("");
        if (target.isEmpty() || target.length() > 400) {
            throw new AwsException("ValidationException", "Target must be between 1 and 400 characters.", 400);
        }
        String documentName = documentName(request.path("DocumentName").asText(""));
        String reason = request.hasNonNull("Reason") ? request.path("Reason").asText() : null;
        if (reason != null && (reason.isEmpty() || reason.length() > 256 || reason.contains("\n"))) {
            throw new AwsException("ValidationException", "Reason must be between 1 and 256 characters.", 400);
        }
        Map<String, List<String>> values = parameterValues(request.path("Parameters"));
        String account = regions.getAccountId();
        if (!TARGET.matcher(target).matches() || !commands.isInstanceRegistered(target, region)
                || !commands.messageGatewayConnected(target, region)) {
            throw new AwsException("TargetNotConnected", target + " is not connected.", 400);
        }
        SsmSessionDocument document = SsmSessionDocument.resolve(documents, mapper, documentName, region);
        ObjectNode parameters = document.parameters(mapper, values);
        String owner = callerArn(account);
        String sessionId = sessionId(owner);

        SsmSession session = new SsmSession();
        session.setSessionId(sessionId);
        session.setAccountId(account);
        session.setRegion(region);
        session.setTarget(target);
        session.setStatus("Connecting");
        session.setStartDate(clock.instant());
        session.setDocumentName(document.name());
        session.setOwner(owner);
        session.setReason(reason);
        String maxDuration = document.content().path("inputs").path("maxSessionDuration").asText("");
        session.setMaxSessionDuration(maxDuration.isEmpty() ? null : maxDuration);
        store.put(key(region, sessionId), session);

        JsonNode inputs = document.content().path("inputs");
        ObjectNode task = mapper.createObjectNode()
                .put("DocumentName", document.name())
                .put("SessionId", sessionId)
                .put("RunAsUser", inputs.path("runAsEnabled").asBoolean(false)
                        ? inputs.path("runAsDefaultUser").asText("") : "")
                .put("SessionOwner", owner);
        task.set("DocumentContent", document.content());
        task.set("Parameters", parameters);
        ObjectNode start = mapper.createObjectNode().put("SchemaVersion", 1).put("TaskId", sessionId);
        start.put("Content", write(task));
        Live state = new Live(account, region, sessionId, target);
        state.control.add(new ControlMessage(UUID.randomUUID(), "interactive_shell", bytes(start), true));
        live.put(liveKey(account, region, sessionId), state);
        LOG.infov("SSM session {0} started for node {1} with {2}", sessionId, target, document.name());
        return connection(state);
    }

    ObjectNode resume(JsonNode request, String region) {
        String sessionId = request.path("SessionId").asText("");
        String account = regions.getAccountId();
        Live state = live.get(liveKey(account, region, sessionId));
        SsmSession session = sessionId.isEmpty() ? null : store.get(key(region, sessionId)).orElse(null);
        if (state == null || session == null || !ACTIVE.contains(session.getStatus())) {
            throw new AwsException("DoesNotExistException", "Session " + sessionId + " does not exist.", 400);
        }
        return connection(state);
    }

    ObjectNode terminate(JsonNode request, String region) {
        String sessionId = request.path("SessionId").asText("");
        if (sessionId.isEmpty() || sessionId.length() > 96) {
            throw new AwsException("ValidationException", "SessionId must be between 1 and 96 characters.", 400);
        }
        String account = regions.getAccountId();
        end(account, region, sessionId, true, "");
        return mapper.createObjectNode().put("SessionId", sessionId);
    }

    ObjectNode describe(JsonNode request, String region) {
        String state = request.path("State").asText("");
        if (!"Active".equals(state) && !"History".equals(state)) {
            throw new AwsException("ValidationException", "State must be Active or History.", 400);
        }
        Predicate<SsmSession> filter = filters(request.path("Filters"));
        Integer maxResults = request.hasNonNull("MaxResults") ? request.path("MaxResults").asInt() : null;
        if (maxResults != null && (maxResults < 1 || maxResults > 200)) {
            throw new AwsException("ValidationException", "MaxResults must be between 1 and 200.", 400);
        }
        String prefix = region + "::";
        List<SsmSession> sessions = new ArrayList<>(store.scan(k -> k.startsWith(prefix)).stream()
                .filter(s -> ACTIVE.contains(s.getStatus()) == "Active".equals(state))
                .filter(filter)
                .toList());
        sessions.sort(Comparator.comparing(SsmSession::getStartDate).reversed()
                .thenComparing(SsmSession::getSessionId));
        PaginatedResult<SsmSession> page = Pagination.paginate(sessions, SsmSession::getSessionId,
                maxResults != null ? maxResults : DEFAULT_PAGE, request.path("NextToken").asText(null),
                "describe-sessions:" + regions.getAccountId() + ":" + region + ":" + state,
                token -> new AwsException("InvalidNextToken", "The specified token isn't valid.", 400));
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("Sessions");
        for (SsmSession session : page.items()) {
            ObjectNode node = list.addObject()
                    .put("SessionId", session.getSessionId())
                    .put("Target", session.getTarget())
                    .put("Status", session.getStatus())
                    .put("StartDate", EpochSeconds.fromInstant(session.getStartDate()))
                    .put("DocumentName", session.getDocumentName())
                    .put("Owner", session.getOwner())
                    .put("AccessType", "Standard");
            if (session.getEndDate() != null) {
                node.put("EndDate", EpochSeconds.fromInstant(session.getEndDate()));
            }
            if (session.getReason() != null) {
                node.put("Reason", session.getReason());
            }
            if (session.getMaxSessionDuration() != null) {
                node.put("MaxSessionDuration", session.getMaxSessionDuration());
            }
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    ObjectNode connectionStatus(JsonNode request, String region) {
        String target = request.path("Target").asText("");
        if (target.isEmpty() || target.length() > 400) {
            throw new AwsException("ValidationException", "Target must be between 1 and 400 characters.", 400);
        }
        boolean connected = TARGET.matcher(target).matches() && commands.isInstanceRegistered(target, region)
                && commands.messageGatewayConnected(target, region);
        return mapper.createObjectNode().put("Target", target).put("Status", connected ? "connected" : "notconnected");
    }

    // ── Control channel ─────────────────────────────────────────────────────

    /** Messages for this node not yet sent on {@code channel}; a replacement channel receives them again. */
    List<ControlMessage> pendingControlMessages(String account, String region, String instanceId, UUID channel) {
        List<ControlMessage> pending = new ArrayList<>();
        for (Live state : live.values()) {
            if (!state.account.equals(account) || !state.region.equals(region) || !state.target.equals(instanceId)) {
                continue;
            }
            synchronized (state) {
                for (ControlMessage message : state.control) {
                    if (!channel.equals(message.deliveredOn)) {
                        pending.add(message);
                    }
                }
            }
        }
        for (Notice notice : notices.values()) {
            if (notice.account().equals(account) && notice.region().equals(region)
                    && notice.target().equals(instanceId)) {
                pending.add(notice.message());
            }
        }
        return pending;
    }

    /** A session start is sent again on a replacement channel; a termination notice is sent once. */
    void controlDelivered(ControlMessage message, UUID channel) {
        message.deliveredOn = channel;
        if (!message.untilDataChannel) {
            notices.remove(message.id);
        }
    }

    /** The agent reports the session finished; the client is told and the session ends. */
    void agentTaskComplete(String account, String region, String instanceId, JsonNode payload) {
        String sessionId = payload.path("TaskId").asText("");
        Live state = live.get(liveKey(account, region, sessionId));
        if (state == null || !state.target.equals(instanceId)) {
            return;
        }
        String output = payload.path("Output").asText("");
        end(account, region, sessionId, false, output.length() > 2048 ? output.substring(0, 2048) : output);
    }

    boolean ownsLiveSession(String region, String sessionId) {
        return live.containsKey(liveKey(regions.getAccountId(), region, sessionId));
    }

    // ── Data channel ────────────────────────────────────────────────────────

    /** Whether a session with this ID is open in any account; its data-channel upgrades are this service's. */
    boolean holds(String sessionId) {
        return live.values().stream().anyMatch(state -> state.sessionId.equals(sessionId));
    }

    /** CreateDataChannel: the agent's one-use token for the session's data channel. */
    String createDataChannel(Caller caller, String sessionId, JsonNode request) {
        if (request == null || !"1.0".equals(request.path("MessageSchemaVersion").asText())
                || request.path("RequestId").asText("").length() < 16
                || request.path("ClientId").asText("").isEmpty()) {
            throw new AwsException("ValidationException", "Invalid data-channel request.", 400);
        }
        Live state = live.get(liveKey(caller.account(), caller.region(), sessionId));
        if (state == null || state.ended) {
            throw new AwsException("ValidationException", SESSION_ALREADY_TERMINATED, 400);
        }
        authentication.authorizeNode(caller, state.target);
        if (!RequestScopes.callAs(caller.account(), caller.region(),
                () -> commands.isInstanceRegistered(state.target, caller.region()))) {
            throw SsmChannelAuthentication.denied("The managed node is not registered.");
        }
        synchronized (state) {
            state.control.removeIf(message -> message.untilDataChannel);
        }
        return issue(state, Role.AGENT, caller.arn());
    }

    /** A data-channel WebSocket; it is attached once its first text frame presents a valid token. */
    void openDataChannel(Caller caller, String sessionId, ServerWebSocket socket) {
        Side side = new Side(socket, Vertx.currentContext());
        long timer = vertx.setTimer(TOKEN_LIFETIME_SECONDS * 1000,
                ignored -> side.close((short) 1008, "The data-channel handshake expired."));
        socket.textMessageHandler(text -> {
            if (side.attached != null || text.length() > 4096) {
                return;
            }
            vertx.cancelTimer(timer);
            try {
                attach(caller, sessionId, side, mapper.readTree(text));
            } catch (Exception rejected) {
                LOG.debugv(rejected, "SSM data-channel handshake rejected for session {0}", sessionId);
                side.close((short) 1008, "The data-channel handshake was rejected.");
            }
        });
        socket.binaryMessageHandler(bytes -> receive(side, bytes.getBytes()));
        socket.closeHandler(ignored -> {
            vertx.cancelTimer(timer);
            detach(side);
        });
        socket.exceptionHandler(error -> side.close((short) 1011, "The data channel failed."));
    }

    private void attach(Caller caller, String sessionId, Side side, JsonNode open) {
        if (!"1.0".equals(open.path("MessageSchemaVersion").asText())
                || open.path("RequestId").asText("").length() < 16) {
            throw SsmChannelAuthentication.denied("Invalid data-channel handshake.");
        }
        Token token = tokens.remove(open.path("TokenValue").asText(""));
        if (token == null || !token.expires().isAfter(clock.instant())) {
            throw SsmChannelAuthentication.denied("Invalid data-channel token.");
        }
        Live state = live.get(token.liveKey());
        if (state == null || state.ended || !state.sessionId.equals(sessionId)) {
            throw SsmChannelAuthentication.denied("Invalid data-channel token.");
        }
        String sessionArn = sessionArn(state);
        if (token.role() == Role.AGENT) {
            if (caller == null || !caller.account().equals(state.account) || !caller.region().equals(state.region)
                    || !caller.arn().equals(token.callerArn())
                    || (open.hasNonNull("ClientInstanceId")
                    && !state.target.equals(open.path("ClientInstanceId").asText()))) {
                throw SsmChannelAuthentication.denied("The token belongs to a different caller.");
            }
            authentication.authorizeNode(caller, state.target);
            authentication.authorizeResource(caller, "OpenDataChannel", sessionArn);
        } else if (caller != null) {
            if (!caller.account().equals(state.account)) {
                throw SsmChannelAuthentication.denied("The token belongs to a different account.");
            }
            authentication.authorizeResource(caller, "OpenDataChannel", sessionArn);
        }
        Side previous;
        synchronized (state) {
            if (state.ended) {
                throw SsmChannelAuthentication.denied(SESSION_ALREADY_TERMINATED);
            }
            side.attached = state;
            side.role = token.role();
            if (token.role() == Role.AGENT) {
                previous = state.agent;
                state.agent = side;
                side.send(control(state.client == null ? "pause_publication" : "start_publication", "{}"));
                flush(state.toAgent, side);
            } else {
                previous = state.client;
                state.client = side;
                flush(state.toClient, side);
                if (state.agent != null) {
                    state.agent.send(control("start_publication", "{}"));
                    flush(state.toAgent, state.agent);
                }
                if (state.agentConnected) {
                    status(state, "Connected");
                }
            }
        }
        if (previous != null) {
            previous.close((short) 1000, "A replacement data channel was opened.");
        }
        LOG.infov("SSM data channel attached: session={0} role={1}", state.sessionId, token.role());
    }

    private void receive(Side side, byte[] frame) {
        Live state = side.attached;
        if (state == null) {
            side.close((short) 1008, "Authenticate the data channel first.");
            return;
        }
        if (frame.length < SsmAgentMessage.PAYLOAD_OFFSET || frame.length > MAX_FRAME_BYTES) {
            side.close((short) 1002, "Invalid data-channel message.");
            return;
        }
        synchronized (state) {
            if (side.role == Role.AGENT && state.agent == side) {
                if ("agent_session_state".equals(messageType(frame))) {
                    sessionState(state, frame);
                } else if (state.client != null) {
                    state.client.send(frame);
                } else {
                    buffer(state.toClient, frame);
                }
            } else if (side.role == Role.CLIENT && state.client == side) {
                if (state.agent != null) {
                    state.agent.send(frame);
                } else {
                    buffer(state.toAgent, frame);
                }
            }
        }
    }

    private void detach(Side side) {
        side.closed = true;
        Live state = side.attached;
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.agent == side) {
                state.agent = null;
            } else if (state.client == side) {
                state.client = null;
                if (!state.ended) {
                    if (state.agent != null) {
                        state.agent.send(control("pause_publication", "{}"));
                    }
                    status(state, "Disconnected");
                }
            }
        }
    }

    private void sessionState(Live state, byte[] frame) {
        try {
            String reported = mapper.readTree(SsmAgentMessage.decode(frame).payload()).path("SessionState").asText();
            if ("Connected".equals(reported)) {
                state.agentConnected = true;
                if (state.client != null) {
                    status(state, "Connected");
                }
            } else if ("Terminating".equals(reported)) {
                status(state, "Terminating");
            }
        } catch (Exception invalid) {
            LOG.debugv(invalid, "Ignoring an invalid agent session state for session {0}", state.sessionId);
        }
    }

    // ── Session end ─────────────────────────────────────────────────────────

    private void end(String account, String region, String sessionId, boolean notifyAgent, String output) {
        Live state = live.remove(liveKey(account, region, sessionId));
        if (state == null) {
            // A session recorded before a restart has no data channels left to close.
            RequestScopes.runAs(account, region, () -> store.get(key(region, sessionId))
                    .filter(session -> ACTIVE.contains(session.getStatus())).ifPresent(session -> {
                        session.setStatus("Terminated");
                        session.setEndDate(clock.instant());
                        store.put(key(region, sessionId), session);
                    }));
            return;
        }
        Side agent;
        Side client;
        synchronized (state) {
            state.ended = true;
            agent = state.agent;
            client = state.client;
            state.agent = null;
            state.client = null;
            state.control.clear();
            if (client != null) {
                flush(state.toClient, client);
            }
        }
        tokens.values().removeIf(token -> token.liveKey().equals(state.key()));
        RequestScopes.runAs(account, region, () -> store.get(key(region, sessionId)).ifPresent(session -> {
            session.setStatus("Terminated");
            session.setEndDate(clock.instant());
            store.put(key(region, sessionId), session);
        }));
        byte[] closed = channelClosed(state, output);
        if (client != null) {
            client.send(closed);
            client.close((short) 1000, "The session ended.");
        }
        if (agent != null) {
            agent.send(closed);
            agent.close((short) 1000, "The session ended.");
        }
        if (notifyAgent) {
            ControlMessage notice = new ControlMessage(UUID.randomUUID(), "channel_closed",
                    channelClosedPayload(state, ""), false);
            notices.put(notice.id, new Notice(account, region, state.target, notice));
        }
        LOG.infov("SSM session {0} terminated", sessionId);
    }

    @Override
    @PreDestroy
    public void clear() {
        tokens.clear();
        notices.clear();
        List<Live> states = List.copyOf(live.values());
        live.clear();
        for (Live state : states) {
            synchronized (state) {
                state.ended = true;
                if (state.agent != null) {
                    state.agent.close((short) 1001, "The service is stopping.");
                }
                if (state.client != null) {
                    state.client.close((short) 1001, "The service is stopping.");
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ObjectNode connection(Live state) {
        String token = issue(state, Role.CLIENT, null);
        return mapper.createObjectNode()
                .put("SessionId", state.sessionId)
                .put("StreamUrl", streamUrl(state))
                .put("TokenValue", token);
    }

    private String issue(Live state, Role role, String callerArn) {
        Instant now = clock.instant();
        tokens.values().removeIf(token -> !token.expires().isAfter(now));
        if (tokens.size() >= 10_000) {
            throw new AwsException("ThrottlingException", "Too many pending data channels.", 429);
        }
        String value = UUID.randomUUID() + "/" + UUID.randomUUID().toString().replace("-", "");
        tokens.put(value, new Token(state.key(), role, callerArn, now.plusSeconds(TOKEN_LIFETIME_SECONDS)));
        return value;
    }

    /**
     * The API reference's {@code wss://ssmmessages.<region>.<suffix>/v1/data-channel/<session-id>} when the
     * caller addressed the regional AWS endpoint; otherwise the same path on the endpoint it addressed.
     */
    private String streamUrl(Live state) {
        String path = "/v1/data-channel/" + state.sessionId + "?role=publish_subscribe";
        HttpServerRequest request = requests.getCurrent() != null ? requests.getCurrent().request() : null;
        String host = request != null ? RequestHost.of(request) : null;
        String name = host == null ? "" : host.replaceFirst(":[0-9]+$", "").toLowerCase(Locale.ROOT);
        if (host == null || name.endsWith("." + AwsRegions.dnsSuffixFor(state.region))) {
            return "wss://" + AwsEndpoints.host("ssmmessages", state.region) + path;
        }
        return (request.isSSL() ? "wss://" : "ws://") + host + path;
    }

    private String callerArn(String account) {
        String accessKey = requestContext != null ? requestContext.getAccessKeyId() : null;
        return iam.resolveCallerArn(accessKey).orElseGet(() -> regions.buildGlobalArn("iam", account, "root"));
    }

    /** {@code prefix-random-suffix}; the prefix is the caller's name as Session Manager forms session IDs. */
    private static String sessionId(String callerArn) {
        String resource = AwsArnUtils.isArn(callerArn) ? AwsArnUtils.parse(callerArn).resource() : "root";
        String prefix = resource.substring(resource.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9+=,.@_-]", "");
        if (prefix.isEmpty()) {
            prefix = "root";
        }
        if (prefix.length() > 78) {
            prefix = prefix.substring(0, 78);
        }
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
    }

    private String sessionArn(Live state) {
        return regions.buildArn("ssm", state.region, state.account, "session/" + state.sessionId);
    }

    private static String documentName(String name) {
        if (name.isEmpty()) {
            return null;
        }
        if (AwsArnUtils.looksLikeArn(name) && AwsArnUtils.isArnFor(name, "ssm")) {
            String resource = AwsArnUtils.parse(name).resource();
            if (resource.startsWith("document/")) {
                name = resource.substring("document/".length());
            }
        }
        if (!DOCUMENT_NAME.matcher(name).matches()) {
            throw new AwsException("ValidationException", "DocumentName is not valid.", 400);
        }
        return name;
    }

    private static Map<String, List<String>> parameterValues(JsonNode parameters) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (parameters == null || parameters.isMissingNode() || parameters.isNull()) {
            return values;
        }
        if (!parameters.isObject()) {
            throw new AwsException("ValidationException", "Parameters must be a map of string lists.", 400);
        }
        for (Map.Entry<String, JsonNode> entry : parameters.properties()) {
            if (entry.getKey().isEmpty() || entry.getKey().length() > 255 || !entry.getValue().isArray()) {
                throw new AwsException("ValidationException", "Parameters must be a map of string lists.", 400);
            }
            List<String> list = new ArrayList<>();
            for (JsonNode value : entry.getValue()) {
                if (!value.isTextual() || value.asText().isEmpty() || value.asText().length() > 65535) {
                    throw new AwsException("ValidationException", "Parameter values must be 1 to 65535 characters.", 400);
                }
                list.add(value.asText());
            }
            values.put(entry.getKey(), list);
        }
        return values;
    }

    private static Predicate<SsmSession> filters(JsonNode filters) {
        Predicate<SsmSession> result = session -> true;
        if (filters == null || filters.isMissingNode() || filters.isNull()) {
            return result;
        }
        if (!filters.isArray() || filters.isEmpty() || filters.size() > 6) {
            throw new AwsException("ValidationException", "Filters must hold between 1 and 6 filters.", 400);
        }
        for (JsonNode filter : filters) {
            String key = filter.path("key").asText("");
            String value = filter.path("value").asText("");
            if (!FILTER_KEYS.contains(key)) {
                throw new AwsException("InvalidFilterKey", "The specified key isn't valid.", 400);
            }
            if (value.isEmpty() || value.length() > 400) {
                throw new AwsException("ValidationException", "A filter value must be 1 to 400 characters.", 400);
            }
            Predicate<SsmSession> next = switch (key) {
                case "InvokedAfter" -> session -> !session.getStartDate().isBefore(instant(value));
                case "InvokedBefore" -> session -> !session.getStartDate().isAfter(instant(value));
                case "Target" -> session -> value.equals(session.getTarget());
                case "Owner" -> session -> value.equals(session.getOwner());
                case "Status" -> session -> value.equals(session.getStatus());
                case "SessionId" -> session -> value.equals(session.getSessionId());
                default -> session -> "Standard".equals(value);
            };
            result = result.and(next);
        }
        return result;
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new AwsException("ValidationException", "The date filter value must be an ISO 8601 time.", 400);
        }
    }

    private void status(Live state, String status) {
        if (state.ended || status.equals(state.status)) {
            return;
        }
        state.status = status;
        RequestScopes.runAs(state.account, state.region, () ->
                store.get(key(state.region, state.sessionId)).ifPresent(session -> {
                    if (ACTIVE.contains(session.getStatus())) {
                        session.setStatus(status);
                        store.put(key(state.region, state.sessionId), session);
                    }
                }));
    }

    private static void buffer(Deque<byte[]> queue, byte[] frame) {
        queue.addLast(frame);
        long bytes = queue.stream().mapToLong(item -> item.length).sum();
        while (queue.size() > MAX_BUFFERED_FRAMES || bytes > MAX_BUFFERED_BYTES) {
            bytes -= queue.removeFirst().length;
        }
    }

    private static void flush(Deque<byte[]> queue, Side side) {
        while (!queue.isEmpty()) {
            side.send(queue.removeFirst());
        }
    }

    /** The message type an agent message carries in its fixed header, without validating the rest. */
    static String messageType(byte[] frame) {
        return new String(frame, 4, 32, StandardCharsets.US_ASCII).replace("\0", "").trim();
    }

    private byte[] control(String type, String payload) {
        return SsmAgentMessage.create(type, payload.getBytes(StandardCharsets.UTF_8)).encode();
    }

    private byte[] channelClosed(Live state, String output) {
        return SsmAgentMessage.create("channel_closed", channelClosedPayload(state, output)).encode();
    }

    private byte[] channelClosedPayload(Live state, String output) {
        ObjectNode payload = mapper.createObjectNode()
                .put("MessageId", UUID.randomUUID().toString())
                .put("CreatedDate", clock.instant().toString())
                .put("DestinationId", state.target)
                .put("SessionId", state.sessionId)
                .put("MessageType", "channel_closed")
                .put("SchemaVersion", 1)
                .put("Output", output);
        return bytes(payload);
    }

    private byte[] bytes(JsonNode node) {
        try {
            return mapper.writeValueAsBytes(node);
        } catch (Exception invalid) {
            throw new IllegalStateException("Could not encode a session message.", invalid);
        }
    }

    private String write(JsonNode node) {
        return new String(bytes(node), StandardCharsets.UTF_8);
    }

    private static String key(String region, String sessionId) {
        return region + "::" + sessionId;
    }

    private static String liveKey(String account, String region, String sessionId) {
        return account + "::" + region + "::" + sessionId;
    }

    enum Role { AGENT, CLIENT }

    private record Token(String liveKey, Role role, String callerArn, Instant expires) {}

    private record Notice(String account, String region, String target, ControlMessage message) {}

    /** A message for the node's control channel; a session start stays queued until the agent opens its data channel. */
    static final class ControlMessage {
        final UUID id;
        final String type;
        final byte[] payload;
        final boolean untilDataChannel;
        volatile UUID deliveredOn;

        ControlMessage(UUID id, String type, byte[] payload, boolean untilDataChannel) {
            this.id = id;
            this.type = type;
            this.payload = payload;
            this.untilDataChannel = untilDataChannel;
        }
    }

    private static final class Live {
        final String account;
        final String region;
        final String sessionId;
        final String target;
        final List<ControlMessage> control = new ArrayList<>();
        final Deque<byte[]> toClient = new ArrayDeque<>();
        final Deque<byte[]> toAgent = new ArrayDeque<>();
        Side agent;
        Side client;
        String status = "Connecting";
        boolean agentConnected;
        boolean ended;

        Live(String account, String region, String sessionId, String target) {
            this.account = account;
            this.region = region;
            this.sessionId = sessionId;
            this.target = target;
        }

        String key() {
            return liveKey(account, region, sessionId);
        }
    }

    private static final class Side {
        final ServerWebSocket socket;
        final Context context;
        volatile Live attached;
        volatile Role role;
        volatile boolean closed;

        Side(ServerWebSocket socket, Context context) {
            this.socket = socket;
            this.context = context;
        }

        void send(byte[] frame) {
            context.runOnContext(ignored -> {
                if (!closed) {
                    socket.writeBinaryMessage(Buffer.buffer(frame)).onFailure(error ->
                            close((short) 1011, "The data-channel write failed."));
                }
            });
        }

        void close(short code, String reason) {
            context.runOnContext(ignored -> {
                if (!closed) {
                    closed = true;
                    socket.close(code, reason);
                }
            });
        }
    }
}
