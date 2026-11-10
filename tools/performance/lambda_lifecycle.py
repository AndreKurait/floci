"""Measure a local Floci Lambda lifecycle with boto3 and cached Python runtime images."""
import argparse
import io
import json
import math
import statistics
import time
import uuid
import zipfile

import boto3
from botocore.config import Config

parser = argparse.ArgumentParser()
parser.add_argument("--endpoint", default="http://localhost:4566")
parser.add_argument("--samples", type=int, default=5)
parser.add_argument("--warm-invocations", type=int, default=20)
args = parser.parse_args()
if args.samples < 1:
    parser.error("--samples must be at least 1")
if args.warm_invocations < 0:
    parser.error("--warm-invocations must not be negative")
client = boto3.client(
    "lambda", endpoint_url=args.endpoint, region_name="us-east-1",
    aws_access_key_id="test", aws_secret_access_key="test",
    config=Config(read_timeout=180, connect_timeout=5,
                  retries={"total_max_attempts": 1}, proxies={}),
)
archive = io.BytesIO()
with zipfile.ZipFile(archive, "w") as output:
    output.writestr("handler.py", "def handler(event, context):\n    return {'echo': event['value']}\n")
observations = {"create_to_first_result_ms": [], "warm_invoke_ms": [], "delete_ms": []}
for sample in range(args.samples):
    name = "lambda-lifecycle-probe-" + uuid.uuid4().hex[:12]
    created = False
    try:
        start = time.perf_counter()
        client.create_function(
            FunctionName=name, Runtime="python3.12",
            Role="arn:aws:iam::000000000000:role/lambda-role",
            Handler="handler.handler", Timeout=30, MemorySize=128,
            Code={"ZipFile": archive.getvalue()},
        )
        created = True
        for invocation in range(args.warm_invocations + 1):
            invocation_start = time.perf_counter()
            value = f"{sample}-{invocation}"
            response = client.invoke(FunctionName=name, Payload=json.dumps({"value": value}).encode())
            result = json.loads(response["Payload"].read())
            assert "FunctionError" not in response, result
            assert result == {"echo": value}, result
            elapsed = time.perf_counter()
            if invocation == 0:
                observations["create_to_first_result_ms"].append((elapsed - start) * 1000)
            else:
                observations["warm_invoke_ms"].append((elapsed - invocation_start) * 1000)
    finally:
        if created:
            start = time.perf_counter()
            client.delete_function(FunctionName=name)
            observations["delete_ms"].append((time.perf_counter() - start) * 1000)
summary = {}
for metric, values in observations.items():
    summary[metric] = {"n": len(values)}
    if values:  # --warm-invocations 0 records no warm invocations
        summary[metric].update(median=statistics.median(values),
                               p95=sorted(values)[math.ceil(len(values) * .95) - 1],
                               min=min(values), max=max(values))
    summary[metric]["samples"] = values
print(json.dumps(summary, indent=2))
