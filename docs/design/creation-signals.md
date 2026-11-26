# CloudFormation creation signals

An EC2 instance or Auto Scaling group is not ready merely because its backing
resource exists. A template's `CreationPolicy` holds the resource and its
dependants until the declared success signals arrive, a failure signal arrives,
or the configured deadline expires.

The CloudFormation operation owns a signal collector keyed by stack ID and
logical resource ID. It registers before provisioning so a fast guest's signal
cannot race its registration. The deadline starts after the provisioner finishes.
Only distinct success IDs count. Account and Region checks use the existing stack
lookup. The ordinary `SignalResource` Query API reports valid signals as stack
events; no custom callback URL or application delay is introduced.

The first implementation supports `ResourceSignal` on EC2 instances and Auto
Scaling groups, plus `AutoScalingCreationPolicy.MinSuccessfulInstancesPercent`
on groups. Counts and percentages use the resolved initial capacity. Creation
and replacement wait; an in-place update does not reapply a creation policy.
Unsupported creation policy fields and resource types fail explicitly.

When a group omits `DesiredCapacity`, creation uses its `MinSize`. An in-place
update leaves the current desired capacity to Auto Scaling instead of resetting
instances that have scaled since creation.

Failure and timeout retain the backing resource identity for normal CloudFormation
rollback and deletion. Signal collectors are released when the operation finishes.
Operation resumption after an emulator restart, rolling-update signals, AppStream
creation policies, and handle-free WaitCondition creation policies remain separate
work.

Qualification covers SDK signals, duplicate success IDs, dependent-resource
ordering, failure, real deadline expiry, rollback, account/Region isolation,
replacement and in-place updates. Guest startup still needs its own packet and
application qualification.

References:

- [CreationPolicy](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-attribute-creationpolicy.html)
- [SignalResource](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_SignalResource.html)
