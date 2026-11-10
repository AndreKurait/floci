package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.codedeploy.model.DeploymentGroup;
import io.github.hectorvent.floci.services.elb.ElbClassicHealthChecker;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** The service-owned Classic ELB phases surrounding an in-place host-agent deployment. */
@ApplicationScoped
public class CodeDeployClassicTraffic {
    private final ElbClassicService loadBalancers;
    private final ServiceRoleCallAuthorizer authorizer;
    private final ObjectMapper mapper;
    private final CodeDeployTimer timer;

    @Inject
    public CodeDeployClassicTraffic(ElbClassicService loadBalancers, ServiceRoleCallAuthorizer authorizer,
                                     ObjectMapper mapper) {
        this(loadBalancers, authorizer, mapper, CodeDeployTimer.SYSTEM);
    }

    CodeDeployClassicTraffic(ElbClassicService loadBalancers, ServiceRoleCallAuthorizer authorizer,
                             ObjectMapper mapper, CodeDeployTimer timer) {
        this.loadBalancers = loadBalancers;
        this.authorizer = authorizer;
        this.mapper = mapper;
        this.timer = timer;
    }

    Plan prepare(String account, String region, DeploymentGroup group) {
        JsonNode info = mapper.valueToTree(group.getLoadBalancerInfo());
        JsonNode names = info.path("elbInfoList");
        if (!names.isArray() || names.isEmpty() || hasTargets(info.path("targetGroupInfoList"))
                || hasTargets(info.path("targetGroupPairInfoList"))) {
            throw new AwsException("InvalidLoadBalancerInfoException",
                    "S3 agent traffic control requires Classic load balancers; target groups are not supported", 400);
        }
        Set<String> selected = new LinkedHashSet<>();
        for (JsonNode entry : names) {
            JsonNode name = entry.path("name");
            if (!name.isTextual() || name.asText().isBlank()) {
                throw new AwsException("InvalidLoadBalancerInfoException",
                        "Each Classic load balancer must have a name", 400);
            }
            selected.add(name.asText());
        }
        return new Plan(account, region, group.getServiceRoleArn(), List.copyOf(selected));
    }

    private static boolean hasTargets(JsonNode value) {
        return !value.isMissingNode() && !value.isNull() && (!value.isArray() || !value.isEmpty());
    }

    void change(Plan plan, String instance, boolean allow, Duration timeout, BooleanSupplier cancelled)
            throws InterruptedException, TimeoutException {
        long deadline = timer.nanoTime() + timeout.toNanos();
        String action = allow ? "RegisterInstancesWithLoadBalancer" : "DeregisterInstancesFromLoadBalancer";
        for (String name : plan.loadBalancers()) {
            checkCancelled(cancelled);
            RequestScopes.runAs(plan.account(), plan.region(), () -> {
                ClassicLoadBalancer loadBalancer = loadBalancers.requireLoadBalancer(plan.region(), name);
                authorizeWrite(plan, action, loadBalancer);
                checkCancelled(cancelled);
                if (allow) {
                    loadBalancers.registerInstances(plan.region(), name, List.of(instance));
                } else {
                    loadBalancers.deregisterInstances(plan.region(), name, List.of(instance));
                }
            });
        }
        while (true) {
            checkCancelled(cancelled);
            boolean complete = true;
            for (String name : plan.loadBalancers()) {
                checkCancelled(cancelled);
                if (!RequestScopes.callAs(plan.account(), plan.region(), () -> ready(plan, name, instance, allow))) {
                    complete = false;
                }
            }
            if (complete) {
                return;
            }
            long remaining = deadline - timer.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException(allow ? "Classic load balancer health did not become InService"
                        : "Classic load balancer connections did not finish draining");
            }
            timer.sleepNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)));
        }
    }

    private boolean ready(Plan plan, String name, String instance, boolean allow) {
        if (authorizer.authorizeUnscopedWithoutSourceContext("codedeploy", plan.roleArn(),
                "elasticloadbalancing:DescribeInstanceHealth", plan.account(), plan.region()) != Decision.ALLOW) {
            throw denied("DescribeInstanceHealth", name);
        }
        ElbClassicHealthChecker.InstanceHealth health =
                loadBalancers.describeInstanceHealth(plan.region(), name, List.of(instance)).get(instance);
        ClassicLoadBalancer loadBalancer = loadBalancers.requireLoadBalancer(plan.region(), name);
        boolean registered = loadBalancer.getInstanceIds().contains(instance);
        boolean draining = loadBalancers.deregisteringInstances(plan.region(), name).contains(instance);
        return allow ? registered && !draining && health != null && "InService".equals(health.state())
                : !registered && !draining;
    }

    private void authorizeWrite(Plan plan, String action, ClassicLoadBalancer loadBalancer) {
        String arn = AwsArnUtils.Arn.of("elasticloadbalancing", plan.region(), plan.account(),
                "loadbalancer/" + loadBalancer.getLoadBalancerName()).toString();
        Map<String, List<String>> tags = new HashMap<>();
        loadBalancer.getTags().forEach((key, value) -> {
            tags.put("aws:ResourceTag/" + key, List.of(value));
            tags.put("elasticloadbalancing:ResourceTag/" + key, List.of(value));
        });
        if (authorizer.authorizeWithoutSourceContext("codedeploy", plan.roleArn(),
                "elasticloadbalancing:" + action, arn, plan.account(), AwsRegions.partitionFor(plan.region()),
                tags) != Decision.ALLOW) {
            throw denied(action, loadBalancer.getLoadBalancerName());
        }
    }

    private static AwsException denied(String action, String name) {
        return new AwsException("IAM_ROLE_PERMISSIONS",
                "The CodeDeploy service role cannot perform elasticloadbalancing:" + action + " for " + name, 403);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Deployment was stopped");
        }
    }

    record Plan(String account, String region, String roleArn, List<String> loadBalancers) {}
}
