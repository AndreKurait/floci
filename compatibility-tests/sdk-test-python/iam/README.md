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

`test_sns_lambda_permissions.py` runs a Python 3.12 Lambda handler with both an
unqualified subscription and an alias. It observes actual handler logs, revokes
and restores the topic's permission, and verifies that an SQS sibling continues
receiving messages during denial. It requires Docker for the Lambda runtime.

`test_appconfig_s3_permissions.py` checks retrieval-role permission and trust
failures and selected-version tag conditions. These cases require IAM enforcement;
S3 source deployment, missing versions, rollback and CloudFormation cases remain
in the default suite. Both suites share owned fixtures and cleanup.

`test_stepfunctions_apigateway_resource_policy.py` checks seven role and public
resource-policy decisions through real Step Functions API Gateway tasks. Its fixtures
remove the owned state machines, APIs and roles after each case.
