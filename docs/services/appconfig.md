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

Hosted versions accept a case-sensitive `VersionLabel` of 1–64 characters containing at least
one non-numeric character. Labels are unique among versions still stored in the profile and
are returned by create, get and list. Deleting a version releases its label.
`LatestVersionNumber` checks the highest version currently stored (zero for an empty profile);
a mismatch returns `ConflictException` without creating a version. Version numbers continue
increasing after deletion and persist across restart. Lists support `MaxResults`, `NextToken`,
and exact or trailing-asterisk prefix label filters. A label filter excludes unlabeled versions.
Hosted deployment and validation requests accept a numeric version or an exact, case-sensitive
label within the selected profile. An unknown label returns `ResourceNotFoundException` before
assigning a deployment number or changing active content. Deployment start, get and list responses
return the numeric `ConfigurationVersion` and the selected version's `VersionLabel` when it has one.
Each deployment retains its captured label across restart, version deletion and later label reuse.
Older stored deployments without a captured label continue to omit it.

### Supported Operations

- `CreateApplication`
- `GetApplication`
- `ListApplications`
- `UpdateApplication`
- `DeleteApplication`
- `CreateEnvironment`
- `GetEnvironment`
- `ListEnvironments`
- `DeleteEnvironment`
- `CreateConfigurationProfile`
- `GetConfigurationProfile`
- `UpdateConfigurationProfile`
- `ListConfigurationProfiles`
- `DeleteConfigurationProfile`
- `ValidateConfiguration`
- `CreateHostedConfigurationVersion`
- `GetHostedConfigurationVersion`
- `ListHostedConfigurationVersions`
- `DeleteHostedConfigurationVersion`
- `CreateDeploymentStrategy`
- `GetDeploymentStrategy`
- `ListDeploymentStrategies`
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
Only hosted configurations are served: a deployed version with no hosted configuration version behind it,
such as one from an S3 or SSM source, returns `ResourceNotFoundException`.

`UpdateConfigurationProfile` accepts application/profile IDs or names and updates supplied
`Name`, `Description` and `Validators` fields. Omitted fields retain their values; an empty
description or validator list clears that field. The update returns the complete profile and
preserves its ID, parent application, type and location. Validator declaration checks run before
the stored profile is replaced; schema compilation and Lambda invocation remain in configuration
validation and deployment. Updating validators does not replace hosted versions or active content.
Retrieval-role and KMS-key updates are not implemented.

Clearing `Description` with an empty string returns `Description: ""` in the update response.
The following `GetConfigurationProfile` response omits `Description`; other fields retain their values.

`CreateConfigurationProfile` stores up to two `Validators`, each with a `Type` and `Content`
of at most 32,768 characters. Hosted freeform profiles support inline `JSON_SCHEMA` validators
using draft-04, including references within the schema. `ValidateConfiguration` checks the
selected hosted version and returns HTTP 204 when it passes; invalid content or an invalid
schema returns `BadRequestException`. Validation leaves the stored profile and versions unchanged.
`StartDeployment` applies the same validators before creating a deployment or changing the active
configuration, so a refusal preserves the previous deployment and content. With IAM enforcement
enabled, validation requires `appconfig:ValidateConfiguration` on both the application and
configuration-profile ARNs.

Other schema drafts and external schema references are not supported. JSON Schema validators
are refused for feature-flag profiles. Validation of S3 and other external configuration sources
is not implemented. The CloudFormation provisioner still
ignores the template's `Validators`; these validators are configured through the management API.

Hosted profiles also support `LAMBDA` validators naming a function ARN. Both validation and deployment
invoke the function with five string fields: `applicationId`, `configurationProfileId`,
`configurationVersion`, base64-encoded `content`, and `uri` set to `hosted`. The event uses the resolved
resource IDs and numeric hosted version. An ordinary return value, including `false` or an error-shaped
JSON object, succeeds; a Lambda function error refuses validation with `BadRequestException`.
With IAM enforcement enabled, the function's resource policy must allow `appconfig.amazonaws.com`
to invoke it. Source conditions receive the exact configuration-profile ARN and the calling account.

The validation wait has a 15-second budget, including invocation startup. A function already running
when that budget expires may finish later; its result cannot publish a deployment or active content.
Floci tracks at most 32 outstanding validator invocations per process and refuses reset while any remain.
Explicit process shutdown requests worker interruption and waits up to five seconds for them to drain;
that shutdown behavior is separate from an ordinary validation timeout. Lambda validation of external
configuration sources is not supported.

`CreateEnvironment` keeps up to five `Monitors`, each a CloudWatch `AlarmArn` with an optional IAM role
`AlarmRoleArn`, and environment reads return them; an environment created without monitors returns none.
More than five monitors, or an `AlarmRoleArn` that is not an IAM role ARN, returns `BadRequestException`.
Before starting a deployment, Floci checks every configured alarm in the deployment's account, Region and
partition. A missing or unreadable monitor returns `BadRequestException` before deployment or active-content
changes. With IAM enforcement enabled, configure an explicit monitoring role that trusts AppConfig and
allows `cloudwatch:DescribeAlarms`. Metric alarms use their ARN and stored resource tags for authorization;
composite alarms require wildcard resource permission. Role permissions boundaries and SCPs also apply.
Default monitoring-role discovery is not implemented.

During rollout and final bake, enabled alarms in `ALARM` or `INSUFFICIENT_DATA` trigger automatic rollback
to the previous available hosted configuration. A background worker advances deployments without requiring
deployment-status reads. Disabled alarm actions suppress rollback. The returned `EventLog` contains
`ROLLBACK_COMPLETED` and `ROLLBACK_STARTED`, newest first, identifying the alarm and its state. Monitoring
ends at the captured rollout-plus-bake deadline; a delayed worker completes an elapsed deployment before
checking its alarms. The polling interval does not guarantee an AWS response-time bound.
`StartDeployment` and `GetDeployment` return the deployed profile's name and location URI as
`ConfigurationName` and `ConfigurationLocationUri`. Deployments stored by earlier Floci versions did not
record them, so on those neither is set.
They also return the deployment strategy's `DeploymentDurationInMinutes`, `FinalBakeTimeInMinutes`,
`GrowthFactor` and `GrowthType` as the deployment started with them, and `ListDeployments` reports each
deployment with the profile name and strategy settings it recorded, also once its strategy is deleted.
A deployment stored by an earlier Floci version returns no strategy settings from `GetDeployment`, and
`ListDeployments` reports the strategy's current settings while the strategy exists.
A configuration profile created without `Type` reports no type, as AWS documents no default.

Configuration content becomes available immediately. A positive `DeploymentDurationInMinutes` starts
the deployment in `DEPLOYING` at zero percent, with captured LINEAR or EXPONENTIAL growth steps.
After rollout, a positive `FinalBakeTimeInMinutes` keeps it in `BAKING` at 100 percent until the final
deadline, then it becomes `COMPLETE`. Started and completed timestamps use the API's ISO 8601 format
and survive restart; completion records the scheduled deadline. Gradual per-client distribution,
extension ticks and external configuration retrieval are not implemented.

`StopDeployment` with `AllowRevert=true` returns an in-progress deployment as `ROLLED_BACK`
or a completed deployment as `REVERTED`, restoring the previous available configuration
for that environment and profile. It rejects older deployments while a newer one is
active, and rejects completed deployments outside their 72-hour revert window.
If the previous hosted configuration version has been deleted, the stop is rejected
with `BadRequestException` before changing the active version or stopping the deployment.
Older persisted deployments without completion timestamps cannot be reverted because
that window cannot be verified.
Explicit stop/revert bypasses alarm reads, so an unhealthy or deleted monitor does not prevent recovery.

## Data Plane (AppConfigData) {#data-plane}

The data plane is used by applications to retrieve the active configuration for an environment and profile.

Newly delivered hosted content includes `Version-Label` only when its version has a label.
An empty hosted payload still carries its label when it is new to the session. An unchanged
poll returns an empty payload without that header and retains the resolved `Content-Type`.

### Supported Operations

- `StartConfigurationSession`
- `GetLatestConfiguration`

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

`Ref` returns the AppConfig ID of each, which `Fn::GetAtt` also returns as `ApplicationId`, `EnvironmentId`,
`ConfigurationProfileId` and `Id`. A configuration profile's `KmsKeyArn` is not set.

An update that changes only mutable properties keeps the ID and changes the resource in place: the name and
description of an application, environment or configuration profile, an environment's `Monitors`, an application's
`Tags`, and a deployment strategy's description, duration, final bake time, growth factor and growth type. A property
the template drops is cleared, or set back to its default (`FinalBakeTimeInMinutes` 0, `GrowthType` `LINEAR`). A
change to a createOnly property (`ApplicationId` on an environment or configuration profile, `LocationUri` or `Type`
on a configuration profile, `Name` or `ReplicateTo` on a deployment strategy) replaces the resource: the new one is
created and the old one is deleted once the stack update completes. A failed stack update puts back what it changed in
place and deletes what it created.

Deleting an environment deletes its deployments, and deleting a configuration profile deletes its hosted configuration
versions. Floci keeps tags on applications only, so `Tags` on the other three types are accepted and not stored, as
`TagResource` treats them; so are a configuration profile's `KmsKeyIdentifier`, `RetrievalRoleArn` and `Validators`,
and `DeletionProtectionCheck`. `ReplicateTo: SSM_DOCUMENT` writes no SSM document.
`AWS::AppConfig::HostedConfigurationVersion`, `Deployment`, `Extension` and `ExtensionAssociation` are not provisioned.

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
