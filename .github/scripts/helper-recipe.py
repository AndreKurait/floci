#!/usr/bin/env python3
"""Hash the same declared build context used by LocallyBuiltHelperImage."""
import hashlib
from pathlib import Path
import re
import sys


def recipe(dockerfile, destination=None):
    files = {"Dockerfile": dockerfile.read_bytes()}
    manifest = Path(str(dockerfile) + ".files")
    if manifest.exists():
        for line in manifest.read_text(encoding="utf-8").splitlines():
            name = line.strip()
            if not name:
                continue
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name) or name in files:
                raise ValueError("Invalid helper build-context filename: " + name)
            files[name] = (dockerfile.parent / name).read_bytes()
    if destination is not None:
        destination.mkdir()
        destination.chmod(0o755)
        for name, data in files.items():
            (destination / name).write_bytes(data)
            (destination / name).chmod(0o644)
    if len(files) == 1:
        return hashlib.sha256(files["Dockerfile"]).hexdigest()
    content = "".join(
        name + "\0" + hashlib.sha256(data).hexdigest() + "\n"
        for name, data in sorted(files.items())
    )
    return hashlib.sha256(content.encode()).hexdigest()


if __name__ == "__main__":
    print(recipe(Path(sys.argv[1]), Path(sys.argv[2]) if len(sys.argv) > 2 else None))
