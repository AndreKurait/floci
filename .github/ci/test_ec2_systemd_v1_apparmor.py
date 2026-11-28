"""Exercise ownership and independent failure handling in native CI cleanup."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "native_apparmor", Path(__file__).with_name("ec2-systemd-v1-apparmor.py"))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class NativeCleanupTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name)
        self.calls = []
        self.remaining = ["first", "second"]
        self.fault = None
        self.addCleanup(patch.stopall)
        patch.object(driver, "docker", self.docker).start()
        patch.object(driver, "diagnostics").start()
        patch.object(driver, "containers", lambda namespace: self.remaining).start()

    def docker(self, *args, **kwargs):
        self.calls.append(args)
        if args == ("rm", "--force", "first") and self.fault == "container":
            raise subprocess.CalledProcessError(1, args, stderr="container removal failed")
        if args[0] == "inspect":
            labels = {"io.floci.ci.run": "owned", "floci_namespace": "owned"}
            if args[1] == "first" and self.fault == "container-owner":
                labels["floci_namespace"] = "foreign"
            value = [{"Config": {"Labels": labels}}]
        elif args[:2] == ("volume", "ls"):
            return subprocess.CompletedProcess(args, 0, "first-volume\nsecond-volume\n", "")
        elif args[:2] == ("volume", "inspect"):
            labels = {"floci_namespace": "owned"}
            if args[2] == "first-volume" and self.fault == "volume-owner":
                labels["floci_namespace"] = "foreign"
            value = [{"Labels": labels, "Driver": "local", "Options": {}}]
        elif args[0] == "run" and self.fault == "nonempty-volume" and any(
                "source=first-volume," in item for item in args):
            raise subprocess.CalledProcessError(1, args, stderr="volume contains data")
        elif args[:2] == ("network", "inspect"):
            value = [{"Labels": {"io.floci.ci.run": "foreign" if self.fault == "network-owner" else "owned"}}]
        else:
            value = []
        return subprocess.CompletedProcess(args, 0, json.dumps(value), "")

    def cleanup(self, record):
        driver.cleanup_case("owned", "server", "network", "guest", self.output, record)

    def test_failed_container_removal_does_not_skip_other_resources(self):
        self.fault = "container"
        record = {"status": "failed", "traceback": "original boot failure"}
        self.cleanup(record)
        self.assertFalse(record["cleanup_passed"])
        self.assertEqual("original boot failure", record["traceback"])
        self.assertIn(("rm", "--force", "second"), self.calls)
        self.assertIn(("volume", "rm", "second-volume"), self.calls)
        self.assertIn(("network", "rm", "network"), self.calls)
        self.assertEqual("remove container first", record["cleanup_errors"][0]["operation"])

    def test_failed_run_can_clean_up_its_remaining_resources(self):
        record = {"status": "failed"}
        self.cleanup(record)
        self.assertEqual("failed", record["status"])
        self.assertTrue(record["cleanup_passed"])

    def test_passed_run_rejects_leftovers_after_attempting_all_cleanup(self):
        record = {"status": "passed"}
        with self.assertRaises(AssertionError):
            self.cleanup(record)
        self.assertEqual("failed", record["status"])
        self.assertFalse(record["cleanup_passed"])
        self.assertIn(("network", "rm", "network"), self.calls)

    def test_empty_successful_run_stays_passed(self):
        self.remaining = []
        record = {"status": "passed"}
        self.cleanup(record)
        self.assertEqual("passed", record["status"])
        self.assertTrue(record["cleanup_passed"])

    def test_ownership_and_volume_contents_are_not_bypassed(self):
        for fault, forbidden in (
            ("container-owner", ("rm", "--force", "first")),
            ("volume-owner", ("volume", "rm", "first-volume")),
            ("nonempty-volume", ("volume", "rm", "first-volume")),
            ("network-owner", ("network", "rm", "network")),
        ):
            with self.subTest(fault=fault):
                self.fault = fault
                self.calls.clear()
                record = {"status": "failed"}
                self.cleanup(record)
                self.assertFalse(record["cleanup_passed"])
                self.assertNotIn(forbidden, self.calls)
                self.assertIn(("rm", "--force", "second"), self.calls)
                self.assertIn(("volume", "rm", "second-volume"), self.calls)


if __name__ == "__main__":
    unittest.main()
