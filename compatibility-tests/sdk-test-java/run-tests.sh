#!/usr/bin/env bash
set -euo pipefail

results=${SDK_TEST_RESULTS_DIR:-/results}
mkdir -p "$results"

collect_reports() {
    local status=$?
    trap - EXIT
    shopt -s nullglob
    local reports=(target/surefire-reports/TEST-*.xml target/surefire-reports/*.txt
                   target/surefire-reports/*.dump target/surefire-reports/*.dumpstream)
    if (( ${#reports[@]} > 0 )); then
        if ! cp "${reports[@]}" "$results/"; then
            echo "Could not collect Java SDK test reports" >&2
            if (( status == 0 )); then
                status=1
            fi
        fi
    fi
    exit "$status"
}
trap collect_reports EXIT

command=(mvn -B -ntp test "$@")
if [[ -n ${SDK_TEST_TIMEOUT_SECONDS:-} ]]; then
    if [[ ! $SDK_TEST_TIMEOUT_SECONDS =~ ^[1-9][0-9]*$ ]]; then
        echo "SDK_TEST_TIMEOUT_SECONDS must be a positive integer" >&2
        exit 2
    fi
    command=(timeout --verbose --kill-after=15s "${SDK_TEST_TIMEOUT_SECONDS}s" "${command[@]}")
fi

"${command[@]}" 2>&1 | tee "$results/maven.log"
