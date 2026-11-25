package io.floci.aws.services.ssm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
