# SSM

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonSSM.*`)
**Endpoint:** `POST http://localhost:4566/`

With IAM enforcement enabled, authorization failures return `AccessDeniedException`
with HTTP 403, including denied `iam:PassRole` checks. Parameter validation and
missing-resource errors retain their own status codes.

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
| `AddTagsToResource` | Tag a parameter, a document (by name), an association (by ID) or a customer patch baseline, picked by `ResourceType` |
| `ListTagsForResource` | List tags on a parameter, a document or an association |
| `RemoveTagsFromResource` | Remove tags from a parameter, a document or an association |
| `PutResourcePolicy` | Attach or update the resource policy of an Advanced-tier parameter |
| `GetResourcePolicies` | List the resource policy attached to a parameter |
| `DeleteResourcePolicy` | Remove a parameter's resource policy |

`GetParameters` returns successful parameters alphabetically by `Name`, preserving
the selected versions and labels.

`CreateDocument` and `CreateAssociation` keep their `Tags`. A document's tags are also returned in
the `DocumentDescription` of `CreateDocument`, `UpdateDocument` and `DescribeDocument`, and in each
`ListDocuments` entry. With IAM enforcement on, the tag calls are checked against the resource their
`ResourceType` and `ResourceId` name: `document/<name>`, `association/<id>` or `parameter/<name>`
without its leading `/`. `CreateDocument` is checked against `document/<name>`, and `CreateAssociation` against the
association and the `document/<name>` its `Name` gives.

`CreateAssociation` also runs a document another account shared with the caller through
`ModifyDocumentPermission`, given in `Name` by its ARN. With IAM enforcement on, the caller's policy must allow
that ARN. A document the owner did not share is `InvalidDocument`.

`GetParameters` returns successful parameters alphabetically by `Name`, preserving
the selected versions and labels.

`DescribeParameters` includes the stored parameter's `ARN` in each metadata entry, matching
`GetParameter`. This applies to filtered and paginated results as well.

Retained flat-name parameters from older Floci versions expose canonical
`parameter/<name>` ARNs on reads without a `PutParameter` overwrite. Normalization
preserves the ARN's partition, region and account, along with the parameter's value,
version, tags and history. Refresh cached Terraform outputs or policies that consumed
the old malformed ARN; malformed policy resources do not grant access to the canonical ARN.
The tagging actions still accept that old `parameter<name>` form as `ResourceId` when it names
the request's partition, region and account, and read it as `parameter/<name>`.

`PutParameter` removes leading and trailing spaces from `Name` before it validates and stores it,
as AWS does; a tab, newline or other whitespace is not removed. The name must then consist of
letters, numbers, `.`, `-`, `_` and the `/` separator, so a colon or an interior space fails with
a `ValidationException`. Parameters stored under invalid names by older Floci versions can
still be read, restored during CloudFormation rollback and deleted.

`GetParameter`, `GetParameters`, `GetParameterHistory`, `LabelParameterVersion` and
`GetParametersByPath` remove leading and trailing ASCII spaces from the requested name
or path before lookup, labeling or pagination. An interior space fails the complete
request with `ValidationException` unless the name addresses a stored parameter, or the
path contains stored descendants, in the caller's account and region. This exception
preserves access to legacy Floci data; it does not allow new names with interior spaces.
Secret-reference authorization uses the trimmed name;
ARN, version and label selectors retain their normal scope and permission checks.
Missing batch names are returned in `InvalidParameters` after this trimming. History
and path pagination tokens work with either the padded or canonical name.

`GetParameter` and `GetParameters` remove leading and trailing ASCII spaces from each
requested name before lookup and secret-reference authorization. An interior space fails
the complete request with `ValidationException`. ARN, version and label selectors retain
their normal scope and permission checks. Missing batch names are returned in
`InvalidParameters` after this trimming.

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

With IAM enforcement enabled, `SendCommand` checks its document ARN and every
explicit instance target. EC2 targets use `ec2:...:instance/<id>` resources;
hybrid managed nodes use `ssm:...:managed-instance/<id>`. The two implemented
AWS shell documents use AWS-owned document ARNs with an empty account field.
A document grant alone cannot authorize an instance, and an explicit deny on
either resource prevents submission.

Custom documents retain `CreateDocument.Tags` and expose their own tags through
the document description and `ResourceType=Document` tagging APIs. Tags survive
document updates and storage reloads. A same-named parameter has separate tags.
The three document tagging APIs authorize the document ARN identified by `ResourceId`.
Command authorization reads `aws:ResourceTag/<key>` from each document and
`ssm:resourceTag/<key>` from each EC2 target. One target's tags cannot authorize
another target in the same command. Tag-selected `Targets`, hybrid-node tag
conditions and shared cross-account command documents remain unsupported.

| Action | Description |
|---|---|
| `RegisterManagedInstance` | Register an EC2 agent public key using verified instance identity credentials |

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

With IAM enforcement enabled, `SendCommand` checks its document ARN and every
explicit instance target. EC2 targets use `ec2:...:instance/<id>` resources;
hybrid managed nodes use `ssm:...:managed-instance/<id>`. The two implemented
AWS shell documents use AWS-owned document ARNs with an empty account field.
A document grant alone cannot authorize an instance, and an explicit deny on
either resource prevents submission.

Custom documents retain `CreateDocument.Tags` and expose their own tags through
the document description and `ResourceType=Document` tagging APIs. Tags survive
document updates and storage reloads. A same-named parameter has separate tags.
The three document tagging APIs authorize the document ARN identified by `ResourceId`.
Command authorization reads `aws:ResourceTag/<key>` from each document and
`ssm:resourceTag/<key>` from each EC2 target. One target's tags cannot authorize
another target in the same command. Tag-selected `Targets`, hybrid-node tag
conditions and shared cross-account command documents remain unsupported.

| Action | Description |
|---|---|
| `RegisterManagedInstance` | Register an EC2 agent's RSA public key using that running instance's IMDS identity credentials |
| `UpdateInstanceInformation` | Register or update an SSM agent record for an instance. Does not create an EC2 instance or register the container with IMDS (see [Run Command Execution](#run-command-execution)) |
| `DescribeInstanceInformation` | Filter and paginate registered managed nodes in the caller's account and region |
| `SendCommand` | Create command invocations for target instances |
| `GetCommandInvocation` | Return a command invocation result |
| `ListCommands` | List command records |
| `ListCommandInvocations` | List command invocation records |
| `CancelCommand` | Cancel pending or in-progress command invocations |

### Documents

| Action | Description |
|---|---|
| `CreateDocument` | Create a document with its content, type and tags |
| `GetDocument` | Get a document's content, the default version or the one `DocumentVersion` names |
| `DescribeDocument` | Get a document's description, tags included |
| `UpdateDocument` | Store new content as the next document version; unchanged content is refused with `DuplicateDocumentContent` |
| `DeleteDocument` | Delete a document and the accounts it was shared with |
| `ListDocuments` | List documents, filtered by `Filters` or `DocumentFilterList` |
| `ModifyDocumentPermission` | Share a document with other accounts, or stop sharing it |
| `DescribeDocumentPermission` | List the accounts a document is shared with |

### Associations

| Action | Description |
|---|---|
| `CreateAssociation` | Associate a document with an instance or with targets. Floci does not run it, so the association reports `Success` at once |
| `DescribeAssociation` | Get an association by ID, or by document name and instance |
| `UpdateAssociation` | Change an association's name, document version, targets, parameters or schedule, and raise its version |
| `DeleteAssociation` | Delete an association by ID, or by document name and instance |
| `ListAssociations` | List associations, filtered by `AssociationFilterList` |

### Service settings and maintenance windows

| Action | Description |
|---|---|
| `GetServiceSetting` | Get a service setting's value, or its default when it was never changed |
| `UpdateServiceSetting` | Change a service setting's value |
| `ResetServiceSetting` | Return a service setting to its default |
| `DescribeMaintenanceWindows` | Always returns an empty list; maintenance windows are not modeled |

The service settings Floci knows are `/ssm/documents/console/public-sharing-permission`,
`/ssm/parameter-store/default-parameter-tier`, `/ssm/parameter-store/high-throughput-enabled` and
`/ssm/managed-instance/activation-tier`. Any other setting ID is refused with `ServiceSettingNotFound`.

EC2 `RegisterManagedInstance` requires a valid SigV4 request signed with the
instance identity role credentials from IMDS. The credentials must identify the
running instance in the request's account and region. Signature verification
applies even when global signature validation is disabled. These credentials
receive no general IAM or SSM permissions.

The EC2 agent supplies its instance ID as `Fingerprint`, `PublicKeyType=Rsa` and an RSA `PublicKey` of at
least 2048 bits, encoded as base64 X.509 DER or a `PUBLIC KEY` PEM block. The existing lowercase
`rsa` key type remains supported; other spellings and key algorithms are rejected.
Registration uses the existing SSM storage mode, and an identical retry preserves
the registration time. It does not mark the agent online; that requires its
subsequent `UpdateInstanceInformation` heartbeat. Activation-based hybrid
registration and managed-instance role token issuance are not implemented here.

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
`FLOCI_SERVICES_SSM_RESOURCE_DATA_SYNC_INTERVAL_MS`. Organizations data sharing and `AWSKMSKeyARN` are explicitly unsupported.
Single-account `SyncFromSource` metadata is described below.
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

The channel carries the agent's binary job, acknowledgement and result messages,
and the Session Manager session starts and terminations described below.

### Session Manager

| Action | Description |
|---|---|
| `StartSession` | Start a session on a managed node whose agent holds a control channel; returns `SessionId`, `StreamUrl` and a one-use `TokenValue` |
| `ResumeSession` | Return a new `TokenValue` and `StreamUrl` for an active session |
| `TerminateSession` | End a session: both data channels receive `channel_closed` and the agent is told over its control channel |
| `DescribeSessions` | List `Active` or `History` sessions in the caller's account and region, with the documented filters and pagination |
| `GetConnectionStatus` | `connected` while the node's agent holds an authenticated control channel |

`StartSession` requires a registered node in the caller's account and region whose
agent holds an authenticated control channel; otherwise it fails with
`TargetNotConnected`. The session starts on the agent as the agent's
`interactive_shell` message with the Session document's content and the resolved
parameter values; the agent substitutes the parameters itself. Session IDs have the
documented `prefix-random-suffix` form, where the prefix is the caller's user name,
role session name, federated user name or `root`.

Session documents: a stored `DocumentType` `Session` document of the caller's account,
or one of the built-in definitions of `AWS-StartNonInteractiveCommand` (`command`, and
`separateOutputStream`, `true` or `false`, default `false`, passed to the agent's shell properties so it
delivers stderr and the exit code apart from stdout) and `AWS-StartInteractiveCommand` (`command`),
`AWS-StartPortForwardingSession` (`portNumber`, default `80`, and `localPortNumber`) and
`AWS-StartPortForwardingSessionToRemoteHost` (adds `host`). An omitted `DocumentName`
uses the account's stored `SSM-SessionManagerRunShell`, or the documented defaults
(a `Standard_Stream` shell without logging, KMS encryption or Run As) when there is
none. The built-in definitions are Floci's; `GetDocument` and `ListDocuments` do not
return them. `String` parameters take one value and `StringList` parameters a list;
undeclared parameters, missing required parameters and values outside
`allowedValues` or `allowedPattern` fail with `ValidationException`.

With IAM enforcement enabled, `StartSession` checks the document ARN (AWS-owned
documents have an empty account field) and the target's `ec2:...:instance/<id>` or
`ssm:...:managed-instance/<id>` ARN. `ResumeSession` and `TerminateSession` check
`ssm:...:session/<session-id>`; `GetConnectionStatus` checks the selected node ARN,
and `DescribeSessions` checks `*`.

The data channel is `/v1/data-channel/{session-id}?role=publish_subscribe` on the
ssmmessages endpoint. The agent calls `CreateDataChannel` (SigV4, unscoped
`ssmmessages:CreateDataChannel`) for a one-use token, then opens the signed WebSocket
and sends `OpenDataChannel` with it; EC2 instance credentials can open only their own
node's sessions. The client opens the `StreamUrl` and sends the `TokenValue` in its
`OpenDataChannel` frame. A client upgrade may carry a SigV4 signature (headers or a
presigned URL), which must then verify for the session's account. Both sides are
authorized for `ssmmessages:OpenDataChannel` on the session ARN. Tokens are valid for
60 seconds and only once; a reconnecting client calls `ResumeSession`.

The `StreamUrl` is `wss://ssmmessages.<region>.<dns-suffix>/v1/data-channel/<session-id>?role=publish_subscribe`
when `StartSession` reached the regional AWS host name, and the same path on the
host the request addressed otherwise (`ws://` without TLS).

The service relays the agent's and the client's binary messages unchanged; the
handshake, acknowledgements, sequence numbers, retransmission and payloads are
end to end between them. It reads the agent's `agent_session_state`, which is
addressed to the service and not relayed, sends `pause_publication` to the agent
while no client is attached and `start_publication` when one attaches, and buffers
at most 256 messages (4 MiB) in each direction until both are attached. When the
agent reports the session finished (`agent_task_complete` on its control channel,
acknowledged with `agent_task_acknowledge`) the client receives `channel_closed`
with the agent's output and the session moves to `History` as `Terminated`.

Not implemented: idle and maximum session duration timeouts, session logging to
S3 or CloudWatch Logs, `JustInTime` access, the `Failed` status, session tags and
`ssm:resourceTag/aws:ssmmessages:session-id` conditions, and session preferences
applied to sessions that name another document. KMS-encrypted sessions are relayed
unchanged but not tested.

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
steps. The built-in shell documents are schema 1.2 documents, as in AWS: their one step is
their plugin, `aws:runShellScript` or `aws:runPowerShellScript`. Results remain separate for each step. A document completion summary may arrive
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
If Docker's completion notification is delayed, a finished command keeps its recorded
exit status and output. Running or indeterminate commands still time out, and guest
execution deadlines remain enforced.

Managed-node registration is independent of EC2 and IMDS. `UpdateInstanceInformation`
stores a managed-node record; it does not create an EC2 instance or install IMDS.
Launch an instance with EC2 `RunInstances`, an instance profile and an image that
starts the agent to exercise the normal credential and network paths.

EC2 `RegisterManagedInstance` requests must bind the request body to their SigV4 signature.
An explicit payload hash must match the body; `UNSIGNED-PAYLOAD` is rejected for this
registration path. Sequential or concurrent identical retries preserve the first stored
registration and its timestamp.

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

Each recommended EKS optimized AMI path also has a `recommended/release_version` leaf, which
the EKS managed node group Terraform module reads by default. As on AWS its value is the
Kubernetes patch version and the AMI build date, such as `1.34.1-20230315`: the patch version
Floci runs for that Kubernetes version and the catalog image's creation date.

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
aws ssm get-parameter --name /aws/service/eks/optimized-ami/1.31/amazon-linux-2023/x86_64/standard/recommended/release_version
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
`AddTagsToResource`, `ListTagsForResource` and `RemoveTagsFromResource` accept it as `ResourceId`
when it names the request's partition, region and account; an ARN naming another one fails with
`InvalidResourceId`. An ECS container secret's `valueFrom` accepts it too.

`GetParameter` and `GetParameters` accept the returned ARN, optionally followed by
`:version` or `:label`, for parameters in the caller's account and request region.
Batch reads keep unresolved inputs in `InvalidParameters` in their original form.
An ARN whose resource is `parameter//<name>` names `//<name>`, never `/<name>`.
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

## Source resource data syncs

| Action | Description |
|---|---|
| `CreateResourceDataSync` | Store a single-account source configuration |
| `ListResourceDataSync` | List configurations of the requested sync type |
| `UpdateResourceDataSync` | Replace an existing source configuration |
| `DeleteResourceDataSync` | Delete the named configuration of the requested type |

`CreateResourceDataSync`, `ListResourceDataSync`, `UpdateResourceDataSync`, and
`DeleteResourceDataSync` manage `SyncFromSource` configurations with
`SourceType=SingleAccountMultiRegions`. Configurations are isolated by account and
Region, with at most five source syncs in each account and Region. The source
regions and supported flags round-trip; update replaces the source configuration,
preserves its creation time, and defaults an omitted `IncludeFutureRegions` to
`false`. Account options cannot change.

This is configuration metadata only: Floci does not collect or aggregate OpsData
for these sources, and does not report an export status. Organizations sources,
mixed source/S3-destination requests, and source syncs through CloudFormation are
unsupported. Omitted `SyncType` on list and delete selects `SyncToDestination`,
retaining existing compatibility; specify `SyncFromSource` for source configurations.
Named sync operations authorize the exact `resource-data-sync/<SyncName>` ARN;
listing authorizes `*`. All four operations support the `ssm:SyncType` IAM condition.

Existing `SyncToDestination` inventory exports remain available with their separate
five-sync limit. Source configurations are excluded from the export worker.
