# Lambda Web Functions

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/2025-03-07/web-functions/...`

The `Lambda Web` service model (apiVersion 2025-03-07, `aws lambda-web` in the AWS CLI) signs as `lambda`, so it is served under Floci's existing lambda service and is enabled by it. A Web Function owns immutable revisions (code and configuration) and endpoints (a domain name that routes to one or two revisions).

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateWebFunction` | Create a function with its first revision and endpoint |
| `ListWebFunctions` | List functions by name; `state` is the only filter |
| `GetWebFunction` | Function detail |
| `DeleteWebFunction` | Delete a function with its revisions and endpoints |
| `CreateWebFunctionRevision` | Create a revision from an S3 object; endpoints in `LatestRevision` mode move to it |
| `ListWebFunctionRevisions` | List revisions, newest first |
| `GetWebFunctionRevision` | Revision detail with the defaults the service fills in |
| `DeleteWebFunctionRevision` | Delete a revision no endpoint routes to |
| `CreateWebFunctionEndpoint` | Create a `HomeRegion`, `MultiRegion` or `PerRegion` endpoint |
| `ListWebFunctionEndpoints` | List endpoints by name |
| `GetWebFunctionEndpoint` | Endpoint detail with its per-region block |
| `UpdateWebFunctionEndpoint` | Change auth type, description, auto-deployment mode, revision weights, scaling and throttling |
| `DeleteWebFunctionEndpoint` | Delete an endpoint |
| `GetWebAccountSettings` | Account quotas and the function count |
| `ListTags` | Tags of a function |
| `TagResource` | Tag a function |
| `UntagResource` | Untag a function |
| `PutResourcePolicy` | Set the function's resource policy with an optimistic `revisionId` |
| `GetResourcePolicy` | Read the resource policy |
| `DeleteResourcePolicy` | Delete the resource policy |
<!-- floci:actions:end -->

## Emulation Behavior

- **Instant convergence:** a revision and an endpoint are stored `Active` and an endpoint update is stored `Successful`. The create and update responses still report the transitional state AWS reports (`Pending`, `updateStatus: InProgress`, and the per-region block as it was before the update), so a waiter sees one poll.
- **Code comes from S3 only.** The object named in `buildConfig.codeConfig.s3Object` must exist in Floci's S3, as on AWS. AWS also requires bucket versioning and a bucket policy for the Lambda service principal. Floci does not check them yet, and the revision does not pin a `versionId` the request did not give yet.
- **Execution role and network connector** ARNs are checked for shape only. The assume-role check AWS makes is not done yet.
- **Resource policies** are checked the way AWS checks them before storing: the document grammar, a `lambda:` action, a `Resource` that names the function, a valid principal, and no public `Allow`. A wildcard principal needs a positive condition (`StringEquals`, `StringLike`, `ArnLike`, `Bool`) on a trusted key such as `aws:PrincipalAccount` or `aws:PrincipalArn`, with no wildcard in the account it names. The policy is not evaluated on requests yet.
- **ARNs** given as identifiers must name the request's region and the caller's account, as on AWS. A revision or endpoint ARN resolves by its id or name only.
- **Page tokens** are base64 cursors that carry the request and the position of the page, not the UUIDs AWS issues. A token reused with other parameters gets the AWS "does not match the request" error, and any other token the "invalid or has expired" error.
- **Domain names** take the form `<id>.001.lambda-web.<region>.<floci host>` and `<id>.lambda-web.global.<floci host>`, with the host and port of `floci.base-url`. A `PerRegion` endpoint gets one domain per region in `regionalEndpoints`.
- **Not implemented yet:** invoking a function through its endpoint domain, execution environments, `IamAuth` on requests, and the automatic deletion of revisions past 50. The endpoint domain does not answer requests yet.

### `aws lambda-web deploy`

The CLI's `deploy` command works against Floci with `AWS_ENDPOINT_URL` set:

```bash
AWS_ENDPOINT_URL=http://localhost:4566 aws lambda-web deploy --name my-app --hello-world --create
```

The command passes `--endpoint-url` only to the Lambda Web client. Its STS, S3 and IAM calls use the default endpoints, so the environment variable is what routes them to Floci. The `endpointUrl` it prints is the Floci domain name behind `https://`, which Floci does not serve yet.

## CloudFormation

`AWS::Lambda::WebFunction`, `AWS::Lambda::WebFunctionRevision` and `AWS::Lambda::WebFunctionEndpoint` provision through the resource registry. `Ref` of each is its ARN. Every property of a revision is createOnly, so a changed revision mints a new one and the displaced revision is deleted once the update commits. An endpoint keeps its identity while its function, name, type and regions stay the same. A changed type or region list under the same name is refused the way CloudFormation refuses to replace a custom-named resource. `Fn::GetAtt` on `RegionalEndpoints` is not supported yet.

CloudFormation has no `AutoDeploymentMode` property, so an endpoint it creates is always `Disabled` and needs `RevisionWeights`, as on AWS. Name the revision with `Fn::GetAtt` so a replaced revision moves the endpoint with it. A stack update that fails after an in-place change to a function's tags or an endpoint's configuration puts the previous values back.
