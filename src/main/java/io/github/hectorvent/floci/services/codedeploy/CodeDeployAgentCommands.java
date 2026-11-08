package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** The tenant-scoped command mailbox used by real CodeDeploy agents. */
@ApplicationScoped
public class CodeDeployAgentCommands {

    private final ObjectMapper mapper;
    private final CodeDeploySpecificationSigner signer;
    private final long pollWaitNanos;
    private final long redeliveryNanos;
    private final Map<DeploymentKey, Session> sessions = new HashMap<>();
    private final Map<GroupKey, Session> reservations = new HashMap<>();
    private final Map<HostKey, Set<Session>> hostSessions = new HashMap<>();
    private final Map<String, Command> commands = new HashMap<>();
    private boolean stopped;

    @Inject
    public CodeDeployAgentCommands(ObjectMapper mapper, CodeDeploySpecificationSigner signer) {
        this(mapper, signer, Duration.ofSeconds(60), Duration.ofSeconds(5));
    }

    CodeDeployAgentCommands(ObjectMapper mapper, CodeDeploySpecificationSigner signer,
                            Duration pollWait, Duration redelivery) {
        if (pollWait.isNegative() || redelivery.isNegative()) {
            throw new IllegalArgumentException("Command polling durations cannot be negative");
        }
        this.mapper = mapper;
        this.signer = signer;
        this.pollWaitNanos = pollWait.toNanos();
        this.redeliveryNanos = redelivery.toNanos();
    }

    public synchronized Session open(String account, String region, String executionId, String groupId,
                                     Collection<String> hosts, Map<String, Object> specification) {
        return open(account, region, executionId, groupId, hosts, specification, true);
    }

    synchronized Session open(String account, String region, String executionId, String groupId,
                              Collection<String> hosts, Map<String, Object> specification, boolean reserveGroup) {
        if (stopped) {
            throw new IllegalStateException("Command service is stopped");
        }
        DeploymentKey key = new DeploymentKey(account, region, executionId);
        GroupKey group = new GroupKey(account, region, groupId);
        Set<String> selected = Set.copyOf(hosts);
        if (selected.isEmpty() || sessions.containsKey(key)) {
            throw new IllegalArgumentException("A deployment needs unique execution identity and selected hosts");
        }
        if (reserveGroup && reservations.containsKey(group)) {
            throw new AwsException("DeploymentLimitExceededException",
                    "The deployment group already has an active deployment", 400);
        }
        for (String host : selected) {
            if (hostSessions.getOrDefault(new HostKey(account, region, host), Set.of()).stream()
                    .anyMatch(session -> session.group.equals(group))) {
                throw new AwsException("DeploymentLimitExceededException",
                        "The instance already has an active deployment in this deployment group", 400);
            }
        }
        String payload;
        try {
            payload = signer.sign(mapper.writeValueAsString(specification));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid deployment specification", failure);
        }
        Session session = new Session(key, group, selected, payload);
        sessions.put(key, session);
        if (reserveGroup) {
            reservations.put(group, session);
        }
        selected.forEach(host -> hostSessions.computeIfAbsent(new HostKey(account, region, host),
                ignored -> new LinkedHashSet<>()).add(session));
        return session;
    }

    public synchronized Ticket enqueue(Session session, String host, String name) {
        requireOpen(session);
        if (!session.hosts.contains(host)) {
            throw new IllegalArgumentException("Host is not selected for this deployment");
        }
        Command previous = session.current.get(host);
        if (previous != null && !previous.result.isDone()) {
            throw new IllegalStateException("The previous host command has not completed");
        }
        Command command = new Command(session, host, name);
        session.current.put(host, command);
        commands.put(command.identifier, command);
        notifyAll();
        return new Ticket(command.accepted.minimalCompletionStage(), command.result.minimalCompletionStage());
    }

    public synchronized Map<String, Object> poll(String account, String region, String host) {
        long deadline = System.nanoTime() + pollWaitNanos;
        HostKey key = new HostKey(account, region, host);
        while (!stopped) {
            long now = System.nanoTime();
            long remaining = deadline - now;
            for (Session session : hostSessions.getOrDefault(key, Set.of())) {
                Command command = session.current.get(host);
                if (command == null || !"Pending".equals(command.status)) {
                    continue;
                }
                long availableIn = command.delivered ? command.availableAfter - now : 0;
                if (availableIn <= 0) {
                    command.delivered = true;
                    command.availableAfter = now + redeliveryNanos;
                    return Map.of("HostCommand", Map.of(
                            "HostCommandIdentifier", command.identifier,
                            "HostIdentifier", command.host,
                            "DeploymentExecutionId", session.key.executionId,
                            "CommandName", command.name));
                }
                remaining = Math.min(remaining, availableIn);
            }
            if (remaining <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Map.of();
    }

    public synchronized Map<String, Object> acknowledge(String account, String region, String identifier) {
        Command command = command(account, region, identifier);
        if ("Pending".equals(command.status)) {
            command.status = "InProgress";
            command.accepted.complete(null);
            notifyAll();
        }
        return Map.of("CommandStatus", command.status);
    }

    public synchronized void authorizeHostCommand(String account, String region, String identifier, String host) {
        if (!command(account, region, identifier).host.equals(host)) {
            throw new AwsException("AccessDeniedException", "The command belongs to a different instance", 403);
        }
    }

    public synchronized Map<String, Object> specification(String account, String region,
                                                          String executionId, String host) {
        Session session = sessions.get(new DeploymentKey(account, region, executionId));
        if (session == null || session.closed || !session.hosts.contains(host)) {
            throw new AwsException("InvalidDeploymentExecutionIdException",
                    "Deployment execution is unavailable for this host", 400);
        }
        return Map.of("DeploymentSystem", "CodeDeploy",
                "DeploymentSpecification", Map.of("GenericEnvelope",
                        Map.of("Format", "PKCS7/JSON", "Payload", session.specification)));
    }

    public synchronized Map<String, Object> update(String account, String region, String identifier) {
        return Map.of("CommandStatus", command(account, region, identifier).status);
    }

    public synchronized void complete(String account, String region, String identifier,
                                      String status, String diagnostics) {
        if (!"Succeeded".equals(status) && !"Failed".equals(status)) {
            throw new AwsException("ValidationException", "A terminal command status is required", 400);
        }
        Command command = command(account, region, identifier);
        if (command.result.isDone()) {
            if (!command.cancelled && command.status.equals(status)) {
                return;
            }
            throw new AwsException("InvalidHostCommandIdentifierException",
                    "The command is already terminal", 400);
        }
        if (!"InProgress".equals(command.status)) {
            throw new AwsException("InvalidHostCommandIdentifierException",
                    "The command has not been acknowledged", 400);
        }
        command.status = status;
        command.result.complete(new Result(status, diagnostics));
        notifyAll();
    }

    public synchronized void close(Session session) {
        if (sessions.get(session.key) != session || session.closed) {
            return;
        }
        session.closed = true;
        reservations.remove(session.group, session);
        session.hosts.forEach(host -> {
            HostKey key = new HostKey(session.key.account, session.key.region, host);
            Set<Session> selected = hostSessions.get(key);
            if (selected != null) {
                selected.remove(session);
                if (selected.isEmpty()) {
                    hostSessions.remove(key);
                }
            }
            cancel(session, host);
        });
        notifyAll();
    }

    /** Cancel queued work atomically with acknowledgement, preserving an agent's active command. */
    public synchronized boolean cancelPending(Session session, String host) {
        Command command = session.current.get(host);
        if (command != null && "Pending".equals(command.status)) {
            cancel(session, host);
            return true;
        }
        return false;
    }

    public synchronized void cancel(Session session, String host) {
        Command command = session.current.get(host);
        if (command != null && !command.result.isDone()) {
            command.cancelled = true;
            command.status = "Failed";
            command.accepted.cancel(false);
            command.result.cancel(false);
            notifyAll();
        }
    }

    @PreDestroy
    synchronized void shutdown() {
        stopped = true;
        sessions.values().forEach(this::close);
        commands.clear();
        reservations.clear();
        hostSessions.clear();
        sessions.clear();
        notifyAll();
    }

    private Command command(String account, String region, String identifier) {
        Command command = commands.get(identifier);
        if (command == null || !command.session.key.account.equals(account)
                || !command.session.key.region.equals(region)) {
            throw new AwsException("InvalidHostCommandIdentifierException",
                    "Host command is unavailable", 400);
        }
        return command;
    }

    private void requireOpen(Session session) {
        if (sessions.get(session.key) != session || session.closed) {
            throw new IllegalStateException("Deployment execution is closed");
        }
    }

    public record Result(String status, String diagnostics) {}

    public record Ticket(CompletionStage<Void> accepted, CompletionStage<Result> result) {}

    private record DeploymentKey(String account, String region, String executionId) {}

    private record GroupKey(String account, String region, String groupId) {}

    private record HostKey(String account, String region, String host) {}

    public static final class Session {
        private final DeploymentKey key;
        private final GroupKey group;
        private final Set<String> hosts;
        private final String specification;
        private final Map<String, Command> current = new HashMap<>();
        private boolean closed;

        private Session(DeploymentKey key, GroupKey group, Set<String> hosts, String specification) {
            this.key = key;
            this.group = group;
            this.hosts = hosts;
            this.specification = specification;
        }
    }

    private static final class Command {
        private final String identifier = UUID.randomUUID().toString();
        private final Session session;
        private final String host;
        private final String name;
        private final CompletableFuture<Void> accepted = new CompletableFuture<>();
        private final CompletableFuture<Result> result = new CompletableFuture<>();
        private String status = "Pending";
        private boolean cancelled;
        private boolean delivered;
        private long availableAfter;

        private Command(Session session, String host, String name) {
            this.session = session;
            this.host = host;
            this.name = name;
        }
    }
}
