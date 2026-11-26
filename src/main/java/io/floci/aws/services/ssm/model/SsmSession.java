package io.floci.aws.services.ssm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionState;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** A Session Manager session as DescribeSessions reports it. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SsmSession {

    private String sessionId;
    private String accountId;
    private String region;
    private String target;
    private String status;
    private Instant startDate;
    private Instant endDate;
    private String documentName;
    private String owner;
    private String reason;
    private String maxSessionDuration;

    public SsmSession() {}

    public SsmSession(String sessionId, String target, String owner, String documentName, String reason,
                      SessionState state, Instant startDate, Instant endDate) {
        this.sessionId = sessionId;
        this.target = target;
        this.owner = owner;
        this.documentName = documentName;
        this.reason = reason;
        this.status = status(state);
        this.startDate = startDate;
        this.endDate = endDate;
    }

    // Older upstream snapshots used an enum named state instead of the public status string.
    @JsonSetter("state")
    public void readState(SessionState state) {
        if (status == null && state != null) {
            status = status(state);
        }
    }

    public String sessionId() { return sessionId; }
    public String target() { return target; }
    public String owner() { return owner; }
    public String documentName() { return documentName; }
    public String reason() { return reason; }
    public String status() { return status; }
    public Instant startDate() { return startDate; }
    public Instant endDate() { return endDate; }

    public boolean active() {
        return endDate == null;
    }

    public SessionState state() {
        return switch (status) {
            case "Connected" -> SessionState.CONNECTED;
            case "Connecting", "Disconnected", "Terminating" -> SessionState.DISCONNECTED;
            case "Terminated" -> SessionState.TERMINATED;
            case "Failed" -> SessionState.FAILED;
            default -> throw new IllegalStateException("Unknown SSM session status: " + status);
        };
    }

    public SsmSession transition(SessionState next, Instant now) {
        Instant end = switch (next) {
            case TERMINATED, FAILED -> now;
            case CONNECTED, DISCONNECTED -> null;
        };
        SsmSession updated = new SsmSession(sessionId, target, owner, documentName, reason, next, startDate, end);
        updated.setAccountId(accountId);
        updated.setRegion(region);
        updated.setMaxSessionDuration(maxSessionDuration);
        return updated;
    }

    private static String status(SessionState state) {
        return switch (state) {
            case CONNECTED -> "Connected";
            case DISCONNECTED -> "Disconnected";
            case TERMINATED -> "Terminated";
            case FAILED -> "Failed";
        };
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getStartDate() { return startDate; }
    public void setStartDate(Instant startDate) { this.startDate = startDate; }

    public Instant getEndDate() { return endDate; }
    public void setEndDate(Instant endDate) { this.endDate = endDate; }

    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getMaxSessionDuration() { return maxSessionDuration; }
    public void setMaxSessionDuration(String maxSessionDuration) { this.maxSessionDuration = maxSessionDuration; }
}
