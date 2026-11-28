package io.floci.aws.services.elbv2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.FlociDockerHttpClient;
import io.floci.aws.core.common.docker.LocallyBuiltHelperImage;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.NetworkInterfaceListResult;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.elbv2.model.AvailabilityZone;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import io.floci.aws.testing.DockerAvailability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class ElbV2ManagedNamespacePacketTest {
    @TempDir
    Path storageDirectory;

    @Test
    void forwardsRevokesAndRestoresInterfaceFromPersistentStorage() throws Exception {
        assumeTrue(DockerAvailability.isAvailable(), "Docker is required for managed ALB namespace packets");
        String prefix = "alb-runtime-" + UUID.randomUUID();
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
                when(config.storage().persistentPath()).thenReturn(storageDirectory.toString());
                ServiceConfigAccess storageConfig = mock(ServiceConfigAccess.class);
                when(storageConfig.storageMode("elbv2")).thenReturn("persistent");
                when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
                when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
                String helperImage = System.getProperty("floci.test.alb.helper-image", "floci/network-helper:local");
                when(config.network().securityGroupEnforcement().helperImage()).thenReturn(helperImage);
                when(config.services().ec2().vpcNetworks().enabled()).thenReturn(true);
                when(config.services().ec2().vpcNetworks().driver()).thenReturn("bridge");
                when(config.services().ec2().vpcNetworks().fallbackPool()).thenReturn("10.240.0.0/12");
                when(config.services().ec2().vpcNetworks().fallbackPrefixLength()).thenReturn(24);
                ContainerBuilder builder = new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class));
                LocallyBuiltHelperImage.ensureBuilt(docker, builder, helperImage, "floci/network-helper:local",
                        "/docker/network-helper.Dockerfile");
                VpcNetworkManager networks = new VpcNetworkManager(config, docker, null);
                networks.declareVpc("111111111111", "us-east-1", "vpc-owned", "10.203.218.0/24");
                networks.declareSubnet("111111111111", "us-east-1", "vpc-owned", "subnet-owned", "10.203.218.0/24");
                String frontendAddress = networks.allocatePrivateIp("111111111111", "us-east-1", "subnet-owned").orElseThrow();
                String backendAddress = networks.allocatePrivateIp("111111111111", "us-east-1", "subnet-owned").orElseThrow();
                String networkName = networks.networkNameFor("111111111111", "us-east-1", "vpc-owned").orElseThrow();
                planned("network " + networkName);
                String helper = create(docker, created, prefix + "-helper", Map.of("test-owner", prefix), "bridge",
                        builder.resolveImage(helperImage));
                assertTrue(networks.attach("111111111111", "us-east-1", "vpc-owned", "subnet-owned", helper, backendAddress).isPresent());
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
                eni.setPrivateIpAddress(frontendAddress);
                when(ec2.createNetworkInterface(anyString(), anyString(), anyString(), isNull(), anyList(), anyList(), anyList()))
                        .thenReturn(eni);
                SecurityGroup group = new SecurityGroup();
                group.setGroupId("sg-owned");
                group.setVpcId("vpc-owned");
                group.setOwnerId("111111111111");
                group.getIpPermissionsEgress().add(permission(18081, backendAddress + "/32"));
                when(ec2.describeSecurityGroups(anyString(), anyList(), anyList(), anyMap())).thenReturn(List.of(group));
                ImageCacheService images = mock(ImageCacheService.class);
                when(images.ensureImageExists(anyString())).thenAnswer(call -> call.getArgument(0));
                ContainerLifecycleManager lifecycle = spy(new ContainerLifecycleManager(docker, docker,
                        images, mock(ContainerDetector.class), config));
                doAnswer(call -> {
                    ContainerSpec spec = call.getArgument(0);
                    planned(spec.name());
                    ContainerLifecycleManager.ContainerInfo info =
                            (ContainerLifecycleManager.ContainerInfo) call.callRealMethod();
                    record(created, info.containerId());
                    return info;
                }).when(lifecycle).createAndStart(any(ContainerSpec.class));
                SecurityGroupFirewallManager firewall = new SecurityGroupFirewallManager(docker, builder, lifecycle, config, mock(EmbeddedDnsServer.class));
                ElbV2VpcNetworkManager manager = new ElbV2VpcNetworkManager(config, ec2, firewall, lifecycle, networks);
                LoadBalancer lb = new LoadBalancer();
                lb.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-east-1:111111111111:loadbalancer/app/owned/123");
                lb.setRegion("us-east-1");
                lb.setType("application");
                lb.getSecurityGroups().add("sg-owned");
                AvailabilityZone zone = new AvailabilityZone();
                zone.setSubnetId("subnet-owned");
                lb.getAvailabilityZones().add(zone);
                try {
                    StorageFactory firstStorage = new StorageFactory(config, storageConfig);
                    AccountAwareStorageBackend<Map<String, LoadBalancer>> firstStore = firstStorage.create(
                            "elbv2", "elbv2-load-balancers.json", new TypeReference<Map<String, Map<String, LoadBalancer>>>() { });
                    Runnable persist = () -> firstStore.put("us-east-1", Map.of(lb.getLoadBalancerArn(), lb));
                    manager.reconcile(lb, persist);
                    persist.run();
                    firstStorage.shutdownAll();
                    assertTrue(Files.size(storageDirectory.resolve("elbv2-load-balancers.json")) > 0);
                    assertEquals(List.of("eni-owned"), lb.getBackendNetworkInterfaceIds());
                    assertEquals("owned-backend", packet(manager, lb, backendAddress));
                    SecurityGroup denied = new SecurityGroup();
                    denied.setGroupId("sg-owned");
                    denied.setVpcId("vpc-owned");
                    denied.setOwnerId("111111111111");
                    firewall.updateGroups("eni-owned", Set.of("sg-owned"), Map.of("sg-owned", denied), Map.of());
                    assertThrows(IOException.class, () -> packet(manager, lb, backendAddress));
                    firewall.updateGroups("eni-owned", Set.of("sg-owned"), Map.of("sg-owned", group), Map.of());
                    assertEquals("owned-backend", packet(manager, lb, backendAddress));
                    manager.stopManagedContainers();
                    assertEquals(List.of("eni-owned"), lb.getBackendNetworkInterfaceIds());
                    verify(ec2, never()).deleteNetworkInterface(anyString(), anyString());
                    assertThrows(IllegalStateException.class, () -> manager.proxy(lb.getLoadBalancerArn()));
                    assertThrows(IllegalStateException.class, () -> manager.reconcile(lb, () -> { }));
                    Ec2Service restoredEc2 = mock(Ec2Service.class, RETURNS_DEEP_STUBS);
                    when(restoredEc2.describeNetworkInterfaces("us-east-1", List.of("eni-owned"), Map.of()))
                            .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));
                    when(restoredEc2.describeSecurityGroups(anyString(), anyList(), anyList(), anyMap()))
                            .thenReturn(List.of(group));
                    ElbV2VpcNetworkManager restored = new ElbV2VpcNetworkManager(config, restoredEc2, firewall,
                            lifecycle, networks);
                    StorageFactory restoredStorage = new StorageFactory(config, storageConfig);
                    ElbV2Service restoredService = new ElbV2Service(restored);
                    restoredService.storageFactory = restoredStorage;
                    restoredService.regionResolver = new RegionResolver("us-east-1", "111111111111");
                    restoredService.ec2Service = restoredEc2;
                    restoredService.initializeStorage();
                    LoadBalancer restoredLb = restoredService.describeLoadBalancers("us-east-1",
                            List.of(lb.getLoadBalancerArn()), null, null, null).getFirst();
                    assertNotSame(lb, restoredLb, "Restart must deserialize a new model from disk");
                    assertEquals(List.of("eni-owned"), restoredLb.getBackendNetworkInterfaceIds());
                    assertThrows(IllegalStateException.class, () -> restored.proxy(restoredLb.getLoadBalancerArn()));
                    try {
                        restoredService.restorePersistedRuntime();
                        assertEquals("active", restoredLb.getState());
                        assertEquals("owned-backend", packet(restored, restoredLb, backendAddress));
                        verify(restoredEc2, never()).createNetworkInterface(anyString(), anyString(), anyString(),
                                any(), anyList(), anyList(), anyList());
                        restoredService.deleteLoadBalancer("us-east-1", restoredLb.getLoadBalancerArn());
                        assertTrue(restoredLb.getBackendNetworkInterfaceIds().isEmpty());
                        assertEquals(List.of("eni-owned"), lb.getBackendNetworkInterfaceIds(),
                                "The old in-memory model is not reused by the restored service");
                        verify(restoredEc2).deleteNetworkInterface("us-east-1", "eni-owned");
                        assertThrows(IllegalStateException.class, () -> restored.proxy(lb.getLoadBalancerArn()));
                        restoredStorage.shutdownAll();
                        StorageFactory finalStorage = new StorageFactory(config, storageConfig);
                        try {
                            AccountAwareStorageBackend<Map<String, LoadBalancer>> finalStore = finalStorage.create(
                                    "elbv2", "elbv2-load-balancers.json",
                                    new TypeReference<Map<String, Map<String, LoadBalancer>>>() { });
                            assertTrue(finalStore.get("us-east-1").orElseThrow().isEmpty(),
                                    "Deletion must survive a second disk reload");
                        } finally {
                            finalStorage.shutdownAll();
                        }
                    } finally {
                        restored.stopManagedContainers();
                        restoredStorage.shutdownAll();
                    }
                    assertTrue(docker.inspectContainerCmd(helper).exec().getState().getRunning(),
                            "Backend is not owned by the ALB manager");
                } finally {
                    manager.stopManagedContainers();
                    docker.removeContainerCmd(helper).withForce(true).withRemoveVolumes(true).exec();
                    networks.deleteVpcNetwork("111111111111", "us-east-1", "vpc-owned");
                    assertThrows(NotFoundException.class, () -> docker.inspectNetworkCmd().withNetworkId(networkName).exec());
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

    private static String packet(ElbV2VpcNetworkManager manager, LoadBalancer lb, String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://" + address + ":18081/")
                .toURL().openConnection(manager.proxy(lb.getLoadBalancerArn()).javaProxy());
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        try {
            assertEquals(200, connection.getResponseCode());
            return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static IpPermission permission(int port, String cidr) {
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(port);
        permission.setToPort(port);
        IpRange range = new IpRange();
        range.setCidrIp(cidr);
        permission.getIpRanges().add(range);
        return permission;
    }

    private static void planned(String name) throws IOException {
        Files.writeString(Path.of(System.getenv().getOrDefault("FLOCI_AGENT_RUN_DIR", System.getProperty("java.io.tmpdir")),
                        "docker-ownership.txt"),
                "planned " + name + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void record(List<String> created, String id) throws IOException {
        created.add(id);
        Files.writeString(Path.of(System.getenv().getOrDefault("FLOCI_AGENT_RUN_DIR", System.getProperty("java.io.tmpdir")),
                        "docker-ownership.txt"),
                "created " + id + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }


    private static String create(DockerClient docker, List<String> created, String name,
                                 Map<String, String> labels, String network, String image) throws IOException {
        Path manifest = Path.of(System.getenv().getOrDefault("FLOCI_AGENT_RUN_DIR", System.getProperty("java.io.tmpdir")),
                        "docker-ownership.txt");
        Files.writeString(manifest, "planned " + name + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String id = docker.createContainerCmd(image).withName(name).withLabels(labels)
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode(network))
                .withCmd("sleep", "300").exec().getId();
        created.add(id);
        Files.writeString(manifest, "created " + id + " " + name + "\n", StandardOpenOption.APPEND);
        docker.startContainerCmd(id).exec();
        return id;
    }
}
