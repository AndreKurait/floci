package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class LambdaDnsResolverDockerIntegrationTest {

    private static final String IMAGE = "public.ecr.aws/lambda/python:3.12";
    private static final String PYTHON = "/var/lang/bin/python3.12";
    private static final String DNS_SERVER = """
            import pathlib, socket, struct
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.bind(("0.0.0.0", 53))
            pathlib.Path("/tmp/dns-ready").touch()
            while True:
                query, client = sock.recvfrom(4096)
                end = 12
                while query[end]:
                    end += query[end] + 1
                qtype = struct.unpack(">H", query[end + 1:end + 3])[0]
                answer = b""
                if qtype == 1:
                    answer = b"\\xc0\\x0c" + struct.pack(">HHIH", 1, 1, 30, 4) + socket.inet_aton("192.0.2.44")
                header = query[:2] + struct.pack(">HHHHH", 0x8180, 1, bool(answer), 0, 0)
                sock.sendto(header + query[12:end + 5] + answer, client)
            """;
    private static final String RUNTIME = """
            import os, pathlib, socket
            assert os.getuid() == 12345
            answers = socket.getaddrinfo("peer.example.test", 443, socket.AF_INET)
            assert answers and all(answer[4][0] == "192.0.2.44" for answer in answers), answers
            aliases = socket.getaddrinfo(os.environ["PEER_ALIAS"], 443, socket.AF_INET)
            assert aliases and all(answer[4][0] == os.environ["PEER_IP"] for answer in aliases), aliases
            try:
                pathlib.Path("/etc/resolv.conf").write_text("nameserver 8.8.8.8")
            except OSError:
                pass
            else:
                raise AssertionError("resolver must remain read-only")
            """;

    @Inject DockerClient docker;
    @Inject ContainerBuilder containers;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject EmulatorConfig config;

    @BeforeEach
    void requireDocker() {
        boolean available;
        try {
            docker.pingCmd().exec();
            available = true;
        } catch (RuntimeException unavailable) {
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker is required for the isolated Lambda DNS test");
    }

    @Test
    void ordinaryResolutionWorksDuringNonRootInitializationAndRestart() throws Exception {
        String network = docker.createNetworkCmd().withName("lambda-dns-test-" + UUID.randomUUID())
                .withInternal(true).exec().getId();
        String peer = null;
        String runtime = null;
        String volume = null;
        String alias = "dns-peer-" + UUID.randomUUID();
        try {
            ContainerSpec peerSpec = containers.newContainer(IMAGE).withNetworkMode(network).withName(alias)
                    .withEntrypoint(List.of(PYTHON)).withCmd(List.of("-c", DNS_SERVER)).build();
            peer = lifecycle.createAndStart(peerSpec).containerId();
            ContainerExec.Result ready = ContainerExec.run(docker, peer, new String[]{
                    PYTHON, "-c", """
                    import pathlib, time
                    for attempt in range(100):
                        if pathlib.Path("/tmp/dns-ready").exists():
                            break
                        time.sleep(0.1)
                    assert pathlib.Path("/tmp/dns-ready").exists()
                    """}, 15);
            assertEquals(0, ready.exitCode(), ready.summary());
            String address = docker.inspectContainerCmd(peer).exec().getNetworkSettings().getNetworks()
                    .values().iterator().next().getIpAddress();
            ContainerBuilder.Builder workload = containers.newContainer(IMAGE).withNetworkMode(network)
                    .withDnsServer(address).withUser("12345:12345").withReadonlyRootfs()
                    .withEnv(List.of("PEER_ALIAS=" + alias, "PEER_IP=" + address))
                    .withEntrypoint(List.of(PYTHON)).withCmd(List.of("-c", RUNTIME));

            volume = new LambdaDnsResolver(containers, lifecycle, config).prepare(workload, null);
            assertNotNull(volume);
            ContainerSpec spec = workload.build();
            runtime = lifecycle.create(spec);
            List<InspectContainerResponse.Mount> mounts = docker.inspectContainerCmd(runtime).exec().getMounts();
            String resolverVolume = volume;
            InspectContainerResponse.Mount resolver = mounts.stream()
                    .filter(mount -> "/etc/resolv.conf".equals(mount.getDestination().getPath()))
                    .findFirst().orElseThrow();
            InspectContainerResponse.Mount retained = mounts.stream()
                    .filter(mount -> resolverVolume.equals(mount.getName())).findFirst().orElseThrow();
            assertEquals(Boolean.FALSE, resolver.getRW(), "The effective resolver bind must be read-only");
            assertEquals(Boolean.FALSE, retained.getRW(), "The effective named volume must be read-only");
            for (int attempt = 0; attempt < 2; attempt++) {
                lifecycle.startCreated(runtime, spec);
                Integer exit = docker.waitContainerCmd(runtime).exec(new WaitContainerResultCallback())
                        .awaitStatusCode(30, TimeUnit.SECONDS);
                assertEquals(0, exit, "DNS must work inside the original entrypoint, including after restart");
            }
            String retainedVolume = volume;
            assertThrows(IllegalStateException.class, () -> lifecycle.removeVolumeStrict(retainedVolume));
            lifecycle.removeIfExistsStrict(runtime, true);
            runtime = null;
            lifecycle.removeVolumeStrict(volume);
            assertThrows(NotFoundException.class, () -> docker.inspectVolumeCmd(retainedVolume).exec());
            volume = null;
        } finally {
            try {
                if (runtime != null) {
                    lifecycle.removeIfExistsStrict(runtime, true);
                }
            } finally {
                try {
                    if (volume != null) {
                        lifecycle.removeVolumeStrict(volume);
                    }
                } finally {
                    try {
                        if (peer != null) {
                            lifecycle.removeIfExistsStrict(peer, true);
                        }
                    } finally {
                        docker.removeNetworkCmd(network).exec();
                    }
                }
            }
        }
    }
}
