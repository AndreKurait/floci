"""Tests for regen_eks_access_policies.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json
import subprocess
import sys

import pytest

import regen_eks_access_policies as r


def response(*names: str) -> dict:
    return {"accessPolicies": [{"name": name, "arn": r.ARN_PREFIX + name} for name in names]}


def catalog(*names: str) -> dict:
    return {"accessPolicies": [{"name": name} for name in names]}


def test_names_are_the_response_s_in_its_order():
    assert r.names(response("AmazonEKSAdminPolicy", "AmazonEKSClusterAdminPolicy")) == [
        "AmazonEKSAdminPolicy", "AmazonEKSClusterAdminPolicy"]


def test_a_policy_whose_arn_is_not_its_name_s_is_refused():
    odd = response("AmazonEKSAdminPolicy")
    odd["accessPolicies"][0]["arn"] = "arn:aws:eks::aws:cluster-access-policy/Other"
    with pytest.raises(ValueError, match="AmazonEKSAdminPolicy: ARN"):
        r.names(odd)


@pytest.mark.parametrize("vendored, problem", [
    ({}, "top-level 'accessPolicies' list is missing or empty"),
    (catalog(), "top-level 'accessPolicies' list is missing or empty"),
    ({"accessPolicies": [{"name": "AmazonEKSAdminPolicy", "arn": "x"}]},
     "unexpected entry {'name': 'AmazonEKSAdminPolicy', 'arn': 'x'}"),
    (catalog("Amazon EKS"), "not a policy name: 'Amazon EKS'"),
    (catalog("AmazonEKSViewPolicy", "AmazonEKSAdminPolicy"), "names are not unique and in name order"),
    (catalog("AmazonEKSAdminPolicy", "AmazonEKSAdminPolicy"), "names are not unique and in name order"),
])
def test_validate_refuses_a_catalog_java_cannot_page(vendored, problem):
    assert r.validate(vendored) == [problem]


def test_render_round_trips_one_policy_per_line():
    vendored = catalog("AmazonEKSAdminPolicy", "AmazonEKSViewPolicy")
    text = r.render(vendored)
    assert json.loads(text) == vendored
    assert text.count("\n") == 4


def test_the_vendored_catalog_is_well_formed_and_rendered():
    vendored = json.loads(r.OUTPUT.read_text())
    assert r.validate(vendored) == []
    assert r.render(vendored) == r.OUTPUT.read_text()


def test_check_reports_a_file_it_cannot_read(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(r, "OUTPUT", tmp_path / "missing.json")
    monkeypatch.setattr(sys, "argv", ["regen_eks_access_policies.py", "--check"])

    assert r.main() == 1
    assert "access-policies.json: cannot be read" in capsys.readouterr().err


def test_a_failed_sync_reports_aws_and_leaves_the_file_unchanged(tmp_path, monkeypatch, capsys):
    output = tmp_path / "access-policies.json"
    output.write_text("vendored\n")
    monkeypatch.setattr(r, "OUTPUT", output)
    monkeypatch.setattr(sys, "argv", ["regen_eks_access_policies.py"])

    def expired():
        raise subprocess.CalledProcessError(254, ["aws"], stderr="The security token included in the request is expired\n")
    monkeypatch.setattr(r, "list_policies", expired)

    assert r.main() == 1
    assert output.read_text() == "vendored\n"
    assert "security token included in the request is expired" in capsys.readouterr().err
