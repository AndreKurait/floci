"""Cleanup regressions without AWS or a running Floci process."""
import importlib.util
import os
from pathlib import Path
import socket
import sys
import types
import unittest
from unittest.mock import Mock, patch
from urllib.request import urlopen

source = Path(os.environ.get("BENCHMARK_SOURCE", Path(__file__).with_name("reconciliation_benchmark.py")))
spec = importlib.util.spec_from_file_location("benchmark", source)
benchmark = importlib.util.module_from_spec(spec)
# SDK construction is mocked; the loopback HTTP servers below are real.
with patch.dict(sys.modules, {"boto3": types.SimpleNamespace(),
                              "botocore": types.ModuleType("botocore"),
                              "botocore.config": types.SimpleNamespace(Config=Mock())}):
    spec.loader.exec_module(benchmark)


class BenchmarkCleanupTest(unittest.TestCase):
    def clients(self):
        asg, ec2 = Mock(), Mock()
        asg.describe_auto_scaling_groups.return_value = {
            "AutoScalingGroups": [{"Instances": [{"InstanceId": "i-owned", "LifecycleState": "InService"}]}]}
        ec2.describe_instances.return_value = {"Reservations": [{"Instances": [{"State": {"Name": "terminated"}}]}]}
        return asg, ec2

    def test_success_retains_completion_measurements(self):
        asg, ec2 = self.clients()
        with patch.object(benchmark, "client", side_effect=[asg, ec2]):
            result = benchmark.asg_sample("http://127.0.0.1:1")
        self.assertGreaterEqual(result["in_service_seconds"], result["api_seconds"])
        asg.delete_auto_scaling_group.assert_called_once()
        asg.delete_launch_configuration.assert_called_once()
        ec2.describe_instances.assert_called_once_with(InstanceIds=["i-owned"])

    def test_group_creation_failure_cleans_only_created_configuration(self):
        asg, ec2 = self.clients()
        failure = RuntimeError("group creation failed")
        asg.create_auto_scaling_group.side_effect = failure
        with patch.object(benchmark, "client", side_effect=[asg, ec2]), self.assertRaises(RuntimeError) as raised:
            benchmark.asg_sample("http://127.0.0.1:1")
        self.assertIs(failure, raised.exception)
        asg.delete_auto_scaling_group.assert_not_called()
        asg.delete_launch_configuration.assert_called_once()
        asg.close.assert_called_once()
        ec2.close.assert_called_once()

    def test_configuration_creation_failure_does_not_delete_unowned_names(self):
        asg, ec2 = self.clients()
        asg.create_launch_configuration.side_effect = RuntimeError("not created")
        with patch.object(benchmark, "client", side_effect=[asg, ec2]), self.assertRaises(RuntimeError):
            benchmark.asg_sample("http://127.0.0.1:1")
        asg.delete_auto_scaling_group.assert_not_called()
        asg.delete_launch_configuration.assert_not_called()
        asg.close.assert_called_once()
        ec2.close.assert_called_once()

    def test_delete_failure_does_not_skip_other_cleanup_or_lose_operation_cause(self):
        asg, ec2 = self.clients()
        operation = RuntimeError("set desired capacity failed")
        deletion = RuntimeError("group deletion failed")
        asg.set_desired_capacity.side_effect = operation
        asg.delete_auto_scaling_group.side_effect = deletion
        with patch.object(benchmark, "client", side_effect=[asg, ec2]), self.assertRaises(RuntimeError) as raised:
            benchmark.asg_sample("http://127.0.0.1:1")
        self.assertIs(deletion, raised.exception)
        self.assertIs(operation, raised.exception.__context__)
        asg.delete_launch_configuration.assert_called_once()
        asg.close.assert_called_once()
        ec2.close.assert_called_once()

    def test_second_client_failure_closes_first_client(self):
        asg = Mock()
        with patch.object(benchmark, "client", side_effect=[asg, RuntimeError("client setup")]), self.assertRaises(RuntimeError):
            benchmark.asg_sample("http://127.0.0.1:1")
        asg.close.assert_called_once()
        asg.create_launch_configuration.assert_not_called()

    def test_partial_server_setup_closes_prior_listener(self):
        servers = []
        factory = benchmark.http.server.ThreadingHTTPServer

        def create(*args):
            if servers:
                raise RuntimeError("second listener failed")
            server = factory(*args)
            servers.append(server)
            return server

        try:
            with patch.object(benchmark.http.server, "ThreadingHTTPServer", side_effect=create), self.assertRaises(RuntimeError):
                with benchmark.targets(2, 0):
                    self.fail("partial setup cannot yield")
            self.assertEqual(-1, servers[0].fileno())
        finally:
            # Also reap the deliberately leaking original-source negative control.
            for server in servers:
                if server.fileno() != -1:
                    server.shutdown()
                    server.server_close()

    def test_thread_start_failure_closes_new_listener(self):
        servers = []
        factory = benchmark.http.server.ThreadingHTTPServer

        def create(*args):
            server = factory(*args)
            servers.append(server)
            return server

        try:
            with patch.object(benchmark.http.server, "ThreadingHTTPServer", side_effect=create), \
                    patch.object(benchmark.threading.Thread, "start", side_effect=RuntimeError("thread start")), \
                    self.assertRaises(RuntimeError):
                with benchmark.targets(1, 0):
                    self.fail("failed start cannot yield")
            self.assertEqual(-1, servers[0].fileno())
        finally:
            for server in servers:
                server.server_close()

    def test_real_http_target_serves_and_releases_port(self):
        with benchmark.targets(1, 0) as servers:
            server = servers[0]
            port = server.server_port
            with urlopen(f"http://127.0.0.1:{port}/", timeout=2) as response:
                self.assertEqual(200, response.status)
        self.assertEqual(-1, server.fileno())
        with socket.socket() as probe:
            self.assertNotEqual(0, probe.connect_ex(("127.0.0.1", port)))


if __name__ == "__main__":
    unittest.main()
