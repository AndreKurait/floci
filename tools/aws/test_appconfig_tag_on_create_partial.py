"""The two-operation AppConfig admission scope survives generation and validation."""
import copy

import pytest

import regen_iam_tag_on_create as r
from test_regen_iam_tag_on_create import TAG_SHAPES, model, reference, structure


NAMES = ["CreateApplication", "CreateDeploymentStrategy"]


@pytest.fixture
def partial_document(monkeypatch, tmp_path):
    operations = NAMES + ["CreateEnvironment", "CreateConfigurationProfile", "CreateExtension",
                          "CreateExtensionAssociation", "StartDeployment", "FutureCreate"]
    public = reference("appconfig", {name: ["appconfig:TagResource"] for name in operations},
                       keys={name: ["aws:RequestTag/${TagKey}", "aws:TagKeys"] for name in operations})
    sdk = model({name: "Input" for name in operations},
                {"Input": structure(Tags="TagMap"), **TAG_SHAPES}, protocol="rest-json")
    monkeypatch.setattr(r, "NAMESPACES", ("appconfig",))
    monkeypatch.setattr(r, "load_models", lambda data, clients: {"appconfig": sdk})
    for name in ("BY_HAND", "NOT_READ", "EXCLUDED_MEMBERS", "QUERY_MEMBERS"):
        monkeypatch.setattr(r, name, {})
    return r.build([public], "public-contract-fixture", tmp_path, "test"), public


def test_only_the_two_appconfig_operations_are_generated(partial_document):
    document, public = partial_document
    assert r.PARTIAL_NAMESPACES == {"appconfig": NAMES}
    assert document["partialNamespaces"] == {"appconfig": NAMES}
    assert set(document["operations"]) == {"appconfig:" + name for name in NAMES}
    assert document["notCovered"]["appconfig"].endswith("other creation tags remain uncovered")
    assert r.validate(document) == []
    for entry in document["operations"].values():
        assert entry == {"tagging": ["appconfig:TagResource"], "requestTags": True,
                         "tags": [{"member": "Tags{}"}]}
    upstream = r.tag_on_create_operations([public])
    untrue, added = r.verify_against(document, upstream)
    assert untrue == []
    assert added == []
    assert '"partialNamespaces": {"appconfig": ["CreateApplication", "CreateDeploymentStrategy"]}' in r.render(document)


@pytest.mark.parametrize("names", [[], ["CreateApplication"], NAMES + ["CreateEnvironment"],
                                   NAMES + ["CreateApplication"], ["CreateApplication", "Bad:Name"]])
def test_partial_inventory_cannot_omit_or_expand_the_exact_operation_set(partial_document, names):
    document = copy.deepcopy(partial_document[0])
    document["partialNamespaces"]["appconfig"] = names
    assert r.validate(document)


def test_an_extra_namespace_operation_is_rejected_even_with_valid_tag_members(partial_document):
    document = copy.deepcopy(partial_document[0])
    document["operations"]["appconfig:CreateEnvironment"] = copy.deepcopy(
        document["operations"]["appconfig:CreateApplication"])
    document["operations"] = dict(sorted(document["operations"].items()))
    assert any("does not exactly match" in failure for failure in r.validate(document))
