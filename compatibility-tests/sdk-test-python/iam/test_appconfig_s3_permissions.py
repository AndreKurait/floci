"""S3 retrieval-role permissions, trust, and version tags with IAM enforcement enabled."""
import json

import pytest
from botocore.exceptions import ClientError

from appconfig_s3_fixtures import _assert_rejected_deployment, _deploy, _read, s3_source_scope


@pytest.mark.parametrize("fault", ["permission", "trust"])
def test_s3_source_failed_fetch_preserves_active_configuration(s3_source_scope, fault):
    scope = s3_source_scope
    _deploy(scope, scope["version"])
    version = scope["s3"].put_object(
        Bucket=scope["bucket"], Key=scope["key"], Body=b'{"limit":20}',
        ContentType="application/json")["VersionId"]
    if fault == "permission":
        scope["iam"].put_role_policy(
            RoleName=scope["name"], PolicyName="source", PolicyDocument=json.dumps({
                "Version": "2012-10-17", "Statement": [
                    {"Effect": "Deny", "Action": "s3:*", "Resource": "*"}]}))
    else:
        scope["iam"].update_assume_role_policy(
            RoleName=scope["name"], PolicyDocument=json.dumps({
                "Version": "2012-10-17", "Statement": [
                    {"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"},
                     "Action": "sts:AssumeRole"}]}))
    _assert_rejected_deployment(scope, version)


def test_s3_source_checks_the_selected_versions_tags(s3_source_scope):
    scope = s3_source_scope
    _deploy(scope, scope["version"])
    version = scope["s3"].put_object(
        Bucket=scope["bucket"], Key=scope["key"], Body=b'{"limit":20}',
        ContentType="application/json", Tagging="approved=false")["VersionId"]
    scope["iam"].put_role_policy(
        RoleName=scope["name"], PolicyName="version-tag", PolicyDocument=json.dumps({
            "Version": "2012-10-17", "Statement": [{
                "Effect": "Deny", "Action": "s3:GetObjectVersion",
                "Resource": scope["bucket_arn"] + "/" + scope["key"],
                "Condition": {"StringEquals": {"s3:ExistingObjectTag/approved": "false"}}}]}))
    scope["cleanup"].callback(scope["iam"].delete_role_policy,
                               RoleName=scope["name"], PolicyName="version-tag")
    with pytest.raises(ClientError) as failure:
        _deploy(scope, version)
    assert failure.value.response["Error"]["Code"] == "BadRequestException"
    assert _read(scope) == scope["content"]
    scope["s3"].put_object_tagging(
        Bucket=scope["bucket"], Key=scope["key"], VersionId=version,
        Tagging={"TagSet": [{"Key": "approved", "Value": "true"}]})
    _deploy(scope, version)
    assert _read(scope) == b'{"limit":20}'
