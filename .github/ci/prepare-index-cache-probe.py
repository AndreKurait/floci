#!/usr/bin/env python3
"""Prepare an isolated test dependency for the fork-only CI experiment."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

VERSION = "3.40.1.1"
PROBE_VERSION = VERSION + "-index-cache-probe"
SOURCE_SHA256 = "710b1b2fd54a6e2f3b2b2db50dabe065d2efd71dd3ea1574c5d04f793c52e2a8"
SOURCE_URL = (
    "https://raw.githubusercontent.com/quarkusio/quarkus/"
    + VERSION
    + "/test-framework/common/src/main/java/io/quarkus/test/common/TestClassIndexer.java"
)
NS = "http://maven.apache.org/POM/4.0.0"
ET.register_namespace("", NS)


def tag(name):
    return "{" + NS + "}" + name


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Verified local source for qualification")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    workspace = Path.cwd()
    root = Path(os.environ["RUNNER_TEMP"]) / ("floci-index-cache-" + os.environ["SHARD_INDEX"])
    root.mkdir(parents=True, exist_ok=False)
    env = {
        key: value for key, value in os.environ.items()
        if not key.startswith(("AWS_", "BEDROCK_", "OPENAI_", "ANTHROPIC_", "GH_", "GITHUB_"))
    }
    maven = [str(workspace / "mvnw"), "-ntp", "-B"]
    if args.offline:
        maven.append("-o")
        env["MAVEN_CMD_LINE_ARGS"] = "-o"
    classpath = root / "classpath.txt"
    subprocess.run(
        maven + ["dependency:build-classpath", "-Dmdep.outputFile=" + str(classpath)],
        env=env, check=True,
    )
    jars = [Path(value) for value in classpath.read_text().strip().split(os.pathsep)]
    official = next(path for path in jars if path.name == "quarkus-test-common-" + VERSION + ".jar")
    original_hash = digest(official)
    source = root / "TestClassIndexer.java"
    if args.source:
        source.write_bytes(args.source.read_bytes())
    else:
        with urllib.request.urlopen(SOURCE_URL, timeout=60) as response:
            source.write_bytes(response.read())
    if digest(source) != SOURCE_SHA256:
        raise RuntimeError("Official source differs from the qualified immutable snapshot")
    subprocess.run(
        ["patch", "--batch", "--fuzz=0", "-p1", "-i",
         str(workspace / ".github/ci/quarkus-index-cache-probe.patch")],
        cwd=root, env=env, check=True,
    )
    classes = root / "classes"
    classes.mkdir()
    compiler_args = root / "javac.args"
    compiler_args.write_text(
        "-classpath\n" + json.dumps(classpath.read_text().strip()) + "\n"
        + "-d\n" + json.dumps(str(classes)) + "\n" + json.dumps(str(source)) + "\n"
    )
    subprocess.run(["javac", "@" + str(compiler_args)], env=env, check=True)
    replacements = {
        str(path.relative_to(classes)): path.read_bytes()
        for path in classes.rglob("TestClassIndexer*.class")
    }
    if not replacements:
        raise RuntimeError("The experimental classes were not compiled")
    probe = root / ("quarkus-test-common-" + PROBE_VERSION + ".jar")
    with zipfile.ZipFile(official) as original, zipfile.ZipFile(probe, "w", zipfile.ZIP_DEFLATED) as result:
        for entry in original.infolist():
            result.writestr(entry, replacements.pop(entry.filename, original.read(entry.filename)))
        for name, content in replacements.items():
            result.writestr(name, content)
    original_pom = ET.parse(official.with_suffix(".pom")).getroot()
    probe_pom = ET.Element(tag("project"))
    for name, value in [("modelVersion", "4.0.0"), ("groupId", "io.quarkus"),
                        ("artifactId", "quarkus-test-common"), ("version", PROBE_VERSION)]:
        ET.SubElement(probe_pom, tag(name)).text = value
    management = ET.SubElement(ET.SubElement(probe_pom, tag("dependencyManagement")), tag("dependencies"))
    bom = ET.SubElement(management, tag("dependency"))
    for name, value in [("groupId", "io.quarkus"), ("artifactId", "quarkus-bom"),
                        ("version", VERSION), ("type", "pom"), ("scope", "import")]:
        ET.SubElement(bom, tag(name)).text = value
    dependencies = original_pom.find(tag("dependencies"))
    for dependency in list(dependencies):
        if dependency.findtext(tag("scope")) == "test":
            dependencies.remove(dependency)
    probe_pom.append(dependencies)
    descriptor = root / "artifact.pom"
    ET.ElementTree(probe_pom).write(descriptor, encoding="unicode")
    subprocess.run(
        maven + ["org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file",
                 "-Dfile=" + str(probe), "-DpomFile=" + str(descriptor)],
        env=env, check=True,
    )
    # Only this disposable experiment workspace requests the new coordinate.
    pom = workspace / "pom.xml"
    text = pom.read_text()
    needle = "\n    <dependencies>\n"
    replacement = (
        needle + "        <dependency>\n"
        "            <groupId>io.quarkus</groupId>\n"
        "            <artifactId>quarkus-test-common</artifactId>\n"
        "            <version>" + PROBE_VERSION + "</version>\n"
        "            <scope>test</scope>\n        </dependency>\n"
    )
    if text.count(needle) != 1:
        raise RuntimeError("The project dependency layout differs from qualification")
    pom.write_text(text.replace(needle, replacement, 1))
    if digest(official) != original_hash:
        raise RuntimeError("The official artifact changed")
    (root / "proof.json").write_text(json.dumps({
        "sourceSha256": SOURCE_SHA256,
        "patchedSourceSha256": digest(source),
        "officialArtifactSha256": original_hash,
        "officialArtifactUnchanged": True,
        "probeArtifactSha256": digest(probe),
        "probeCoordinate": "io.quarkus:quarkus-test-common:" + PROBE_VERSION,
        "scope": "Fork-only test dependency experiment; not a release dependency",
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
