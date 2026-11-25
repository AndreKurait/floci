package io.floci.aws.services.elb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.FlociDockerHttpClient;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.NetworkInterfaceListResult;
import io.floci.aws.services.ec2.model.Placement;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import io.floci.aws.services.elb.model.ClassicListener;
import io.floci.aws.services.elb.model.ClassicLoadBalancer;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElbClassicManagerPacketTest {
    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "TCP"})
    void managerStartsProxyPublishesHealthAndRetiresNamespace(String protocol) throws Exception {
        String prefix = "classic-manager-" + UUID.randomUUID();
        URI host = URI.create("unix:///var/run/docker.sock");
        try (FlociDockerHttpClient transport = new FlociDockerHttpClient.Builder()
                .dockerHost(host).responseTimeout(Duration.ofSeconds(10)).build();
             DockerClient docker = DockerClientImpl.getInstance(DefaultDockerClientConfig.createDefaultConfigBuilder()
                     .withDockerHost(host.toString()).build(), transport)) {
            List<String> created = new ArrayList<>();
            try {
                EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
                when(config.docker().resourceNamespace()).thenReturn(Optional.of(prefix));
                when(config.port()).thenReturn(4566);
                when(config.defaultAccountId()).thenReturn("111111111111");
                when(config.services().elb().vpcFrontends()).thenReturn(true);
                when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
                when(config.services().elb().proxyImage()).thenReturn(PROXY);
                String helper = create(docker, created, prefix + "-helper", Map.of("test-owner", prefix), "none");
                RetryingTarCopier.copyBytes(docker, helper, "/tmp", "backend.sh",
                        ("printf 'HTTP/1.1 200 OK\r\nContent-Length: 13\r\n\r\nowned-backend'\n"
                                + "cat > /dev/null\n").getBytes(StandardCharsets.UTF_8), 0600);
                ContainerExec.Result backend = ContainerExec.run(docker, helper, new String[]{"sh", "-c",
                        "socat TCP-LISTEN:18081,reuseaddr,fork 'EXEC:sh /tmp/backend.sh' >/tmp/backend.log 2>&1 &"}, 5);
                assertEquals(0, backend.exitCode(), backend.summary());
                Ec2Service ec2 = mock(Ec2Service.class);
                NetworkInterface eni = new NetworkInterface();
                eni.setNetworkInterfaceId("eni-owned");
                eni.setVpcId("vpc-owned");
                eni.setSubnetId("subnet-owned");
                eni.setPrivateIpAddress("127.0.0.1");
                when(ec2.createServiceNetworkInterface(anyString(), anyString(), anyString(), anyList(), anyString()))
                        .thenReturn(eni);
                when(ec2.describeNetworkInterfaces(eq("us-east-1"), anyList(), anyMap()))
                        .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));
                SecurityGroup group = new SecurityGroup();
                group.setGroupId("sg-owned");
                when(ec2.describeSecurityGroups(anyString(), anyList(), anyList(), anyMap())).thenReturn(List.of(group));
                Subnet subnet = new Subnet();
                subnet.setSubnetId("subnet-owned");
                subnet.setVpcId("vpc-owned");
                subnet.setAvailabilityZone("us-east-1a");
                when(ec2.describeSubnets(eq("us-east-1"), eq(List.of("subnet-owned")), anyMap()))
                        .thenReturn(List.of(subnet));
                Instance instance = new Instance();
                Placement placement = new Placement();
                placement.setAvailabilityZone("us-east-1a");
                instance.setPlacement(placement);
                instance.setVpcId("vpc-owned");
                instance.setPrivateIpAddress("127.0.0.1");
                when(ec2.getInstance("us-east-1", "i-owned")).thenReturn(Optional.of(instance));
                SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
                when(firewall.enabled()).thenReturn(true);
                when(firewall.createNamespace(anyString(), anyString(), anyString(), anyString(), any(), anyMap(), anyMap()))
                        .thenReturn(new SecurityGroupFirewallManager.Namespace(helper, "127.0.0.1", null));
                VpcNetworkManager networks = mock(VpcNetworkManager.class);
                when(networks.enabled()).thenReturn(true);
                when(networks.attach(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                        .thenReturn(Optional.of("owned-loopback"));
                VpcRoutingManager routing = mock(VpcRoutingManager.class);
                StorageFactory factory = mock(StorageFactory.class);
                AccountAwareStorageBackend<ElbClassicFrontendManager.Ownership> store =
                        AccountAwareStorageBackend.inMemory("111111111111");
                doReturn(store).when(factory).create(eq("elb"), eq("elb-classic-frontends.json"), any());
                ImageCacheService images = mock(ImageCacheService.class);
                when(images.ensureImageExists(PROXY)).thenReturn(PROXY);
                ContainerLifecycleManager lifecycle = spy(new ContainerLifecycleManager(docker, docker,
                        images, mock(ContainerDetector.class), config));
                doAnswer(call -> {
                    ContainerSpec spec = call.getArgument(0);
                    planned(spec.name());
                    String id = (String) call.callRealMethod();
                    record(created, id);
                    return id;
                }).when(lifecycle).create(any(ContainerSpec.class));
                ContainerBuilder builder = new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class));
                ElbClassicFrontendManager manager = new ElbClassicFrontendManager(config, ec2, firewall,
                        networks, builder, lifecycle, new ObjectMapper(), mock(IamService.class),
                        mock(AcmService.class), routing, factory);
                ClassicLoadBalancer lb = new ClassicLoadBalancer();
                lb.setAccountId("111111111111");
                lb.setRegion("us-east-1");
                lb.setLoadBalancerName("owned");
                lb.setScheme("internal");
                lb.getSubnets().add("subnet-owned");
                lb.getAvailabilityZones().add("us-east-1a");
                lb.getSecurityGroups().add("sg-owned");
                lb.getInstanceIds().add("i-owned");
                ClassicListener listener = new ClassicListener();
                listener.setProtocol(protocol);
                listener.setInstanceProtocol(protocol);
                listener.setLoadBalancerPort(18080);
                listener.setInstancePort(18081);
                lb.setListeners(List.of(listener));
                try {
                    assertTrue(manager.reconcile(lb));
                    assertEquals(Optional.of("127.0.0.1"), manager.address(lb));
                    assertEquals(1, store.scanAllAccounts().size());
                    ElbClassicFrontendManager.Probe probe = manager.probeFor(lb, "i-owned");
                    String proxy = probe.frontend().proxy();
                    assertTrue(docker.inspectContainerCmd(proxy).exec().getState().getRunning());
                    assertFalse(packet(docker, helper).stdout().contains("owned-backend"));
                    manager.healthChanged(lb, probe, new ElbClassicHealthChecker.InstanceHealth("InService", "", ""));
                    assertEquals("owned-backend", packet(docker, helper).stdout());
                    assertFalse(manager.reconcile(lb), "Unchanged healthy proxy should be reused");
                    assertEquals(proxy, manager.probeFor(lb, "i-owned").frontend().proxy());
                    manager.healthChanged(lb, probe, new ElbClassicHealthChecker.InstanceHealth("OutOfService", "", ""));
                    assertFalse(packet(docker, helper).stdout().contains("owned-backend"));
                    manager.healthChanged(lb, probe, new ElbClassicHealthChecker.InstanceHealth("InService", "", ""));
                    assertEquals("owned-backend", packet(docker, helper).stdout());
                    manager.remove(lb);
                    assertTrue(manager.address(lb).isEmpty());
                    assertFalse(manager.current(lb, probe));
                    assertTrue(store.scanAllAccounts().isEmpty());
                    verify(ec2).deleteNetworkInterface("us-east-1", "eni-owned");
                    assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(proxy).exec());
                    assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(helper).exec());
                } finally {
                    manager.close();
                }
            } finally {
                for (String id : created.reversed()) {
                    try {
                        docker.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec();
                    } catch (NotFoundException ignored) {
                        // The actual manager already removed this test-owned container.
                    }
                }
                for (String id : created) {
                    assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(id).exec());
                }
            }
        }
    }

    private static ContainerExec.Result packet(DockerClient docker, String helper) {
        return ContainerExec.run(docker, helper, new String[]{"curl", "--silent", "--show-error", "--max-time", "2",
                "--noproxy", "*", "http://127.0.0.1:18080/"}, 5).throwIfTimedOut(helper);
    }

    private static void planned(String name) throws IOException {
        Files.writeString(Path.of(System.getenv("FLOCI_AGENT_RUN_DIR"), "docker-ownership.txt"),
                "planned " + name + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void record(List<String> created, String id) throws IOException {
        created.add(id);
        Files.writeString(Path.of(System.getenv("FLOCI_AGENT_RUN_DIR"), "docker-ownership.txt"),
                "created " + id + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static final String PROXY = "haproxy:3.2-alpine@sha256:5893840576071e6217cd52ebdcc14f454c6f7a631056fa6f4e0f40d3cac4215b";

    private static String create(DockerClient docker, List<String> created, String name,
                                 Map<String, String> labels, String network) throws IOException {
        Path manifest = Path.of(System.getenv("FLOCI_AGENT_RUN_DIR"), "docker-ownership.txt");
        Files.writeString(manifest, "planned " + name + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String id = docker.createContainerCmd("floci/network-helper:local").withName(name).withLabels(labels)
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode(network))
                .withCmd("sleep", "300").exec().getId();
        created.add(id);
        Files.writeString(manifest, "created " + id + " " + name + "\n", StandardOpenOption.APPEND);
        docker.startContainerCmd(id).exec();
        return id;
    }
}
