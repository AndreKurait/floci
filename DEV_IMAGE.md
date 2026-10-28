# Fork dev image

The `dev` branch combines the staged feature PRs in this fork. It is a development
build, with changes that have not all merged upstream.

Every push to `dev` builds the existing JVM Docker image for Linux AMD64 and ARM64
and publishes it to GitHub Container Registry. After native AMD64 and ARM64
runners pass a container health check and an S3 CLI check, the workflow updates
`ghcr.io/andrekurait/floci:dev`.
The Actions run also records a timestamped tag and immutable digest.
Maven compiles the shared JVM artifact once on the runner, using its dependency
cache and skipping test compilation. Both images use the existing
`Dockerfile.jvm-package`, so image assembly does not run Maven under emulation.
Publishing runs are serialized. The moving `dev` tag identifies the last image
that passed these checks, so a failed build leaves the previous working image
available. A build already superseded when promotion begins does not update it.
Commits pushed during promotion trigger the next build; the tag can lag the
branch until that build passes.

```sh
docker pull ghcr.io/andrekurait/floci:dev
docker run --rm -p 127.0.0.1:4566:4566 ghcr.io/andrekurait/floci:dev
```

For Lambda runtimes and other services that launch containers, also mount the
Docker socket with `-v /var/run/docker.sock:/var/run/docker.sock`.

GitHub initially creates packages as private. The package owner must set the
`floci` container package visibility to public once for anonymous pulls. Until
then, authenticate to `ghcr.io` with a token that has `read:packages` access.
Publication uses the workflow's `GITHUB_TOKEN`; no Docker Hub secrets are needed.
