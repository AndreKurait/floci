"""Temporary fork-only diagnosis; run the production test without changing its assertions or timeout."""
from pathlib import Path
import datetime
import json
import os
import signal
import subprocess
import time

out = Path("diagnostics")
out.mkdir(exist_ok=True)
downloads = Path(os.environ["RUNNER_TEMP"]) / "pig-downloads"
downloads.mkdir(exist_ok=True)
env = dict(os.environ)
env["JAVA_TOOL_OPTIONS"] = env.get("JAVA_TOOL_OPTIONS", "") + f" -Djava.io.tmpdir={downloads}"
command = ["./mvnw", "-B", "test", "-Dtest=EmrPigStepsNightlyIntegrationTest",
           "-Dnightly.checks.enabled=true", "-DfailIfNoTests=true",
           "-Dfloci.docker.resource-namespace=pig-diagnostic-" + os.environ["GITHUB_RUN_ID"]]
started = time.monotonic()
process = subprocess.Popen(command, env=env, start_new_session=True)
try:
    while process.poll() is None:
        try:
            process.wait(timeout=30)
            break
        except subprocess.TimeoutExpired:
            pass
        stamp = int(time.monotonic() - started)
        try:
            files = []
            for file in downloads.glob("floci-pig-*.tar.gz"):
                try:
                    files.append({"name": file.name, "bytes": file.stat().st_size})
                except FileNotFoundError:
                    pass  # The downloader removes its archive when preparation finishes.
            (out / f"downloads-{stamp}.json").write_text(json.dumps({
                "utc": datetime.datetime.now(datetime.timezone.utc).isoformat(), "files": files}))
            listing = subprocess.run(["jcmd", "-l"], capture_output=True, text=True, timeout=10)
            for line in listing.stdout.splitlines():
                if "surefirebooter" not in line:
                    continue
                pid = line.split()[0]
                if not pid.isdigit():
                    continue
                dump = subprocess.run(["jcmd", pid, "Thread.print", "-l"],
                                      capture_output=True, text=True, timeout=10)
                (out / f"threads-{stamp}-{pid}.txt").write_text(dump.stdout + dump.stderr)
            containers = subprocess.run(["docker", "ps", "-a", "--format", "{{.Names}} {{.Status}}"],
                                        capture_output=True, text=True, timeout=10)
            (out / f"containers-{stamp}.txt").write_text(containers.stdout)
        except (OSError, subprocess.SubprocessError) as error:
            (out / f"diagnostic-error-{stamp}.txt").write_text(type(error).__name__)
        if time.monotonic() - started > 900:
            raise TimeoutError("Diagnostic test process exceeded fifteen minutes")
    raise SystemExit(process.returncode)
finally:
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
