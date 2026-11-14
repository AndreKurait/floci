# AppConfig

Floci supports AWS AppConfig and AppConfigData for local configuration management.

Resources, name lookups, pagination and configuration-session tokens are isolated by
the calling account and region. Persisted records from older versions that did not
record a region remain available only in `floci.default-region`. New writes store
their region explicitly in all storage modes.

With IAM enforcement enabled, AppConfig and AppConfigData requests require the
caller's `appconfig:` permissions. Scoped policies use canonical resource IDs even
when the request uses names. Configuration sessions authorize both creation and
polling against the application/environment/configuration ARN; a denied poll does
not consume its token. Passing retrieval or monitor roles also requires
`iam:PassRole` for the AppConfig service. IAM enforcement remains disabled by default.

Tagged `CreateApplication` and `CreateDeploymentStrategy` requests also require
`appconfig:TagResource` on `application/*` or `deploymentstrategy/*` in the request's
partition, account and Region. Both the create action and tagging action evaluate
`aws:RequestTag` and `aws:TagKeys` conditions. An untagged create needs only its create
permission. This tag-on-create admission currently covers these two operations;
child-resource and deployment creation tags remain outside this admission scope.

## Management Plane (AppConfig)

The management plane allows you to create and manage applications, environments, configuration profiles, and hosted configuration versions.

Environment creation retains up to five monitor definitions, including the optional alarm role.
These definitions are returned by environment reads and monitored during rollout and final bake.
Deployment creation captures the selected profile's configuration name and location URI for subsequent deployment reads.
Hosted and S3 profiles default an omitted `Type` to `AWS.Freeform`. Other sources keep an omitted type unset.

CloudFormation creates backing AppConfig resources. See [CloudFormation](#cloudformation)
for supported types, updates, deletion and property limitations.

The legacy `GetConfiguration` data-plane route accepts application, environment and profile names or IDs.
It returns only the deployed version, its content type and `Configuration-Version`; a matching
`ClientConfigurationVersion` returns HTTP 204 with no body. `ClientId` is required.

### Supported Operations

- `CreateApplication`
- `GetApplication`
- `ListApplications`
- `UpdateApplication`
- `DeleteApplication`
- `CreateEnvironment`
- `GetEnvironment`
- `ListEnvironments`
- `UpdateEnvironment` (IDs only)
- `DeleteEnvironment`
- `CreateConfigurationProfile`
- `GetConfigurationProfile`
- `UpdateConfigurationProfile`
- `ValidateConfiguration`
- `ListConfigurationProfiles`
- `DeleteConfigurationProfile`
- `CreateHostedConfigurationVersion`
- `GetHostedConfigurationVersion`
- `DeleteHostedConfigurationVersion`
- `CreateDeploymentStrategy`
- `GetDeploymentStrategy`
- `ListDeploymentStrategies`
- `UpdateDeploymentStrategy`
- `DeleteDeploymentStrategy`
- `StartDeployment`
- `StopDeployment`
- `GetDeployment`
- `ListDeployments` (`GET /applications/{ApplicationId}/environments/{EnvironmentId}/deployments`)
- `GetConfiguration` (deprecated by AWS in favor of the [data plane](#data-plane))

`GetConfiguration` accepts application, environment and configuration profile names or IDs, and requires
`client_id`. It returns the deployed version's content with its `Content-Type` and `Configuration-Version`;
feature flags are resolved as on the [data plane](#data-plane), including Ion for a caller that accepts it.
An exact resource ID takes precedence over another resource's name. If multiple resources in the
selected scope share a name, name lookup returns `BadRequestException`; use the intended resource's ID.
This also applies to the management operations and `StartConfigurationSession` that use these lookups.
A `client_configuration_version` equal to the deployed version returns HTTP 204 with an empty body
and `Configuration-Version`, without `Content-Type` or `Content-Length`. An empty configuration
that the caller has not received still returns HTTP 200 and its stored content type.
A missing or undeployed environment returns `BadRequestException`; an unknown application or configuration profile
returns `ResourceNotFoundException`.
Hosted configurations and deployed S3 object versions are served. Other sources, including SSM,
are not retrieved; a version without stored content returns `ResourceNotFoundException`.

An S3 profile uses an `s3://bucket/key` URI or S3 object ARN as `LocationUri`, and an IAM
`RetrievalRoleArn`. The source must belong to the application's AWS partition. `StartDeployment`
retrieves the specified immutable object version before changing the active configuration. With IAM
enforcement enabled, the role must trust AppConfig and allow `s3:GetBucketVersioning` and
`s3:GetObjectVersion`; bucket policies, role boundaries, SCPs and version-specific object tags apply.
Cross-account buckets require `FLOCI_SERVICES_S3_GLOBAL_BUCKET_NAMESPACE=true`.
Missing versions, denied retrievals, unsupported storage classes and objects larger than 1 MB are rejected.
The admitted bytes persist with the profile, so readers and rollback keep working after the source is
deleted or retrieval permission changes. S3 configurations deployed before this support must be redeployed.
SSE-S3 is supported. SSE-KMS, SSE-C and mutable `null` object versions are not supported.

Profiles retain up to two `Validators`. Inline `JSON_SCHEMA` validators use draft-04 for free form
configuration, including local references, required fields, types, ranges and pattern properties.
Profile creation and update retain declarations after checking their shape, type and size. Schema
compilation is deferred until content validation, so malformed schemas can be stored but cannot
validate or deploy configuration. `StartDeployment`
validates the retrieved hosted or S3 version before creating a deployment or changing active content.
`ValidateConfiguration` performs the same validation without deploying and returns HTTP 204.

`UpdateConfigurationProfile` changes only supplied fields. An empty `Validators` list clears validation;
a schema update does not change already deployed bytes. CloudFormation updates replace mutable fields,
clear validators omitted from the template, and restore the prior schema on rollback. Stored validators
survive restart. External schema references and other inline schema drafts are refused. Lambda validator
declarations are retained, but validation and deployment requiring them fail explicitly because invoking
Lambda validators is not implemented. `UpdateConfigurationProfile` rejects `KmsKeyIdentifier`.

`UpdateEnvironment` and `UpdateDeploymentStrategy` apply only the mutable fields present in a native
PATCH request. Omitted fields retain their values; an empty description or monitor list clears that
field, and a zero duration or final bake time is stored. The whole request is validated before the
stored record is replaced. Persistent and WAL storage publish each replacement after the atomic write
succeeds; hybrid storage retains asynchronous durability. Updates preserve resource IDs, environment state, strategy names and
`ReplicateTo`, and survive storage reloads. Predefined strategies cannot be updated.

`UpdateEnvironment` currently requires the application and environment IDs. AWS also documents name
selectors; this PATCH implementation does not resolve them. Existing Get operations retain their
name lookup behavior. The PATCH operations do not change rollout behavior or environment state spelling.
CloudFormation continues to replace all mutable properties, including clearing omitted template fields.

With IAM enforcement enabled, these PATCH requests check their own action and concrete resources:
both the application and environment for `UpdateEnvironment`, and the custom strategy for
`UpdateDeploymentStrategy`. Every supplied monitor `AlarmRoleArn` also needs `iam:PassRole` for
AppConfig. Omitting monitors reuses stored roles without another PassRole check; an empty list passes
no role. Environment and strategy resource-tag conditions are not implemented by this change.

`CreateEnvironment` keeps up to five `Monitors`, each a CloudWatch `AlarmArn` with an optional IAM role
`AlarmRoleArn`, and environment reads return them; an environment created without monitors returns none.
More than five monitors, or an `AlarmRoleArn` that is not an IAM role ARN, returns `BadRequestException`.
`StartDeployment` and `GetDeployment` return the deployed profile's name and location URI as
`ConfigurationName` and `ConfigurationLocationUri`. Deployments stored by earlier Floci versions did not
record them, so on those neither is set.
They also return the deployment strategy's `DeploymentDurationInMinutes`, `FinalBakeTimeInMinutes`,
`GrowthFactor` and `GrowthType` as the deployment started with them, and `ListDeployments` reports each
deployment with the profile name and strategy settings it recorded, also once its strategy is deleted.
A deployment stored by an earlier Floci version returns no strategy settings from `GetDeployment`, and
`ListDeployments` reports the strategy's current settings while the strategy exists.
Reading an older profile does not backfill its stored `Type`.

Deployment status follows the recorded strategy: `DEPLOYING` for its rollout duration,
then `BAKING` at 100 percent for its final bake time, then `COMPLETE`. Background ticks,
get and list advance linear or exponential growth in steps across the rollout. Completion timestamps
include both durations, use the API's ISO 8601 format and survive restart, even after
the strategy is changed or deleted. A zero-duration rollout starts baking immediately;
zero rollout and bake durations complete immediately.

Configuration content still becomes available immediately to every client. Gradual
per-client delivery is not implemented; reported deployment
progress does not yet control which clients receive the configuration.

During rollout and final bake, an enabled monitor in `ALARM` or `INSUFFICIENT_DATA`
automatically restores the previous configuration and records `ROLLBACK_STARTED` and
`ROLLBACK_COMPLETED` events with the alarm ARN and `TriggeredBy: CLOUDWATCH_ALARM`.
Disabled alarm actions suppress rollback. A background worker checks active deployments,
including after restart, so data-plane clients do not need to poll deployment status.
Completed deployments are no longer monitored.

Monitors must reference existing alarms in the deployment's account, region and partition.
With IAM enforcement enabled, `AlarmRoleArn` must identify a role in that account that
trusts AppConfig and permits `cloudwatch:DescribeAlarms`. Metric alarms can use an alarm
resource permission; composite alarms require `Resource: "*"`. This uses the role session's
ordinary policy, boundary and organization checks.

An unreadable monitor is rejected before a deployment starts. If access is lost later,
Floci leaves progress unchanged, reports `BadRequestException` on progress reads, and
retries in the background. That failure handling is a local safeguard; exact AWS behavior
for mid-deployment permission revocation is not qualified. Explicit stop/revert bypasses
monitor reads, preserving recovery when the monitoring role is broken.
Monitoring consumes CloudWatch's reported state; it does not add support for
[unimplemented metric-math alarm evaluation](cloudwatch.md).

`StopDeployment` with `AllowRevert=true` returns an in-progress deployment as `ROLLED_BACK`
or a completed deployment as `REVERTED`, restoring the previous available configuration
for that environment and profile. It rejects older deployments while a newer one is
active, and rejects completed deployments outside their 72-hour revert window.
If the previous hosted configuration version has been deleted, the stop is rejected
with `BadRequestException` before changing the active version or stopping the deployment.
Older persisted deployments without completion timestamps cannot be reverted because
that window cannot be verified.

## Data Plane (AppConfigData) {#data-plane}

The data plane is used by applications to retrieve the active configuration for an environment and profile.

### Supported Operations

- `StartConfigurationSession`
- `GetLatestConfiguration`

Missing applications, environments and profiles during session creation return HTTP 404 with
the SDK-modeled `ResourceType` and `ReferencedBy` fields. Polling after the application or
environment is deleted identifies the missing `Configuration` through `ConfigurationToken`,
without consuming the token. Starting a new session identifies the specific missing resource.

Basic `AWS.AppConfig.FeatureFlags` profiles are returned in AWS retrieval-time JSON format.

Feature flags with variants are returned, as AWS does, in Amazon Ion (`application/ion; type=AWS.AppConfig.FeatureFlags`)
to a caller whose `Accept` header asks for it, as the AWS AppConfig Agent's does: the caller evaluates the variants'
rules against its own context (`?flag=name&context=key=value`). The layout is the one AWS documents for the agent's
[local development mode](https://docs.aws.amazon.com/appconfig/latest/userguide/appconfig-agent-how-to-use-local-development-samples.html#appconfig-agent-how-to-use-local-development-samples-multi-variant):
each flag with variants is a list of its variants, and each basic flag in the same profile is its retrieval-time JSON
as a string. Rules of the form `(eq $attribute "value")` are
encoded; a profile with any other rule is returned unchanged, as JSON, and Floci logs a warning, as it does for a
caller that does not accept Ion.

## CloudFormation

Floci provisions these resource types:

- `AWS::AppConfig::Application`
- `AWS::AppConfig::Environment`
- `AWS::AppConfig::ConfigurationProfile`
- `AWS::AppConfig::DeploymentStrategy`
- `AWS::AppConfig::HostedConfigurationVersion`
- `AWS::AppConfig::Deployment`

For the first four types, `Ref` returns the AppConfig ID, which `Fn::GetAtt` also returns as `ApplicationId`, `EnvironmentId`,
`ConfigurationProfileId` and `Id`. A configuration profile's `KmsKeyArn` is not set.

For a hosted version, `Ref` and `Fn::GetAtt VersionNumber` return the version number.
Its physical identity also includes its application and profile, so versions with the same
number in different profiles remain separate resources. Hosted version numbers increase
across concurrent creates, deletions and restarts. Older persisted data without a counter
starts above its highest remaining version.

Changing hosted content, content type, description or parent creates a new version.
Successful replacement deletes the old version unless `UpdateReplacePolicy: Retain`
keeps it; rollback deletes the new version and restores the original resource.
`DeletionPolicy: Retain` keeps a version when its stack removes it.
`LatestVersionNumber` and `VersionLabel` are rejected before creation because the
management service does not implement them.

Deployment creation waits for AppConfig's `COMPLETE` state, preserving the strategy's
rollout and final bake durations. `Fn::GetAtt DeploymentNumber` is a string and `State`
reports completion. The physical identity and `Ref` include application, environment
and deployment number. Rollback or revert during creation fails the stack resource;
the wait is interruptible and bounded to 24 hours.

Changing deployment properties creates a new deployment. Stack deletion and failed
updates use `StopDeployment` with `AllowRevert=true`. Deleting a displaced deployment
does not revert a newer one. Already reverted deployments and deployments outside
their 72-hour revert window need no further change. Other failures, including missing
prior configuration content, propagate instead of reporting successful cleanup.
`KmsKeyIdentifier`, `DynamicExtensionParameters` and deployment `Tags` are rejected.
Alarm-triggered rollback fails an in-progress stack deployment and restores the previous
configuration. This lifecycle does not add gradual per-client content delivery.

An update that changes only mutable properties keeps the ID and changes the resource in place: the name and
description of an application, environment or configuration profile, a profile's `RetrievalRoleArn` and `Validators`,
an environment's `Monitors`, an application's
`Tags`, and a deployment strategy's description, duration, final bake time, growth factor and growth type. A property
the template drops is cleared, or set back to its default (`FinalBakeTimeInMinutes` 0, `GrowthType` `LINEAR`). A
change to a createOnly property (`ApplicationId` on an environment or configuration profile, `LocationUri` or `Type`
on a configuration profile, `Name` or `ReplicateTo` on a deployment strategy) replaces the resource: the new one is
created and the old one is deleted once the stack update completes. A failed stack update puts back what it changed in
place and deletes what it created.

Deleting an environment deletes its deployments, and deleting a configuration profile deletes its hosted configuration
versions and admitted S3 content. Floci keeps tags on applications only, so `Tags` on the other three types are accepted
and not stored, as `TagResource` treats them; so is a configuration profile's `KmsKeyIdentifier`,
and `DeletionProtectionCheck`. `ReplicateTo: SSM_DOCUMENT` writes no SSM document.
`AWS::AppConfig::Extension` and `ExtensionAssociation` are not provisioned.

A stack created before Floci provisioned these types holds stubs for them, and an update creates the real resource in
place of a stub only when it changes that resource. An update that changes an environment or configuration profile of
a stubbed application therefore fails with `ResourceNotFoundException` unless it changes the application too.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_APPCONFIG_ENABLED` | `true` | Enable or disable the AppConfig management plane |
| `FLOCI_SERVICES_APPCONFIGDATA_ENABLED` | `true` | Enable or disable the AppConfigData retrieval plane |

## Example Usage

### 1. Create an Application and Environment

```bash
# Create application
aws appconfig create-application --name my-app --endpoint-url http://localhost:4566

# Create environment
aws appconfig create-environment --application-id <app-id> --name dev --endpoint-url http://localhost:4566
```

### 2. Create a Hosted Configuration

```bash
# Create configuration profile
aws appconfig create-configuration-profile \
  --application-id <app-id> \
  --name my-profile \
  --location-uri hosted \
  --type AWS.Freeform \
  --endpoint-url http://localhost:4566

# Create hosted configuration version
aws appconfig create-hosted-configuration-version \
  --application-id <app-id> \
  --configuration-profile-id <profile-id> \
  --content "{\"foo\": \"bar\"}" \
  --content-type application/json \
  --endpoint-url http://localhost:4566
```

### 3. Deploy the Configuration

```bash
# Create immediate deployment strategy
aws appconfig create-deployment-strategy \
  --name immediate \
  --deployment-duration-in-minutes 0 \
  --growth-factor 100 \
  --final-bake-time-in-minutes 0 \
  --endpoint-url http://localhost:4566

# Start deployment
aws appconfig start-deployment \
  --application-id <app-id> \
  --environment-id <env-id> \
  --configuration-profile-id <profile-id> \
  --configuration-version 1 \
  --deployment-strategy-id <strategy-id> \
  --endpoint-url http://localhost:4566
```

### 4. Retrieve Configuration via Data Plane

```bash
# Start configuration session
TOKEN=$(aws appconfigdata start-configuration-session \
  --application-identifier <app-id> \
  --environment-identifier <env-id> \
  --configuration-profile-identifier <profile-id> \
  --query "InitialConfigurationToken" --output text \
  --endpoint-url http://localhost:4566)

# Get latest configuration
aws appconfigdata get-latest-configuration \
  --configuration-token $TOKEN \
  --endpoint-url http://localhost:4566
```
