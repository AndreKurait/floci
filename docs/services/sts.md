# STS

**Protocol:** Query (XML), `POST http://localhost:4566/` with `Action=` parameter

## Supported Actions

| Action | Description |
|---|---|
| `GetCallerIdentity` | Returns the account ID, user ID, and ARN |
| `AssumeRole` | Assume an IAM role, returns temporary credentials |
| `AssumeRoleWithWebIdentity` | Assume a role using a web identity token (OIDC) |
| `AssumeRoleWithSAML` | Assume a role using a SAML assertion |
| `GetSessionToken` | Get temporary credentials for an IAM user |
| `GetFederationToken` | Get temporary credentials for a federated user |
| `DecodeAuthorizationMessage` | Decode an encoded authorization failure message |
| `GetDelegatedAccessToken` | Exchange a delegation request's trade-in token for temporary credentials |
| `GetAccessKeyInfo` | Returns the account an access key ID belongs to |
| `AssumeRoot` | Opens a privileged session on a member account of the organization |
| `GetWebIdentityToken` | Returns a signed JWT representing the calling identity |

## Privileged Root Sessions

`AssumeRoot` returns "short term credentials you can use to perform privileged tasks on a member
account in your organization". The session acts as that member account's root user, which no
identity policy grants or denies, scoped by the `TaskPolicyArn` the request names: the five AWS
managed policies the model names for it, under `arn:aws:iam::aws:policy/root-task/`, each deny
everything they do not name, so the policy is the whole of the session's bound. Whose policy it is
counts as much as where it sits: a customer-managed policy can carry the same path, and installing
one would bound the session by whatever that document allows, so only AWS's own are accepted and
anything else is refused.

The caller has to be able to manage root access: the management account or a delegated
administrator for IAM, in an organization with All features and trusted access for IAM enabled, and
with `RootSessions` on. The target has to be a member account other than the management account,
privileged tasks being what the management account performs on members. `DurationSeconds` is 0 to
900 and defaults to 900, "the maximum session duration of 900 seconds (15 minutes)".

Four things are deviations rather than readings, since the operation models only
`RegionDisabledException` and `ExpiredTokenException`:

- Every caller, target and feature refusal is `AccessDenied` 403, and a task policy outside the
  root-task path is a `ValidationError`, which is the shape every other member violation answers.
- "You cannot use root user credentials to call sts:AssumeRoot" is the guide's rule; what is
  decided here is which credential that means. Both the one resolving to a root ARN, a privileged
  session opening another among them, and the bare 12-digit account-id key are refused, the latter
  because the enforcement filter already reads it as "floci's account-root principal, never an IAM
  user's key". A privileged session is therefore opened by an IAM user or role of the management
  account, as on AWS, rather than by the account itself.
- "The global endpoint is not supported for AssumeRoot" is refused when the request arrives on the
  partition's global STS host. A request to a local host carries no such name, and the signing
  region cannot tell the two apart, so there is nothing to refuse there.
- `SessionTokenSize` is answered as the length of the token issued, and `MinimumSessionTokenSize`
  pads it to at least the size asked for, held to the 0 to 4096 the model declares, "The value must
  not exceed 4,096 bytes", since the size asked for is the size materialized. Out of that range is
  a `ValidationError` and a value that is no integer an `InvalidParameterValue`, the pair
  `DurationSeconds` already answers. `SessionTokenUtilization` is not answered: the model documents
  neither member, so only the one with an obvious meaning is filled.

The audit reads the `IAMAuditRootUserCredentials` task policy allows name the member account's root
user when they are called with no `UserName`, which is what that policy's own `arn:aws:iam::*:root`
asks for. Floci keeps no user record for a root user, so those reads answer `NoSuchEntity` rather
than the credentials report AWS returns.

## Access Key Info

`GetAccessKeyInfo` answers the account an access key ID belongs to, and nothing about the key
itself: AWS states that the operation "does not indicate the state of the access key. The key might
be active, inactive, or deleted", so an inactive key answers its account the same as an active one.
A long-term `AKIA` key resolves through the account that holds it and a temporary `ASIA` key through
the session that minted it. The ID is read against its own shape first, 16 to 128 characters of
`[\w]`, which answers `ValidationError` before any lookup.

One answer is Floci's own. The operation models no errors at all, and the reference says only that
"providing a deleted access key might return an error that the key doesn't exist", so a well-formed
ID that this emulator never issued answers `InvalidParameterValue`, the published common error
closest to that sentence. Nothing in the reference says what AWS answers for an ID it never issued,
and real access key IDs are not random the way Floci's are, so treat that one as a deviation rather
than a recorded behaviour.

## Delegated Access Token

`GetDelegatedAccessToken` takes the exchange token an IAM delegation request released and answers
credentials in the customer's account, acting as the identity that approved the request, which is
the `AssumedPrincipal` it returns. Accepting a request records the identity that called it, and
that is the identity mapped to the delegated credential, so a request its owner forwarded to an
administrator for approval hands over the administrator's access rather than the owner's. The
credentials expire at the moment the customer released the token plus the request's
`SessionDuration`, not at the exchange, so the same token can be exchanged repeatedly and every
session it mints ends at the same time.

The credentials carry the permissions of that identity. AWS bounds them by the permission policy it
renders from the partner's registered policy template, which no API registers, so Floci bounds them
by the approving identity's own permissions: a user's policies and boundary, the account's access
where the account root approved the request, and nothing at all where that identity no longer
exists.

Only the account that filed the delegation request may exchange its token; any other account gets
`AccessDenied`. A token past its window, and a token nothing matches, both answer
`ExpiredTradeInTokenException`. `PackedPolicySize` is not returned, the model deprecating it and
stating it is not populated for this operation. See the delegation request section of the
[IAM service doc](iam.md) for the lifecycle that issues the token.

## Web Identity Tokens

`GetWebIdentityToken` answers "a signed JSON Web Token (JWT) that represents the calling AWS
identity", for authenticating to an external service that supports OIDC discovery. The issuer is
IAM's: outbound web identity federation mints one issuer URL per account, and the token names it as
`iss`, so the operation reads the switch behind that URL and answers
`OutboundWebIdentityFederationDisabledException` while the feature is off rather than IAM's own
`FeatureDisabled`. The declared constraints are read ahead of the switch, as everywhere else in
this package: `Audience` is required and holds 1 to 10 audiences of 1 to 1000 characters,
`SigningAlgorithm` is required at a fixed length of 5, `DurationSeconds` is 60 to 3600 and defaults
to 300, and `Tags` is at most 50 tags whose keys and values carry their own lengths and pattern. An
audience sent empty is an audience that is present, so it breaks the minimum length rather than
reading as absent.

A session cannot outlive itself. `SessionDurationEscalationException` is what a caller holding
temporary credentials gets when the requested duration would run past the moment its own session
ends, which is the sentence the reference gives that error: "You cannot use this operation to
extend the lifetime of a session beyond what was granted when the session was originally created."
A long-term key names no session and is bounded only by the 3600-second maximum.

A temporary credential with no live session behind it is refused with `ExpiredTokenException`, the
wording the enforcement filter answers for an expired session, rather than read as a caller Floci
cannot identify. An unresolvable caller is the account root stand-in elsewhere in this package,
which is what `GetCallerIdentity` answers for an unknown key, and a token is not a request: its
subject is signed and outlives the call, so a session that has already gone does not get one
naming the account root. An unknown long-term key keeps the stand-in.

The claim layout is the one the IAM User Guide publishes. The standard OIDC claims sit at the top
level, `iss`, `aud`, `sub`, `iat`, `exp` and `jti`, with the caller's ARN as the subject, and the
custom claims are nested under the `https://sts.amazonaws.com/` namespace: `aws_account` for the
account the token was asked for, `source_region` for the region it was asked from, and the
request's tags under `request_tags`. The guide notes that "all these claims may not be present in
a token at the same time", so the organization, principal tag and session context claims it also
lists are left out rather than invented, and `request_tags` is absent from a token whose request
named no tags. The nesting is what keeps a tag off a claim STS sets, a tag named `exp` being a key
under `request_tags` rather than the claim that chooses the token's lifetime.

Two answers here are Floci's readings. `jwtAlgorithmType` publishes a fixed length of 5 and no enum, so a
five-character value that is neither `RS256` nor `ES384` is answered with the enum violation this
package renders for a value outside a documented set, the two values coming from the reference
rather than from the model. The global endpoint is refused because the reference says "The
GetWebIdentityToken API is not available on the STS Global endpoint" without giving the refusal, so
it borrows the wording AWS publishes for `AssumeRoot`; a request to a local host names no such
endpoint and is not refused. `JWTPayloadSizeExceededException` is modelled and nothing published
says what the maximum payload is, so no size is invented here to refuse a request with.

The signing key is the account's, minted on first use per algorithm and stored, so a restart does
not invalidate a token already handed out. `RS256` signs with a 2048-bit RSA key and `ES384` with
P-384, whose signature is the raw `R || S` pair JWS defines rather than the DER sequence a default
ECDSA signer returns. What is not here yet is the other half of the feature: nothing publishes the
verification key, so the `/.well-known/openid-configuration` and `/.well-known/jwks.json` documents
the issuer URL would host are not served, and a relying party cannot verify one of these tokens
against this emulator.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_STS_ENABLED` | `true` | Enable or disable the service |

## Session Token and Federation Token Permissions

`GetSessionToken` and `GetFederationToken` reject temporary callers with `AccessDenied` before issuing credentials,
including when IAM policy enforcement is disabled. Use a long-term IAM user or account key.
For an authenticated temporary caller, `GetFederationToken` returns
`Cannot call GetFederationToken with session credentials` with either enforcement setting.

When `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true`, credentials from `GetSessionToken` and `GetFederationToken`
are evaluated as the identity whose long-term key signed the call, as on AWS:

- `GetSessionToken` credentials have the permissions of that IAM user, or of the account root when its key minted them,
  still bounded by the account's service control policies.
- `GetFederationToken` credentials have no permissions without a session policy (`Policy`). With one, they have the
  intersection of that policy and the permissions of the IAM user that minted them, never those of a role that shares
  the federated user's name. A resource policy naming the federated user's ARN can grant access on its own, but not
  past an explicit `Deny` in the user's policies or permissions boundary. Whatever their policies allow, they cannot
  call any IAM operation, nor any STS operation except `GetCallerIdentity`.
  Managed session policies (`PolicyArns`) are not supported yet.

A session minted with another session's temporary credentials, which AWS does not accept here, with an inactive access
key or one that belongs to another account, or by an IAM user since deleted and recreated under the same name, is not
a valid credential: it is refused like an unknown access key. A key IAM does not know anywhere mints sessions that act
as the account root, as the key itself does.

## Trust Policy Enforcement

`AssumeRole` and `AssumeRoleWithWebIdentity` require the target role to exist in IAM, returning `AccessDenied` (403)
if it does not.

When `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true`, `AssumeRole` also evaluates the target role's trust policy
(`AssumeRolePolicyDocument`) against the caller and returns `AccessDenied` if it is not permitted. AWS principal
forms are matched: `"*"`, an account id, an account-root ARN (`arn:aws:iam::<acct>:root`), and exact principal ARNs;
and an explicit `Deny` always wins. Both `Action` and `NotAction` elements are honored when matching `sts:AssumeRole`.
The caller's own identity policy must allow `sts:AssumeRole` too, evaluated against the role's ARN, so a policy can
allow assuming one role and not another. Other STS actions are evaluated against `*`, whatever `RoleArn` they carry.

The caller is the account that owns the request's credentials, whether they sign the
`Authorization` header or a presigned query string: an IAM user's or a role session's access key
belongs to its own account, not to the default one. A role session matches a `Principal` that
names its session ARN or its role's ARN, path included.

`Condition` blocks are evaluated with the same operators as identity and resource policies. The
request context holds `sts:RoleSessionName`, `sts:ExternalId` when the caller passes `ExternalId`,
`aws:PrincipalArn` (for a caller using assumed-role credentials, the role's ARN with its path, as on AWS),
`aws:PrincipalAccount`, `aws:ResourceAccount`, `aws:RequestedRegion` and
`aws:PrincipalIsAWSService`. A trust policy that requires `sts:ExternalId`, the confused-deputy
guard, refuses a call that omits it or passes another value, and a conditional `Deny` applies only
when its condition matches.

### Known limitations

- Federation support is operation-specific. Direct `AssumeRole` session tags and source identity
  are documented below. Verified SAML source identity is supported;
  OIDC source identity and federation session-tag ingestion remain separate work.

## Web Identity Validation (IRSA)

`AssumeRoleWithWebIdentity` validates tokens minted by an OIDC issuer Floci hosts - currently an EKS cluster's IRSA provider.

When the token's `iss` names a known Floci issuer, all of the following are enforced:

- the RS256 signature, against the issuer's public key
- `iss` matches that issuer exactly
- `aud` contains `sts.amazonaws.com`
- `exp` / `nbf`, with 60s of clock-skew tolerance
- the role's trust policy: `Principal.Federated` plus the `Condition` block, comparing `<oidcProvider>:sub` and `<oidcProvider>:aud` with exact, **case-sensitive** equality (`StringEquals`, `StringNotEquals`, `StringLike`, and `StringNotLike` are supported)

The response carries the token's real claims in `SubjectFromWebIdentityToken`, `Provider`, and `Audience`. A bad token returns `InvalidIdentityToken` (400), an expired one returns `ExpiredTokenException` (400), and a role that does not exist or a trust policy that does not permit the subject returns `AccessDenied` (403).

By default, tokens from an issuer Floci does not host, or tokens that are not parseable JWTs, are treated as opaque and accepted for compatibility with existing workflows, provided the target role exists in IAM. When `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true`, those tokens return `InvalidIdentityToken` because Floci cannot verify a third-party provider's signature. See [EKS](eks.md) for the full IRSA walkthrough and the token-minting endpoint.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Get caller identity (always works, useful for smoke testing)
aws sts get-caller-identity --endpoint-url $AWS_ENDPOINT_URL

# Assume a role
aws sts assume-role \
  --role-arn arn:aws:iam::000000000000:role/my-role \
  --role-session-name dev-session \
  --endpoint-url $AWS_ENDPOINT_URL

# Get a session token
aws sts get-session-token --endpoint-url $AWS_ENDPOINT_URL
```

`GetCallerIdentity` is commonly used in CI pipelines and integration tests as a quick connectivity check before running more complex tests.
For temporary credentials returned by an assumed-role action, its `Arn` and `UserId` match
the `AssumedRoleUser.Arn` and `AssumedRoleUser.AssumedRoleId` returned when the session was created.
For `GetSessionToken` credentials they are those of the IAM user that minted them, and for `GetFederationToken`
credentials the `FederatedUser.Arn` and `FederatedUser.FederatedUserId` (`<account>:<name>`).
The response resolves both header and query-string credentials, including the query session token,
so a presigned request reports the same caller identity. A nonblank Authorization header retains
precedence over query credentials.

When `FLOCI_SERVICES_IAM_SEED_DEPLOYER_PRINCIPAL=true`, requests signed with the seeded `floci` access key return `arn:aws:iam::000000000000:user/floci-deployer`. Other unknown local credentials continue to return the account root ARN for backward compatibility.

When `FLOCI_AUTH_VALIDATE_SIGNATURES=true`, the shared signature verifier checks presigned query
credentials for STS operations, including `GetCallerIdentity`, `GetSessionToken`, `GetFederationToken`
and `AssumeRole`. It checks the request signature, credential scope, expiry and session token before
execution. Nonblank Authorization headers retain priority. See [signature checks](../configuration/multi-account.md#signature-validation).

### GetSessionToken MFA context

GetSessionToken records whether its credentials were issued with verified MFA. A request without
MFA has `aws:MultiFactorAuthPresent=false`; a request with a valid code from the caller's assigned
virtual MFA device has `true`. Invalid, unassigned or another user's devices are refused before
session registration. Virtual codes use the shared TOTP implementation with one adjacent
30-second window on either side. Existing credentials without recorded MFA provenance and
GetFederationToken credentials leave the condition key absent.

Hardware and account-root MFA devices are not supported. The API restrictions and
AssumeRole request authentication are described below.

Verified GetSessionToken MFA sessions retain their authentication instant. Shared caller policy
context computes `aws:MultiFactorAuthAge` in whole elapsed seconds when resolving the session.
Non-MFA, federated and legacy records without verified authentication time leave age absent.
A server clock moving before the authentication instant yields zero elapsed seconds. This does
not establish hardware MFA or live AWS clock-drift parity.

GetSessionToken credentials retain explicit issuance provenance. With IAM enforcement enabled,
IAM API calls require verified MFA and STS calls are limited to AssumeRole and GetCallerIdentity.
These restrictions apply before identity-policy grants; other services still evaluate the issuer's
policies. Previously stored credentials without issuance provenance retain their existing behavior.
This does not add hardware/root MFA devices or workload credential delivery.

### AssumeRole MFA authentication

AssumeRole validates supplied `SerialNumber` and `TokenCode` against the caller's assigned
virtual MFA device before identity and trust policy evaluation. Valid MFA supplies
`aws:MultiFactorAuthPresent=true` and `aws:MultiFactorAuthAge=0` for that request. A missing,
invalid, unassigned, revoked or another user's device cannot authenticate the request. Omitting
both parameters preserves the calling credential's existing condition context.

The resulting assumed-role credentials do not inherit these MFA condition keys. AWS distinguishes
AssumeRole request authentication from GetSessionToken credentials, which retain MFA information
for later API calls. Hardware/root devices, MFA supplied by temporary callers and exact precedence
between multiple invalid request fields are not qualified here.

### Direct AssumeRole session tags

AssumeRole validates the supplied session tags before registration. With IAM enforcement enabled,
`sts:TagSession` must be permitted by the target trust policy and the shared caller-policy evaluation,
including identity, session-policy, permissions-boundary and SCP restrictions. Request-tag and tag-key
conditions use only tag members the STS handler consumes. A same-account trust grant naming the caller
is distinct from a grant delegating to the caller's account.

Issued credentials retain immutable session-owned tags. These override matching attached role tags
case-insensitively for `aws:PrincipalTag` conditions without changing the role or another session.
Stored session tags survive credential serialization; expiry and revocation keep their existing behavior.
IAM-disabled requests still validate tag shapes and retain the tags, without checking policy permissions.

This covers direct AssumeRole tag input and local credential authorization. SAML/web-identity/federation
tagging, packed session-token size limits and full request condition context remain separate limitations. Local JVM and signed SDK tests do not establish managed
AWS, native-image or workload credential-delivery parity.

### Direct AssumeRole tag chaining

`AssumeRole` retains explicitly selected `TransitiveTagKeys` as session-owned metadata.
Subsequent role assumptions inherit those tags, including across multiple hops, and reject
explicit tags that collide with inherited keys. Unmarked session tags and attached role tags
are not inherited. Requested transitive keys are available to identity and trust policy
conditions; inherited tags do not become new request tags. Each receiving role must permit
`sts:TagSession` when IAM enforcement is enabled.

This applies to direct `AssumeRole` sessions. SAML and web-identity tag issuance, packed-token
size accounting, and full role-chaining duration behavior are separate limitations. Local
signature-enforcing SDK tests do not establish managed AWS error-message or runtime parity.

### Direct AssumeRole source identity

`AssumeRole` retains a supplied `SourceIdentity` in the issued session and returns it in the response. Role chaining inherits the value and rejects attempts to change it. With IAM enforcement enabled, setting or inheriting it requires the target trust policy's `sts:SetSourceIdentity` permission and applicable caller permission ceilings. Role chaining requires the caller role's identity permission as well.

Trust and identity policies can use `sts:SourceIdentity` during assumption; downstream policies receive `aws:SourceIdentity` from the issued credentials. Requests without source identity leave that condition key absent. OIDC claim ingestion and CloudTrail source-identity reporting remain separate work.

SAML role sessions reject `DurationSeconds` above the target role maximum. Accepted requests retain the shorter assertion expiration.

### Verified SAML source identity

`AssumeRoleWithSAML` reads the `https://aws.amazon.com/SAML/Attributes/SourceIdentity` attribute only from the verified assertion. A present attribute must contain one valid source identity value. The role trust policy must allow both `sts:AssumeRoleWithSAML` and `sts:SetSourceIdentity`; each evaluation receives the verified `sts:SourceIdentity` value. Explicit denial prevents session creation.

The response and issued session retain the source identity through the existing shared session metadata. Missing attributes preserve the existing trust contract. This does not establish full SAML condition support, session-tag issuance, CloudTrail reporting, native-image behavior or managed AWS parity.
