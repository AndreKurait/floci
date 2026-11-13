package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.codedeploy.model.DeploymentGroup;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** The CodeDeploy service role owns the launch hooks attached to its deployment group. */
@ApplicationScoped
public class CodeDeployLaunchHooks {
    private static final int LAUNCH_HEARTBEAT_TIMEOUT_SECONDS = 600;
    private final AutoScalingService scaling;
    private final ServiceRoleCallAuthorizer authorizer;
    private final RegionResolver regionResolver;

    @Inject
    public CodeDeployLaunchHooks(AutoScalingService scaling, ServiceRoleCallAuthorizer authorizer,
                                 RegionResolver regionResolver) {
        this.scaling = scaling;
        this.authorizer = authorizer;
        this.regionResolver = regionResolver;
    }

    List<Map<String, Object>> configure(String region, DeploymentGroup previous, DeploymentGroup desired) {
        List<Map<String, Object>> requested = groups(desired);
        if (!requested.isEmpty() && !"Server".equals(desired.getComputePlatform())) {
            throw new AwsException("InvalidAutoScalingGroupException",
                    "Auto Scaling groups require the Server compute platform", 400);
        }
        Map<String, AutoScalingGroup> selected = new LinkedHashMap<>();
        for (Map<String, Object> reference : requested) {
            if (!(reference.get("name") instanceof String name) || name.isBlank()) {
                throw new AwsException("InvalidAutoScalingGroupException", "An Auto Scaling group name is required", 400);
            }
            AutoScalingGroup group = find(region, name);
            if (group == null) {
                throw new AwsException("InvalidAutoScalingGroupException", "Auto Scaling group not found: " + name, 400);
            }
            requirePermission(region, desired, group, "PutLifecycleHook");
            selected.put(name, group);
        }
        List<Map<String, Object>> removed = groups(previous).stream()
                .filter(reference -> !selected.containsKey(reference.get("name"))).toList();
        String hook = hookName(desired);
        List<String> installed = new ArrayList<>();
        try {
            for (String name : selected.keySet()) {
                boolean exists = !scaling.describeLifecycleHooks(region, name, List.of(hook)).isEmpty();
                scaling.putLifecycleHook(region, name, hook, "autoscaling:EC2_INSTANCE_LAUNCHING",
                        null, null, null, LAUNCH_HEARTBEAT_TIMEOUT_SECONDS, "ABANDON");
                if (!exists) {
                    installed.add(name);
                }
            }
        } catch (RuntimeException failure) {
            for (String name : installed) {
                try {
                    scaling.deleteLifecycleHook(region, name, hook);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        desired.setAutoScalingGroups(selected.keySet().stream()
                .map(name -> Map.<String, Object>of("name", name, "hook", hook)).toList());
        return delete(region, desired, removed);
    }

    List<Map<String, Object>> delete(String region, DeploymentGroup group) {
        return delete(region, group, groups(group));
    }

    private List<Map<String, Object>> delete(String region, DeploymentGroup group,
                                              List<Map<String, Object>> references) {
        List<Map<String, Object>> remaining = new ArrayList<>();
        for (Map<String, Object> reference : references) {
            AutoScalingGroup scalingGroup = find(region, (String) reference.get("name"));
            if (scalingGroup == null || scaling.describeLifecycleHooks(region, scalingGroup.getAutoScalingGroupName(),
                    List.of(hookName(group))).isEmpty()) {
                continue;
            }
            if (!allowed(region, group, scalingGroup, "DeleteLifecycleHook")) {
                remaining.add(Map.of("name", scalingGroup.getAutoScalingGroupName(), "hook", hookName(group)));
                continue;
            }
            try {
                delete(region, group, reference);
            } catch (AwsException failure) {
                if (find(region, (String) reference.get("name")) != null) {
                    remaining.add(Map.of("name", scalingGroup.getAutoScalingGroupName(), "hook", hookName(group)));
                }
            }
        }
        return List.copyOf(remaining);
    }

    void heartbeat(String region, DeploymentGroup group, String scalingGroup, String instance, String token) {
        AutoScalingGroup selected = requireGroup(region, scalingGroup);
        requirePermission(region, group, selected, "RecordLifecycleActionHeartbeat");
        scaling.recordLifecycleActionHeartbeat(region, scalingGroup, hookName(group), instance, token);
    }

    void complete(String region, DeploymentGroup group, String scalingGroup,
                  String instance, String token, String result) {
        AutoScalingGroup selected = requireGroup(region, scalingGroup);
        requirePermission(region, group, selected, "CompleteLifecycleAction");
        scaling.completeLifecycleAction(region, scalingGroup, hookName(group), instance, result, token);
    }

    private void delete(String region, DeploymentGroup group, Map<String, Object> reference) {
        String name = (String) reference.get("name");
        if (find(region, name) != null) {
            scaling.deleteLifecycleHook(region, name, hookName(group));
        }
    }

    private AutoScalingGroup requireGroup(String region, String name) {
        AutoScalingGroup group = find(region, name);
        if (group == null) {
            throw new AwsException("InvalidAutoScalingGroupException", "Auto Scaling group not found: " + name, 400);
        }
        return group;
    }

    private AutoScalingGroup find(String region, String name) {
        return scaling.describeAutoScalingGroups(region, List.of(name)).stream().findFirst().orElse(null);
    }

    private void requirePermission(String region, DeploymentGroup owner, AutoScalingGroup group, String action) {
        if (!allowed(region, owner, group, action)) {
            throw new AwsException("InvalidRoleException",
                    "The CodeDeploy service role cannot perform autoscaling:" + action
                            + " for " + group.getAutoScalingGroupName(), 400);
        }
    }

    private boolean allowed(String region, DeploymentGroup owner, AutoScalingGroup group, String action) {
        Map<String, List<String>> tags = new HashMap<>();
        group.getTags().forEach((key, value) -> {
            tags.put("aws:ResourceTag/" + key, List.of(value));
            tags.put("autoscaling:ResourceTag/" + key, List.of(value));
        });
        return authorizer.authorizeWithoutSourceContext("codedeploy", owner.getServiceRoleArn(),
                "autoscaling:" + action, group.getAutoScalingGroupArn(), regionResolver.getAccountId(),
                AwsRegions.partitionFor(region), tags) == Decision.ALLOW;
    }

    static String hookName(DeploymentGroup group) {
        return "CodeDeploy-managed-automatic-launch-deployment-hook-" + group.getDeploymentGroupId();
    }

    static Set<String> groupNames(DeploymentGroup group) {
        return groups(group).stream().map(reference -> (String) reference.get("name")).collect(Collectors.toSet());
    }

    private static List<Map<String, Object>> groups(DeploymentGroup group) {
        return group == null || group.getAutoScalingGroups() == null ? List.of() : group.getAutoScalingGroups();
    }
}
