package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssm.model.Command;
import io.github.hectorvent.floci.services.ssm.model.CommandInvocation;
import io.github.hectorvent.floci.services.ssm.model.InstanceInformation;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Handles SSM agent registration and command execution lifecycle:
 * - UpdateInstanceInformation (agent side, via AmazonSSM target)
 * - SendCommand / GetCommandInvocation / ListCommands / ListCommandInvocations / CancelCommand (public API)
 * - GetMessages / AcknowledgeMessage / SendReply / FailMessage / DeleteMessage (ec2messages, agent side)
 */
@ApplicationScoped
public class SsmCommandService implements Resettable {

    private static final Logger LOG = Logger.getLogger(SsmCommandService.class);
    private static final int MIN_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 2_592_000;
    private static final int MAX_INSTANCE_IDS = 50;
    private static final int DEFAULT_SHELL_EXECUTION_TIMEOUT_SECONDS = 3600;
    private static final int MAX_SHELL_EXECUTION_TIMEOUT_SECONDS = 172800;
    static final int MAX_STDOUT_CHARS = 24000;
    static final int MAX_STDERR_CHARS = 8000;

    private final StorageBackend<String, InstanceInformation> instanceStore;
    private final StorageBackend<String, Command> commandStore;
    private final StorageBackend<String, CommandInvocation> invocationStore;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final SsmDirectCommandExecutor directCommandExecutor;
    private final ExecutorService directExecutionExecutor;
    private final SsmService documents;
    private final boolean directCommandExecutionEnabled;

    // In-memory queues are owned by the issuing account, region and instance. Lost on restart.
    private final ConcurrentHashMap<MessageOwner, Queue<PendingMessage>> messageQueues = new ConcurrentHashMap<>();
    // Message correlations retain the same owner through every agent operation.
    private final ConcurrentHashMap<String, MessageCorrelation> messageIndex = new ConcurrentHashMap<>();
    private final Map<MessageOwner, UUID> messageGatewayConnections = new HashMap<>();

    @Inject
    public SsmCommandService(
            StorageFactory storageFactory,
            ObjectMapper objectMapper,
            RegionResolver regionResolver,
            SsmDirectCommandExecutor directCommandExecutor,
            SsmService documents,
            EmulatorConfig config) {
        this(storageFactory, objectMapper, regionResolver, directCommandExecutor, documents,
                config.services().ssm().directCommandExecutionEnabled());
    }

    SsmCommandService(StorageFactory storageFactory, ObjectMapper objectMapper, RegionResolver regionResolver,
                      SsmDirectCommandExecutor directCommandExecutor, SsmService documents,
                      boolean directCommandExecutionEnabled) {
        this.instanceStore = storageFactory.create("ssm", "ssm-instances.json", new TypeReference<>() {});
        this.commandStore = storageFactory.create("ssm", "ssm-commands.json", new TypeReference<>() {});
        this.invocationStore = storageFactory.create("ssm", "ssm-invocations.json", new TypeReference<>() {});
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.directCommandExecutor = directCommandExecutor;
        this.documents = documents;
        this.directCommandExecutionEnabled = directCommandExecutionEnabled;
        // Each direct execution blocks for the whole container exec, up to its execution budget, so every
        // target gets its own virtual thread instead of a platform thread.
        this.directExecutionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("floci-ssm-direct-execution").factory());
    }

    @PreDestroy
    void stop() {
        directExecutionExecutor.shutdownNow();
    }

    public void clear() {
        messageQueues.clear();
        messageIndex.clear();
        synchronized (messageGatewayConnections) {
            messageGatewayConnections.clear();
        }
    }

    // ── Agent registration ──────────────────────────────────────────────────

    public void updateInstanceInformation(JsonNode request, String region) {
        String instanceId = request.path("InstanceId").asText("");
        if (instanceId.isEmpty()) {
            // Some older agent versions don't send InstanceId; fall back to a generated key
            instanceId = "mi-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        }

        InstanceInformation info = instanceStore.get(instanceKey(region, instanceId))
                .orElse(new InstanceInformation());

        info.setInstanceId(instanceId);
        info.setAgentName(request.path("AgentName").asText("amazon-ssm-agent"));
        info.setAgentVersion(request.path("AgentVersion").asText("3.0.0.0"));
        info.setPingStatus("Online");
        info.setLastPingDateTime(Instant.now());
        info.setPlatformType(request.path("PlatformType").asText("Linux"));
        info.setPlatformName(request.path("PlatformName").asText(""));
        info.setPlatformVersion(request.path("PlatformVersion").asText(""));
        info.setIpAddress(request.path("IPAddress").asText(""));
        info.setComputerName(request.path("Hostname").asText(instanceId));
        info.setRegion(region);

        boolean firstRegistration = info.getRegistrationDate() == null;
        if (firstRegistration) {
            info.setRegistrationDate(Instant.now());
        }

        instanceStore.put(instanceKey(region, instanceId), info);
        LOG.infov("SSM agent registered: instanceId={0} platform={1}/{2}", instanceId, info.getPlatformType(), info.getPlatformName());

        // Registering with SSM does not create an EC2 instance record. Make it obvious when the
        // agent lives in a container Floci did not launch through RunInstances: SendCommand falls
        // back to agent polling and IMDS (169.254.169.254) has nothing to serve for that container.
        if (directCommandExecutionEnabled && firstRegistration && !directCommandExecutor.isContainerBacked(instanceId)) {
            LOG.warnv("SSM managed instance {0} is not backed by a running Floci EC2 container. "
                    + "SendCommand will use the agent polling flow and IMDS will not answer for it. "
                    + "To get both, launch the container with EC2 RunInstances before registering its SSM agent.",
                    instanceId);
        }
    }

    public List<InstanceInformation> describeInstanceInformation(String region) {
        String prefix = region + "::";
        return instanceStore.scan(k -> k.startsWith(prefix));
    }

    void requireManagedInstance(String instanceId, String region) {
        if (instanceStore.get(instanceKey(region, instanceId)).isEmpty()) {
            throw new AwsException("InvalidInstanceId", "The managed instance is not registered in this account and region.", 400);
        }
    }

    // ── Public SendCommand API ──────────────────────────────────────────────

    public Command sendCommand(JsonNode request, String region) {
        String documentName = request.path("DocumentName").asText();
        if (documentName.isEmpty()) {
            throw new AwsException("InvalidDocument", "DocumentName is required.", 400);
        }

        List<String> instanceIds = new ArrayList<>();
        request.path("InstanceIds").forEach(n -> instanceIds.add(n.asText()));
        if (instanceIds.isEmpty()) {
            throw new AwsException("InvalidInstanceId", "At least one InstanceId is required.", 400);
        }
        if (instanceIds.size() > MAX_INSTANCE_IDS) {
            throw SmithyValidationErrors.validationException(
                    "Value at 'instanceIds' failed to satisfy constraint: Member must have length less than or equal to " + MAX_INSTANCE_IDS);
        }
        if (!directCommandExecutionEnabled) {
            instanceIds.forEach(instanceId -> requireManagedInstance(instanceId, region));
        }

        boolean publicShell = "AWS-RunShellScript".equals(documentName)
                || "AWS-RunPowerShellScript".equals(documentName);
        Map<String, List<String>> parameters = publicShell
                ? parseShellParameters(request.path("Parameters")) : parseParameters(request.path("Parameters"));
        String comment = request.path("Comment").asText("");
        int timeoutSeconds = SsmJsonDeserialization.optionalInteger(request.path("TimeoutSeconds"), 3600);
        validateTimeoutSeconds(timeoutSeconds);
        SsmCommandDocument selected = SsmCommandDocument.resolve(documents, objectMapper, documentName,
                request.path("DocumentVersion").asText("$DEFAULT"), region);
        ObjectNode agentParameters = SsmCommandDocument.parametersForAgent(objectMapper, selected.content(), parameters);
        int executionTimeoutSeconds = publicShell ? shellExecutionTimeout(parameters)
                : SsmCommandDocument.executionAllowance(selected.content(), agentParameters);
        String documentVersion = selected.version();
        String outputS3Bucket = request.path("OutputS3BucketName").asText("");
        String outputS3Prefix = request.path("OutputS3KeyPrefix").asText("");

        String commandId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        List<DirectExecutionRequest> directExecutionRequests = new ArrayList<>();

        Command command = new Command();
        command.setCommandId(commandId);
        command.setDocumentName(documentName);
        command.setDocumentVersion(documentVersion);
        command.setDocumentContent(selected.content().toString());
        command.setComment(comment);
        command.setParameters(parameters);
        command.setInstanceIds(new ArrayList<>(instanceIds));
        command.setRequestedDateTime(now);
        command.setStatus("Pending");
        command.setStatusDetails(statusDetails("Pending"));
        command.setTimeoutSeconds(timeoutSeconds);
        command.setTargetCount(instanceIds.size());
        command.setOutputS3BucketName(outputS3Bucket.isEmpty() ? null : outputS3Bucket);
        command.setOutputS3KeyPrefix(outputS3Prefix.isEmpty() ? null : outputS3Prefix);
        command.setOutputS3Region(region);
        command.setRegion(region);
        command.setExpiresAfter(now.plusSeconds((long) timeoutSeconds + executionTimeoutSeconds));

        command.setStatus("InProgress");
        command.setStatusDetails(statusDetails("InProgress"));
        commandStore.put(commandKey(region, commandId), command);

        // Create invocations and queue messages or directly execute against Floci EC2 containers.
        for (String instanceId : instanceIds) {
            CommandInvocation inv = new CommandInvocation();
            inv.setCommandId(commandId);
            inv.setInstanceId(instanceId);
            inv.setComment(comment);
            inv.setDocumentName(documentName);
            inv.setDocumentVersion(documentVersion);
            inv.setRequestedDateTime(now);
            inv.setStatus("Pending");
            inv.setStatusDetails(statusDetails("Pending"));
            inv.setRegion(region);
            initializePlugins(inv, selected.content());

            String callerAccountId = regionResolver != null ? regionResolver.getAccountId() : null;
            if (directCommandExecutionEnabled
                    && directCommandExecutor.supports(callerAccountId, instanceId, documentName)) {
                inv.setDirectExecution(true);
                inv.setStatus("InProgress");
                inv.setStatusDetails(statusDetails("InProgress"));
                invocationStore.put(invocationKey(region, commandId, instanceId), inv);
                directExecutionRequests.add(new DirectExecutionRequest(callerAccountId, instanceId, documentName, parameters));
            }
            else {
                invocationStore.put(invocationKey(region, commandId, instanceId), inv);
                queueMessage(commandId, instanceId, region);
            }
        }

        LOG.infov("SendCommand: commandId={0} document={1} targets={2}", commandId, documentName, instanceIds);
        Command response = copyCommand(command);
        for (DirectExecutionRequest directExecutionRequest : directExecutionRequests) {
            runDirectCommandAsync(
                    directExecutionRequest.accountId(),
                    commandId,
                    directExecutionRequest.instanceId(),
                    directExecutionRequest.documentName(),
                    directExecutionRequest.parameters(),
                    executionTimeoutSeconds,
                    region);
        }
        return response;
    }

    private static int shellExecutionTimeout(Map<String, List<String>> parameters) {
        if (!parameters.containsKey("executionTimeout")) {
            return DEFAULT_SHELL_EXECUTION_TIMEOUT_SECONDS;
        }
        List<String> values = parameters.get("executionTimeout");
        if (values.size() != 1 || !values.getFirst().matches("[1-9][0-9]{0,5}")) {
            throw invalidShellParameters();
        }
        int seconds = Integer.parseInt(values.getFirst());
        if (seconds > MAX_SHELL_EXECUTION_TIMEOUT_SECONDS) {
            throw invalidShellParameters();
        }
        return seconds;
    }

    private static Map<String, List<String>> parseShellParameters(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            throw invalidShellParameters();
        }
        if (!node.isObject()) {
            throw invalidShellParameters();
        }
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (!field.getValue().isArray()) {
                throw invalidShellParameters();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode value : field.getValue()) {
                if (!value.isTextual()) {
                    throw invalidShellParameters();
                }
                values.add(value.textValue());
            }
            parameters.put(field.getKey(), values);
        }
        List<String> commands = parameters.get("commands");
        if (commands == null || commands.isEmpty()) {
            throw invalidShellParameters();
        }
        return parameters;
    }

    private static AwsException invalidShellParameters() {
        return new AwsException("InvalidParameters",
                "Shell documents require at least one command and string-list parameters; "
                        + "executionTimeout must contain one integer from 1 to 172800.", 400);
    }

    private static void validateTimeoutSeconds(int timeoutSeconds) {
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS) {
            throw SmithyValidationErrors.validationException(
                    "Value '" + timeoutSeconds + "' at 'timeoutSeconds' failed to satisfy constraint: Member must have value greater than or equal to 30");
        }
        if (timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw SmithyValidationErrors.validationException(
                    "Value '" + timeoutSeconds + "' at 'timeoutSeconds' failed to satisfy constraint: Member must have value less than or equal to 2592000");
        }
    }

    public CommandInvocation getCommandInvocation(String commandId, String instanceId, String region) {
        CommandInvocation invocation = invocationStore.get(invocationKey(region, commandId, instanceId))
                .orElseThrow(() -> new AwsException("InvocationDoesNotExist",
                        "Command " + commandId + " on instance " + instanceId + " does not exist.", 400));
        expireDelivery(invocation, region);
        return invocation;
    }

    public List<Command> listCommands(String commandId, String instanceId, String region) {
        listCommandInvocations(commandId, instanceId, region);
        String prefix = region + "::";
        return commandStore.scan(k -> {
            if (!k.startsWith(prefix)) return false;
            if (commandId != null && !k.equals(commandKey(region, commandId))) return false;
            if (instanceId != null) {
                Command cmd = commandStore.get(k).orElse(null);
                return cmd != null && cmd.getInstanceIds() != null && cmd.getInstanceIds().contains(instanceId);
            }
            return true;
        });
    }

    public List<CommandInvocation> listCommandInvocations(String commandId, String instanceId, String region) {
        String prefix = region + "::";
        List<CommandInvocation> invocations = invocationStore.scan(k -> {
            if (!k.startsWith(prefix)) return false;
            if (commandId != null && !k.contains("::" + commandId + "::")) return false;
            if (instanceId != null && !k.endsWith("::" + instanceId)) return false;
            return true;
        });
        invocations.forEach(invocation -> expireDelivery(invocation, region));
        return invocations;
    }

    public void cancelCommand(String commandId, List<String> targetInstanceIds, String region) {
        Command command = commandStore.get(commandKey(region, commandId))
                .orElseThrow(() -> new AwsException("InvalidCommandId",
                        "Command " + commandId + " does not exist.", 400));

        List<String> targets = (targetInstanceIds != null && !targetInstanceIds.isEmpty())
                ? targetInstanceIds
                : command.getInstanceIds();

        for (String instanceId : targets) {
            String invKey = invocationKey(region, commandId, instanceId);
            invocationStore.get(invKey).ifPresent(inv -> {
                synchronized (inv) {
                    if ("Pending".equals(inv.getStatus()) || "InProgress".equals(inv.getStatus())) {
                        boolean delivered = inv.getDeliveryAttemptTime() != null || "InProgress".equals(inv.getStatus());
                        String status = delivered && !inv.isDirectExecution() ? "Cancelling" : "Cancelled";
                        inv.setStatus(status);
                        inv.setStatusDetails(status);
                        if ("Cancelling".equals(status)) {
                            inv.setCancellationJobId(agentJobId(UUID.randomUUID().toString(), instanceId));
                        } else {
                            inv.setExecutionEndDateTime(Instant.now());
                            terminateActivePlugins(inv, status, status);
                        }
                        invocationStore.put(invKey, inv);
                    }
                    Queue<PendingMessage> queue = messageQueues.get(messageOwner(region, instanceId));
                    if (queue != null) {
                        queue.removeIf(message -> message.commandId().equals(commandId));
                    }
                    if ("Cancelling".equals(inv.getStatus()) && !inv.isCancellationAcknowledged()) {
                        messageQueues.computeIfAbsent(messageOwner(region, instanceId), key -> new ConcurrentLinkedQueue<>())
                                .add(new PendingMessage(inv.getCancellationJobId(), commandId, region, Instant.now()));
                    }
                }
            });
        }

        updateCommandStatus(commandId, region);
        LOG.infov("CancelCommand: commandId={0}", commandId);
    }

    public int failActiveInvocationsForInstance(String region, String instanceId, String statusDetails) {
        return failActiveInvocationsForInstances(region, Set.of(instanceId), statusDetails);
    }

    public int failActiveInvocationsForInstances(String region, Set<String> instanceIds, String statusDetails) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            return 0;
        }
        String prefix = region + "::";
        Instant now = Instant.now();
        Set<String> commandIds = new LinkedHashSet<>();
        int failed = 0;

        for (CommandInvocation invocation : invocationStore.scan(key -> key.startsWith(prefix))) {
            if (!instanceIds.contains(invocation.getInstanceId())) {
                continue;
            }
            synchronized (invocation) {
                if (!isActiveInvocation(invocation.getStatus())) {
                    continue;
                }
                invocation.setStatus("Failed");
                invocation.setStatusDetails(statusDetails);
                invocation.setResponseCode(-1);
                invocation.setExecutionEndDateTime(now);
                terminateActivePlugins(invocation, "Failed", statusDetails);
                invocationStore.put(invocationKey(region, invocation.getCommandId(), invocation.getInstanceId()), invocation);
            }
            commandIds.add(invocation.getCommandId());
            failed++;
        }

        for (String instanceId : instanceIds) {
            Queue<PendingMessage> queue = messageQueues.remove(messageOwner(region, instanceId));
            if (queue != null) {
                queue.clear();
            }
        }
        messageIndex.entrySet().removeIf(entry -> {
            MessageOwner owner = entry.getValue().owner();
            return instanceIds.contains(owner.instanceId())
                    && owner.equals(messageOwner(region, owner.instanceId()));
        });
        commandIds.forEach(commandId -> updateCommandStatus(commandId, region));
        return failed;
    }

    // ── ec2messages agent protocol ──────────────────────────────────────────

    void messageGatewayOpened(String instanceId, String region, UUID connection) {
        synchronized (messageGatewayConnections) {
            messageGatewayConnections.put(messageOwner(region, instanceId), connection);
        }
    }

    void messageGatewayClosed(String instanceId, String region, UUID connection) {
        synchronized (messageGatewayConnections) {
            messageGatewayConnections.remove(messageOwner(region, instanceId), connection);
        }
    }

    public List<Map<String, Object>> getMessages(String instanceId, String messagesRequestId, int visibilityTimeout) {
        MessageOwner owner = messageOwner(regionResolver.getRegion(), instanceId);
        List<Map<String, Object>> result = new ArrayList<>();
        synchronized (messageGatewayConnections) {
            // An agent can keep a legacy poll alive during its gateway handoff. Keep that poll
            // from consuming work assigned to an authenticated gateway connection.
            Queue<PendingMessage> queue = messageQueues.get(owner);
            if (messageGatewayConnections.containsKey(owner) || queue == null || queue.isEmpty()) {
                return result;
            }
            PendingMessage msg = queue.poll();
            if (msg == null) {
                return result;
            }

            deliverAgentCommand(instanceId, msg.messageId(), msg.region(), current -> {
                messageIndex.put(msg.messageId(), new MessageCorrelation(owner, msg.commandId()));
                result.add(Map.of("MessageId", msg.messageId(), "Destination", instanceId,
                        "CreatedDate", msg.createdDate().toString(),
                        "Topic", current.topic() + "." + msg.region(), "Payload", current.content()));
            });

            if (!result.isEmpty()) {
                LOG.infov("GetMessages: instanceId={0} returned messageId={1}", instanceId, msg.messageId());
            }
        }
        return result;
    }

    public void acknowledgeMessage(String messageId) {
        MessageCorrelation meta = ownedCorrelation(messageId, false);
        if (meta == null) {
            return;
        }
        acknowledgeAgentCommand(meta.owner().instanceId(), messageId, meta.owner().region());
        LOG.debugv("AcknowledgeMessage: messageId={0} commandId={1}", messageId, meta.commandId());
    }

    public void sendReply(String messageId, String payloadJson) {
        MessageCorrelation meta = ownedCorrelation(messageId, false);
        if (meta == null) {
            LOG.warnv("SendReply: unknown messageId={0}", messageId);
            return;
        }
        try {
            agentReply(meta.owner().instanceId(), messageId, objectMapper.readTree(payloadJson), meta.owner().region());
        } catch (Exception e) {
            LOG.warnv(e, "Failed to parse SendReply payload for messageId={0}", messageId);
            throw new AwsException("InvalidMessage", "The command reply is invalid.", 400);
        }
    }

    /** Pending commands are recovered from account-aware storage when a channel reconnects. */
    List<AgentCommand> pendingAgentCommands(String instanceId, String region) {
        List<AgentCommand> pending = new ArrayList<>();
        for (CommandInvocation invocation : listCommandInvocations(null, instanceId, region)) {
            synchronized (invocation) {
                Command command = commandStore.get(commandKey(region, invocation.getCommandId())).orElse(null);
                if (command != null) {
                    AgentCommand job = pendingAgentCommand(invocation, command);
                    if (job != null) {
                        pending.add(job);
                    }
                }
            }
        }
        return pending;
    }

    private AgentCommand pendingAgentCommand(CommandInvocation invocation, Command command) {
        if ("Pending".equals(invocation.getStatus())) {
            return new AgentCommand(agentJobId(command.getCommandId(), invocation.getInstanceId()),
                    commandPayload(command), "aws.ssm.sendCommand");
        }
        if ("Cancelling".equals(invocation.getStatus()) && !invocation.isCancellationAcknowledged()) {
            return new AgentCommand(invocation.getCancellationJobId(), objectMapper.createObjectNode()
                    .put("CancelMessageId", agentJobId(command.getCommandId(), invocation.getInstanceId())).toString(),
                    "aws.ssm.cancelCommand");
        }
        return null;
    }

    /** Recheck the persisted state under the cancellation lock before accepting a wire delivery. */
    boolean deliverAgentCommand(String instanceId, String jobId, String region, Consumer<AgentCommand> deliver) {
        CommandInvocation invocation = agentInvocation(instanceId, jobId, region);
        synchronized (invocation) {
            Command command = commandStore.get(commandKey(region, invocation.getCommandId())).orElse(null);
            AgentCommand current = command == null ? null : pendingAgentCommand(invocation, command);
            if (current == null || !current.jobId().equals(jobId)) {
                return false;
            }
            if (invocation.getDeliveryAttemptTime() == null && "aws.ssm.sendCommand".equals(current.topic())) {
                invocation.setDeliveryAttemptTime(Instant.now());
                invocationStore.put(invocationKey(region, invocation.getCommandId(), instanceId), invocation);
            }
            deliver.accept(current);
            return true;
        }
    }

    void acknowledgeAgentCommand(String instanceId, String jobId, String region) {
        CommandInvocation invocation = agentInvocation(instanceId, jobId, region);
        synchronized (invocation) {
            if (jobId.equals(invocation.getCancellationJobId())) {
                invocation.setCancellationAcknowledged(true);
                invocationStore.put(invocationKey(region, invocation.getCommandId(), instanceId), invocation);
            } else if ("Pending".equals(invocation.getStatus())) {
                invocation.setStatus("InProgress");
                invocation.setStatusDetails(statusDetails("InProgress"));
                invocation.setExecutionStartDateTime(Instant.now());
                invocationStore.put(invocationKey(region, invocation.getCommandId(), instanceId), invocation);
            }
        }
        Queue<PendingMessage> queue = messageQueues.get(messageOwner(region, instanceId));
        if (queue != null) {
            queue.removeIf(message -> message.messageId().equals(jobId));
        }
        updateCommandStatus(invocation.getCommandId(), region);
    }

    void agentReply(String instanceId, String jobId, JsonNode reply, String region) {
        CommandInvocation invocation = agentInvocation(instanceId, jobId, region);
        if (reply == null || !reply.isObject()) {
            throw new AwsException("InvalidMessage", "Invalid command reply.", 400);
        }
        String documentStatus = reply.path("documentStatus").asText("");
        if (!Set.of("", "Pending", "InProgress", "Success", "Failed", "TimedOut",
                "Cancelled", "Canceled", "SuccessAndReboot", "PassedAndReboot").contains(documentStatus)) {
            throw new AwsException("InvalidMessage", "Invalid command document status.", 400);
        }
        synchronized (invocation) {
            boolean finished = !isActiveInvocation(invocation.getStatus());
            if (finished && !invocation.isAgentTerminalResponse()) {
                return;
            }
            if (jobId.equals(invocation.getCancellationJobId())) {
                invocation.setCancellationAcknowledged(true);
                invocationStore.put(invocationKey(region, invocation.getCommandId(), instanceId), invocation);
                return;
            }
            JsonNode runtime = reply.get("runtimeStatus");
            if (runtime != null && !runtime.isNull()) {
                if (!runtime.isObject()) {
                    throw new AwsException("InvalidMessage", "Invalid command plugin results.", 400);
                }
                for (Map.Entry<String, JsonNode> entry : runtime.properties()) {
                    if (!invocation.getPluginResults().containsKey(entry.getKey()) || !entry.getValue().isObject()) {
                        throw new AwsException("InvalidMessage", "The reply names an unknown command plugin.", 400);
                    }
                }
                runtime.properties().forEach(entry -> {
                    JsonNode previous = invocation.getPluginResults().get(entry.getKey());
                    if (isActiveInvocation(toInvocationStatus(previous.path("status").asText("Pending")))) {
                        invocation.getPluginResults().put(entry.getKey(), entry.getValue().deepCopy());
                    }
                });
            }
            String status = finished ? invocation.getStatus()
                    : documentStatus.isEmpty() ? "InProgress" : toInvocationStatus(documentStatus);
            if ("Cancelling".equals(invocation.getStatus()) && isActiveInvocation(status)) {
                status = "Cancelling";
            }
            invocation.setStatus(status);
            invocation.setStatusDetails(statusDetails(status));
            if (invocation.getExecutionStartDateTime() == null) {
                invocation.setExecutionStartDateTime(Instant.now());
            }
            if (!invocation.getPluginResults().isEmpty()) {
                JsonNode plugin = invocation.getPluginResults().values().iterator().next();
                invocation.setStandardOutputContent(truncateOutput(
                        plugin.path("standardOutput").asText(plugin.path("output").asText("")), MAX_STDOUT_CHARS));
                invocation.setStandardErrorContent(truncateOutput(
                        plugin.path("standardError").asText(""), MAX_STDERR_CHARS));
                invocation.setResponseCode(plugin.path("code").asInt(plugin.path("returnCode").asInt(-1)));
            }
            if (!finished && !isActiveInvocation(status)) {
                invocation.setExecutionEndDateTime(Instant.now());
                invocation.setAgentTerminalResponse(true);
            }
            invocationStore.put(invocationKey(region, invocation.getCommandId(), instanceId), invocation);
        }
        updateCommandStatus(invocation.getCommandId(), region);
    }

    private CommandInvocation agentInvocation(String instanceId, String jobId, String region) {
        String prefix = "aws.ssm.";
        String suffix = "." + instanceId;
        if (jobId == null || !jobId.startsWith(prefix) || !jobId.endsWith(suffix)
                || jobId.length() != prefix.length() + 36 + suffix.length()) {
            throw new AwsException("InvalidMessage", "The command job does not belong to this node.", 400);
        }
        String commandId = jobId.substring(prefix.length(), jobId.length() - suffix.length());
        try {
            UUID.fromString(commandId);
        } catch (IllegalArgumentException invalid) {
            throw new AwsException("InvalidMessage", "The command job identifier is invalid.", 400);
        }
        CommandInvocation invocation = invocationStore.get(invocationKey(region, commandId, instanceId))
                .orElseGet(() -> invocationStore.scan(key -> key.startsWith(region + "::")
                                && key.endsWith("::" + instanceId)).stream()
                        .filter(candidate -> jobId.equals(candidate.getCancellationJobId())).findFirst()
                        .orElseThrow(() -> new AwsException("InvocationDoesNotExist",
                                "The command job does not belong to this node.", 400)));
        expireDelivery(invocation, region);
        return invocation;
    }

    private void expireDelivery(CommandInvocation invocation, String region) {
        boolean expired = false;
        synchronized (invocation) {
            Command command = commandStore.get(commandKey(region, invocation.getCommandId())).orElse(null);
            if (!invocation.isDirectExecution() && isActiveInvocation(invocation.getStatus())
                    && command != null && command.getExpiresAfter() != null
                    && !Instant.now().isBefore(command.getExpiresAfter())) {
                invocation.setStatus("TimedOut");
                invocation.setStatusDetails("Delivery Timed Out");
                invocation.setExecutionEndDateTime(Instant.now());
                terminateActivePlugins(invocation, "TimedOut", "Delivery Timed Out");
                invocationStore.put(invocationKey(region, invocation.getCommandId(), invocation.getInstanceId()), invocation);
                expired = true;
            }
        }
        if (expired) {
            updateCommandStatus(invocation.getCommandId(), region);
        }
    }

    private void initializePlugins(CommandInvocation invocation, JsonNode document) {
        Map<String, JsonNode> plugins = new LinkedHashMap<>();
        for (JsonNode step : document.path("mainSteps")) {
            plugins.put(step.path("name").asText(), objectMapper.createObjectNode().put("status", "Pending").put("code", -1));
        }
        document.path("runtimeConfig").fieldNames().forEachRemaining(name ->
                plugins.put(name, objectMapper.createObjectNode().put("status", "Pending").put("code", -1)));
        invocation.setPluginResults(plugins);
    }

    private void terminateActivePlugins(CommandInvocation invocation, String status, String details) {
        invocation.getPluginResults().replaceAll((name, plugin) -> {
            if (!isActiveInvocation(toInvocationStatus(plugin.path("status").asText("Pending")))) {
                return plugin;
            }
            ObjectNode result = (ObjectNode) plugin.deepCopy();
            return result.put("status", status).put("statusDetails", details).put("code", -1);
        });
    }

    record AgentCommand(String jobId, String content, String topic) {}

    public void failMessage(String messageId, String failureType) {
        MessageCorrelation meta = ownedCorrelation(messageId, true);
        if (meta == null) {
            return;
        }
        String commandId = meta.commandId();
        String instanceId = meta.owner().instanceId();
        String region = meta.owner().region();

        String invKey = invocationKey(region, commandId, instanceId);
        invocationStore.get(invKey).ifPresent(inv -> {
            synchronized (inv) {
                if (!isActiveInvocation(inv.getStatus())) {
                    return;
                }
                inv.setStatus("Failed");
                inv.setStatusDetails("Failed: " + failureType);
                inv.setExecutionEndDateTime(Instant.now());
                terminateActivePlugins(inv, "Failed", inv.getStatusDetails());
                invocationStore.put(invKey, inv);
            }
        });
        updateCommandStatus(commandId, region);
        LOG.warnv("FailMessage: commandId={0} instanceId={1} failureType={2}", commandId, instanceId, failureType);
    }

    public void deleteMessage(String messageId) {
        ownedCorrelation(messageId, true);
    }

    private MessageOwner messageOwner(String region, String instanceId) {
        return new MessageOwner(regionResolver.getAccountId(), region, instanceId);
    }

    private MessageCorrelation ownedCorrelation(String messageId, boolean remove) {
        MessageCorrelation correlation = messageIndex.get(messageId);
        if (correlation == null || !correlation.owner().equals(
                messageOwner(regionResolver.getRegion(), correlation.owner().instanceId()))) {
            return null;
        }
        return !remove || messageIndex.remove(messageId, correlation) ? correlation : null;
    }

    // ── CodeDeploy integration helpers ─────────────────────────────────────

    public boolean isInstanceRegistered(String instanceId, String region) {
        return instanceStore.get(instanceKey(region, instanceId)).isPresent();
    }

    public String sendCommandToInstance(String instanceId, String documentName,
                                        Map<String, List<String>> parameters,
                                        int timeoutSeconds, String region) {
        String commandId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        SsmCommandDocument selected = SsmCommandDocument.resolve(
                documents, objectMapper, documentName, "$DEFAULT", region);
        Map<String, List<String>> effectiveParameters = new LinkedHashMap<>(parameters);
        if ("AWS-RunShellScript".equals(documentName)) {
            effectiveParameters.putIfAbsent("executionTimeout", List.of(Integer.toString(timeoutSeconds)));
        }

        Command command = new Command();
        command.setCommandId(commandId);
        command.setDocumentName(documentName);
        command.setDocumentVersion(selected.version());
        command.setDocumentContent(selected.content().toString());
        command.setParameters(effectiveParameters);
        command.setInstanceIds(List.of(instanceId));
        command.setRequestedDateTime(now);
        command.setStatus("InProgress");
        command.setStatusDetails(statusDetails("InProgress"));
        command.setTimeoutSeconds(timeoutSeconds);
        command.setTargetCount(1);
        command.setRegion(region);
        command.setExpiresAfter(now.plusSeconds(timeoutSeconds));
        commandStore.put(commandKey(region, commandId), command);

        CommandInvocation inv = new CommandInvocation();
        inv.setCommandId(commandId);
        inv.setInstanceId(instanceId);
        inv.setDocumentName(documentName);
        inv.setDocumentVersion(selected.version());
        inv.setRequestedDateTime(now);
        inv.setStatus("Pending");
        inv.setStatusDetails(statusDetails("Pending"));
        inv.setRegion(region);
        initializePlugins(inv, selected.content());
        invocationStore.put(invocationKey(region, commandId, instanceId), inv);

        queueMessage(commandId, instanceId, region);
        return commandId;
    }

    public String getCommandInvocationStatus(String commandId, String instanceId, String region) {
        return invocationStore.get(invocationKey(region, commandId, instanceId))
                .map(invocation -> {
                    expireDelivery(invocation, region);
                    return invocation.getStatus();
                })
                .orElse("Failed");
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private void runDirectCommandAsync(
            String accountId,
            String commandId,
            String instanceId,
            String documentName,
            Map<String, List<String>> parameters,
            int timeoutSeconds,
            String region) {
        CompletableFuture.runAsync(() -> RequestScopes.runAs(accountId, region, () -> {
            String invKey = invocationKey(region, commandId, instanceId);
            CommandInvocation invocation = invocationStore.get(invKey).orElse(null);
            if (invocation == null || "Cancelled".equals(invocation.getStatus())) {
                return;
            }

            SsmDirectCommandExecutor.ExecutionResult result = directCommandExecutor
                    .executeIfSupported(accountId, instanceId, documentName, parameters, timeoutSeconds)
                    .orElse(null);
            if (result == null) {
                synchronized (invocation) {
                    if (!"InProgress".equals(invocation.getStatus())) {
                        return;
                    }
                    invocation.setStatus("Pending");
                    invocation.setStatusDetails(statusDetails("Pending"));
                    invocation.setDirectExecution(false);
                    invocationStore.put(invKey, invocation);
                }
                queueMessage(commandId, instanceId, region);
                updateCommandStatus(commandId, region);
                return;
            }
            synchronized (invocation) {
                if (!"InProgress".equals(invocation.getStatus())) {
                    return;
                }
                applyDirectResult(invocation, result);
                invocationStore.put(invKey, invocation);
            }
            updateCommandStatus(commandId, region);
        }), directExecutionExecutor);
    }

    private void applyDirectResult(CommandInvocation invocation, SsmDirectCommandExecutor.ExecutionResult result) {
        invocation.setStatus(result.status());
        invocation.setStatusDetails(statusDetails(result.status()));
        invocation.setStandardOutputContent(truncateOutput(result.standardOutput(), MAX_STDOUT_CHARS));
        invocation.setStandardErrorContent(truncateOutput(result.standardError(), MAX_STDERR_CHARS));
        invocation.setResponseCode(result.responseCode());
        invocation.setExecutionStartDateTime(result.executionStartDateTime());
        invocation.setExecutionEndDateTime(result.executionEndDateTime());
    }

    static String truncateOutput(String output, int maxChars) {
        if (output == null) {
            return "";
        }
        if (output.length() <= maxChars) {
            return output;
        }
        return output.substring(0, maxChars);
    }

    private void queueMessage(String commandId, String instanceId, String region) {
        String messageId = agentJobId(commandId, instanceId);
        PendingMessage msg = new PendingMessage(messageId, commandId, region, Instant.now());
        messageQueues.computeIfAbsent(messageOwner(region, instanceId), k -> new ConcurrentLinkedQueue<>()).add(msg);
    }

    private String commandPayload(Command command) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("DocumentName", command.getDocumentName());
            payload.put("DocumentVersion", command.getDocumentVersion());
            payload.put("CommandId", command.getCommandId());
            payload.put("OutputS3BucketName", command.getOutputS3BucketName() == null ? "" : command.getOutputS3BucketName());
            payload.put("OutputS3KeyPrefix", command.getOutputS3KeyPrefix() == null ? "" : command.getOutputS3KeyPrefix());
            payload.put("OutputS3Region", command.getRegion());
            payload.put("CloudWatchLogGroupName", "");
            payload.put("CloudWatchOutputEnabled", "false");

            if (command.getDocumentContent() == null) {
                throw new AwsException("InvalidDocument", "The command has no captured document content.", 400);
            }
            JsonNode document = objectMapper.readTree(command.getDocumentContent());
            payload.set("Parameters", SsmCommandDocument.parametersForAgent(objectMapper, document, command.getParameters()));
            payload.set("DocumentContent", document);

            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read the command's captured document.", e);
        }
    }

    static String agentJobId(String commandId, String instanceId) {
        return "aws.ssm." + commandId + "." + instanceId;
    }

    private void updateCommandStatus(String commandId, String region) {
        Command command = commandStore.get(commandKey(region, commandId)).orElse(null);
        if (command == null) {
            return;
        }

        synchronized (command) {
            List<String> instanceIds = command.getInstanceIds();
            int completed = 0;
            int errors = 0;
            int executionTimedOut = 0;
            int deliveryTimedOut = 0;
            int cancelled = 0;
            boolean anyInProgress = false;
            boolean anyCancelling = false;
            for (String instanceId : instanceIds) {
                CommandInvocation invocation = invocationStore.get(invocationKey(region, commandId, instanceId)).orElse(null);
                if (invocation == null) {
                    continue;
                }
                synchronized (invocation) {
                    String status = invocation.getStatus();
                    if (!isActiveInvocation(status)) {
                        completed++;
                    }
                    if ("Cancelled".equals(status)) {
                        cancelled++;
                    } else if ("Failed".equals(status)) {
                        errors++;
                    } else if ("TimedOut".equals(status)) {
                        if ("Delivery Timed Out".equals(invocation.getStatusDetails())) {
                            deliveryTimedOut++;
                        } else {
                            executionTimedOut++;
                            errors++;
                        }
                    }
                    anyCancelling |= "Cancelling".equals(status);
                    anyInProgress |= "InProgress".equals(status) || invocation.getDeliveryAttemptTime() != null;
                }
            }
            String status;
            String details;
            if (completed == instanceIds.size()) {
                status = cancelled == instanceIds.size() ? "Cancelled"
                        : deliveryTimedOut == instanceIds.size() ? "TimedOut"
                        : commandStatus(errors, executionTimedOut, instanceIds.size());
                details = deliveryTimedOut == instanceIds.size() ? "Delivery Timed Out" : statusDetails(status);
            } else {
                status = anyCancelling ? "Cancelling" : anyInProgress ? "InProgress" : "Pending";
                details = statusDetails(status);
            }
            command.setCompletedCount(completed);
            command.setErrorCount(errors);
            command.setDeliveryTimedOutCount(deliveryTimedOut);
            command.setStatus(status);
            command.setStatusDetails(details);
            commandStore.put(commandKey(region, commandId), command);
        }
    }

    private static String commandStatus(int errors, int timedOut, int targetCount) {
        if (errors == 0) {
            return "Success";
        }
        if (timedOut == targetCount) {
            return "TimedOut";
        }
        if (errors == targetCount) {
            return "Failed";
        }
        return "Success";
    }

    static String toInvocationStatus(String agentStatus) {
        return switch (agentStatus) {
            case "Pending" -> "Pending";
            case "InProgress", "SuccessAndReboot", "PassedAndReboot" -> "InProgress";
            case "Success" -> "Success";
            case "Failed" -> "Failed";
            case "TimedOut" -> "TimedOut";
            case "Cancelled", "Canceled" -> "Cancelled";
            default -> "Failed";
        };
    }

    private static boolean isActiveInvocation(String status) {
        return "Pending".equals(status) || "InProgress".equals(status) || "Cancelling".equals(status);
    }

    static String statusDetails(String status) {
        return switch (status) {
            case "InProgress" -> "In Progress";
            case "TimedOut" -> "Execution Timed Out";
            default -> status;
        };
    }

    private static Command copyCommand(Command source) {
        Command copy = new Command();
        copy.setCommandId(source.getCommandId());
        copy.setDocumentName(source.getDocumentName());
        copy.setDocumentVersion(source.getDocumentVersion());
        copy.setDocumentContent(source.getDocumentContent());
        copy.setComment(source.getComment());
        copy.setExpiresAfter(source.getExpiresAfter());
        copy.setParameters(source.getParameters());
        copy.setInstanceIds(source.getInstanceIds() == null ? null : new ArrayList<>(source.getInstanceIds()));
        copy.setRequestedDateTime(source.getRequestedDateTime());
        copy.setStatus(source.getStatus());
        copy.setStatusDetails(source.getStatusDetails());
        copy.setTimeoutSeconds(source.getTimeoutSeconds());
        copy.setTargetCount(source.getTargetCount());
        copy.setCompletedCount(source.getCompletedCount());
        copy.setErrorCount(source.getErrorCount());
        copy.setDeliveryTimedOutCount(source.getDeliveryTimedOutCount());
        copy.setOutputS3BucketName(source.getOutputS3BucketName());
        copy.setOutputS3KeyPrefix(source.getOutputS3KeyPrefix());
        copy.setOutputS3Region(source.getOutputS3Region());
        copy.setRegion(source.getRegion());
        return copy;
    }

    private record DirectExecutionRequest(
            String accountId,
            String instanceId,
            String documentName,
            Map<String, List<String>> parameters) {}

    private Map<String, List<String>> parseParameters(JsonNode parametersNode) {
        if (parametersNode == null || parametersNode.isNull() || parametersNode.isMissingNode()) {
            return Map.of();
        }
        if (!parametersNode.isObject()) {
            throw new AwsException("InvalidParameters", "Parameters must be a map of string lists.", 400);
        }
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : parametersNode.properties()) {
            List<String> items = new ArrayList<>();
            if (!entry.getValue().isArray()) {
                throw new AwsException("InvalidParameters", "Parameters must be a map of string lists.", 400);
            }
            for (JsonNode item : entry.getValue()) {
                if (!item.isTextual()) {
                    throw new AwsException("InvalidParameters", "Parameters must be a map of string lists.", 400);
                }
                items.add(item.textValue());
            }
            values.put(entry.getKey(), List.copyOf(items));
        }
        return values;
    }

    private static String instanceKey(String region, String instanceId) {
        return region + "::" + instanceId;
    }

    private static String commandKey(String region, String commandId) {
        return region + "::" + commandId;
    }

    private static String invocationKey(String region, String commandId, String instanceId) {
        return region + "::" + commandId + "::" + instanceId;
    }

    private record MessageOwner(String accountId, String region, String instanceId) {}
    private record MessageCorrelation(MessageOwner owner, String commandId) {}

    record PendingMessage(String messageId, String commandId, String region, Instant createdDate) {}
}
