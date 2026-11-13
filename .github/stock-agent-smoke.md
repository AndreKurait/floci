# Published-image stock-agent smoke

`fork-dev-image.yml` requires the x86_64 stock-agent smoke before moving the
`dev` tag. It uses the immutable JVM image and network helper produced by that
workflow. The existing helper smoke continues to cover both architectures.

The smoke downloads the versioned AWS SSM agent RPM and checks its pinned
SHA-256, installs it in a pinned Amazon Linux 2 container, and launches that
guest through EC2. An instance role supplies credentials through IMDSv2. The
agent uses ordinary AWS endpoint names, verified TLS and its unchanged
`aws:softwareInventory` plugin.

The script starts the Floci image with its original entrypoint, command, and
health check. A separate pinned AWS CLI container makes signed setup and
verification calls with local test credentials and the generated CA, so the
Floci image does not need to contain AWS CLI. The Docker endpoint is read from
the active Docker context and must be a local socket.

Success requires the agent to register, discover a tag-targeted State Manager
association, execute it, report success, and publish inventory containing its
installed agent package. The driver never registers a managed node or submits
inventory on the agent's behalf. API teardown and the original Docker
container/network/volume footprint must also pass.

Run it on a dedicated Linux x86_64 Docker daemon with Python 3:

```sh
python3 .github/scripts/smoke-ssm-agent.py \
  ghcr.io/andrekurait/floci@sha256:IMAGE_DIGEST \
  ghcr.io/andrekurait/floci@sha256:HELPER_DIGEST \
  --output /tmp/stock-agent-result
```

Fast fixture checks, which need no Floci image:

```sh
python3 -B -m unittest discover -s .github/scripts -p test_smoke_ssm_agent.py
```

The server uses an owned Docker network and its standard image launcher.
Logs, image identity, inventory, timings and cleanup results are written to
the output directory and uploaded by the workflow.

This tests initial State Manager inventory execution in a generic container.
VM boot, patching, Run Command transport, downstream inventory consumers and
private full-fleet pipelines require their own qualification.
