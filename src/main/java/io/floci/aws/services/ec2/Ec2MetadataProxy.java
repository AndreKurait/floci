package io.floci.aws.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared commands and network resolution helpers for link-local metadata proxies.
 */
public final class Ec2MetadataProxy {

    private static final int EC2_IMDS_RELAY_PORT = 42169;

    /** A shell function: whether process {@code $1} is a socat whose arguments hold {@code $2}. */
    private static final List<String> IS_SOCAT_LISTENER = List.of(
            "is_socat_listener() {",
            "  [ \"$(cat \"/proc/$1/comm\" 2>/dev/null)\" = socat ]"
                    + " && tr '\\0' ' ' 2>/dev/null <\"/proc/$1/cmdline\" | grep -Fq \"$2\"",
            "}");

    /**
     * Run first in the shells that install and start these proxies and in the sshd install probe.
     * {@code docker exec} hands the shell the image's environment, so an application's library
     * search path or Python installation would otherwise be what the package manager, {@code socat}
     * and {@code curl} load. Only the helper shell changes; the container's configured environment
     * and its processes keep these variables.
     */
    static final String CLEAR_APPLICATION_PATHS = "unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME";

    private Ec2MetadataProxy() {}

    /**
     * Moves the exec'd shell, and everything it starts, out of the container's root cgroup into the
     * leaf {@code /sys/fs/cgroup/floci}. A {@code docker exec} lands in the root of the container's
     * cgroup namespace, and a systemd guest runs with a private one. cgroup v2 will not enable
     * controllers in a cgroup's {@code subtree_control} while that cgroup holds processes, so a
     * long-lived socat left in the root kept it empty, and kubelet with the cgroupfs driver then
     * failed to create {@code kubepods.slice} ("pids.max: no such file or directory").
     *
     * <p>Acts only when this shell sits at the root of a cgroup v2 namespace ({@code 0::/}) whose
     * cgroupfs is writable. A host-namespace container reports its full path, cgroup v1 reports one
     * line per hierarchy, and a read-only mount fails {@code -w} (access(2) reports EROFS, even for
     * root); all three run as before. Any {@code mkdir} or write that still fails is swallowed.
     * A plain directory rather than {@code systemd-run}, because the proxy starts while the guest's
     * systemd may still be booting, and systemd neither reaps nor moves a cgroup it did not create.
     */
    static final String LEAVE_ROOT_CGROUP =
            "if [ \"$(cat /proc/self/cgroup 2>/dev/null)\" = 0::/ ] && [ -w /sys/fs/cgroup/cgroup.procs ]; then"
                    + " { mkdir -p /sys/fs/cgroup/floci && echo $$ >/sys/fs/cgroup/floci/cgroup.procs; } 2>/dev/null || true;"
                    + " fi";

    static String[] installEc2Command() {
        List<String> lines = new ArrayList<>(List.of(
                "set -eu",
                LEAVE_ROOT_CGROUP,
                CLEAR_APPLICATION_PATHS,
                "if command -v ip >/dev/null 2>&1 && command -v socat >/dev/null 2>&1"
                        + " && command -v curl >/dev/null 2>&1; then exit 0; fi",
                "if command -v apt-get >/dev/null 2>&1; then",
                "  apt-get update -qq >/dev/null",
                "  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends"
                        + " iproute2 socat curl ca-certificates >/dev/null",
                "elif command -v dnf >/dev/null 2>&1; then",
                // --allowerasing lets dnf swap the curl-minimal that
                // public.ecr.aws/amazonlinux/amazonlinux:2023 ships by default for the full
                // curl package this proxy needs. Without it, dnf aborts the whole transaction
                // on a curl/curl-minimal conflict and iproute+socat never install either, even
                // though neither of them conflicts with anything.
                "  dnf install -y --allowerasing iproute socat curl ca-certificates >/dev/null",
                // Same gap as the sshd probe: Amazon Linux 2 has only yum, so on an instance
                // launched from ami-amazonlinux2 this chain reached its else branch and exited 1
                // with "No supported package manager found for IMDS proxy dependencies",
                // leaving the instance without a link-local IMDS endpoint.
                "elif command -v yum >/dev/null 2>&1; then",
                "  yum install -y iproute socat curl ca-certificates >/dev/null",
                "elif command -v apk >/dev/null 2>&1; then",
                "  apk add --no-cache iproute2 socat curl ca-certificates >/dev/null",
                "else",
                "  echo 'No supported package manager found for IMDS proxy dependencies' >&2",
                "  exit 1",
                "fi"));
        // A guest without a NAT tool can still use the pre-upgrade TCP/80 relay.
        lines.add("if ! command -v iptables >/dev/null 2>&1 && ! command -v nft >/dev/null 2>&1; then");
        lines.add("  if command -v apt-get >/dev/null 2>&1; then");
        lines.add("    { apt-get update -qq >/dev/null"
                + " && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends"
                + " iptables >/dev/null; } || true");
        lines.add("  elif command -v dnf >/dev/null 2>&1; then");
        lines.add("    dnf install -y --allowerasing iptables-nft >/dev/null || true");
        lines.add("  elif command -v yum >/dev/null 2>&1; then");
        lines.add("    yum install -y iptables >/dev/null || true");
        lines.add("  elif command -v apk >/dev/null 2>&1; then");
        lines.add("    apk add --no-cache iptables >/dev/null || true");
        lines.add("  fi");
        lines.add("fi");
        return new String[]{"sh", "-c", String.join("\n", lines)};
    }

    static String[] startEc2Command(String flociHost, int imdsPort) {
        return startCommand("imds", "169.254.169.254", EC2_IMDS_RELAY_PORT, flociHost, imdsPort,
                imdsProbeCommand("http://169.254.169.254"));
    }

    /**
     * Ready once an IMDSv2 token reads the instance ID, which also holds on an instance whose
     * HttpTokens is required. A refused or empty token ends the attempt rather than falling back to
     * a tokenless read, which an empty token header would otherwise become. Both requests bypass
     * any HTTP proxy the image configures: the check is of the link-local path itself.
     */
    public static String[] ec2InstallCommand() {
        return installNamespaceCommand();
    }

    private static String[] installNamespaceCommand() {
        String routingProbe = " && command -v iptables >/dev/null 2>&1";
        String routingPackage = " iptables";
        String dnfRoutingPackage = " iptables-nft";
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                CLEAR_APPLICATION_PATHS,
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

    public static String[] ec2RoutingCommand(String flociHost, int imdsPort) {
        return ec2RoutingCommand(flociHost, imdsPort, true);
    }

    static String[] ec2RoutingCommand(String flociHost, int imdsPort, boolean metadataEnabled) {
        if (flociHost == null || flociHost.length() > 253
                || !flociHost.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")
                || imdsPort < 1 || imdsPort > 65535) {
            throw new IllegalArgumentException("IMDS requires an IPv4 address or hostname and a valid port");
        }
        // Resolve inside the namespace: host.docker.internal may exist only in its /etc/hosts.
        // DNS is exempt from security groups; a direct HTTP request to the backend is not.
        String resolveTarget = flociHost.matches("[0-9.]+") ? "target_ip='" + flociHost + "'"
                : "target_ip=$(getent ahostsv4 '" + flociHost + "' | awk 'NR == 1 {print $1; exit}')";
        String rule = "-d 169.254.169.254/32 -p tcp --dport 80 -m comment --comment floci-imds"
                + " -j DNAT --to-destination \"$target_ip:" + imdsPort + "\"";
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                CLEAR_APPLICATION_PATHS,
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
                // Disabled IMDS must still be routed locally rather than escaping to the host.
                metadataEnabled ? ":" : "exit 0",
                "for i in 1 2 3 4 5 6; do",
                "  " + imdsProbeCommand("http://169.254.169.254"),
                "  sleep 1",
                "done",
                "refuse 'IMDSv2 routing readiness failed'")};
    }

    static String imdsProbeCommand(String endpoint) {
        return "token=$(curl -fsS --noproxy '*' --max-time 1 -X PUT "
                + "-H 'X-aws-ec2-metadata-token-ttl-seconds: 60' " + endpoint + "/latest/api/token)"
                + " && [ -n \"$token\" ]"
                + " && curl -fsS --noproxy '*' --max-time 1 -H \"X-aws-ec2-metadata-token: $token\" "
                + endpoint + "/latest/meta-data/instance-id >/dev/null && exit 0";
    }

    private static String[] startCommand(String name, String bindIp, int bindPort,
                                         String targetHost, int targetPort, String probeCommand) {
        String legacyPidFile = relayPidFile(name);
        String pidFile = "/tmp/floci-" + name + "-proxy-" + bindPort + ".pid";
        String logFile = "/tmp/floci-" + name + "-proxy.log";
        String listener = "TCP-LISTEN:" + bindPort + ",bind=" + bindIp + ",";
        String ruleMatch = "-d " + bindIp + "/32 -p tcp --dport 80 -j DNAT --to-destination "
                + bindIp + ":" + bindPort;
        String nftRuleMarker = "floci-imds-relay-" + bindPort;
        List<String> lines = new ArrayList<>(List.of(
                "set -eu",
                LEAVE_ROOT_CGROUP,
                CLEAR_APPLICATION_PATHS,
                "ip addr show dev lo | grep -q '" + bindIp + "/32' || ip addr add " + bindIp + "/32 dev lo"));
        lines.addAll(IS_SOCAT_LISTENER);

        // The writable-layer PID file survives a container restart, so verify the process name and
        // listener arguments before reusing it or retiring a pre-upgrade TCP/80 listener.
        lines.add("legacy_pid=");
        lines.add(socatPidCheck(legacyPidFile, "TCP-LISTEN:80,bind=" + bindIp + ","));
        lines.add("  legacy_pid=$pid");
        lines.add("fi");
        lines.add("proxy_running=0");
        lines.add("new_pid=");
        lines.add("rule_added=0");
        lines.add("rule_backend=");
        lines.add("nft_chain_created=0");
        lines.add("nft_rule_marker=" + nftRuleMarker + "-$$");
        lines.add("legacy_new_pid=");
        lines.add("fallback_legacy=0");
        lines.add("remove_nft_rule() {");
        lines.add("  nft -a list chain ip floci_imds output 2>/dev/null"
                + " | grep -F \"comment \\\"$1\\\"\" | while IFS= read -r rule; do");
        lines.add("    case \"$rule\" in");
        lines.add("      *'# handle '*) nft delete rule ip floci_imds output"
                + " handle \"${rule##*# handle }\" 2>/dev/null || true ;;");
        lines.add("    esac");
        lines.add("  done");
        lines.add("}");
        lines.add("cleanup_new_proxy() {");
        lines.add("  if [ -n \"$new_pid\" ]; then");
        lines.add("    if is_socat_listener \"$new_pid\" '" + listener + "'; then");
        lines.add("      kill \"$new_pid\" 2>/dev/null || true");
        lines.add("    fi");
        lines.add("    if [ \"$(cat " + pidFile + " 2>/dev/null)\" = \"$new_pid\" ]; then");
        lines.add("      rm -f " + pidFile);
        lines.add("    fi");
        lines.add("  fi");
        lines.add("  if [ \"$rule_added\" -eq 1 ]; then");
        lines.add("    if [ \"$rule_backend\" = nft ]; then");
        lines.add("      remove_nft_rule \"$nft_rule_marker\" || true");
        lines.add("    else");
        lines.add("      iptables -t nat -D OUTPUT " + ruleMatch + " 2>/dev/null || true");
        lines.add("    fi");
        lines.add("  fi");
        lines.add("  if [ \"$nft_chain_created\" -eq 1 ]; then");
        lines.add("    nft delete chain ip floci_imds output 2>/dev/null || true");
        lines.add("  fi");
        lines.add("  if [ -n \"$legacy_new_pid\" ]; then");
        lines.add("    if is_socat_listener \"$legacy_new_pid\" 'TCP-LISTEN:80,bind=" + bindIp + ",'; then");
        lines.add("      kill \"$legacy_new_pid\" 2>/dev/null || true");
        lines.add("    fi");
        lines.add("    if [ \"$(cat " + legacyPidFile + " 2>/dev/null)\" = \"$legacy_new_pid\" ]; then");
        lines.add("      rm -f " + legacyPidFile);
        lines.add("    fi");
        lines.add("  fi");
        lines.add("}");
        lines.add(socatPidCheck(pidFile, listener));
        lines.add("  proxy_running=1");
        lines.add("fi");
        lines.add("if [ \"$proxy_running\" -eq 0 ]; then");
        lines.add("  nohup socat " + listener + "fork,reuseaddr TCP:"
                + targetHost + ":" + targetPort + " >" + logFile + " 2>&1 &");
        lines.add("  new_pid=$!");
        lines.add("  echo \"$new_pid\" > " + pidFile);
        lines.add("fi");
        lines.add("if command -v nft >/dev/null 2>&1"
                + " && nft list chain ip floci_imds output 2>/dev/null"
                + " | grep -F 'comment \"" + nftRuleMarker + "'"
                + " | grep -Fq 'ip daddr " + bindIp + " tcp dport 80 dnat to "
                + bindIp + ":" + bindPort + "'; then");
        lines.add("  rule_backend=nft");
        lines.add("fi");
        lines.add("if [ -z \"$rule_backend\" ] && command -v iptables >/dev/null 2>&1; then");
        lines.add("  if iptables -t nat -C OUTPUT " + ruleMatch + " 2>/dev/null; then");
        lines.add("    rule_backend=iptables");
        lines.add("  elif ! { command -v nft >/dev/null 2>&1"
                + " && nft list table ip floci_imds >/dev/null 2>&1; }"
                + " && iptables -t nat -I OUTPUT 1 " + ruleMatch + "; then");
        lines.add("    rule_backend=iptables");
        lines.add("    rule_added=1");
        lines.add("  fi");
        lines.add("fi");
        lines.add("if [ -z \"$rule_backend\" ] && command -v nft >/dev/null 2>&1; then");
        lines.add("  nft_ready=1");
        lines.add("  if ! nft list table ip floci_imds >/dev/null 2>&1; then");
        lines.add("    nft add table ip floci_imds || nft_ready=0");
        lines.add("  fi");
        lines.add("  if [ \"$nft_ready\" -eq 1 ]"
                + " && ! nft list chain ip floci_imds output >/dev/null 2>&1; then");
        lines.add("    if printf '%s\\n'"
                + " 'add chain ip floci_imds output { type nat hook output priority -100; policy accept; }'"
                + " | nft -f -; then");
        lines.add("      nft_chain_created=1");
        lines.add("    else");
        lines.add("      nft_ready=0");
        lines.add("    fi");
        lines.add("  fi");
        lines.add("  if [ \"$nft_ready\" -eq 1 ]; then");
        lines.add("    if printf '%s\\n' \"insert rule ip floci_imds output ip daddr " + bindIp
                + " tcp dport 80 dnat to " + bindIp + ":" + bindPort
                + " comment \\\"$nft_rule_marker\\\"\" | nft -f -; then");
        lines.add("      rule_backend=nft");
        lines.add("      rule_added=1");
        lines.add("    fi");
        lines.add("  fi");
        lines.add("fi");
        lines.add("if [ -z \"$rule_backend\" ] && command -v iptables >/dev/null 2>&1"
                + " && iptables -t nat -I OUTPUT 1 " + ruleMatch + "; then");
        lines.add("  rule_backend=iptables");
        lines.add("  rule_added=1");
        lines.add("fi");
        lines.add("if [ -z \"$rule_backend\" ]; then");
        lines.add("  cleanup_new_proxy");
        lines.add("  fallback_legacy=1");
        lines.add("  if [ -z \"$legacy_pid\" ]; then");
        lines.add("    nohup socat TCP-LISTEN:80,bind=" + bindIp + ",fork,reuseaddr TCP:"
                + targetHost + ":" + targetPort + " >" + logFile + " 2>&1 &");
        lines.add("    legacy_new_pid=$!");
        lines.add("    echo \"$legacy_new_pid\" > " + legacyPidFile);
        lines.add("  fi");
        lines.add("fi");
        lines.add("for i in 1 2 3 4 5 6 7 8 9 10 11 12; do");
        lines.add("  if (" + probeCommand + "); then");
        lines.add("    if [ \"$fallback_legacy\" -eq 0 ] && [ -n \"$legacy_pid\" ]"
                + " && is_socat_listener \"$legacy_pid\" 'TCP-LISTEN:80,bind=" + bindIp + ",'; then");
        lines.add("      kill \"$legacy_pid\" 2>/dev/null || true");
        lines.add("      if [ \"$(cat " + legacyPidFile + " 2>/dev/null)\" = \"$legacy_pid\" ]; then");
        lines.add("        rm -f " + legacyPidFile);
        lines.add("      fi");
        lines.add("    fi");
        lines.add("    exit 0");
        lines.add("  fi");
        lines.add("  sleep 1");
        lines.add("done");
        lines.add("cleanup_new_proxy");
        lines.add("cat " + logFile + " >&2 || true");
        lines.add("exit 1");
        return new String[]{"sh", "-c", String.join("\n", lines)};
    }

    /**
     * Shell lines that retire the relay an older Floci ran as {@code name} on {@code bindIp}, port 80, on an EKS
     * node: the socat its PID file names, while that is still the relay, then the
     * PID file and the loopback address the relay was given. A node without the relay, or without {@code ip}, is
     * left as it is.
     */
    public static List<String> stopRelayLines(String name, String bindIp) {
        String pidFile = relayPidFile(name);
        List<String> lines = new ArrayList<>(IS_SOCAT_LISTENER);
        lines.add(socatPidCheck(pidFile, "TCP-LISTEN:80,bind=" + bindIp + ","));
        lines.add("  kill \"$pid\" 2>/dev/null || true");
        lines.add("fi");
        lines.add("rm -f " + pidFile);
        lines.add("if command -v ip >/dev/null 2>&1 && ip addr show dev lo | grep -q '" + bindIp + "/32'; then");
        lines.add("  ip addr del " + bindIp + "/32 dev lo");
        lines.add("fi");
        return lines;
    }

    private static String relayPidFile(String name) {
        return "/tmp/floci-" + name + "-proxy.pid";
    }

    private static String socatPidCheck(String pidFile, String listener) {
        return "if [ -f " + pidFile + " ] && pid=\"$(cat " + pidFile + ")\""
                + " && is_socat_listener \"$pid\" '" + listener + "'; then";
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
