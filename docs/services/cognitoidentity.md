# Amazon Cognito Identity

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSCognitoIdentityService.<Action>`)
**Endpoint:** `http://localhost:4566/` (SigV4 service `cognito-identity`)

Cognito Identity is the federated identity pool API, distinct from the Cognito user pool
API (`cognito-idp`, documented in [Cognito](cognito.md)). Floci supports identity pool management, guest identities, developer identity links,
and enhanced or classic credential flows backed by real IAM role sessions.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateIdentityPool` | Create a pool; returns the whole pool including tags |
| `DescribeIdentityPool` | Read a pool by id |
| `UpdateIdentityPool` | Replace a pool's configuration wholesale |
| `DeleteIdentityPool` | Delete a pool |
| `ListIdentityPools` | Page through pools, ordered by pool id |
| `SetIdentityPoolRoles` | Set the authenticated/unauthenticated roles and role mappings |
| `GetIdentityPoolRoles` | Read the roles and role mappings |
| `TagResource` | Add tags to a pool by ARN |
| `UntagResource` | Remove tags from a pool by ARN |
| `ListTagsForResource` | List a pool's tags |
| `SetPrincipalTagAttributeMap` | Map principal tags to user attributes for one provider |
| `GetPrincipalTagAttributeMap` | Read a provider's principal tag attribute map |
| `GetId` | Create an unauthenticated identity in an enabled pool |
| `DescribeIdentity` | Read identity dates and provider names |
| `ListIdentities` | Page through identities, optionally hiding disabled identities |
| `DeleteIdentities` | Delete owned identities |
| `GetOpenIdToken` | Issue a signed guest token for an enabled classic flow |
| `GetOpenIdTokenForDeveloperIdentity` | Link developer users and issue a signed identity token |
| `GetCredentialsForIdentity` | Exchange a guest identity or signed developer token for a scoped role session |
| `LookupDeveloperIdentity` | Resolve an identity or page its developer user identifiers |
| `UnlinkDeveloperIdentity` | Remove a developer user link and disable access after its last link |
<!-- floci:actions:end -->

Pool ids use the AWS `<region>:<uuid>` format (`us-east-1:0f4a...`), and pool ARNs the
documented `arn:aws:cognito-identity:<region>:<account>:identitypool/<poolId>` form, so
callers that parse either get the same structure they would from AWS.

`UpdateIdentityPool` takes the full `IdentityPool` shape and replaces the stored pool with
it: a member the caller omits is reset to its default rather than carried over, matching
the AWS behaviour ("if you don't provide a value for a parameter, Amazon Cognito sets it to
its default value"). Roles, role mappings and principal tag attribute maps are set by their
own operations, are not part of the `IdentityPool` shape, and survive the replace.
An already configured `DeveloperProviderName` must be supplied unchanged: replacing or
omitting it is rejected before the pool or its existing identity links are changed.

`TagResource` writes into the same store as `IdentityPoolTags`, so a tag added by ARN is
visible on the next `DescribeIdentityPool` and vice versa.

`GetIdentityPoolRoles` returns `Roles` and `RoleMappings` as empty maps before
`SetIdentityPoolRoles` has run, never as null, so a provider read that dereferences them
does not crash.

## Identity and credential flows

Guest `GetId`, `GetOpenIdToken`, and `GetCredentialsForIdentity` use the identity's regional
pool and owner. Administrative identity operations require the owning account and request
region. Developer token requests accept exactly the pool's configured developer provider.
The enhanced flow accepts that signed token under its exact `iss` value in `Logins`.

Identity tokens use persisted per-pool RSA signing keys and RS512 signatures. Their issuer,
audience, subject, authentication methods, and validity window are verified before role
exchange, including when IAM enforcement is disabled. Older commercial regions use the
global Cognito identity issuer; other regions use their regional issuer and partition DNS
suffix. This is separate from the existing RS256 EKS web identity path.

Role exchange evaluates the real role trust policy, including pool audience and authentication
method conditions. Enhanced roles must belong to the pool's account and partition. Classic
cross-account roles must constrain the pool audience. Returned credentials are registered IAM
sessions with expiration, session-token authentication, role policies, and Cognito condition
context. Enhanced guest permissions are restricted to the combined documented inline and
managed guest policy allow sets, intersected with role permissions. Enhanced sessions cannot
call identity-pool or Cognito Sync APIs. Classic sessions retain role grants and any requested
inline session-policy restriction. Each classic exchange requires an active identity and a
pool with `AllowClassicFlow` enabled, including exchanges using a previously issued token.

Identity records, developer links, and signing keys persist through storage reloads. Removing
the last developer link disables new public token and credential access; it never turns an
authenticated identity into a guest. It does not revoke already issued IAM sessions.

## Limitations

External identity-provider token validation and linking, `UnlinkIdentity`, and
`MergeDeveloperIdentities` are not implemented. `GetId` and `GetOpenIdToken` currently support
guests without `Logins`; authenticated access uses developer-issued tokens. Requests with
active role mappings, principal-tag mappings, requested `PrincipalTags`, or `CustomRoleArn`
are refused instead of silently selecting a default role. Classic exchange does not accept
managed session-policy ARNs. These limits also apply when IAM enforcement is disabled.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_COGNITOIDENTITY_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws cognito-identity create-identity-pool \
  --identity-pool-name my-pool \
  --allow-unauthenticated-identities

aws cognito-identity set-identity-pool-roles \
  --identity-pool-id us-east-1:... \
  --roles authenticated=arn:aws:iam::000000000000:role/authenticated

aws cognito-identity describe-identity-pool --identity-pool-id us-east-1:...

aws cognito-identity delete-identity-pool --identity-pool-id us-east-1:...
```

Tokens without a known local OIDC signing key cannot bypass a role's Cognito token verification,
even when general IAM enforcement is disabled. Mixed Cognito/OIDC trust still accepts local OIDC
tokens through the existing signature, audience and role-trust checks. This is a local verifier routing
guarantee; it does not change the unrelated STS fallback for roles without Cognito trust.
