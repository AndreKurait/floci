#!/usr/bin/env python3
"""Regenerate src/main/resources/eks/access-policies.json from EKS ListAccessPolicies.

AWS publishes the access policies EKS offers only through the EKS API, so the source is the AWS CLI:
`aws eks list-access-policies`, which follows every page. Each policy's ARN is
arn:aws:eks::aws:cluster-access-policy/<name>: no Region and the account `aws`. The file keeps the names, in the
order AWS lists them (name order), and Floci builds each ARN in the caller's partition.

The call is a read that works from any AWS account; it always asks us-east-1, so the caller's default region
cannot change the result. Nothing is hand-typed. Without AWS credentials (CI), `--check` only verifies the
vendored file parses and keeps its shape; a regeneration needs credentials.

Run from anywhere in the repo:
    python3 tools/aws/regen_eks_access_policies.py            # rewrite the vendored file in place
    python3 tools/aws/regen_eks_access_policies.py --check    # exit 1 when the vendored file is malformed
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REGION = "us-east-1"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/eks/access-policies.json"
ARN_PREFIX = "arn:aws:eks::aws:cluster-access-policy/"
NAME = re.compile(r"[A-Za-z0-9]+")


def names(response: dict) -> list[str]:
    """The policy names a ListAccessPolicies response lists, each checked against the ARN AWS gives it."""
    found = []
    for policy in response.get("accessPolicies", []):
        name, arn = policy.get("name"), policy.get("arn")
        if arn != f"{ARN_PREFIX}{name}":
            raise ValueError(f"policy {name}: ARN {arn} is not {ARN_PREFIX}{name}")
        found.append(name)
    return found


def validate(catalog: dict) -> list[str]:
    """Problems with the vendored catalog; empty when it is well-formed."""
    entries = catalog.get("accessPolicies") if isinstance(catalog, dict) else None
    if not isinstance(entries, list) or not entries:
        return ["top-level 'accessPolicies' list is missing or empty"]
    problems = []
    found = []
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"name"}:
            problems.append(f"unexpected entry {entry!r}")
        elif not isinstance(entry["name"], str) or not NAME.fullmatch(entry["name"]):
            problems.append(f"not a policy name: {entry['name']!r}")
        else:
            found.append(entry["name"])
    # Java pages the policies in name order, as AWS lists them.
    if found != sorted(set(found)):
        problems.append("names are not unique and in name order")
    return problems


def list_policies() -> dict:
    result = subprocess.run(
        ["aws", "eks", "list-access-policies", "--region", REGION, "--output", "json"],
        check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def render(catalog: dict) -> str:
    """One policy per line, so a new AWS policy is a one-line diff."""
    lines = ",\n".join(json.dumps(entry, separators=(",", ":")) for entry in catalog["accessPolicies"])
    return '{"accessPolicies":[\n' + lines + "\n]}\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="verify the vendored file instead of rewriting it")
    args = parser.parse_args()
    if args.check:
        try:
            problems = validate(json.loads(OUTPUT.read_text()))
        except (OSError, ValueError) as error:
            problems = [f"cannot be read: {error}"]
        for problem in problems:
            print(f"access-policies.json: {problem}", file=sys.stderr)
        return 1 if problems else 0
    try:
        catalog = {"accessPolicies": [{"name": name} for name in names(list_policies())]}
    except (subprocess.CalledProcessError, OSError, ValueError) as error:
        detail = (getattr(error, "stderr", None) or "").strip() or error
        print(f"regen_eks_access_policies: {detail}; the vendored file is unchanged", file=sys.stderr)
        return 1
    problems = validate(catalog)
    if problems:
        for problem in problems:
            print(f"generated catalog: {problem}", file=sys.stderr)
        return 1
    OUTPUT.write_text(render(catalog))
    return 0


if __name__ == "__main__":
    sys.exit(main())
