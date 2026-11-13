# AWS Private CA

Floci supports a root certificate authority lifecycle through the AWS Private CA
JSON 1.1 API. Certificate authorities have actual RSA keys, signed PKCS#10
certificate signing requests, and X.509 certificates. Issued certificates verify
against their issuing authority; responses do not expose the authority's private
key.

## Bootstrap and issuance

1. Call `CreateCertificateAuthority` with type `ROOT`, key algorithm `RSA_2048`,
   signing algorithm `SHA256WITHRSA`, and a subject.
2. Retrieve the CSR with `GetCertificateAuthorityCsr`.
3. Submit that CSR to `IssueCertificate` on the same authority, using
   `arn:aws:acm-pca:::template/RootCACertificate/V1` and the requested validity.
   Use the corresponding ARN partition in China or GovCloud.
4. Retrieve the signed root using `GetCertificate` and activate the authority with
   `ImportCertificateAuthorityCertificate`. A root import omits `CertificateChain`.
5. Submit a client-generated RSA CSR to the active authority. The default
   `EndEntityCertificate/V1` template copies the CSR subject and subject alternative
   names and issues a certificate for TLS server and client authentication.
   `GetCertificate` returns the certificate and its root chain.

CSRs must prove possession of their private key and contain a subject or subject
alternative name. End-entity CSRs may use RSA 2048, 3072 or 4096 bit keys. Issuance
uses SHA-256 with RSA and checks that the requested expiration does not exceed the
authority certificate's expiration. Validity supports `DAYS`, `MONTHS`, `YEARS` and
`ABSOLUTE`; an optional `ValidityNotBefore` supports `ABSOLUTE`. The default start
time is one hour before issuance.

The two supported base templates ignore `ApiPassthrough`, including supplied
subject, subject alternative names, key usage and extended key usage. The subject
and alternative names come from the CSR; key usage and extended key usage come
from the template. Other CSR extension requests are ignored. Template variants
named `APIPassthrough` or `APICSRPassthrough` are not supported.

Root import verifies the CSR key and subject, self-signature, current validity,
critical CA basic constraints and `keyCertSign` usage before changing state.
Imported name or policy constraints, extended key usage and critical extensions
outside basic constraints, key usage and subject alternative names are rejected.
This implementation supports initial activation of a pending root authority;
replacing its certificate is not supported.

## State and ownership

New authorities start in `PENDING_CERTIFICATE`. Successful import makes them
`ACTIVE`; `UpdateCertificateAuthority` can disable or reactivate them. Disabled
authorities cannot issue certificates. An expired authority is reported as
`EXPIRED`.

Disable an active authority before deleting it. `DeleteCertificateAuthority`
retains it in `DELETED` state for 7 to 30 days, defaulting to 30.
`DescribeCertificateAuthority` reports `RestorableUntil`.
`RestoreCertificateAuthority` restores an imported authority as `DISABLED`, or
an authority without an imported certificate as `PENDING_CERTIFICATE`. Keys,
issued certificates and creation metadata survive restoration. Expired deletion
state is removed when it is next accessed. Deletion does not immediately revoke
previously issued certificates.

Authorities and their issued certificates are isolated by account and Region,
with partition-correct ARNs. `ListCertificateAuthorities` supports `SELF` ownership
and scoped pagination. Create and issue idempotency tokens last five minutes.
IAM checks support action and CA-ARN resource grants and denies. Issuance supplies
the `acm-pca:TemplateArn` condition key, including the default end-entity template
when the request omits it.
Durable storage preserves signing keys and certificate state across restarts.
Signing keys are stored in the emulator's local storage file; this is not an HSM
or a production certificate authority.

## Supported operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCertificateAuthority` | - |
| `DescribeCertificateAuthority` | - |
| `GetCertificateAuthorityCsr` | - |
| `IssueCertificate` | - |
| `GetCertificate` | - |
| `ImportCertificateAuthorityCertificate` | - |
| `GetCertificateAuthorityCertificate` | - |
| `UpdateCertificateAuthority` | - |
| `DeleteCertificateAuthority` | - |
| `RestoreCertificateAuthority` | - |
| `ListCertificateAuthorities` | - |
<!-- floci:actions:end -->

## Configuration

```yaml
floci:
  services:
    acm-pca:
      enabled: true
  storage:
    services:
      acm-pca:
        mode: memory
        flush-interval-ms: 5000
```

## Scope

Supported subjects contain `Country`, `State`, `Locality`, `Organization`,
`OrganizationalUnit`, `CommonName` or `SerialNumber`. Name limits count Unicode
scalar values. Each supplied name must be nonempty and valid for its X.509 name
type. Usage mode is `GENERAL_PURPOSE`. Revocation configuration can omit CRL and
OCSP settings or explicitly disable them.

Subordinate authorities, other signing algorithms, custom CA CSR configuration,
other templates, short-lived usage, tags, sharing and resource
policies, revocation, CRL/OCSP delivery, audit reports and explicit HSM storage
standards are not supported. Unsupported options are rejected before issuing or
activating a certificate. `END_DATE` validity is not supported.

This service does not provision certificate pools, renew certificates, integrate
with an external certificate broker, or change ACM's `RequestCertificate` private
certificate behavior. The AWS Private CA API is exposed through JSON 1.1;
Smithy RPC v2 CBOR is not supported.
