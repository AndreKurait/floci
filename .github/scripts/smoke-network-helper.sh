#!/usr/bin/env bash
# Exercise the published helper through EC2, without a helper-image override or local helper build.
set -euo pipefail

image="${1:?Floci image is required}"
helper_image="${2:?Expected network helper image is required}"
daemon_architecture="$(docker info --format '{{.Architecture}}')"
case "$daemon_architecture" in
    x86_64|amd64) daemon_architecture=amd64 ;;
    aarch64|arm64) daemon_architecture=arm64 ;;
    *) echo "Unexpected Docker architecture: $daemon_architecture" >&2; exit 1 ;;
esac
guest_architecture="${3:-$daemon_architecture}"
[[ "$guest_architecture" == amd64 || "$guest_architecture" == arm64 ]]
docker_socket="${DOCKER_HOST:-unix:///var/run/docker.sock}"
[[ "$docker_socket" == unix://* ]] || { echo "This smoke test requires a local Docker socket." >&2; exit 1; }
docker_socket="${docker_socket#unix://}"
work="$(mktemp -d)"
namespace="floci-helper-smoke-$$"
parent="$namespace-server"
network="$namespace-network"
guest_image="$namespace-guest:local"
instance=""

footprint() {
    docker ps -aq --no-trunc | sort | sed 's/^/container /'
    docker network ls --no-trunc --format '{{.ID}}' | sort | sed 's/^/network /'
    docker volume ls -q | sort | sed 's/^/volume /'
}
before="$(footprint)"

aws() {
    docker exec "$parent" aws "$@"
}

cleanup() {
    result=$?
    trap - EXIT
    set +e
    if (( result != 0 )); then
        docker logs "$parent"
    fi
    if [[ -n "$instance" ]]; then
        aws ec2 terminate-instances --instance-ids "$instance" >/dev/null
    fi
    if docker inspect "$parent" >/dev/null 2>&1; then
        docker stop --time 70 "$parent" >/dev/null
        docker rm -v "$parent" >/dev/null
    fi
    while read -r container; do
        if [[ -n "$container" ]]; then
            echo "Normal shutdown left a managed container: $container" >&2
            docker rm -fv "$container" >/dev/null
            result=1
        fi
    done < <(docker ps -aq --filter "label=floci_namespace=$namespace")
    while read -r owned_network; do
        if [[ -n "$owned_network" ]]; then
            echo "Normal shutdown left a managed network: $owned_network" >&2
            docker network rm "$owned_network" >/dev/null
            result=1
        fi
    done < <(docker network ls -q --filter "label=floci_namespace=$namespace")
    docker network rm "$network" >/dev/null 2>&1
    docker image rm "$guest_image" >/dev/null 2>&1
    rm -rf "$work"
    if [[ "$(footprint)" != "$before" ]]; then
        echo "Docker resources did not return to their original state." >&2
        footprint >&2
        result=1
    fi
    exit "$result"
}
trap cleanup EXIT

cat > "$work/Dockerfile" <<'DOCKERFILE'
FROM alpine:3.21
RUN apk add --no-cache curl
ENTRYPOINT ["sh", "-c", "identity=$(curl --noproxy '*' -fsS --max-time 3 http://169.254.169.254/latest/meta-data/instance-id) || exit; echo \"first_metadata=$identity\"; exec sleep 2147483647"]
DOCKERFILE
docker build --platform "linux/$guest_architecture" --tag "$guest_image" "$work" >/dev/null
architecture="$(docker image inspect --format '{{.Architecture}}' "$guest_image")"
[[ "$architecture" == "$guest_architecture" ]]
case "$architecture" in
    amd64) ec2_architecture=x86_64; instance_type=t3.nano; machine=x86_64 ;;
    arm64) ec2_architecture=arm64; instance_type=t4g.nano; machine=aarch64 ;;
    *) echo "Unexpected guest architecture: $architecture" >&2; exit 1 ;;
esac
jq -n --arg image "$guest_image" --arg architecture "$ec2_architecture" \
    '{defaultDockerImage: $image, images: [{imageId: "ami-00000000000000001",
      dockerImage: $image, name: "metadata-boot-smoke", architecture: $architecture,
      description: "Check metadata before the guest entrypoint",
      creationDate: "2026-01-01T00:00:00.000Z", guestRuntime: "image"}]}' > "$work/catalog.json"

docker network create "$network" >/dev/null
docker run --detach --name "$parent" --network "$network" \
    --volume "$docker_socket:/var/run/docker.sock" \
    --mount "type=bind,source=$work/catalog.json,target=/catalog.json,readonly" \
    --env "FLOCI_DOCKER_RESOURCE_NAMESPACE=$namespace" \
    --env "FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK=$network" \
    --env "FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE=$parent" \
    --env FLOCI_SERVICES_EC2_IMAGE_CATALOG_PATH=/catalog.json \
    --env FLOCI_SERVICES_EC2_MOCK=false \
    --env FLOCI_SERVICES_EC2_VOLUME_BLOCK_DEVICES=false \
    --env FLOCI_SERVICES_EC2_PUBLISH_SECURITY_GROUP_PORTS=false \
    --env FLOCI_NETWORK_SECURITY_GROUP_ENFORCEMENT_ENABLED=true \
    "$image" >/dev/null
deadline=$((SECONDS + 90))
until [[ "$(docker inspect --format '{{.State.Health.Status}}' "$parent")" == healthy ]]; do
    (( SECONDS < deadline )) || { echo "Floci did not become healthy." >&2; exit 1; }
    sleep 1
done
aws s3api list-buckets --output json >/dev/null
for setting in FLOCI_SERVICES_EC2_METADATA_HELPER_IMAGE \
        FLOCI_NETWORK_SECURITY_GROUP_ENFORCEMENT_HELPER_IMAGE \
        FLOCI_SERVICES_ECS_TASK_ROLE_CREDENTIALS_PROXY_IMAGE; do
    [[ "$(docker exec "$parent" printenv "$setting")" == "$helper_image" ]]
done

vpc="$(aws ec2 create-vpc --cidr-block 10.73.0.0/16 --query Vpc.VpcId --output text)"
subnet="$(aws ec2 create-subnet --vpc-id "$vpc" --cidr-block 10.73.1.0/24 \
    --query Subnet.SubnetId --output text)"
group="$(aws ec2 create-security-group --vpc-id "$vpc" --group-name metadata-only \
    --description 'Metadata smoke without egress' --query GroupId --output text)"
permissions="$(aws ec2 describe-security-groups --group-ids "$group" \
    --query 'SecurityGroups[0].IpPermissionsEgress' --output json)"
aws ec2 revoke-security-group-egress --group-id "$group" --ip-permissions "$permissions" >/dev/null
instance="$(aws ec2 run-instances --image-id ami-00000000000000001 \
    --instance-type "$instance_type" --subnet-id "$subnet" --security-group-ids "$group" \
    --metadata-options HttpEndpoint=enabled,HttpTokens=optional \
    --query 'Instances[0].InstanceId' --output text)"
deadline=$((SECONDS + 90))
guest=""
until [[ -n "$guest" ]]; do
    guest="$(docker ps -q --filter "ancestor=$guest_image")"
    (( SECONDS < deadline )) || { echo "EC2 guest did not start." >&2; exit 1; }
    sleep 1
done
until [[ "$(docker logs "$guest")" == "first_metadata=$instance" ]]; do
    (( SECONDS < deadline )) || { echo "The first guest metadata request failed." >&2; exit 1; }
    sleep 1
done
helper="$(docker inspect --format '{{.HostConfig.NetworkMode}}' "$guest")"
[[ "$helper" == container:* ]]
[[ "$(docker inspect --format '{{.Image}}' "${helper#container:}")" == \
   "$(docker image inspect --format '{{.Id}}' "$helper_image")" ]]
[[ "$(docker image inspect --format '{{.Architecture}}' "$helper_image")" == "$daemon_architecture" ]]
[[ "$(docker exec "$guest" uname -m)" == "$machine" ]]
[[ "$(docker exec "$guest" sh -c \
    'curl --noproxy "*" -fsS --max-time 3 "$AWS_EC2_METADATA_SERVICE_ENDPOINT/latest/meta-data/instance-id"')" == "$instance" ]]
parent_ip="$(docker inspect "$parent" | jq -r '.[0].NetworkSettings.Networks[].IPAddress')"
if docker exec "$guest" curl --noproxy '*' -sS --max-time 2 "http://$parent_ip:4566/" >/dev/null 2>&1; then
    echo "Metadata access also opened unrelated service traffic." >&2
    exit 1
fi

aws ec2 stop-instances --instance-ids "$instance" >/dev/null
aws ec2 wait instance-stopped --instance-ids "$instance"
aws ec2 start-instances --instance-ids "$instance" >/dev/null
deadline=$((SECONDS + 60))
until [[ "$(docker logs "$guest" | grep -c "^first_metadata=$instance$")" == 2 ]]; do
    (( SECONDS < deadline )) || { echo "EC2 restart lost metadata." >&2; exit 1; }
    sleep 1
done
aws ec2 terminate-instances --instance-ids "$instance" >/dev/null
deadline=$((SECONDS + 60))
until [[ -z "$(docker ps -aq --filter "name=$instance")" ]]; do
    (( SECONDS < deadline )) || { echo "EC2 termination left containers." >&2; exit 1; }
    sleep 1
done
instance=""
aws ec2 delete-security-group --group-id "$group"
aws ec2 delete-subnet --subnet-id "$subnet"
aws ec2 delete-vpc --vpc-id "$vpc"
echo "Published $daemon_architecture helper passed $architecture EC2 boot, metadata, restart and termination."
