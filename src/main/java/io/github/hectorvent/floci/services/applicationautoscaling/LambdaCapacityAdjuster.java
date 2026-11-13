package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.ProvisionedConcurrencyPool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.regex.Pattern;

/** Connects scalable Lambda aliases and versions to their real provisioned environments. */
@ApplicationScoped
public class LambdaCapacityAdjuster implements CapacityAdjuster {

    private static final Logger LOG = Logger.getLogger(LambdaCapacityAdjuster.class);
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern QUALIFIER = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final ProvisionedConcurrencyPool pool;
    private final RegionResolver regionResolver;

    public LambdaCapacityAdjuster(ProvisionedConcurrencyPool pool, RegionResolver regionResolver) {
        this.pool = pool;
        this.regionResolver = regionResolver;
    }

    @Override
    public boolean supports(String scalableDimension) {
        return "lambda:function:ProvisionedConcurrency".equals(scalableDimension);
    }

    @Override
    public int getCurrentCapacity(String resourceId, String region) {
        FunctionTarget target = parse(resourceId);
        try {
            return pool.get(region, target.name(), target.qualifier(), regionResolver.getAccountId()).requested();
        } catch (AwsException error) {
            if (!"ProvisionedConcurrencyConfigNotFoundException".equals(error.getErrorCode())) {
                throw error;
            }
            LOG.debugv("No provisioned capacity is configured for {0} in {1}", resourceId, region);
            return 0;
        }
    }

    @Override
    public void setCapacity(String resourceId, int capacity, String region) {
        FunctionTarget target = parse(resourceId);
        if (capacity == 0) {
            pool.delete(region, target.name(), target.qualifier(), regionResolver.getAccountId());
        } else {
            pool.put(region, target.name(), target.qualifier(), capacity, regionResolver.getAccountId());
        }
    }

    void reconcileRegistration(String resourceId, int minimum, int maximum, String region) {
        parse(resourceId);
        if (minimum < 0 || maximum < minimum) {
            throw new AwsException("ValidationException",
                    "Capacity bounds must satisfy 0 <= MinCapacity <= MaxCapacity.", 400);
        }
        int current;
        try {
            current = getCurrentCapacity(resourceId, region);
        } catch (AwsException error) {
            if (!"ResourceNotFoundException".equals(error.getErrorCode())) {
                throw error;
            }
            // AWS registers inactive targets without changing the underlying resource's capacity.
            LOG.debugv("Lambda target {0} in {1} is not active; capacity is unchanged", resourceId, region);
            return;
        }
        int desired = Math.max(minimum, Math.min(maximum, current));
        if (desired != current) {
            setCapacity(resourceId, desired, region);
        }
    }

    static FunctionTarget parse(String resourceId) {
        String[] parts = resourceId == null ? new String[0] : resourceId.split(":", -1);
        if (parts.length != 3 || !"function".equals(parts[0])
                || !NAME.matcher(parts[1]).matches() || !QUALIFIER.matcher(parts[2]).matches()) {
            throw new AwsException("ValidationException",
                    "Lambda ResourceId must be function:<function-name>:<alias-or-version>.", 400);
        }
        return new FunctionTarget(parts[1], parts[2]);
    }

    record FunctionTarget(String name, String qualifier) {}
}
