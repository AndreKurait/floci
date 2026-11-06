# IAM-enabled compatibility cases

These cases require Floci with `floci.services.iam.enforcement-enabled=true` and
administrative test credentials. They are separate from the default `tests/`
suite, which runs with IAM enforcement disabled.

From `compatibility-tests/sdk-test-python`, run:

```sh
FLOCI_ENDPOINT=http://localhost:4566 pytest -q iam/
```

Both Python SDK compatibility jobs also run this directory against a fresh Floci
instance with IAM enforcement and signature validation enabled. They preserve the
default suite's log and export a separate `iam-junit.xml` report.

The DynamoDB selection cases create disposable tables and roles, use scoped STS
sessions, and delete their resources in reverse dependency order. They verify
Query/Scan table and index defaults and explicit projection policies.

The AppConfig alarm cases refuse deployment when the monitor role lacks
`cloudwatch:DescribeAlarms` or does not trust AppConfig. They verify that refusal
leaves the prior configuration active and creates no deployment. Alarm lifecycle
cases remain in the default suite; both suites share owned fixtures and cleanup.

`test_appconfig_permissions.py` uses actual IAM user keys in commercial and China
regions. It checks implicit denial, scoped grants, explicit denial, name-to-ID
resource resolution, configuration-token authorization without consuming a denied
token, and `iam:PassRole` with the AppConfig service condition.
