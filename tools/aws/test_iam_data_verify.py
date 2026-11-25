"""Exercise IAM gate selection without contacting the live AWS reference."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class IamDataVerifyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.calls = self.directory / "calls"
        self.make = self.directory / "make"
        self.make.write_text(
            '#!/bin/sh\nprintf "%s\\n" "$*" >> "$CALLS"\n'
            '[ "$*" != "--no-print-directory $FAIL_TARGET" ]\n'
        )
        self.make.chmod(0o700)

    def run_gate(self, *arguments, fail_target=""):
        result = subprocess.run(
            ["make", "--no-print-directory", "iam-data-verify", f"MAKE={self.make}", *arguments],
            cwd=Path(__file__).resolve().parents[2],
            env={**os.environ, "CALLS": str(self.calls), "FAIL_TARGET": fail_target},
            text=True, capture_output=True,
        )
        calls = self.calls.read_text().splitlines() if self.calls.exists() else []
        return result, [line.split()[-1] for line in calls]

    def changed_file(self, text):
        path = self.directory / "changed"
        path.write_text(text)
        return f"CHANGED_FILE={path}"

    def test_large_diff_selects_only_affected_check(self):
        paths = "".join(f"src/test/unrelated/path_{index}.java\n" for index in range(10000))
        paths += "src/main/resources/aws/iam-service-namespaces.json\n"
        self.assertGreater(len(paths), 131072)
        result, calls = self.run_gate(self.changed_file(paths))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["iam-namespaces-verify"], calls)

    def test_unrelated_diff_runs_no_checks(self):
        result, calls = self.run_gate(self.changed_file("docs/index.md\n"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], calls)

    def test_empty_diff_keeps_full_verification(self):
        result, calls = self.run_gate(self.changed_file(""))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(12, len(calls))

    def test_weekly_run_checks_every_file(self):
        result, calls = self.run_gate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(12, len(calls))

    def test_legacy_changed_argument_still_selects(self):
        result, calls = self.run_gate("CHANGED=  tools/aws/regen_service_namespaces.py  ")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["iam-namespaces-verify"], calls)

    def test_missing_file_fails_without_silently_skipping(self):
        result, calls = self.run_gate(f"CHANGED_FILE={self.directory / 'missing'}")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)

    def test_failed_check_does_not_hide_later_checks(self):
        result, calls = self.run_gate(self.changed_file(
            "tools/aws/regen_service_namespaces.py\ntools/aws/regen_iam_action_resources.py\n"
        ), fail_target="iam-namespaces-verify")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["iam-namespaces-verify", "iam-action-resources-verify"], calls)
        self.assertIn("the service namespace list", result.stdout)


ROOT = Path(__file__).resolve().parents[2]


class IamDataVerifyManifestTest(unittest.TestCase):
    def run_gate(self, contents=None, legacy=None, missing=False, fail_target=""):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            calls = root / "calls"
            stub = root / "verify.py"
            stub.write_text(
                "import os, pathlib, sys\n"
                "with pathlib.Path(os.environ['VERIFY_CALLS']).open('a') as f: "
                "f.write(sys.argv[-1] + '\\n')\n"
                "sys.exit(1 if sys.argv[-1] == os.environ['FAIL_TARGET'] else 0)\n"
            )
            command = ["make", "--no-print-directory", "iam-data-verify",
                       "MAKE=python3 " + str(stub)]
            if contents is not None or missing:
                manifest = root / "changed paths.txt"
                if not missing:
                    manifest.write_text(contents)
                command.append("CHANGED_FILE=" + str(manifest))
            if legacy is not None:
                command.append("CHANGED=" + legacy)
            environment = dict(os.environ, VERIFY_CALLS=str(calls), FAIL_TARGET=fail_target)
            result = subprocess.run(command, cwd=ROOT, env=environment,
                                    capture_output=True, text=True, timeout=30)
            return result, calls.read_text().splitlines() if calls.exists() else []

    def test_large_manifest_selects_only_covered_verifiers(self):
        paths = "".join(f"src/main/java/unrelated/package/Service{index}.java\n"
                        for index in range(12000))
        result, calls = self.run_gate(paths + "src/main/resources/aws/iam-service-namespaces.json\n")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["iam-namespaces-verify"], calls)

    def test_empty_manifest_and_default_run_every_verifier(self):
        default, expected = self.run_gate()
        empty, actual = self.run_gate("")
        blank, blank_calls = self.run_gate("\n  \n")
        self.assertEqual(0, blank.returncode, blank.stdout)
        self.assertEqual(expected, blank_calls)
        self.assertEqual(0, default.returncode, default.stdout)
        self.assertEqual(0, empty.returncode, empty.stdout)
        self.assertEqual(12, len(expected))
        self.assertIn("documented-routes-verify", expected)
        self.assertEqual(expected, actual)

    def test_missing_manifest_fails_before_skipping_checks(self):
        result, calls = self.run_gate(missing=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("cannot read changed-file manifest", result.stdout)
        self.assertEqual([], calls)

    def test_unrelated_paths_do_not_run_verifiers(self):
        result, calls = self.run_gate("docs/example.md\n")
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual([], calls)

    def test_legacy_list_and_manifest_select_the_same_checks(self):
        paths = ["src/main/resources/aws/iam-service-namespaces.json",
                 "src/main/resources/aws/iam-action-resources.json"]
        legacy, expected = self.run_gate(legacy=" ".join(paths))
        manifest, actual = self.run_gate("\n".join(paths))
        self.assertEqual(0, legacy.returncode, legacy.stdout)
        self.assertEqual(0, manifest.returncode, manifest.stdout)
        self.assertEqual(["iam-namespaces-verify", "iam-action-resources-verify"], expected)
        self.assertEqual(expected, actual)

    def test_failure_does_not_hide_later_verifier_results(self):
        result, calls = self.run_gate("", fail_target="iam-namespaces-verify")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(12, len(calls))
        self.assertIn("documented-routes-verify", calls)
        self.assertIn("the service namespace list, see its message above", result.stdout)


if __name__ == "__main__":
    unittest.main()
