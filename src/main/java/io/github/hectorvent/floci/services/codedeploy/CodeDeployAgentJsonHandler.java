package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** The PascalCase wire protocol used by the CodeDeploy host agent. */
@ApplicationScoped
public class CodeDeployAgentJsonHandler {

    private final CodeDeployAgentCommands commands;

    @Inject
    public CodeDeployAgentJsonHandler(CodeDeployAgentCommands commands) {
        this.commands = commands;
    }

    public Response handle(String action, JsonNode request, String region, String account) {
        return switch (action) {
            case "PollHostCommand" -> Response.ok(commands.poll(account, region,
                    required(request, "HostIdentifier"))).build();
            case "PutHostCommandAcknowledgement" -> {
                diagnostics(request);
                yield Response.ok(commands.acknowledge(account, region,
                        required(request, "HostCommandIdentifier"))).build();
            }
            case "GetDeploymentSpecification" -> Response.ok(commands.specification(account, region,
                    required(request, "DeploymentExecutionId"), required(request, "HostIdentifier"))).build();
            case "PostHostCommandUpdate" -> {
                diagnostics(request);
                yield Response.ok(commands.update(account, region,
                        required(request, "HostCommandIdentifier"))).build();
            }
            case "PutHostCommandComplete" -> {
                commands.complete(account, region, required(request, "HostCommandIdentifier"),
                        required(request, "CommandStatus"), diagnostics(request));
                yield Response.ok(Map.of()).build();
            }
            default -> throw new AwsException("UnknownOperationException",
                    "Unsupported CodeDeploy agent operation: " + action, 400);
        };
    }

    private static String required(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new AwsException("ValidationException", field + " must be a nonempty string", 400);
        }
        return value.textValue();
    }

    private static String diagnostics(JsonNode request) {
        JsonNode envelope = request.get("Diagnostics");
        if (envelope == null || envelope.isNull()) {
            return null;
        }
        String format = required(envelope, "Format");
        String payload = required(envelope, "Payload");
        if (format.length() > 64 || payload.length() > 8192 || !"JSON".equals(format)) {
            throw new AwsException("ValidationException", "Invalid JSON diagnostics envelope", 400);
        }
        return payload;
    }
}
