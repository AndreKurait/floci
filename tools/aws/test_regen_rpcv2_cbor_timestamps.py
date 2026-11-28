"""Tests for regen_rpcv2_cbor_timestamps.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json

import pytest

import regen_rpcv2_cbor_timestamps as r


def model(prefix: str, **members: str) -> dict:
    """A model whose one structure has the members given, each targeting the shape type named."""
    shapes = {"Structure": {"type": "structure", "members": {name: {"shape": kind.title()} for name, kind in members.items()}}}
    for kind in set(members.values()):
        shapes[kind.title()] = {"type": kind}
    return {"metadata": {"apiVersion": "2020-01-01", "targetPrefix": prefix, "protocols": ["smithy-rpc-v2-cbor", "json"]},
            "shapes": shapes}


def write(data, name, value):
    directory = data / name / "2020-01-01"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "service-2.json").write_text(json.dumps(value), encoding="utf-8")


def test_only_members_that_target_a_timestamp_are_named():
    assert r.timestamp_members(model("P", CreatedAt="timestamp", Name="string", Count="integer")) == ["CreatedAt"]


def test_every_service_is_keyed_by_its_rpc_v2_service_name(tmp_path, monkeypatch):
    monkeypatch.setattr(r, "SERVICES", {"CodeBuild_20161006": "codebuild"})
    write(tmp_path, "codebuild", model("CodeBuild_20161006", created="timestamp", lastModified="timestamp", name="string"))

    doc = r.build(tmp_path, "botocore test")

    assert doc["_source"] == {"generator": "tools/aws/regen_rpcv2_cbor_timestamps.py", "botocore": "botocore test"}
    assert doc["services"] == {"CodeBuild_20161006": ["created", "lastModified"]}


def test_a_service_whose_target_prefix_changed_stops_the_generation(tmp_path, monkeypatch):
    monkeypatch.setattr(r, "SERVICES", {"CodeBuild_20161006": "codebuild"})
    write(tmp_path, "codebuild", model("CodeBuild_20200101", created="timestamp"))

    with pytest.raises(SystemExit):
        r.build(tmp_path, "botocore test")


def test_check_ignores_the_provenance_and_catches_a_changed_member(tmp_path, monkeypatch):
    monkeypatch.setattr(r, "SERVICES", {"Textract": "textract"})
    data = tmp_path / "data"
    write(data, "textract", model("Textract", CreationTime="timestamp"))
    output = tmp_path / "rpc-v2-cbor-timestamps.json"

    assert r.main(["--botocore-data", str(data), "--output", str(output)]) == 0
    assert r.main(["--check", "--botocore-data", str(data), "--output", str(output)]) == 0

    relabelled = json.loads(output.read_text(encoding="utf-8"))
    relabelled["_source"]["botocore"] = "another label"
    output.write_text(json.dumps(relabelled, indent=2) + "\n", encoding="utf-8")
    assert r.main(["--check", "--botocore-data", str(data), "--output", str(output)]) == 0

    write(data, "textract", model("Textract", CreationTime="timestamp", CompletionTime="timestamp"))
    assert r.main(["--check", "--botocore-data", str(data), "--output", str(output)]) == 1
