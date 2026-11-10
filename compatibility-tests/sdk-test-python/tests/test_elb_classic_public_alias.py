"""Classic endpoint routing for public SDK-created resources, without fabricated health."""

import http.client
import time
import uuid
from contextlib import ExitStack, closing
from urllib.parse import urlsplit

import boto3
from botocore.exceptions import ClientError
import pytest


@pytest.fixture
def member_config(aws_config, client_config):
    """Use an issued member-account session even when IAM and signature checks are enabled."""
    with ExitStack() as cleanup:
        organizations = cleanup.enter_context(closing(
            boto3.client("organizations", **aws_config, config=client_config)))
        try:
            organizations.create_organization(FeatureSet="ALL")
        except ClientError as error:
            if error.response["Error"]["Code"] != "AlreadyInOrganizationException":
                raise
        else:
            cleanup.callback(organizations.delete_organization)

        account_name = "classic-alias-" + uuid.uuid4().hex[:10]
        status = organizations.create_account(
            Email=account_name + "@example.test", AccountName=account_name,
        )["CreateAccountStatus"]
        deadline = time.monotonic() + 30
        while status["State"] == "IN_PROGRESS":
            assert time.monotonic() < deadline, "Member account creation did not finish"
            time.sleep(0.25)
            status = organizations.describe_create_account_status(
                CreateAccountRequestId=status["Id"],
            )["CreateAccountStatus"]
        assert status["State"] == "SUCCEEDED", status
        account_id = status["AccountId"]
        cleanup.callback(organizations.remove_account_from_organization, AccountId=account_id)
        account = organizations.describe_account(AccountId=account_id)["Account"]
        partition = account["Arn"].split(":")[1]
        sts = cleanup.enter_context(closing(boto3.client("sts", **aws_config, config=client_config)))
        assert sts.get_caller_identity()["Account"] != account_id
        credentials = sts.assume_role(
            RoleArn=f"arn:{partition}:iam::{account_id}:role/OrganizationAccountAccessRole",
            RoleSessionName=account_name,
        )["Credentials"]
        config = {
            **aws_config,
            "aws_access_key_id": credentials["AccessKeyId"],
            "aws_secret_access_key": credentials["SecretAccessKey"],
            "aws_session_token": credentials["SessionToken"],
        }
        member_sts = cleanup.enter_context(closing(boto3.client("sts", **config, config=client_config)))
        assert member_sts.get_caller_identity()["Account"] == account_id
        yield config


def test_public_alias_uses_owned_classic_lb_and_revokes_deleted_resources(
        aws_config, client_config, member_config):
    config = member_config
    name = "public-classic-" + uuid.uuid4().hex[:10]
    zone_name = name + ".example.test"
    host = "search." + zone_name

    def status(customer):
        endpoint = urlsplit(config["endpoint_url"])
        connection_type = http.client.HTTPSConnection if endpoint.scheme == "https" else http.client.HTTPConnection
        connection = connection_type(endpoint.hostname, endpoint.port, timeout=10)
        try:
            connection.request("GET", "/", headers={"Host": customer})
            response = connection.getresponse()
            response.read()
            return response.status
        finally:
            connection.close()

    with ExitStack() as cleanup:
        elb = cleanup.enter_context(closing(boto3.client("elb", **config, config=client_config)))
        foreign = cleanup.enter_context(closing(boto3.client("elb", **aws_config, config=client_config)))
        route53 = cleanup.enter_context(closing(boto3.client("route53", **config, config=client_config)))
        unowned_status = status(host)
        assert unowned_status != 503
        elb.create_load_balancer(
            LoadBalancerName=name,
            AvailabilityZones=[config["region_name"] + "a"],
            Listeners=[{"Protocol": "HTTP", "LoadBalancerPort": 80,
                        "InstanceProtocol": "HTTP", "InstancePort": 8080}],
        )
        cleanup.callback(elb.delete_load_balancer, LoadBalancerName=name)
        description = elb.describe_load_balancers(LoadBalancerNames=[name])["LoadBalancerDescriptions"][0]
        unowned_dns_status = status("unowned-" + description["DNSName"])
        assert unowned_dns_status != 503
        zone_id = route53.create_hosted_zone(Name=zone_name, CallerReference=uuid.uuid4().hex)["HostedZone"]["Id"]
        cleanup.callback(route53.delete_hosted_zone, Id=zone_id)
        record = {
            "Name": host, "Type": "A", "SetIdentifier": "only", "Weight": 1,
            "AliasTarget": {
                "DNSName": description["DNSName"],
                "HostedZoneId": description["CanonicalHostedZoneNameID"],
                "EvaluateTargetHealth": False,
            },
        }
        route53.change_resource_record_sets(
            HostedZoneId=zone_id, ChangeBatch={"Changes": [{"Action": "CREATE", "ResourceRecordSet": record}]}
        )
        cleanup.callback(
            route53.change_resource_record_sets, HostedZoneId=zone_id,
            ChangeBatch={"Changes": [{"Action": "DELETE", "ResourceRecordSet": record}]},
        )
        with pytest.raises(ClientError) as missing:
            foreign.describe_load_balancers(LoadBalancerNames=[name])
        assert missing.value.response["Error"]["Code"] == "LoadBalancerNotFound"
        # The real load balancer has no registered instances, so it must report 503, never S3 or a canned success.
        assert status(description["DNSName"]) == 503
        assert status(host) == 503
        # Unowned names retain the shared edge's existing dispatch, rather than being claimed by Classic.
        assert status("unknown." + zone_name) == unowned_status
        elb.delete_load_balancer(LoadBalancerName=name)
        assert status(host) == unowned_status
        assert status(description["DNSName"]) == unowned_dns_status
