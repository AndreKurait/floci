package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.EpochSeconds;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.codedeploy.model.DeploymentGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates real host-agent commands; the agent owns bundle download, AppSpec and script execution. */
@ApplicationScoped
public class CodeDeployAgentDeployment {

    private static final Logger LOG = Logger.getLogger(CodeDeployAgentDeployment.class);
    private static final List<String> PHASES = List.of("ApplicationStop", "DownloadBundle", "BeforeInstall",
            "Install", "AfterInstall", "ApplicationStart", "ValidateService");
    private static final String ONE_AT_A_TIME = "CodeDeployDefault.OneAtATime";
    private static final String ALL_AT_ONCE = "CodeDeployDefault.AllAtOnce";
    private static final List<String> DIAGNOSTIC_CODES = List.of("Success", "ScriptMissing",
            "ScriptNotExecutable", "ScriptTimedOut", "ScriptFailed", "UnknownError");

    private final CodeDeployAgentCommands commands;
    private final AutoScalingService autoScaling;
    private final Ec2Service ec2;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final Duration startTimeout;
    private final Duration phaseTimeout;

    @Inject
    public CodeDeployAgentDeployment(CodeDeployAgentCommands commands, AutoScalingService autoScaling,
                                     Ec2Service ec2, RegionResolver regionResolver, ObjectMapper mapper) {
        this(commands, autoScaling, ec2, regionResolver, mapper, Duration.ofMinutes(5), Duration.ofHours(1));
    }

    CodeDeployAgentDeployment(CodeDeployAgentCommands commands, AutoScalingService autoScaling,
                              Ec2Service ec2, RegionResolver regionResolver, ObjectMapper mapper,
                              Duration startTimeout, Duration phaseTimeout) {
        this.commands = commands;
        this.autoScaling = autoScaling;
        this.ec2 = ec2;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
        this.startTimeout = startTimeout;
        this.phaseTimeout = phaseTimeout;
    }

    /** Resolve account-scoped resources on the request thread before starting any worker. */
    public Prepared prepare(String region, DeploymentGroup group, String config, Map<String, Object> revision) {
        return prepare(region, group, config, revision, "DISALLOW");
    }

    public Prepared prepare(String region, DeploymentGroup group, String config, Map<String, Object> revision,
                            String fileExistsBehavior) {
        if (fileExistsBehavior == null || !Set.of("DISALLOW", "OVERWRITE", "RETAIN").contains(fileExistsBehavior)) {
            throw new AwsException("InvalidFileExistsBehaviorException",
                    "fileExistsBehavior must be DISALLOW, OVERWRITE or RETAIN", 400);
        }
        String effectiveConfig = config == null ? ONE_AT_A_TIME : config;
        if (!Set.of(ONE_AT_A_TIME, ALL_AT_ONCE).contains(effectiveConfig)) {
            throw unsupported("S3 agent deployments currently support OneAtATime and AllAtOnce");
        }
        Map<String, Object> style = group.getDeploymentStyle();
        if (style != null && (!"IN_PLACE".equals(style.getOrDefault("deploymentType", "IN_PLACE"))
                || !"WITHOUT_TRAFFIC_CONTROL".equals(
                        style.getOrDefault("deploymentOption", "WITHOUT_TRAFFIC_CONTROL")))) {
            throw unsupported("S3 agent deployments require IN_PLACE without load balancer traffic control");
        }
        if (group.getAutoRollbackConfiguration() != null
                && Boolean.TRUE.equals(group.getAutoRollbackConfiguration().get("enabled"))) {
            throw unsupported("Automatic rollback is not yet supported for S3 agent deployments");
        }
        if (group.getAlarmConfiguration() != null
                && Boolean.TRUE.equals(group.getAlarmConfiguration().get("enabled"))) {
            throw unsupported("Deployment alarm monitoring is not yet supported for S3 agent deployments");
        }
        if ((group.getOnPremisesInstanceTagFilters() != null && !group.getOnPremisesInstanceTagFilters().isEmpty())
                || group.getOnPremisesTagSet() != null) {
            throw unsupported("S3 agent deployments currently select EC2 instances");
        }
        JsonNode s3 = mapper.valueToTree(revision).path("s3Location");
        if (!s3.isObject() || !s3.path("bucket").isTextual() || s3.path("bucket").asText().isBlank()
                || !s3.path("key").isTextual() || s3.path("key").asText().isBlank()
                || !Set.of("zip", "tar", "tgz").contains(s3.path("bundleType").asText(""))) {
            throw new AwsException("InvalidRevisionException",
                    "An S3 bucket, key and zip, tar or tgz bundle type are required", 400);
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("Bucket", s3.path("bucket").asText());
        source.put("Key", s3.path("key").asText());
        source.put("BundleType", s3.path("bundleType").asText());
        for (String optional : List.of("version", "eTag")) {
            if (s3.hasNonNull(optional)
                    && (!s3.path(optional).isTextual() || s3.path(optional).asText().isBlank())) {
                throw new AwsException("InvalidRevisionException", optional + " must be nonempty text", 400);
            }
        }
        if (s3.hasNonNull("version")) {
            source.put("Version", s3.path("version").asText());
        }
        if (s3.hasNonNull("eTag")) {
            source.put("ETag", s3.path("eTag").asText());
        }

        String account = regionResolver.getAccountId();
        List<String> ids = selectInstances(region, group);
        Map<String, String> hosts = new LinkedHashMap<>();
        ids.forEach(id -> hosts.put(id, AwsArnUtils.Arn.of("ec2", region, account, "instance/" + id).toString()));
        return new Prepared(account, region, group.getDeploymentGroupId(), effectiveConfig,
                Map.copyOf(source), Map.copyOf(hosts), List.copyOf(ids), fileExistsBehavior);
    }

    private List<String> selectInstances(String region, DeploymentGroup group) {
        Set<String> ids = new LinkedHashSet<>();
        if (group.getAutoScalingGroups() != null && !group.getAutoScalingGroups().isEmpty()) {
            List<String> names = group.getAutoScalingGroups().stream()
                    .map(value -> (String) value.get("name")).toList();
            List<AutoScalingGroup> groups = autoScaling.describeAutoScalingGroups(region, names);
            for (AutoScalingGroup selected : groups) {
                selected.getInstances().stream().filter(instance -> "InService".equals(instance.getLifecycleState()))
                        .forEach(instance -> ids.add(instance.getInstanceId()));
            }
        }
        JsonNode filters = mapper.valueToTree(group.getEc2TagFilters());
        JsonNode tagSet = mapper.valueToTree(group.getEc2TagSet());
        if ((filters.isArray() && !filters.isEmpty()) || tagSet.isObject()) {
            ec2.describeInstances(region, List.of(), Map.of()).stream()
                    .flatMap(reservation -> reservation.getInstances().stream())
                    .filter(instance -> instance.getState() == null
                            || !Set.of("terminated", "shutting-down").contains(instance.getState().getName()))
                    .filter(instance -> matches(instance, filters, tagSet))
                    .forEach(instance -> ids.add(instance.getInstanceId()));
        }
        return List.copyOf(ids);
    }

    private static boolean matches(Instance instance, JsonNode filters, JsonNode tagSet) {
        if (filters.isArray() && !filters.isEmpty() && !matchesAny(instance, filters)) {
            return false;
        }
        if (tagSet.isObject()) {
            JsonNode groups = tagSet.path("ec2TagSetList");
            if (!groups.isArray() || groups.isEmpty()) {
                return false;
            }
            for (JsonNode group : groups) {
                if (!matchesAny(instance, group)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matchesAny(Instance instance, JsonNode filters) {
        for (JsonNode filter : filters) {
            String type = filter.path("Type").asText("KEY_AND_VALUE");
            boolean matched = instance.getTags().stream().anyMatch(tag -> switch (type) {
                case "KEY_ONLY" -> tag.getKey().equals(filter.path("Key").asText());
                case "VALUE_ONLY" -> tag.getValue().equals(filter.path("Value").asText());
                case "KEY_AND_VALUE" -> tag.getKey().equals(filter.path("Key").asText())
                        && tag.getValue().equals(filter.path("Value").asText());
                default -> false;
            });
            if (matched) {
                return true;
            }
        }
        return false;
    }

    public CodeDeployAgentCommands.Session open(Prepared prepared, Deployment deployment) {
        Map<String, Object> specification = Map.of(
                "DeploymentId", deployment.getDeploymentId(),
                "DeploymentGroupId", prepared.groupId(),
                "DeploymentGroupName", deployment.getDeploymentGroupName(),
                "ApplicationName", deployment.getApplicationName(),
                "DeploymentCreator", "user",
                "DeploymentType", "IN_PLACE",
                "AgentActionOverrides", Map.of("AgentOverrides",
                        Map.of("FileExistsBehavior", prepared.fileExistsBehavior())),
                "Revision", Map.of("RevisionType", "S3", "S3Revision", prepared.source()));
        return commands.open(prepared.account(), prepared.region(), deployment.getDeploymentId(), prepared.groupId(),
                prepared.hosts().values(), specification);
    }

    public void run(Prepared prepared, CodeDeployAgentCommands.Session session, Deployment deployment,
                    List<Map<String, Object>> targets, AtomicBoolean stop) {
        List<CompletableFuture<Boolean>> workers = new ArrayList<>();
        try {
            deployment.setStartTime(now());
            deployment.setStatus("InProgress");
            int succeeded = 0;
            if (ALL_AT_ONCE.equals(prepared.config())) {
                for (Map<String, Object> target : targets) {
                    CompletableFuture<Boolean> result = new CompletableFuture<>();
                    workers.add(result);
                    Thread.ofVirtual().name("codedeploy-agent-" + target.get("targetId")).start(() -> {
                        try {
                            result.complete(runHost(prepared, session, target, stop));
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    });
                }
                for (CompletableFuture<Boolean> worker : workers) {
                    if (worker.get()) {
                        succeeded++;
                    }
                }
            } else {
                for (Map<String, Object> target : targets) {
                    if (!runHost(prepared, session, target, stop)) {
                        break;
                    }
                    succeeded++;
                }
            }
            if (stop.get()) {
                finish(deployment, "Stopped", null);
            } else {
                int required = ALL_AT_ONCE.equals(prepared.config()) ? 1 : Math.max(1, targets.size() - 1);
                boolean complete = succeeded >= required
                        && targets.stream().noneMatch(target -> "Pending".equals(target.get("status")));
                finish(deployment, complete ? "Succeeded" : "Failed", complete ? null : "HEALTH_CONSTRAINTS");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            finish(deployment, "Stopped", null);
        } catch (CancellationException failure) {
            finish(deployment, "Stopped", null);
        } catch (Exception failure) {
            LOG.warnv("Agent deployment {0} failed: {1}", deployment.getDeploymentId(), failure.getMessage());
            finish(deployment, stop.get() ? "Stopped" : "Failed", stop.get() ? null : "DeploymentFailed");
        } finally {
            commands.close(session);
            for (CompletableFuture<Boolean> worker : workers) {
                worker.handle((result, failure) -> null).join();
            }
            targets.stream().filter(target -> Set.of("Pending", "InProgress").contains(target.get("status")))
                    .forEach(target -> update(target, "Skipped"));
        }
    }

    private boolean runHost(Prepared prepared, CodeDeployAgentCommands.Session session,
                             Map<String, Object> target, AtomicBoolean stop)
            throws InterruptedException, ExecutionException {
        String host = prepared.hosts().get(target.get("targetId"));
        update(target, "InProgress");
        for (String phase : PHASES) {
            if (stop.get()) {
                throw new CancellationException("Deployment was stopped");
            }
            Map<String, Object> event = new ConcurrentHashMap<>();
            event.put("lifecycleEventName", phase);
            event.put("status", "Pending");
            events(target).add(event);
            try {
                CodeDeployAgentCommands.Ticket ticket = commands.enqueue(session, host, phase);
                await(ticket.accepted(), startTimeout, stop);
                event.put("startTime", now());
                event.put("status", "InProgress");
                CodeDeployAgentCommands.Result result = await(ticket.result(), phaseTimeout, stop);
                if (result.diagnostics() != null) {
                    event.put("diagnostics", diagnostics(result.diagnostics()));
                }
                event.put("endTime", now());
                event.put("status", result.status());
                if (!"Succeeded".equals(result.status())) {
                    update(target, "Failed");
                    return false;
                }
            } catch (TimeoutException failure) {
                commands.cancel(session, host);
                event.put("endTime", now());
                event.put("diagnostics", Map.of("errorCode", "UnknownError",
                        "message", phase + " did not " + ("Pending".equals(event.get("status")) ? "start" : "complete")
                                + " within the CodeDeploy lifecycle limit"));
                event.put("status", "Failed");
                update(target, "Failed");
                return false;
            } catch (CancellationException failure) {
                commands.cancel(session, host);
                event.put("endTime", now());
                event.put("status", "Skipped");
                update(target, "Skipped");
                throw failure;
            }
        }
        update(target, "Succeeded");
        return true;
    }

    private Map<String, String> diagnostics(String payload) {
        try {
            JsonNode body = mapper.readTree(payload);
            int code = body.path("error_code").asInt(5);
            return Map.of("errorCode", code >= 0 && code < DIAGNOSTIC_CODES.size()
                            ? DIAGNOSTIC_CODES.get(code) : "UnknownError",
                    "scriptName", body.path("script_name").asText(""),
                    "message", body.path("message").asText(""), "logTail", body.path("log").asText(""));
        } catch (JsonProcessingException failure) {
            return Map.of("errorCode", "UnknownError", "message", payload);
        }
    }

    private static <T> T await(CompletionStage<T> stage, Duration timeout, AtomicBoolean stop)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<T> future = stage.toCompletableFuture();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!stop.get()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException();
            }
            try {
                return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS);
            } catch (TimeoutException pending) {
                // Poll only to observe StopDeployment without changing the native lifecycle deadline.
            }
        }
        throw new CancellationException("Deployment was stopped");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(Map<String, Object> target) {
        return (List<Map<String, Object>>) target.computeIfAbsent("lifecycleEvents",
                ignored -> new CopyOnWriteArrayList<>());
    }

    private static void update(Map<String, Object> target, String status) {
        target.put("lastUpdatedAt", now());
        target.put("status", status);
    }

    private static void finish(Deployment deployment, String status, String error) {
        if (error != null) {
            deployment.setErrorInformation(Map.of("code", error, "message", "A host-agent deployment failed"));
        }
        deployment.setCompleteTime(now());
        deployment.setStatus(status);
    }

    private static double now() {
        return EpochSeconds.fromInstant(Instant.now());
    }

    private static AwsException unsupported(String message) {
        return new AwsException("InvalidDeploymentGroupException", message, 400);
    }

    public record Prepared(String account, String region, String groupId, String config,
                           Map<String, Object> source, Map<String, String> hosts, List<String> instanceIds,
                           String fileExistsBehavior) {}
}
