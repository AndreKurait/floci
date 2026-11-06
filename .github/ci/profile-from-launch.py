#!/usr/bin/env python3
"""Record each inherited JVM from launch; retain recordings privately."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--output", required=True, type=Path)
parser.add_argument("command", nargs=argparse.REMAINDER)
args = parser.parse_args()
command = args.command[1:] if args.command[:1] == ["--"] else args.command
if not command:
    parser.error("a command is required after --")
output = args.output.resolve()
if any(character.isspace() or character in ",\"'" for character in str(output)):
    parser.error("the output path must not contain whitespace, commas or quotes")
os.umask(0o077)
output.mkdir(mode=0o700, parents=True, exist_ok=True)
if output.stat().st_uid != os.getuid():
    parser.error("the output directory must belong to the current user")
if list(output.glob("*.jfr")):
    parser.error("use a fresh output directory for each run")
output.chmod(0o700)
temporary = output / "tmp"
temporary.mkdir(mode=0o700)
environment = os.environ.copy()
for name in list(environment):
    if name.startswith(("AWS_", "FLOCI_", "QUARKUS_")) or name in (
        "OPENAI_API_KEY", "GH_TOKEN", "GITHUB_TOKEN", "ANTHROPIC_API_KEY",
        "CODEX_API_KEY", "JAVA_TOOL_OPTIONS", "MAVEN_OPTS"
    ):
        environment.pop(name)
environment["JAVA_TOOL_OPTIONS"] = (
    "-XX:StartFlightRecording=settings=profile,disk=true,dumponexit=true,"
    f"maxsize=128m,filename={output}/jvm-%p-%t.jfr"
    f" -Djava.io.tmpdir={temporary} -Dvertx.cacheDirBase={temporary}/vertx"
)
started = time.monotonic()
result = subprocess.run(command, env=environment, check=False)
elapsed = time.monotonic() - started
recordings = []
for recording in sorted(output.glob("*.jfr")):
    recording.chmod(0o600)
    recordings.append({"file": recording.name, "bytes": recording.stat().st_size})
(output / "result.json").write_text(json.dumps({
    "exitCode": result.returncode,
    "elapsedSeconds": round(elapsed, 3),
    "recordings": recordings,
    "limits": "Launch-time sampling; neither causal wall-time attribution nor an optimization result."
}, indent=2) + "\n")
raise SystemExit(result.returncode)
