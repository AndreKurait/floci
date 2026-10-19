#!/usr/bin/env python3
"""Vendor Resource Groups request constraints from botocore's Apache-2.0 service model."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from request_shapes import REPO_ROOT, load_model

OUTPUT = REPO_ROOT / "src/main/resources/aws/resource-groups-request-shapes.json"
OPERATIONS = ("CreateGroup", "GetGroup", "DeleteGroup", "GroupResources", "ListGroupResources")


def build(model: dict, provenance: str) -> dict:
    kept = {}

    def keep(name: str) -> None:
        if name in kept:
            return
        shape = model["shapes"][name]
        entry = {k: shape[k] for k in ("type", "required", "min", "max", "pattern", "enum") if k in shape}
        kept[name] = entry
        if shape["type"] == "structure":
            entry["members"] = {k: v["shape"] for k, v in shape.get("members", {}).items()}
            for child in entry["members"].values():
                keep(child)
        elif shape["type"] == "list":
            entry["member"] = shape["member"]["shape"]
            keep(entry["member"])
        elif shape["type"] == "map":
            for side in ("key", "value"):
                entry[side] = shape[side]["shape"]
                keep(entry[side])

    operations = {name: model["operations"][name]["input"]["shape"] for name in OPERATIONS}
    for name in operations.values():
        keep(name)
    return {"_source": {"generator": "tools/aws/regen_resource_groups_shapes.py", "botocore": provenance},
            "operations": operations, "shapes": dict(sorted(kept.items()))}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--botocore-data", type=Path)
    args = parser.parse_args()
    data, provenance = resolve_botocore_data(args.botocore_data)
    fresh = json.dumps(build(load_model(data, Path("resource-groups/2017-11-27")), provenance), indent=2) + "\n"
    if args.check:
        return int(not OUTPUT.exists() or strip_source(OUTPUT.read_text()) != strip_source(fresh))
    OUTPUT.write_text(fresh)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
