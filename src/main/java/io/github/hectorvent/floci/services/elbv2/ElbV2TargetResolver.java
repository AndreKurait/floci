package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.SsrfProtection;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Objects;
import java.util.Set;

final class ElbV2TargetResolver {

    /**
     * Stands for a protected target outside its target group's VPC, which no connection reaches. It
     * is not a valid host name, so no URL or resolver can turn it into some other host.
     */
    static final String UNREACHABLE = "<unreachable protected target>";

    private ElbV2TargetResolver() {}

    /**
     * As {@link #resolveHost(Ec2Service, TargetGroup, String)}, then, unless {@code origin} (the
     * listener or load balancer the traffic is for) is an application load balancer's, the address
     * Floci dials under security-group enforcement ({@link SecurityGroupFirewallManager#emulatorRoute}).
     * A protected endpoint of another VPC or account, one whose ingress does not admit tcp {@code port}
     * from the origin's own {@code originGroups}, and a host name in an ip target group, are not
     * dialled ({@link #UNREACHABLE}). ALB traffic is left as it is: the ALB reaches its targets from its
     * own VPC interfaces. The ec2 model's {@code Instance} is imported here, so CDI's is qualified.
     */
    static String resolveHost(Ec2Service ec2Service,
                              jakarta.enterprise.inject.Instance<SecurityGroupFirewallManager> firewall,
                              TargetGroup targetGroup, String targetId, String origin,
                              int port, Set<String> originGroups) {
        String host = resolveHost(ec2Service, targetGroup, targetId);
        if (targetGroup == null || host == null || applicationLoadBalancer(origin)) {
            return host;
        }
        if (!isIpLiteral(host)) {
            // AWS takes only IP addresses as ip targets. A name could resolve to a protected endpoint
            // of another VPC or account, so under enforcement it is not dialled.
            return "ip".equals(targetGroup.getTargetType()) && enforcing(firewall) ? UNREACHABLE : host;
        }
        String arn = targetGroup.getTargetGroupArn();
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.looksLikeArn(arn) ? AwsArnUtils.parse(arn) : null;
        } catch (IllegalArgumentException e) {
            parsed = null;
        }
        if (parsed == null) {
            // Without the group's account and region nothing can show the target is its own.
            return enforcing(firewall) ? UNREACHABLE : host;
        }
        String routed = SecurityGroupFirewallManager.emulatorRoute(firewall, parsed.accountId(), parsed.region(),
                targetGroup.getVpcId(), host, port, originGroups);
        return routed != null ? routed : UNREACHABLE;
    }

    static String resolveHost(Ec2Service ec2Service,
                              jakarta.enterprise.inject.Instance<SecurityGroupFirewallManager> firewall,
                              TargetGroup targetGroup, TargetDescription target, String origin,
                              int port, Set<String> originGroups) {
        return resolveHost(ec2Service, firewall, targetGroup, target != null ? target.getId() : null, origin,
                port, originGroups);
    }

    /** Whether a load balancer or listener ARN is an application load balancer's. */
    static boolean applicationLoadBalancer(String arn) {
        return arn != null && (arn.contains(":loadbalancer/app/") || arn.contains(":listener/app/"));
    }

    /** The load balancer whose transport health-checks {@code group}, as ElbV2VpcNetworkManager picks it. */
    static String healthCheckOrigin(TargetGroup group) {
        return group == null || group.getLoadBalancerArns() == null ? null
                : group.getLoadBalancerArns().stream().filter(Objects::nonNull).sorted().findFirst()
                        .orElse(null);
    }

    private static boolean enforcing(jakarta.enterprise.inject.Instance<SecurityGroupFirewallManager> firewall) {
        return firewall != null && firewall.isResolvable() && firewall.get().enabled();
    }

    static String resolveHost(Ec2Service ec2Service, TargetGroup targetGroup, TargetDescription target) {
        return resolveHost(ec2Service, targetGroup, target != null ? target.getId() : null);
    }

    static String resolveHost(Ec2Service ec2Service, TargetGroup targetGroup, String targetId) {
        if (targetId == null || targetId.isBlank()) {
            return targetId;
        }
        if (targetGroup == null || !"instance".equals(targetGroup.getTargetType()) || ec2Service == null) {
            return targetId;
        }

        String accountId = extractAccountId(targetGroup.getTargetGroupArn());
        Instance instance = accountId != null
                ? ec2Service.findInstanceById(accountId, targetId)
                : ec2Service.findInstanceById(targetId);
        if (instance == null) {
            return targetId;
        }

        String containerBridgeIp = instance.getContainerBridgeIp();
        if (containerBridgeIp != null && !containerBridgeIp.isBlank()) {
            return containerBridgeIp;
        }
        return targetId;
    }

    /** Resolves once, rejects metadata addresses, and returns the address to connect to instead of the name. */
    static String resolveCheckedAddress(String host) throws IOException {
        if (UNREACHABLE.equals(host)) {
            throw new IOException("The target is a protected endpoint outside its target group's VPC");
        }
        InetAddress[] addresses = SsrfProtection.rejectMetadataAddresses(InetAddress.getAllByName(host), host);
        return addresses[0].getHostAddress();
    }

    static boolean isIpLiteral(String host) {
        return host != null && (host.contains(":") || host.matches("[0-9.]+"));
    }

    private static String extractAccountId(String targetGroupArn) {
        if (!AwsArnUtils.looksLikeArn(targetGroupArn)) {
            return null;
        }
        try {
            return AwsArnUtils.parse(targetGroupArn).accountId();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
