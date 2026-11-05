package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ssm.model.Command;
import io.github.hectorvent.floci.services.ssm.model.CommandInvocation;
import io.github.hectorvent.floci.services.ssm.model.SsmDocument;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SsmAgentCommandServiceTest {
    private static final String REGION = "us-east-1";
    private static final String INSTANCE = "i-0123456789abcdef0";
    private static final String CUSTOM = """
            {"schemaVersion":"2.2","parameters":{},"mainSteps":[
              {"action":"aws:runShellScript","name":"first","inputs":{"runCommand":["echo original"]}},
              {"action":"aws:runShellScript","name":"second","inputs":{"runCommand":["exit 42"]}}
            ]}
            """;
    private final ObjectMapper mapper = new ObjectMapper();
    private final InMemoryStorageFactory storage = new InMemoryStorageFactory("000000000000");
    private final SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
    private final SsmService documents = mock(SsmService.class);
    private final RegionResolver regions = mock(RegionResolver.class);
    private SsmCommandService service;
    private SsmDocument document;

    @BeforeEach
    void managedNodeAndDocument() {
        when(regions.getAccountId()).thenReturn("000000000000");
        when(regions.getRegion()).thenReturn(REGION);
        document = new SsmDocument("Custom", CUSTOM, "Command");
        when(documents.getDocument("Custom", REGION)).thenReturn(document);
        service = new SsmCommandService(storage, mapper, regions, executor, documents, false);
        service.updateInstanceInformation(mapper.createObjectNode().put("InstanceId", INSTANCE), REGION);
    }

    @AfterEach
    void stop() {
        service.stop();
    }

    @Test
    void agentModeDoesNotExecuteThroughDockerEvenWhenSupported() {
        when(executor.supports(any(), anyString(), anyString())).thenReturn(true);
        Command command = send("AWS-RunShellScript");
        assertEquals("Pending", invocation(command).getStatus());
        assertEquals(1, service.pendingAgentCommands(INSTANCE, REGION).size());
        verifyNoInteractions(executor);
    }

    @Test
    void requiresAManagedNodeInTheRequestRegion() {
        AwsException failure = assertThrows(AwsException.class,
                () -> service.sendCommand(request("AWS-RunShellScript"), "us-west-2"));
        assertEquals("InvalidInstanceId", failure.getErrorCode());
        assertTrue(service.listCommands(null, null, "us-west-2").isEmpty());
    }

    @Test
    void legacyPollingWaitsForTheCurrentGatewayConnectionToClose() {
        Command command = send("Custom");
        UUID first = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        service.messageGatewayOpened(INSTANCE, REGION, first);
        assertTrue(service.getMessages(INSTANCE, "legacy", 30).isEmpty());
        assertNull(invocation(command).getDeliveryAttemptTime());
        assertEquals(1, service.pendingAgentCommands(INSTANCE, REGION).size());
        service.messageGatewayOpened(INSTANCE, REGION, replacement);
        service.messageGatewayClosed(INSTANCE, REGION, first);
        assertTrue(service.getMessages(INSTANCE, "legacy", 30).isEmpty());
        service.messageGatewayClosed(INSTANCE, REGION, replacement);
        assertEquals(SsmCommandService.agentJobId(command.getCommandId(), INSTANCE),
                service.getMessages(INSTANCE, "legacy", 30).getFirst().get("MessageId"));
    }

    @Test
    void gatewaysAreScopedToTheirAccountRegionAndNode() {
        when(regions.getAccountId()).thenReturn("111111111111");
        service.messageGatewayOpened(INSTANCE, REGION, UUID.randomUUID());
        when(regions.getAccountId()).thenReturn("000000000000");
        service.messageGatewayOpened(INSTANCE, "us-west-2", UUID.randomUUID());
        service.messageGatewayOpened("i-fedcba98765432100", REGION, UUID.randomUUID());
        Command command = send("Custom");
        assertEquals(SsmCommandService.agentJobId(command.getCommandId(), INSTANCE),
                service.getMessages(INSTANCE, "legacy", 30).getFirst().get("MessageId"));
    }

    @Test
    void resetClearsEphemeralGatewayOwnership() {
        service.messageGatewayOpened(INSTANCE, REGION, UUID.randomUUID());
        service.clear();
        Command command = send("Custom");
        assertEquals(SsmCommandService.agentJobId(command.getCommandId(), INSTANCE),
                service.getMessages(INSTANCE, "legacy", 30).getFirst().get("MessageId"));
    }

    @Test
    void capturesTheSelectedCustomDocumentInsteadOfSynthesizingShellCommands() throws Exception {
        Command command = send("Custom");
        document.setContent(CUSTOM.replace("original", "changed"));
        JsonNode payload = mapper.readTree(service.pendingAgentCommands(INSTANCE, REGION).getFirst().content());
        assertAll(
                () -> assertEquals("1", command.getDocumentVersion()),
                () -> assertEquals("echo original", payload.at("/DocumentContent/mainSteps/0/inputs/runCommand/0").asText()),
                () -> assertEquals("exit 42", payload.at("/DocumentContent/mainSteps/1/inputs/runCommand/0").asText()),
                () -> assertEquals(command.getCommandId(), payload.path("CommandId").asText()),
                () -> assertEquals("aws.ssm." + command.getCommandId() + "." + INSTANCE,
                        service.pendingAgentCommands(INSTANCE, REGION).getFirst().jobId()));
    }

    @Test
    void doesNotCompleteTheDocumentWhenOnlyTheFirstPluginCompletes() throws Exception {
        Command command = send("Custom");
        reply(command, """
                {"documentStatus":"InProgress","runtimeStatus":{
                  "first":{"status":"Success","code":0,"standardOutput":"first output"}
                }}
                """);
        assertEquals("InProgress", invocation(command).getStatus());
        assertEquals("Pending", invocation(command).getPluginResults().get("second").path("status").asText());
        reply(command, """
                {"documentStatus":"Failed","runtimeStatus":{
                  "second":{"status":"Failed","code":42,"standardError":"second error"}
                }}
                """);
        assertAll(
                () -> assertEquals("Failed", invocation(command).getStatus()),
                () -> assertEquals("first output",
                        invocation(command).getPluginResults().get("first").path("standardOutput").asText()),
                () -> assertEquals(42, invocation(command).getPluginResults().get("second").path("code").asInt()),
                () -> assertEquals("Failed", service.listCommands(command.getCommandId(), null, REGION).getFirst().getStatus()));
    }

    @Test
    void anInitialDocumentReplyDoesNotClaimSuccessOrConsumeTheFinalReply() throws Exception {
        Command command = send("AWS-RunShellScript");
        reply(command, "{\"documentStatus\":\"InProgress\"}");
        assertEquals("InProgress", invocation(command).getStatus());
        assertEquals(-1, invocation(command).getResponseCode());
        reply(command, """
                {"documentStatus":"Success","runtimeStatus":{
                  "runShellScript":{"status":"Success","code":0,"standardOutput":"done"}
                }}
                """);
        assertEquals("Success", invocation(command).getStatus());
        assertEquals("done", invocation(command).getStandardOutputContent());
    }

    @Test
    void pluginRepliesCanArriveAfterTheDocumentSummaryWithoutReopeningIt() throws Exception {
        Command command = send("Custom");
        reply(command, """
                {"documentStatus":"InProgress","runtimeStatus":{
                  "first":{"status":"Success","code":0,"standardOutput":"first output"}
                }}
                """);
        reply(command, "{\"documentStatus\":\"Success\"}");
        Instant ended = invocation(command).getExecutionEndDateTime();
        ObjectMapper storageMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        CommandInvocation restored = storageMapper.readValue(storageMapper.writeValueAsBytes(invocation(command)),
                CommandInvocation.class);
        assertTrue(restored.isAgentTerminalResponse());
        reply(command, """
                {"documentStatus":"InProgress","runtimeStatus":{
                  "first":{"status":"InProgress","code":-1},
                  "second":{"status":"Success","code":0,"standardOutput":"second output"}
                }}
                """);
        assertAll(
                () -> assertEquals("Success", invocation(command).getStatus()),
                () -> assertEquals(ended, invocation(command).getExecutionEndDateTime()),
                () -> assertEquals("first output",
                        invocation(command).getPluginResults().get("first").path("standardOutput").asText()),
                () -> assertEquals("second output",
                        invocation(command).getPluginResults().get("second").path("standardOutput").asText()));
    }

    @Test
    void malformedLegacyRepliesDoNotLoseTheirCorrelation() {
        Command command = send("AWS-RunShellScript");
        Map<String, Object> message = service.getMessages(INSTANCE, "request", 30).getFirst();
        String id = (String) message.get("MessageId");
        assertThrows(AwsException.class, () -> service.sendReply(id, "not json"));
        service.sendReply(id, """
                {"documentStatus":"Success","runtimeStatus":{
                  "runShellScript":{"status":"Success","code":0,"standardOutput":"recovered"}
                }}
                """);
        assertEquals("recovered", invocation(command).getStandardOutputContent());
    }

    @Test
    void disconnectedNodesExpireWithoutExecutingACommand() {
        Command command = send("AWS-RunShellScript");
        service.listCommands(command.getCommandId(), null, REGION).getFirst().setExpiresAfter(Instant.EPOCH);
        assertEquals("TimedOut", invocation(command).getStatus());
        assertEquals("Delivery Timed Out", invocation(command).getStatusDetails());
        assertTrue(service.pendingAgentCommands(INSTANCE, REGION).isEmpty());
        verifyNoInteractions(executor);
    }

    @Test
    void aDifferentNodeOrRegionCannotReplyForTheInvocation() {
        Command command = send("Custom");
        String job = SsmCommandService.agentJobId(command.getCommandId(), INSTANCE);
        assertThrows(AwsException.class, () -> service.agentReply("i-fedcba98765432100", job,
                mapper.createObjectNode().put("documentStatus", "Success"), REGION));
        assertThrows(AwsException.class, () -> service.agentReply(INSTANCE, job,
                mapper.createObjectNode().put("documentStatus", "Success"), "us-west-2"));
        assertEquals("Pending", invocation(command).getStatus());
    }

    @Test
    void pendingCommandsSurviveReplacementOfTheChannelService() {
        Command command = send("Custom");
        service.stop();
        service = new SsmCommandService(storage, mapper, regions, executor, documents, false);
        assertEquals(SsmCommandService.agentJobId(command.getCommandId(), INSTANCE),
                service.pendingAgentCommands(INSTANCE, REGION).getFirst().jobId());
    }

    @Test
    void cancelledCommandsCannotBeResurrectedByLateReplies() throws Exception {
        Command command = send("Custom");
        service.cancelCommand(command.getCommandId(), null, REGION);
        reply(command, "{\"documentStatus\":\"Success\"}");
        assertEquals("Cancelled", invocation(command).getStatus());
        assertTrue(service.pendingAgentCommands(INSTANCE, REGION).isEmpty());
    }

    @Test
    void cancelledSelectionIsRecheckedBeforeDelivery() {
        Command command = send("Custom");
        String selected = service.pendingAgentCommands(INSTANCE, REGION).getFirst().jobId();
        service.cancelCommand(command.getCommandId(), null, REGION);
        assertFalse(service.deliverAgentCommand(INSTANCE, selected, REGION,
                ignored -> fail("A cancelled command was sent")));
        Command summary = service.listCommands(command.getCommandId(), null, REGION).getFirst();
        assertEquals("Cancelled", summary.getStatus());
        assertEquals(0, summary.getErrorCount());
    }

    @Test
    void sentCommandWaitsForTheAgentToConfirmCancellation() throws Exception {
        Command command = send("Custom");
        String original = SsmCommandService.agentJobId(command.getCommandId(), INSTANCE);
        assertTrue(service.deliverAgentCommand(INSTANCE, original, REGION, ignored -> { }));
        service.cancelCommand(command.getCommandId(), null, REGION);
        assertEquals("Cancelling", invocation(command).getStatus());
        SsmCommandService.AgentCommand cancel = service.pendingAgentCommands(INSTANCE, REGION).getFirst();
        assertEquals("aws.ssm.cancelCommand", cancel.topic());
        assertEquals(original, mapper.readTree(cancel.content()).path("CancelMessageId").asText());
        assertNotEquals(original, cancel.jobId());
        service.acknowledgeAgentCommand(INSTANCE, cancel.jobId(), REGION);
        assertTrue(service.pendingAgentCommands(INSTANCE, REGION).isEmpty());
        reply(command, "{\"documentStatus\":\"InProgress\"}");
        assertEquals("Cancelling", invocation(command).getStatus());
        reply(command, "{\"documentStatus\":\"Cancelled\"}");
        assertEquals("Cancelled", invocation(command).getStatus());
        assertEquals("Cancelled", service.listCommands(command.getCommandId(), null, REGION).getFirst().getStatus());
    }

    @Test
    void cancellationDeliverySurvivesServiceReplacement() {
        Command command = send("Custom");
        service.deliverAgentCommand(INSTANCE, SsmCommandService.agentJobId(command.getCommandId(), INSTANCE),
                REGION, ignored -> { });
        service.cancelCommand(command.getCommandId(), null, REGION);
        String cancellation = service.pendingAgentCommands(INSTANCE, REGION).getFirst().jobId();
        service.stop();
        service = new SsmCommandService(storage, mapper, regions, executor, documents, false);
        assertEquals(cancellation, service.pendingAgentCommands(INSTANCE, REGION).getFirst().jobId());
    }

    @Test
    void aMissingTerminalReplyExpiresEvenAfterAcknowledgement() throws Exception {
        Command command = send("AWS-RunShellScript");
        reply(command, """
                {"documentStatus":"InProgress","runtimeStatus":{"runShellScript":{"status":"InProgress","code":0}}}
                """);
        service.listCommands(command.getCommandId(), null, REGION).getFirst().setExpiresAfter(Instant.EPOCH);
        assertEquals("Delivery Timed Out", invocation(command).getStatusDetails());
        assertEquals("TimedOut", invocation(command).getPluginResults().get("runShellScript").path("status").asText());
        reply(command, "{\"documentStatus\":\"Success\"}");
        Command summary = service.listCommands(command.getCommandId(), null, REGION).getFirst();
        assertEquals("TimedOut", summary.getStatus());
        assertEquals(1, summary.getDeliveryTimedOutCount());
        assertEquals(0, summary.getErrorCount());
    }

    @Test
    void convertsPublicStringListsIntoTheDeclaredAgentParameterTypes() throws Exception {
        String content = CUSTOM.replace("\"parameters\":{}",
                "\"parameters\":{\"Message\":{\"type\":\"String\"},\"Items\":{\"type\":\"StringList\"}}");
        when(documents.getDocument("Custom", REGION)).thenReturn(new SsmDocument("Custom", content, "Command"));
        ObjectNode request = request("Custom");
        ObjectNode parameters = request.putObject("Parameters");
        parameters.putArray("Message").add("one string");
        parameters.putArray("Items").add("first").add("second");
        service.sendCommand(request, REGION);
        JsonNode payload = mapper.readTree(service.pendingAgentCommands(INSTANCE, REGION).getFirst().content());
        assertTrue(payload.at("/Parameters/Message").isTextual());
        assertEquals("one string", payload.at("/Parameters/Message").asText());
        assertEquals(mapper.readTree("[\"first\",\"second\"]"), payload.at("/Parameters/Items"));
    }

    @Test
    void invalidParametersAreRejectedBeforeCreatingCommands() {
        String content = CUSTOM.replace("\"parameters\":{}", "\"parameters\":{\"Message\":{\"type\":\"String\"}}");
        when(documents.getDocument("Custom", REGION)).thenReturn(new SsmDocument("Custom", content, "Command"));
        assertThrows(AwsException.class, () -> send("Custom"));
        ObjectNode request = request("Custom");
        request.putObject("Parameters").putArray("Message").add("one").add("two");
        assertThrows(AwsException.class, () -> service.sendCommand(request, REGION));
        assertTrue(service.listCommands(null, null, REGION).isEmpty());
    }

    private Command send(String name) {
        return service.sendCommand(request(name), REGION);
    }

    private ObjectNode request(String name) {
        ObjectNode request = mapper.createObjectNode().put("DocumentName", name).put("TimeoutSeconds", 30);
        request.putArray("InstanceIds").add(INSTANCE);
        if ("AWS-RunShellScript".equals(name)) {
            request.putObject("Parameters").putArray("commands").add("echo hello");
        }
        return request;
    }

    private CommandInvocation invocation(Command command) {
        return service.getCommandInvocation(command.getCommandId(), INSTANCE, REGION);
    }

    private void reply(Command command, String payload) throws Exception {
        service.agentReply(INSTANCE, SsmCommandService.agentJobId(command.getCommandId(), INSTANCE),
                mapper.readTree(payload), REGION);
    }
}
