package io.github.hectorvent.floci.services.ec2;

/**
 * Dependencies read from the owning service's current account-scoped state.
 * Reference changes must hold {@link Ec2Service#attachmentTopologyLock(String)},
 * which EC2 holds while checking dependencies and deleting a subnet or VPC.
 */
public interface VpcDependencyProvider {
    boolean hasSubnetDependency(String region, String subnetId);

    boolean hasVpcDependency(String region, String vpcId);
}
