#!/usr/bin/env python3
"""Install a private diagnostic overlay only after checking exact source identities."""
import hashlib
import json
from pathlib import Path


def install(root, payload):
    manifest = json.loads((payload / 'manifest.json').read_text())
    target = root / manifest['sourcePath']
    helper = target.with_name('BindFailureCapture.java')
    source = target.read_bytes()
    if hashlib.sha256(source).hexdigest() != manifest['sourceSha256']:
        raise ValueError('ListenBacklog source changed; diagnostic overlay not applied')
    if helper.exists():
        raise ValueError('Diagnostic helper already exists; refusing replacement')
    bodies = {}
    for name, expected in manifest['payloadSha256'].items():
        body = (payload / name).read_bytes()
        if hashlib.sha256(body).hexdigest() != expected:
            raise ValueError('Diagnostic payload identity mismatch: ' + name)
        bodies[name] = body
    # All preconditions checked before source mutation. Failure aborts the job before compilation.
    helper.write_bytes(bodies['BindFailureCapture.java'])
    target.write_bytes(bodies['ListenBacklog.java'])


if __name__ == '__main__':
    install(Path.cwd(), Path(__file__).resolve().parent)
