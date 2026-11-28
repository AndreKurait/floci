import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
import urllib.request
import zipfile

SPEC = importlib.util.spec_from_file_location(
    "cache_delivery", Path(__file__).with_name("deliver-floci-cache.py"))
delivery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(delivery)
SOURCE = "a" * 40


class Store:
    def __init__(self):
        self.objects, self.attempts, self.reads = {}, [], []
        self.writes = 0
        self.error = None

    def put(self, key, raw):
        self.attempts.append(key)
        if self.error:
            raise delivery.S3Error(self.error)
        if key in self.objects:
            raise delivery.S3Error("PreconditionFailed")
        self.writes += 1
        self.objects[key] = raw, "version-" + str(self.writes)
        return self.objects[key][1]

    def read(self, key, version):
        self.reads.append((key, version))
        value = self.objects[key]
        if version is not None and version != value[1]:
            raise ValueError("Wrong original version")
        return value


class DeliveryTests(unittest.TestCase):
    def setUp(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("floci-image-provenance.json", json.dumps({
                "source": {"repository": delivery.REPOSITORY, "sha": SOURCE},
                "workflow": {"runId": "123", "attempt": "2"}}))
        self.raw = buffer.getvalue()
        self.run = {"id": 123, "run_attempt": 2, "head_sha": SOURCE,
                    "head_branch": "dev", "repository": {"full_name": delivery.REPOSITORY},
                    "path": ".github/workflows/fork-dev-image.yml", "status": "in_progress"}
        self.artifact = {"id": 456, "name": "floci-image-provenance-test", "expired": False,
                         "digest": "sha256:" + hashlib.sha256(self.raw).hexdigest(),
                         "workflow_run": {"id": 123, "head_sha": SOURCE}}
        self.page = {"total_count": 1, "artifacts": [self.artifact]}
        self.calls = []
        self.store = Store()

    def api(self, path, *, binary=False):
        self.calls.append((path, binary))
        return copy.deepcopy({
            "actions/runs/123": self.run,
            "actions/runs/123/artifacts?per_page=100": self.page,
            "actions/artifacts/456/zip": self.raw,
        }[path])

    def deliver(self):
        return delivery.deliver(123, 2, SOURCE, api=self.api, store=self.store)

    def test_original_archive_is_unchanged_and_versioned_request_is_last(self):
        result = self.deliver()
        request = result["request"]
        sha = hashlib.sha256(self.raw).hexdigest()
        prefix = f"fleet-releases/floci-ingress/v1/123/a2/{sha}/"
        self.assertEqual(request, {
            "schemaVersion": 1, "repository": delivery.REPOSITORY,
            "runId": 123, "runAttempt": 2, "sourceRevision": SOURCE, "artifactId": 456,
            "archive": {"key": prefix + "provenance.zip", "versionId": "version-1", "sha256": sha}})
        self.assertEqual(self.store.objects[prefix + "provenance.zip"][0], self.raw)
        self.assertEqual(self.store.attempts, [prefix + "provenance.zip", prefix + "request.json"])
        self.assertEqual(self.store.objects[result["requestKey"]][0], delivery.canonical(request))
        self.assertFalse(result["workflowCompletionQualified"])
        self.assertFalse(result["cachePublicationQualified"])

    def test_same_original_delivery_reuses_bytes_and_original_archive_version(self):
        original = self.deliver()
        repeated = self.deliver()
        self.assertEqual(repeated, original)
        self.assertEqual(self.store.writes, 2)
        self.assertEqual(len(self.store.objects), 2)
        self.assertEqual(repeated["request"]["archive"]["versionId"], "version-1")
        self.assertEqual(self.store.reads[-2:], [
            (original["request"]["archive"]["key"], None), (original["requestKey"], None)])

    def test_existing_conflicting_archive_never_writes_request(self):
        sha = hashlib.sha256(self.raw).hexdigest()
        key = f"{delivery.PREFIX}/123/a2/{sha}/provenance.zip"
        self.store.objects[key] = b"different", "original-version"
        with self.assertRaisesRegex(ValueError, "different bytes"):
            self.deliver()
        self.assertEqual(self.store.attempts, [key])
        self.assertEqual(self.store.writes, 0)

    def test_request_cannot_rebind_to_a_different_archive_version(self):
        original = self.deliver()
        key = original["request"]["archive"]["key"]
        self.store.objects[key] = self.raw, "externally-replaced-version"
        with self.assertRaisesRegex(ValueError, "different bytes"):
            self.deliver()
        self.assertEqual(self.store.objects[original["requestKey"]][0],
                         delivery.canonical(original["request"]))
        self.assertEqual(self.store.writes, 2)

    def test_unknown_or_denied_write_is_not_repeated_or_followed_by_request(self):
        for code in ("UNKNOWN", "AccessDenied"):
            with self.subTest(code=code):
                self.store = Store()
                self.store.error = code
                with self.assertRaises(delivery.S3Error):
                    self.deliver()
                self.assertEqual(len(self.store.attempts), 1)
                self.assertFalse(self.store.reads)
                self.assertFalse(self.store.objects)

    def test_changed_workflow_or_incomplete_artifacts_never_write(self):
        for field, value in (("run_attempt", 3), ("head_sha", "b" * 40),
                             ("head_branch", "main"), ("repository", {"full_name": "other/floci"})):
            with self.subTest(field=field):
                prior = self.run[field]
                self.run[field] = value
                with self.assertRaisesRegex(ValueError, "identity changed"):
                    self.deliver()
                self.run[field] = prior
        self.page["total_count"] = 2
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            self.deliver()
        self.assertFalse(self.store.attempts)

    def test_artifact_identity_digest_and_internal_attempt_are_checked_before_writes(self):
        for change in ({"expired": True}, {"workflow_run": {"id": 124, "head_sha": SOURCE}},
                       {"digest": "sha256:" + "0" * 64}):
            with self.subTest(change=change):
                original = copy.deepcopy(self.artifact)
                self.artifact.update(change)
                with self.assertRaises(ValueError):
                    self.deliver()
                self.artifact.clear()
                self.artifact.update(original)
        changed = io.BytesIO()
        with zipfile.ZipFile(changed, "w") as archive:
            archive.writestr("floci-image-provenance.json", json.dumps({
                "source": {"repository": delivery.REPOSITORY, "sha": SOURCE},
                "workflow": {"runId": "123", "attempt": "1"}}))
        self.raw = changed.getvalue()
        self.artifact["digest"] = "sha256:" + hashlib.sha256(self.raw).hexdigest()
        with self.assertRaisesRegex(ValueError, "attempt differs"):
            self.deliver()
        self.assertFalse(self.store.attempts)

    def test_artifact_redirect_never_forwards_token_and_rejects_plain_http(self):
        request = urllib.request.Request("https://api.github.com/artifact",
                                         headers={"Authorization": "Bearer private-token"})
        handler = delivery.ArtifactRedirect()
        forwarded = handler.redirect_request(request, None, 302, "found", {},
                                             "https://artifact.example/signed")
        self.assertIsNone(forwarded.get_header("Authorization"))
        with self.assertRaisesRegex(ValueError, "Insecure"):
            handler.redirect_request(request, None, 302, "found", {}, "http://artifact.example/signed")

    def test_s3_reads_are_version_pinned_and_reject_large_objects_before_download(self):
        store = delivery.S3("example-bucket", "us-west-2")
        key = delivery.PREFIX + "/123/a2/hash/provenance.zip"
        seen = []

        def call(operation, actual_key, *args):
            seen.append((operation, actual_key, args))
            if operation == "head-object":
                return {"VersionId": "original", "ContentLength": 4}
            self.assertEqual(args[:-1], ("--version-id", "original"))
            Path(args[-1]).write_bytes(b"data")
            return {"VersionId": "original"}

        with patch.object(store, "call", side_effect=call):
            self.assertEqual(store.read(key, "original"), (b"data", "original"))
        self.assertEqual(seen[0][2], ("--version-id", "original"))
        with patch.object(store, "call", return_value={
                "VersionId": "original", "ContentLength": delivery.ARCHIVE_LIMIT + 1}) as mocked:
            with self.assertRaisesRegex(ValueError, "size bound"):
                store.read(key)
            self.assertEqual(mocked.call_count, 1)

    def test_actual_cli_uses_conditional_put_single_attempt_and_bounded_timeout(self):
        store = delivery.S3("example-bucket", "us-west-2")
        with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess(
                [], 0, b'{"VersionId":"original"}', b"")) as run:
            self.assertEqual(store.put(delivery.PREFIX + "/123/a2/hash/request.json", b"{}"), "original")
        args, kwargs = run.call_args
        self.assertIn("--if-none-match", args[0])
        self.assertEqual(args[0][args[0].index("--if-none-match") + 1], "*")
        self.assertEqual(kwargs["env"]["AWS_MAX_ATTEMPTS"], "1")
        self.assertEqual(kwargs["timeout"], 60)
        self.assertEqual(run.call_count, 1)


if __name__ == "__main__":
    unittest.main()
