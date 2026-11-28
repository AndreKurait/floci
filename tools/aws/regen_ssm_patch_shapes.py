#!/usr/bin/env python3
"""Vendor SSM patch-baseline request constraints from botocore's Apache-2.0 model."""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/ssm-patch-request-shapes.json"
OPERATIONS = (
    "CreatePatchBaseline",
    "GetPatchBaseline",
    "UpdatePatchBaseline",
    "DeletePatchBaseline",
    "DescribePatchBaselines",
    "GetDefaultPatchBaseline",
    "RegisterDefaultPatchBaseline",
    "RegisterPatchBaselineForPatchGroup",
    "DeregisterPatchBaselineForPatchGroup",
    "GetPatchBaselineForPatchGroup",
    "DescribePatchGroups",
    "AddTagsToResource",
    "ListTagsForResource",
    "RemoveTagsFromResource",
)


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=Path("ssm/2014-11-06"),
                               output=OUTPUT, description=__doc__, operations=OPERATIONS)


if __name__ == "__main__":
    sys.exit(main())
