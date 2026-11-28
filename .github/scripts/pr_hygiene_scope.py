"""Resolve trusted synchronization ancestry and check newly contributed text."""

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


EM_DASH = b"\xe2\x80\x94"
OLD_PACKAGE = b"io/github/hectorvent/floci"
NEW_PACKAGE = b"io/floci/aws"
SHA = re.compile(r"[0-9a-f]{40}")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
HISTORY_POLICY = Path(__file__).with_name("pr_hygiene_history.json")


def history_policy(repository, eligible):
    """Reviewed immutable import roots, never roots supplied by a PR event."""
    if not HISTORY_POLICY.exists():
        return [], None
    contents = HISTORY_POLICY.read_bytes()
    policy = json.loads(contents)
    if policy.get("version") != 1 or not isinstance(policy.get("repositories"), dict):
        raise ValueError("Malformed historical import policy")
    roots = policy["repositories"].get(repository, [])
    if not isinstance(roots, list) or len(set(roots)) != len(roots):
        raise ValueError("Malformed historical import roots")
    for root in roots:
        valid_sha(root)
    return roots if eligible else [], hashlib.sha256(contents).hexdigest()


def run(*args, allowed=(0,)):
    result = subprocess.run(args, capture_output=True)
    if result.returncode not in allowed:
        raise ValueError(f"{args[0]} operation failed ({result.returncode}): "
                         + result.stderr.decode("utf-8", "replace")[-1000:])
    return result.stdout


def git(*args, allowed=(0,)):
    return run("git", *args, allowed=allowed)


def valid_sha(value):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise ValueError("Missing or malformed immutable commit SHA")
    return value


def event_anchors():
    repository = os.environ["REPO"]
    if not REPOSITORY.fullmatch(repository) or any(p in (".", "..") for p in repository.split("/")):
        raise ValueError("Malformed trusted repository identity")
    if os.environ["GITHUB_EVENT_NAME"] != "pull_request":
        raise ValueError("Only pull_request events are supported")
    event_bytes = Path(os.environ["GITHUB_EVENT_PATH"]).read_bytes()
    event = json.loads(event_bytes)
    pull = event["pull_request"]
    base, head = pull["base"], pull["head"]
    if event["repository"]["full_name"] != repository or base["repo"]["full_name"] != repository:
        raise ValueError("Event target repository differs from the trusted repository")
    for endpoint, variable in ((base, "BASE_SHA"), (head, "HEAD_SHA")):
        if valid_sha(endpoint["sha"]) != valid_sha(os.environ[variable]):
            raise ValueError("Event commit differs from the workflow anchor")
        if not isinstance(endpoint["ref"], str) or not endpoint["ref"]:
            raise ValueError("Missing event branch identity")
        git("check-ref-format", "refs/heads/" + endpoint["ref"])
    repository_id = int(os.environ["GITHUB_REPOSITORY_ID"])
    if (repository_id <= 0 or type(event["repository"]["id"]) is not int
            or type(base["repo"]["id"]) is not int
            or event["repository"]["id"] != repository_id or base["repo"]["id"] != repository_id):
        raise ValueError("Event target repository ID differs from the trusted repository")
    same_repository = head["repo"]["full_name"] == repository
    if same_repository and (type(head["repo"]["id"]) is not int or head["repo"]["id"] != repository_id):
        raise ValueError("Head repository name and ID disagree")
    sync = same_repository and base["ref"] == "dev" and head["ref"] == "dev_target"
    roots, policy_digest = history_policy(repository, same_repository and base["ref"] == "main")
    return {"version": 1, "repository": repository, "base": base["sha"], "head": head["sha"],
            "eventDigest": hashlib.sha256(event_bytes).hexdigest(), "sync": sync,
            "historyCandidates": roots, "historyPolicyDigest": policy_digest}


def require_object(sha):
    if git("cat-file", "-t", valid_sha(sha)).strip() != b"commit":
        raise ValueError("Expected a commit object")


def require_accepted_ancestry(scope):
    for sha in (scope["base"], scope["head"]):
        require_object(sha)
    if scope["sync"]:
        require_object(scope["acceptedMain"])
        for ancestor in (scope["base"], scope["acceptedMain"]):
            git("merge-base", "--is-ancestor", ancestor, scope["head"])
    accepted = []
    for root in scope["historyCandidates"]:
        require_object(root)
        result = subprocess.run(["git", "merge-base", "--is-ancestor", root, scope["head"]])
        if result.returncode == 0:
            accepted.append(root)
        elif result.returncode != 1:
            raise ValueError("Unable to verify historical import ancestry")
    if scope["acceptedHistory"] != accepted:
        raise ValueError("Historical import scope differs from reviewed ancestry")


def write_scope(path):
    # A failed resolution cannot leave a previously successful scope behind.
    path.unlink(missing_ok=True)
    scope = event_anchors()
    scope["acceptedMain"] = None
    scope["acceptedHistory"] = []
    for root in scope["historyCandidates"]:
        present = subprocess.run(["git", "cat-file", "-e", root + "^{commit}"], capture_output=True)
        if present.returncode:
            git("fetch", "--no-tags", "--no-write-fetch-head",
                f"https://github.com/{scope['repository']}.git", root)
        result = subprocess.run(["git", "merge-base", "--is-ancestor", root, scope["head"]])
        if result.returncode == 0:
            scope["acceptedHistory"].append(root)
        elif result.returncode != 1:
            raise ValueError("Unable to verify historical import ancestry")
    if scope["sync"]:
        endpoint = f"repos/{scope['repository']}/git/ref/heads/main"
        reference = json.loads(run("gh", "api", "--hostname", "github.com", endpoint))
        if reference["ref"] != "refs/heads/main" or reference["object"]["type"] != "commit":
            raise ValueError("Trusted main lookup did not return a commit reference")
        main = valid_sha(reference["object"]["sha"])
        # A source PR cannot supply the fetch URL or replace it via origin.
        present = subprocess.run(["git", "cat-file", "-e", main + "^{commit}"], capture_output=True)
        if present.returncode:
            git("fetch", "--no-tags", "--no-write-fetch-head",
                f"https://github.com/{scope['repository']}.git", main)
        scope["acceptedMain"] = main
    require_accepted_ancestry(scope)
    path.write_text(json.dumps(scope, sort_keys=True) + "\n")
    print("Review scope: " + json.dumps(scope, sort_keys=True), file=sys.stderr)


def read_scope(path):
    scope = json.loads(path.read_text())
    anchors = event_anchors()
    if any(scope.get(key) != value for key, value in anchors.items()):
        raise ValueError("Stored review scope differs from the immutable event anchors")
    if scope["sync"]:
        valid_sha(scope["acceptedMain"])
    elif scope["acceptedMain"] is not None:
        raise ValueError("Ordinary PRs cannot subtract main ancestry")
    require_accepted_ancestry(scope)
    return scope


def ordinary_commits(scope):
    exclusions = ["^" + scope["base"]]
    exclusions.extend("^" + root for root in scope["acceptedHistory"])
    if scope["sync"]:
        exclusions.append("^" + scope["acceptedMain"])
    return git("rev-list", "--no-merges", scope["head"], *exclusions).decode("ascii").splitlines()


def canonical_path(path):
    # Only the repository's documented Java source package migration is inherited.
    for prefix in (b"src/main/java/", b"src/test/java/"):
        old = prefix + OLD_PACKAGE + b"/"
        if path.startswith(old):
            return prefix + NEW_PACKAGE + b"/" + path[len(old):]
    return path


def canonical_line(path, line):
    # Limit namespace equivalence to corresponding Java source paths.
    if any(canonical_path(path).startswith(prefix + NEW_PACKAGE + b"/")
           for prefix in (b"src/main/java/", b"src/test/java/")):
        return line.replace(OLD_PACKAGE, NEW_PACKAGE).replace(
            b"io.github.hectorvent.floci", b"io.floci.aws")
    return line


def emdash_lines(ref):
    # One git process per tree; NUL separates paths and line numbers, even for
    # filenames containing newlines. Git -I keeps the existing text-file scope.
    data = git("grep", "-I", "-n", "-z", "-F", EM_DASH, ref, "--", allowed=(0, 1))
    result = {}
    original_paths = {}
    position = 0
    while position < len(data):
        end = data.index(b"\0", position)
        path = data[position:end].split(b":", 1)[1]
        position = data.index(b"\0", end + 1) + 1
        end = data.find(b"\n", position)
        if end == -1:
            end = len(data)
        line = data[position:end]
        position = end + 1
        canonical = canonical_path(path)
        if canonical in original_paths and original_paths[canonical] != path:
            raise ValueError("Ambiguous inherited Java namespace paths")
        original_paths[canonical] = path
        result.setdefault(canonical, Counter())[canonical_line(path, line)] += 1
    return result


def report(message):
    # Escape workflow command controls in untrusted content.
    escaped = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print("::error::" + escaped)


def check_emdashes(scope):
    if not scope["sync"] and not scope["acceptedHistory"]:
        added = set(git("diff", "-z", "--diff-filter=A", "--name-only",
                        scope["base"], scope["head"]).split(b"\0"))
        failed = False
        for path in added - {b""}:
            content = git("show", scope["head"] + ":" + os.fsdecode(path))
            if b"\0" not in content and EM_DASH in content:
                report("Em-dash found in new file: " + repr(os.fsdecode(path)))
                failed = True
        return failed

    failed = False
    base = emdash_lines(scope["base"])
    inherited = {}
    for root in scope["acceptedHistory"] + ([scope["acceptedMain"]] if scope["sync"] else []):
        for path, lines in emdash_lines(root).items():
            inherited[path] = inherited.get(path, Counter()) | lines
    for sha in ordinary_commits(scope):
        if scope["acceptedHistory"]:
            # Signed history carriers can reapply accepted lines. Check their
            # complete trees so new text and extra occurrences still fail.
            for path, lines in emdash_lines(sha).items():
                accepted = base.get(path, Counter()) | inherited.get(path, Counter())
                for line, count in (lines - accepted).items():
                    report("New em-dash text in commit " + sha + " at " + repr(os.fsdecode(path))
                           + f" ({count} occurrence(s)): " + line.decode("utf-8", "replace"))
                    failed = True
            continue
        in_hunk = False
        patch = git("show", "--format=", "--unified=0", "--no-renames",
                    "--no-ext-diff", "--no-textconv", sha)
        for line in patch.split(b"\n"):
            if line.startswith(b"diff --git "):
                in_hunk = False
            elif line.startswith(b"@@ "):
                in_hunk = True
            elif in_hunk and line.startswith(b"+") and EM_DASH in line:
                report("New em-dash text in commit " + sha + ": " + line[1:].decode("utf-8", "replace"))
                failed = True
    head = emdash_lines(scope["head"])
    for path, lines in head.items():
        accepted = base.get(path, Counter()) | inherited.get(path, Counter())
        for line, count in (lines - accepted).items():
            report("New em-dash provenance in " + repr(os.fsdecode(path))
                   + f" ({count} occurrence(s)): " + line.decode("utf-8", "replace"))
            failed = True
    return failed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("resolve", "commits", "emdashes"))
    parser.add_argument("--scope-file", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.mode == "resolve":
            write_scope(args.scope_file)
            return 0
        scope = read_scope(args.scope_file)
        if args.mode == "commits":
            for sha in ordinary_commits(scope):
                print(sha)
            return 0
        return int(check_emdashes(scope))
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
        report("Unable to establish PR hygiene scope: " + str(error))
        return 1


if __name__ == "__main__":
    sys.exit(main())
