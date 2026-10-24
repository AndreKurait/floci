"""Classic endpoint routing for public SDK-created resources, without fabricated health."""

import http.client
import uuid
from urllib.parse import urlsplit

import boto3
from botocore.exceptions import ClientError
import pytest


def test_public_alias_uses_owned_classic_lb_and_revokes_deleted_resources(aws_config, client_config):
    config = {**aws_config, "aws_access_key_id": "321321321321"}
    name = "public-classic-" + uuid.uuid4().hex[:10]
    zone_name = name + ".example.test"
    host = "search." + zone_name
    elb = boto3.client("elb", **config, config=client_config)
    foreign = boto3.client("elb", **{**config, "aws_access_key_id": "654654654654"}, config=client_config)
    route53 = boto3.client("route53", **config, config=client_config)
    zone_id = record = None

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

    try:
        unowned_status = status(host)
        assert unowned_status != 503
        elb.create_load_balancer(
            LoadBalancerName=name,
            AvailabilityZones=[config["region_name"] + "a"],
            Listeners=[{"Protocol": "HTTP", "LoadBalancerPort": 80,
                        "InstanceProtocol": "HTTP", "InstancePort": 8080}],
        )
        description = elb.describe_load_balancers(LoadBalancerNames=[name])["LoadBalancerDescriptions"][0]
        unowned_dns_status = status("unowned-" + description["DNSName"])
        assert unowned_dns_status != 503
        zone_id = route53.create_hosted_zone(Name=zone_name, CallerReference=uuid.uuid4().hex)["HostedZone"]["Id"]
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
    finally:
        elb.delete_load_balancer(LoadBalancerName=name)
        if record is not None:
            route53.change_resource_record_sets(
                HostedZoneId=zone_id, ChangeBatch={"Changes": [{"Action": "DELETE", "ResourceRecordSet": record}]}
            )
        if zone_id is not None:
            route53.delete_hosted_zone(Id=zone_id)
        elb.close()
        foreign.close()
        route53.close()
