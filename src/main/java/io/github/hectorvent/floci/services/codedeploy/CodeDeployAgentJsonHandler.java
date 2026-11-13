package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** The PascalCase wire protocol used by the CodeDeploy host agent. */
@ApplicationScoped
public class CodeDeployAgentJsonHandler {

    private final CodeDeployAgentCommands commands;
    private final EmulatorConfig config;
    private final RequestContext context;
    private final IamService iam;

    @Inject
    public CodeDeployAgentJsonHandler(CodeDeployAgentCommands commands, EmulatorConfig config,
                                      RequestContext context, IamService iam) {
        this.commands = commands;
        this.config = config;
        this.context = context;
        this.iam = iam;
    }

    public Response handle(String action, JsonNode request, String region, String account,
                            boolean instanceAuthentication) {
        String host = authenticatedHost(region, account, instanceAuthentication);
        return switch (action) {
            case "PollHostCommand" -> Response.ok(commands.poll(account, region,
                    requestedHost(request, host))).build();
            case "PutHostCommandAcknowledgement" -> {
                diagnostics(request);
                yield Response.ok(commands.acknowledge(account, region,
                        commandIdentifier(request, account, region, host))).build();
            }
            case "GetDeploymentSpecification" -> Response.ok(commands.specification(account, region,
                    required(request, "DeploymentExecutionId"), requestedHost(request, host))).build();
            case "PostHostCommandUpdate" -> {
                diagnostics(request);
                yield Response.ok(commands.update(account, region,
                        commandIdentifier(request, account, region, host))).build();
            }
            case "PutHostCommandComplete" -> {
                commands.complete(account, region, commandIdentifier(request, account, region, host),
                        required(request, "CommandStatus"), diagnostics(request));
                yield Response.ok(Map.of()).build();
            }
            default -> throw new AwsException("UnknownOperationException",
                    "Unsupported CodeDeploy agent operation: " + action, 400);
        };
    }

    private String authenticatedHost(String region, String account, boolean instanceAuthentication) {
        if (!instanceAuthentication || !config.services().iam().enforcementEnabled()) {
            return null;
        }
        SessionCredential verified = context.getVerifiedSession();
        if (verified == null || iam.ec2InstanceProfileSession(context.getAccessKeyId())
                .filter(current -> current == verified).isEmpty()
                || !AwsArnUtils.isArn(verified.getEc2InstanceArn())) {
            throw denied();
        }
        AwsArnUtils.Arn host = AwsArnUtils.parse(verified.getEc2InstanceArn());
        if (!"ec2".equals(host.service()) || !host.partition().equals(AwsRegions.partitionFor(region))
                || !host.region().equals(region) || !host.accountId().equals(account)
                || !account.equals(verified.getOriginAccountId())
                || !host.resource().equals("instance/" + verified.getEc2InstanceId())) {
            throw denied();
        }
        return host.toString();
    }

    private static String requestedHost(JsonNode request, String authenticatedHost) {
        String host = required(request, "HostIdentifier");
        if (authenticatedHost != null && !authenticatedHost.equals(host)) {
            throw denied();
        }
        return host;
    }

    private String commandIdentifier(JsonNode request, String account, String region, String host) {
        String identifier = required(request, "HostCommandIdentifier");
        if (host != null) {
            commands.authorizeHostCommand(account, region, identifier, host);
        }
        return identifier;
    }

    private static AwsException denied() {
        return new AwsException("AccessDeniedException",
                "The request requires credentials issued to this EC2 instance profile", 403);
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
