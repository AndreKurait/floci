import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("bootstrap-maven.sh")


class MavenBootstrapTest(unittest.TestCase):
    def run_bootstrap(self, failures, exit_code=7):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            wrapper = root / "mvnw"
            wrapper.write_text("""#!/usr/bin/env bash
set -eu
[[ "$*" == "--version" ]]
[[ "$MVNW_VERBOSE" == true ]]
count=0
[[ ! -f calls ]] || count=$(cat calls)
count=$((count + 1))
echo "$count" > calls
if (( count <= FAILURES )); then
    exit "$EXIT_CODE"
fi
echo "Apache Maven fixture"
""")
            wrapper.chmod(0o755)
            tools = root / "tools"
            tools.mkdir()
            sleep = tools / "sleep"
            sleep.write_text('#!/bin/sh\necho "$*" >> sleeps\n')
            sleep.chmod(0o755)
            env = dict(os.environ, PATH=f"{tools}:{os.environ['PATH']}",
                       FAILURES=str(failures), EXIT_CODE=str(exit_code))
            result = subprocess.run(["bash", str(SCRIPT)], cwd=root, env=env,
                                    text=True, capture_output=True, timeout=10)
            waits = (root / "sleeps").read_text().splitlines() if (root / "sleeps").exists() else []
            return result, int((root / "calls").read_text()), waits

    def test_success_does_not_retry(self):
        result, calls, waits = self.run_bootstrap(0)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, calls)
        self.assertEqual([], waits)

    def test_transient_bootstrap_failures_recover(self):
        result, calls, waits = self.run_bootstrap(2)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(3, calls)
        self.assertEqual(2, len(waits))
        self.assertIn("Apache Maven fixture", result.stdout)

    def test_persistent_failure_preserves_exit_status(self):
        result, calls, waits = self.run_bootstrap(10)
        self.assertEqual(7, result.returncode)
        self.assertEqual(3, calls)
        self.assertEqual(2, len(waits))
        self.assertIn("::error::", result.stdout)

    def test_timeout_status_is_not_treated_as_success(self):
        result, calls, waits = self.run_bootstrap(10, exit_code=124)
        self.assertEqual(124, result.returncode)
        self.assertEqual(3, calls)
        self.assertEqual(2, len(waits))


if __name__ == "__main__":
    unittest.main()
