package io.github.hectorvent.floci.services.ec2;

import java.util.List;

/**
 * A service whose traffic to protected endpoints Floci sends from its own address on behalf of a
 * resource with security groups, such as a load balancer's health checks and forwarded requests.
 * Call {@link SecurityGroupFirewallManager#emulatorOriginsChanged()} when its answer changes.
 */
public interface EmulatorOriginSource {

    /** Every such resource in every account. */
    List<SecurityGroupNftCompiler.EmulatorOrigin> emulatorOrigins();
}
