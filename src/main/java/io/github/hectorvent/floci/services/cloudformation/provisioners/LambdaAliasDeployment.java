package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Runs the CodeDeploy deployment declared by a CloudFormation alias update policy. */
@ApplicationScoped
public class LambdaAliasDeployment {
    static final String DEPLOYMENT_ATTR = "__FlociLambdaAliasDeployment";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private final CodeDeployService service;
    private final Duration timeout;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    @Inject
    public LambdaAliasDeployment(CodeDeployService service) {
        this(service, Duration.ofHours(24), System::nanoTime, Thread::sleep);
    }

    LambdaAliasDeployment(CodeDeployService service, Duration timeout, LongSupplier nanoTime, Sleeper sleeper) {
        this.service = service;
        this.timeout = timeout;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }

    static JsonNode policy(ProvisionContext ctx) {
        if (ctx.updatePolicy() == null) {
            return null;
        }
        return resolvedPolicy(ctx.engine().resolveNodeOmittingNoValue(ctx.updatePolicy()));
    }

    static JsonNode resolvedPolicy(JsonNode update) {
        if (update == null || update.isNull() || update.isMissingNode()) {
            return null;
        }
        if (!update.isObject()) {
            throw invalid("UpdatePolicy must be an object.");
        }
        if (!update.hasNonNull("CodeDeployLambdaAliasUpdate")) {
            return null;
        }
        JsonNode policy = update.get("CodeDeployLambdaAliasUpdate");
        if (!policy.isObject()) {
            throw invalid("CodeDeployLambdaAliasUpdate must be an object.");
        }
        for (String name : Set.of("ApplicationName", "DeploymentGroupName",
                "BeforeAllowTrafficHook", "AfterAllowTrafficHook")) {
            JsonNode value = policy.get(name);
            boolean required = name.equals("ApplicationName") || name.equals("DeploymentGroupName");
            if (value == null && !required) {
                continue;
            }
            if (value == null || !value.isTextual() || value.asText().isBlank()) {
                throw invalid("CodeDeployLambdaAliasUpdate requires a nonempty string " + name + ".");
            }
        }
        return policy;
    }

    void shift(StackResource resource, String region, LambdaAlias current, String target,
               JsonNode policy, Consumer<StackEvent> progress) {
        if (policy == null) {
            return;
        }
        if (target == null || target.isBlank()) {
            throw invalid("CodeDeployLambdaAliasUpdate requires an alias FunctionVersion.");
        }
        if (target.equals(current.getFunctionVersion())) {
            return;
        }
        ObjectNode content = MAPPER.createObjectNode().put("version", "0.0");
        ObjectNode function = content.putArray("Resources").addObject().putObject("Function");
        function.put("Type", "AWS::Lambda::Function");
        function.putObject("Properties").put("Name", current.getFunctionName()).put("Alias", current.getName())
                .put("CurrentVersion", current.getFunctionVersion()).put("TargetVersion", target);
        ArrayNode hooks = MAPPER.createArrayNode();
        if (policy.has("BeforeAllowTrafficHook")) {
            hooks.addObject().set("BeforeAllowTraffic", policy.get("BeforeAllowTrafficHook"));
        }
        if (policy.has("AfterAllowTrafficHook")) {
            hooks.addObject().set("AfterAllowTraffic", policy.get("AfterAllowTrafficHook"));
        }
        if (!hooks.isEmpty()) {
            content.set("Hooks", hooks);
        }
        String deployment = service.createDeployment(region, policy.path("ApplicationName").asText(),
                policy.path("DeploymentGroupName").asText(), null,
                Map.of("revisionType", "AppSpecContent", "appSpecContent", Map.of("content", content.toString())),
                "CloudFormation alias update " + resource.getLogicalId());
        resource.getAttributes().put(DEPLOYMENT_ATTR, deployment);
        ProvisionContext.report(progress, resource.getPhysicalId(), "UPDATE_IN_PROGRESS",
                "CodeDeploy deployment " + deployment + " started");
        awaitTerminal(resource, region, deployment, progress, true);
    }

    void stopBeforeRestore(StackResource resource, String region, Consumer<StackEvent> progress) {
        String deployment = resource.getAttributes().get(DEPLOYMENT_ATTR);
        if (deployment != null) {
            Deployment current = service.getDeployment(region, deployment);
            if (!terminal(current.getStatus())) {
                service.stopDeployment(region, deployment);
                awaitTerminal(resource, region, deployment, progress, false);
            }
        }
    }

    private void awaitTerminal(StackResource resource, String region, String identifier,
                               Consumer<StackEvent> progress, boolean requireSuccess) {
        long started = nanoTime.getAsLong();
        String lastState = null;
        while (true) {
            Deployment deployment = service.getDeployment(region, identifier);
            String state = deployment.getStatus();
            if (terminal(state)) {
                if (requireSuccess && !"Succeeded".equals(state)) {
                    throw invalid("CodeDeploy deployment " + identifier + " ended in " + state
                            + ": " + deployment.getErrorInformation());
                }
                return;
            }
            if (state != null && !state.equals(lastState)) {
                ProvisionContext.report(progress, resource.getPhysicalId(), "UPDATE_IN_PROGRESS",
                        "CodeDeploy deployment " + identifier + " is " + state);
                lastState = state;
            }
            long remaining = timeout.toNanos() - (nanoTime.getAsLong() - started);
            if (remaining <= 0) {
                service.stopDeployment(region, identifier);
                throw invalid("CodeDeploy deployment " + identifier + " did not finish within "
                        + timeout.toSeconds() + " seconds.");
            }
            try {
                sleeper.sleep(Duration.ofNanos(Math.min(remaining, POLL_INTERVAL.toNanos())));
            } catch (InterruptedException failure) {
                service.stopDeployment(region, identifier);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for CodeDeploy deployment " + identifier,
                        failure);
            }
        }
    }

    private static boolean terminal(String status) {
        return "Succeeded".equals(status) || "Failed".equals(status) || "Stopped".equals(status);
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }
}
