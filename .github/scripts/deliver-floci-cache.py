#!/usr/bin/env python3
"""Deliver original Floci publication evidence to the optional private cache ingress."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.request
import zipfile

REPOSITORY = "AndreKurait/floci"
PREFIX = "fleet-releases/floci-ingress/v1"
ARCHIVE_LIMIT = 256 * 1024
METADATA_LIMIT = 1024 * 1024


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def require(condition, message):
    if not condition:
        raise ValueError(message)


class ArtifactRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, original, fp, code, message, headers, newurl):
        redirected = super().redirect_request(original, fp, code, message, headers, newurl)
        if redirected is not None:
            require(redirected.full_url.startswith("https://"), "Insecure GitHub artifact redirect")
            redirected.remove_header("Authorization")
        return redirected


class GitHub:
    def __init__(self, token):
        require(isinstance(token, str) and token.strip(), "A workflow GitHub token is required")
        self.token = token

    def __call__(self, path, *, binary=False):
        require(path.startswith("actions/") and ".." not in path, "Unexpected GitHub API path")
        request = urllib.request.Request(
            "https://api.github.com/repos/" + REPOSITORY + "/" + path,
            headers={"Authorization": "Bearer " + self.token,
                     "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28",
                     "User-Agent": "floci-cache-delivery"})
        limit = ARCHIVE_LIMIT if binary else METADATA_LIMIT
        try:
            with urllib.request.build_opener(ArtifactRedirect()).open(request, timeout=30) as response:
                raw = response.read(limit + 1)
        except (urllib.error.HTTPError, urllib.error.URLError):
            raise ValueError("GitHub evidence read failed") from None
        require(len(raw) <= limit, "GitHub evidence exceeds its size bound")
        return raw if binary else json.loads(raw)


class S3Error(RuntimeError):
    def __init__(self, code):
        super().__init__("S3 evidence operation failed: " + code)
        self.code = code


class S3:
    """Use the runner's AWS CLI; each command makes one attempt with no hidden retries."""
    def __init__(self, bucket, region):
        require(isinstance(bucket, str) and re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket),
                "Invalid ingress bucket")
        require(isinstance(region, str) and re.fullmatch(r"[a-z]{2}(?:-[a-z]+)+-[0-9]+", region),
                "Invalid ingress region")
        self.bucket, self.region = bucket, region

    def call(self, operation, key, *args):
        require(key.startswith(PREFIX + "/") and ".." not in key, "Unexpected ingress key")
        command = ["aws", "s3api", operation, "--bucket", self.bucket, "--key", key,
                   "--region", self.region, "--no-cli-pager", "--output", "json",
                   "--cli-connect-timeout", "10", "--cli-read-timeout", "30", *args]
        environment = {**os.environ, "AWS_MAX_ATTEMPTS": "1", "AWS_RETRY_MODE": "standard",
                       "AWS_PAGER": ""}
        completed = subprocess.run(command, env=environment, capture_output=True, timeout=60)
        if completed.returncode:
            matched = re.search(rb"\(([A-Za-z0-9]+)\) when calling", completed.stderr)
            raise S3Error(matched.group(1).decode() if matched else "UNKNOWN")
        require(len(completed.stdout) <= METADATA_LIMIT, "S3 metadata exceeds its size bound")
        return json.loads(completed.stdout)

    def read(self, key, version=None):
        extra = ["--version-id", version] if version is not None else []
        metadata = self.call("head-object", key, *extra)
        identity = metadata.get("VersionId")
        require(isinstance(identity, str) and identity not in ("", "null"),
                "Ingress evidence requires an actual S3 version")
        require(version is None or identity == version, "Ingress object version changed")
        require(0 < metadata["ContentLength"] <= ARCHIVE_LIMIT, "Ingress object exceeds its size bound")
        with tempfile.TemporaryDirectory(prefix="floci-ingress-read-") as directory:
            path = Path(directory) / "object"
            response = self.call("get-object", key, "--version-id", identity, str(path))
            raw = path.read_bytes()
        require(response.get("VersionId") == identity and len(raw) == metadata["ContentLength"],
                "Ingress object readback differs")
        return raw, identity

    def put(self, key, raw):
        with tempfile.TemporaryDirectory(prefix="floci-ingress-put-") as directory:
            path = Path(directory) / "object"
            path.write_bytes(raw)
            return self.call("put-object", key, "--body", str(path),
                             "--if-none-match", "*")["VersionId"]


def immutable(store, key, raw):
    require(0 < len(raw) <= ARCHIVE_LIMIT, "Ingress evidence exceeds its size bound")
    try:
        version = store.put(key, raw)
        require(isinstance(version, str) and version not in ("", "null"),
                "Ingress write did not return an actual S3 version")
    except S3Error as error:
        if error.code not in ("PreconditionFailed", "412"):
            # An unknown write outcome is preserved; do not make another write attempt.
            raise
        version = None
    actual, version = store.read(key, version)
    require(actual == raw, "Existing ingress object contains different bytes")
    return version


def deliver(run_id, attempt, source, *, api, store):
    require(type(run_id) is int and run_id > 0 and type(attempt) is int and attempt > 0,
            "Invalid original workflow coordinates")
    require(isinstance(source, str) and re.fullmatch(r"[a-f0-9]{40}", source),
            "Invalid original source revision")
    run = api(f"actions/runs/{run_id}")
    require(run.get("id") == run_id and run.get("run_attempt") == attempt
            and run.get("head_sha") == source and run.get("head_branch") == "dev"
            and run.get("repository", {}).get("full_name") == REPOSITORY
            and run.get("path") == ".github/workflows/fork-dev-image.yml",
            "Publication workflow identity changed")
    page = api(f"actions/runs/{run_id}/artifacts?per_page=100")
    artifacts = page.get("artifacts", [])
    require(page.get("total_count") == len(artifacts), "Incomplete publication artifact metadata")
    selected = [row for row in artifacts if row.get("name", "").startswith("floci-image-provenance-")]
    require(len(selected) == 1, "Expected one original provenance archive")
    artifact = selected[0]
    origin = artifact.get("workflow_run", {})
    require(type(artifact.get("id")) is int and artifact["id"] > 0
            and artifact.get("expired") is False and origin.get("id") == run_id
            and origin.get("head_sha") == source, "Provenance artifact belongs to another publication")
    raw = api(f"actions/artifacts/{artifact['id']}/zip", binary=True)
    sha = hashlib.sha256(raw).hexdigest()
    require(len(raw) <= ARCHIVE_LIMIT and artifact.get("digest") == "sha256:" + sha,
            "Original provenance archive digest differs")
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        require(archive.namelist() == ["floci-image-provenance.json"]
                and archive.infolist()[0].file_size <= ARCHIVE_LIMIT,
                "Unexpected provenance archive contents")
        provenance = json.loads(archive.read("floci-image-provenance.json"))
    require(provenance.get("source", {}).get("repository") == REPOSITORY
            and provenance["source"].get("sha") == source
            and provenance.get("workflow", {}).get("runId") == str(run_id)
            and provenance["workflow"].get("attempt") == str(attempt),
            "Original provenance source or attempt differs")
    directory = f"{PREFIX}/{run_id}/a{attempt}/{sha}/"
    key = directory + "provenance.zip"
    version = immutable(store, key, raw)
    request = {"schemaVersion": 1, "repository": REPOSITORY, "runId": run_id,
               "runAttempt": attempt, "sourceRevision": source, "artifactId": artifact["id"],
               "archive": {"key": key, "versionId": version, "sha256": sha}}
    request_key = directory + "request.json"
    request_version = immutable(store, request_key, canonical(request))
    return {"status": "EVIDENCE_DELIVERED", "requestKey": request_key,
            "requestVersionId": request_version, "request": request,
            "workflowCompletionQualified": False, "cachePublicationQualified": False}


def main():
    require(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY
            and os.environ.get("GITHUB_REF") == "refs/heads/dev",
            "Cache delivery is restricted to the original fork dev ref")
    result = deliver(int(os.environ["GITHUB_RUN_ID"]), int(os.environ["GITHUB_RUN_ATTEMPT"]),
                     os.environ["GITHUB_SHA"], api=GitHub(os.environ["GH_TOKEN"]),
                     store=S3(os.environ["FLOCI_CACHE_BUCKET"], os.environ["FLOCI_CACHE_REGION"]))
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
