# IAM-enabled compatibility cases

These cases require Floci with `floci.services.iam.enforcement-enabled=true` and
administrative test credentials. They are separate from the default `tests/`
suite, which runs with IAM enforcement disabled.

From `compatibility-tests/sdk-test-python`, run:

```sh
FLOCI_ENDPOINT=http://localhost:4566 pytest -q iam/test_dynamodb_select_defaults.py
```

Both Python SDK compatibility jobs also run this directory against a fresh Floci
instance with IAM enforcement and signature validation enabled. They preserve the
default suite's log and export a separate `iam-junit.xml` report.

The DynamoDB selection cases create disposable tables and roles, use scoped STS
sessions, and delete their resources in reverse dependency order. They verify
Query/Scan table and index defaults and explicit projection policies.

`test_appconfig_permissions.py` uses actual IAM user keys in commercial and China
regions. It checks implicit denial, scoped grants, explicit denial, name-to-ID
resource resolution, configuration-token authorization without consuming a denied
token, and `iam:PassRole` with the AppConfig service condition.

`test_sns_lambda_permissions.py` runs a Python 3.12 Lambda handler with both an
unqualified subscription and an alias. It observes actual handler logs, revokes
and restores the topic's permission, and verifies that an SQS sibling continues
receiving messages during denial. It requires Docker for the Lambda runtime.

`test_stepfunctions_apigateway_resource_policy.py` checks seven IAM_ROLE task
resource-policy combinations through a real Step Functions execution and API
Gateway MOCK integration. It runs here because the default Python suite disables
IAM enforcement. The signed IAM job checks both allowed and denied executions.
