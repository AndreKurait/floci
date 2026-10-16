"""Tests for regen_sesv2_shapes.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import regen_sesv2_shapes as r


MODEL = {
    "operations": {
        "SendEmail": {"input": {"shape": "SendEmailRequest"}, "output": {"shape": "SendEmailResponse"}},
        "GetEmailIdentity": {"input": {"shape": "GetEmailIdentityRequest"}},
        "NoInput": {},
    },
    "shapes": {
        "SendEmailRequest": {"type": "structure", "members": {
            "FromEmailAddress": {"shape": "EmailAddress"},
            "ReplyToAddresses": {"shape": "EmailAddressList"},
            "Content": {"shape": "EmailContent"},
        }},
        "SendEmailResponse": {"type": "structure", "members": {"MessageId": {"shape": "OutboundMessageId"}}},
        "GetEmailIdentityRequest": {"type": "structure", "members": {
            "EmailIdentity": {"shape": "Identity", "location": "uri", "locationName": "EmailIdentity"},
            "NextToken": {"shape": "NextToken", "location": "querystring", "locationName": "NextToken"},
        }},
        "EmailAddress": {"type": "string"},
        "EmailAddressList": {"type": "list", "member": {"shape": "EmailAddress"}},
        "EmailContent": {"type": "structure", "members": {"Raw": {"shape": "RawMessage"}}},
        "RawMessage": {"type": "structure", "members": {"Data": {"shape": "RawMessageData"}}},
        "RawMessageData": {"type": "blob"},
        "OutboundMessageId": {"type": "string"},
        "Identity": {"type": "string", "min": 1},
        "NextToken": {"type": "string"},
    },
}


def test_keeps_only_body_members_of_input_shapes():
    doc = r.build(MODEL, "botocore test")

    assert doc["_source"] == {"generator": "tools/aws/regen_sesv2_shapes.py", "botocore": "botocore test"}
    assert doc["operations"] == {"GetEmailIdentity": "GetEmailIdentityRequest", "SendEmail": "SendEmailRequest"}
    assert "SendEmailResponse" not in doc["shapes"]
    assert "OutboundMessageId" not in doc["shapes"]
    assert doc["shapes"]["SendEmailRequest"] == {"type": "structure", "members": {
        "FromEmailAddress": "EmailAddress", "ReplyToAddresses": "EmailAddressList", "Content": "EmailContent"}}
    assert doc["shapes"]["EmailAddressList"] == {"type": "list", "member": "EmailAddress"}
    assert doc["shapes"]["RawMessageData"] == {"type": "blob"}


def test_drops_uri_and_query_members():
    doc = r.build(MODEL, "botocore test")

    assert doc["shapes"]["GetEmailIdentityRequest"] == {"type": "structure", "members": {}}
    assert "Identity" not in doc["shapes"]
    assert "NextToken" not in doc["shapes"]


def test_check_ignores_provenance(tmp_path, monkeypatch):
    output = tmp_path / "shapes.json"
    output.write_text(r.render(r.build(MODEL, "botocore 0.0.1 (local/aws/botocore)")), encoding="utf-8")
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "--botocore-data /elsewhere"))
    monkeypatch.setattr(r, "load_model", lambda data, model: MODEL)

    assert r.main(["--check", "--output", str(output)]) == 0


def test_check_reports_changed_shapes(tmp_path, monkeypatch):
    output = tmp_path / "shapes.json"
    changed = {**MODEL, "shapes": {**MODEL["shapes"], "EmailAddress": {"type": "integer"}}}
    output.write_text(r.render(r.build(changed, "botocore test")), encoding="utf-8")
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "botocore test"))
    monkeypatch.setattr(r, "load_model", lambda data, model: MODEL)

    assert r.main(["--check", "--output", str(output)]) == 1
