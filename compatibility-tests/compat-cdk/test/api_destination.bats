#!/usr/bin/env bats

setup() {
    load 'test_helper/common-setup'
    export PATH="$CDK_DIR/node_modules/.bin:$PATH"
    API_CDK_DEPLOY_ATTEMPTED=false
    export AWS_DEFAULT_REGION=us-east-1
    export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test
    API_CDK_WORK="$(mktemp -d "${TMPDIR:-/tmp}/floci-api-cdk-XXXXXX")"
    export API_DESTINATION_PREFIX="api-cdk-$(date +%s)-$$"
    export API_DESTINATION_PHASE=initial
    API_CDK_APP="node \"$BATS_TEST_DIRNAME/api-destination-cdk/app.js\""
    python3 "$BATS_TEST_DIRNAME/api-destination-cdk/sink.py" "$API_CDK_WORK" > "$API_CDK_WORK/sink.log" 2>&1 3>&- &
    API_SINK_PID=$!
    for attempt in $(seq 1 100); do
        [ -s "$API_CDK_WORK/port" ] && break
        kill -0 "$API_SINK_PID" 2>/dev/null || break
        sleep 0.1
    done
    if [ ! -s "$API_CDK_WORK/port" ]; then
        echo "# API destination receiver did not become ready" >&3
        cat "$API_CDK_WORK/sink.log" >&3
        return 1
    fi
    local sink_host="${FLOCI_API_DESTINATION_SINK_HOST:-}"
    if [ -z "$sink_host" ]; then
        sink_host=$(python3 -c 'import os,socket,urllib.parse; host=urllib.parse.urlparse(os.environ["FLOCI_ENDPOINT"]).hostname; print("127.0.0.1" if host in ("localhost","127.0.0.1","::1") else socket.gethostbyname(socket.gethostname()))')
    fi
    export API_DESTINATION_SINK_URL="http://${sink_host}:$(cat "$API_CDK_WORK/port")"
    export LOCALSTACK_HOSTNAME=$(python3 -c 'import os,urllib.parse; print(urllib.parse.urlparse(os.environ["FLOCI_ENDPOINT"]).hostname)')
    export EDGE_PORT=$(python3 -c 'import os,urllib.parse; print(urllib.parse.urlparse(os.environ["FLOCI_ENDPOINT"]).port or 4566)')
}

teardown() {
    if [ "${API_CDK_DEPLOY_ATTEMPTED:-false}" = true ]; then
        cdklocal destroy "$API_DESTINATION_PREFIX" --force --app "$API_CDK_APP" --output "$API_CDK_WORK/cdk.out" >&3 || true
    fi
    if [ -n "${API_SINK_PID:-}" ]; then
        kill "$API_SINK_PID" 2>/dev/null || true
        wait "$API_SINK_PID" 2>/dev/null || true
    fi
    [ -z "${API_CDK_WORK:-}" ] || rm -rf "$API_CDK_WORK"
}

cdk_api() {
    cdklocal "$@" --app "$API_CDK_APP" --output "$API_CDK_WORK/cdk.out"
}

publish_and_verify() {
    local phase="$1" count="$2"
    run aws_cmd events put-events --entries "[{\"Source\":\"$API_DESTINATION_PREFIX\",\"DetailType\":\"test\",\"Detail\":\"{}\"}]"
    assert_success
    [ "$(json_get "$output" '.FailedEntryCount')" = "0" ]
    for attempt in $(seq 1 100); do
        [ -f "$API_CDK_WORK/deliveries.jsonl" ] && [ "$(wc -l < "$API_CDK_WORK/deliveries.jsonl")" -ge "$count" ] && break
        sleep 0.1
    done
    run jq -se --arg phase "$phase" 'last | .path == ("/" + $phase + "/item?version=2") and .api_key == ("synthetic-" + $phase) and .target_header == $phase and .body.value == "ok"' "$API_CDK_WORK/deliveries.jsonl"
    assert_success
}

@test "CDK API destination: deploy, exact attributes, delivery, no-op, update, rename, destroy" {
    API_CDK_DEPLOY_ATTEMPTED=true
    run cdk_api deploy "$API_DESTINATION_PREFIX" --require-approval never --outputs-file "$API_CDK_WORK/outputs.json"
    assert_success
    local connection_arn destination_arn
    connection_arn=$(jq -r --arg stack "$API_DESTINATION_PREFIX" '.[$stack].ConnectionArn' "$API_CDK_WORK/outputs.json")
    destination_arn=$(jq -r --arg stack "$API_DESTINATION_PREFIX" '.[$stack].DestinationArn' "$API_CDK_WORK/outputs.json")
    run aws_cmd events describe-connection --name "$API_DESTINATION_PREFIX-auth"
    assert_success
    [ "$(json_get "$output" '.ConnectionArn')" = "$connection_arn" ]
    local secret_arn
    secret_arn=$(json_get "$output" '.SecretArn')
    run jq -e --arg stack "$API_DESTINATION_PREFIX" --arg secret "$secret_arn" '
        .[$stack] | .ConnectionName == ($stack + "-auth") and .DestinationName == ($stack + "-destination")
        and .ConnectionArn == (.ConnectionPolicyArn + "/" + (.ConnectionArn | split("/") | last))
        and .DestinationArn == (.DestinationPolicyArn + "/" + (.DestinationArn | split("/") | last))
        and .SecretArn == $secret' "$API_CDK_WORK/outputs.json"
    assert_success
    publish_and_verify initial 1
    run cdk_api deploy "$API_DESTINATION_PREFIX" --require-approval never --outputs-file "$API_CDK_WORK/outputs.json"
    assert_success
    [ "$(jq -r --arg stack "$API_DESTINATION_PREFIX" '.[$stack].ConnectionArn' "$API_CDK_WORK/outputs.json")" = "$connection_arn" ]
    [ "$(jq -r --arg stack "$API_DESTINATION_PREFIX" '.[$stack].DestinationArn' "$API_CDK_WORK/outputs.json")" = "$destination_arn" ]
    run cdk_api diff "$API_DESTINATION_PREFIX" --fail --no-change-set
    assert_success

    export API_DESTINATION_PHASE=updated
    run cdk_api deploy "$API_DESTINATION_PREFIX" --require-approval never --outputs-file "$API_CDK_WORK/outputs.json"
    assert_success
    [ "$(jq -r --arg stack "$API_DESTINATION_PREFIX" '.[$stack].ConnectionArn' "$API_CDK_WORK/outputs.json")" = "$connection_arn" ]
    [ "$(jq -r --arg stack "$API_DESTINATION_PREFIX" '.[$stack].DestinationArn' "$API_CDK_WORK/outputs.json")" = "$destination_arn" ]
    run aws_cmd events describe-api-destination --name "$API_DESTINATION_PREFIX-destination"
    assert_success
    [ "$(json_get "$output" '.InvocationRateLimitPerSecond')" = "84" ]
    publish_and_verify updated 2
    run cdk_api diff "$API_DESTINATION_PREFIX" --fail --no-change-set
    assert_success

    export API_DESTINATION_PHASE=renamed
    run cdk_api deploy "$API_DESTINATION_PREFIX" --require-approval never --outputs-file "$API_CDK_WORK/outputs.json"
    assert_success
    run aws_cmd events describe-connection --name "$API_DESTINATION_PREFIX-auth"
    assert_failure
    assert_output --partial "ResourceNotFoundException"
    run aws_cmd events describe-api-destination --name "$API_DESTINATION_PREFIX-destination"
    assert_failure
    assert_output --partial "ResourceNotFoundException"
    run jq -e --arg stack "$API_DESTINATION_PREFIX" '.[$stack] | .ConnectionName == ($stack + "-auth-renamed") and .DestinationName == ($stack + "-destination-renamed")' "$API_CDK_WORK/outputs.json"
    assert_success
    publish_and_verify renamed 3
    run cdk_api diff "$API_DESTINATION_PREFIX" --fail --no-change-set
    assert_success

    run cdk_api destroy "$API_DESTINATION_PREFIX" --force
    assert_success
    API_CDK_DEPLOY_ATTEMPTED=false
    run aws_cmd events describe-connection --name "$API_DESTINATION_PREFIX-auth-renamed"
    assert_failure
    assert_output --partial "ResourceNotFoundException"
    run aws_cmd events describe-api-destination --name "$API_DESTINATION_PREFIX-destination-renamed"
    assert_failure
    assert_output --partial "ResourceNotFoundException"
    run aws_cmd events describe-rule --name "$API_DESTINATION_PREFIX"
    assert_failure
    assert_output --partial "ResourceNotFoundException"
}
