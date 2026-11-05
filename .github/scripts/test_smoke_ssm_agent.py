"""Readiness checks must survive registration latency without hiding API errors."""
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import lambda_vpc_smoke


spec = importlib.util.spec_from_file_location(
    "smoke_ssm_agent", Path(__file__).with_name("smoke-ssm-agent.py")
)
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class AgentReadinessTest(unittest.TestCase):
    def setUp(self):
        self.instance = "i-0123456789abcdef0"
        self.node = {
            "InstanceId": self.instance, "AgentVersion": smoke.AGENT_VERSION, "PingStatus": "Online",
        }
        self.subject = smoke.Smoke.__new__(smoke.Smoke)
        self.subject.aws = Mock(return_value={"InstanceInformationList": [self.node]})

    def test_ready_requires_the_requested_instance(self):
        self.assertEqual(self.node, self.subject.registered_agent(self.instance))
        self.subject.aws.assert_called_once_with(
            "ssm", "describe-instance-information",
            Filters=[{"Key": "InstanceIds", "Values": [self.instance]}],
        )

    def test_unregistered_instance_is_not_ready(self):
        self.subject.aws.side_effect = subprocess.CalledProcessError(
            254, ["aws"], stderr="An error occurred (InvalidInstanceId) when calling "
            "the DescribeInstanceInformation operation: Instance is not registered.",
        )
        self.assertIsNone(self.subject.registered_agent(self.instance))

    def test_other_api_errors_propagate(self):
        for code in ("AccessDeniedException", "InvalidFilterKey", "ThrottlingException"):
            with self.subTest(code=code):
                error = subprocess.CalledProcessError(
                    254, ["aws"], stderr=f"An error occurred ({code}) when calling "
                    "the DescribeInstanceInformation operation: failed",
                )
                self.subject.aws.side_effect = error
                with self.assertRaises(subprocess.CalledProcessError) as observed:
                    self.subject.registered_agent(self.instance)
                self.assertIs(error, observed.exception)

    def test_empty_inventory_is_not_ready(self):
        self.subject.aws.return_value = {"InstanceInformationList": []}
        self.assertIsNone(self.subject.registered_agent(self.instance))

    def test_offline_agent_is_not_ready(self):
        self.node["PingStatus"] = "ConnectionLost"
        self.assertIsNone(self.subject.registered_agent(self.instance))

    def test_foreign_instance_does_not_satisfy_readiness(self):
        self.node["InstanceId"] = "i-11111111111111111"
        with self.assertRaises(ValueError):
            self.subject.registered_agent(self.instance)

    def test_multiple_nodes_do_not_satisfy_readiness(self):
        self.subject.aws.return_value = {"InstanceInformationList": [self.node, self.node]}
        with self.assertRaises(ValueError):
            self.subject.registered_agent(self.instance)

    def test_unexpected_agent_version_is_rejected(self):
        self.node["AgentVersion"] = "0.0.0.0"
        with self.assertRaises(ValueError):
            self.subject.registered_agent(self.instance)


class NativeImageCliTest(unittest.TestCase):
    def setUp(self):
        self.subject = smoke.Smoke.__new__(smoke.Smoke)
        self.subject.cli = "owned-cli-fixture"

    def test_signed_calls_use_the_separate_fixture_and_local_credentials(self):
        with patch.object(smoke, "docker", return_value=Mock(stdout='{"Account":"000000000000"}')) as run:
            self.assertEqual({"Account": "000000000000"}, self.subject.aws("sts", "get-caller-identity"))
        arguments = run.call_args.args
        self.assertIn("owned-cli-fixture", arguments)
        for value in (
            "AWS_ACCESS_KEY_ID=test", "AWS_SECRET_ACCESS_KEY=test", "AWS_SESSION_TOKEN=",
            "AWS_EC2_METADATA_DISABLED=true", "AWS_CONFIG_FILE=/dev/null",
            "AWS_SHARED_CREDENTIALS_FILE=/dev/null", "AWS_CA_BUNDLE=/floci-root-ca.crt",
            "https://localhost:4566",
        ):
            self.assertIn(value, arguments)
        self.assertNotIn("--no-verify-ssl", arguments)

    def test_cli_failure_cannot_be_reported_as_an_empty_success(self):
        error = subprocess.CalledProcessError(254, ["aws"], stderr="AccessDenied")
        with patch.object(smoke, "docker", side_effect=error):
            with self.assertRaises(subprocess.CalledProcessError) as observed:
                self.subject.aws("iam", "create-role", RoleName="example")
        self.assertIs(error, observed.exception)



class AllocationCleanupTest(unittest.TestCase):
    def test_accepted_stack_with_lost_create_response_is_deleted_by_name(self):
        self.check_stack_cleanup("create-stack", False)

    def test_stack_wait_failure_still_deletes_by_known_name(self):
        self.check_stack_cleanup("wait", False)

    def test_cleanup_failure_preserves_the_original_create_error(self):
        self.check_stack_cleanup("create-stack", True)

    def check_stack_cleanup(self, failing_operation, cleanup_fails):
        with tempfile.TemporaryDirectory() as directory:
            subject = Mock(owner="owned-fixture", output=Path(directory), record={})
            primary = RuntimeError("accepted request lost its response")
            deleted = []

            def aws(service, operation, *arguments, **parameters):
                if operation == "delete-stack":
                    deleted.append(parameters["StackName"])
                    if cleanup_fails:
                        raise RuntimeError("cleanup unavailable")
                elif operation == "describe-stack-events":
                    return {"StackEvents": []}
                elif operation == failing_operation and arguments != ("stack-delete-complete",):
                    raise primary
                return {"StackId": "returned-stack-id"}

            subject.aws.side_effect = aws
            with patch.object(lambda_vpc_smoke, "template", return_value={}):
                with self.assertRaises(RuntimeError) as observed:
                    lambda_vpc_smoke.exercise_lambda_vpc(subject, Mock())
            self.assertIs(primary, observed.exception)
            self.assertEqual(["owned-fixture-lambda"], deleted)
            result = subject.record["lambda_vpc"]
            self.assertEqual(not cleanup_fails, result["stack_deleted"])
            if cleanup_fails:
                self.assertIn("cleanup unavailable", result["cleanup_error"])
                self.assertIn("cleanup unavailable", primary.__notes__[0])

    def test_build_log_failure_still_removes_the_created_guest_image(self):
        with tempfile.TemporaryDirectory() as directory:
            subject = smoke.Smoke.__new__(smoke.Smoke)
            subject.owner = "owned-fixture"
            subject.image = subject.helper = "example@sha256:digest"
            subject.work = Path(directory)
            subject.output = Mock()
            subject.output.__truediv__ = Mock(return_value=Mock())
            subject.output.__truediv__.return_value.write_text.side_effect = OSError("disk full")
            subject.guest_image = "owned-fixture-guest"
            subject.guest = None
            subject.cli, subject.server, subject.network = "cli", "server", "network"
            subject.created = {}
            subject.record = {"cleanup_errors": []}
            subject.before, subject.daemon = {}, "daemon"
            package = b"test package"
            response = Mock()
            response.__enter__ = Mock(return_value=Mock(read=Mock(return_value=package)))
            response.__exit__ = Mock(return_value=False)

            def docker(*arguments, **options):
                if arguments[:2] == ("info", "--format"):
                    return Mock(stdout="amd64" if arguments[2] == "{{.Architecture}}" else "daemon")
                return Mock(stdout="", stderr="", returncode=1 if "inspect" in arguments else 0)

            with patch.object(smoke, "docker", side_effect=docker) as commands, \
                    patch.object(smoke, "footprint", return_value={}), \
                    patch.object(smoke.os, "environ", {"DOCKER_HOST": "unix:///test.sock"}), \
                    patch.object(smoke, "AGENT_SHA256", hashlib.sha256(package).hexdigest()), \
                    patch.object(smoke.urllib.request, "urlopen", return_value=response):
                try:
                    with self.assertRaisesRegex(OSError, "disk full"):
                        subject.prepare()
                finally:
                    subject.cleanup()
                commands.assert_any_call("image", "rm", "owned-fixture-guest")
            self.assertTrue(subject.record["cleanup_passed"])

if __name__ == "__main__":
    unittest.main()
