# AppConfig

Floci supports AWS AppConfig and AppConfigData for local configuration management.

Resources, name lookups, pagination and configuration-session tokens are isolated by
the calling account and region. Persisted records from older versions that did not
record a region remain available only in `floci.default-region`. New writes store
their region explicitly in all storage modes.

## Management Plane (AppConfig)

The management plane allows you to create and manage applications, environments, configuration profiles, and hosted configuration versions.

Environment creation retains up to five monitor definitions, including the optional alarm role.
These definitions are returned by environment reads; alarm evaluation and automatic rollback are not implemented.
Deployment creation captures the selected profile's configuration name and location URI for subsequent deployment reads.
Profiles whose `Type` is omitted report no type.

CloudFormation creates real `AWS::AppConfig::Application`, `AWS::AppConfig::Environment`,
`AWS::AppConfig::ConfigurationProfile`, and `AWS::AppConfig::DeploymentStrategy` resources.
Their `Ref` and ID `Fn::GetAtt` values resolve to the backing AppConfig IDs.
Stack deletion removes these resources and their hosted configuration/deployment state.
Unchanged reapplication preserves their identities. Property-changing updates are rejected.
KMS encryption, profile validators and retrieval roles, environment deletion protection, and
tags on child resources or deployment strategies are not supported by this provisioner;
unsupported properties are rejected before creation.

The legacy `GetConfiguration` data-plane route accepts application, environment and profile names or IDs.
It returns only the deployed version, its content type and `Configuration-Version`; a matching
`ClientConfigurationVersion` returns HTTP 204 with no body. `ClientId` is required.

### Supported Operations

- `CreateApplication`
- `GetApplication`
- `ListApplications`
- `DeleteApplication`
- `CreateEnvironment`
- `GetEnvironment`
- `ListEnvironments`
- `DeleteEnvironment`
- `CreateConfigurationProfile`
- `GetConfigurationProfile`
- `ListConfigurationProfiles`
- `DeleteConfigurationProfile`
- `CreateHostedConfigurationVersion`
- `GetHostedConfigurationVersion`
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

`CreateEnvironment` keeps up to five `Monitors`, each a CloudWatch `AlarmArn` with an optional IAM role
`AlarmRoleArn`, and environment reads return them; an environment created without monitors returns none.
Floci does not evaluate the alarms or trigger automatic rollback. More than five monitors, or an `AlarmRoleArn`
that is not an IAM role ARN, returns `BadRequestException`.
`StartDeployment` and `GetDeployment` return the deployed profile's name and location URI as
`ConfigurationName` and `ConfigurationLocationUri`. Deployments stored by earlier Floci versions did not
record them, so on those neither is set.
They also return the deployment strategy's `DeploymentDurationInMinutes`, `FinalBakeTimeInMinutes`,
`GrowthFactor` and `GrowthType` as the deployment started with them, and `ListDeployments` reports each
deployment with the profile name and strategy settings it recorded, also once its strategy is deleted.
A deployment stored by an earlier Floci version returns no strategy settings from `GetDeployment`, and
`ListDeployments` reports the strategy's current settings while the strategy exists.
A configuration profile created without `Type` reports no type, as AWS documents no default.

Configuration content becomes available immediately. A positive `FinalBakeTimeInMinutes`
keeps the deployment in `BAKING` at 100 percent until that time has elapsed; get and list
readback then report `COMPLETE`. Started and completed timestamps use the API's ISO 8601
format and survive restart. Gradual per-client rollout and alarm evaluation are not implemented.

`StopDeployment` with `AllowRevert=true` returns an in-progress deployment as `ROLLED_BACK`
or a completed deployment as `REVERTED`, restoring the previous available configuration
for that environment and profile. It rejects older deployments while a newer one is
active, and rejects completed deployments outside their 72-hour revert window.
Older persisted deployments without completion timestamps cannot be reverted because
that window cannot be verified.

## Data Plane (AppConfigData) {#data-plane}

The data plane is used by applications to retrieve the active configuration for an environment and profile.

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
