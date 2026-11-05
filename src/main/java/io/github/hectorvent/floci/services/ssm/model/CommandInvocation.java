package io.github.hectorvent.floci.services.ssm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommandInvocation {

    private String commandId;
    private String instanceId;
    private String comment;
    private String documentName;
    private String documentVersion;
    private Instant requestedDateTime;
    private String status = "Pending";
    private String statusDetails = "Pending";
    private String standardOutputContent = "";
    private String standardErrorContent = "";
    private int responseCode = -1;
    private Instant executionStartDateTime;
    private Instant executionEndDateTime;
    private String region;
    private Map<String, JsonNode> pluginResults = new LinkedHashMap<>();
    private Instant deliveryAttemptTime;
    private String cancellationJobId;
    private boolean cancellationAcknowledged;
    private boolean directExecution;
    private boolean agentTerminalResponse;

    public CommandInvocation() {}

    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }

    public String getDocumentVersion() { return documentVersion; }
    public void setDocumentVersion(String documentVersion) { this.documentVersion = documentVersion; }

    public Instant getRequestedDateTime() { return requestedDateTime; }
    public void setRequestedDateTime(Instant requestedDateTime) { this.requestedDateTime = requestedDateTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusDetails() { return statusDetails; }
    public void setStatusDetails(String statusDetails) { this.statusDetails = statusDetails; }

    public String getStandardOutputContent() { return standardOutputContent; }
    public void setStandardOutputContent(String standardOutputContent) { this.standardOutputContent = standardOutputContent; }

    public String getStandardErrorContent() { return standardErrorContent; }
    public void setStandardErrorContent(String standardErrorContent) { this.standardErrorContent = standardErrorContent; }

    public int getResponseCode() { return responseCode; }
    public void setResponseCode(int responseCode) { this.responseCode = responseCode; }

    public Instant getExecutionStartDateTime() { return executionStartDateTime; }
    public void setExecutionStartDateTime(Instant executionStartDateTime) { this.executionStartDateTime = executionStartDateTime; }

    public Instant getExecutionEndDateTime() { return executionEndDateTime; }
    public void setExecutionEndDateTime(Instant executionEndDateTime) { this.executionEndDateTime = executionEndDateTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, JsonNode> getPluginResults() { return pluginResults; }
    public void setPluginResults(Map<String, JsonNode> pluginResults) { this.pluginResults = pluginResults; }

    public Instant getDeliveryAttemptTime() { return deliveryAttemptTime; }
    public void setDeliveryAttemptTime(Instant deliveryAttemptTime) { this.deliveryAttemptTime = deliveryAttemptTime; }

    public String getCancellationJobId() { return cancellationJobId; }
    public void setCancellationJobId(String cancellationJobId) { this.cancellationJobId = cancellationJobId; }

    public boolean isCancellationAcknowledged() { return cancellationAcknowledged; }
    public void setCancellationAcknowledged(boolean cancellationAcknowledged) {
        this.cancellationAcknowledged = cancellationAcknowledged;
    }

    public boolean isDirectExecution() { return directExecution; }
    public void setDirectExecution(boolean directExecution) { this.directExecution = directExecution; }

    public boolean isAgentTerminalResponse() { return agentTerminalResponse; }
    public void setAgentTerminalResponse(boolean agentTerminalResponse) { this.agentTerminalResponse = agentTerminalResponse; }
}
