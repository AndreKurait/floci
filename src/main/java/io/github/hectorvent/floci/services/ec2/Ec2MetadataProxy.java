package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;

import java.util.Map;
import java.util.Optional;

/**
 * Shared commands and network resolution helpers for installing and starting
 * link-local IMDS routing (169.254.169.254:80) inside containers.
 */
public final class Ec2MetadataProxy {

    private Ec2MetadataProxy() {}

    public static String[] installCommand() {
        return installCommand(false);
    }

    public static String[] ec2InstallCommand() {
        return installCommand(true);
    }

    private static String[] installCommand(boolean ec2Routing) {
        String routingProbe = ec2Routing ? " && command -v iptables >/dev/null 2>&1" : "";
        String routingPackage = ec2Routing ? " iptables" : "";
        String dnfRoutingPackage = ec2Routing ? " iptables-nft" : "";
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                "unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME",
                "if command -v ip >/dev/null 2>&1 && command -v socat >/dev/null 2>&1 && command -v curl >/dev/null 2>&1"
                        + routingProbe + "; then exit 0; fi",
                "if command -v apt-get >/dev/null 2>&1; then",
                "  apt-get update -qq >/dev/null",
                "  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends iproute2 socat curl ca-certificates"
                        + routingPackage + " >/dev/null",
                "elif command -v dnf >/dev/null 2>&1; then",
                // --allowerasing lets dnf swap the curl-minimal that
                // public.ecr.aws/amazonlinux/amazonlinux:2023 ships by default for the full
                // curl package this proxy needs. Without it, dnf aborts the whole transaction
                // on a curl/curl-minimal conflict and iproute+socat never install either, even
                // though neither of them conflicts with anything.
                "  dnf install -y --allowerasing iproute socat curl ca-certificates" + dnfRoutingPackage + " >/dev/null",
                // Same gap as the sshd probe: Amazon Linux 2 has only yum, so on an instance
                // launched from ami-amazonlinux2 this chain reached its else branch and exited 1
                // with "No supported package manager found for IMDS proxy dependencies",
                // leaving the instance without a link-local IMDS endpoint.
                "elif command -v yum >/dev/null 2>&1; then",
                "  yum install -y iproute socat curl ca-certificates" + routingPackage + " >/dev/null",
                "elif command -v apk >/dev/null 2>&1; then",
                "  apk add --no-cache iproute2 socat curl ca-certificates" + routingPackage + " >/dev/null",
                "else",
                "  echo 'No supported package manager found for IMDS proxy dependencies' >&2",
                "  exit 1",
                "fi")};
    }

    public static String[] startCommand(String flociHost, int imdsPort) {
        return startCommand("imds", "169.254.169.254", 80, flociHost, imdsPort,
                imdsProbeCommand("http://169.254.169.254"));
    }

    public static String[] ec2RoutingCommand(String flociHost, int imdsPort) {
        if (flociHost == null || flociHost.length() > 253
                || !flociHost.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")
                || imdsPort < 1 || imdsPort > 65535) {
            throw new IllegalArgumentException("IMDS requires an IPv4 address or hostname and a valid port");
        }
        // Resolve inside the guest: host.docker.internal may exist only in its /etc/hosts.
        String resolveTarget = flociHost.matches("[0-9.]+") ? "target_ip='" + flociHost + "'"
                : "target_ip=$(curl --noproxy '*' -sS --max-time 2 -o /dev/null -w '%{remote_ip}'"
                + " 'http://" + flociHost + ":" + imdsPort + "/latest/meta-data/instance-id')";
        String rule = "-d 169.254.169.254/32 -p tcp --dport 80 -m comment --comment floci-imds"
                + " -j DNAT --to-destination \"$target_ip:" + imdsPort + "\"";
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                "unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME",
                "refuse() { echo \"$1\" >&2; exit 1; }",
                "addresses=$(ip -4 -o addr show)",
                "if printf '%s\\n' \"$addresses\" | grep -Eq 'inet 169\\.254\\.169\\.254/'; then",
                "  refuse 'IMDS refuses an existing local 169.254.169.254 address; recreate the guest without the legacy proxy'",
                "fi",
                resolveTarget,
                "case \"$target_ip\" in ''|*[!0-9.]*|.*|*.|*..*) refuse 'IMDS target did not resolve to IPv4' ;; esac",
                "saved_ifs=$IFS; IFS=.; set -- $target_ip; IFS=$saved_ifs",
                "[ \"$#\" -eq 4 ] || refuse 'Invalid IMDS IPv4 target'",
                "for octet in \"$@\"; do",
                "  case \"$octet\" in ''|0[0-9]*|[0-9][0-9][0-9][0-9]*) refuse 'Invalid IMDS IPv4 target' ;; esac",
                "  [ \"$octet\" -le 255 ] || refuse 'Invalid IMDS IPv4 target'",
                "done",
                "[ \"$1\" -ge 1 ] && [ \"$1\" -le 223 ] && [ \"$1\" -ne 127 ]"
                        + " || refuse 'IMDS target must be a remote unicast IPv4 address'",
                "case \"$target_ip\" in 169.254.*) refuse 'IMDS target must not be link-local' ;; esac",
                "if printf '%s\\n' \"$addresses\" | grep -Fq \"inet $target_ip/\"; then",
                "  refuse 'IMDS target must not be a guest interface address'",
                "fi",
                // Source selection precedes OUTPUT DNAT. With another default bridge, the
                // original link-local route otherwise selects a foreign source that Docker
                // masquerades when DNAT reroutes the packet to the metadata bridge.
                "target_route=$(ip -4 route get \"$target_ip\")",
                "route_device=; route_source=",
                "set -- $target_route",
                "while [ \"$#\" -gt 0 ]; do",
                "  case \"$1\" in dev) route_device=${2-} ;; src) route_source=${2-} ;; esac",
                "  shift",
                "done",
                "case \"$route_device\" in ''|lo|*[!A-Za-z0-9_.:-]*) refuse 'Invalid IMDS route device' ;; esac",
                "case \"$route_source\" in ''|*[!0-9.]*) refuse 'Invalid IMDS route source' ;; esac",
                "ip -4 -o addr show dev \"$route_device\" | grep -Fq \"inet $route_source/\""
                        + " || refuse 'IMDS route source is not on its selected guest interface'",
                "check_metadata_route() {",
                "  set -- $(ip -4 route show table main exact 169.254.169.254/32)",
                "  [ \"$#\" -eq 7 ] || return 1",
                "  { [ \"$1\" = 169.254.169.254 ] || [ \"$1\" = 169.254.169.254/32 ]; }"
                        + " && [ \"$2\" = dev ] && [ \"$3\" = \"$route_device\" ] || return 1",
                "  [ \"$4\" = scope ] && [ \"$5\" = link ] && [ \"$6\" = src ] && [ \"$7\" = \"$route_source\" ]",
                "}",
                "existing_route=$(ip -4 route show table main exact 169.254.169.254/32)",
                "if [ -n \"$existing_route\" ]; then",
                "  check_metadata_route || refuse 'Conflicting existing IMDS host route'",
                "fi",
                "rules=$(iptables -w 2 -t nat -S OUTPUT)",
                "owned_count=$(printf '%s\\n' \"$rules\" | grep -Ec -- '--comment \"?floci-imds\"?( |$)' || true)",
                "case \"$owned_count\" in",
                "  0) : ;;",
                "  1) iptables -w 2 -t nat -C OUTPUT " + rule
                        + " || refuse 'Conflicting existing Floci IMDS routing rule' ;;",
                "  *) refuse 'Multiple existing Floci IMDS routing rules' ;;",
                "esac",
                "if [ -z \"$existing_route\" ]; then",
                "  ip -4 route add 169.254.169.254/32 dev \"$route_device\" scope link src \"$route_source\"",
                "fi",
                "check_metadata_route || refuse 'IMDS host route changed during configuration'",
                "if [ \"$owned_count\" -eq 0 ]; then",
                "  iptables -w 2 -t nat -I OUTPUT 1 " + rule,
                "fi",
                "rules=$(iptables -w 2 -t nat -S OUTPUT)",
                "owned_count=$(printf '%s\\n' \"$rules\" | grep -Ec -- '--comment \"?floci-imds\"?( |$)' || true)",
                "[ \"$owned_count\" -eq 1 ] || refuse 'Floci IMDS routing changed during configuration'",
                "iptables -w 2 -t nat -C OUTPUT " + rule,
                "for i in 1 2 3 4 5 6; do",
                "  " + imdsProbeCommand("http://169.254.169.254"),
                "  sleep 1",
                "done",
                "refuse 'IMDSv2 routing readiness failed'")};
    }

    static String imdsProbeCommand(String endpoint) {
        return "token=$(curl --noproxy '*' -fsS --max-time 1 -X PUT "
                + "-H 'X-aws-ec2-metadata-token-ttl-seconds: 60' " + endpoint + "/latest/api/token)"
                + " && [ -n \"$token\" ]"
                + " && curl --noproxy '*' -fsS --max-time 1 -H \"X-aws-ec2-metadata-token: $token\" "
                + endpoint + "/latest/meta-data/instance-id >/dev/null && exit 0";
    }

    public static String[] podIdentityStartCommand(String flociHost, int flociPort) {
        return startCommand("pod-identity", "169.254.170.23", 80, flociHost, flociPort,
                "[ \"$(curl -s -o /dev/null -w '%{http_code}' --max-time 1 http://169.254.170.23/v1/credentials)\" != \"000\" ] && exit 0");
    }

    public static String[] startCommand(String name, String bindIp, int bindPort,
                                        String targetHost, int targetPort, String probeCommand) {
        String pidFile = "/tmp/floci-" + name + "-proxy.pid";
        String logFile = "/tmp/floci-" + name + "-proxy.log";
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                "unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME",
                "ip addr show dev lo | grep -q '" + bindIp + "/32' || ip addr add " + bindIp + "/32 dev lo",
                "if [ -f " + pidFile + " ] && kill -0 \"$(cat " + pidFile + ")\" 2>/dev/null; then",
                "  exit 0",
                "fi",
                "nohup socat TCP-LISTEN:" + bindPort + ",bind=" + bindIp + ",fork,reuseaddr TCP:"
                        + targetHost + ":" + targetPort + " >" + logFile + " 2>&1 &",
                "echo $! > " + pidFile,
                "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do",
                "  " + probeCommand,
                "  sleep 1",
                "done",
                "cat " + logFile + " >&2 || true",
                "exit 1")};
    }

    public static Optional<String> preferredMetadataSourceIp(Map<String, ContainerNetwork> networks) {
        if (networks == null || networks.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> configuredNetworkIp = networks.entrySet().stream()
                .filter(entry -> !"bridge".equals(entry.getKey()))
                .map(Map.Entry::getValue)
                .map(ContainerNetwork::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst();
        if (configuredNetworkIp.isPresent()) {
            return configuredNetworkIp;
        }
        ContainerNetwork bridge = networks.get("bridge");
        if (bridge != null && bridge.getIpAddress() != null && !bridge.getIpAddress().isBlank()) {
            return Optional.of(bridge.getIpAddress());
        }
        return networks.values().stream()
                .map(ContainerNetwork::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst();
    }
}
