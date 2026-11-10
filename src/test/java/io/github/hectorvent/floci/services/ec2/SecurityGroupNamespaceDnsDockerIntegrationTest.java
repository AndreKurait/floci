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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
                        .withEntrypoint(List.of("sh", "-c"))
                        .withCmd(List.of("socat -T 2 UDP4-RECVFROM:53,reuseaddr,fork UDP4:"
                                + gateway + ":" + target.port() + " & exec socat TCP4-LISTEN:53,reuseaddr,fork TCP4:"
                                + gateway + ":" + target.tcp.getLocalPort())).build()).containerId();
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
                assertTrue(execute(workload, "nslookup", "-type=A", "link-local.example", "169.254.169.253")
                        .contains("192.0.2.71"), "AWS's link-local resolver must work through a deny-all security group");
                assertTrue(execute(namespace.helperId(), "sh", "-c", tcpQuestionCommand())
                        .replaceAll("\\s+", "").endsWith("c0000247"),
                        "The same link-local address must answer DNS over TCP");
                String routes = execute(namespace.helperId(), "ip", "-4", "route", "show",
                        "table", "main", "exact", "169.254.169.253/32");
                assertTrue(routes.contains("src " + serviceAddress),
                        "Link-local DNS must retain the registered VPC caller's control-network identity");
                assertTrue(execute(workload, "sh", "-c",
                        "if nc -w 1 169.254.169.253 80 </dev/null >/dev/null 2>&1; then exit 1; fi").isBlank(),
                        "The resolver alias must not grant HTTP access");
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

    private static String tcpQuestionCommand() {
        byte[] question = new byte[]{0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                3, 't', 'c', 'p', 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0, 0, 1, 0, 1};
        byte[] request = ByteBuffer.allocate(question.length + 2).putShort((short) question.length)
                .put(question).array();
        StringBuilder octal = new StringBuilder();
        for (byte value : request) {
            octal.append("\\").append("%03o".formatted(Byte.toUnsignedInt(value)));
        }
        return "printf '" + octal + "' | socat -T 3 - TCP4:169.254.169.253:53,connect-timeout=2 | od -An -tx1";
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
        private final ServerSocket tcp;
        private final Thread worker;
        private final Thread tcpWorker;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private DnsTarget(String address) throws Exception {
            socket = new DatagramSocket(0, InetAddress.getByName(address));
            tcp = new ServerSocket(0, 10, InetAddress.getByName(address));
            worker = Thread.ofVirtual().start(this::serve);
            tcpWorker = Thread.ofVirtual().start(this::serveTcp);
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
                    byte[] response = answer(Arrays.copyOf(request.getData(), request.getLength()));
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

        private void serveTcp() {
            while (!tcp.isClosed()) {
                try (Socket peer = tcp.accept()) {
                    peer.setSoTimeout(3000);
                    DataInputStream input = new DataInputStream(peer.getInputStream());
                    int length = input.readUnsignedShort();
                    assertTrue(length >= 12 && length <= 512);
                    byte[] question = input.readNBytes(length);
                    assertEquals(length, question.length);
                    byte[] response = answer(question);
                    DataOutputStream output = new DataOutputStream(peer.getOutputStream());
                    output.writeShort(response.length);
                    output.write(response);
                    output.flush();
                } catch (SocketException expected) {
                    if (!tcp.isClosed()) {
                        failure.set(expected);
                    }
                    return;
                } catch (Throwable error) {
                    failure.set(error);
                    return;
                }
            }
        }

        private static byte[] answer(byte[] question) {
            byte[] response = Arrays.copyOf(question, question.length + 16);
            ByteBuffer buffer = ByteBuffer.wrap(response);
            assertEquals(1, buffer.getShort(4), "One DNS question is expected");
            assertEquals(0, buffer.getShort(10), "The fixture expects no additional records");
            buffer.putShort(2, (short) 0x8180).putShort(6, (short) 1).putShort(8, (short) 0);
            buffer.position(question.length);
            buffer.putShort((short) 0xc00c).putShort((short) 1).putShort((short) 1)
                    .putInt(0).putShort((short) 4).put(new byte[]{(byte) 192, 0, 2, 71});
            return response;
        }

        @Override
        public void close() throws Exception {
            socket.close();
            tcp.close();
            worker.join(3000);
            tcpWorker.join(3000);
            assertTrue(!worker.isAlive(), "The owned DNS target must stop");
            assertTrue(!tcpWorker.isAlive(), "The owned TCP DNS target must stop");
        }
    }
}
