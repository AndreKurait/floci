#!/bin/sh
set -eu
token=$(curl -fsS --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
curl -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/instance-id > /tmp/boot-instance-id
trap 'exit 0' TERM INT
"$@" &
wait
