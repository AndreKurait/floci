"""Temporary fork-only diagnosis; run the production test without changing its assertions or timeout."""
from pathlib import Path
import datetime
import json
import os
import signal
import subprocess
import sys
import time

out = Path("diagnostics")
out.mkdir(exist_ok=True)
downloads = Path("/tmp")
env = dict(os.environ)
command = sys.argv[1:]
if not command:
    raise SystemExit("Pass the unchanged nightly test command")
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
                heap = subprocess.run(["jcmd", pid, "GC.heap_info"],
                                      capture_output=True, text=True, timeout=10)
                (out / f"heap-{stamp}-{pid}.txt").write_text(heap.stdout + heap.stderr)
            containers = subprocess.run(["docker", "ps", "-a", "--format", "{{.Names}} {{.Status}}"],
                                        capture_output=True, text=True, timeout=10)
            (out / f"containers-{stamp}.txt").write_text(containers.stdout)
            disk = subprocess.run(["df", "-B1", "/"], capture_output=True, text=True, timeout=10)
            (out / f"disk-{stamp}.txt").write_text(disk.stdout)
        except (OSError, subprocess.SubprocessError) as error:
            (out / f"diagnostic-error-{stamp}.txt").write_text(type(error).__name__)
        if time.monotonic() - started > 1800:
            raise TimeoutError("Diagnostic test process exceeded thirty minutes")
    raise SystemExit(process.returncode)
finally:
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
