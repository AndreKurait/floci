#!/usr/bin/env python3
"""SDK benchmark against an isolated Floci JVM. Requires boto3; never contacts AWS.

Reports mock-EC2 ASG completion and real local HTTP health-probe completion separately.
The application must run on this host, because the target addresses are loopback.
"""
import argparse
import contextlib
import datetime
import http.server
import json
import os
from pathlib import Path
import statistics
import threading
import time
import uuid

import boto3
from botocore.config import Config


def client(service, endpoint, account="111111111111"):
    return boto3.client(service, endpoint_url=endpoint, region_name="us-east-1",
                        aws_access_key_id=account, aws_secret_access_key="benchmark-only",
                        config=Config(retries={"max_attempts": 0}, connect_timeout=2, read_timeout=5))


def until(predicate, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(0.02)
    raise TimeoutError("Completion predicate was not satisfied")


def asg_sample(endpoint):
    name = "wake-bench-" + uuid.uuid4().hex[:12]
    instance_ids = []
    with contextlib.ExitStack() as cleanup:
        asg = cleanup.enter_context(contextlib.closing(client("autoscaling", endpoint)))
        ec2 = cleanup.enter_context(contextlib.closing(client("ec2", endpoint)))
        asg.create_launch_configuration(LaunchConfigurationName=name, ImageId="ami-amazonlinux2023",
                                        InstanceType="t3.micro")
        cleanup.callback(asg.delete_launch_configuration, LaunchConfigurationName=name)
        asg.create_auto_scaling_group(AutoScalingGroupName=name, LaunchConfigurationName=name,
                                      MinSize=0, MaxSize=1, DesiredCapacity=0, AvailabilityZones=["us-east-1a"])

        def await_termination():
            if instance_ids:
                until(lambda: all(i["State"]["Name"] == "terminated"
                                  for reservation in ec2.describe_instances(InstanceIds=instance_ids)["Reservations"]
                                  for i in reservation["Instances"]))

        cleanup.callback(await_termination)
        cleanup.callback(asg.delete_auto_scaling_group, AutoScalingGroupName=name, ForceDelete=True)
        start = time.monotonic()
        asg.set_desired_capacity(AutoScalingGroupName=name, DesiredCapacity=1)
        api_seconds = time.monotonic() - start

        def completed():
            group = asg.describe_auto_scaling_groups(AutoScalingGroupNames=[name])["AutoScalingGroups"][0]
            instances = group["Instances"]
            instance_ids[:] = [instance["InstanceId"] for instance in instances]
            return len(instances) == 1 and instances[0]["LifecycleState"] == "InService"

        until(completed)
        return {"api_seconds": api_seconds, "in_service_seconds": time.monotonic() - start}


@contextlib.contextmanager
def targets(count, delay):
    servers = []
    with contextlib.ExitStack() as cleanup:
        for index in range(count):
            class Handler(http.server.BaseHTTPRequestHandler):
                def do_GET(self):
                    time.sleep(delay)
                    self.send_response(200)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    with self.server.result_lock:
                        self.server.first_completed = self.server.first_completed or time.monotonic()
                        self.server.requests += 1

                def log_message(self, *_):
                    pass

            server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            cleanup.callback(server.server_close)
            server.daemon_threads = True
            server.result_lock = threading.Lock()
            server.first_completed = None
            server.requests = 0
            thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
            thread.start()
            servers.append(server)
            cleanup.callback(thread.join, 2)
            cleanup.callback(server.shutdown)
        yield servers


def health_sample(endpoint, count, delay):
    name = "health-bench-" + uuid.uuid4().hex[:12]
    with contextlib.closing(client("elbv2", endpoint)) as elb, targets(count, delay) as servers:
        group = elb.create_target_group(Name=name, Protocol="HTTP", Port=80, VpcId="vpc-benchmark",
                                        TargetType="ip", HealthCheckEnabled=True, HealthCheckIntervalSeconds=5,
                                        HealthCheckTimeoutSeconds=2, HealthyThresholdCount=2,
                                        UnhealthyThresholdCount=2)["TargetGroups"][0]["TargetGroupArn"]
        try:
            start = time.monotonic()
            elb.register_targets(TargetGroupArn=group,
                                 Targets=[{"Id": "127.0.0.1", "Port": server.server_port} for server in servers])
            api_seconds = time.monotonic() - start
            until(lambda: all(server.first_completed is not None for server in servers))
            first_round = max(server.first_completed for server in servers) - start
            def healthy():
                states = elb.describe_target_health(TargetGroupArn=group)["TargetHealthDescriptions"]
                return len(states) == count and all(s["TargetHealth"]["State"] == "healthy" for s in states)
            until(healthy, timeout=120)
            elapsed = time.monotonic() - start
            return {"api_seconds": api_seconds, "first_probe_round_seconds": first_round,
                    "all_targets_healthy_seconds": elapsed, "requests": sum(s.requests for s in servers),
                    "targets": count, "response_delay_seconds": delay, "healthy_threshold": 2,
                    "probe_interval_seconds": 5}
        finally:
            elb.delete_target_group(TargetGroupArn=group)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--targets", type=int, default=32)
    parser.add_argument("--delay", type=float, default=0.4)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    # Explicit local endpoint plus synthetic credentials prevents accidental AWS access.
    from urllib.parse import urlparse
    if urlparse(args.endpoint).hostname not in ("127.0.0.1", "localhost", "::1"):
        parser.error("This benchmark requires an isolated loopback endpoint")
    result = {"label": args.label, "revision": args.revision, "endpoint": args.endpoint,
              "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "scope": "warm JVM; mock EC2 ASG coordination; real loopback HTTP probes; not a fleet lifecycle",
              "host_load_start": os.getloadavg(), "asg": [], "health": []}
    # Warm protocol parsing and SDK transport without creating workloads.
    with contextlib.closing(client("autoscaling", args.endpoint)) as asg:
        asg.describe_auto_scaling_groups()
    with contextlib.closing(client("elbv2", args.endpoint)) as elb:
        elb.describe_target_groups()
    for index in range(args.runs):
        for kind, sample in [("asg", lambda: asg_sample(args.endpoint)),
                             ("health", lambda: health_sample(args.endpoint, args.targets, args.delay))]:
            measurement = sample()
            result[kind].append(measurement)
            args.output.write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps({"label": args.label, "run": index + 1, kind: measurement}), flush=True)
    result["medians"] = {kind: {key: statistics.median(row[key] for row in result[kind])
                                for key in result[kind][0] if key.endswith("seconds")}
                         for kind in ("asg", "health")}
    result["host_load_end"] = os.getloadavg()
    args.output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
