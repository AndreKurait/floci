#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/sesv2-request-shapes.json from botocore's SES v2 model.

SES v2 rejects a request body member of the wrong JSON type with a SerializationException before
it validates anything. The Java side (`SesV2RequestShapes`) needs each operation's body shapes to
do the same, so this script keeps only what that check reads: the input shape of every
operation, reduced to the members carried in the JSON body, and for every shape reachable from
one, its type and the shapes of its members, list elements or map values.

Members bound to the URI, the query string or a header are left out: they never reach the body,
and an operation whose binding moved (ListEmailIdentities went from a GET with a query string
to a POST with a body) keeps its old route served by hand.

The model is botocore's `sesv2/2019-09-27/service-2.json` (Apache-2.0,
https://github.com/boto/botocore). It is located, in order, at `--botocore-data`, the
`local/aws/botocore` checkout, then the installed `botocore` package, which `requirements.txt`
pins so CI regenerates the same bytes.

Run from anywhere in the repo:
    python3 tools/aws/regen_sesv2_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_sesv2_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from regen_dynamodb_shapes import load_model, render
from regen_partitions import resolve_botocore_data, strip_source

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/sesv2-request-shapes.json"
MODEL = Path("sesv2/2019-09-27/service-2.json")


def build(model: dict, provenance: str) -> dict:
    shapes = model["shapes"]
    kept: dict[str, dict] = {}

    def keep(name: str, body_only: bool = False) -> None:
        if name in kept:
            return
        shape = shapes[name]
        kind = shape["type"]
        entry: dict = {"type": kind}
        kept[name] = entry
        if kind == "structure":
            members = {member: spec for member, spec in shape.get("members", {}).items()
                       if not (body_only and "location" in spec)}
            entry["members"] = {member: spec["shape"] for member, spec in members.items()}
            for spec in members.values():
                keep(spec["shape"])
        elif kind == "list":
            entry["member"] = shape["member"]["shape"]
            keep(shape["member"]["shape"])
        elif kind == "map":
            entry["value"] = shape["value"]["shape"]
            keep(shape["value"]["shape"])

    operations = {}
    for name, operation in sorted(model["operations"].items()):
        if "input" in operation:
            operations[name] = operation["input"]["shape"]
            keep(operation["input"]["shape"], body_only=True)

    return {
        "_source": {
            "generator": "tools/aws/regen_sesv2_shapes.py",
            "botocore": provenance,
        },
        "operations": operations,
        "shapes": dict(sorted(kept.items())),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    data, provenance = resolve_botocore_data(args.botocore_data)
    fresh = render(build(load_model(data, MODEL), provenance))

    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if strip_source(current) != strip_source(fresh):
            print(f"{args.output} is stale (generated from {provenance}).", file=sys.stderr)
            return 1
        return 0

    args.output.write_text(fresh, encoding="utf-8")
    print(f"wrote {args.output.relative_to(REPO_ROOT)} from {provenance}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
