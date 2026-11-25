"""An operation-limited REST addition must not widen older Batch create authorization."""
import copy
import json
from pathlib import Path

import pytest

import regen_iam_tag_on_create as r


def document():
    return {
        "namespaces": {"batch": ["batch"]},
        "operations": {"batch:CreateSchedulingPolicy": {
            "tagging": ["batch:TagResource"], "requestTags": True, "tags": [{"member": "tags{}"}]}},
        "partialNamespaces": {"batch": ["CreateSchedulingPolicy"]},
    }


@pytest.mark.parametrize("partial", [
    None, [], "batch", {"unknown": ["CreateSchedulingPolicy"]}, {"batch": []},
    {"batch": "CreateSchedulingPolicy"}, {"batch": [None]}, {"batch": [1]},
    {"batch": ["CreateSchedulingPolicy", "CreateSchedulingPolicy"]},
    {"batch": ["CreateJobQueue"]}, {"batch": ["batch:CreateSchedulingPolicy"]},
])
def test_invalid_partial_metadata_is_refused(partial):
    selected = document()
    selected["partialNamespaces"] = partial
    assert r.validate(selected)


def test_extra_operation_is_rejected_instead_of_silently_expanding_scope():
    selected = document()
    selected["operations"]["batch:CreateJobQueue"] = copy.deepcopy(
        selected["operations"]["batch:CreateSchedulingPolicy"])
    assert any("partialNamespaces" in problem for problem in r.validate(selected))


def test_reference_comparison_leaves_excluded_batch_creates_outside_coverage():
    selected = document()
    upstream = {name: {"tagging": ["batch:TagResource"], "createAction": False,
                       "requestTags": True, "clients": ["batch"]}
                for name in ("batch:CreateSchedulingPolicy", "batch:CreateJobQueue",
                             "batch:CreateComputeEnvironment", "batch:RegisterJobDefinition")}
    assert r.validate(selected) == []
    assert r.verify_against(selected, upstream) == ([], [])


def test_regeneration_preserves_every_existing_namespace_operation_set(monkeypatch, tmp_path):
    vendored = json.loads((Path(__file__).resolve().parents[2]
                          / "src/main/resources/aws/iam-tag-on-create.json").read_text())
    original = {name: value for name, value in vendored["operations"].items()
                if not name.startswith("batch:")}
    upstream = {
        name: {"tagging": value["tagging"], "createAction": value.get("createAction", False),
               "requestTags": value.get("requestTags", False),
               "clients": vendored["namespaces"][r.namespace_of(name)]}
        for name, value in original.items()
    }
    for name in ("CreateSchedulingPolicy", "CreateJobQueue", "CreateComputeEnvironment", "RegisterJobDefinition"):
        upstream["batch:" + name] = {"tagging": ["batch:TagResource"], "createAction": False,
                                     "requestTags": True, "clients": ["batch"]}
    monkeypatch.setattr(r, "tag_on_create_operations", lambda documents: dict(sorted(upstream.items())))
    monkeypatch.setattr(r, "with_rest_operations", lambda upstream, documents, given: upstream, raising=False)
    monkeypatch.setattr(r, "rest_operations", lambda data: {}, raising=False)
    monkeypatch.setattr(r, "load_models", lambda data, clients: {})
    monkeypatch.setattr(r, "_check_overrides", lambda covered: None)
    monkeypatch.setattr(r, "_unmatched_exclusions", lambda covered, namespaces, data: [])
    monkeypatch.setattr(r, "entry_for", lambda operation, value, models:
                        copy.deepcopy(vendored["operations"].get(operation, document()["operations"]["batch:CreateSchedulingPolicy"])))
    monkeypatch.setattr(r, "resource_arns", lambda documents, namespaces: vendored["resourceArns"])
    generated = r.build([], "fixture", tmp_path, "fixture")
    assert r.validate(generated) == []
    assert {name: value for name, value in generated["operations"].items()
            if not name.startswith("batch:")} == original
    assert {name for name in generated["operations"] if name.startswith("batch:")} == {"batch:CreateSchedulingPolicy"}
    assert generated["partialNamespaces"] == {"batch": ["CreateSchedulingPolicy"]}
    assert json.loads(r.render(generated)) == generated
