#!/usr/bin/env python3
"""Vendor EC2/SSM SDK task shapes and wire names from pinned botocore models.

Only the listed operations are admitted by SdkComputeTaskInvoker. Both input and
output closures are retained so the Query adapter uses the SDK's field names,
collection names and scalar types rather than guessing from XML element names.
"""
import argparse
import json
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from request_shapes import load_model


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "src/main/resources/aws/stepfunctions-compute-shapes.json"
OPERATIONS = {
    "ec2": ("2016-11-15", ("RunInstances", "DescribeInstances", "TerminateInstances")),
    "ssm": ("2014-11-06", ("DescribeInstanceInformation", "SendCommand", "GetCommandInvocation",
                          "ListCommands", "ListCommandInvocations", "CancelCommand")),
}


def build(data, provenance):
    services = {}
    for service, (version, operations) in OPERATIONS.items():
        model = load_model(data, Path(service) / version)
        kept = {}

        def keep(name):
            if name in kept:
                return
            shape = model["shapes"][name]
            entry = {key: shape[key] for key in ("type", "required", "flattened", "locationName", "timestampFormat")
                     if key in shape}
            kept[name] = entry
            if shape["type"] == "structure":
                entry["members"] = {
                    key: {field: value for field, value in spec.items()
                          if field in ("shape", "locationName", "queryName", "flattened", "timestampFormat")}
                    for key, spec in shape.get("members", {}).items()
                }
                for spec in entry["members"].values():
                    keep(spec["shape"])
            elif shape["type"] in ("list", "map"):
                for side in ("member",) if shape["type"] == "list" else ("key", "value"):
                    entry[side] = {key: value for key, value in shape[side].items()
                                   if key in ("shape", "locationName", "flattened", "timestampFormat")}
                    keep(entry[side]["shape"])

        selected = {}
        for name in operations:
            operation = model["operations"][name]
            selected[name] = {side: operation[side]["shape"] for side in ("input", "output") if side in operation}
            for shape_name in selected[name].values():
                keep(shape_name)
        services[service] = {
            "apiVersion": version, "targetPrefix": model["metadata"].get("targetPrefix"),
            "operations": selected, "shapes": dict(sorted(kept.items())),
            "exceptions": {
                shape.get("error", {}).get("code", name):
                    name if name.endswith("Exception") else name + "Exception"
                for name, shape in sorted(model["shapes"].items()) if shape.get("exception")
            },
        }
    return {"_source": {"generator": "tools/aws/regen_stepfunctions_compute_shapes.py",
                         "botocore": provenance}, "services": services}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--botocore-data", type=Path)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    data, provenance = resolve_botocore_data(args.botocore_data)
    fresh = json.dumps(build(data, provenance), indent=2) + "\n"
    if args.check:
        if not args.output.is_file() or strip_source(args.output.read_text()) != strip_source(fresh):
            raise SystemExit("Step Functions compute task models are stale")
    else:
        args.output.write_text(fresh)


if __name__ == "__main__":
    main()
