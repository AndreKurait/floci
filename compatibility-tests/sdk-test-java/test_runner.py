"""Exercise the SDK container entry point with real child processes."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest


RUNNER = Path(__file__).with_name("run-tests.sh")


class RunnerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.results = self.root / "result files"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.env = {
            **os.environ,
            "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "SDK_TEST_RESULTS_DIR": str(self.results),
        }
        self.env.pop("SDK_TEST_TIMEOUT_SECONDS", None)

    def maven(self, body):
        path = self.bin / "mvn"
        path.write_text("#!" + sys.executable + "\n" + textwrap.dedent(body))
        path.chmod(0o755)

    def run_runner(self, *args, timeout=30):
        with subprocess.Popen(
            ["bash", str(RUNNER), *args], cwd=self.root, env=self.env,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True,
        ) as process:
            try:
                stdout, stderr = process.communicate(timeout=timeout)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
                raise
            return subprocess.CompletedProcess(process.args, process.returncode, stdout, stderr)

    def test_success_preserves_arguments_and_streams_progress_and_reports(self):
        self.maven("""
            import json,sys
            from pathlib import Path
            Path("args.json").write_text(json.dumps(sys.argv[1:]))
            reports = Path("target/surefire-reports")
            reports.mkdir(parents=True)
            (reports / "TEST-example.xml").write_text('<testsuite tests="1"/>')
            print("Running example.Test", flush=True)
            print("test diagnostic", file=sys.stderr, flush=True)
        """)
        result = self.run_runner("-Dtest=ExampleTest", "-Dmessage=two words")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["-B", "-ntp", "test", "-Dtest=ExampleTest", "-Dmessage=two words"],
                         json.loads((self.root / "args.json").read_text()))
        self.assertEqual('<testsuite tests="1"/>', (self.results / "TEST-example.xml").read_text())
        self.assertIn("Running example.Test", result.stdout)
        self.assertIn("test diagnostic", result.stdout)
        self.assertEqual(result.stdout, (self.results / "maven.log").read_text())

    def test_failed_maven_retains_its_exit_code_and_surefire_diagnostics(self):
        self.maven("""
            from pathlib import Path
            import sys
            reports = Path("target/surefire-reports")
            reports.mkdir(parents=True)
            for name in ("TEST-example.xml", "example.txt", "example.dump", "example.dumpstream"):
                (reports / name).write_text(name)
            print("failed assertion", flush=True)
            sys.exit(7)
        """)
        result = self.run_runner()
        self.assertEqual(7, result.returncode, result.stderr)
        for name in ("TEST-example.xml", "example.txt", "example.dump", "example.dumpstream"):
            self.assertEqual(name, (self.results / name).read_text())
        self.assertIn("failed assertion", (self.results / "maven.log").read_text())

    def test_deadline_stops_maven_and_its_child_and_keeps_completed_reports(self):
        self.env["SDK_TEST_TIMEOUT_SECONDS"] = "2"
        self.maven("""
            from pathlib import Path
            import signal,subprocess,sys,time
            reports = Path("target/surefire-reports")
            reports.mkdir(parents=True)
            (reports / "TEST-completed.xml").write_text('<testsuite tests="1"/>')
            child = subprocess.Popen([sys.executable, "-c",
                "import signal,sys,time; from pathlib import Path; "
                "signal.signal(signal.SIGTERM, lambda *_: "
                "(Path('child-stopped').write_text('yes'), sys.exit(0))); "
                "Path('child-started').touch(); time.sleep(60)"])
            def stop(*_):
                child.wait(timeout=3)
                Path("maven-stopped").touch()
                sys.exit(143)
            signal.signal(signal.SIGTERM, stop)
            print("Running blocked.Test", flush=True)
            time.sleep(60)
        """)
        started = time.monotonic()
        result = self.run_runner(timeout=10)
        self.assertEqual(124, result.returncode, result.stdout + result.stderr)
        self.assertLess(time.monotonic() - started, 8)
        self.assertTrue((self.root / "child-started").exists())
        self.assertTrue((self.root / "child-stopped").exists())
        self.assertTrue((self.root / "maven-stopped").exists())
        self.assertTrue((self.results / "TEST-completed.xml").exists())
        self.assertIn("Running blocked.Test", (self.results / "maven.log").read_text())

    def test_deadline_kills_a_process_that_ignores_termination(self):
        self.env["SDK_TEST_TIMEOUT_SECONDS"] = "1"
        self.maven("""
            from pathlib import Path
            import signal,time
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            reports = Path("target/surefire-reports")
            reports.mkdir(parents=True)
            (reports / "TEST-completed.xml").write_text('<testsuite tests="1"/>')
            print("Ignoring TERM", flush=True)
            time.sleep(60)
        """)
        started = time.monotonic()
        result = self.run_runner(timeout=25)
        self.assertEqual(137, result.returncode, result.stdout + result.stderr)
        self.assertLess(time.monotonic() - started, 23)
        self.assertTrue((self.results / "TEST-completed.xml").exists())
        self.assertIn("Ignoring TERM", (self.results / "maven.log").read_text())

    def test_invalid_deadline_never_launches_maven(self):
        self.maven('from pathlib import Path\nPath("started").touch()\n')
        for value in ("0", "-1", "2.5", "2 seconds", "invalid"):
            with self.subTest(value=value):
                self.env["SDK_TEST_TIMEOUT_SECONDS"] = value
                result = self.run_runner()
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("positive integer", result.stderr)
                self.assertFalse((self.root / "started").exists())

    def test_report_copy_failure_cannot_turn_into_a_passing_result(self):
        self.results.mkdir()
        (self.results / "TEST-example.xml").mkdir()
        self.maven("""
            from pathlib import Path
            reports = Path("target/surefire-reports")
            reports.mkdir(parents=True)
            (reports / "TEST-example.xml").write_text('<testsuite tests="1"/>')
        """)
        result = self.run_runner()
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("Could not collect", result.stderr)

    def test_failure_before_any_report_still_retains_console_output(self):
        self.maven('import sys\nprint("Maven could not start tests", flush=True)\nsys.exit(9)\n')
        result = self.run_runner()
        self.assertEqual(9, result.returncode, result.stderr)
        self.assertIn("Maven could not start tests", (self.results / "maven.log").read_text())
        self.assertEqual([], list(self.results.glob("*.xml")))


if __name__ == "__main__":
    unittest.main()
