package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.LocallyBuiltHelperImage;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
class SecurityGroupNamespaceDnsDockerIntegrationTest {

    @Inject SecurityGroupFirewallManager firewall;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject ContainerBuilder builder;
    @Inject DockerClient docker;
    @Inject EmulatorConfig config;
    @InjectMock EmbeddedDnsServer embeddedDns;
    private DnsTarget activeTarget;

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void ordinaryResolverWorksWithoutAnExternalRouteOrWorkloadOverride(boolean internal, boolean publishPort)
            throws Exception {
        Assumptions.assumeTrue(firewall.enabled());
        Assumptions.assumeTrue("Linux".equals(System.getProperty("os.name")));
        Assumptions.assumeTrue("linux".equalsIgnoreCase(docker.infoCmd().exec().getOsType()));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String eni = "eni-resolver-" + suffix;
        String network = null;
        String relay = null;
        String workload = null;
        SecurityGroupFirewallManager.Namespace namespace = null;
        try {
            network = docker.createNetworkCmd().withName("floci-resolver-" + suffix)
                    .withInternal(internal).exec().getId();
            String gateway = docker.inspectNetworkCmd().withNetworkId(network).exec()
                    .getIpam().getConfig().getFirst().getGateway();
            String helperImage = config.network().securityGroupEnforcement().helperImage();
            LocallyBuiltHelperImage.ensureBuilt(docker, builder, helperImage, "floci/network-helper:local",
                    "/docker/network-helper.Dockerfile");
            try (DnsTarget target = new DnsTarget(gateway)) {
                activeTarget = target;
                relay = lifecycle.createAndStart(builder.newContainer(helperImage)
                        .withName("floci-resolver-target-" + suffix)
                        .withDockerNetwork(Optional.of(network))
                        .withEntrypoint(List.of("socat"))
                        .withCmd(List.of("-T", "2", "UDP4-RECVFROM:53,reuseaddr,fork",
                                "UDP4:" + gateway + ":" + target.port())).build()).containerId();
                String resolver = docker.inspectContainerCmd(relay).exec().getNetworkSettings()
                        .getNetworks().values().iterator().next().getIpAddress();
                when(embeddedDns.getServerIp()).thenReturn(Optional.of(resolver));
                namespace = firewall.createNamespace("ec2", eni, "000000000000", "us-east-1",
                        Optional.of(network), publishPort ? Map.of(22, 0) : Map.of());
                String networkName = docker.inspectNetworkCmd().withNetworkId(network).exec().getName();
                Map<String, ContainerNetwork> attached = docker.inspectContainerCmd(namespace.helperId()).exec()
                        .getNetworkSettings().getNetworks();
                String serviceAddress = attached.get(networkName).getIpAddress();
                assertEquals(serviceAddress, namespace.transportAddress(),
                        "The caller identity must use the address that reaches the VPC resolver");
                SecurityGroup group = new SecurityGroup();
                group.setGroupId("sg-resolver-" + suffix);
                firewall.register(new SecurityGroupNftCompiler.Endpoint(
                        "000000000000", "us-east-1", "vpc-resolver", eni, "10.0.0.2",
                        namespace.transportAddress(), Set.of(group.getGroupId()), List.of(group)),
                        namespace.helperId(), Map.of());
                assertEquals(Optional.of(new ClientVpc("000000000000", "us-east-1", "vpc-resolver")),
                        firewall.vpcForClient(serviceAddress));
                workload = lifecycle.createAndStart(builder.newContainer("alpine:3.21")
                        .withName("floci-resolver-workload-" + suffix)
                        .withNetworkMode("container:" + namespace.helperId())
                        .withEntrypoint(List.of("sleep")).withCmd(List.of("600")).build()).containerId();
                assertTrue(execute(workload, "nslookup", "-type=A", "workload.example")
                        .contains("192.0.2.71"), "The workload must use its ordinary inherited resolver");
                assertTrue(execute(namespace.helperId(), "nslookup", "-type=A", "helper.example")
                        .contains("192.0.2.71"), "The namespace owner must use the same resolver");
                assertTrue(execute(workload, "nslookup", "-type=A", "control.example", resolver)
                        .contains("192.0.2.71"), "The DNS target and protected DNS exception must work");
                assertNull(target.failure.get(), "The real DNS target must remain healthy");
            }
        } finally {
            if (workload != null) {
                lifecycle.removeIfExistsStrict(workload);
            }
            firewall.unregister(eni);
            if (namespace != null) {
                lifecycle.removeIfExistsStrict(namespace.helperId());
            }
            if (relay != null) {
                lifecycle.removeIfExistsStrict(relay);
            }
            if (network != null) {
                docker.removeNetworkCmd(network).exec();
            }
        }
    }

    private String execute(String container, String... command) throws Exception {
        String id = docker.execCreateCmd(container).withAttachStdout(true).withAttachStderr(true)
                .withCmd(command).exec().getId();
        StringBuilder output = new StringBuilder();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
        }) {
            assertTrue(docker.execStartCmd(id).exec(callback).awaitCompletion(15, TimeUnit.SECONDS));
        }
        assertEquals(0L, docker.inspectExecCmd(id).exec().getExitCodeLong(),
                () -> output + "\ntarget failure=" + activeTarget.failure.get());
        return output.toString();
    }

    private static final class DnsTarget implements AutoCloseable {
        private final DatagramSocket socket;
        private final Thread worker;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private DnsTarget(String address) throws Exception {
            socket = new DatagramSocket(0, InetAddress.getByName(address));
            worker = Thread.ofVirtual().start(this::serve);
        }

        private int port() {
            return socket.getLocalPort();
        }

        private void serve() {
            while (!socket.isClosed()) {
                try {
                    DatagramPacket request = new DatagramPacket(new byte[512], 512);
                    socket.receive(request);
                    if (request.getLength() == 0) {
                        continue; // The socat UDP relay emits an empty datagram when its peer closes.
                    }
                    byte[] response = Arrays.copyOf(request.getData(), request.getLength() + 16);
                    ByteBuffer buffer = ByteBuffer.wrap(response);
                    assertEquals(1, buffer.getShort(4), "One DNS question is expected");
                    assertEquals(0, buffer.getShort(10), "The fixture expects no additional records");
                    buffer.putShort(2, (short) 0x8180).putShort(6, (short) 1).putShort(8, (short) 0);
                    buffer.position(request.getLength());
                    buffer.putShort((short) 0xc00c).putShort((short) 1).putShort((short) 1)
                            .putInt(0).putShort((short) 4).put(new byte[]{(byte) 192, 0, 2, 71});
                    socket.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                } catch (SocketException expected) {
                    if (!socket.isClosed()) {
                        failure.set(expected);
                    }
                    return; // Closing the owned target wakes its receive call.
                } catch (Throwable error) {
                    failure.set(error);
                    return;
                }
            }
        }

        @Override
        public void close() throws InterruptedException {
            socket.close();
            worker.join(3000);
            assertTrue(!worker.isAlive(), "The owned DNS target must stop");
        }
    }
}
