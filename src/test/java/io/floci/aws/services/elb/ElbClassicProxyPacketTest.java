package io.floci.aws.services.elb;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.CertificateReferenceResolver.CertificateMaterial;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.FlociDockerHttpClient;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.services.acm.CertificateGenerator.GeneratedCertificate;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.services.elb.model.ClassicListener;
import io.floci.aws.services.elb.model.ClassicLoadBalancer;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class ElbClassicProxyPacketTest {
    private static final String PROXY = "haproxy:3.2-alpine@sha256:5893840576071e6217cd52ebdcc14f454c6f7a631056fa6f4e0f40d3cac4215b";

    @TempDir
    Path certificates;

    @ParameterizedTest
    @CsvSource({"HTTP,HTTP", "TCP,TCP", "HTTPS,HTTP", "SSL,TCP", "HTTPS,HTTPS", "SSL,SSL"})
    void renderedListenerForwardsOnlyAfterHealthAdmissionAndStopsAfterWithdrawal(
            String protocol, String backendProtocol) throws Exception {
        boolean tls = protocol.equals("HTTPS") || protocol.equals("SSL");
        boolean backendTls = backendProtocol.equals("HTTPS") || backendProtocol.equals("SSL");
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(certificates.resolve("trusted"));
        GeneratedCertificate certificate = ca.issueServerCertificate("owned.classic.test",
                List.of("owned.classic.test"), KeyAlgorithm.RSA_2048, null);
        CertificateMaterial material = new CertificateMaterial("owned", certificate.certificatePem(),
                certificate.privateKeyPem(), ca.caPem());
        String request = "curl --silent --show-error --max-time 2 --noproxy '*' "
                + (tls ? "--cacert /tmp/ca.pem --resolve owned.classic.test:18080:127.0.0.1 "
                        + "https://owned.classic.test:18080/" : "http://127.0.0.1:18080/");
        String prefix = "classic-packet-" + UUID.randomUUID();
        URI host = URI.create("unix:///var/run/docker.sock");
        try (FlociDockerHttpClient transport = new FlociDockerHttpClient.Builder()
                .dockerHost(host).responseTimeout(Duration.ofSeconds(10)).build();
             DockerClient docker = DockerClientImpl.getInstance(DefaultDockerClientConfig.createDefaultConfigBuilder()
                     .withDockerHost(host.toString()).build(), transport)) {
            List<String> created = new ArrayList<>();
            try {
                planned(prefix + "-helper");
                String helper = docker.createContainerCmd("floci/network-helper:local").withName(prefix + "-helper")
                        .withLabels(Map.of("test-owner", prefix))
                        .withHostConfig(HostConfig.newHostConfig().withNetworkMode("none"))
                        .withEntrypoint("socat").withCmd(backendTls
                                ? "OPENSSL-LISTEN:18081,reuseaddr,fork,cert=/tmp/backend.pem,verify=0"
                                : "TCP-LISTEN:18081,reuseaddr,fork",
                                "EXEC:sh /tmp/backend.sh").exec().getId();
                record(created, helper);
                RetryingTarCopier.copyBytes(docker, helper, "/tmp", "backend.sh",
                        ("printf 'HTTP/1.1 200 OK\\r\\nContent-Length: 13\\r\\n\\r\\nowned-backend'\n"
                                + "cat > /dev/null\n").getBytes(StandardCharsets.UTF_8), 0600);
                RetryingTarCopier.copyBytes(docker, helper, "/tmp", "ca.pem",
                        ca.caPem().getBytes(StandardCharsets.UTF_8), 0600);
                RetryingTarCopier.copyBytes(docker, helper, "/tmp", "backend.pem",
                        (certificate.certificatePem() + certificate.privateKeyPem())
                                .getBytes(StandardCharsets.UTF_8), 0600);
                docker.startContainerCmd(helper).exec();
                ClassicLoadBalancer lb = new ClassicLoadBalancer();
                ClassicListener listener = new ClassicListener();
                listener.setProtocol(protocol);
                listener.setInstanceProtocol(backendProtocol);
                listener.setSslCertificateId("owned");
                listener.setLoadBalancerPort(18080);
                listener.setInstancePort(18081);
                lb.setListeners(List.of(listener));
                ElbClassicProxyConfig.Rendered rendered = ElbClassicProxyConfig.render(lb, "127.0.0.1",
                        List.of(new ElbClassicProxyConfig.Backend("owned", "127.0.0.1")), reference -> {
                            assertEquals("owned", reference);
                            return material;
                        });
                planned(prefix + "-proxy");
                String proxy = docker.createContainerCmd(PROXY).withName(prefix + "-proxy").withUser("0")
                        .withLabels(Map.of("test-owner", prefix))
                        .withHostConfig(HostConfig.newHostConfig().withNetworkMode("container:" + helper))
                        .withEntrypoint("haproxy").withCmd("-W", "-db", "-f",
                                ElbClassicProxyConfig.DIRECTORY + "/haproxy.cfg").exec().getId();
                record(created, proxy);
                for (Map.Entry<String, byte[]> file : rendered.files().entrySet()) {
                    RetryingTarCopier.copyBytes(docker, proxy, ElbClassicProxyConfig.DIRECTORY,
                            file.getKey(), file.getValue(), 0600);
                }
                docker.startContainerCmd(proxy).exec();
                ContainerExec.Result ready = exec(docker, helper, "for n in $(seq 1 100); do "
                        + "printf 'show info\\n' | socat -t 1 - ABSTRACT-CONNECT:floci-classic | grep -q 'Version:' "
                        + "&& exit 0; sleep 0.05; done; exit 1");
                assertEquals(0, ready.exitCode(), ready.summary());
                assertEquals("owned-backend", exec(docker, helper,
                        "curl --silent --show-error --max-time 2 --noproxy '*' "
                                + (backendTls ? "--cacert /tmp/ca.pem --resolve owned.classic.test:18081:127.0.0.1 "
                                        + "https://owned.classic.test:18081/" : "http://127.0.0.1:18081/")).stdout());
                assertUnavailable(docker, helper, protocol, request);
                String server = rendered.servers().get("owned").getFirst();
                changeHealth(docker, helper, server, "ready");
                assertEquals("owned-backend", exec(docker, helper, request).stdout());
                if (tls) {
                    ContainerExec.Result untrusted = exec(docker, helper,
                            request.replace("--cacert /tmp/ca.pem ", ""));
                    assertEquals(60, untrusted.exitCode(), untrusted.summary());
                    assertFalse(untrusted.stdout().contains("owned-backend"), untrusted.summary());
                    ContainerExec.Result wrongHostname = exec(docker, helper,
                            request.replace("owned.classic.test", "wrong.classic.test"));
                    assertEquals(60, wrongHostname.exitCode(), wrongHostname.summary());
                    assertFalse(wrongHostname.stdout().contains("owned-backend"), wrongHostname.summary());
                }
                changeHealth(docker, helper, server, "maint");
                assertUnavailable(docker, helper, protocol, request);
                changeHealth(docker, helper, server, "ready");
                assertEquals("owned-backend", exec(docker, helper, request).stdout());
                docker.removeContainerCmd(proxy).withForce(true).withRemoveVolumes(true).exec();
                assertNotEquals(0, exec(docker, helper, request).exitCode());
            } finally {
                for (String id : created.reversed()) {
                    try {
                        docker.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec();
                    } catch (NotFoundException ignored) {
                        // The packet test already removed its proxy.
                    }
                }
                for (String id : created) {
                    assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(id).exec());
                }
            }
        }
    }

    private static void assertUnavailable(DockerClient docker, String helper, String protocol, String request) {
        ContainerExec.Result result = exec(docker, helper, request);
        assertFalse(result.stdout().contains("owned-backend"), result.summary());
        if ("HTTP".equals(protocol) || "HTTPS".equals(protocol)) {
            assertTrue(result.stdout().contains("503 Service Unavailable"), result.summary());
        } else {
            assertNotEquals(0, result.exitCode(), result.summary());
        }
    }

    private static void changeHealth(DockerClient docker, String helper, String server, String state) {
        ContainerExec.Result result = exec(docker, helper,
                "printf '%s\\n' 'set server " + server + " state " + state
                        + "' | socat -t 1 - ABSTRACT-CONNECT:floci-classic");
        assertEquals(0, result.exitCode(), result.summary());
        assertTrue(result.stdout().isBlank(), result.summary());
    }

    private static ContainerExec.Result exec(DockerClient docker, String helper, String command) {
        return ContainerExec.run(docker, helper, new String[]{"sh", "-c", command}, 10).throwIfTimedOut(helper);
    }

    private static void planned(String name) throws Exception {
        Files.writeString(Path.of(System.getenv("FLOCI_AGENT_RUN_DIR"), "docker-ownership.txt"),
                "planned " + name + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void record(List<String> created, String id) throws Exception {
        created.add(id);
        Files.writeString(Path.of(System.getenv("FLOCI_AGENT_RUN_DIR"), "docker-ownership.txt"),
                "created " + id + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
