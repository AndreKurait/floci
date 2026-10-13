# Auto Scaling

Floci implements the EC2 Auto Scaling API — stored-state management for launch configurations, auto scaling groups, lifecycle hooks, and scaling policies, plus a real capacity reconciler that launches and terminates EC2 instances to maintain desired capacity.

**Protocol:** Query — `POST /` with `Action=` form parameter, credential scope `autoscaling`

**ARN formats:**

- `arn:aws:autoscaling:<region>:<account>:autoScalingGroup:<uuid>:autoScalingGroupName/<name>`
- `arn:aws:autoscaling:<region>:<account>:launchConfiguration:<uuid>:launchConfigurationName/<name>`
- `arn:aws:autoscaling:<region>:<account>:scalingPolicy:<uuid>:autoScalingGroupName/<group>/policyName/<name>`

## Supported Operations (47 total)

### Launch Configurations

| Operation | Notes |
|---|---|
| `CreateLaunchConfiguration` | Stores template: `ImageId`, `InstanceType`, `KeyName`, `SecurityGroups`, `UserData`, `IamInstanceProfile`, `InstanceMonitoring`, `BlockDeviceMappings` |
| `DescribeLaunchConfigurations` | Filtered by name list; returns all if no filter |
| `DeleteLaunchConfiguration` | Removes the named launch configuration |

### Auto Scaling Groups

| Operation | Notes |
|---|---|
| `CreateAutoScalingGroup` | Creates a group with min/max/desired capacity, AZs, tags, launch configuration, launch template, or mixed instances policy; starts capacity reconciliation loop |
| `DescribeAutoScalingGroups` | Filtered by name list; returns all if no filter; includes current instance list with lifecycle state and mixed instances policy shape when configured |
| `UpdateAutoScalingGroup` | Updates capacity bounds, cooldown, launch source, AZs |
| `DeleteAutoScalingGroup` | `ForceDelete=true` terminates all instances before deletion |
| `EnableMetricsCollection` | Records the named group metrics, or all 20 when none are named, as `EnabledMetrics` on `DescribeAutoScalingGroups`. `Granularity` is required and must be `1Minute`; an unknown metric name raises `ValidationError`. No metrics are published to CloudWatch |
| `DisableMetricsCollection` | Removes the named metrics from `EnabledMetrics`, or all of them when none are named |

`CreateAutoScalingGroup` accepts both `subnet-a,subnet-b` and
`subnet-a, subnet-b` in `VPCZoneIdentifier`. `DescribeAutoScalingGroups` returns the
comma-only form. This matches a live AWS comparison in `us-east-1` on October 12,
2026 at 17:30 UTC: two subnets, matching Availability Zones, one launch template
and zero capacity. Both groups had identical configuration except for name, ARN
and creation time. The SDK regression is
`compatibility-tests/sdk-test-python/tests/test_autoscaling_subnet_whitespace.py`.

### Instance Management

| Operation | Notes |
|---|---|
| `DescribeAutoScalingInstances` | Returns all ASG-tracked instances with lifecycle state and health status |
| `SetDesiredCapacity` | Updates desired count; reconciler handles scale-out / scale-in within 10 s |
| `AttachInstances` | Attaches existing EC2 instances to a group; sets lifecycle state to `InService` |
| `DetachInstances` | Detaches instances from a group; optionally decrements desired capacity |
| `TerminateInstanceInAutoScalingGroup` | Terminates a specific instance; optionally decrements desired capacity |
| `SuspendProcesses` | Records suspended scaling processes (empty list suspends all); reported back by `DescribeAutoScalingGroups` |
| `ResumeProcesses` | Clears suspended processes (empty list resumes all) |

### Load Balancer Attachment

| Operation | Notes |
|---|---|
| `AttachLoadBalancerTargetGroups` | Attaches ELB v2 target group ARNs; new instances auto-registered on InService |
| `DetachLoadBalancerTargetGroups` | Detaches target groups; instances deregistered |
| `DescribeLoadBalancerTargetGroups` | Lists target groups attached to a group |
| `AttachLoadBalancers` | Classic ELB attachment (stored; no ELB v1 routing) |
| `DetachLoadBalancers` | Classic ELB detachment |
| `DescribeLoadBalancers` | Lists classic ELBs attached to a group |

### Traffic Sources

| Operation | Notes |
|---|---|
| `AttachTrafficSources` | Unified elb/elbv2/vpc-lattice attachment API; stored per identifier with its type |
| `DetachTrafficSources` | Removes a traffic source by identifier |
| `DescribeTrafficSources` | Lists attached sources, optionally filtered by type; every source reports `InService` |

### Lifecycle Hooks

| Operation | Notes |
|---|---|
| `PutLifecycleHook` | Creates or updates a hook: `LifecycleTransition`, `DefaultResult`, `HeartbeatTimeout` |
| `DescribeLifecycleHooks` | Lists hooks for a group |
| `DeleteLifecycleHook` | Removes a hook |
| `CompleteLifecycleAction` | Signals `CONTINUE` or `ABANDON` for a pending lifecycle action |
| `RecordLifecycleActionHeartbeat` | Extends the heartbeat timeout for an in-progress lifecycle action |

### Scaling Policies

| Operation | Notes |
|---|---|
| `PutScalingPolicy` | Creates or updates a policy: `SimpleScaling` fields or `TargetTrackingScaling` with predefined metric, target value, and estimated warmup |
| `DescribePolicies` | Lists policies filtered by group or policy name, including stored target tracking configuration |
| `DeletePolicy` | Removes a scaling policy |

### Scheduled Actions

| Operation | Notes |
|---|---|
| `PutScheduledUpdateGroupAction` | Creates or updates a schedule: recurrence, time zone, start/end time, capacity bounds |
| `DescribeScheduledActions` | Lists scheduled actions filtered by group or action name |
| `DeleteScheduledAction` | Removes a scheduled action |

### Warm Pools

| Operation | Notes |
|---|---|
| `PutWarmPool` | Full replace per the wire model: omitted fields reset to defaults; `MaxGroupPreparedCapacity=-1` clears the value |
| `DescribeWarmPool` | Returns the stored configuration; also embedded in `DescribeAutoScalingGroups` per the AutoScalingGroup shape |
| `DeleteWarmPool` | Removes the configuration; idempotent when none exists |

### Activities

| Operation | Notes |
|---|---|
| `DescribeScalingActivities` | Returns the activity log for a group; activities recorded on scale-out and scale-in events |

### Metadata

| Operation | Notes |
|---|---|
| `DescribeTerminationPolicyTypes` | Returns the standard termination policy names |
| `DescribeAccountLimits` | Returns max group / config / instance limits |
| `DescribeLifecycleHookTypes` | Returns `autoscaling:EC2_INSTANCE_LAUNCHING` and `autoscaling:EC2_INSTANCE_TERMINATING` |
| `DescribeAdjustmentTypes` | Returns the four standard adjustment types |
| `DescribeMetricCollectionTypes` | Returns standard metric and granularity names |
| `DescribeAutoScalingNotificationTypes` | Returns all notification type names |

## Capacity Reconciler (Phase 2)

Floci runs a background reconciler (10 s fixed rate) that keeps each group's InService instance count aligned with `DesiredCapacity`:

- **Scale-out**: calls `RunInstances` with the group's launch configuration; new instances are tracked as `Pending` until the EC2 state transitions to `running`, at which point they move to `InService` and are registered with all attached ELB v2 target groups.
- **Scale-in**: selects InService instances not protected from scale-in, deregisters them from target groups, then calls `TerminateInstances`.
- Activity records are written on each scale-out and scale-in event.

`SuspendProcesses` pauses desired-capacity scale-out for `Launch` and scale-in for
`Terminate`. The desired count remains stored, and `ResumeProcesses` allows the
next reconciliation pass to continue toward it. Omitting process names suspends
or resumes all processes. Suspending another process does not pause these capacity
adjustments. Suspension and resumption wait for any desired-capacity adjustment already
in progress for that group, so a completed suspension cannot be bypassed by an earlier
reconciliation check.

Suspension does not yet pause instance-refresh selection or replacement cleanup.
Explicitly requested termination and stale-instance cleanup continue independently.
Other suspended process names are stored and returned but do not pause their
associated activities.

## Launch Source Compatibility

Auto Scaling groups preserve either a launch configuration, a top-level launch template, or a `MixedInstancesPolicy`. These launch sources are mutually exclusive in create/update requests. When a mixed instances policy is supplied, Floci stores and returns:

- `LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId`
- `LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateName`
- `LaunchTemplate.LaunchTemplateSpecification.Version`
- `LaunchTemplate.Overrides.member.N.InstanceType`
- `LaunchTemplate.Overrides.member.N.WeightedCapacity`
- `LaunchTemplate.Overrides.member.N.InstanceRequirements`
- `InstancesDistribution.OnDemandBaseCapacity`
- `InstancesDistribution.OnDemandPercentageAboveBaseCapacity`
- `InstancesDistribution.SpotAllocationStrategy`

An override selects instance types either by name or by attribute. Setting both `InstanceType` and
`InstanceRequirements` on the same override raises `ValidationError`, matching AWS. An override that
specifies `InstanceRequirements` must specify both `VCpuCount` and `MemoryMiB`, the two members the
AWS model marks required on that shape, and raises `ValidationError` otherwise. Every member of the
`InstanceRequirements` shape round-trips except `BaselinePerformanceFactors`, which is accepted and
dropped.

`WeightedCapacity` must be an integer from 1 to 999 written in at most 32 characters, and once
one override sets it every override must, as the AWS model requires. Either rule raises
`ValidationError` otherwise.

A group uses attribute-based instance type selection when the mixed instances policy left in effect
by the request carries at least one launch template override with `InstanceRequirements`. On create
that is the policy the request supplies. On update it is the policy the request supplies, the stored
policy when the request names no launch source, or none when the request switches the group to a
launch configuration or a plain launch template.

## Optional Group Fields

`CreateAutoScalingGroup` and `UpdateAutoScalingGroup` both accept the fields below, and
`DescribeAutoScalingGroups` returns each one only when the group has a value for it. A group that
never set a field omits it from the response rather than reporting a default.

| Field | Notes |
|---|---|
| `DesiredCapacityType` | One of `units`, `vcpu`, `memory-mib`. Any other value raises `ValidationError`. AWS supports it for attribute-based instance type selection only, so `vcpu` and `memory-mib` raise `ValidationError` unless the effective mixed instances policy uses `InstanceRequirements`. `units` is the documented default and is always accepted |
| `CapacityRebalance` | Stored and echoed as a boolean |
| `MaxInstanceLifetime` | Seconds. Must be `0` or at least `86400`. `0` means no maximum and is echoed back as `0` |
| `DefaultInstanceWarmup` | Seconds. Pass `-1` to remove a value already set, after which the field is omitted again |
| `ServiceLinkedRoleARN` | Stored and echoed as given, 1 to 1600 characters. Floci does not check that the role exists, and a group that never set it omits it rather than reporting the default `AWSServiceRoleForAutoScaling` role |
| `InstanceMaintenancePolicy` | `MinHealthyPercentage` 0 to 100 and `MaxHealthyPercentage` 100 to 200, both required, at most 100 apart. `-1` on both clears a policy already set. Any other combination raises `ValidationError` |
| `AvailabilityZoneDistribution` | `CapacityDistributionStrategy` is one of `balanced-only`, `balanced-best-effort`, `reservations-then-balanced` |
| `CapacityReservationSpecification` | `CapacityReservationPreference` is one of `capacity-reservations-only`, `capacity-reservations-first`, `none`, `default`. `CapacityReservationTarget` (`CapacityReservationIds`, `CapacityReservationResourceGroupArns`) round-trips, and is rejected alongside `none`. Each ID is 1 to 255 characters of `[A-Za-z0-9-_/]` and each resource group ARN is 1 to 1600 characters |

These fields are stored and reported only. Floci's capacity reconciler does not change how it
launches or replaces instances because of them.

## Scaling Policy Compatibility

Target tracking policies preserve `TargetTrackingConfiguration` for predefined metrics. `DescribePolicies` returns `PredefinedMetricSpecification.PredefinedMetricType`, `TargetValue`, and `EstimatedInstanceWarmup` when present.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_AUTOSCALING_ENABLED` | `true` | Enable or disable the service |

## Usage Example

```bash
# Create a launch configuration
aws autoscaling create-launch-configuration \
  --launch-configuration-name my-lc \
  --image-id ami-12345678 \
  --instance-type t3.micro

# Create a group targeting desired=2
aws autoscaling create-auto-scaling-group \
  --auto-scaling-group-name my-asg \
  --launch-configuration-name my-lc \
  --min-size 1 \
  --max-size 5 \
  --desired-capacity 2 \
  --availability-zones us-east-1a

# Attach an ELB v2 target group
aws autoscaling attach-load-balancer-target-groups \
  --auto-scaling-group-name my-asg \
  --target-group-arns arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-tg/abc123

# Watch instances appear
aws autoscaling describe-auto-scaling-groups \
  --auto-scaling-group-names my-asg

# Scale out
aws autoscaling set-desired-capacity \
  --auto-scaling-group-name my-asg \
  --desired-capacity 3
```
