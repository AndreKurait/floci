package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.lambda.LambdaArnUtils;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.ProvisionedConcurrencyPool;
import io.floci.aws.services.lambda.model.LambdaAlias;
import io.floci.aws.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * CloudFormation provisioning for {@code AWS::Lambda::Version} and {@code AWS::Lambda::Alias}.
 *
 * <p>Covers only these two types, not {@code AWS::Lambda::Function}, which
 * {@code LambdaCfnProvisioner} owns. The
 * two types are what SAM's {@code AutoPublishAlias} expands into, and without them an
 * alias-qualified invoke ({@code <function>:production}) fails with "Alias not found" even though
 * the template declared the alias and the stack reported CREATE_COMPLETE.
 *
 * <p>The Lambda service already implements both operations, so this is plumbing rather than new
 * service behavior.
 */
@ApplicationScoped
public class LambdaVersionAliasCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(LambdaVersionAliasCfnProvisioner.class);
    private static final Duration READINESS_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration READINESS_POLL = Duration.ofMillis(200);

    private final LambdaService lambdaService;
    private final ProvisionedConcurrencyPool provisionedConcurrency;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    @Inject
    public LambdaVersionAliasCfnProvisioner(LambdaService lambdaService,
                                           ProvisionedConcurrencyPool provisionedConcurrency) {
        this(lambdaService, provisionedConcurrency, System::nanoTime, Thread::sleep);
    }

    LambdaVersionAliasCfnProvisioner(LambdaService lambdaService,
                                     ProvisionedConcurrencyPool provisionedConcurrency,
                                     LongSupplier nanoTime, Sleeper sleeper) {
        this.lambdaService = lambdaService;
        this.provisionedConcurrency = provisionedConcurrency;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Lambda::Version", "AWS::Lambda::Alias");
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        if ("AWS::Lambda::Alias".equals(resource.getResourceType())) {
            ReplacementCleanup.retainExhaustedOrphansForRetry(resource, "AWS::Lambda::Alias");
        }
        ReplacementCleanup.clear(resource);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case "AWS::Lambda::Version" -> provisionVersion(r, props, ctx);
            case "AWS::Lambda::Alias" -> provisionAlias(r, props, ctx);
            default -> throw new IllegalStateException(
                    "LambdaVersionAliasCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if ("AWS::Lambda::Version".equals(resourceType)) {
            LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolveWithRegion(region, physicalId, null);
            String version = ref.qualifier();
            if (version == null || !version.chars().allMatch(value -> value >= '0' && value <= '9')) {
                // Older stacks can carry a generic placeholder. Never turn that into a whole-function delete.
                LOG.debugv("Not a published version ARN, nothing to delete: {0}", physicalId);
                return;
            }
            try {
                lambdaService.deleteFunction(region, physicalId, version);
            } catch (AwsException failure) {
                if (failure.getHttpStatus() != 404 || !"ResourceNotFoundException".equals(failure.getErrorCode())) {
                    throw failure;
                }
                LOG.debugv("Version {0} already absent: {1}", physicalId, failure.getMessage());
            }
            return;
        }
        if (!"AWS::Lambda::Alias".equals(resourceType)) {
            return;
        }
        // The physical id is the alias ARN (arn:…:function:<name>:<alias>), so the function name
        // and alias name come back out of it.
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve(physicalId);
        if (ref.qualifier() == null) {
            LOG.debugv("Not an alias ARN, nothing to delete: {0}", physicalId);
            return;
        }
        try {
            lambdaService.deleteAlias(region, ref.name(), ref.qualifier());
        } catch (AwsException e) {
            if (e.getHttpStatus() != 404 || !"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            // Already gone (function deleted first, or a partial rollback) — deleting a stack
            // must stay idempotent.
            LOG.debugv("Alias {0} already absent: {1}", physicalId, e.getMessage());
        }
    }

    private void provisionVersion(StackResource r, JsonNode props, ProvisionContext ctx) {
        String functionName = ctx.resolveOptional(props, "FunctionName");
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalArgumentException(
                    "AWS::Lambda::Version requires FunctionName (" + r.getLogicalId() + ")");
        }
        String description = ctx.resolveOptional(props, "Description");

        // Version resources are immutable in CloudFormation. An update re-invokes provision with
        // the prior physical id, and publishing again would mint a new version and repoint every
        // Ref to it, so the version this resource already published is kept while it still exists.
        String previous = r.getPhysicalId();
        if (previous != null && existingVersionIsCurrent(ctx.region(), functionName, previous)) {
            r.getAttributes().put("Version", previous.substring(previous.lastIndexOf(':') + 1));
            r.getAttributes().put("FunctionArn", previous);
            return;
        }

        LambdaFunction version = lambdaService.publishVersion(ctx.region(), functionName, description);

        // Matches real CloudFormation: Ref returns the qualified ARN, GetAtt Version the version
        // number (what the Alias resource references), GetAtt FunctionArn the qualified ARN.
        r.setPhysicalId(version.getFunctionArn());
        r.getAttributes().put("Version", version.getVersion());
        r.getAttributes().put("FunctionArn", version.getFunctionArn());
    }

    /** True when {@code physicalId} names a still-published version of the function in the template. */
    private boolean existingVersionIsCurrent(String region, String functionName, String physicalId) {
        int sep = physicalId.lastIndexOf(':');
        if (sep <= 0) {
            return false;
        }
        String version = physicalId.substring(sep + 1);
        String baseArn = physicalId.substring(0, sep);
        try {
            LambdaFunction fn = lambdaService.getFunction(region, functionName);
            if (!baseArn.equals(fn.getFunctionArn().replace(":$LATEST", ""))) {
                return false; // the FunctionName property now points at a different function
            }
            return lambdaService.listVersionsByFunction(region, functionName).stream()
                    .anyMatch(v -> version.equals(v.getVersion()));
        } catch (AwsException e) {
            return false;
        }
    }

    private void provisionAlias(StackResource r, JsonNode props, ProvisionContext ctx) {
        String functionName = ctx.resolveOptional(props, "FunctionName");
        String aliasName = ctx.resolveOptional(props, "Name");
        if (functionName == null || functionName.isBlank() || aliasName == null || aliasName.isBlank()) {
            throw new IllegalArgumentException(
                    "AWS::Lambda::Alias requires FunctionName and Name (" + r.getLogicalId() + ")");
        }
        String functionVersion = ctx.resolveOptional(props, "FunctionVersion");
        String description = ctx.resolveOptional(props, "Description");

        Map<String, Double> routingConfig = parseRoutingConfig(props, ctx);
        Integer provisionedCount = provisionedCount(props, ctx);

        LambdaAlias alias;
        boolean created = false;
        try {
            alias = lambdaService.createAlias(ctx.region(), functionName, aliasName,
                    functionVersion, description, routingConfig);
            created = true;
            r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        } catch (AwsException e) {
            // An alias surviving from a prior deploy makes create conflict; converge on it rather
            // than failing the stack, so a redeploy is idempotent.
            if (!"ResourceConflictException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Alias {0} exists on {1}, updating instead", aliasName, functionName);
            // An absent RoutingConfig clears any previous weights, since an empty map clears in
            // LambdaService#updateAlias while null keeps what is there.
            alias = lambdaService.updateAlias(ctx.region(), functionName, aliasName,
                    functionVersion, description, routingConfig != null ? routingConfig : Map.of());
            if (description == null && ctx.droppedProperties(props).contains("Description")) {
                alias = lambdaService.replaceAliasDescription(ctx.region(), functionName, aliasName, null);
            }
        }

        r.setPhysicalId(alias.getAliasArn());
        r.getAttributes().put("AliasArn", alias.getAliasArn());
        Runnable reconcileCapacity = () -> {
            if (provisionedCount != null) {
                ProvisionedConcurrencyPool.Status requested = provisionedConcurrency.put(
                        ctx.region(), functionName, aliasName, provisionedCount, ctx.accountId());
                awaitProvisionedConcurrency(functionName, aliasName, provisionedCount, requested, ctx);
            } else if (ctx.previouslyDeclared("ProvisionedConcurrencyConfig")) {
                provisionedConcurrency.delete(ctx.region(), functionName, aliasName, ctx.accountId());
            }
        };
        if (created && ctx.isUpdate()) {
            CfnRollback.guarded(r, ctx, true,
                    () -> delete("AWS::Lambda::Alias", r.getPhysicalId(), ctx.region()),
                    () -> { }, reconcileCapacity);
        } else {
            reconcileCapacity.run();
        }
        r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
    }

    private Integer provisionedCount(JsonNode props, ProvisionContext ctx) {
        JsonNode raw = props.get("ProvisionedConcurrencyConfig");
        if (raw == null || raw.isNull()) {
            return null;
        }
        JsonNode config = ctx.engine().resolveNodeOmittingNoValue(raw);
        if (config == null || config.isNull() || config.isMissingNode()) {
            return null;
        }
        if (!config.isObject()) {
            throw new AwsException("ValidationError", "ProvisionedConcurrencyConfig must be an object.", 400);
        }
        Integer count = ctx.resolveOptionalInt(config, "ProvisionedConcurrentExecutions");
        if (count == null || count < 1) {
            throw new AwsException("ValidationError",
                    "ProvisionedConcurrencyConfig requires a positive ProvisionedConcurrentExecutions.", 400);
        }
        return count;
    }

    private void awaitProvisionedConcurrency(String functionName, String aliasName, int requested,
                                            ProvisionedConcurrencyPool.Status status, ProvisionContext ctx) {
        long started = nanoTime.getAsLong();
        while (true) {
            if ("READY".equals(status.status()) && status.requested() == requested
                    && status.allocated() == requested) {
                return;
            }
            if ("FAILED".equals(status.status()) || nanoTime.getAsLong() - started >= READINESS_TIMEOUT.toNanos()) {
                throw new AwsException("InternalFailure", "Provisioned concurrency for alias " + aliasName
                        + " did not become READY (status " + status.status() + "): "
                        + (status.reason() == null ? "initialization deadline exceeded" : status.reason()), 500);
            }
            try {
                sleeper.sleep(READINESS_POLL);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AwsException("InternalFailure",
                        "Interrupted while initializing provisioned concurrency for alias " + aliasName, 500);
            }
            status = provisionedConcurrency.get(ctx.region(), functionName, aliasName, ctx.accountId());
        }
    }

    /**
     * Parses the CloudFormation {@code RoutingConfig.AdditionalVersionWeights} list
     * ({@code [{FunctionVersion, FunctionWeight}]}) into the service's version-to-weight map.
     */
    private Map<String, Double> parseRoutingConfig(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("RoutingConfig") || props.get("RoutingConfig").isNull()) {
            return null;
        }
        Map<String, Double> routing = new LinkedHashMap<>();
        JsonNode weights = props.get("RoutingConfig").path("AdditionalVersionWeights");
        if (weights.isArray()) {
            for (JsonNode weight : weights) {
                String version = ctx.engine().resolve(weight.get("FunctionVersion"));
                routing.put(version, Double.parseDouble(ctx.engine().resolve(weight.get("FunctionWeight"))));
            }
        }
        return routing;
    }
}
