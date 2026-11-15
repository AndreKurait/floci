# AWS Account Management

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci supports alternate contacts and Account Region opt-in management over the AWS REST JSON protocol.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `PutAlternateContact` | - |
| `GetAlternateContact` | - |
| `DeleteAlternateContact` | - |
| `ListRegions` | - |
| `GetRegionOptStatus` | - |
| `EnableRegion` | - |
| `DisableRegion` | - |
<!-- floci:actions:end -->

Alternate contacts are isolated by caller account and stored through `StorageFactory`.

## AWS-compatible failures

`PutAlternateContact` validates the contact type, name, title, email address, phone number, and optional target account ID. `GetAlternateContact` returns `ResourceNotFoundException` when the requested contact has not been configured. Invalid request data uses `ValidationException` and unsupported cross-account access uses `AccessDeniedException`.

AWS models provider-side `InternalServerException` and `TooManyRequestsException`; Floci does not synthesize those failures without an actual triggering condition.

See the [AWS Account Management API Reference](https://docs.aws.amazon.com/accounts/latest/reference/API_Operations.html).

## Region opt-in state

`ListRegions` and `GetRegionOptStatus` use Floci's vendored partition catalog, including Taipei and New Zealand.
`EnableRegion` and `DisableRegion` return empty HTTP 200 responses and change opt-in Regions through
`ENABLING` or `DISABLING` to `ENABLED` or `DISABLED`. Regions enabled by default cannot be changed.

State belongs to the owning account and partition, shared across signing Regions in that partition.
An explicit `AccountId` uses the existing Organizations membership, trusted-access and administrator checks.
The four operations authorize the account or actual organization-member ARN. Enable, disable and named
status reads support `account:TargetRegion`; list requests do not invent that condition.

Transition intents and deadlines use the existing Account storage configuration. Floci settles a transition
after a local 500 ms schedule, including after restart, without a background worker. AWS completion may take
much longer. Opposing requests during a transition conflict; repeated requests toward the same state are
local no-ops. Reset drains admitted writes before clearing state.

Lists filter before paging, with `MaxResults` from 1 to 50 (local default 50). Local opaque tokens bind the
account, partition, filters, page size and state generation. State changes, settlement, restart or reset can
invalidate a token. Sorted output and this token lifetime are local implementation choices.

This control plane does not propagate enablement into EC2, IAM regional access or other regional services.
It does not emit transition events or enforce AWS Region-request quotas and rates.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ACCOUNT_ENABLED` | `true` | Enable or disable AWS Account Management |
