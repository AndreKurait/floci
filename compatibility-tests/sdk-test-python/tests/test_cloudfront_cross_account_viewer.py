"""CloudFront public delivery is independent of its management account."""

from contextlib import ExitStack
import json
from urllib.request import Request, urlopen

import boto3
from botocore.exceptions import ClientError
import pytest


def test_public_viewer_uses_distribution_owner_without_exposing_management(
        aws_config, client_config, s3_client, unique_name, endpoint_url):
    owner_account = "111122223333"
    other_account = "444455556666"
    key = "2/core/repository/repodata/repomd.xml"
    content = b"repository metadata"
    bucket = unique_name
    with ExitStack() as cleanup:
        clients = {}
        for account in (owner_account, other_account):
            client = boto3.client("cloudfront", config=client_config, **{
                **aws_config, "region_name": "us-east-1",
                "aws_access_key_id": account, "aws_secret_access_key": "test",
            })
            cleanup.callback(client.close)
            clients[account] = client
        owner, other = clients[owner_account], clients[other_account]
        region = aws_config["region_name"]
        bucket_args = {"Bucket": bucket}
        if region != "us-east-1":
            bucket_args["CreateBucketConfiguration"] = {"LocationConstraint": region}
        s3_client.create_bucket(**bucket_args)
        cleanup.callback(s3_client.delete_bucket, Bucket=bucket)
        s3_client.delete_public_access_block(Bucket=bucket)
        s3_client.put_bucket_policy(Bucket=bucket, Policy=json.dumps({
            "Version": "2012-10-17",
            "Statement": [{
                "Effect": "Allow", "Principal": "*", "Action": "s3:GetObject",
                "Resource": f"arn:aws:s3:::{bucket}/*",
            }],
        }))
        s3_client.put_object(Bucket=bucket, Key=key, Body=content)
        cleanup.callback(s3_client.delete_object, Bucket=bucket, Key=key)
        policy = owner.create_response_headers_policy(ResponseHeadersPolicyConfig={
            "Name": unique_name,
            "CustomHeadersConfig": {
                "Quantity": 1,
                "Items": [{"Header": "X-Distribution-Owner", "Value": owner_account, "Override": True}],
            },
        })
        policy_id = policy["ResponseHeadersPolicy"]["Id"]
        cleanup.callback(owner.delete_response_headers_policy, Id=policy_id, IfMatch=policy["ETag"])
        config = {
            "CallerReference": unique_name, "Comment": "Public cross-account viewer",
            "Enabled": True,
            "Origins": {"Quantity": 1, "Items": [{
                "Id": "repository", "DomainName": f"{bucket}.s3.{region}.amazonaws.com",
                "S3OriginConfig": {"OriginAccessIdentity": ""},
            }]},
            "DefaultCacheBehavior": {
                "TargetOriginId": "repository", "ViewerProtocolPolicy": "allow-all",
                "TrustedSigners": {"Enabled": False, "Quantity": 0},
                "ForwardedValues": {"QueryString": False, "Cookies": {"Forward": "none"}},
                "MinTTL": 0, "ResponseHeadersPolicyId": policy_id,
            },
        }
        created = owner.create_distribution(DistributionConfig=config)
        distribution = created["Distribution"]

        def delete_distribution():
            live = owner.get_distribution_config(Id=distribution["Id"])
            disabled = {**live["DistributionConfig"], "Enabled": False}
            updated = owner.update_distribution(
                Id=distribution["Id"], IfMatch=live["ETag"], DistributionConfig=disabled)
            owner.delete_distribution(Id=distribution["Id"], IfMatch=updated["ETag"])

        cleanup.callback(delete_distribution)
        assert distribution["ARN"] == f"arn:aws:cloudfront::{owner_account}:distribution/{distribution['Id']}"
        for query in ("", "?instance_id=i-local&region=eu-west-1"):
            request = Request(
                f"{endpoint_url}/{key}{query}", headers={"Host": distribution["DomainName"]})
            with urlopen(request, timeout=10) as response:
                assert response.status == 200
                assert response.read() == content
                assert response.headers["X-Distribution-Owner"] == owner_account
        with pytest.raises(ClientError) as denied:
            other.get_distribution(Id=distribution["Id"])
        assert denied.value.response["Error"]["Code"] == "NoSuchDistribution"
        assert not any(
            item["Id"] == distribution["Id"]
            for item in other.list_distributions()["DistributionList"].get("Items", []))
        assert owner.get_distribution(Id=distribution["Id"])["Distribution"]["Id"] == distribution["Id"]
