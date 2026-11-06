# Stock SSM agent image qualification

Run on a disposable Linux x86_64 Docker host:

```sh
python3 -B .github/scripts/smoke-ssm-agent.py \
  REGISTRY/FLOCI@sha256:IMAGE_DIGEST \
  REGISTRY/HELPER@sha256:HELPER_DIGEST \
  --output /tmp/stock-agent-evidence
```

Use a Floci image with stock-agent SSM transport and the declared helper digest.
The script starts that image with its original entrypoint, command, and health
check. A separate pinned AWS CLI container makes signed setup and verification
calls with local test credentials and the generated CA. Floci does not need to
contain AWS CLI.

The guest runs the unchanged, checksum-pinned Amazon SSM agent. Assertions cover
managed-node registration, inventory association, command execution and failure,
cancellation, connectivity loss and recovery, native Lambda VPC networking, and
cleanup. The script does not inject managed-node registration or inventory
through SDK calls.

Read `result.json` and the captured logs. A pass qualifies this container-based
stock-agent probe, not an operating-system VM boot or an application's complete
integration suite. Only x86_64 is covered by this probe. Docker footprint checks
require a host without concurrent container activity.

Fast fixture checks:

```sh
python3 -B -m unittest discover -s .github/scripts -p test_smoke_ssm_agent.py
```
