package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.services.appconfig.AppConfigService;
import io.github.hectorvent.floci.services.appconfig.DeploymentStopException;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Starts an AppConfig deployment and waits through its rollout and final bake. */
@ApplicationScoped
public class AppConfigDeploymentCfnProvisioner implements CfnResourceProvisioner {
    private static final Logger LOG = Logger.getLogger(AppConfigDeploymentCfnProvisioner.class);
    private static final String TYPE = "AWS::AppConfig::Deployment";
    private static final String PROPERTIES_ATTR = CfnRollback.USES_ATTR_PREFIX + "AppConfigDeploymentProperties";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration TIMEOUT = Duration.ofHours(24);

    private final AppConfigService service;
    private final ServiceConfigAccess serviceConfig;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    @Inject
    public AppConfigDeploymentCfnProvisioner(AppConfigService service, ServiceConfigAccess serviceConfig) {
        this(service, serviceConfig, System::nanoTime, Thread::sleep);
    }

    AppConfigDeploymentCfnProvisioner(AppConfigService service, ServiceConfigAccess serviceConfig,
                                    LongSupplier nanoTime, Sleeper sleeper) {
        this.service = service;
        this.serviceConfig = serviceConfig;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public boolean serviceDisabled() {
        return !serviceConfig.isEnabled("appconfig");
    }

    @Override
    public String serviceEnabledFlag() {
        return "floci.services.appconfig.enabled";
    }

    @Override
    public String referenceValue(String physicalId) {
        String[] parts = physicalId.split("\\|", -1);
        // An older stack can still carry a dispatcher stub until it replaces this resource.
        return parts.length == 3 ? parts[2] : physicalId;
    }

    @Override
    public String publicPhysicalId(String physicalId) {
        return physicalId == null ? null : referenceValue(physicalId);
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(resource);
        }
        JsonNode resolved = ctx.engine().resolveNode(properties);
        if (resolved == null || !resolved.isObject()) {
            throw invalid(TYPE + " requires Properties.");
        }
        for (String unsupported : Set.of("KmsKeyIdentifier", "DynamicExtensionParameters", "Tags")) {
            if (resolved.hasNonNull(unsupported)) {
                throw invalid(TYPE + " does not yet support " + unsupported + ".");
            }
        }
        String app = text(resolved, "ApplicationId", 1, 64);
        String environment = text(resolved, "EnvironmentId", 1, 64);
        Map<String, Object> request = new HashMap<>();
        request.put("ConfigurationProfileId", text(resolved, "ConfigurationProfileId", 1, 128));
        request.put("ConfigurationVersion", text(resolved, "ConfigurationVersion", 1, 1024));
        request.put("DeploymentStrategyId", text(resolved, "DeploymentStrategyId", 1, 128));
        if (resolved.hasNonNull("Description")) {
            request.put("Description", text(resolved, "Description", 0, 1024));
        }
        if (ctx.isUpdate() && resolved.toString().equals(resource.getAttributes().get(PROPERTIES_ATTR))
                && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), resource.getAttributes())) {
            return;
        }
        Map<String, String> before = Map.copyOf(resource.getAttributes());
        Deployment deployment = service.startDeployment(app, environment, request);
        resource.setPhysicalId(deployment.getApplicationId() + "|" + deployment.getEnvironmentId()
                + "|" + deployment.getDeploymentNumber());
        resource.getAttributes().put("DeploymentNumber", Integer.toString(deployment.getDeploymentNumber()));
        CfnRollback.guarded(resource, ctx, true,
                () -> delete(TYPE, resource.getPhysicalId(), ctx.region()), () -> {},
                () -> awaitComplete(resource, deployment, ctx));
        resource.getAttributes().put(PROPERTIES_ATTR, resolved.toString());
        ReplacementCleanup.record(resource, ctx, before);
    }

    private void awaitComplete(StackResource resource, Deployment started, ProvisionContext ctx) {
        long began = nanoTime.getAsLong();
        String lastProgress = null;
        while (true) {
            Deployment deployment = service.getDeployment(started.getApplicationId(),
                    started.getEnvironmentId(), started.getDeploymentNumber());
            String state = deployment.getState();
            if (state != null) {
                resource.getAttributes().put("State", state);
            }
            if ("COMPLETE".equals(state)) {
                return;
            }
            if (state == null || !Set.of("VALIDATING", "DEPLOYING", "BAKING").contains(state)) {
                throw invalid("AppConfig deployment " + resource.getPhysicalId() + " ended in " + state + ".");
            }
            String progress = state + " (" + deployment.getPercentageComplete() + "%)";
            if (!progress.equals(lastProgress)) {
                ProvisionContext.report(ctx.progress(), resource.getPhysicalId(),
                        ctx.isUpdate() ? "UPDATE_IN_PROGRESS" : "CREATE_IN_PROGRESS", progress);
                lastProgress = progress;
            }
            long remaining = TIMEOUT.toNanos() - (nanoTime.getAsLong() - began);
            if (remaining <= 0) {
                throw invalid("AppConfig deployment " + resource.getPhysicalId() + " did not complete within "
                        + TIMEOUT.toSeconds() + "s (state " + state + ").");
            }
            try {
                sleeper.sleep(Duration.ofNanos(Math.min(remaining, POLL_INTERVAL.toNanos())));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for AppConfig deployment "
                        + resource.getPhysicalId(), failure);
            }
        }
    }

    private static String text(JsonNode properties, String name, int minimum, int maximum) {
        JsonNode value = properties.get(name);
        if (value == null || !value.isTextual() || value.textValue().length() < minimum
                || value.textValue().length() > maximum) {
            throw invalid(TYPE + " requires a string " + name + " of " + minimum + " to " + maximum + " characters.");
        }
        return value.textValue();
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }

    @Override
    public void delete(StackResource resource, String region) {
        if (!CfnResourceDispatcher.isStub(resource.getPhysicalId(), resource.getAttributes())) {
            delete(resource.getResourceType(), resource.getPhysicalId(), region);
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        String[] parts = physicalId.split("\\|", -1);
        if (parts.length != 3) {
            throw new IllegalStateException("Invalid AppConfig deployment identity " + physicalId);
        }
        int number = Integer.parseInt(parts[2]);
        CfnDeletes.safeDelete(TYPE, physicalId, () -> {
            try {
                service.stopDeployment(parts[0], parts[1], number, true);
            } catch (DeploymentStopException alreadyHandled) {
                LOG.debugv("No AppConfig deployment revert on deletion of {0}: {1}",
                        physicalId, alreadyHandled.reason());
            }
        }, "ResourceNotFoundException");
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        ReplacementCleanup.rollback(resource, this::delete);
        return true;
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }
}
