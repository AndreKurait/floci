package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ssm.model.SsmDocument;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.List;
import java.util.Map;

/** A command captures its selected version before the document can be updated or deleted. */
record SsmCommandDocument(String version, JsonNode content) {
    private static final String SHELL_DOCUMENT = """
            {
              "schemaVersion": "2.2",
              "description": "Run a script",
              "parameters": {
                "commands": {"type": "StringList"},
                "workingDirectory": {"type": "String", "default": ""},
                "executionTimeout": {"type": "String", "default": "3600"}
              },
              "mainSteps": [{
                "action": "%s",
                "name": "%s",
                "inputs": {
                  "runCommand": "{{ commands }}",
                  "workingDirectory": "{{ workingDirectory }}",
                  "timeoutSeconds": "{{ executionTimeout }}"
                }
              }]
            }
            """;

    static SsmCommandDocument resolve(SsmService documents, ObjectMapper mapper, String name,
                                      String requestedVersion, String region) {
        if ("AWS-RunShellScript".equals(name) || "AWS-RunPowerShellScript".equals(name)) {
            if (requestedVersion != null && !requestedVersion.isBlank()
                    && !"$DEFAULT".equals(requestedVersion) && !"$LATEST".equals(requestedVersion)
                    && !"1".equals(requestedVersion)) {
                throw new AwsException("InvalidDocumentVersion", "The document version does not exist.", 400);
            }
            String plugin = "AWS-RunPowerShellScript".equals(name) ? "runPowerShellScript" : "runShellScript";
            return new SsmCommandDocument("1", parse(mapper, SHELL_DOCUMENT.formatted("aws:" + plugin, plugin)));
        }
        if (documents == null) {
            throw new AwsException("InvalidDocument", "The command document does not exist.", 400);
        }
        synchronized (documents) {
            SsmDocument document = documents.getDocument(name, region);
            if (!"Command".equals(document.getDocumentType())) {
                throw new AwsException("InvalidDocument", "SendCommand requires a Command document.", 400);
            }
            String version = SsmService.resolveDocumentVersion(document, requestedVersion);
            return new SsmCommandDocument(version, parse(mapper, document.getContentForVersion(version)));
        }
    }

    static ObjectNode parametersForAgent(ObjectMapper mapper, JsonNode document, Map<String, List<String>> values) {
        JsonNode definitions = document.path("parameters");
        for (Map.Entry<String, JsonNode> entry : definitions.properties()) {
            if (!values.containsKey(entry.getKey()) && !entry.getValue().has("default")) {
                throw new AwsException("InvalidParameters", "Missing document parameter: " + entry.getKey(), 400);
            }
        }
        ObjectNode result = mapper.createObjectNode();
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            String type = definitions.path(entry.getKey()).path("type").asText();
            if ("String".equals(type) && entry.getValue().size() == 1) {
                result.put(entry.getKey(), entry.getValue().getFirst());
            } else if ("StringList".equals(type)) {
                result.set(entry.getKey(), mapper.valueToTree(entry.getValue()));
            } else {
                throw new AwsException("InvalidParameters", "Invalid document parameter: " + entry.getKey(), 400);
            }
        }
        return result;
    }

    static int executionAllowance(JsonNode document, ObjectNode parameters) {
        JsonNode value = parameters.has("executionTimeout") ? parameters.path("executionTimeout")
                : document.path("parameters").path("executionTimeout").path("default");
        String seconds = value.asText("");
        if (seconds.matches("[1-9][0-9]{0,5}")) {
            return Integer.parseInt(seconds);
        }
        return 3600;
    }

    private static JsonNode parse(ObjectMapper mapper, String content) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(64 * 1024);
            options.setAllowDuplicateKeys(false);
            JsonNode document = mapper.valueToTree(new Yaml(new SafeConstructor(options)).load(content));
            if (document == null || !document.isObject()) {
                throw new IllegalArgumentException("The document must be an object.");
            }
            return document;
        } catch (RuntimeException invalid) {
            throw new AwsException("InvalidDocument", "The command document is not valid JSON or YAML.", 400);
        }
    }
}
