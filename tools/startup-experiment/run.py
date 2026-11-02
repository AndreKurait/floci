#!/usr/bin/env python3
"""Startup-only ABBA experiment; no application/test sources or test selection rewritten."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

TEST = "io.github.hectorvent.floci.config.IotMqttWebSocketProxyIntegrationTest"
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "startup-evidence"

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def save(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")

def command(args, log):
    start = time.time()
    with log.open("w") as stream:
        result = subprocess.run(args, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
    save(log.with_suffix(".command.json"), {"argv": args, "startEpoch": start,
         "endEpoch": time.time(), "exitCode": result.returncode})
    if result.returncode:
        raise RuntimeError(f"Command failed: {log}")

def manifest(directory, pattern):
    return {str(p.relative_to(ROOT)): digest(p) for p in sorted(directory.rglob(pattern)) if p.is_file()}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--single", action="store_true", help="One local qualification invocation only")
    args = parser.parse_args()
    OUT.mkdir(exist_ok=False)
    pom = ROOT / "pom.xml"
    original = pom.read_bytes()
    save(OUT / "source.json", {"head": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
         "pomSha256": digest(pom), "source": manifest(ROOT / "src", "*"),
         "selectedTest": TEST, "scope": "Two existing tests, one profile per JVM; not full suite coverage."})
    command(["javac", "-d", str(OUT), str(ROOT / "tools/startup-experiment/ForkReceipt.java")], OUT / "agent-compile.log")
    (OUT / "MANIFEST.MF").write_text("Premain-Class: ForkReceipt\n")
    command(["jar", "cfm", str(OUT / "receipt.jar"), str(OUT / "MANIFEST.MF"), "-C", str(OUT), "ForkReceipt.class"], OUT / "agent-jar.log")
    order = ["test"] if args.single else ["test", "surefire:test", "surefire:test", "test"]
    save(OUT / "design.json", {"order": order, "sameRunner": True, "freshJvm": True,
         "limits": "Filesystem, Maven metadata and test-index caches warmed by earlier invocations. ABBA limits order confounding, not independent-host variance. One profile per invocation prevents profile metrics overwrite."})
    try:
        fixed_receipt = OUT / "fork-current.txt"
        old_argline = "<argLine>@{argLine} -Xmx6g</argLine>"
        text = original.decode()
        assert text.count(old_argline) == 1
        options = f"-Dquarkus.builder.metrics.enabled=true -javaagent:{OUT}/receipt.jar={fixed_receipt}"
        pom.write_text(text.replace(old_argline, f"<argLine>@{{argLine}} -Xmx6g {options}</argLine>"))
        instrumented_hash = digest(pom)
        command(["./mvnw", "-B", "test-compile"], OUT / "instrumented-compile.log")
        save(OUT / "prepared-compiled.json", {"classes": manifest(ROOT / "target/classes", "*.class"),
             "tests": manifest(ROOT / "target/test-classes", "*.class")})
        def index_receipt():
            path = ROOT / "target/test-classes/test-classes.idx"
            return {"exists": path.exists(), "sha256": digest(path) if path.exists() else None,
                    "size": path.stat().st_size if path.exists() else None,
                    "mtimeNs": path.stat().st_mtime_ns if path.exists() else None}
        for index, goal in enumerate(order):
            dest = OUT / f"{index + 1}-{goal.replace(':', '-')}"
            dest.mkdir()
            metrics = ROOT / "target/build-metrics.json"
            metrics.unlink(missing_ok=True)
            reports = ROOT / "target/surefire-reports"
            if reports.exists():
                shutil.rmtree(reports)
            fixed_receipt.unlink(missing_ok=True)
            assert digest(pom) == instrumented_hash
            before_classes = {"classes": manifest(ROOT / "target/classes", "*.class"),
                              "tests": manifest(ROOT / "target/test-classes", "*.class")}
            before_index = index_receipt()
            save(dest / "instrumentation.json", {"originalPom": hashlib.sha256(original).hexdigest(),
                 "instrumentedPom": instrumented_hash, "beforeClasses": before_classes,
                 "beforeIndex": before_index})
            try:
                command(["./mvnw", "-B", goal, "-Dtest=" + TEST, "-Dsurefire.failIfNoTests=true", "-Dquarkus.http.test-ssl-port=0"], dest / "tests.log")
            finally:
                assert digest(pom) == instrumented_hash
                if fixed_receipt.exists():
                    shutil.move(fixed_receipt, dest / "fork.txt")
                after_classes = {"classes": manifest(ROOT / "target/classes", "*.class"),
                                 "tests": manifest(ROOT / "target/test-classes", "*.class")}
                save(dest / "output-stability.json", {"unchanged": before_classes == after_classes,
                     "afterClasses": after_classes, "beforeIndex": before_index, "afterIndex": index_receipt()})
                if reports.exists():
                    shutil.copytree(reports, dest / "reports")
                if metrics.exists():
                    shutil.move(metrics, dest / "build-metrics.json")
            receipt = (dest / "fork.txt").read_text()
            assert "metrics=true" in receipt
            data = json.loads((dest / "build-metrics.json").read_text())
            assert data["records"] and data["duration"] > 0
            xml = list((dest / "reports").glob("TEST-*.xml"))
            assert len(xml) == 1
            suite = ET.parse(xml[0]).getroot()
            properties = {p.get("name"): p.get("value") for p in suite.findall("./properties/property")}
            classpath = properties.get("surefire.test.class.path") or properties.get("java.class.path")
            assert classpath
            entries = [Path(value) for value in classpath.split(os.pathsep)]
            assert any(entry.suffix == ".jar" for entry in entries)
            save(dest / "classpath.json", [{"path": str(entry),
                 "sha256": digest(entry) if entry.is_file() else None,
                 "directory": entry.is_dir()} for entry in entries])
            assert suite.get("name") == TEST and suite.get("tests") == "2"
            assert all(int(suite.get(k, "0")) == 0 for k in ("failures", "errors", "skipped"))
            started = [line for line in (dest / "tests.log").read_text().splitlines() if "Profile " in line and "activated" in line]
            assert len(started) == 1, started
            save(dest / "summary.json", {"tests": 2, "profile": started, "buildDuration": data["duration"],
                 "buildSteps": len(data["records"]), "metricsSha256": digest(dest / "build-metrics.json"),
                 "longestSteps": sorted(data["records"], key=lambda x: x["duration"], reverse=True)[:20],
                 "limits": "Build steps overlap on parallel workers; summed durations are not wall time. Pre-augmentation indexing is outside build-metrics."})
    finally:
        pom.write_bytes(original)
    assert digest(pom) == hashlib.sha256(original).hexdigest()
    runs = []
    for directory in sorted(OUT.glob("[1-4]-*")):
        receipt = dict(line.split("=", 1) for line in (directory / "fork.txt").read_text().splitlines())
        timing = json.loads((directory / "tests.command.json").read_text())
        summary = json.loads((directory / "summary.json").read_text())
        runs.append({"invocation": directory.name, "pid": receipt["pid"],
                     "elapsedSeconds": timing["endEpoch"] - timing["startEpoch"],
                     "metrics": summary})
    assert len({run["pid"] for run in runs}) == len(order)
    save(OUT / "comparison.json", {"runs": runs,
         "limits": "Same-host sequential startup-only measurements; no full-suite or causal speedup claim."})

if __name__ == "__main__":
    main()
