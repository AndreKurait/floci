package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.FlociDockerHttpClient;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.testing.DockerAvailability;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the production scripts on IPv4 Docker bridges using only an explicitly selected cached helper image. */
class Ec2AttachedInterfaceIpv6DockerIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void enablesDisabledAttachedEndpointsOnRetriesNewAttachmentsAndRecreatedHelpers(boolean router) throws Exception {
        String image = System.getenv("FLOCI_EC2_IPV6_TEST_IMAGE");
        assumeTrue(image != null && !image.isBlank(), "Set FLOCI_EC2_IPV6_TEST_IMAGE to a cached network-helper image");
        assumeTrue(DockerAvailability.isAvailable(), "Docker must be available");
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        try (DockerClient docker = DockerClientImpl.getInstance(config,
                new FlociDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                        .sslConfig(config.getSSLConfig()).build())) {
            docker.inspectImageCmd(image).exec();
            String owner = "ipv6-attach-test-" + UUID.randomUUID();
            Map<String, String> labels = Map.of("floci.test.ipv6-attachment-owner", owner);
            List<String> networks = new ArrayList<>();
            List<String> containers = new ArrayList<>();
            List<String> evidence = new ArrayList<>();
            try {
                for (int i = 0; i < 3; i++) {
                    String network = docker.createNetworkCmd().withName(owner + "-net-" + i).withDriver("bridge")
                            .withInternal(true).withEnableIpv6(false).withLabels(labels).exec().getId();
                    networks.add(network);
                }
                for (int generation = 0; generation < 2; generation++) {
                    String id = docker.createContainerCmd(image).withName(owner + "-helper-" + generation)
                            .withLabels(labels).withEntrypoint("sh", "-c").withCmd("exec sleep 180")
                            .withHostConfig(HostConfig.newHostConfig().withNetworkMode(networks.getFirst())
                                    .withCapAdd(Capability.NET_ADMIN)
                                    .withSysctls(Map.of("net.ipv6.conf.all.disable_ipv6", "0",
                                            "net.ipv6.conf.default.disable_ipv6", "0")))
                            .exec().getId();
                    containers.add(id);
                    docker.startContainerCmd(id).exec();
                    assertEquals("1", run(docker, id, "sysctl", "-n", "net.ipv6.conf.eth0.disable_ipv6").strip());
                    String transport = run(docker, id, "ip", "-4", "addr", "show", "dev", "eth0");
                    for (int attachment = 1; attachment < networks.size(); attachment++) {
                        String network = networks.get(attachment);
                        docker.connectToNetworkCmd().withContainerId(id).withNetworkId(network).exec();
                        ContainerNetwork endpoint = docker.inspectContainerCmd(id).exec().getNetworkSettings()
                                .getNetworks().values().stream()
                                .filter(candidate -> network.equals(candidate.getNetworkID())).findFirst().orElseThrow();
                        String dev = device(docker, id, endpoint);
                        assertTrue(!dev.isBlank() && !dev.equals("eth0"), dev);
                        String key = "net.ipv6.conf." + dev + ".disable_ipv6";
                        // An ordinary attach reproduces Docker's disabled endpoint despite enabled namespace defaults.
                        assertEquals("1", run(docker, id, "sysctl", "-n", key).strip());
                        String block = "2001:db8:" + attachment + "00::/56";
                        String address = "2001:db8:" + attachment + "00::10";
                        String[] command = router ? Ec2Ipv6VpcRouter.joinCommand(endpoint.getMacAddress(), block)
                                : Ec2ContainerManager.guestIpv6Command(endpoint.getIpAddress(), address, block);
                        String[] beforeFix = command.clone();
                        beforeFix[2] = beforeFix[2].lines()
                                .filter(line -> !line.startsWith("sysctl -w")
                                        && !line.startsWith("[ \"$(sysctl -n"))
                                .collect(Collectors.joining("\n"));
                        ContainerExec.Result failed = ContainerExec.run(docker, id, beforeFix, 10);
                        assertTrue(!failed.timedOut(), failed.summary());
                        assertNotEquals(0L, failed.exitCode(), failed.summary());
                        assertEquals("1", run(docker, id, "sysctl", "-n", key).strip());
                        evidence.add("router=" + router + " generation=" + generation + " attachment=" + attachment
                                + " pre-fix exit=" + failed.exitCode() + " " + failed.summary());
                        ContainerExec.Result refused = ContainerExec.run(docker, id, command, 10);
                        assertEquals(4L, refused.exitCode(), refused.summary());
                        docker.disconnectFromNetworkCmd().withContainerId(id).withNetworkId(network).exec();
                        docker.connectToNetworkCmd().withContainerId(id).withNetworkId(network)
                                .withContainerNetwork(VpcNetworkManager.ipv6Endpoint()).exec();
                        endpoint = docker.inspectContainerCmd(id).exec().getNetworkSettings().getNetworks().values()
                                .stream().filter(candidate -> network.equals(candidate.getNetworkID()))
                                .findFirst().orElseThrow();
                        dev = device(docker, id, endpoint);
                        key = "net.ipv6.conf." + dev + ".disable_ipv6";
                        command = router ? Ec2Ipv6VpcRouter.joinCommand(endpoint.getMacAddress(), block)
                                : Ec2ContainerManager.guestIpv6Command(endpoint.getIpAddress(), address, block);
                        assertEquals("0", run(docker, id, "sysctl", "-n", key).strip());
                        for (int retry = 0; retry < 2; retry++) {
                            ContainerExec.Result result = ContainerExec.run(docker, id, command, 10);
                            assertEquals(0L, result.exitCode(), result.summary());
                            assertEquals("0", run(docker, id, "sysctl", "-n", key).strip());
                        }
                        assertTrue(run(docker, id, "ip", "-6", "addr", "show", "dev", dev)
                                .contains(router ? "fe80:ec2::1" : address));
                        assertTrue(run(docker, id, "ip", "-6", "route", "show", "dev", dev).contains(block));
                        if (!router) {
                            assertTrue(run(docker, id, "ip", "-6", "route", "show", "default")
                                    .contains("via fe80:ec2::1 dev " + dev));
                        }
                        assertEquals("1", run(docker, id, "sysctl", "-n", "net.ipv6.conf.eth0.disable_ipv6").strip());
                        assertEquals(transport, run(docker, id, "ip", "-4", "addr", "show", "dev", "eth0"));
                        assertEquals("0", run(docker, id, "sysctl", "-n", "net.ipv6.conf.default.disable_ipv6").strip());
                        evidence.add("router=" + router + " generation=" + generation + " attachment=" + attachment
                                + " PASS: enabled matched endpoint, addresses/routes installed, retries succeeded,"
                                + " transport IPv4 and disabled IPv6 preserved");
                    }
                }
            } finally {
                for (String id : containers.reversed()) {
                    docker.removeContainerCmd(id).withForce(true).exec();
                }
                for (String network : networks.reversed()) {
                    docker.removeNetworkCmd(network).exec();
                }
                Files.write(Path.of("target", "attached-interface-ipv6-" + router + ".txt"), evidence);
            }
        }
    }

    private static String run(DockerClient docker, String id, String... command) {
        ContainerExec.Result result = ContainerExec.run(docker, id, command, 10);
        assertEquals(0L, result.exitCode(), result.summary());
        return result.stdout();
    }

    private static String device(DockerClient docker, String id, ContainerNetwork endpoint) {
        return run(docker, id, "sh", "-c",
                "ip -o link show | awk -v a=\"$1\" '$0 ~ a {sub(/@.*/, \"\", $2); sub(/:$/, \"\", $2); print $2}'",
                "find-interface", endpoint.getMacAddress()).strip();
    }
}
