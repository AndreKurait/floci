# Application Auto Scaling

**Protocol:** JSON 1.1 (`X-Amz-Target: AnyScaleFrontendService.*`)
**Endpoint:** `POST http://localhost:4566/`
**Signing name:** `application-autoscaling`

Application Auto Scaling is the API behind `aws_appautoscaling_target` and
`aws_appautoscaling_policy`, and is what scales ECS services, MSK broker storage,
DynamoDB capacity, Lambda provisioned concurrency, and similar resources.

It is **not** the same service as [Auto Scaling](autoscaling.md), which scales EC2
Auto Scaling groups over the Query protocol under the `autoscaling` signing name.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `RegisterScalableTarget` | Registers or updates a scalable target and returns its ARN |
| `DescribeScalableTargets` | Lists scalable targets in a namespace, optionally filtered |
| `DeregisterScalableTarget` | Deregisters a target and deletes its policies and alarms |
| `PutScalingPolicy` | Creates or updates a scaling policy and its CloudWatch alarms |
| `DescribeScalingPolicies` | Lists scaling policies in a namespace, optionally filtered |
| `DeleteScalingPolicy` | Deletes a scaling policy and its CloudWatch alarms |
| `DescribeScalingActivities` | Lists recorded capacity-change events for a scalable target |
| `ListTagsForResource` | Returns the tags on a scalable target |
| `TagResource` | Adds or overwrites tags on a scalable target |
| `UntagResource` | Removes tags from a scalable target |
<!-- floci:actions:end -->

## Pagination

`DescribeScalableTargets` accepts `MaxResults` from 1 to 50 and defaults to 50.
`DescribeScalingPolicies` accepts `MaxResults` from 1 to 10 and defaults to 10.
When more results remain, pass the returned `NextToken` with the same operation,
region, and filters to retrieve the next page. An invalid token returns
`InvalidNextTokenException`; an out-of-range page size returns `ValidationException`.
Tokens expire when the emulator restarts.

## Identity

A scalable target is keyed by the triple **(ServiceNamespace, ResourceId,
ScalableDimension)** — there is no separate identifier. `RegisterScalableTarget` is an
upsert on that triple: parameters you omit are left unchanged, matching AWS.

A scaling policy is keyed by that same triple plus `PolicyName`.

Both `ServiceNamespace` and `ScalableDimension` are validated against the AWS enums; an
unknown value returns `ValidationException`.

## ARN formats

The two ARN families deliberately use different service names, mirroring AWS:

```
ScalableTargetARN  arn:aws:application-autoscaling:<region>:<account>:scalable-target/<id>
PolicyARN          arn:aws:autoscaling:<region>:<account>:scalingPolicy:<uuid>:resource/<namespace>/<resourceId>:policyName/<name>
```

`ScalableTargetARN` is the tagging identifier. The Terraform AWS provider reads it from
`DescribeScalableTargets` into the resource's `arn` attribute and then passes it to
`ListTagsForResource` on every read, so it is always populated.

Under IAM enforcement, a request naming a target by its triple is checked on that ARN, and a
`RegisterScalableTarget` registering a new one on `scalable-target/*`, as its id is not assigned yet.

## DynamoDB capacity at registration

With Floci's built-in DynamoDB backend, `RegisterScalableTarget` reconciles the
selected provisioned capacity for all four DynamoDB dimensions:

- `dynamodb:table:ReadCapacityUnits`
- `dynamodb:table:WriteCapacityUnits`
- `dynamodb:index:ReadCapacityUnits`
- `dynamodb:index:WriteCapacityUnits`

Use `table/<name>` for a table and `table/<name>/index/<index>` for a global secondary
index. Both bounds are required for a new target. Updating a target preserves each
omitted bound. Bounds must be positive and the minimum cannot exceed the maximum.
The table must exist and use `PROVISIONED` billing; an index target must also name an
existing index. Missing resources and on-demand tables return `ValidationException`.

For an active table, capacity below the minimum rises to that minimum and capacity
above the maximum falls to that maximum. Capacity already in range stays unchanged.
The opposite read/write capacity and sibling indexes stay unchanged. An inactive
table accepts the target metadata without changing capacity; registration does not
schedule a later retry. Index activity follows the built-in backend's table status,
since it does not model a separate asynchronous index lifecycle.

The target and capacity belong to the request's account and Region. IAM checks the
new-target wildcard or the stored target ARN, plus the existing explicit `RoleARN`
and tagging permissions. Registration does not require the caller to receive a
separate `dynamodb:UpdateTable` grant.

A successful capacity change retains the table or index's prior warm throughput
peak. See [DynamoDB warm throughput](dynamodb.md#warm-throughput).
DynamoDB scaling-policy execution, utilization metric publication, and scheduled
capacity changes are not implemented by this registration path.

## CloudWatch alarms

Other target-tracking configurations create two CloudWatch alarms.
DynamoDB uses the four alarms described below:

```
TargetTracking-<resourceId>-AlarmHigh-<uuid>
TargetTracking-<resourceId>-AlarmLow-<uuid>
```

They are visible through `DescribeAlarms` and are deleted when the policy is deleted or
its scalable target is deregistered, exactly as on AWS.

Each alarm watches the CloudWatch metric it scales on, as real AWS's alarms do:

- A `CustomizedMetricSpecification` passes the caller's `Namespace`, `MetricName`,
  `Dimensions`, `Statistic` (defaulting to `Average`) and `Unit` straight onto the
  alarms, so data published under the identity the policy named is what the alarms
  read.
- `ECSServiceAverageCPUUtilization` maps onto the documented AWS/ECS service-level
  metric: `CPUUtilization` with the `ClusterName`/`ServiceName` dimensions taken from
  the resource ID, and `ECSServiceAverageMemoryUtilization` maps to `MemoryUtilization`
  the same way.
- `ALBRequestCountPerTarget` maps onto AWS/ApplicationELB `RequestCountPerTarget` with
  the `LoadBalancer`/`TargetGroup` dimensions encoded in the policy's `ResourceLabel`.
  The alarm reads the `Sum` statistic, the only valid one for that metric on CloudWatch
  (its data points are already the per-minute average across the group's targets).
- Except for the DynamoDB types below, other predefined types keep the service namespace's CloudWatch namespace under the
  type's own name, since that type's metric identity is not one Floci derives; push the
  metric under that name to exercise such a policy.

For `ecs` targets, the alarm is created with the same `ClusterName`/`ServiceName`
dimensions real AWS attaches to the metric, so a metric you push with
`cloudwatch:PutMetricData` using those dimensions is what the control loop below
evaluates against.

## Lambda provisioned capacity

Registering `lambda:function:ProvisionedConcurrency` reconciles the alias or published
version's requested capacity into `MinCapacity` and `MaxCapacity`. Updating the target
does the same even when the request omits the bounds. This also applies to
`AWS::ApplicationAutoScaling::ScalableTarget` resources in CloudFormation.

The existing Lambda provisioned-concurrency pool owns initialization and reports
`IN_PROGRESS`, `READY`, or `FAILED` through the Lambda API. Registration does not imply
that initialization has finished. Inactive resources can be registered without changing
capacity. Deregistering the scalable target removes its policies and alarms but preserves
the Lambda allocation; delete that allocation or function separately when it is no longer
needed.

Lambda target tracking uses `LambdaProvisionedConcurrencyUtilization`, mapped to
`AWS/Lambda`'s `ProvisionedConcurrencyUtilization` metric with the `FunctionName` and
qualified `Resource` dimensions. Policies use the existing pool to change real
provisioned environments. A zero capacity removes the allocation. Lambda supports
target tracking, and rejects step-scaling policies. The lower alarm uses 90 percent
of the target utilization, matching Lambda's scale-in threshold.

## Control loop (ECS and Lambda)

A background evaluator (ticking every ~10s) evaluates every CloudWatch alarm with
metric-math configuration — the two alarms above, and any hand-created alarm behind a
`StepScaling` policy, since AWS does not auto-create alarms for those — against the
metric data pushed via `PutMetricData`, transitioning `StateValue` the same way real
CloudWatch does, over the last `EvaluationPeriods` **complete** periods (the still-forming
current period is never counted, matching real CloudWatch). While an alarm is in `ALARM`,
its policy fires on every tick, not only the first transition — a scale-out blocked by
cooldown on one tick is retried on a later one instead of being dropped. For
`ScalableDimension=ecs:service:DesiredCount`, Floci calls the ECS `UpdateService` action
to change `desiredCount`. For `lambda:function:ProvisionedConcurrency`, it changes the
Lambda provisioned-capacity request. Both respect `MinCapacity`/`MaxCapacity` and
`SuspendedState`. Background evaluation reads metrics and dispatches actions in each
alarm's stored account and region.

- `TargetTrackingScaling` computes the new capacity as
  `ceil(current * metricValue / TargetValue)`. `DisableScaleIn` suppresses the scale-in
  direction only; the alarm still transitions.
- `StepScaling` resolves the matching `StepAdjustment` by comparing
  `metricValue - Threshold` against each step's interval bounds, then applies
  `AdjustmentType` (`ChangeInCapacity`, `PercentChangeInCapacity` with
  `MinAdjustmentMagnitude`, or `ExactCapacity`).
- **Cooldown** (`ScaleInCooldown`/`ScaleOutCooldown` for target tracking, `Cooldown` for
  step scaling) defaults to 300 seconds for ECS and zero for Lambda. Per
  AWS's own cooldown semantics, a scale-out blocked by cooldown still proceeds immediately
  if the newly computed capacity is *larger* than what the last scale-out already applied
  — cooldown exists to stop flapping on small repeated moves, not to cap a sustained
  breach's climb toward `MaxCapacity`. Scale-in has no such carve-out and is simply
  blocked for the full cooldown window, same as AWS.
- When `TreatMissingData=breaching` reaches `ALARM` with no populated datapoint actually
  breaching (only missing periods pushed it there), the policy still fires: target
  tracking nudges capacity by one unit in the alarm's own direction (the only substitute
  that's guaranteed not to reverse direction), and step scaling resolves the step at
  `delta=0` (right at the alarm's own threshold).
- `TreatMissingData=ignore` keeps the alarm's current state rather than transitioning it,
  but if that current state is already `ALARM`, a deferred action still retries — "ignore"
  only means "don't let this gap change my state," not "stop trying to act."
- Every capacity change is recorded and visible through `DescribeScalingActivities`.
- No metric data pushed for the alarm's window means `INSUFFICIENT_DATA` and no action —
  Floci does not synthesize ECS CPU/memory metrics itself, so an operator (or a compat
  test) has to push them to exercise the loop, the same way a real CloudWatch agent
  would against real AWS.
- Other scalable dimensions such as MSK broker storage are not driven by this
  generic policy path. DynamoDB uses the separate native target-tracking path below.

## Native DynamoDB target tracking

With the native DynamoDB backend, a matching `DynamoDBReadCapacityUtilization` or
`DynamoDBWriteCapacityUtilization` predefined target-tracking policy adjusts the
registered table or GSI read/write capacity. Ordinary native item requests produce
its consumed-capacity metrics; see [the covered request paths](dynamodb.md#cloudwatch-capacity-metrics).
Transactions, PartiQL and external DynamoDB Local workloads do not supply this producer.

Each policy owns four CloudWatch alarms, or three when scale-in is disabled.
An unchanged complete target-tracking configuration retains its alarm identities.
A changed configuration creates new alarms and removes the old managed alarms
once the policy is stored. Policy identity and creation time remain unchanged.
Its consumed-capacity
alarms use `Sum`, 60-second periods, two high periods and fifteen low periods.
The high threshold is provisioned capacity times 60 times target percent / 100;
the low threshold uses a 20 percentage-point buffer with a 5 percent floor. The other two alarms use
provisioned capacity, `Average`, 300-second periods and three periods to detect
capacity changes and refresh thresholds. They do not apply the consumed-capacity formula.

A consumed `Sum` is divided by 60 before the utilization calculation. For example,
9,000 units in one minute is 150 units per second; target 70 requires
`ceil(150 * 100 / 70) = 215` provisioned units. The result is clamped to the current
registered bounds. A finite zero sample can scale in to the minimum. Missing,
negative and non-finite values do not substitute an invented capacity adjustment.
The selected capacity is re-read while its table is locked, and stale thresholds
are checked against that current capacity before mutation. The opposite capacity
and sibling indexes remain unchanged. Existing warm-throughput peaks are retained.

The actual clamped direction controls `DisableScaleIn`, dynamic suspension and
cooldown. Omitted DynamoDB cooldowns execute with a zero-second default and remain
omitted in policy responses. Successful changes record scaling activities. The
capacity write and later policy/activity records are separate operations; a later
metadata failure does not roll back an already applied capacity change.

Owned thresholds refresh after a successful policy action or policy update, and a
worker checks them every ten seconds after external capacity changes. It re-reads
the current target and policy under their owner and target lock before publication,
so a deleted policy is not restored by a stale sweep. The worker drains for reset
and shutdown. Table status, billing mode and separate index lifecycle limitations
follow the native backend. Inactive or on-demand resources do not receive adjustments.

This internal adjustment path does not assume the stored service-linked role or
enforce a background `dynamodb:UpdateTable` resource policy. Request authorization
for registration and policy management remains separate. Registration and capacity
decisions reject a configured external backend before native metadata is accessed.

DynamoDB policy creation requires `PolicyType` and a target-tracking configuration.
An update may omit the policy type or whole configuration to retain that value.
Within a supplied configuration, an omitted predefined metric retains the previous
metric; omitted cooldowns and `DisableScaleIn` reset to absent. Explicit zero
cooldowns and `false` remain present. Read and write utilization targets must be
between 10 and 90 inclusive. Unsupported policy types, customized metrics, missing
required inputs and mismatched predefined metrics are refused before mutation.
Scheduled DynamoDB scaling is not implemented by this path.

Native managed alarms use the policy ARN directly in their action and description.
AWS appends a stable `:createdBy/` identifier to those references; that opaque
identifier is not reproduced here. Alarm definitions are not an exact AWS wire copy.

## Service-linked roles

When `RoleARN` is omitted, Floci synthesizes and returns a service-linked role ARN in the
AWS shape, since the provider treats the attribute as computed:

```
arn:aws:iam::<account>:role/aws-service-role/<namespace>.application-autoscaling.amazonaws.com/AWSServiceRoleForApplicationAutoScaling_<Suffix>
```

## Limitations

- **The control loop drives ECS desired count and Lambda provisioned concurrency.**
  Native DynamoDB also supports the four predefined target-tracking dimensions below.
  Other scalable dimensions have no policy-driven capacity executor here.
  ECS registration retains its existing control-plane behavior; registration
  reconciliation is implemented for Lambda and DynamoDB.
- The loop consumes CloudWatch metric data; it does not synthesize Lambda utilization
  from invocations. Tests or applications must publish the metric to exercise target
  tracking.
- `PutScheduledAction`, `DescribeScheduledActions`, and `DeleteScheduledAction` are not
  implemented.
- `PredictiveScalingPolicyConfiguration` is accepted only insofar as `PolicyType` is
  validated; the configuration block is not stored.
- **The evaluation range's exact width is an approximation.** Alarm evaluation follows
  CloudWatch's documented precedence: a wider "evaluation range" than
  `Period × EvaluationPeriods` is queried, and whenever enough *real* datapoints exist within
  it the alarm is evaluated on those and `TreatMissingData` is ignored entirely; the setting
  fills in only what real data cannot cover. The premature-transition rule is implemented too,
  so a breach that has aged past `DatapointsToAlarm` with only missing periods after it reaches
  `ALARM`, while a breach at the very end of the window does not. AWS does not publish how wide
  the range is (only that it varies with period length and metric resolution), so Floci uses
  `EvaluationPeriods + 2`, which reproduces the single worked example in AWS's documentation.
  Both published example tables are transcribed as tests in
  `AlarmEvaluatorMissingDataTablesTest`.
- **Scale-out cooldown rarely blocks a repeated scale-out in practice.** Its "proceed if
  larger than the last applied capacity" carve-out (see above) is almost always satisfied
  when nothing external has changed capacity, because both capacity formulas grow
  monotonically with `current` — this matches AWS's documented intent for scale-out to add
  capacity "as fast as it can," so cooldown's practical effect for this control loop is
  mostly to gate scale-*in*, and to eventually halt scale-out once `MaxCapacity` is
  reached (via the ordinary no-op-when-unchanged check, not the cooldown check itself).

## Terraform

Point the `appautoscaling` endpoint at Floci:

```hcl
provider "aws" {
  endpoints {
    appautoscaling = "http://localhost:4566"
  }
}
```

`aws_appautoscaling_target` and `aws_appautoscaling_policy` support create, read, update,
and delete, and converge to a clean plan.
