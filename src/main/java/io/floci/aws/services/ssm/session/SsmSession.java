package io.floci.aws.services.ssm.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionState;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Objects;

/** Shared persisted session representation, readable from both previously published JSON formats. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SsmSession extends io.floci.aws.services.ssm.model.SsmSession {
    public SsmSession() { }

    public SsmSession(String sessionId, String target, String owner, String documentName,
                      String reason, SessionState state, Instant startDate, Instant endDate) {
        setSessionId(sessionId);
        setTarget(target);
        setOwner(owner);
        setDocumentName(documentName);
        setReason(reason);
        readState(state);
        setStartDate(startDate);
        setEndDate(endDate);
    }

    public String sessionId() { return getSessionId(); }
    public String target() { return getTarget(); }
    public String owner() { return getOwner(); }
    public String documentName() { return getDocumentName(); }
    public String reason() { return getReason(); }
    public Instant startDate() { return getStartDate(); }
    public Instant endDate() { return getEndDate(); }
    public String status() { return getStatus(); }

    public SessionState state() {
        return switch (getStatus()) {
            case "Connected" -> SessionState.CONNECTED;
            case "Terminated" -> SessionState.TERMINATED;
            case "Failed" -> SessionState.FAILED;
            default -> SessionState.DISCONNECTED;
        };
    }

    /** Reads the enum-valued state in sessions written by the original gateway implementation. */
    @JsonSetter("state")
    public void readState(SessionState state) {
        if (state != null) {
            setStatus(switch (state) {
                case CONNECTED -> "Connected";
                case DISCONNECTED -> "Disconnected";
                case TERMINATED -> "Terminated";
                case FAILED -> "Failed";
            });
        }
    }

    public boolean active() {
        return endDate() == null;
    }

    SsmSession transition(SessionState next, Instant now) {
        Instant end = switch (next) {
            case TERMINATED, FAILED -> now;
            case CONNECTED, DISCONNECTED -> null;
        };
        SsmSession transitioned = new SsmSession(sessionId(), target(), owner(), documentName(), reason(),
                next, startDate(), end);
        transitioned.setAccountId(getAccountId());
        transitioned.setRegion(getRegion());
        transitioned.setMaxSessionDuration(getMaxSessionDuration());
        return transitioned;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SsmSession session)) {
            return false;
        }
        return Objects.equals(sessionId(), session.sessionId()) && Objects.equals(target(), session.target())
                && Objects.equals(owner(), session.owner()) && Objects.equals(documentName(), session.documentName())
                && Objects.equals(reason(), session.reason()) && Objects.equals(status(), session.status())
                && Objects.equals(startDate(), session.startDate()) && Objects.equals(endDate(), session.endDate())
                && Objects.equals(getAccountId(), session.getAccountId()) && Objects.equals(getRegion(), session.getRegion())
                && Objects.equals(getMaxSessionDuration(), session.getMaxSessionDuration());
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId(), target(), owner(), documentName(), reason(), status(), startDate(), endDate(),
                getAccountId(), getRegion(), getMaxSessionDuration());
    }
}
