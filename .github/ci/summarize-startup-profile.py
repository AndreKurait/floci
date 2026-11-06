#!/usr/bin/env python3
"""Export sampled method counts and timing totals without JVM environment or hosts."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess


def seconds(value):
    match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?", value)
    if not match:
        raise ValueError("unsupported JFR duration")
    hours, minutes, seconds_part = match.groups()
    return int(hours or 0) * 3600 + int(minutes or 0) * 60 + float(seconds_part or 0)


def summarize(recording):
    command = [
        "jfr", "print", "--json", "--events",
        "jdk.ExecutionSample,jdk.GarbageCollection,jdk.SocketRead,jdk.JVMInformation",
        str(recording),
    ]
    events = json.loads(subprocess.check_output(command))["recording"]["events"]
    counts = Counter()
    leaves = Counter()
    samples = 0
    pauses = 0
    reads = []
    kind = "helper JVM"
    for event in events:
        values = event["values"]
        if event["type"] == "jdk.JVMInformation":
            arguments = (values.get("javaArguments") or "").lower()
            if "surefire" in arguments:
                kind = "Surefire test JVM"
            elif "classworlds" in arguments:
                kind = "Maven JVM"
        elif event["type"] == "jdk.ExecutionSample":
            samples += 1
            frames = (values.get("stackTrace") or {}).get("frames", [])
            names = [
                frame["method"]["type"]["name"] + "." + frame["method"]["name"]
                for frame in frames
            ]
            if names:
                leaves[names[0]] += 1
            for label, prefix in [
                ("javac", "com/sun/tools/javac"),
                ("Checkstyle", "com/puppycrawl/tools/checkstyle"),
                ("Quarkus deployment", "io/quarkus/deployment"),
                ("Jandex", "org/jboss/jandex"),
                ("Byte Buddy", "net/bytebuddy"),
                ("class initialization", ".<clinit>"),
            ]:
                if any(prefix in name for name in names):
                    counts[label] += 1
        elif event["type"] == "jdk.GarbageCollection":
            pauses += seconds(values["sumOfPauses"])
        elif event["type"] == "jdk.SocketRead":
            reads.append(seconds(values["duration"]))
    return {
        "kind": kind,
        "executionSamples": samples,
        "inclusiveStackSampleCounts": dict(counts),
        "topLeafMethods": leaves.most_common(12),
        "summedGcPausesSeconds": round(pauses, 3),
        "socketReadEvents": len(reads),
        "summedSocketReadThreadSeconds": round(sum(reads), 3),
        "largestSocketReadSeconds": round(max(reads, default=0), 3),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--recordings", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    recordings = sorted(args.recordings.glob("*.jfr"))
    if not recordings:
        parser.error("no launch-time recordings found")
    result = {
        "recordings": [summarize(recording) for recording in recordings],
        "limits": [
            "Diagnostic run with profiling overhead, not an optimization comparison.",
            "Inclusive sampled stacks overlap and are not wall-time attribution.",
            "Socket-read durations overlap across threads and omit short reads.",
            "Raw recordings, JVM arguments, environment and network hosts are not exported.",
        ],
    }
    launch_result = args.recordings / "result.json"
    if launch_result.exists():
        launch = json.loads(launch_result.read_text())
        result["commandExitCode"] = launch["exitCode"]
        if "elapsedSeconds" in launch:
            result["commandElapsedSeconds"] = launch["elapsedSeconds"]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
