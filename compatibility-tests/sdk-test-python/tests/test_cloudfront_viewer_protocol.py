"""Apply SDK-configured viewer policies to real HTTP requests."""

from contextlib import ExitStack
import json
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener

import boto3


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def test_sdk_viewer_policy_updates_change_delivery(
        aws_config, client_config, s3_client, unique_name, endpoint_url):
    key = "repository/metadata.txt"
    content = b"repository metadata"
    with ExitStack() as cleanup:
        cloudfront = boto3.client("cloudfront", config=client_config, **aws_config)
        cleanup.callback(cloudfront.close)
        bucket_args = {"Bucket": unique_name}
        region = aws_config["region_name"]
        if region != "us-east-1":
            bucket_args["CreateBucketConfiguration"] = {"LocationConstraint": region}
        s3_client.create_bucket(**bucket_args)
        cleanup.callback(s3_client.delete_bucket, Bucket=unique_name)
        s3_client.delete_public_access_block(Bucket=unique_name)
        s3_client.put_bucket_policy(Bucket=unique_name, Policy=json.dumps({
            "Version": "2012-10-17", "Statement": [{
                "Effect": "Allow", "Principal": "*", "Action": "s3:GetObject",
                "Resource": f"arn:aws:s3:::{unique_name}/*",
            }],
        }))
        s3_client.put_object(Bucket=unique_name, Key=key, Body=content)
        cleanup.callback(s3_client.delete_object, Bucket=unique_name, Key=key)
        config = {
            "CallerReference": unique_name, "Comment": "Viewer protocol policy",
            "Enabled": True,
            "Origins": {"Quantity": 1, "Items": [{
                "Id": "repository", "DomainName": f"{unique_name}.s3.{region}.amazonaws.com",
                "S3OriginConfig": {"OriginAccessIdentity": ""},
            }]},
            "DefaultCacheBehavior": {
                "TargetOriginId": "repository", "ViewerProtocolPolicy": "allow-all",
                "TrustedSigners": {"Enabled": False, "Quantity": 0},
                "ForwardedValues": {"QueryString": False, "Cookies": {"Forward": "none"}},
                "MinTTL": 0,
                "AllowedMethods": {
                    "Quantity": 7, "Items": ["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
                },
            },
        }
        created = cloudfront.create_distribution(DistributionConfig=config)
        distribution = created["Distribution"]

        def delete_distribution():
            live = cloudfront.get_distribution_config(Id=distribution["Id"])
            updated = cloudfront.update_distribution(
                Id=distribution["Id"], IfMatch=live["ETag"],
                DistributionConfig={**live["DistributionConfig"], "Enabled": False})
            cloudfront.delete_distribution(Id=distribution["Id"], IfMatch=updated["ETag"])

        cleanup.callback(delete_distribution)
        opener = build_opener(NoRedirect)
        target = "/" + key + "?key=a%2Fb&space=one+two"
        for policy, method, status in [
            ("allow-all", "GET", 200), ("https-only", "GET", 403),
            ("redirect-to-https", "GET", 301), ("redirect-to-https", "POST", 307),
        ]:
            live = cloudfront.get_distribution_config(Id=distribution["Id"])
            current = live["DistributionConfig"]
            current["DefaultCacheBehavior"]["ViewerProtocolPolicy"] = policy
            cloudfront.update_distribution(
                Id=distribution["Id"], IfMatch=live["ETag"], DistributionConfig=current)
            observed = cloudfront.get_distribution_config(Id=distribution["Id"])
            assert observed["DistributionConfig"]["DefaultCacheBehavior"]["ViewerProtocolPolicy"] == policy
            request = Request(endpoint_url + target, method=method,
                              headers={"Host": distribution["DomainName"]},
                              data=b"replay" if method == "POST" else None)
            try:
                response = opener.open(request, timeout=10)
            except HTTPError as error:
                response = error
            with response:
                body = response.read()
                assert response.status == status
                if status == 200:
                    assert body == content
                else:
                    assert body != content
                if status in (301, 307):
                    assert response.headers["Location"] == "https://" + distribution["DomainName"] + target
