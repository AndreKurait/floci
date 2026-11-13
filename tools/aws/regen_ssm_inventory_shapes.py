#!/usr/bin/env python3
"""Vendor SSM Inventory request constraints from botocore's Apache-2.0 service model."""
from pathlib import Path

from request_shapes import REPO_ROOT, main

if __name__ == "__main__":
    raise SystemExit(main(
        None,
        script=__file__,
        model=Path("ssm/2014-11-06"),
        output=REPO_ROOT / "src/main/resources/aws/ssm-inventory-request-shapes.json",
        description=__doc__,
        operations=("PutInventory", "ListInventoryEntries"),
    ))
