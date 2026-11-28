"""Run the workflow's signature guard against Git history and a paginated API fixture."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / "workflows" / "pr-hygiene.yml"


def signature_script():
    lines = WORKFLOW.read_text().splitlines()
    start = lines.index("      - name: Commits are signed and verified")
    run = lines.index("        run: |", start)
    script = []
    for line in lines[run + 1:]:
        if line and not line.startswith("          "):
            break
        script.append(line[10:] if line else "")
    return "\n".join(script) + "\n"


class SignatureGuardTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.env = dict(os.environ, GIT_AUTHOR_NAME="Fixture", GIT_AUTHOR_EMAIL="fixture@example.com",
                        GIT_COMMITTER_NAME="Fixture", GIT_COMMITTER_EMAIL="fixture@example.com")
        self.git("init", "-q")
        tree = self.git("mktree", input="")

        def commit(subject, *parents):
            args = ["commit-tree", tree, "-m", subject]
            for parent in parents:
                args.extend(["-p", parent])
            return self.git(*args)

        self.base = commit("chore: base")
        self.first = commit("fix: first", self.base)
        self.second = commit("fix: second", self.first)
        self.side = commit("fix: side", self.base)
        self.head = commit("Merge side", self.second, self.side)
        self.required = {self.first, self.second, self.side}
        tools = self.root / "tools"
        tools.mkdir()
        gh = tools / "gh"
        gh.write_text(f"#!{sys.executable}\n" + r'''
import json
import os
from pathlib import Path
import subprocess
import sys

args = sys.argv[1:]
with Path("api-calls.jsonl").open("a") as output:
    output.write(json.dumps(args) + "\n")
fixture = json.loads(Path("api-fixture.json").read_text())
endpoint = args[1]
expected = f"repos/example/floci/compare/{os.environ['BASE_SHA']}...{os.environ['HEAD_SHA']}?per_page=100"
query = args[args.index("--jq") + 1]
if endpoint == expected and "--paginate" in args:
    if fixture.get("compare_error"):
        sys.exit(1)
    for page in fixture["pages"]:
        result = subprocess.run(["jq", "-c", query], input=json.dumps(page), text=True)
        if result.returncode:
            sys.exit(result.returncode)
    if fixture.get("partial_compare_error"):
        sys.exit(1)
elif endpoint.startswith("repos/example/floci/commits/"):
    sha = endpoint.rsplit("/", 1)[1]
    if sha not in fixture["fallbacks"]:
        sys.exit(1)
    result = subprocess.run(["jq", "-c", query],
                            input=json.dumps({"commit": {"verification": fixture["fallbacks"][sha]}}),
                            text=True)
    sys.exit(result.returncode)
else:
    sys.exit(2)
''')
        gh.chmod(0o755)
        self.env.update(PATH=f"{tools}:{self.env['PATH']}", BASE_SHA=self.base, HEAD_SHA=self.head,
                        REPO="example/floci", GH_TOKEN="fixture-token")
        self.env.update(GITHUB_EVENT_NAME="pull_request", GITHUB_REPOSITORY_ID="123",
                        GITHUB_WORKSPACE=str(WORKFLOW.parents[2]), RUNNER_TEMP=str(self.root),
                        GITHUB_EVENT_PATH=str(self.root / "event.json"))
        self.write_event()

    def write_event(self, base_ref="main", head_ref="feature", head_repository="example/floci"):
        repository = {"id": 123, "full_name": "example/floci"}
        event = {"repository": repository, "pull_request": {
            "base": {"sha": self.base, "ref": base_ref, "repo": repository},
            "head": {"sha": self.head, "ref": head_ref, "repo": {
                "id": 123 if head_repository == "example/floci" else 456,
                "full_name": head_repository}},
        }}
        Path(self.env["GITHUB_EVENT_PATH"]).write_text(json.dumps(event))


    def git(self, *args, input=None):
        return subprocess.check_output(["git", *args], cwd=self.root, env=self.env,
                                       input=input, text=True).strip()

    def record(self, sha, verified=True):
        return {"sha": sha, "commit": {"verification": {
            "verified": verified, "reason": "valid" if verified else "unsigned",
            "signature": "fixture", "payload": "fixture",
        }}}

    def run_guard(self, records=None, fallbacks=None, **options):
        if records is None:
            records = [self.record(sha) for sha in sorted(self.required)]
        pages = [{"commits": records[start:start + 100]} for start in range(0, len(records), 100)]
        fixture = dict(pages=pages, fallbacks=fallbacks or {}, **options)
        (self.root / "api-fixture.json").write_text(json.dumps(fixture))
        result = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail"],
                                input=signature_script(), cwd=self.root, env=self.env,
                                text=True, capture_output=True, timeout=30)
        calls = [json.loads(line) for line in (self.root / "api-calls.jsonl").read_text().splitlines()]
        fallbacks_used = [call[1].rsplit("/", 1)[1] for call in calls if "/commits/" in call[1]]
        return result, calls, fallbacks_used

    def test_paginated_compare_covers_commits_after_pr_endpoint_cap(self):
        records = [self.record(f"{index:040x}", verified=False) for index in range(251)]
        records += [self.record(self.head, verified=False)]
        records += [self.record(sha) for sha in sorted(self.required)]
        result, calls, fallbacks = self.run_guard(records)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(1, len(calls))
        self.assertEqual([], fallbacks)
        self.assertEqual(3, result.stdout.count("ok: "))
        self.assertNotIn("::error::", result.stdout)

    def test_unsigned_required_commit_fails_without_fallback(self):
        records = [self.record(sha, verified=sha != self.second) for sha in sorted(self.required)]
        result, _, fallbacks = self.run_guard(records)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"Commit is not signed/verified: {self.second}", result.stdout)
        self.assertIn("(unsigned)", result.stdout)
        self.assertEqual([], fallbacks)

    def test_missing_bulk_sha_requires_verified_per_commit_result(self):
        records = [self.record(sha) for sha in sorted(self.required - {self.second})]
        result, _, fallbacks = self.run_guard(records, {self.second: {"verified": True, "reason": "valid"}})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([self.second], fallbacks)

    def test_unsigned_fallback_fails(self):
        records = [self.record(sha) for sha in sorted(self.required - {self.second})]
        result, _, fallbacks = self.run_guard(records, {self.second: {"verified": False, "reason": "unsigned"}})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([self.second], fallbacks)
        self.assertIn(f"Commit is not signed/verified: {self.second}", result.stdout)

    def test_unresolvable_fallback_fails_closed(self):
        records = [self.record(sha) for sha in sorted(self.required - {self.second})]
        result, _, fallbacks = self.run_guard(records)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([self.second], fallbacks)
        self.assertIn(f"Could not check verification status for commit: {self.second}", result.stdout)

    def test_missing_bulk_verification_fails_closed(self):
        records = [self.record(sha) for sha in sorted(self.required - {self.second})]
        records.append({"sha": self.second, "commit": {}})
        result, _, _ = self.run_guard(records)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"Commit is not signed/verified: {self.second}", result.stdout)

    def test_missing_fallback_verification_fails_closed(self):
        records = [self.record(sha) for sha in sorted(self.required - {self.second})]
        result, _, fallbacks = self.run_guard(records, {self.second: None})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([self.second], fallbacks)
        self.assertIn(f"Commit is not signed/verified: {self.second}", result.stdout)

    def test_compare_failure_still_checks_every_required_commit(self):
        fallbacks = {sha: {"verified": True, "reason": "valid"} for sha in self.required}
        result, _, used = self.run_guard(fallbacks=fallbacks, compare_error=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(self.required, set(used))

    def test_partial_pagination_failure_discards_bulk_results_and_fails_closed(self):
        result, _, used = self.run_guard(partial_compare_error=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(self.required, set(used))
        self.assertEqual(3, result.stdout.count("Could not check verification status"))


if __name__ == "__main__":
    unittest.main()
