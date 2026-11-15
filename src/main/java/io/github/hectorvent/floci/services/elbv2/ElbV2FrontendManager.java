package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.LoadBalancerProxySources;
import io.github.hectorvent.floci.config.LoadBalancerProxySources.Backend;
import io.github.hectorvent.floci.config.LoadBalancerProxySources.Destination;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.net.VpcRoutingManager;
import io.github.hectorvent.floci.services.elbv2.ElbV2VpcNetworkManager.Attachment;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** Frontend sockets live in the ALB's protected ENIs, alongside its native backend proxy. */
@ApplicationScoped
public class ElbV2FrontendManager {
    private static final Logger LOG = Logger.getLogger(ElbV2FrontendManager.class);
    private final EmulatorConfig config;
    private final ContainerLifecycleManager lifecycle;
    private final VpcRoutingManager routing;
    private final LoadBalancerProxySources sources;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Listener> listeners = new LinkedHashMap<>();
    private final Map<String, RequestedListener> requested = new LinkedHashMap<>();

    public ElbV2FrontendManager(EmulatorConfig config, ContainerLifecycleManager lifecycle,
                               VpcRoutingManager routing, LoadBalancerProxySources sources) {
        this.config = config;
        this.lifecycle = lifecycle;
        this.routing = routing;
        this.sources = sources;
    }

    boolean enabled() {
        return !config.services().elbv2().mock() && config.services().ec2().vpcNetworks().enabled()
                && config.services().ec2().awsFaithfulPrivateIp()
                && config.network().securityGroupEnforcement().enabled() && routing.enabled()
                && config.tls().enabled() && config.tls().awsHttpsPort() > 0
                && config.tls().awsHttpsPort() != config.port();
    }

    synchronized void prepare(LoadBalancer balancer, NetworkInterface eni, Attachment attachment) {
        if (!enabled()) {
            return;
        }
        String account = AwsArnUtils.parse(balancer.getLoadBalancerArn()).accountId();
        Node node = new Node(account, balancer.getRegion(), balancer.getVpcId(), balancer.getLoadBalancerArn(),
                attachment, eni.getPrivateIpAddress(), "internet-facing".equals(balancer.getScheme()));
        if (nodes.putIfAbsent(attachment.eniId(), node) != null) {
            throw new IllegalStateException("An ALB frontend already owns this interface");
        }
        if (node.publicFacing) {
            routing.attachPublicFrontend(account, node.region, eni, attachment.helperId());
            node.publicTransport = routing.publicFrontendTransport(account, node.region, attachment.eniId())
                    .orElseThrow(() -> new IllegalStateException("ALB public frontend has no native transport"));
        }
    }

    synchronized void reconcile(String eni) {
        Node node = nodes.get(eni);
        if (node != null) {
            node.registered = true;
            synchronize(node);
        }
    }

    /** Select the whole ALB before its asynchronous native sockets are published. */
    synchronized boolean select(String arn, String balancer, int port) {
        new Destination("127.0.0.1", port);
        RequestedListener next = new RequestedListener(balancer, port);
        RequestedListener previous = requested.put(arn, next);
        if (next.equals(previous)) {
            return false;
        }
        invalidate(balancer);
        if (usesSharedListener(balancer)) {
            LOG.infov("ALB {0} retains its shared listeners because port {1} belongs to its backend control socket",
                    balancer, String.valueOf(ElbV2VpcNetworkManager.PROXY_PORT));
        }
        return true;
    }

    synchronized String forget(String arn) {
        RequestedListener previous = requested.remove(arn);
        if (previous != null) {
            invalidate(previous.balancer());
            return previous.balancer();
        }
        return null;
    }

    synchronized boolean usesSharedListener(String balancer) {
        return requested.values().stream().anyMatch(listener -> listener.balancer().equals(balancer)
                && listener.port() == ElbV2VpcNetworkManager.PROXY_PORT);
    }

    private void invalidate(String balancer) {
        // Revoke every subnet admission together, before any Docker work or DNS fallback.
        for (Node node : nodes.values()) {
            if (node.balancer.equals(balancer)) {
                node.ready = false;
                sources.revoke(node);
            }
        }
    }

    synchronized void reconcileBalancer(String balancer) {
        for (Node node : nodes.values()) {
            if (node.balancer.equals(balancer) && node.registered) {
                synchronize(node);
            }
        }
    }

    synchronized void publish(String arn, String balancer, int port, Object owner, Backend backend) {
        if (!backend.current().getAsBoolean()
                || !new RequestedListener(balancer, port).equals(requested.get(arn))) {
            return;
        }
        new Destination("127.0.0.1", port);
        Listener next = new Listener(arn, balancer, port, owner, backend);
        for (Listener current : listeners.values()) {
            if (!current.arn().equals(arn) && current.balancer().equals(balancer) && current.port() == port) {
                throw new IllegalStateException("Another listener owns this ALB frontend port");
            }
        }
        listeners.put(arn, next);
        for (Node node : nodes.values()) {
            if (node.balancer.equals(balancer) && node.registered) {
                synchronize(node);
            }
        }
    }

    synchronized void withdraw(String arn, Object owner) {
        Listener current = listeners.get(arn);
        if (current != null && current.owner() == owner && listeners.remove(arn, current)) {
            for (Node node : nodes.values()) {
                if (node.balancer.equals(current.balancer()) && node.registered) {
                    synchronize(node);
                }
            }
        }
    }

    private void synchronize(Node node) {
        RequestScopes.runAs(node.account, node.region, () -> {
            node.ready = false;
            sources.revoke(node);
            try {
                publishPorts(node, Set.of());
                Map<Integer, Listener> selected = listeners.values().stream()
                        .filter(listener -> node.balancer.equals(listener.balancer())
                                && new RequestedListener(listener.balancer(), listener.port())
                                .equals(requested.get(listener.arn()))
                                && listener.backend().current().getAsBoolean())
                        .collect(Collectors.toMap(Listener::port, value -> value, (first, second) -> {
                            throw new IllegalStateException("An ALB frontend port has conflicting listeners");
                        }, LinkedHashMap::new));
                long expected = requested.values().stream()
                        .filter(listener -> listener.balancer().equals(node.balancer)).count();
                boolean complete = !usesSharedListener(node.balancer) && selected.size() == expected;
                if (!complete) {
                    selected.clear();
                }
                for (int port : Set.copyOf(node.running)) {
                    if (!selected.containsKey(port)) {
                        stopListener(node, port);
                        node.running.remove(port);
                    }
                }
                for (int port : selected.keySet()) {
                    if (!node.running.contains(port)) {
                        startListener(node, port);
                        node.running.add(port);
                    }
                }
                Map<Destination, Backend> admitted = new LinkedHashMap<>();
                for (Map.Entry<Integer, Listener> entry : selected.entrySet()) {
                    Backend target = entry.getValue().backend();
                    BooleanSupplier current = () -> node.ready && target.current().getAsBoolean();
                    for (String address : node.addresses()) {
                        admitted.put(new Destination(address, entry.getKey()),
                                new Backend(target.port(), target.tls(), current));
                    }
                }
                if (complete) {
                    sources.register(node, node.attachment.transportAddress(), admitted);
                }
                publishPorts(node, selected.keySet());
                node.ready = complete;
            } catch (RuntimeException failure) {
                sources.revoke(node);
                try {
                    publishPorts(node, Set.of());
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        });
    }

    private void publishPorts(Node node, Set<Integer> ports) {
        if (node.publicFacing && node.registered) {
            routing.publishFrontendListeners(node.account, node.region, node.attachment.eniId(),
                    node.attachment.helperId(), ports, node.attachment.transportAddress());
        }
    }

    /** Withdraw identity before firewall retirement removes the helper and releases its route lease. */
    synchronized void retire(String eni) {
        Node node = nodes.get(eni);
        if (node == null) {
            return;
        }
        node.ready = false;
        sources.revoke(node);
        RequestScopes.runAs(node.account, node.region, () -> {
            publishPorts(node, Set.of());
            for (int port : Set.copyOf(node.running)) {
                stopListener(node, port);
                node.running.remove(port);
            }
        });
        nodes.remove(eni, node);
    }

    void releaseRoute(String account, String region, String eni) {
        routing.unregister(account, region, eni);
    }

    synchronized List<String> addresses(LoadBalancer balancer, ClientVpc origin) {
        String account = AwsArnUtils.parse(balancer.getLoadBalancerArn()).accountId();
        if (!"internet-facing".equals(balancer.getScheme()) && (origin == null
                || !new ClientVpc(account, balancer.getRegion(), balancer.getVpcId()).equals(origin))) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Node node : nodes.values()) {
            if (!node.balancer.equals(balancer.getLoadBalancerArn()) || !node.ready) {
                continue;
            }
            if (!node.publicFacing) {
                result.add(node.privateAddress);
            } else {
                routing.publicFrontendTransport(node.account, node.region, node.attachment.eniId())
                        .ifPresent(transport -> {
                            if (origin == null) {
                                result.add(transport);
                            } else {
                                routing.publicAddress(node.account, node.region, node.attachment.eniId())
                                        .ifPresent(result::add);
                            }
                        });
            }
        }
        return List.copyOf(result);
    }

    private void startListener(Node node, int port) {
        String stem = stem(port);
        stopListener(node, port);
        copy(node, stem + "-connect.sh", connector(node.attachment.controllerAddress(),
                node.attachment.transportAddress(), config.tls().awsHttpsPort()));
        copy(node, stem + ".sh", listenerScript(port, node.addresses()));
        execute(node, "sh", "-c", "setsid sh \"$1\" >\"$2\" 2>&1 </dev/null &",
                "floci-alb-start", "/tmp/" + stem + ".sh", "/tmp/" + stem + ".log");
        String checks = node.addresses().stream().map(address -> "ss -H -ltn 'sport = :" + port
                + "' | awk '{print $4}' | grep -Fx '" + address + ":" + port + "' >/dev/null")
                .collect(Collectors.joining(" && "));
        execute(node, "sh", "-c", "for i in $(seq 1 50); do "
                + "test -s /tmp/" + stem + ".pid && " + checks + " && exit 0; sleep 0.1; done; exit 1");
    }

    private void stopListener(Node node, int port) {
        execute(node, "sh", "-c", """
                set -eu
                file="$1"
                if [ ! -s "$file.pid" ]; then exit 0; fi
                read -r pid started < "$file.pid"
                case "$pid:$started" in *[!0-9:]*|:*) exit 1;; esac
                test "$pid" -gt 1
                group_alive() {
                    for status_file in /proc/[0-9]*/stat; do
                        if IFS= read -r process_status < "$status_file" 2>/dev/null; then
                            set -- ${process_status##*) }
                            if [ "$3" = "$pid" ] && [ "$1" != Z ]; then return 0; fi
                        fi
                    done
                    return 1
                }
                if [ -r "/proc/$pid/stat" ]; then
                    test "$(awk '{print $22}' "/proc/$pid/stat")" = "$started"
                    test "$(awk '{print $5}' "/proc/$pid/stat")" = "$pid"
                    if [ "$(awk '{print $3}' "/proc/$pid/stat")" != Z ]; then
                        tr '\\000' '\\n' < "/proc/$pid/cmdline" | grep -Fx "$file.sh" >/dev/null
                    fi
                    if ! kill -TERM "-$pid"; then
                        if group_alive; then exit 1; fi
                    fi
                elif group_alive; then
                    exit 1
                fi
                for i in $(seq 1 50); do
                    if ! group_alive; then rm -f "$file.pid"; exit 0; fi
                    sleep 0.1
                done
                exit 1
                """, "floci-alb-stop", "/tmp/" + stem(port));
    }

    static String connector(String controller, String source, int port) {
        new Destination(controller, port);
        new Destination(source, port);
        return """
                set -eu
                {
                    printf 'PROXY TCP4 %%s %%s %%s %%s\\r\\n' "$SOCAT_PEERADDR" "$SOCAT_SOCKADDR" \
                "$SOCAT_PEERPORT" "$SOCAT_SOCKPORT"
                    cat
                } | socat - TCP4:%s:%d,bind=%s
                """.formatted(controller, port, source);
    }

    static String listenerScript(int port, Set<String> addresses) {
        String stem = stem(port);
        StringBuilder script = new StringBuilder("set -eu\npids=''\n");
        for (String address : addresses.stream().sorted().toList()) {
            new Destination(address, port);
            script.append("socat -d -d TCP4-LISTEN:").append(port).append(",bind=").append(address)
                    .append(",reuseaddr,fork EXEC:'sh /tmp/").append(stem).append("-connect.sh',pipes &\n")
                    .append("pids=\"$pids $!\"\n");
        }
        return script.append("printf '%s %s\\n' \"$$\" \"$(awk '{print $22}' /proc/$$/stat)\" > /tmp/")
                .append(stem).append(".pid\n")
                .append("trap 'kill $pids 2>/dev/null || true' EXIT\n")
                .append("while :; do for pid in $pids; do kill -0 \"$pid\" 2>/dev/null || exit 1; done; sleep 0.1; done\n")
                .toString();
    }

    private static String stem(int port) {
        new Destination("127.0.0.1", port);
        return "floci-alb-frontend-" + port;
    }

    private void copy(Node node, String name, String contents) {
        RetryingTarCopier.copyBytes(lifecycle.getDockerClient(), node.attachment.helperId(), "/tmp", name,
                contents.getBytes(StandardCharsets.UTF_8), 0600);
    }

    private void execute(Node node, String... argv) {
        ContainerExec.Result result = ContainerExec.run(lifecycle.getDockerClient(), node.attachment.helperId(),
                argv, 10).throwIfTimedOut(node.attachment.helperId());
        if (result.exitCode() != 0) {
            throw new IllegalStateException("ALB frontend command failed: " + result.summary());
        }
    }

    private record Listener(String arn, String balancer, int port, Object owner, Backend backend) {}
    private record RequestedListener(String balancer, int port) {}

    private static final class Node {
        final String account;
        final String region;
        final String vpc;
        final String balancer;
        final Attachment attachment;
        final String privateAddress;
        final boolean publicFacing;
        final Set<Integer> running = new LinkedHashSet<>();
        String publicTransport;
        boolean registered;
        volatile boolean ready;

        Node(String account, String region, String vpc, String balancer, Attachment attachment,
             String privateAddress, boolean publicFacing) {
            this.account = account;
            this.region = region;
            this.vpc = vpc;
            this.balancer = balancer;
            this.attachment = attachment;
            this.privateAddress = privateAddress;
            this.publicFacing = publicFacing;
        }

        Set<String> addresses() {
            Set<String> result = new LinkedHashSet<>();
            result.add(privateAddress);
            if (publicFacing) {
                result.add(attachment.transportAddress());
                if (publicTransport != null) {
                    result.add(publicTransport);
                }
            }
            return Set.copyOf(result);
        }
    }
}
