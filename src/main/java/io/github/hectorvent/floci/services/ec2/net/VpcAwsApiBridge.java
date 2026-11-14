package io.github.hectorvent.floci.services.ec2.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.PublicApiProxySources;
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
import java.util.stream.Collectors;

/** A managed public destination. Native routes and NATs remain outside its TCP forwarding. */
final class VpcAwsApiBridge {
    private static final String SERVICE = "vpc-aws-api";
    private final EmulatorConfig config;
    private final DockerClient docker;
    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final ObjectMapper mapper;
    private final ContainerReachableEndpoint endpoint;
    private final PublicApiProxySources publicSources;
    private NetworkNamespaceLease namespace;
    private String transport;
    private String device;
    private String backend;
    private String httpsBackend;
    private String albBackend;
    private int httpBackendPort;
    private int httpsBackendPort;
    private String applied;
    private boolean listenersStarted;
    private String sourcePeer;

    VpcAwsApiBridge(EmulatorConfig config, DockerClient docker, ContainerBuilder containers,
                    ContainerLifecycleManager lifecycle, ObjectMapper mapper, ContainerReachableEndpoint endpoint) {
        this(config, docker, containers, lifecycle, mapper, endpoint, new PublicApiProxySources());
    }

    VpcAwsApiBridge(EmulatorConfig config, DockerClient docker, ContainerBuilder containers,
                    ContainerLifecycleManager lifecycle, ObjectMapper mapper, ContainerReachableEndpoint endpoint,
                    PublicApiProxySources publicSources) {
        this.config = config;
        this.docker = docker;
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.publicSources = publicSources;
    }

    boolean enabled() {
        return config.network().vpcRouting().publicAwsApi();
    }

    boolean publicAlbHttps() {
        return enabled() && !config.network().vpcRouting().publicAwsApiNativeHttps()
                && config.network().vpcRouting().publicAwsApiUpstreamHost().isPresent()
                && config.tls().enabled() && config.tls().awsHttpsPort() == 443;
    }

    List<String> destinations() {
        return publicAlbHttps() ? List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS,
                EmulatorConfig.VpcRoutingConfig.PUBLIC_ALB_HTTPS_ADDRESS)
                : List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS);
    }

    String transport() {
        return transport;
    }

    String device() {
        return device;
    }

    record NetworkEndpoint(String address, String device) {}

    NetworkEndpoint onNetwork(String network) {
        InspectContainerResponse inspection = docker.inspectContainerCmd(namespace.id()).exec();
        if (inspection.getNetworkSettings().getNetworks().values().stream()
                .noneMatch(value -> network.equals(value.getNetworkID()))) {
            docker.connectToNetworkCmd().withNetworkId(network).withContainerId(namespace.id()).exec();
            inspection = docker.inspectContainerCmd(namespace.id()).exec();
        }
        String address = inspection.getNetworkSettings().getNetworks().values().stream()
                .filter(value -> network.equals(value.getNetworkID())).map(ContainerNetwork::getIpAddress)
                .filter(value -> value != null && !value.isBlank()).findFirst().orElseThrow();
        return new NetworkEndpoint(VpcRouteSelection.ipv4(address), deviceFor(address));
    }

    void ensure(String network) {
        if (namespace != null) {
            if (!Boolean.TRUE.equals(docker.inspectContainerCmd(namespace.id()).exec().getState().getRunning())) {
                publicSources.revoke(this);
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
        validateTarget(target);
        httpBackendPort = target.getPort();
        httpsBackendPort = selectedHost == null ? httpBackendPort
                : config.network().vpcRouting().publicAwsApiUpstreamHttpsPort();
        URI httpsTarget = target;
        if (config.network().vpcRouting().publicAwsApiNativeHttps()) {
            httpsBackendPort = config.tls().awsHttpsPort();
            if (!config.tls().enabled() || httpsBackendPort == config.port()) {
                throw new IllegalStateException("Native public HTTPS requires a distinct enabled TLS listener");
            }
            httpsTarget = URI.create(endpoint.baseUrl(false));
            validateTarget(httpsTarget);
        }
        if (httpsBackendPort < 1 || httpsBackendPort > 65535
                || EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS.equals(selectedHost)
                || EmulatorConfig.VpcRoutingConfig.PUBLIC_ALB_HTTPS_ADDRESS.equals(selectedHost)) {
            throw new IllegalStateException("Invalid public endpoint upstream address or HTTPS port");
        }
        retireOrphans();
        ContainerSpec spec = containers.newContainer(config.network().securityGroupEnforcement().helperImage())
                .withName(ContainerStorageHelper.resourceName(config, SERVICE, null, UUID.randomUUID().toString()))
                .withDockerNetwork(Optional.empty()).withHostDockerInternalOnLinux().withCapAdd("NET_ADMIN").withInit()
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
            backend = resolveBackend(target);
            httpsBackend = httpsTarget.equals(target) ? backend : resolveBackend(httpsTarget);
            if (publicAlbHttps()) {
                URI nativeTarget = URI.create(endpoint.baseUrl(false));
                validateTarget(nativeTarget);
                albBackend = resolveBackend(nativeTarget);
            }
            docker.connectToNetworkCmd().withNetworkId(network).withContainerId(id).exec();
            transport = docker.inspectContainerCmd(id).exec().getNetworkSettings().getNetworks().values().stream()
                    .filter(value -> network.equals(value.getNetworkID())).map(ContainerNetwork::getIpAddress)
                    .filter(value -> value != null && !value.isBlank()).findFirst().orElseThrow();
            device = deviceFor(transport);
            if (config.network().vpcRouting().publicAwsApiNativeHttps()) {
                sourcePeer = sourceAddressFor(httpsBackend);
            } else if (albBackend != null) {
                sourcePeer = sourceAddressFor(albBackend);
            }
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

    private static void validateTarget(URI target) {
        if (!"http".equals(target.getScheme()) || target.getHost() == null
                || target.getPort() < 1 || target.getPort() > 65535) {
            throw new IllegalStateException("Invalid container-reachable AWS API endpoint");
        }
    }

    private String resolveBackend(URI target) {
        List<String> addresses = execute("getent", "ahostsv4", target.getHost()).stdout().lines()
                .filter(line -> !line.isBlank()).map(line -> line.split("\\s+")[0]).distinct().toList();
        if (addresses.size() != 1) {
            throw new IllegalStateException("The AWS API backend does not resolve to one IPv4 address");
        }
        return VpcRouteSelection.ipv4(addresses.getFirst());
    }

    private void retireOrphans() {
        String owner = ContainerStorageHelper.ownerIdentity(config);
        Map<String, String> selection = Map.of(
                ContainerStorageHelper.OWNER_LABEL, owner, ContainerStorageHelper.SERVICE_LABEL, SERVICE);
        for (Container container : docker.listContainersCmd().withShowAll(true).withLabelFilter(selection).exec()) {
            Map<String, String> labels = container.getLabels();
            if (labels == null || !owner.equals(labels.get(ContainerStorageHelper.OWNER_LABEL))
                    || !SERVICE.equals(labels.get(ContainerStorageHelper.SERVICE_LABEL))) {
                continue;
            }
            String[] names = container.getNames();
            new NetworkNamespaceLease(container.getId(), names == null || names.length == 0 ? null : names[0])
                    .requireUnused(docker);
            lifecycle.removeIfExistsStrict(container.getId());
        }
    }

    void configure(List<VpcRoutingCommands.Hop> callers) {
        configure(callers, List.of());
    }

    void configure(List<VpcRoutingCommands.Hop> callers, List<VpcRoutingCommands.Hop> albCallers) {
        String address = EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS;
        String script = VpcRoutingCommands.publicApi(
                address, backend, httpBackendPort, httpsBackend, httpsBackendPort, device, callers,
                albBackend, albCallers);
        if (!script.equals(applied)) {
            publicSources.revoke(this);
            applied = null;
            execute("sh", "-c", script);
            applied = script;
            if (config.network().vpcRouting().publicAwsApiNativeHttps()) {
                publicSources.register(this, sourcePeer, callers.stream().map(VpcRoutingCommands.Hop::destination)
                        .collect(Collectors.toUnmodifiableSet()));
            } else if (albBackend != null) {
                publicSources.register(this, sourcePeer, Map.of(
                        EmulatorConfig.VpcRoutingConfig.PUBLIC_ALB_HTTPS_ADDRESS,
                        albCallers.stream().map(VpcRoutingCommands.Hop::destination)
                                .collect(Collectors.toUnmodifiableSet())));
            }
        }
        if (!listenersStarted) {
            boolean nativeHttps = config.network().vpcRouting().publicAwsApiNativeHttps();
            if (nativeHttps) {
                RetryingTarCopier.copyBytes(docker, namespace.id(), "/tmp", "floci-api-https.sh",
                        httpsConnector(httpsBackend, httpsBackendPort).getBytes(StandardCharsets.UTF_8), 0600);
            }
            if (albBackend != null) {
                RetryingTarCopier.copyBytes(docker, namespace.id(), "/tmp", "floci-alb-https.sh",
                        httpsConnector(albBackend, 443).getBytes(StandardCharsets.UTF_8), 0600);
            }
            String start = listenerScript(address, backend, httpBackendPort, nativeHttps
                    ? "EXEC:'sh /tmp/floci-api-https.sh',pipes" : "TCP4:" + httpsBackend + ":" + httpsBackendPort,
                    albBackend != null);
            RetryingTarCopier.copyBytes(docker, namespace.id(), "/tmp", "floci-api-start.sh",
                    start.getBytes(StandardCharsets.UTF_8), 0600);
            execute("touch", "/tmp/floci-api-ready");
            awaitListeners(address);
            listenersStarted = true;
        }
    }

    static String listenerScript(String address, String backend, int httpPort, String httpsTarget) {
        return listenerScript(address, backend, httpPort, httpsTarget, false);
    }

    static String listenerScript(String address, String backend, int httpPort, String httpsTarget, boolean publicAlb) {
        // Connection subprocesses may outlive their parent; Docker init owns their reaping.
        // Supervise every listener explicitly, independent of the helper shell's wait -n behavior.
        String nativeListener = publicAlb ? """
                socat -d -d TCP4-LISTEN:443,bind=%s,reuseaddr,fork EXEC:'sh /tmp/floci-alb-https.sh',pipes &
                third=$!
                """.formatted(EmulatorConfig.VpcRoutingConfig.PUBLIC_ALB_HTTPS_ADDRESS) : "third=\n";
        return """
                set -eu
                socat -d -d TCP4-LISTEN:80,bind=%s,reuseaddr,fork TCP4:%s:%d &
                first=$!
                socat -d -d TCP4-LISTEN:443,bind=%s,reuseaddr,fork %s &
                second=$!
                %s\
                trap 'kill "$first" "$second" ${third:+"$third"} 2>/dev/null || true' EXIT TERM INT
                while kill -0 "$first" 2>/dev/null && kill -0 "$second" 2>/dev/null \
                && { [ -z "$third" ] || kill -0 "$third" 2>/dev/null; }; do
                    sleep 0.1
                done
                exit 1
                """.formatted(address, backend, httpPort, address, httpsTarget, nativeListener);
    }

    static String httpsConnector(String backend, int port) {
        String address = VpcRouteSelection.ipv4(backend);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid native HTTPS backend port");
        }
        return """
                set -eu
                {
                    printf 'PROXY TCP4 %%s %%s %%s %%s\\r\\n' "$SOCAT_PEERADDR" "$SOCAT_SOCKADDR" \
                "$SOCAT_PEERPORT" "$SOCAT_SOCKPORT"
                    cat
                } | socat - TCP4:%s:%d
                """.formatted(address, port);
    }

    private String sourceAddressFor(String target) {
        try {
            JsonNode routes = mapper.readTree(execute("ip", "-j", "-4", "route", "get", target).stdout());
            if (!routes.isArray() || routes.size() != 1) {
                throw new IllegalStateException("The public API backend requires one source route");
            }
            String source = VpcRouteSelection.ipv4(routes.get(0).path("prefsrc").asText());
            boolean owned = docker.inspectContainerCmd(namespace.id()).exec().getNetworkSettings().getNetworks()
                    .values().stream().anyMatch(network -> source.equals(network.getIpAddress()));
            if (!owned) {
                throw new IllegalStateException("The public API source is not an owned Docker interface");
            }
            return source;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot decode the public API source route", failure);
        }
    }

    private void awaitListeners(String address) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            String listeners = execute("ss", "-H", "-lnt").stdout();
            boolean albReady = albBackend == null
                    || listeners.contains(EmulatorConfig.VpcRoutingConfig.PUBLIC_ALB_HTTPS_ADDRESS + ":443 ");
            if (listeners.contains(address + ":80 ") && listeners.contains(address + ":443 ") && albReady) {
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
        publicSources.revoke(this);
        if (namespace != null) {
            namespace.requireUnused(docker);
            lifecycle.removeIfExistsStrict(namespace.id());
            namespace = null;
        }
        transport = null;
        device = null;
        backend = null;
        httpsBackend = null;
        albBackend = null;
        applied = null;
        listenersStarted = false;
        sourcePeer = null;
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
