#!/usr/bin/env bash
set -euo pipefail

for attempt in 1 2 3; do
    if timeout --kill-after=5s 60s env MVNW_VERBOSE=true ./mvnw --version; then
        exit 0
    else
        status=$?
    fi
    if (( attempt < 3 )); then
        echo "::warning::Maven wrapper bootstrap attempt $attempt failed (exit $status); retrying."
        sleep 5
    fi
done

echo "::error::Maven wrapper bootstrap failed after three attempts."
exit "$status"
