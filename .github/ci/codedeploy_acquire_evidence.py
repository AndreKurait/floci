"""One-off hosted acquisition evidence; does not alter the stock-agent fixture."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

BASE = "ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852"
PACKAGE = "https://github.com/aws/aws-codedeploy-agent/releases/download/v2.1.2/codedeploy-agent_2.1.2_amd64.deb"
DIGEST = "ecafc903fd2dce929c7b4dba4c5c54593507245a177eedccec41d033a37325ac"
DOCKERFILE_DIGEST = "c789fb07fe5527e1cbf2b1bf45e6a5b247cf2fc6fc79497c9aad8c78495cc591"


def command(argv, output, timeout):
    at_utc = datetime.datetime.now(datetime.timezone.utc).isoformat()
    started = time.monotonic()
    with output.open("wb") as stream:
        process = subprocess.Popen(argv, stdout=stream, stderr=subprocess.STDOUT)
        try:
            code = process.wait(timeout=timeout)
            timed_out = False
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
            code = process.returncode
            timed_out = True
    return {"atUtc": at_utc, "argv": argv, "exit": code, "timedOut": timed_out,
            "seconds": time.monotonic() - started, "log": str(output)}


def verify_file(path, digest):
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != digest:
        raise ValueError(f"Unexpected bytes for {path}: {actual}")


def collect(root, run_id, attempt):
    output = root / "acquire-evidence"
    output.mkdir(exist_ok=True)
    name = f"codedeploy-acquire-{run_id}-{attempt}"
    proof = {"container": name, "base": BASE, "packageSha256": DIGEST,
             "source": os.environ.get("GITHUB_SHA"), "commands": [],
             "scope": "Exact fixture RUN in named container with APT debug logging. Not Docker build API or agent execution."}
    record = output / "proof.json"

    def save():
        record.write_text(json.dumps(proof, indent=2) + "\n")

    def execute(argv, label, timeout):
        result = command(argv, output / (label + ".log"), timeout)
        proof["commands"].append(result)
        save()
        return result

    save()  # Ownership precedes any allocation.
    verify_file(root / "src/test/resources/codedeploy/agent/Dockerfile", DOCKERFILE_DIGEST)
    package = output / "agent.deb"
    result = execute(["curl", "--fail", "--location", "--connect-timeout", "15",
                      "--max-time", "90", "--max-filesize", "33554432", "--output", str(package),
                      PACKAGE], "download", 95)
    if result["exit"] != 0:
        raise RuntimeError("Agent download failed")
    verify_file(package, DIGEST)
    result = execute(["docker", "pull", BASE], "pull", 180)
    if result["exit"] != 0:
        raise RuntimeError("Pinned base pull failed")
    execute(["docker", "image", "inspect", BASE], "image", 10)
    try:
        result = execute(["docker", "run", "--detach", "--name", name,
                          "--label", "floci.diagnostic=" + name, BASE,
                          "sh", "-ec", "trap 'exit 0' TERM; sleep 2147483647 & wait"], "create", 30)
        if result["exit"] != 0:
            raise RuntimeError("Owned container startup failed")
        execute(["docker", "cp", str(package), name + ":/tmp/agent.deb"], "copy", 15)
        execute(["docker", "exec", name, "sh", "-ec",
                 "cat /etc/resolv.conf; cat /etc/apt/sources.list.d/ubuntu.sources; "
                 "getent ahosts archive.ubuntu.com; getent ahosts security.ubuntu.com"], "container-dns", 20)
        # Debug configuration only: no retries, mirrors, protocol or timeout overrides.
        execute(["docker", "exec", name, "sh", "-ec",
                 "printf '%s\\n' 'Debug::Acquire::http \"true\";' "
                 "'Debug::Acquire::https \"true\";' 'Debug::pkgAcquire::Worker \"true\";' "
                 "> /etc/apt/apt.conf.d/99-acquire-evidence"], "debug-config", 10)
        dockerfile = (root / "src/test/resources/codedeploy/agent/Dockerfile").read_text()
        run_command = dockerfile.split("RUN ", 1)[1].split("\nENTRYPOINT", 1)[0].replace("\\\n", " ")
        proof["runCommand"] = run_command
        save()
        execute(["docker", "exec", name, "sh", "-ec", run_command], "apt-acquire", 180)
        execute(["docker", "exec", name, "sh", "-c",
                 "date -u; cat /proc/net/tcp /proc/net/tcp6; ps -ef; "
                 "dpkg-query -W openssh-server ca-certificates busybox-static"], "container-status", 10)
        # Host comparisons are labeled separately from the container APT transaction.
        execute(["getent", "ahosts", "archive.ubuntu.com"], "host-dns", 15)
        for scheme in ("http", "https"):
            execute(["curl", "--silent", "--show-error", "--location", "--connect-timeout", "10",
                     "--max-time", "20", "--output", "/dev/null", "--write-out",
                     "status=%{http_code} remote=%{remote_ip} dns=%{time_namelookup} connect=%{time_connect} tls=%{time_appconnect} first=%{time_starttransfer} total=%{time_total}\\n",
                     scheme + "://archive.ubuntu.com/ubuntu/dists/noble/InRelease"], "host-" + scheme, 25)
    finally:
        execute(["docker", "rm", "--force", "--volumes", name], "cleanup", 20)
        result = execute(["docker", "container", "ls", "--all", "--quiet", "--filter",
                          "label=floci.diagnostic=" + name], "remaining", 10)
        proof["containerAbsent"] = result["exit"] == 0 and not (output / "remaining.log").read_text().strip()
        package.unlink(missing_ok=True)
        save()
    if not proof["containerAbsent"]:
        raise RuntimeError("Owned container cleanup failed")


if __name__ == "__main__":
    collect(Path.cwd(), os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"])
