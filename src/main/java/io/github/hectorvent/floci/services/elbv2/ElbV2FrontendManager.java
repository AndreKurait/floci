package io.github.hectorvent.floci.services.elbv2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLiveness;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.NetworkNamespaceLease;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.netty.util.NetUtil;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.KeyCertOptions;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Gives Docker-network clients a distinct, owned ALB destination before TLS negotiation. */
@ApplicationScoped
public class ElbV2FrontendManager implements ContainerTeardown, Resettable {
    private static final String SERVICE = "elbv2-frontend";
    private static final String BALANCER_LABEL = "io.floci.elbv2.frontend";
    private static final String LISTENER_LABEL = "io.floci.elbv2.frontend-listener";
    private static final String CONNECTOR = "/tmp/floci-alb-connect.sh";
    private final EmulatorConfig config;
    private final ElbV2VpcNetworkManager vpcNetworks;
    private final Vertx vertx;
    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final DockerClient docker;
    private final Map<String, Frontend> frontends = new LinkedHashMap<>();
    private boolean recovered;
    private boolean draining;

    public ElbV2FrontendManager(EmulatorConfig config, ElbV2VpcNetworkManager vpcNetworks,
                                Vertx vertx, ContainerBuilder containers, ContainerLifecycleManager lifecycle) {
        this.config = config;
        this.vpcNetworks = vpcNetworks;
        this.vertx = vertx;
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.docker = lifecycle.getDockerClient();
    }

    boolean enabled() {
        return vpcNetworks.enabled();
    }

    synchronized List<String> addresses(String balancerArn) {
        Frontend frontend = frontends.get(balancerArn);
        return draining || frontend == null || frontend.listeners.isEmpty()
                ? List.of() : List.of(frontend.address);
    }

    synchronized boolean ownsAddress(String address) {
        return !draining && frontends.values().stream()
                .anyMatch(frontend -> !frontend.listeners.isEmpty() && frontend.address.equals(address));
    }

    synchronized void putListener(String balancerArn, String listenerArn, int port,
                                   KeyCertOptions certificates, Handler<HttpServerRequest> handler) {
        if (!enabled()) {
            return;
        }
        if (draining) {
            throw new IllegalStateException("ALB frontend networking is shutting down");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("An ALB listener requires a valid TCP port");
        }
        recoverOrphans();
        Frontend frontend = frontends.computeIfAbsent(balancerArn, this::createFrontend);
        PublishedListener current = frontend.listeners.get(listenerArn);
        if (current != null && current.port == port && current.secure == (certificates != null)) {
            ContainerLiveness liveness = lifecycle.probeContainer(current.proxy);
            if (liveness == ContainerLiveness.UNKNOWN) {
                throw new IllegalStateException("Cannot verify the ALB frontend listener");
            }
            if (liveness == ContainerLiveness.RUNNING) {
                if (certificates != null) {
                    current.transport.reload(certificates);
                }
                return;
            }
        }
        if (frontend.listeners.entrySet().stream().anyMatch(entry ->
                !entry.getKey().equals(listenerArn) && entry.getValue().port == port)) {
            throw new IllegalStateException("Another listener owns this ALB frontend port");
        }
        if (current != null) {
            closeListener(current);
            frontend.listeners.remove(listenerArn);
        }
        ElbV2ListenerTransport transport = await(ElbV2ListenerTransport.start(
                vertx, frontend.controller, frontend.address, port, certificates, handler));
        String proxy = null;
        try {
            Map<String, String> labels = labels(balancerArn);
            labels.put(LISTENER_LABEL, listenerArn);
            ContainerSpec spec = containers.newContainer(config.network().securityGroupEnforcement().helperImage())
                    .withName(ContainerStorageHelper.resourceName(config, SERVICE, null, UUID.randomUUID().toString()))
                    .withNetworkMode("container:" + frontend.namespace.id()).withUser("0").withInit()
                    .withEntrypoint(List.of("sh", "-c"))
                    .withCmd(List.of("exec socat TCP4-LISTEN:" + port
                            + ",reuseaddr,fork EXEC:'sh " + CONNECTOR + "',pipes"))
                    .withLabels(labels).build();
            proxy = lifecycle.create(spec);
            RetryingTarCopier.copyBytes(docker, proxy, "/tmp", "floci-alb-connect.sh",
                    connector(frontend.controller, transport.controlPort()).getBytes(StandardCharsets.US_ASCII), 0600);
            lifecycle.startCreated(proxy, spec);
            awaitListener(proxy, port);
            frontend.listeners.put(listenerArn, new PublishedListener(proxy, port, certificates != null, transport));
        } catch (RuntimeException failure) {
            if (proxy != null) {
                try {
                    lifecycle.removeIfExistsStrict(proxy);
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            try {
                await(transport.close());
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    synchronized void removeListener(String balancerArn, String listenerArn) {
        Frontend frontend = frontends.get(balancerArn);
        if (frontend == null) {
            return;
        }
        PublishedListener listener = frontend.listeners.get(listenerArn);
        if (listener != null) {
            closeListener(listener);
            frontend.listeners.remove(listenerArn);
        }
        if (frontend.listeners.isEmpty()) {
            frontend.namespace.requireUnused(docker);
            lifecycle.removeIfExistsStrict(frontend.namespace.id());
            frontends.remove(balancerArn, frontend);
        }
    }

    private void closeListener(PublishedListener listener) {
        lifecycle.removeIfExistsStrict(listener.proxy);
        await(listener.transport.close());
    }

    private Frontend createFrontend(String balancerArn) {
        String network = config.services().dockerNetwork().orElse("bridge");
        ContainerSpec spec = containers.newContainer(config.network().securityGroupEnforcement().helperImage())
                .withName(ContainerStorageHelper.resourceName(config, SERVICE, null, UUID.randomUUID().toString()))
                .withDockerNetwork(config.services().dockerNetwork()).withInit()
                .withEntrypoint(List.of("tail")).withCmd(List.of("-f", "/dev/null"))
                .withLabels(labels(balancerArn)).build();
        String id = lifecycle.createAndStart(spec).containerId();
        try {
            InspectContainerResponse inspected = docker.inspectContainerCmd(id).exec();
            if (!Boolean.TRUE.equals(inspected.getState().getRunning())
                    || !inspected.getConfig().getLabels().entrySet().containsAll(labels(balancerArn).entrySet())) {
                throw new IllegalStateException("ALB frontend namespace is not running with its owner identity");
            }
            List<String> addresses = new ArrayList<>();
            for (Map.Entry<String, ContainerNetwork> attached : inspected.getNetworkSettings().getNetworks().entrySet()) {
                if (network.equals(attached.getKey()) || network.equals(attached.getValue().getNetworkID())) {
                    addresses.add(attached.getValue().getIpAddress());
                }
            }
            if (addresses.size() != 1 || !NetUtil.isValidIpV4Address(addresses.getFirst())) {
                throw new IllegalStateException("ALB frontend needs one address on its selected Docker network");
            }
            String address = addresses.getFirst();
            return new Frontend(new NetworkNamespaceLease(id, inspected.getName()), address, controllerAddress(address));
        } catch (RuntimeException failure) {
            try {
                new NetworkNamespaceLease(id, null).requireUnused(docker);
                lifecycle.removeIfExistsStrict(id);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private Map<String, String> labels(String balancerArn) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(balancerArn);
        Map<String, String> labels = new LinkedHashMap<>(
                ContainerStorageHelper.resourceIdentityLabels(SERVICE, balancerArn, arn.accountId(), arn.region()));
        labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
        labels.put(BALANCER_LABEL, balancerArn);
        return labels;
    }

    static String connector(String controller, int port) {
        if (!NetUtil.isValidIpV4Address(controller) || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid ALB listener controller endpoint");
        }
        return """
                set -eu
                request_dir=$(mktemp -d /tmp/floci-alb-request.XXXXXX)
                writer_pid=
                trap 'if [ -n "$writer_pid" ]; then
                    kill "$writer_pid" 2>/dev/null || true
                    wait "$writer_pid" 2>/dev/null || true
                fi
                rm -f "$request_dir/input"
                rmdir "$request_dir"' EXIT
                mkfifo "$request_dir/input"
                exec 3<&0
                {
                    printf 'PROXY TCP4 %%s %%s %%s %%s\\r\\n' "$SOCAT_PEERADDR" "$SOCAT_SOCKADDR" \
                "$SOCAT_PEERPORT" "$SOCAT_SOCKPORT"
                    exec cat <&3
                } > "$request_dir/input" &
                writer_pid=$!
                socat - TCP4:%s:%d < "$request_dir/input"
                """.formatted(controller, port);
    }

    private static String controllerAddress(String frontend) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName(frontend), 443);
            String address = socket.getLocalAddress().getHostAddress();
            if (!NetUtil.isValidIpV4Address(address) || "0.0.0.0".equals(address)) {
                throw new IllegalStateException("No IPv4 controller route to the ALB frontend");
            }
            return address;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot resolve the ALB frontend controller address", failure);
        }
    }

    private void awaitListener(String proxy, int port) {
        String command = "for i in $(seq 1 50); do ss -ltnH 'sport = :" + port
                + "' | grep -q . && exit 0; sleep 0.1; done; exit 1";
        ContainerExec.Result result = ContainerExec.run(docker, proxy, new String[]{"sh", "-c", command}, 6)
                .throwIfTimedOut(proxy);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("ALB frontend listener did not become ready");
        }
    }

    private void recoverOrphans() {
        if (!recovered) {
            removeOrphanContainers();
            recovered = true;
        }
    }

    private void removeOrphanContainers() {
        Map<String, String> selector = Map.of(
                ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                ContainerStorageHelper.SERVICE_LABEL, SERVICE);
        List<Container> selected = docker.listContainersCmd().withShowAll(true).withLabelFilter(selector).exec().stream()
                .filter(container -> container.getLabels() != null
                        && container.getLabels().entrySet().containsAll(selector.entrySet())).toList();
        for (Container container : selected) {
            if (container.getLabels().containsKey(LISTENER_LABEL)) {
                lifecycle.removeIfExistsStrict(container.getId());
            }
        }
        for (Container container : selected) {
            if (!container.getLabels().containsKey(LISTENER_LABEL)) {
                String[] names = container.getNames();
                new NetworkNamespaceLease(container.getId(), names == null || names.length == 0 ? null : names[0])
                        .requireUnused(docker);
                lifecycle.removeIfExistsStrict(container.getId());
            }
        }
    }

    @Override
    public synchronized void beginTeardown() {
        draining = true;
    }

    @Override
    public synchronized void stopManagedContainers() {
        draining = true;
        if (!enabled() && frontends.isEmpty() && !recovered) {
            return;
        }
        List<RuntimeException> failures = new ArrayList<>();
        for (Frontend frontend : frontends.values()) {
            for (PublishedListener listener : frontend.listeners.values()) {
                try {
                    closeListener(listener);
                } catch (RuntimeException failure) {
                    failures.add(failure);
                }
            }
        }
        try {
            removeOrphanContainers();
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
        if (!failures.isEmpty()) {
            IllegalStateException failure = new IllegalStateException("ALB frontend cleanup did not complete");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
        frontends.clear();
    }

    @Override
    public synchronized void clear() {
        stopManagedContainers();
    }

    @Override
    public synchronized void afterReset() {
        draining = false;
        recovered = false;
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while changing an ALB listener", failure);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot change the ALB listener transport", failure);
        }
    }

    private static final class Frontend {
        private final NetworkNamespaceLease namespace;
        private final String address;
        private final String controller;
        private final Map<String, PublishedListener> listeners = new LinkedHashMap<>();

        private Frontend(NetworkNamespaceLease namespace, String address, String controller) {
            this.namespace = namespace;
            this.address = address;
            this.controller = controller;
        }
    }

    private record PublishedListener(String proxy, int port, boolean secure, ElbV2ListenerTransport transport) {}
}
