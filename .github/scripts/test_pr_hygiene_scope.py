"""Exercise the actual workflow commands with Git histories and CLI transports."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from test_pr_hygiene import WORKFLOW, signature_script


SCRIPT = Path(__file__).with_name("pr_hygiene_scope.py")
EM_DASH = "\u2014"


def emdash_script():
    text = WORKFLOW.read_text().split("      - name: New files omit em-dashes\n", 1)[1]
    block = text.split("      - name: PR body keeps the required template sections", 1)[0]
    return "\n".join(line[10:] for line in block.split("        run: |\n", 1)[1].splitlines()) + "\n"


class HygieneScopeTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.env = dict(os.environ, GIT_AUTHOR_NAME="Fixture", GIT_AUTHOR_EMAIL="fixture@example.invalid",
                        GIT_COMMITTER_NAME="Fixture", GIT_COMMITTER_EMAIL="fixture@example.invalid")
        self.git("init", "-q")
        self.base = self.commit("chore: accepted dev", files={"old.txt": "old" + EM_DASH + "\n"})
        self.main = self.commit("chore: accepted main", self.base,
                                files={"import.txt": "import" + EM_DASH + "\n"})
        self.boundary = self.commit("Merge accepted main", self.base, self.main, tree_from=self.main)
        self.head = self.commit("fix: new contribution", self.boundary, files={"feature.txt": "new\n"})
        self.required = {self.head}
        self.api = {"main": {"ref": "refs/heads/main", "object": {"type": "commit", "sha": self.main}},
                    "pages": [], "fallbacks": {}}
        tools = self.root / "tools"
        tools.mkdir()
        real_git = shutil.which("git")
        (tools / "gh").write_text(f"#!{sys.executable}\n" + r'''
import json
from pathlib import Path
import subprocess
import sys
args = sys.argv[1:]
with Path("api-calls.jsonl").open("a") as out:
    out.write(json.dumps(args) + "\n")
fixture = json.loads(Path("api-fixture.json").read_text())
if args == ["api", "--hostname", "github.com", "repos/example/floci/git/ref/heads/main"]:
    if fixture.get("main_error"):
        print("HTTP 503 fixture", file=sys.stderr)
        sys.exit(1)
    if fixture.get("main_invalid_json"):
        print("not JSON")
    else:
        print(json.dumps(fixture["main"]))
    sys.exit(0)
if len(args) < 2:
    sys.exit(2)
endpoint = args[1]
if not endpoint.startswith("repos/example/floci/"):
    sys.exit(2)
query = args[args.index("--jq") + 1]
if "/compare/" in endpoint and "--paginate" in args:
    if fixture.get("compare_error"):
        sys.exit(1)
    for page in fixture["pages"]:
        result = subprocess.run(["jq", "-c", query], input=json.dumps(page), text=True)
        if result.returncode:
            sys.exit(result.returncode)
    sys.exit(0)
if "/commits/" in endpoint:
    sha = endpoint.rsplit("/", 1)[1]
    if sha not in fixture["fallbacks"]:
        sys.exit(1)
    result = subprocess.run(["jq", "-c", query], input=json.dumps({"commit": {"verification": fixture["fallbacks"][sha]}}), text=True)
    sys.exit(result.returncode)
sys.exit(2)
''')
        (tools / "git").write_text(f"#!{sys.executable}\n" + f"REAL_GIT = {real_git!r}\n" + r'''
import json
import os
from pathlib import Path
import sys
args = sys.argv[1:]
if args and args[0] == "fetch":
    with Path("fetch-calls.jsonl").open("a") as out:
        out.write(json.dumps(args) + "\n")
    if os.environ.get("TEST_REAL_GIT_TRANSPORT_FAILURE") == "1":
        os.execv(REAL_GIT, [REAL_GIT, *args])
    print("fixture transport unavailable", file=sys.stderr)
    sys.exit(128)
os.execv(REAL_GIT, [REAL_GIT, *args])
''')
        for tool in tools.iterdir():
            tool.chmod(0o755)
        self.env.update(PATH=f"{tools}:{self.env['PATH']}", BASE_SHA=self.base, HEAD_SHA=self.head,
                        REPO="example/floci", GH_TOKEN="fixture-token", GITHUB_EVENT_NAME="pull_request",
                        GITHUB_REPOSITORY_ID="123", GITHUB_WORKSPACE=str(WORKFLOW.parents[2]),
                        RUNNER_TEMP=str(self.root), GITHUB_EVENT_PATH=str(self.root / "event.json"))
        self.write_event()

    def git(self, *args, input=None, env=None):
        return subprocess.check_output(["git", *args], cwd=self.root, env=env or self.env,
                                       input=input, text=True).strip()

    def commit(self, subject, *parents, files=None, tree_from=None):
        index = self.root / "temporary-index"
        index.unlink(missing_ok=True)
        env = dict(self.env, GIT_INDEX_FILE=str(index))
        if tree_from or parents:
            self.git("read-tree", tree_from or parents[0], env=env)
        else:
            self.git("read-tree", "--empty", env=env)
        for path, content in (files or {}).items():
            if content is None:
                self.git("update-index", "--force-remove", "--", path, env=env)
            else:
                blob = self.git("hash-object", "-w", "--stdin", input=content)
                self.git("update-index", "--add", "--cacheinfo", "100644", blob, path, env=env)
        tree = self.git("write-tree", env=env)
        args = ["commit-tree", tree, "-m", subject]
        for parent in parents:
            args.extend(["-p", parent])
        return self.git(*args)

    def write_event(self, base_ref="dev", head_ref="dev_target", head_repository="example/floci"):
        self.env["HEAD_SHA"] = self.head
        repository = {"id": 123, "full_name": "example/floci"}
        self.event = {"repository": repository, "pull_request": {
            "base": {"sha": self.base, "ref": base_ref, "repo": repository},
            "head": {"sha": self.head, "ref": head_ref, "repo": {
                "id": 123 if head_repository == "example/floci" else 456,
                "full_name": head_repository}},
        }}
        self.save_event()

    def save_event(self):
        Path(self.env["GITHUB_EVENT_PATH"]).write_text(json.dumps(self.event))

    def write_api(self, verified=True):
        if not self.api["pages"]:
            self.api["pages"] = [{"commits": [
                {"sha": sha, "commit": {"verification": {"verified": verified,
                                                           "reason": "valid" if verified else "unsigned"}}}
                for sha in sorted(self.required)]}]
        (self.root / "api-fixture.json").write_text(json.dumps(self.api))

    def bash(self, script):
        return subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail"], input=script,
                              cwd=self.root, env=self.env, capture_output=True, text=True, timeout=30)

    def signatures(self, verified=True):
        self.write_api(verified)
        return self.bash(signature_script())

    def resolve(self):
        self.write_api()
        return subprocess.run([sys.executable, str(SCRIPT), "resolve", "--scope-file",
                               str(self.root / "pr-hygiene-scope.json")], cwd=self.root, env=self.env,
                              capture_output=True, text=True, timeout=30)

    def emdashes(self):
        result = self.resolve()
        if result.returncode:
            return result
        return self.bash(emdash_script())

    def calls(self):
        path = self.root / "api-calls.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def assert_success(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def assert_failure(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("::error::", result.stdout)

    def test_accepted_unsigned_main_excluded_new_verified_checked(self):
        result = self.signatures()
        self.assert_success(result)
        self.assertEqual(1, result.stdout.count("ok: "))
        self.assertIn(self.head, result.stdout)
        self.assertNotIn("ok: " + self.main, result.stdout)

    def test_new_unsigned_still_fails(self):
        self.assert_failure(self.signatures(verified=False))

    def test_missing_verification_and_failed_transport_fail_closed(self):
        self.api["pages"] = [{"commits": []}]
        self.assert_failure(self.signatures())
        self.assertTrue(any("/commits/" in call[1] for call in self.calls() if len(call) > 1))

    def test_verified_with_invalid_reason_fails_closed(self):
        self.api["pages"] = [{"commits": [{"sha": self.head, "commit": {"verification": {
            "verified": True, "reason": "unknown_key"}}}]}]
        self.assert_failure(self.signatures())

    def test_main_ref_cli_failure_cannot_fall_back_to_ordinary_or_stale_scope(self):
        self.assert_success(self.resolve())
        self.api["main_error"] = True
        self.assert_failure(self.signatures())
        self.assertFalse((self.root / "pr-hygiene-scope.json").exists())
        self.assert_failure(self.bash(emdash_script()))

    def test_malformed_main_json_fails_closed(self):
        self.api["main_invalid_json"] = True
        self.assert_failure(self.resolve())

    def test_missing_main_field_fails_closed(self):
        self.api["main"] = {"ref": "refs/heads/main"}
        self.assert_failure(self.resolve())

    def test_wrong_returned_ref_fails_closed(self):
        self.api["main"]["ref"] = "refs/heads/attacker"
        self.assert_failure(self.resolve())

    def test_unknown_main_fetch_uses_only_trusted_https_url_and_fails_closed(self):
        self.api["main"]["object"]["sha"] = "f" * 40
        self.git("remote", "add", "origin", "https://attacker.invalid/untrusted.git")
        self.event["pull_request"]["head"]["repo"]["clone_url"] = "https://attacker.invalid/head.git"
        self.save_event()
        result = self.resolve()
        self.assert_failure(result)
        calls = [json.loads(line) for line in (self.root / "fetch-calls.jsonl").read_text().splitlines()]
        self.assertEqual([["fetch", "--no-tags", "--no-write-fetch-head",
                           "https://github.com/example/floci.git", "f" * 40]], calls)

    def test_real_git_transport_failure_fails_closed_without_network(self):
        self.api["main"]["object"]["sha"] = "f" * 40
        self.env.update(TEST_REAL_GIT_TRANSPORT_FAILURE="1", GIT_CONFIG_COUNT="1",
                        GIT_CONFIG_KEY_0="protocol.https.allow", GIT_CONFIG_VALUE_0="never")
        result = self.resolve()
        self.assert_failure(result)
        self.assertIn("transport 'https' not allowed", result.stdout)
        self.assertFalse((self.root / "pr-hygiene-scope.json").exists())

    def test_invalid_branch_ref_fails_closed_before_api(self):
        self.event["pull_request"]["head"]["ref"] = "dev_target invalid"
        self.save_event()
        self.assert_failure(self.resolve())
        self.assertEqual([], self.calls())

    def test_main_nonancestor_fails_closed(self):
        self.api["main"]["object"]["sha"] = self.commit("fix: later main", self.main)
        self.assert_failure(self.resolve())

    def test_dev_base_nonancestor_fails_closed(self):
        other = self.commit("fix: later dev", self.base)
        self.event["pull_request"]["base"]["sha"] = other
        self.env["BASE_SHA"] = other
        self.save_event()
        self.assert_failure(self.resolve())

    def test_event_cannot_supply_accepted_main_or_sha_mismatch(self):
        self.event["acceptedMain"] = self.head
        self.event["pull_request"]["acceptedMain"] = self.head
        self.save_event()
        self.assert_failure(self.signatures(verified=False))
        self.event["pull_request"]["head"]["sha"] = self.main
        self.save_event()
        self.assert_failure(self.resolve())

    def test_malformed_repository_cannot_control_api_endpoint(self):
        self.env["REPO"] = "example/floci/../../attacker"
        self.assert_failure(self.resolve())
        self.assertEqual([], self.calls())

    def test_malformed_event_sha_fails_before_cli_lookup(self):
        self.event["pull_request"]["head"]["sha"] = "HEAD; arbitrary-command"
        self.save_event()
        self.assert_failure(self.resolve())
        self.assertEqual([], self.calls())

    def test_matching_name_with_wrong_repository_id_fails(self):
        self.event["pull_request"]["head"]["repo"]["id"] = 456
        self.save_event()
        self.assert_failure(self.resolve())

    def test_ordinary_main_target_retains_full_signature_scope(self):
        self.write_event(base_ref="main")
        self.assert_failure(self.signatures())
        self.assertFalse(any("--hostname" in call for call in self.calls()))
        self.assertIn(self.main, self.signatures().stdout)

    def test_other_same_repo_dev_branch_retains_full_scope(self):
        self.write_event(head_ref="another_feature")
        self.assert_failure(self.signatures())
        self.assertFalse(any("--hostname" in call for call in self.calls()))

    def test_foreign_fork_named_dev_target_retains_full_scope(self):
        self.write_event(head_repository="attacker/floci")
        self.assert_failure(self.signatures())
        self.assertFalse(any("--hostname" in call for call in self.calls()))

    def test_inherited_main_new_file_and_existing_dev_lines_pass(self):
        self.assert_success(self.emdashes())

    def test_new_file_new_glyph_fails(self):
        self.head = self.commit("fix: new file", self.head, files={"new.txt": "new" + EM_DASH})
        self.write_event()
        self.assert_failure(self.emdashes())

    def test_new_glyph_in_modified_file_fails(self):
        self.head = self.commit("fix: changed file", self.head, files={"old.txt": "different" + EM_DASH})
        self.write_event()
        self.assert_failure(self.emdashes())

    def test_ordinary_modification_keeps_existing_added_file_behavior(self):
        self.head = self.commit("fix: changed file", self.base, files={"old.txt": "different" + EM_DASH})
        self.write_event(base_ref="main", head_ref="feature")
        self.assert_success(self.emdashes())

    def test_ordinary_new_file_still_fails(self):
        self.head = self.commit("fix: new file", self.base, files={"new.txt": "new" + EM_DASH})
        self.write_event(base_ref="main", head_ref="feature")
        self.assert_failure(self.emdashes())

    def test_merge_resolution_new_glyph_fails_without_new_ordinary_glyph(self):
        self.head = self.commit("Merge resolution", self.head, self.main,
                                files={"resolution.txt": "new" + EM_DASH})
        self.write_event()
        result = self.emdashes()
        self.assert_failure(result)
        self.assertIn("New em-dash provenance", result.stdout)
        self.assertNotIn("New em-dash text in commit", result.stdout)

    def test_duplicate_inherited_merge_line_fails(self):
        self.head = self.commit("Merge duplicate", self.head, self.main,
                                files={"old.txt": ("old" + EM_DASH + "\n") * 2})
        self.write_event()
        self.assert_failure(self.emdashes())

    def test_inherited_line_copied_to_different_path_fails(self):
        self.head = self.commit("Merge copied path", self.head, self.main,
                                files={"copy.txt": "old" + EM_DASH + "\n"})
        self.write_event()
        self.assert_failure(self.emdashes())

    def test_merge_cannot_normalize_package_names_outside_java_source(self):
        self.main = self.commit("chore: imported docs", self.main,
                                files={"docs.md": "io.github.hectorvent.floci" + EM_DASH})
        self.api["main"]["object"]["sha"] = self.main
        self.head = self.commit("Merge changed docs", self.head, self.main, tree_from=self.main,
                                files={"docs.md": "io.floci.aws" + EM_DASH})
        self.write_event()
        self.assert_failure(self.emdashes())

    def test_documented_java_package_migration_preserves_provenance(self):
        old = "src/main/java/io/github/hectorvent/floci/X.java"
        new = "src/main/java/io/floci/aws/X.java"
        self.main = self.commit("chore: imported source", self.main,
                                files={old: "io.github.hectorvent.floci" + EM_DASH})
        self.api["main"]["object"]["sha"] = self.main
        self.head = self.commit("Merge source migration", self.head, self.main, tree_from=self.main,
                                files={old: None, new: "io.floci.aws" + EM_DASH})
        self.write_event()
        self.assert_success(self.emdashes())

    def test_newline_unicode_path_is_checked(self):
        self.head = self.commit("fix: unusual path", self.head,
                                files={"unicode-λ\nname.txt": "new" + EM_DASH})
        self.write_event()
        self.assert_failure(self.emdashes())

    def test_plus_plus_content_is_not_mistaken_for_diff_header(self):
        self.head = self.commit("fix: plus prefix", self.head, files={"new.txt": "++" + EM_DASH})
        self.write_event()
        self.assert_failure(self.emdashes())

    def test_unrelated_path_containing_package_token_is_not_a_migration(self):
        old = "docs/copy/io/github/hectorvent/floci/X.java"
        new = "docs/copy/io/floci/aws/X.java"
        self.main = self.commit("chore: imported copy", self.main,
                                files={old: "legacy" + EM_DASH})
        self.api["main"]["object"]["sha"] = self.main
        self.head = self.commit("Merge copied path", self.head, self.main, tree_from=self.main,
                                files={old: None, new: "legacy" + EM_DASH})
        self.write_event()
        self.assert_failure(self.emdashes())

    def test_string_true_verification_is_not_boolean_verification(self):
        self.api["pages"] = [{"commits": [{"sha": self.head, "commit": {"verification": {
            "verified": "true", "reason": "valid"}}}]}]
        self.assert_failure(self.signatures())

    def test_github_cli_missing_fails_closed(self):
        (self.root / "tools" / "gh").write_text("#!/bin/sh\nexit 127\n")
        self.assert_failure(self.resolve())

    def test_binary_file_retains_existing_text_only_policy(self):
        self.head = self.commit("fix: binary", self.head,
                                files={"binary.dat": "binary\0" + EM_DASH})
        self.write_event()
        self.assert_success(self.emdashes())

    def test_scope_reused_without_second_mutable_main_lookup(self):
        self.assert_success(self.resolve())
        self.api["main_error"] = True
        self.write_api()
        self.assert_success(self.bash(emdash_script()))
        self.assertEqual(1, len([call for call in self.calls() if "--hostname" in call]))

    def test_changed_event_cannot_reuse_scope(self):
        self.assert_success(self.resolve())
        self.event["pull_request"]["head"]["ref"] = "other"
        self.save_event()
        self.assert_failure(self.bash(emdash_script()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
