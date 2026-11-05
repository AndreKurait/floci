"""Readiness checks must survive registration latency without hiding API errors."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import Mock


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


if __name__ == "__main__":
    unittest.main()
