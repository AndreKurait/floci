#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/rpc-v2-cbor-timestamps.json from botocore's models.

Smithy RPC v2 CBOR carries a timestamp as CBOR tag 1 around its epoch seconds. A JSON 1.1 service whose
Smithy RPC v2 CBOR calls Floci answers with the same JSON 1.1 handler (a catalog descriptor that
declares both protocols; see `AwsJsonCborController`) returns its timestamps as the epoch-second
numbers JSON 1.1 uses, so the CBOR response tags each member its model declares as a timestamp. This
file names those members, for each such service, by the service name the RPC v2 path carries (the
JSON protocol's `targetPrefix`).

Which services there are is Floci's choice and stays in SERVICES below: each is a JSON 1.1 service
Floci serves whose current botocore model lists both `smithy-rpc-v2-cbor` and `json`. The member names
are read from the models, which declare the same shapes whatever protocols they list.

The models are botocore's `<model>/<apiVersion>/service-2.json` (Apache-2.0,
https://github.com/boto/botocore), the latest API version of each, located as
`regen_query_services.py` locates them.

Run from anywhere in the repo:
    python3 tools/aws/regen_rpcv2_cbor_timestamps.py            # rewrite the vendored file in place
    python3 tools/aws/regen_rpcv2_cbor_timestamps.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from regen_query_services import latest_model_dir, load_model

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/rpc-v2-cbor-timestamps.json"

# The RPC v2 service name (the JSON targetPrefix) and the botocore model of each JSON 1.1 service Floci answers
# over Smithy RPC v2 CBOR through its JSON 1.1 handler.
SERVICES: dict[str, str] = {
    "ACMPrivateCA": "acm-pca",
    "AWS242ServiceCatalogService": "servicecatalog",
    "AWSShineFrontendService_20170701": "translate",
    "AmazonMemoryDB": "memorydb",
    "BaldrApiService": "cloudhsmv2",
    "CodeBuild_20161006": "codebuild",
    "CodeDeploy_20141006": "codedeploy",
    "CodePipeline_20150709": "codepipeline",
    "Comprehend_20171127": "comprehend",
    "FmrsService": "datasync",
    "NetworkFirewall_20201112": "network-firewall",
    "Route53AutoNaming_v20170314": "servicediscovery",
    "Textract": "textract",
    "Transcribe": "transcribe",
}


def timestamp_members(model: dict) -> list[str]:
    """Every member name, in any structure of the model, whose target shape is a timestamp."""
    shapes = model.get("shapes", {})
    names = set()
    for shape in shapes.values():
        for name, member in shape.get("members", {}).items():
            if shapes.get(member.get("shape"), {}).get("type") == "timestamp":
                names.add(name)
    return sorted(names)


def build(data: Path, provenance: str) -> dict:
    services = {}
    for prefix, name in sorted(SERVICES.items()):
        model = load_model(latest_model_dir(data, name))
        target_prefix = model.get("metadata", {}).get("targetPrefix")
        if target_prefix != prefix:
            raise SystemExit(f"error: {name}'s targetPrefix is {target_prefix}, not {prefix}")
        services[prefix] = timestamp_members(model)
    return {
        "_source": {
            "generator": "tools/aws/regen_rpcv2_cbor_timestamps.py",
            "botocore": provenance,
        },
        "services": services,
    }


def render(doc: dict) -> str:
    return json.dumps(doc, indent=2, sort_keys=False) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    data, provenance = resolve_botocore_data(args.botocore_data)
    fresh = render(build(data, provenance))

    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if strip_source(current) != strip_source(fresh):
            print(f"{args.output} is stale (generated from {provenance}).", file=sys.stderr)
            return 1
        return 0

    args.output.write_text(fresh, encoding="utf-8")
    shown = args.output.relative_to(REPO_ROOT) if args.output.is_relative_to(REPO_ROOT) else args.output
    print(f"wrote {shown} from {provenance}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
