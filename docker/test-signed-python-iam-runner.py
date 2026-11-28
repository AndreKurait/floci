#!/usr/bin/env python3
"""Execute the signed IAM workflow step without allocating Docker resources."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]


def workflow_script(workflow=None):
    if workflow is None:
        workflow = (ROOT / ".github/workflows/compatibility.yml").read_text()
    lines = workflow.splitlines()
    matches = [i for i, line in enumerate(lines)
               if line.strip() == "- name: Run signed Python SDK IAM permissions"]
    if len(matches) != 1:
        raise ValueError("Expected exactly one signed Python SDK IAM permissions step")
    start = matches[0]
    step_indent = len(lines[start]) - len(lines[start].lstrip())
    end = next((i for i in range(start + 1, len(lines))
                if lines[i].strip() and len(lines[i]) - len(lines[i].lstrip()) <= step_indent),
               len(lines))
    runs = [i for i in range(start + 1, end) if lines[i].strip() == "run: |"]
    if len(runs) != 1:
        raise ValueError("Expected exactly one literal run block in the signed IAM step")
    run = runs[0]
    run_indent = len(lines[run]) - len(lines[run].lstrip())
    block_end = next((i for i in range(run + 1, end)
                      if lines[i].strip() and len(lines[i]) - len(lines[i].lstrip()) <= run_indent),
                     end)
    block = textwrap.dedent("\n".join(lines[run + 1:block_end]))
    if not block.strip():
        raise ValueError("The signed IAM step must execute a script")
    return block + "\n"


class WorkflowExtractionTest(unittest.TestCase):
    def test_step_indentation_does_not_change_the_executed_script(self):
        for spaces in (4, 6):
            with self.subTest(spaces=spaces):
                workflow = textwrap.indent(
                    "- name: Run signed Python SDK IAM permissions\n"
                    "  if: matrix.test == 'sdk-test-python'\n"
                    "  run: |\n"
                    "    printf 'signed IAM\\n'\n"
                    "  shell: bash\n"
                    "- uses: example/action@revision\n",
                    " " * spaces,
                )
                self.assertEqual("printf 'signed IAM\\n'\n", workflow_script(workflow))

    def test_missing_or_duplicate_signed_step_fails(self):
        step = "- name: Run signed Python SDK IAM permissions\n  run: |\n    true\n"
        for workflow in ("jobs: {}\n", step + step):
            with self.subTest(workflow=workflow), self.assertRaises(ValueError):
                workflow_script(workflow)

    def test_missing_script_cannot_borrow_a_later_steps_script(self):
        workflow = (
            "- name: Run signed Python SDK IAM permissions\n"
            "  if: matrix.test == 'sdk-test-python'\n"
            "- name: Another step\n"
            "  run: |\n"
            "    true\n"
        )
        with self.assertRaises(ValueError):
            workflow_script(workflow)


class SignedPythonIamRunnerTest(unittest.TestCase):
    def execute(self, health="healthy", test_exit=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "test-results").mkdir()
            commands = root / "commands.jsonl"
            docker = root / "docker"
            docker.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "args = sys.argv[1:]\n"
                "with open(os.environ['COMMAND_LOG'], 'a') as log:\n"
                "    log.write(json.dumps(args) + '\\n')\n"
                "if args[0] == 'inspect':\n"
                "    print(os.environ['TEST_HEALTH'])\n"
                "if args[0] == 'run' and '--entrypoint' in args:\n"
                "    sys.exit(int(os.environ['TEST_EXIT']))\n"
            )
            docker.chmod(0o700)
            stat = root / "stat"
            stat.write_text("#!/bin/sh\nprintf '987\\n'\n")
            stat.chmod(0o700)
            result = subprocess.run(
                ["bash", "-e", "-o", "pipefail", "-c", workflow_script()],
                cwd=root,
                env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"],
                         COMMAND_LOG=str(commands), TEST_HEALTH=health,
                         TEST_EXIT=str(test_exit)),
                capture_output=True, text=True, timeout=10,
            )
            calls = [json.loads(line) for line in commands.read_text().splitlines()]
            return result, calls

    def start_arguments(self):
        result, calls = self.execute()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return next(call for call in calls if call[:2] == ["run", "-d"])

    def test_server_receives_docker_socket(self):
        args = self.start_arguments()
        self.assertIn("/var/run/docker.sock:/var/run/docker.sock", args)
        self.assertEqual("-v", args[args.index("/var/run/docker.sock:/var/run/docker.sock") - 1])

    def test_server_receives_actual_socket_group(self):
        args = self.start_arguments()
        self.assertIn("--group-add", args)
        self.assertEqual("987", args[args.index("--group-add") + 1])

    def test_lambda_uses_server_network(self):
        args = self.start_arguments()
        self.assertEqual("compat-net", args[args.index("--network") + 1])
        self.assertIn("FLOCI_SERVICES_DOCKER_NETWORK=compat-net", args)

    def test_signed_suite_and_native_server_remain_enabled(self):
        result, calls = self.execute()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        server = next(call for call in calls if call[:2] == ["run", "-d"])
        self.assertEqual("floci:test-native", server[-1])
        self.assertIn("FLOCI_AUTH_VALIDATE_SIGNATURES=true", server)
        self.assertIn("FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true", server)
        suite = next(call for call in calls if "--entrypoint" in call)
        self.assertEqual(
            ["pytest", "compat-sdk-test-python", "iam/", "-v", "--junit-xml=/results/iam-junit.xml"],
            suite[suite.index("--entrypoint") + 1:],
        )

    def test_server_enforces_member_account_scps(self):
        self.assertIn("FLOCI_SERVICES_ORGANIZATIONS_SCP_ENFORCEMENT_ENABLED=true", self.start_arguments())

    def test_unhealthy_server_prevents_suite_execution(self):
        result, calls = self.execute(health="unhealthy")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("IAM-enabled floci did not report healthy", result.stdout)
        self.assertFalse(any("--entrypoint" in call for call in calls))

    def test_permission_suite_failure_propagates(self):
        result, calls = self.execute(test_exit=7)
        self.assertEqual(7, result.returncode)
        self.assertTrue(any("--entrypoint" in call for call in calls))


if __name__ == "__main__":
    unittest.main()
