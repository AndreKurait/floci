package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.testing.MutableClock;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.InvalidDocumentException;
import software.amazon.awssdk.services.ssm.model.InvalidFilterKeyException;
import software.amazon.awssdk.services.ssm.model.Session;
import software.amazon.awssdk.services.ssm.model.SessionFilterKey;
import software.amazon.awssdk.services.ssm.model.SessionState;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.ssm.model.StartSessionResponse;
import software.amazon.awssdk.services.ssm.model.TargetNotConnectedException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class SsmSessionManagerIntegrationTest {
    private static final String REGION = "us-east-1";
    private static final AwsRequestSigner ROOT = AwsRequestSigner.signedAs("test", "test", "ssmmessages");
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<WebSocket> sockets = new ArrayList<>();
    private SsmClient ssm;
    private String instance;

    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    SsmCommandService commands;
    @Inject
    RegionResolver regions;
    @Inject
    MutableClock clock;

    @BeforeEach
    void prepareManagedNode() {
        clock.reset();
        clock.advance(Duration.between(clock.instant(), Instant.now()));
        instance = "i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        ssm = client("test", "test");
        RequestScopes.runAs(regions.getDefaultAccountId(), REGION, () ->
                commands.updateInstanceInformation(mapper.createObjectNode().put("InstanceId", instance), REGION));
    }

    @AfterEach
    void closeClients() {
        sockets.forEach(WebSocket::abort);
        http.close();
        ssm.close();
        clock.reset();
    }

    @Test
    void aNonInteractiveSessionStartsOnTheAgentAndRelaysBothWaysUnchanged() throws Exception {
        Receiver control = new Receiver();
        handshake(openControl(control), controlToken());
        StartSessionResponse started = ssm.startSession(request -> request.target(instance)
                .documentName("AWS-StartNonInteractiveCommand").reason("relay check")
                .parameters(Map.of("command", List.of("printf relayed"))));
        String sessionId = started.sessionId();
        assertTrue(sessionId.matches("root-[0-9a-f]{17}"), sessionId);
        assertEquals(URI.create(dataChannel(sessionId).toString().replaceFirst("^http", "ws")
                + "?role=publish_subscribe"), URI.create(started.streamUrl()));

        JsonNode start = mapper.readTree(control.next("interactive_shell").payload());
        assertEquals(sessionId, start.path("TaskId").asText());
        JsonNode task = mapper.readTree(start.path("Content").asText());
        assertEquals(sessionId, task.path("SessionId").asText());
        assertEquals("AWS-StartNonInteractiveCommand", task.path("DocumentName").asText());
        assertEquals("NonInteractiveCommands", task.at("/DocumentContent/sessionType").asText());
        assertEquals("{{ command }}", task.at("/DocumentContent/properties/linux/commands").asText());
        assertEquals("printf relayed", task.at("/Parameters/command").asText());
        assertEquals("arn:aws:iam::" + regions.getDefaultAccountId() + ":root", task.path("SessionOwner").asText());

        Receiver agent = new Receiver();
        attachAgent(sessionId, agent);
        assertEquals("pause_publication", agent.next("pause_publication").type());
        byte[] state = frame("agent_session_state", "{\"SchemaVersion\":1,\"SessionState\":\"Connected\",\"SessionId\":\""
                + sessionId + "\"}");
        byte[] handshakeRequest = frame("output_stream_data", "{\"AgentVersion\":\"3.3.5700.0\"}");
        agent.socket.sendBinary(ByteBuffer.wrap(state), true).get(5, TimeUnit.SECONDS);
        agent.socket.sendBinary(ByteBuffer.wrap(handshakeRequest), true).get(5, TimeUnit.SECONDS);

        Receiver client = new Receiver();
        attachClient(started.streamUrl(), started.tokenValue(), client);
        agent.next("start_publication");
        assertArrayEquals(handshakeRequest, client.nextRaw(), "The agent's message reached the client changed");
        byte[] input = frame("input_stream_data", "{\"ClientVersion\":\"1.2.0.0\"}");
        client.socket.sendBinary(ByteBuffer.wrap(input), true).get(5, TimeUnit.SECONDS);
        assertArrayEquals(input, agent.nextRaw(), "The client's message reached the agent changed");
        assertNull(client.raw.poll(300, TimeUnit.MILLISECONDS), "The agent's session state was relayed to the client");

        Session active = only(SessionState.ACTIVE, sessionId);
        assertEquals("Connected", active.statusAsString());
        assertEquals(instance, active.target());
        assertEquals("AWS-StartNonInteractiveCommand", active.documentName());
        assertEquals("relay check", active.reason());

        assertEquals(sessionId, ssm.terminateSession(request -> request.sessionId(sessionId)).sessionId());
        assertEquals(sessionId, mapper.readTree(client.next("channel_closed").payload()).path("SessionId").asText());
        assertEquals(sessionId, mapper.readTree(agent.next("channel_closed").payload()).path("SessionId").asText());
        assertEquals(sessionId, mapper.readTree(control.next("channel_closed").payload()).path("SessionId").asText());
        assertEquals(1000, client.closed.get(5, TimeUnit.SECONDS));
        Session ended = only(SessionState.HISTORY, sessionId);
        assertEquals("Terminated", ended.statusAsString());
        assertNotNull(ended.endDate());
        assertTrue(ssm.describeSessions(request -> request.state(SessionState.ACTIVE)).sessions().stream()
                .noneMatch(session -> session.sessionId().equals(sessionId)));
        assertThrows(SsmException.class, () -> ssm.resumeSession(request -> request.sessionId(sessionId)));
    }

    @Test
    void theAgentsTaskCompletionClosesTheClientWithItsOutput() throws Exception {
        Receiver control = new Receiver();
        handshake(openControl(control), controlToken());
        StartSessionResponse started = ssm.startSession(request -> request.target(instance)
                .documentName("AWS-StartInteractiveCommand").parameters(Map.of("command", List.of("true"))));
        control.next("interactive_shell");
        Receiver agent = new Receiver();
        attachAgent(started.sessionId(), agent);
        agent.next("pause_publication");
        Receiver client = new Receiver();
        attachClient(started.streamUrl(), started.tokenValue(), client);
        agent.next("start_publication");
        SsmAgentMessage complete = SsmAgentMessage.create("agent_task_complete", mapper.writeValueAsBytes(
                Map.of("SchemaVersion", 1, "TaskId", started.sessionId(), "Topic", "agent_task_complete",
                        "FinalTaskStatus", "Success", "InstanceId", instance, "Output", "done")));
        control.socket.sendBinary(ByteBuffer.wrap(complete.encode()), true).get(5, TimeUnit.SECONDS);
        JsonNode acknowledged = mapper.readTree(control.next("agent_task_acknowledge").payload());
        assertEquals(complete.id().toString(), acknowledged.path("MessageId").asText());
        assertEquals(started.sessionId(), acknowledged.path("TaskId").asText());
        assertEquals("done", mapper.readTree(client.next("channel_closed").payload()).path("Output").asText());
        assertEquals("Terminated", only(SessionState.HISTORY, started.sessionId()).statusAsString());
    }

    @Test
    void aTokenOpensOneDataChannelOnlyWhileItIsCurrent() throws Exception {
        Receiver control = new Receiver();
        handshake(openControl(control), controlToken());
        StartSessionResponse started = ssm.startSession(request -> request.target(instance));
        JsonNode task = mapper.readTree(mapper.readTree(control.next("interactive_shell").payload())
                .path("Content").asText());
        assertEquals("SSM-SessionManagerRunShell", task.path("DocumentName").asText());
        assertEquals("Standard_Stream", task.at("/DocumentContent/sessionType").asText());
        Receiver agent = new Receiver();
        attachAgent(started.sessionId(), agent);
        agent.next("pause_publication");

        Receiver first = new Receiver();
        attachClient(started.streamUrl(), started.tokenValue(), first);
        agent.next("start_publication");
        Receiver reused = new Receiver();
        attachClient(started.streamUrl(), started.tokenValue(), reused);
        assertEquals(1008, reused.closed.get(5, TimeUnit.SECONDS));

        String resumed = ssm.resumeSession(request -> request.sessionId(started.sessionId())).tokenValue();
        assertNotEquals(started.tokenValue(), resumed);
        clock.advance(Duration.ofSeconds(61));
        Receiver late = new Receiver();
        attachClient(started.streamUrl(), resumed, late);
        assertEquals(1008, late.closed.get(5, TimeUnit.SECONDS));
    }

    @Test
    void aSignedClientNeedsOpenDataChannelOnItsSession() throws Exception {
        Receiver control = new Receiver();
        handshake(openControl(control), controlToken());
        StartSessionResponse started = ssm.startSession(request -> request.target(instance));
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-session-", "other", """
                {"Statement":{"Effect":"Allow","Action":"ssmmessages:OpenDataChannel",
                  "Resource":"arn:aws:ssm:us-east-1:000000000000:session/someone-else-*"}}
                """)) {
            Receiver client = new Receiver();
            URI stream = URI.create(started.streamUrl());
            WebSocket.Builder builder = http.newWebSocketBuilder();
            user.signer("ssmmessages").headersFor("GET", stream, Map.of(), new byte[0]).forEach(builder::header);
            WebSocket socket = builder.buildAsync(stream, client).get(5, TimeUnit.SECONDS);
            sockets.add(socket);
            socket.sendText(open(started.tokenValue(), null), true).get(5, TimeUnit.SECONDS);
            assertEquals(1008, client.closed.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void startSessionNeedsAConnectedTargetAndASessionDocument() throws Exception {
        assertEquals("notconnected",
                ssm.getConnectionStatus(request -> request.target(instance)).statusAsString());
        assertThrows(TargetNotConnectedException.class, () -> ssm.startSession(request -> request.target(instance)));
        Receiver control = new Receiver();
        handshake(openControl(control), controlToken());
        assertEquals("connected", ssm.getConnectionStatus(request -> request.target(instance)).statusAsString());
        assertThrows(TargetNotConnectedException.class, () ->
                ssm.startSession(request -> request.target("i-0123456789abcdef0")));
        assertThrows(InvalidDocumentException.class, () ->
                ssm.startSession(request -> request.target(instance).documentName("No-Such-Session-Document")));
        String command = "session-cmd-" + UUID.randomUUID();
        ssm.createDocument(request -> request.name(command).documentType("Command").content(
                "{\"schemaVersion\":\"2.2\",\"mainSteps\":[{\"action\":\"aws:runShellScript\",\"name\":\"a\","
                        + "\"inputs\":{\"runCommand\":[\"true\"]}}]}"));
        try {
            assertThrows(InvalidDocumentException.class, () ->
                    ssm.startSession(request -> request.target(instance).documentName(command)));
        } finally {
            ssm.deleteDocument(request -> request.name(command));
        }
        SsmException missing = assertThrows(SsmException.class, () -> ssm.startSession(request ->
                request.target(instance).documentName("AWS-StartNonInteractiveCommand")));
        assertEquals("ValidationException", missing.awsErrorDetails().errorCode());
        String session = "session-doc-" + UUID.randomUUID();
        ssm.createDocument(request -> request.name(session).documentType("Session").content("""
                {"schemaVersion":"1.0","sessionType":"InteractiveCommands",
                 "parameters":{"logpath":{"type":"String","allowedPattern":"^[a-z/]+\\\\.log$"}},
                 "properties":{"linux":{"commands":"tail {{ logpath }}","runAsElevated":false}}}
                """));
        try {
            SsmException pattern = assertThrows(SsmException.class, () -> ssm.startSession(request ->
                    request.target(instance).documentName(session).parameters(Map.of("logpath", List.of("x;y")))));
            assertEquals("ValidationException", pattern.awsErrorDetails().errorCode());
            StartSessionResponse started = ssm.startSession(request -> request.target(instance).documentName(session)
                    .parameters(Map.of("logpath", List.of("/var/log/agent.log"))));
            JsonNode task = mapper.readTree(mapper.readTree(control.next("interactive_shell").payload())
                    .path("Content").asText());
            assertEquals(started.sessionId(), task.path("SessionId").asText());
            assertEquals("/var/log/agent.log", task.at("/Parameters/logpath").asText());
        } finally {
            ssm.deleteDocument(request -> request.name(session));
        }
        assertThrows(InvalidFilterKeyException.class, () -> ssm.describeSessions(request -> request
                .state(SessionState.ACTIVE).filters(filter -> filter.key("Name").value("x"))));
        assertFalse(ssm.describeSessions(request -> request.state(SessionState.ACTIVE)
                .filters(filter -> filter.key(SessionFilterKey.TARGET).value(instance))).sessions().isEmpty());
    }

    @Test
    void startSessionNeedsTheDocumentAndTheTargetInTheCallersPolicy() throws Exception {
        Receiver control = new Receiver();
        handshake(openControl(control), controlToken());
        String document = "arn:aws:ssm:us-east-1::document/AWS-StartNonInteractiveCommand";
        String other = "arn:aws:ec2:us-east-1:000000000000:instance/i-0123456789abcdef0";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-start-", "start", """
                {"Statement":{"Effect":"Allow","Action":"ssm:StartSession","Resource":["%s","%s"]}}
                """.formatted(document, other)); SsmClient limited = client(user.accessKeyId(), user.secretAccessKey())) {
            SsmException denied = assertThrows(SsmException.class, () -> limited.startSession(request ->
                    request.target(instance).documentName("AWS-StartNonInteractiveCommand")
                            .parameters(Map.of("command", List.of("true")))));
            assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
        }
        String target = "arn:aws:ec2:us-east-1:000000000000:instance/" + instance;
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-start-", "start", """
                {"Statement":[{"Effect":"Allow","Action":"ssm:StartSession","Resource":["%s","%s"]},
                  {"Effect":"Allow","Action":"ssm:TerminateSession","Resource":"arn:aws:ssm:us-east-1:000000000000:session/nobody-*"}]}
                """.formatted(document, target)); SsmClient limited = client(user.accessKeyId(), user.secretAccessKey())) {
            String sessionId = limited.startSession(request -> request.target(instance)
                    .documentName("AWS-StartNonInteractiveCommand")
                    .parameters(Map.of("command", List.of("true")))).sessionId();
            assertTrue(sessionId.startsWith(user.name() + "-"), sessionId);
            assertEquals("arn:aws:iam::000000000000:user/" + user.name(), only(SessionState.ACTIVE, sessionId).owner());
            SsmException own = assertThrows(SsmException.class, () ->
                    limited.terminateSession(request -> request.sessionId(sessionId)));
            assertEquals("AccessDeniedException", own.awsErrorDetails().errorCode());
            ssm.terminateSession(request -> request.sessionId(sessionId));
            assertEquals("Terminated", only(SessionState.HISTORY, sessionId).statusAsString());
        }
    }

    @Test
    void aNonInteractiveSessionCanAskTheAgentToSeparateItsOutputStreams() throws Exception {
        Receiver control = new Receiver();
        handshake(openControl(control), controlToken());
        StartSessionResponse started = ssm.startSession(request -> request.target(instance)
                .documentName("AWS-StartNonInteractiveCommand")
                .parameters(Map.of("command", List.of("true"), "separateOutputStream", List.of("true"))));
        JsonNode task = mapper.readTree(mapper.readTree(control.next("interactive_shell").payload())
                .path("Content").asText());
        assertEquals(started.sessionId(), task.path("SessionId").asText());
        assertEquals("true", task.at("/Parameters/separateOutputStream").asText());
        for (String os : List.of("linux", "windows", "macos")) {
            assertEquals("{{ separateOutputStream }}",
                    task.at("/DocumentContent/properties/" + os + "/separateOutputStream").asText(), os);
        }
        assertEquals("false", task.at("/DocumentContent/parameters/separateOutputStream/default").asText());
        SsmException invalid = assertThrows(SsmException.class, () -> ssm.startSession(request -> request
                .target(instance).documentName("AWS-StartNonInteractiveCommand")
                .parameters(Map.of("command", List.of("true"), "separateOutputStream", List.of("yes")))));
        assertEquals("ValidationException", invalid.awsErrorDetails().errorCode());
    }

    @Test
    void aPresignedClientStreamAndStderrAndExitCodeFramesRelayUnchanged() throws Exception {
        Receiver control = new Receiver();
        handshake(openControl(control), controlToken());
        StartSessionResponse started = ssm.startSession(request -> request.target(instance)
                .documentName("AWS-StartNonInteractiveCommand").parameters(Map.of("command", List.of("false"))));
        control.next("interactive_shell");
        Receiver agent = new Receiver();
        attachAgent(started.sessionId(), agent);
        agent.next("pause_publication");
        URI base = URI.create(started.streamUrl().substring(0, started.streamUrl().indexOf('?')));
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-presign-", "open", """
                {"Statement":{"Effect":"Allow","Action":"ssmmessages:OpenDataChannel","Resource":"*"}}
                """); StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build()) {
            Credentials session = sts.getSessionToken().credentials();
            URI presigned = AwsRequestSigner.signedAs(session.accessKeyId(), session.secretAccessKey(), "ssmmessages")
                    .presignedUri("GET", base, Map.of("role", "publish_subscribe",
                            "X-Amz-Security-Token", session.sessionToken()), new byte[0]);
            for (String name : List.of("X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Date", "X-Amz-Expires",
                    "X-Amz-SignedHeaders", "X-Amz-Signature", "X-Amz-Security-Token", "role=publish_subscribe")) {
                assertTrue(presigned.getRawQuery().contains(name), name);
            }
            Receiver client = new Receiver();
            attachClient(presigned.toString(), started.tokenValue(), client);
            agent.next("start_publication");
            for (int payloadType : List.of(1, 11, 12)) {
                byte[] output = withPayloadType(frame("output_stream_data", "payload " + payloadType), payloadType);
                agent.socket.sendBinary(ByteBuffer.wrap(output), true).get(5, TimeUnit.SECONDS);
                assertArrayEquals(output, client.nextRaw(), "payload type " + payloadType);
            }
        }
    }

    @Test
    void theStreamUrlNamesTheRegionalMessagingHostWhenTheRegionalHostWasAddressed() throws Exception {
        Receiver control = new Receiver();
        handshake(openControl(control), controlToken());
        byte[] body = mapper.writeValueAsBytes(Map.of("Target", instance));
        AwsRequestSigner signer = AwsRequestSigner.signedAs("test", "test", "ssm");
        Map<String, String> headers = signer.headersFor("POST", URI.create("https://ssm.us-east-1.amazonaws.com/"),
                Map.of("x-amz-target", "AmazonSSM.StartSession"), body);
        JsonPath started = given().header("Host", "ssm.us-east-1.amazonaws.com")
                .headers(headers).header("X-Amz-Target", "AmazonSSM.StartSession")
                .contentType("application/x-amz-json-1.1").body(body).post(endpoint)
                .then().statusCode(200).extract().jsonPath();
        assertEquals("wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/" + started.getString("SessionId")
                + "?role=publish_subscribe", started.getString("StreamUrl"));
    }

    private static byte[] withPayloadType(byte[] frame, int payloadType) {
        ByteBuffer.wrap(frame).putInt(SsmAgentMessage.HEADER_LENGTH - 4, payloadType);
        return frame;
    }

    private Session only(SessionState state, String sessionId) {
        List<Session> sessions = ssm.describeSessions(request -> request.state(state)
                .filters(filter -> filter.key(SessionFilterKey.SESSION_ID).value(sessionId))).sessions();
        assertEquals(1, sessions.size(), "DescribeSessions " + state + " for " + sessionId);
        return sessions.getFirst();
    }

    private SsmClient client(String accessKey, String secret) {
        return SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
                .build();
    }

    private void attachAgent(String sessionId, Receiver agent) throws Exception {
        String token = given().filter(ROOT).contentType("application/json")
                .body("{\"MessageSchemaVersion\":\"1.0\",\"RequestId\":\"" + UUID.randomUUID()
                        + "\",\"ClientId\":\"" + UUID.randomUUID() + "\"}")
                .post(dataChannel(sessionId)).then().statusCode(201).extract().xmlPath()
                .getString("CreateDataChannelResponse.TokenValue");
        URI uri = URI.create(dataChannel(sessionId).toString().replaceFirst("^http", "ws") + "?role=publish_subscribe");
        WebSocket.Builder builder = http.newWebSocketBuilder();
        ROOT.headersFor("GET", uri, Map.of(), new byte[0]).forEach(builder::header);
        WebSocket socket = builder.buildAsync(uri, agent).get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        socket.sendText(open(token, instance), true).get(5, TimeUnit.SECONDS);
    }

    private void attachClient(String streamUrl, String token, Receiver client) throws Exception {
        WebSocket socket = http.newWebSocketBuilder().buildAsync(URI.create(streamUrl), client).get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        socket.sendText(open(token, null), true).get(5, TimeUnit.SECONDS);
    }

    private String open(String token, String clientInstance) throws Exception {
        Map<String, String> open = new LinkedHashMap<>(Map.of("MessageSchemaVersion", "1.0",
                "RequestId", UUID.randomUUID().toString(), "TokenValue", token, "ClientId", UUID.randomUUID().toString()));
        if (clientInstance != null) {
            open.put("ClientInstanceId", clientInstance);
        } else {
            open.put("ClientVersion", "1.2.0.0");
        }
        return mapper.writeValueAsString(open);
    }

    private static byte[] frame(String type, String payload) {
        return SsmAgentMessage.create(type, payload.getBytes(StandardCharsets.UTF_8)).encode();
    }

    private URI dataChannel(String sessionId) {
        return endpoint.resolve("v1/data-channel/" + sessionId);
    }

    private URI controlChannel(String node) {
        return endpoint.resolve("v1/control-channel/" + node);
    }

    private String controlToken() {
        return given().filter(ROOT).contentType("application/json")
                .body("{\"MessageSchemaVersion\":\"1.0\",\"RequestId\":\"" + UUID.randomUUID() + "\"}")
                .post(controlChannel(instance)).then().statusCode(201).extract().xmlPath()
                .getString("CreateControlChannelResponse.TokenValue");
    }

    private WebSocket openControl(Receiver receiver) throws Exception {
        URI uri = URI.create(controlChannel(instance).toString().replaceFirst("^http", "ws") + "?role=subscribe&stream=input");
        WebSocket.Builder builder = http.newWebSocketBuilder();
        ROOT.headersFor("GET", uri, Map.of(), new byte[0]).forEach(builder::header);
        WebSocket socket = builder.buildAsync(uri, receiver).get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        return socket;
    }

    private void handshake(WebSocket socket, String token) throws Exception {
        socket.sendText(mapper.writeValueAsString(Map.of("MessageSchemaVersion", "1.0",
                "RequestId", UUID.randomUUID().toString(), "TokenValue", token)), true).get(5, TimeUnit.SECONDS);
    }

    private static final class Receiver implements WebSocket.Listener {
        private final BlockingQueue<byte[]> raw = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
        private volatile WebSocket socket;

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            partial.writeBytes(bytes);
            if (last) {
                raw.add(partial.toByteArray());
                partial.reset();
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
            closed.complete(status);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }

        private byte[] nextRaw() throws Exception {
            byte[] message = raw.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "No message arrived");
            return message;
        }

        private SsmAgentMessage next(String type) throws Exception {
            SsmAgentMessage message = SsmAgentMessage.decode(nextRaw());
            assertEquals(type, message.type());
            return message;
        }
    }
}
