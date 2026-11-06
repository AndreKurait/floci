package io.github.hectorvent.floci.services.ec2.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.NetworkNamespaceLease;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A managed public destination. Native routes and NATs remain outside its TCP forwarding. */
final class VpcAwsApiBridge {
    private static final String SERVICE = "vpc-aws-api";
    private final EmulatorConfig config;
    private final DockerClient docker;
    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final ObjectMapper mapper;
    private final ContainerReachableEndpoint endpoint;
    private NetworkNamespaceLease namespace;
    private String transport;
    private String device;
    private String backend;
    private int httpBackendPort;
    private int httpsBackendPort;
    private String applied;
    private boolean listenersStarted;

    VpcAwsApiBridge(EmulatorConfig config, DockerClient docker, ContainerBuilder containers,
                    ContainerLifecycleManager lifecycle, ObjectMapper mapper, ContainerReachableEndpoint endpoint) {
        this.config = config;
        this.docker = docker;
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.mapper = mapper;
        this.endpoint = endpoint;
    }

    boolean enabled() {
        return config.network().vpcRouting().publicAwsApi();
    }

    String transport() {
        return transport;
    }

    String device() {
        return device;
    }

    void ensure(String network) {
        if (namespace != null) {
            if (!Boolean.TRUE.equals(docker.inspectContainerCmd(namespace.id()).exec().getState().getRunning())) {
                throw new IllegalStateException("The public AWS API bridge stopped");
            }
            return;
        }
        if (endpoint == null) {
            throw new IllegalStateException("The public AWS API bridge has no container endpoint resolver");
        }
        String selectedHost = config.network().vpcRouting().publicAwsApiUpstreamHost()
                .map(VpcRouteSelection::ipv4).orElse(null);
        URI target = URI.create(selectedHost == null ? endpoint.baseUrl(false)
                : "http://" + selectedHost + ":" + config.network().vpcRouting().publicAwsApiUpstreamHttpPort());
        if (!"http".equals(target.getScheme()) || target.getHost() == null
                || target.getPort() < 1 || target.getPort() > 65535) {
            throw new IllegalStateException("Invalid container-reachable AWS API endpoint");
        }
        httpBackendPort = target.getPort();
        httpsBackendPort = selectedHost == null ? httpBackendPort
                : config.network().vpcRouting().publicAwsApiUpstreamHttpsPort();
        if (httpsBackendPort < 1 || httpsBackendPort > 65535
                || EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS.equals(selectedHost)) {
            throw new IllegalStateException("Invalid public endpoint upstream address or HTTPS port");
        }
        ContainerSpec spec = containers.newContainer(config.network().securityGroupEnforcement().helperImage())
                .withName(ContainerStorageHelper.resourceName(config, SERVICE, null, UUID.randomUUID().toString()))
                .withDockerNetwork(Optional.empty()).withHostDockerInternalOnLinux().withCapAdd("NET_ADMIN")
                .withNetworkSysctl("net.ipv4.conf.all.rp_filter", "0")
                .withNetworkSysctl("net.ipv4.conf.default.rp_filter", "0")
                .withNetworkSysctl("net.ipv6.conf.all.disable_ipv6", "1")
                .withEntrypoint(List.of("sh", "-c")).withCmd(List.of("""
                        while [ ! -f /tmp/floci-api-ready ]; do sleep 0.05; done
                        exec sh /tmp/floci-api-start.sh
                        """))
                .withLabels(Map.of(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                        ContainerStorageHelper.SERVICE_LABEL, SERVICE))
                .build();
        String id = lifecycle.createAndStart(spec).containerId();
        namespace = new NetworkNamespaceLease(id, null);
        try {
            InspectContainerResponse inspection = docker.inspectContainerCmd(id).exec();
            namespace = new NetworkNamespaceLease(id, inspection.getName());
            List<String> addresses = execute("getent", "ahostsv4", target.getHost()).stdout().lines()
                    .filter(line -> !line.isBlank()).map(line -> line.split("\\s+")[0]).distinct().toList();
            if (addresses.size() != 1) {
                throw new IllegalStateException("The AWS API backend does not resolve to one IPv4 address");
            }
            backend = VpcRouteSelection.ipv4(addresses.getFirst());
            docker.connectToNetworkCmd().withNetworkId(network).withContainerId(id).exec();
            transport = docker.inspectContainerCmd(id).exec().getNetworkSettings().getNetworks().values().stream()
                    .filter(value -> network.equals(value.getNetworkID())).map(ContainerNetwork::getIpAddress)
                    .filter(value -> value != null && !value.isBlank()).findFirst().orElseThrow();
            device = deviceFor(transport);
            configure(List.of());
        } catch (RuntimeException failure) {
            try {
                retire();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    void configure(List<VpcRoutingCommands.Hop> callers) {
        String address = EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS;
        String script = VpcRoutingCommands.publicApi(
                address, backend, httpBackendPort, httpsBackendPort, device, callers);
        if (!script.equals(applied)) {
            execute("sh", "-c", script);
            applied = script;
        }
        if (!listenersStarted) {
            String start = """
                    set -eu
                    socat -d -d TCP4-LISTEN:80,bind=%s,reuseaddr,fork TCP4:%s:%d &
                    first=$!
                    socat -d -d TCP4-LISTEN:443,bind=%s,reuseaddr,fork TCP4:%s:%d &
                    second=$!
                    trap 'kill "$first" "$second" 2>/dev/null || true' EXIT TERM INT
                    wait -n
                    exit 1
                    """.formatted(address, backend, httpBackendPort, address, backend, httpsBackendPort);
            RetryingTarCopier.copyBytes(docker, namespace.id(), "/tmp", "floci-api-start.sh",
                    start.getBytes(StandardCharsets.UTF_8), 0600);
            execute("touch", "/tmp/floci-api-ready");
            awaitListeners(address);
            listenersStarted = true;
        }
    }

    private void awaitListeners(String address) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            String listeners = execute("ss", "-H", "-lnt").stdout();
            if (listeners.contains(address + ":80 ") && listeners.contains(address + ":443 ")) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for the public AWS API listeners", failure);
            }
        }
        throw new IllegalStateException("The public AWS API listeners did not start");
    }

    void retire() {
        if (namespace != null) {
            namespace.requireUnused(docker);
            lifecycle.removeIfExistsStrict(namespace.id());
            namespace = null;
        }
        transport = null;
        device = null;
        backend = null;
        applied = null;
        listenersStarted = false;
    }

    private String deviceFor(String address) {
        try {
            for (JsonNode deviceInfo : mapper.readTree(execute("ip", "-j", "-4", "address").stdout())) {
                for (JsonNode value : deviceInfo.path("addr_info")) {
                    if (address.equals(value.path("local").asText())) {
                        return VpcRoutingCommands.device(deviceInfo.path("ifname").asText());
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot decode the public AWS API interface", failure);
        }
        throw new IllegalStateException("The public AWS API transport has no interface");
    }

    private ContainerExec.Result execute(String... command) {
        ContainerExec.Result result = ContainerExec.run(docker, namespace.id(), command, 20)
                .throwIfTimedOut(namespace.id());
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Cannot configure public AWS API routing: " + result.summary());
        }
        return result;
    }
}
