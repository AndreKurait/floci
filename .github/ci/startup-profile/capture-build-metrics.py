"""Capture sanitized Quarkus build metrics on Linux file-close events."""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import select
import signal
import struct
import sys
import time


def positive_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0


def sanitized(raw):
    steps = []
    for record in raw.get("records", []):
        name = record.get("stepId", "")
        duration = record.get("duration")
        if (isinstance(name, str)
                and name.startswith(("io.quarkus.", "org.jboss.", "io.github.hectorvent.floci."))
                and re.fullmatch(r"[A-Za-z0-9_.$#<>-]+", name)
                and positive_number(duration)):
            steps.append({"step": name, "durationMillis": duration})
    steps.sort(key=lambda step: step["durationMillis"], reverse=True)
    return {
        "durationMillis": raw.get("duration") if positive_number(raw.get("duration")) else None,
        "allowedStepCount": len(steps),
        "largestSteps": steps[:40],
    }


def main():
    directory, destination = map(Path, sys.argv[1:])
    directory.mkdir(parents=True, exist_ok=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    libc = ctypes.CDLL(None, use_errno=True)
    libc.inotify_init1.argtypes = [ctypes.c_int]
    libc.inotify_init1.restype = ctypes.c_int
    libc.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
    libc.inotify_add_watch.restype = ctypes.c_int
    fd = libc.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
    if fd < 0:
        raise OSError(ctypes.get_errno(), "inotify_init1 failed")
    builds = []
    seen = set()
    started = time.monotonic()
    errors = 0

    def write_summary():
        temporary = destination.with_suffix(".tmp")
        temporary.write_text(json.dumps({
            "scope": "File-close captures, relative to observer start; step durations are milliseconds. "
                     "Concurrent steps overlap. At most 64 builds are retained. "
                     "Only allowlisted step names and numbers are retained.",
            "builds": builds,
            "captureErrors": errors,
        }, indent=2) + "\n")
        temporary.replace(destination)

    def stop(signum, frame):
        raise SystemExit(0)

    try:
        # CLOSE_WRITE and MOVED_TO cover ordinary writes and atomic replacements.
        if libc.inotify_add_watch(fd, os.fsencode(directory), 0x8 | 0x80) < 0:
            raise OSError(ctypes.get_errno(), "inotify_add_watch failed")
        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        write_summary()
        print("READY", flush=True)
        while True:
            select.select([fd], [], [])
            payload = os.read(fd, 65536)
            offset = 0
            while offset + 16 <= len(payload):
                watch, mask, cookie, length = struct.unpack_from("iIII", payload, offset)
                name = payload[offset + 16:offset + 16 + length].split(b"\0", 1)[0]
                offset += 16 + length
                if mask & 0x4000:  # IN_Q_OVERFLOW invalidates complete capture.
                    errors += 1
                    write_summary()
                if name != b"build-metrics.json" or len(builds) >= 64:
                    continue
                try:
                    content = (directory / "build-metrics.json").read_bytes()
                    fingerprint = hashlib.sha256(content).hexdigest()
                    if fingerprint in seen:
                        continue
                    summary = sanitized(json.loads(content))
                    summary["capturedAfterSeconds"] = round(time.monotonic() - started, 3)
                    builds.append(summary)
                    seen.add(fingerprint)
                except (OSError, ValueError, TypeError, AttributeError):
                    errors += 1
                write_summary()
                print("CAPTURED {}".format(len(builds)), flush=True)
    finally:
        os.close(fd)


if __name__ == "__main__":
    main()
