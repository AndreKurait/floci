package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ssm.model.SsmDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SsmSessionDocumentTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void awsOwnedDocumentsAreBuiltInAndTakeTheirDocumentedParameters() {
        SsmService documents = mock(SsmService.class);
        SsmSessionDocument command = SsmSessionDocument.resolve(documents, mapper,
                "AWS-StartNonInteractiveCommand", "us-east-1");
        assertEquals("NonInteractiveCommands", command.sessionType());
        assertEquals("{{ command }}", command.content().at("/properties/linux/commands").asText());
        assertEquals("printf hi", command.parameters(mapper, Map.of("command", List.of("printf hi")))
                .path("command").asText());
        SsmSessionDocument port = SsmSessionDocument.resolve(documents, mapper,
                "AWS-StartPortForwardingSession", "us-east-1");
        assertEquals("Port", port.sessionType());
        assertEquals("LocalPortForwarding", port.content().at("/properties/type").asText());
        assertEquals("443", port.parameters(mapper, Map.of("portNumber", List.of("443"))).path("portNumber").asText());
        assertEquals("{{ host }}", SsmSessionDocument.resolve(documents, mapper,
                "AWS-StartPortForwardingSessionToRemoteHost", "us-east-1").content().at("/properties/host").asText());
        assertTrue(SsmService.isAwsSessionDocument("AWS-StartInteractiveCommand"));
        assertFalse(SsmService.isAwsSessionDocument("SSM-SessionManagerRunShell"));
        verify(documents, never()).getDocument(anyString(), anyString());
    }

    @Test
    void theDefaultDocumentIsTheAccountsPreferencesOrTheDocumentedDefaults() {
        SsmService documents = mock(SsmService.class);
        when(documents.getDocument("SSM-SessionManagerRunShell", "us-east-1"))
                .thenThrow(new AwsException("InvalidDocument", "missing", 400));
        SsmSessionDocument defaults = SsmSessionDocument.resolve(documents, mapper, null, "us-east-1");
        assertEquals("SSM-SessionManagerRunShell", defaults.name());
        assertEquals("Standard_Stream", defaults.sessionType());
        assertEquals("", defaults.content().at("/inputs/kmsKeyId").asText());
        assertFalse(defaults.content().at("/inputs/runAsEnabled").asBoolean());

        SsmDocument stored = document("Session", """
                {"schemaVersion":"1.0","sessionType":"Standard_Stream","inputs":{"runAsEnabled":true,"runAsDefaultUser":"ops"}}
                """);
        SsmService withPreferences = mock(SsmService.class);
        when(withPreferences.getDocument("SSM-SessionManagerRunShell", "us-east-1")).thenReturn(stored);
        assertEquals("ops", SsmSessionDocument.resolve(withPreferences, mapper, "", "us-east-1")
                .content().at("/inputs/runAsDefaultUser").asText());
    }

    @Test
    void onlySessionDocumentsOfTheSchemaCanStartASession() {
        SsmService documents = mock(SsmService.class);
        when(documents.getDocument("missing", "us-east-1")).thenThrow(new AwsException("InvalidDocument", "x", 400));
        when(documents.getDocument("command", "us-east-1")).thenReturn(document("Command",
                "{\"schemaVersion\":\"2.2\",\"mainSteps\":[]}"));
        when(documents.getDocument("old", "us-east-1")).thenReturn(document("Session",
                "{\"schemaVersion\":\"0.3\",\"sessionType\":\"Port\"}"));
        when(documents.getDocument("odd", "us-east-1")).thenReturn(document("Session",
                "{\"schemaVersion\":\"1.0\",\"sessionType\":\"Telnet\"}"));
        for (String name : List.of("missing", "command", "old", "odd")) {
            AwsException refused = assertThrows(AwsException.class, () ->
                    SsmSessionDocument.resolve(documents, mapper, name, "us-east-1"), name);
            assertEquals("InvalidDocument", refused.getErrorCode(), name);
        }
    }

    @Test
    void parametersFollowTheirDeclaredTypesDefaultsAndConstraints() {
        SsmService documents = mock(SsmService.class);
        when(documents.getDocument("logs", "us-east-1")).thenReturn(document("Session", """
                schemaVersion: '1.0'
                sessionType: InteractiveCommands
                parameters:
                  logpath: {type: string, default: /var/log/a.log, allowedPattern: '^[a-z/.]+$'}
                  files: {type: StringList, default: []}
                  level: {type: String, allowedValues: [info, debug]}
                properties:
                  linux: {commands: 'tail {{ logpath }}', runAsElevated: true}
                """));
        SsmSessionDocument logs = SsmSessionDocument.resolve(documents, mapper, "logs", "us-east-1");
        ObjectNode resolved = logs.parameters(mapper, Map.of("level", List.of("debug"),
                "files", List.of("/a", "/b")));
        assertEquals("debug", resolved.path("level").asText());
        assertEquals(2, resolved.path("files").size());
        assertFalse(resolved.has("logpath"), "A default is left for the agent to apply");
        for (Map<String, List<String>> invalid : List.of(
                Map.<String, List<String>>of(),
                Map.of("level", List.of("trace")),
                Map.of("level", List.of("info"), "logpath", List.of("/var/log/$(id)")),
                Map.of("level", List.of("info", "debug")),
                Map.of("level", List.of("info"), "undeclared", List.of("x")))) {
            AwsException refused = assertThrows(AwsException.class, () -> logs.parameters(mapper, invalid),
                    invalid.toString());
            assertEquals("ValidationException", refused.getErrorCode(), invalid.toString());
        }
    }

    private static SsmDocument document(String type, String content) {
        SsmDocument document = new SsmDocument();
        document.setDocumentType(type);
        document.setContent(content);
        document.setDocumentVersion(1);
        document.setDefaultVersion(1L);
        document.getVersions().put("1", content);
        return document;
    }
}
