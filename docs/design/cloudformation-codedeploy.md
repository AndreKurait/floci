# CloudFormation CodeDeploy resources

CloudFormation application and deployment-group resources use the existing
CodeDeploy service. Their `Ref` values are the actual application and group
names. The published registry schemas define no `Fn::GetAtt` attributes for
either type.

The application owns its compute platform and tags. A deployment group retains
its application identity for deletion and replacement, and passes its resolved
configuration to CodeDeploy. This includes Auto Scaling group names, instance
tag filters, load balancer settings, alarms, rollback settings and triggers.
CloudFormation tag-set wrappers are converted to the API's list structure.

Unnamed resources retain their generated names during an in-place update.
Create-only changes require replacement. Mutable configuration and tags have
an update snapshot so failed stack updates can restore the previous state.
Cleanup follows the resource's recorded application, account and Region.

The `Deployment` property requires starting and waiting for a revision during
CloudFormation provisioning. This initial implementation explicitly rejects it,
and also rejects `Ec2DeploymentOptions`, before creating or mutating a resource.
Creating a deployment group establishes configuration only. Installing a
revision requires `CreateDeployment` and a running
[host agent](codedeploy-agent-runtime.md). Auto Scaling launch hooks still need
separate runtime work and qualification.

Group cleanup retains the application for each live or displaced name. A failed
replacement can restore the original group and retry deletion of the replacement
in its own application. Completed cleanup removes unused identity records.

SDK lifecycle tests inspect CodeDeploy resources after stack creation, updates,
replacements, rollback and deletion. They also exercise identical names in
separate accounts and Regions. Focused tests inject deletion failures to check
retry ownership and ensure access errors cannot be mistaken for successful
cleanup.

## Sources

- [Application template reference](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-codedeploy-application.html)
- [DeploymentGroup template reference](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-codedeploy-deploymentgroup.html)
- [Published registry schema archive](https://schema.cloudformation.us-east-1.amazonaws.com/CloudformationSchema.zip)
