package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ssm.model.SsmDocument;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The Session document a session starts with, as the Session document schema describes it: schema version
 * {@code 1.0}, a {@code sessionType}, optional {@code inputs}, {@code parameters} and {@code properties}.
 * The agent substitutes the parameters into the properties itself, so the content is passed on unchanged
 * with the resolved parameter values beside it.
 */
record SsmSessionDocument(String name, JsonNode content) {
    static final String DEFAULT_DOCUMENT = "SSM-SessionManagerRunShell";
    private static final Set<String> SESSION_TYPES =
            Set.of("Standard_Stream", "InteractiveCommands", "NonInteractiveCommands", "Port");
    private static final String STANDARD_STREAM = """
            {
              "schemaVersion": "1.0",
              "description": "Document to hold regional settings for Session Manager",
              "sessionType": "Standard_Stream",
              "inputs": {
                "s3BucketName": "", "s3KeyPrefix": "", "s3EncryptionEnabled": true,
                "cloudWatchLogGroupName": "", "cloudWatchEncryptionEnabled": true,
                "cloudWatchStreamingEnabled": true, "kmsKeyId": "",
                "runAsEnabled": false, "runAsDefaultUser": "", "idleSessionTimeout": "20",
                "maxSessionDuration": "", "shellProfile": {"windows": "", "linux": ""}
              }
            }
            """;
    private static final String COMMAND = """
            {
              "schemaVersion": "1.0",
              "description": "%s",
              "sessionType": "%s",
              "parameters": {"command": {"type": "String", "description": "The command to run."}},
              "properties": {
                "windows": {"commands": "{{ command }}", "runAsElevated": false},
                "linux": {"commands": "{{ command }}", "runAsElevated": false},
                "macos": {"commands": "{{ command }}", "runAsElevated": false}
              }
            }
            """;
    private static final String PORT = """
            {
              "schemaVersion": "1.0",
              "description": "Document to start a port forwarding session",
              "sessionType": "Port",
              "parameters": {
                %s
                "portNumber": {"type": "String", "default": "80"},
                "localPortNumber": {"type": "String", "default": "0"}
              },
              "properties": {
                %s
                "portNumber": "{{ portNumber }}",
                "type": "LocalPortForwarding",
                "localPortNumber": "{{ localPortNumber }}"
              }
            }
            """;
    /**
     * A non-interactive command can ask the agent to deliver stderr and the exit code apart from stdout
     * (amazon-ssm-agent 3.1.1511.0, "Support separate output stream for non-interactive session"; the
     * shell properties' {@code separateOutputStream}). The separator prefixes the agent also reads are not
     * declared: AWS publishes no document that declares them.
     */
    private static final String NON_INTERACTIVE_COMMAND = """
            {
              "schemaVersion": "1.0",
              "description": "Run one non-interactive command on a managed node",
              "sessionType": "NonInteractiveCommands",
              "parameters": {
                "command": {"type": "String", "description": "The command to run."},
                "separateOutputStream": {"type": "String", "default": "false", "allowedValues": ["true", "false"],
                  "description": "Deliver stderr and the exit code separately from stdout."}
              },
              "properties": {
                "windows": {"commands": "{{ command }}", "runAsElevated": false,
                  "separateOutputStream": "{{ separateOutputStream }}"},
                "linux": {"commands": "{{ command }}", "runAsElevated": false,
                  "separateOutputStream": "{{ separateOutputStream }}"},
                "macos": {"commands": "{{ command }}", "runAsElevated": false,
                  "separateOutputStream": "{{ separateOutputStream }}"}
              }
            }
            """;
    private static final Map<String, String> AWS_DOCUMENTS = Map.of(
            "AWS-StartNonInteractiveCommand", NON_INTERACTIVE_COMMAND,
            "AWS-StartInteractiveCommand", COMMAND.formatted(
                    "Run one interactive command on a managed node", "InteractiveCommands"),
            "AWS-StartPortForwardingSession", PORT.formatted("", ""),
            "AWS-StartPortForwardingSessionToRemoteHost", PORT.formatted(
                    "\"host\": {\"type\": \"String\"},", "\"host\": \"{{ host }}\","));

    /** AWS owns these documents; their ARNs have no account. */
    static boolean isAwsSessionDocument(String name) {
        return AWS_DOCUMENTS.containsKey(name);
    }

    /**
     * An omitted name selects {@code SSM-SessionManagerRunShell}, the account's session preferences. Without
     * stored preferences the documented defaults apply: no logging, no encryption, no Run As.
     */
    static SsmSessionDocument resolve(SsmService documents, ObjectMapper mapper, String requested, String region) {
        String name = requested == null || requested.isBlank() ? DEFAULT_DOCUMENT : requested;
        if (AWS_DOCUMENTS.containsKey(name)) {
            return new SsmSessionDocument(name, parse(mapper, AWS_DOCUMENTS.get(name)));
        }
        synchronized (documents) {
            SsmDocument stored;
            try {
                stored = documents.getDocument(name, region);
            } catch (AwsException missing) {
                if (DEFAULT_DOCUMENT.equals(name)) {
                    return new SsmSessionDocument(name, parse(mapper, STANDARD_STREAM));
                }
                throw invalidDocument();
            }
            if (!"Session".equals(stored.getDocumentType())) {
                throw new AwsException("InvalidDocument", "StartSession requires a Session document.", 400);
            }
            String version = SsmService.resolveDocumentVersion(stored, null);
            return new SsmSessionDocument(name, parse(mapper, stored.getContentForVersion(version)));
        }
    }

    String sessionType() {
        return content.path("sessionType").asText();
    }

    /**
     * The values the agent substitutes, one per declared parameter: a {@code String} parameter takes one value
     * and a {@code StringList} parameter the list. A missing parameter falls back to its default; one with
     * neither, an undeclared parameter, or a value outside {@code allowedValues} or {@code allowedPattern}
     * is refused.
     */
    ObjectNode parameters(ObjectMapper mapper, Map<String, List<String>> values) {
        JsonNode definitions = content.path("parameters");
        ObjectNode result = mapper.createObjectNode();
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            JsonNode definition = definitions.get(entry.getKey());
            if (definition == null || !definition.isObject()) {
                throw invalidParameters("The document does not declare the parameter " + entry.getKey() + ".");
            }
            String type = definition.path("type").asText("String").toLowerCase(Locale.ROOT);
            List<String> given = entry.getValue();
            if ("stringlist".equals(type)) {
                given.forEach(value -> check(entry.getKey(), definition, value));
                result.set(entry.getKey(), mapper.valueToTree(given));
            } else if ("string".equals(type) && given.size() == 1) {
                check(entry.getKey(), definition, given.getFirst());
                result.put(entry.getKey(), given.getFirst());
            } else {
                throw invalidParameters("The parameter " + entry.getKey() + " takes exactly one value.");
            }
        }
        for (Map.Entry<String, JsonNode> definition : definitions.properties()) {
            if (!values.containsKey(definition.getKey()) && !definition.getValue().has("default")) {
                throw invalidParameters("The required parameter " + definition.getKey() + " is missing.");
            }
        }
        return result;
    }

    private static void check(String name, JsonNode definition, String value) {
        JsonNode allowed = definition.get("allowedValues");
        if (allowed != null && allowed.isArray()) {
            boolean listed = false;
            for (JsonNode item : allowed) {
                listed |= value.equals(item.asText());
            }
            if (!listed) {
                throw invalidParameters("The value of " + name + " is not one of its allowed values.");
            }
        }
        String pattern = definition.path("allowedPattern").asText("");
        if (!pattern.isEmpty()) {
            boolean matches;
            try {
                matches = Pattern.compile(pattern).matcher(value).find();
            } catch (PatternSyntaxException invalid) {
                throw new AwsException("InvalidDocument", "The document's allowedPattern is not valid.", 400);
            }
            if (!matches) {
                throw invalidParameters("The value of " + name + " does not match its allowed pattern.");
            }
        }
    }

    private static JsonNode parse(ObjectMapper mapper, String raw) {
        JsonNode document;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(64 * 1024);
            options.setAllowDuplicateKeys(false);
            document = mapper.valueToTree(new Yaml(new SafeConstructor(options)).load(raw));
        } catch (RuntimeException invalid) {
            throw new AwsException("InvalidDocument", "The session document is not valid JSON or YAML.", 400);
        }
        if (document == null || !document.isObject() || !"1.0".equals(document.path("schemaVersion").asText())
                || !SESSION_TYPES.contains(document.path("sessionType").asText())) {
            throw new AwsException("InvalidDocument",
                    "A Session document needs schemaVersion 1.0 and a supported sessionType.", 400);
        }
        return document;
    }

    private static AwsException invalidDocument() {
        return new AwsException("InvalidDocument", "The SSM document doesn't exist or the document isn't "
                + "available to the user.", 400);
    }

    private static AwsException invalidParameters(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
