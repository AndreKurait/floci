# SSM

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonSSM.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

### Parameter Store

| Action | Description |
|---|---|
| `PutParameter` | Create or update a parameter |
| `GetParameter` | Get a single parameter by name, optionally `name:version` or `name:label` |
| `GetParameters` | Get multiple parameters by name, with the same `name:version` and `name:label` selectors |
| `GetParametersByPath` | Get all parameters under a path prefix |
| `DeleteParameter` | Delete a parameter |
| `DeleteParameters` | Delete multiple parameters |
| `GetParameterHistory` | List all versions of a parameter |
| `DescribeParameters` | List parameters with optional filters |
| `LabelParameterVersion` | Attach a label to a specific version |
| `UnlabelParameterVersion` | Remove labels from a specific version |
| `AddTagsToResource` | Tag a parameter or customer patch baseline |
| `ListTagsForResource` | List tags on a parameter or customer patch baseline |
| `RemoveTagsFromResource` | Remove tags from a parameter or customer patch baseline |
| `PutResourcePolicy` | Attach or update the resource policy of an Advanced-tier parameter |
| `GetResourcePolicies` | List the resource policy attached to a parameter |
| `DeleteResourcePolicy` | Remove a parameter's resource policy |

`DescribeParameters` includes the stored parameter's `ARN` in each metadata entry, matching
`GetParameter`. This applies to filtered and paginated results as well.

`GetParametersByPath` supports `Type` and `KeyId` entries in `ParameterFilters`, with `Equals`
(the default) or `BeginsWith`. Values within one filter combine with OR; multiple
filters combine with AND. Filtering also applies to public AMI parameters and
respects `Path`, `Recursive`, and region selection. Unknown or missing keys and keys
not allowed for this action are rejected with `InvalidFilterKey`.
`Label` filters accept only `Equals` and return the stored versions carrying the requested
labels, including older versions. Moving or removing a label changes subsequent results.
The same OR/AND rules apply to label filters, and a version matching several requested
labels appears only once. Type and KeyId filters match the labeled version's metadata.
Public AMI parameters have no stored labels and are excluded from label queries.
`MaxResults` and `NextToken` paginate the matching versions in name/version order; queries
without a label filter continue to return the latest version of each parameter.

### Patch baseline configuration

| Action | Description |
|---|---|
| `CreatePatchBaseline` | Store a customer baseline's approval rules, patch lists, repository sources and tags |
| `GetPatchBaseline` | Read customer configuration and patch-group bindings |
| `UpdatePatchBaseline` | Change supplied fields, or replace optional configuration with `Replace=true` |
| `DeletePatchBaseline` | Delete an unused customer baseline; registered patch groups must first be removed |
| `DescribePatchBaselines` | Page through predefined and customer identities with `OWNER`, `OPERATING_SYSTEM` and `NAME_PREFIX` filters |
| `GetDefaultPatchBaseline` | Return the account's default for an operating system, falling back to the predefined baseline |
| `RegisterDefaultPatchBaseline` | Select a customer baseline as the account's default for its operating system |
| `RegisterPatchBaselineForPatchGroup` | Bind a patch group to one customer baseline per operating system |
| `DeregisterPatchBaselineForPatchGroup` | Remove a patch-group binding |
| `GetPatchBaselineForPatchGroup` | Resolve a group's baseline or the operating-system default |
| `DescribePatchGroups` | Page through bindings with `NAME_PREFIX` and `OPERATING_SYSTEM` filters |

Customer baselines, defaults, tags and idempotency tokens use the existing SSM
storage mode and are isolated by account and region. `CreatePatchBaseline`
replays an identical client token and rejects a token used with different input.
Updates preserve the operating system and creation time. An ordinary update
preserves omitted fields; `Replace=true` requires a name and resets omitted
optional configuration. JSON timestamps use epoch seconds for strict SDK clients.
Debian and Ubuntu rules accept filters without date-based auto approval; supplying
`ApproveAfterDays` or `ApproveUntilDate` for those operating systems is rejected.

This implements configuration storage. It does not install or scan patches,
resolve repository package versions, or report managed-node patch compliance.
Predefined baselines retain the existing identity catalog; reading their full
approval policies or registering an AWS-owned baseline ARN is not implemented.

### Run Command

| Action | Description |
|---|---|
| `UpdateInstanceInformation` | Register or update an SSM agent record for an instance. Does not create an EC2 instance or register the container with IMDS (see [Run Command Execution](#run-command-execution)) |
| `DescribeInstanceInformation` | Filter and paginate registered managed nodes in the caller's account and region |
| `SendCommand` | Create command invocations for target instances |
| `GetCommandInvocation` | Return a command invocation result |
| `ListCommands` | List command records |
| `ListCommandInvocations` | List command invocation records |
| `CancelCommand` | Cancel pending or in-progress command invocations |

### Inventory

| Action | Description |
|---|---|
| `PutInventory` | Report inventory for a registered managed node |
| `ListInventoryEntries` | Read, filter and page one inventory type on a managed node |

Inventory belongs to the registering account and region. Each upload replaces the
reported types and preserves other types. An unchanged `ContentHash` and schema version
retain the previous content while refreshing `CaptureTime`, matching SSM Agent's
optimized uploads. A hash-only upload with no matching stored content fails with
`ItemContentMismatchException`; an explicit empty content list clears that type's entries.

The software-inventory agent's `AWS:*` types and `Custom:*` types are supported. Custom
inventory allows 20 types per account and region and requires consistent attribute casing.
Values remain strings. Invalid batches leave the previous inventory intact.
The SSM storage setting controls persistence across restarts.

This implements inventory upload and entry reads. Inventory collection scheduling,
compliance item contexts, inventory aggregators and custom-schema deletion are not
implemented by these actions. AWS's full attribute-schema and
daily byte-quota validation are not modeled. A successful upload does not prove a
State Manager document executed.

### Inventory resource data sync

| Action | Description |
|---|---|
| `CreateResourceDataSync` | Configure asynchronous inventory export to S3 |
| `ListResourceDataSync` | Page through configurations and their last attempt/success statuses |
| `DeleteResourceDataSync` | Stop future exports while preserving existing S3 objects |

`SyncToDestination` creates an inventory baseline and exports subsequent uploads.
Each type is written as JSON lines at
`<prefix>/<type>/accountid=<account>/region=<region>/resourcetype=ManagedInstanceInventory/<instance>.json`.
Rows include `resourceId`, `captureTime` and `schemaVersion`. Empty inventory content
overwrites that type's object with an empty body. Hash-only refreshes export the
retained content with the new capture time.

Configurations and acknowledged inventory revisions use the SSM storage mode.
Failed writes retain their error and the prior successful-sync time; the worker
retries after the destination or policy is repaired. Success is recorded only
after all pending writes complete. There are five configurations per account and
region. Deletion, reset and shutdown wait for in-flight exports to finish.

With S3 auth or IAM enforcement enabled, delivery evaluates the SSM service
principal against the bucket policy for `s3:GetBucketAcl` and `s3:PutObject`,
including the source account, sync ARN, SigV4 signing version and
`bucket-owner-full-control` ACL. Service-to-service network context is redacted;
the worker does not inherit the caller's transport or source IP.
Cross-account destinations require the existing S3 global bucket namespace.
The destination must be in the same partition and must not use S3 Object Lock.

The worker interval defaults to 1,000 ms and is configurable with
`FLOCI_SERVICES_SSM_RESOURCE_DATA_SYNC_INTERVAL_MS`. Explorer `SyncFromSource`,
Organizations data sharing and `AWSKMSKeyARN` are explicitly unsupported.
Collection and scheduling require a running SSM agent and an association. Resource
data sync exports the inventory that managed nodes actually report.

### CloudFormation inventory destinations

`AWS::SSM::ResourceDataSync` owns a real SSM inventory sync. `Ref` returns `SyncName`;
the AWS schema defines no `Fn::GetAtt` attributes. Both the legacy top-level S3
destination fields and nested `S3Destination` are supported. Combining them fails
before creating a sync.

Creation reads the actual SSM status using the AWS provider's initial two-second
callback and bounded ten-second retries. `Successful` and `InProgress` satisfy
resource admission; `Failed` fails creation and removes the new sync. Admission
does not establish that inventory was exported: inspect the sync status and S3
objects for that result.

Destination properties are create-only. A replacement requires a new `SyncName`,
creates it before retiring the previous sync, and honors rollback and retention.
Changing a destination while keeping its custom name fails without modifying the
old sync. Stack deletion stops future exports and preserves existing S3 objects.
Explorer `SyncSource` and KMS-encrypted delivery remain unsupported.

### ec2messages Agent Protocol

| Action | Description |
|---|---|
| `GetMessages` | Agent polls for pending command messages |
| `AcknowledgeMessage` | Agent acknowledges receipt of a command message |
| `SendReply` | Agent reports command output and status |

### ssmmessages control channel

The stock agent uses `CreateControlChannel` and a signed WebSocket
`OpenControlChannel` at `/v1/control-channel/{managed-node-id}`. Both handshakes
verify SigV4, session credentials and IAM authorization. A one-use token binds
the connection to its account, region, node and caller. EC2 instance credentials
can open only their own node's channel.

The channel carries the agent's binary job, acknowledgement and result messages.
These are Run Command control channels; Session Manager data channels are not implemented.

## Document versions

`DescribeDocument` returns `CreatedDate` as numeric epoch seconds, so AWS SDK
clients can decode descriptions of current and retained document versions.

New documents default to version 1. `UpdateDocument` publishes a new latest version
without changing the default. `UpdateDocumentDefaultVersion` selects an existing
numeric version; `GetDocument` and `DescribeDocument` resolve an omitted selector
or `$DEFAULT` to that selection, `$LATEST` to the newest version, and a number to
that retained version. Descriptions report the default and latest separately.

The selection survives storage reloads. Older stored documents without a default
field retain their previous effective default, the latest version at migration.
Content missing from legacy history is not recreated: a reference can exist while
`GetDocument` and `DescribeDocument` cannot return that version's content.
Changing the default does not schedule State Manager execution.

## CloudFormation patch baselines

`AWS::SSM::PatchBaseline` creates backing SSM configuration. `Ref` and `Fn::GetAtt Id`
return its baseline ID. Updates keep that ID unless `OperatingSystem` changes;
omitted optional policy fields reset through `UpdatePatchBaseline` with `Replace=true`.
Patch groups and template-owned tags are reconciled, and deletion deregisters the
baseline's groups before deleting it. Replacement honors `UpdateReplacePolicy`.

`DefaultBaseline: true` registers the baseline on creation or a false-to-true update.
As in AWS's resource handler, false does not unregister it, and an unchanged true does
not overwrite a default selected outside the stack. This resource configures patching;
it does not run Patch Manager or prove that managed nodes installed patches.

## State Manager associations

Association create, describe, list, update and delete operations store and return the
configuration. A running SSM agent discovers its configuration through
`ListInstanceAssociations`, fetches the selected version with `GetDocument`, and executes
the document using its own plugins and schedule. Discovery supports legacy instance
associations, `InstanceIds` targets, and live EC2 tag targets in the caller's account
and region.

New associations remain `Pending` until an agent reports execution through
`UpdateInstanceAssociationStatus`. Reports retain each node's association version,
status and execution date. One successful target does not complete other pending
targets. Older reports cannot overwrite newer executions, and a delayed progress
report cannot undo a completed execution at the same date. An updated association
requires reports for its new version.

These agent APIs do not provide centralized dispatch, concurrency/error budgets,
resource-group target resolution, or compliance aggregation. Association creation
alone does not prove that a document ran or inventory was collected. The Run Command
execution paths below are separate.

CloudFormation `AWS::SSM::Association` creates that backing configuration and returns
its ID through both `Ref` and `Fn::GetAtt AssociationId`. Document references, target
conditions, parameters, schedules and rate controls are resolved from the template.
Updates preserve the association ID, except a changed legacy `InstanceId` replaces
the association. Failed updates restore the previous configuration; replacement
retention and stack deletion operate on the owned association.

`ApplyOnlyAtCronInterval`, `AssociationDispatchAssumeRole`, `AutomationTargetParameterName`,
`CalendarNames`, `OutputLocation`, `ScheduleOffset`, `SyncCompliance`, `Tags` and
`WaitForSuccessTimeoutSeconds` are not yet supported by this CloudFormation owner.
Nonempty declarations fail before creating or changing an association. Omit the
execution wait only when successful configuration creation is the intended contract.

`UpdateAssociation.Name` selects the new document without changing the association ID.
The requested document version is validated against that document before any stored
fields change. Changing the document without a version selects its `$DEFAULT`, so a
numeric version from the previous document cannot carry over. Missing documents or
invalid versions leave the association unchanged.

Updates replace the optional `AssociationName`, `Parameters`, `ScheduleExpression`,
`MaxErrors`, `MaxConcurrency` and `ComplianceSeverity` values. Include their current
values in the request to retain them; omitting them clears the previous configuration.

`AssociationVersion` provides optimistic concurrency: an explicit version must match
the current association version, otherwise the update fails with
`InvalidAssociationVersion`. Omitting it or passing `$LATEST` permits the update.
The check and write are atomic, so concurrent requests for the same explicit version
cannot both overwrite the association.

## Run Command Execution

`DescribeInstanceInformation` supports `InstanceIds`, `AgentVersion`, `PingStatus`,
`PlatformTypes` and `ResourceType` through modern `Filters` and the deprecated
`InstanceInformationFilterList` wire shape. Values within a filter are alternatives;
different keys must all match. Unknown or foreign managed-node IDs fail with
`InvalidInstanceId`. Known EC2 nodes that are stopped, shutting down or terminated
are omitted. A registered external agent does not require a Floci EC2 record.
Discovery does not infer lost contact from heartbeat age.

Listings default to 10 items and accept `MaxResults` from 5 to 50. Follow
`NextToken` for more results; tokens are scoped to the account, region and filters.
Mixing the two filter shapes, invalid keys or values, and foreign tokens fail.
Activation, association, IAM role, source and tag filters are not implemented;
they return `UnsupportedOperation` rather than ignoring the requested selection.

By default, `SendCommand` requires a registered managed node. Execution requires
a running SSM agent; commands wait for delivery while it is stopped. Floci delivers the selected Command document through the agent's `ssmmessages`
channel. The agent executes its own plugins and reports their output and status.
Stopping the agent prevents execution even when its EC2 container is still running.

Commands capture the selected document version and content at submission. Changing
or deleting the document afterward does not change queued work. Built-in
`AWS-RunShellScript` and `AWS-RunPowerShellScript` documents and stored JSON/YAML
Command documents use this path. Public parameter values remain string lists in
command metadata; delivery converts them to the document's declared `String` or
`StringList` type. Missing required parameters, unknown parameters and malformed
values fail before command creation. The agent performs plugin-specific validation.

Use a document step's name as `GetCommandInvocation.PluginName` when it has multiple
steps. Results remain separate for each step. A document completion summary may arrive
before its final plugin replies; those replies fill in missing results without
reopening the finished command or overwriting completed steps with older progress.
Inline output retains the first 24,000 characters of stdout and 8,000 of stderr.

`TimeoutSeconds` supplies the delivery allowance, from 30 to 2,592,000 seconds and
3,600 by default. The execution allowance contributes to `ExpiresAfter`: shell
documents accept `Parameters.executionTimeout` from 1 to 172800 seconds, defaulting
to 3,600. Custom documents use their declared execution-timeout parameter when
present, otherwise the 3,600-second service default. Each plugin's execution timer
is enforced by the agent. Internal CodeDeploy hook budgets remain separate.

Without an agent's terminal reply before `ExpiresAfter`, active invocations become
`TimedOut` with `StatusDetails: Delivery Timed Out`. Execution timeout requires the
agent's timeout result. Expired work is not delivered after reconnection. Delivery
timeouts are counted separately from execution failures in command metadata.

`CancelCommand` cancels work immediately if it has never been sent. Sent work becomes
`Cancelling` until the agent reports a terminal result; cancellation is delivered
through the same authenticated channel. Queued commands and cancellation state use
the selected SSM storage backend. A replacement connection recovers pending work.
While a node has an authenticated gateway connection, its legacy polls return no
commands. Closing an older connection cannot release a replacement connection's
ownership; once the current connection closes, legacy polling can resume queued work.

The older `ec2messages` polling path uses `EC2WindowsMessageDeliveryService.*`
targets and plain JSON payload strings. Its queues and message correlations remain
in memory; visibility retries and recovery of those legacy queues are not implemented.
Run Command concurrency/error budgets and full document-schema constraints are also
not implemented.

### Direct execution compatibility mode

Set `FLOCI_SERVICES_SSM_DIRECT_COMMAND_EXECUTION_ENABLED=true` to retain direct
execution of `AWS-RunShellScript` in a Floci EC2 container. This mode bypasses the
SSM agent and cannot establish that agent startup or network transport works.
It writes a private executable script, preserves the requested working directory,
and records output, exit status and timing. Unsupported targets use agent delivery.

Managed-node registration is independent of EC2 and IMDS. `UpdateInstanceInformation`
stores a managed-node record; it does not create an EC2 instance or install IMDS.
Launch an instance with EC2 `RunInstances`, an instance profile and an image that
starts the agent to exercise the normal credential and network paths.

### Image promotion check

The development image workflow runs `.github/scripts/smoke-ssm-agent.py` before
promoting `dev`. It uses the unmodified stock agent, instance-profile credentials
and verified TLS to collect inventory, run a versioned multi-step document, preserve
a native script failure, cancel a running process, and recover after an agent stop.
An undelivered command must expire without creating its side effect, including after
restart. API and container cleanup must restore the daemon's original footprint.

The check uses a generic Amazon Linux container. Native AMI boot and application
fleet workflows require separate integration tests.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SSM_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_SSM_DIRECT_COMMAND_EXECUTION_ENABLED` | `false` | Enable direct container execution for compatibility; bypasses the SSM agent |
| `FLOCI_SERVICES_SSM_MAX_PARAMETER_HISTORY` | `5` | Number of parameter versions retained per parameter |
| `FLOCI_STORAGE_SERVICES_SSM_MODE` | *(global default)* | Storage mode override for SSM (`memory`, `persistent`, `hybrid`, `wal`) |
| `FLOCI_STORAGE_SERVICES_SSM_FLUSH_INTERVAL_MS` | `5000` | Flush interval for `hybrid`/`wal` storage modes (milliseconds) |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Store parameters
aws ssm put-parameter --endpoint-url $AWS_ENDPOINT_URL \
  --name /app/db/host --value "localhost" --type String

aws ssm put-parameter --endpoint-url $AWS_ENDPOINT_URL \
  --name /app/db/password --value "secret" --type SecureString

# Retrieve
aws ssm get-parameter --endpoint-url $AWS_ENDPOINT_URL \
  --name /app/db/host

aws ssm get-parameters-by-path --endpoint-url $AWS_ENDPOINT_URL \
  --path /app/ --recursive

# Delete
aws ssm delete-parameter --endpoint-url $AWS_ENDPOINT_URL \
  --name /app/db/host

# Run a shell command on a Floci EC2 instance
aws ssm send-command --endpoint-url $AWS_ENDPOINT_URL \
  --instance-ids i-0123456789abcdef0 \
  --document-name AWS-RunShellScript \
  --parameters commands='["echo hello"]'
```

## Public AMI Parameters

AWS publishes AMI id lookup parameters under `/aws/service/ami-amazon-linux-latest/` and EKS
optimized node AMI lookup parameters under `/aws/service/eks/optimized-ami/` in every account,
and tools such as Terraform, Karpenter and eksctl read them without any setup. Floci answers the
documented Amazon Linux 2 and Amazon Linux 2023 names from its EC2 image catalog across supported
architectures and Kubernetes versions, so `GetParameter`, `GetParameters` and `GetParametersByPath`
resolve them to the catalog's AMI ids. As on AWS they are read-only and belong to no account,
so they do not appear in `DescribeParameters`.

Floci also answers the Amazon Linux 2023 ECS optimized AMI `recommended/image_id` and
`recommended` parameters under `/aws/service/ecs/optimized-ami/`. The image ID points to
the catalog's x86_64 Amazon Linux 2023 image. The `recommended` value is JSON with the
matching image ID and name; its ECS agent and runtime versions are `unknown` because
the catalog image is synthetic and does not represent an installed ECS runtime.

Canonical's Ubuntu Server AMI parameters under `/aws/service/canonical/ubuntu/server/` resolve
too, by version or codename: `24.04`/`noble` (amd64 and arm64, `ebs-gp3`) and `22.04`/`jammy`
and `20.04`/`focal` (amd64, `ebs-gp2`), each at `stable/current`.

```bash
aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64
aws ssm get-parameter --name /aws/service/eks/optimized-ami/1.31/amazon-linux-2023/x86_64/standard/recommended/image_id
aws ssm get-parameter --name /aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_id
aws ssm get-parameter --name /aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id
```

## Secrets Manager References

`GetParameter` and `GetParameters` answer `/aws/reference/secretsmanager/<secret-id>` from
Secrets Manager, so an application, an ECS task secret or a CodeBuild `parameter-store`
variable can read a secret through Parameter Store.
The answer is a `SecureString` with the secret's value and ARN and `Version` 0, and
`SourceResult` carries the Secrets Manager `GetSecretValue` result as a JSON string, without
`secretBinary` for a binary secret. A
`:<version-id>` or `:<staging-label>` suffix, such as `:AWSPREVIOUS`, selects a version and is
echoed as `Selector`. As on AWS, `WithDecryption` must be true, and a missing secret is
`ParameterNotFound`; `GetParameters` lists a reference it cannot answer in `InvalidParameters`.
With IAM enforcement on, the caller also needs `secretsmanager:GetSecretValue` on the secret,
and a refusal fails the whole call with a `ValidationException`, as it does on AWS.

```bash
aws ssm get-parameter --name /aws/reference/secretsmanager/my-app/api-key --with-decryption
```

## Parameter ARNs

A parameter's ARN is `arn:<partition>:ssm:<region>:<account>:parameter/<name without leading slash>`
for both a top-level name such as `db-host` and a path such as `/app/db/host`, as on AWS.
The CloudFormation `Arn` attribute of an `AWS::SSM::Parameter` is the same ARN, and
`AddTagsToResource`, `ListTagsForResource` and `RemoveTagsFromResource` accept it as `ResourceId`.
An ECS container secret's `valueFrom` accepts it too.

`GetParameter` and `GetParameters` accept the returned ARN, optionally followed by
`:version` or `:label`, for parameters in the caller's account and request region.
Batch reads keep unresolved inputs in `InvalidParameters` in their original form.
ARNs naming another account, region or partition never resolve to a same-name local
parameter. Cross-account shared parameters are not modeled. Secrets Manager
references continue to use `/aws/reference/secretsmanager/<secret-id>`, not a
parameter ARN.

## Parameter Resource Policies

`PutResourcePolicy`, `GetResourcePolicies` and `DeleteResourcePolicy` take the parameter ARN as
`ResourceArn`. Only an Advanced-tier parameter can have a policy, and it has at most one; a
`SecureString` encrypted with the default `aws/ssm` key cannot have one.
`PutResourcePolicy` returns the policy's `PolicyId` and `PolicyHash`; updating or deleting the
policy takes both, and a hash that is no longer current fails with
`ResourcePolicyConflictException`. A policy over 1024 bytes fails with
`ResourcePolicyLimitExceededException`, and a document that is not a JSON object with
`MalformedResourcePolicyDocumentException`. An overwriting `PutParameter` keeps the policy, and
is refused with `ResourcePolicyInvalidParameterException` when it would leave the policy on a
parameter that could not take one; deleting the parameter removes it. With IAM enforcement on, the three actions are checked against
the parameter `ResourceArn` names. Floci stores the policy but does not evaluate it against callers
or create the AWS RAM resource share AWS makes for a shared parameter.

## Parameter Types

All AWS parameter types are accepted: `String`, `StringList`, `SecureString`.

`PutParameter` encrypts a `SecureString` with Floci's KMS under the parameter's key
(`alias/aws/ssm` when none was given), with the encryption context `PARAMETER_ARN` set to the
parameter's ARN, as AWS does. A read without `WithDecryption` (`GetParameter`, `GetParameters`,
`GetParametersByPath`, `GetParameterHistory`) returns that ciphertext, the same one on every read
of a version, and KMS `Decrypt` with that context returns the plaintext. With `WithDecryption`
the plaintext is returned.

!!! note
    The encryption applies to what the API returns, not to storage: Floci saves each
    `SecureString` version's plaintext alongside its ciphertext, so the plaintext stays readable in
    the persisted state files.

    A `KeyId` that Floci's KMS cannot use, which AWS would refuse on `PutParameter`, is accepted,
    and such a parameter is returned unencrypted. So is a value over KMS's 4096-byte limit, and a
    parameter version stored before Floci encrypted `SecureString` values.

    AWS encrypts every Advanced `SecureString` with the AWS Encryption SDK, so its value is the
    SDK's encrypted message. Floci encrypts an Advanced value up to 4096 bytes with a plain KMS
    `Encrypt` instead, the same as a Standard one.

## Parameter Store Change events

Like AWS, Floci publishes a `Parameter Store Change` event (`source: aws.ssm`) to the account's
**default** EventBridge bus after a successful parameter write, so a rule matching `aws.ssm` fires
from Parameter Store activity. `resources` holds the parameter ARN, and the event carries the
account and region of the call.

| Call | Events | `detail` |
|---|---|---|
| `PutParameter`, new name | one `Create` | `operation`, `name`, `type`, and `description` when set |
| `PutParameter` overwriting an existing name | one `Update`, even when the value is unchanged | as for `Create` |
| `DeleteParameter` | one `Delete` | as for `Create` |
| `DeleteParameters` | one `Delete` per deleted name, none for a missing name | as for `Create` |
| `LabelParameterVersion` | one `LabelParameterVersion` per valid label it attaches or moves | as for `Create`, from the labelled version, plus `label`, `fromVersion` (the version the label left as a string, `""` for a new label) and `toVersion` |
| `UnlabelParameterVersion` | one `UnlabelParameterVersion` when it removes a label, none otherwise | as for `Create`, from the unlabelled version, plus `labels` (the labels removed), `fromVersion` (that version as a string) and `toVersion` (`""`) |

Parameters a CloudFormation stack creates or deletes publish the same events. A call that fails,
such as a `PutParameter` on an existing name without `Overwrite`, publishes nothing, and so do
`AddTagsToResource`, a label already on the requested version and an invalid label. Publishing
is best effort: a delivery failure is logged and never fails or undoes the write.
