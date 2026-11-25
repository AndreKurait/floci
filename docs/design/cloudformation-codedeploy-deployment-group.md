# CloudFormation CodeDeploy deployment groups

`AWS::CodeDeploy::DeploymentGroup` creates and updates a native deployment group. `Ref` returns the group name. The handler resolves application and role references, converts nested CloudFormation fields to the CodeDeploy request shape, and reconciles mutable configuration and tags without replacing the group.

The application name is tracked alongside every current or displaced group name. Replacement cleanup and rollback retries use that recorded parent identity. Missing identity fails before deletion; cleanup never searches another application for a matching group name. Only missing-resource responses are tolerated, and access-denied or other failures remain visible for retry.

Dropping mutable fields clears their native values. Dropping `DeploymentConfigName` restores `CodeDeployDefault.OneAtATime`; required application and role fields remain required. A changed parent or a removed declared group name creates a replacement with its own cleanup record. Failed in-place updates restore both configuration and tags.

`Deployment` and `Ec2DeploymentOptions` are rejected because this handler does not execute an inline deployment. Conflicting EC2 or on-premises filter and tag-set forms are rejected before creation.

The dedicated group provisioner leaves the current Application provisioner intact. DeploymentConfig provisioning has separate review ownership in #785. The shared native service carries only the group behavior needed here: inherited compute platform, explicit null clearing, and conversion of Auto Scaling group names. Existing application/group deletion and tag cleanup remain unchanged.
