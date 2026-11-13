package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.testing.MutableClock;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.Command;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class SsmControlChannelIntegrationTest {
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
        ssm = SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
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
    void sdkCommandNeedsTheTokenHandshakeAndPreservesIntermediateAndFinalReplies() throws Exception {
        Command command = ssm.sendCommand(request -> request.instanceIds(instance).documentName("AWS-RunShellScript")
                .parameters(Map.of("commands", List.of("printf native")))).command();
        Receiver receiver = new Receiver();
        WebSocket socket = open(instance, ROOT, receiver);
        assertNull(receiver.messages.poll(300, TimeUnit.MILLISECONDS), "An unauthenticated socket received a job");
        handshake(socket, token(instance, ROOT));
        SsmAgentMessage delivery = receiver.next("agent_job");
        JsonNode job = mapper.readTree(delivery.payload());
        assertEquals("aws.ssm." + command.commandId() + "." + instance, job.path("JobId").asText());
        JsonNode content = mapper.readTree(job.path("Content").asText());
        assertEquals("printf native", content.at("/Parameters/commands/0").asText());
        assertEquals("aws:runShellScript", content.at("/DocumentContent/mainSteps/0/action").asText());
        send(socket, "agent_job_ack", mapper.createObjectNode().put("jobId", job.path("JobId").asText())
                .put("acknowledgedMessageId", delivery.id().toString()).put("statusCode", "200"));
        reply(socket, job.path("JobId").asText(), "{\"documentStatus\":\"InProgress\"}");
        receiver.next("agent_job_reply_ack");
        GetCommandInvocationResponse active = invocation(command);
        assertEquals("InProgress", active.statusAsString());
        assertEquals(-1, active.responseCode());
        reply(socket, job.path("JobId").asText(), """
                {"documentStatus":"Success","runtimeStatus":{
                  "runShellScript":{"status":"Success","code":0,"standardOutput":"native"}
                }}
                """);
        receiver.next("agent_job_reply_ack");
        GetCommandInvocationResponse complete = invocation(command);
        assertEquals("Success", complete.statusAsString());
        assertEquals(0, complete.responseCode());
        assertEquals("native", complete.standardOutputContent());
    }

    @Test
    void anotherNodeCannotConsumeTheTokenAndTheOwnerCanStillUseIt() throws Exception {
        String token = token(instance, ROOT);
        Receiver wrong = new Receiver();
        handshake(open("i-fedcba98765432100", ROOT, wrong), token);
        assertEquals(1008, wrong.closed.get(5, TimeUnit.SECONDS));
        Receiver owner = new Receiver();
        handshake(open(instance, ROOT, owner), token);
        Command command = ssm.sendCommand(request -> request.instanceIds(instance)
                .documentName("AWS-RunShellScript").parameters(Map.of("commands", List.of("true")))).command();
        JsonNode job = mapper.readTree(owner.next("agent_job").payload());
        assertTrue(job.path("JobId").asText().contains(command.commandId()));
    }

    @Test
    void anOutstandingLegacyPollCannotTakeTheGatewaysCommand() throws Exception {
        Command command = ssm.sendCommand(request -> request.instanceIds(instance)
                .documentName("AWS-RunShellScript").parameters(Map.of("commands", List.of("true")))).command();
        Receiver receiver = new Receiver();
        WebSocket socket = open(instance, ROOT, receiver);
        handshake(socket, token(instance, ROOT));
        JsonNode job = mapper.readTree(receiver.next("agent_job").payload());
        assertEquals("aws.ssm." + command.commandId() + "." + instance, job.path("JobId").asText());
        assertTrue(legacyMessages().isEmpty(), "Legacy polling raced an authenticated gateway delivery");
        socket.abort();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Map<String, Object>> messages = legacyMessages();
            assertEquals(1, messages.size());
            assertEquals(job.path("JobId").asText(), messages.getFirst().get("MessageId"));
        });
    }

    @Test
    void pluginResultsStaySeparateWhenTheDocumentSummaryArrivesFirst() throws Exception {
        String name = "channel-" + UUID.randomUUID();
        ssm.createDocument(request -> request.name(name).documentType("Command").content("""
                {"schemaVersion":"2.2","mainSteps":[
                  {"action":"aws:runShellScript","name":"first","inputs":{"runCommand":["echo first"]}},
                  {"action":"aws:runShellScript","name":"second","inputs":{"runCommand":["echo second"]}}
                ]}
                """));
        try {
            Command command = ssm.sendCommand(request -> request.instanceIds(instance).documentName(name)).command();
            Receiver receiver = new Receiver();
            WebSocket socket = open(instance, ROOT, receiver);
            handshake(socket, token(instance, ROOT));
            String job = mapper.readTree(receiver.next("agent_job").payload()).path("JobId").asText();
            reply(socket, job, """
                    {"documentStatus":"InProgress","runtimeStatus":{
                      "first":{"status":"Success","code":0,"standardOutput":"first output"}
                    }}
                    """);
            receiver.next("agent_job_reply_ack");
            reply(socket, job, "{\"documentStatus\":\"Success\"}");
            receiver.next("agent_job_reply_ack");
            GetCommandInvocationResponse pending = ssm.getCommandInvocation(request ->
                    request.commandId(command.commandId()).instanceId(instance).pluginName("second"));
            assertEquals("Pending", pending.statusAsString());
            assertEquals(-1, pending.responseCode());
            assertEquals("", pending.standardOutputContent());
            reply(socket, job, """
                    {"documentStatus":"InProgress","runtimeStatus":{
                      "second":{"status":"Success","code":0,"standardOutput":"second output"}
                    }}
                    """);
            receiver.next("agent_job_reply_ack");
            GetCommandInvocationResponse complete = ssm.getCommandInvocation(request ->
                    request.commandId(command.commandId()).instanceId(instance).pluginName("second"));
            assertEquals("Success", complete.statusAsString());
            assertEquals("second output", complete.standardOutputContent());
            assertEquals("Success", ssm.listCommands(request -> request.commandId(command.commandId()))
                    .commands().getFirst().statusAsString());
        } finally {
            ssm.deleteDocument(request -> request.name(name));
        }
    }

    @Test
    void wildcardOnlyChannelAuthorizationDoesNotAcceptAnInstanceResourceGrant() throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-resource-", "channel", """
                {"Statement":{"Effect":"Allow","Action":"ssmmessages:*",
                  "Resource":"arn:aws:ec2:us-east-1:000000000000:instance/*"}}
                """)) {
            given().filter(user.signer("ssmmessages")).contentType("application/json").body(schema())
                    .post(channel(instance)).then().statusCode(403);
        }
    }

    @Test
    void unsignedAndInvalidSignaturesCannotCreateChannels() {
        given().contentType("application/json").body(schema()).post(channel(instance)).then().statusCode(403);
        given().filter(ROOT.withSecret("incorrect")).contentType("application/json").body(schema())
                .post(channel(instance)).then().statusCode(403);
    }

    @Test
    void unsignedWebSocketUpgradeIsRejected() {
        URI uri = URI.create(channel(instance).toString().replaceFirst("^http", "ws") + "?role=subscribe&stream=input");
        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                http.newWebSocketBuilder().buildAsync(uri, new Receiver()).get(5, TimeUnit.SECONDS));
        WebSocketHandshakeException handshake = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
        assertEquals(403, handshake.getResponse().statusCode());
    }

    private GetCommandInvocationResponse invocation(Command command) {
        return ssm.getCommandInvocation(request -> request.commandId(command.commandId()).instanceId(instance));
    }

    private List<Map<String, Object>> legacyMessages() throws Exception {
        return given().filter(AwsRequestSigner.signedAs("test", "test", "ec2messages"))
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "EC2WindowsMessageDeliveryService.GetMessages")
                .body(mapper.writeValueAsBytes(
                        Map.of("Destination", instance, "MessagesRequestId", UUID.randomUUID().toString())))
                .post(endpoint).then().statusCode(200).extract().jsonPath().getList("Messages");
    }

    private URI channel(String node) {
        return endpoint.resolve("v1/control-channel/" + node);
    }

    private String schema() {
        return "{\"MessageSchemaVersion\":\"1.0\",\"RequestId\":\"" + UUID.randomUUID() + "\"}";
    }

    private String token(String node, AwsRequestSigner signer) {
        return given().filter(signer).contentType("application/json").body(schema()).post(channel(node))
                .then().statusCode(201).extract().xmlPath().getString("CreateControlChannelResponse.TokenValue");
    }

    private WebSocket open(String node, AwsRequestSigner signer, Receiver receiver) throws Exception {
        URI uri = URI.create(channel(node).toString().replaceFirst("^http", "ws") + "?role=subscribe&stream=input");
        WebSocket.Builder builder = http.newWebSocketBuilder();
        signer.headersFor("GET", uri, Map.of(), new byte[0]).forEach(builder::header);
        WebSocket socket = builder.buildAsync(uri, receiver).get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        return socket;
    }

    private void handshake(WebSocket socket, String token) throws Exception {
        socket.sendText(mapper.writeValueAsString(Map.of("MessageSchemaVersion", "1.0",
                "RequestId", UUID.randomUUID().toString(), "TokenValue", token)), true).get(5, TimeUnit.SECONDS);
    }

    private void reply(WebSocket socket, String job, String content) throws Exception {
        send(socket, "agent_job_reply", mapper.createObjectNode().put("jobId", job).put("content", content));
    }

    private void send(WebSocket socket, String type, JsonNode payload) throws Exception {
        socket.sendBinary(ByteBuffer.wrap(SsmAgentMessage.create(type, mapper.writeValueAsBytes(payload)).encode()), true)
                .get(5, TimeUnit.SECONDS);
    }

    private static final class Receiver implements WebSocket.Listener {
        private final BlockingQueue<SsmAgentMessage> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            partial.writeBytes(bytes);
            if (last) {
                messages.add(SsmAgentMessage.decode(partial.toByteArray()));
                partial.reset();
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closed.complete(status);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            closed.completeExceptionally(error);
        }

        private SsmAgentMessage next(String type) throws Exception {
            SsmAgentMessage message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "No " + type + " arrived");
            assertEquals(type, message.type());
            return message;
        }
    }
}
