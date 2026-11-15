#!/usr/bin/env bash
set -euo pipefail

# Ordinary CI keeps the command and JVM options unchanged.
if [[ "${FLOCI_CI_PROFILE:-false}" != true ]]; then
  exec "$@"
fi

profile_dir="${RUNNER_TEMP:?}/floci-ci-profile-${SHARD_INDEX:?}"
mkdir -p "$profile_dir"

# Record startup and CPU samples, without copying environment variables or
# system properties (which can contain credentials) into the artifact.
jfr configure --input profile.jfc --output "$profile_dir/profile.jfc" \
  jdk.InitialEnvironmentVariable#enabled=false \
  jdk.InitialSystemProperty#enabled=false

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-XX:StartFlightRecording=filename=$profile_dir,settings=$profile_dir/profile.jfc,maxsize=128m,dumponexit=true -Xlog:jfr+startup=off"
exec "$@"
