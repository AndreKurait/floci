package io.floci.aws.services.elb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.FlociCertificateAuthority;
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
import io.floci.aws.services.acm.CertificateGenerator.GeneratedCertificate;
import io.floci.aws.services.acm.model.Certificate;
import io.floci.aws.services.acm.model.CertificateStatus;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
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
import org.junit.jupiter.api.io.TempDir;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElbClassicVpcPacketTest {
    @TempDir
    Path certificates;

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "TCP", "HTTPS", "SSL"})
    void managerEnforcesFirewallAcrossVpcPackets(String protocol) throws Exception {
        boolean tls = "HTTPS".equals(protocol) || "SSL".equals(protocol);
        boolean tcp = "TCP".equals(protocol) || "SSL".equals(protocol);
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(certificates.resolve("trusted"));
        GeneratedCertificate certificate = ca.issueServerCertificate("owned.classic.test",
                List.of("owned.classic.test"), KeyAlgorithm.RSA_2048, null);
        String prefix = "classic-vpc-" + UUID.randomUUID();
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
                when(config.storage().persistentPath()).thenReturn(certificates.resolve("data").toString());
                when(config.defaultAccountId()).thenReturn("111111111111");
                when(config.services().elb().vpcFrontends()).thenReturn(true);
                when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
                when(config.services().elb().proxyImage()).thenReturn(PROXY);
                when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
                when(config.network().securityGroupEnforcement().helperImage()).thenReturn("floci/network-helper:local");
                when(config.services().ec2().vpcNetworks().enabled()).thenReturn(true);
                when(config.services().ec2().vpcNetworks().driver()).thenReturn("bridge");
                when(config.services().ec2().vpcNetworks().fallbackPool()).thenReturn("10.240.0.0/12");
                when(config.services().ec2().vpcNetworks().fallbackPrefixLength()).thenReturn(24);
                VpcNetworkManager networks = new VpcNetworkManager(config, docker, null);
                networks.declareVpc("111111111111", "us-east-1", "vpc-owned", "10.203.219.0/24");
                networks.declareSubnet("111111111111", "us-east-1", "vpc-owned", "subnet-owned", "10.203.219.0/24");
                String frontendAddress = networks.allocatePrivateIp("111111111111", "us-east-1", "subnet-owned").orElseThrow();
                String backendAddress = networks.allocatePrivateIp("111111111111", "us-east-1", "subnet-owned").orElseThrow();
                String networkName = networks.networkNameFor("111111111111", "us-east-1", "vpc-owned").orElseThrow();
                planned("network " + networkName);
                String helper = create(docker, created, prefix + "-helper", Map.of("test-owner", prefix), "bridge");
                assertTrue(networks.attach("111111111111", "us-east-1", "vpc-owned", "subnet-owned", helper, backendAddress).isPresent());
                RetryingTarCopier.copyBytes(docker, helper, "/tmp", "backend.sh",
                        (tcp ? "cat\n"
                                : "printf 'HTTP/1.1 200 OK\r\nContent-Length: 13\r\nConnection: close\r\n\r\nowned-backend'\n").getBytes(StandardCharsets.UTF_8), 0600);
                RetryingTarCopier.copyBytes(docker, helper, "/tmp", "ca.pem",
                        ca.caPem().getBytes(StandardCharsets.UTF_8), 0600);
                RetryingTarCopier.copyBytes(docker, helper, "/tmp", "backend.pem",
                        (certificate.certificatePem() + certificate.privateKeyPem())
                                .getBytes(StandardCharsets.UTF_8), 0600);
                ContainerExec.Result backend = ContainerExec.run(docker, helper, new String[]{"sh", "-c",
                        "socat " + (tls ? "OPENSSL-LISTEN:18081,reuseaddr,fork,cert=/tmp/backend.pem,verify=0"
                                : "TCP-LISTEN:18081,reuseaddr,fork")
                                + " 'EXEC:sh /tmp/backend.sh' >/tmp/backend.log 2>&1 &"}, 5);
                assertEquals(0, backend.exitCode(), backend.summary());
                Ec2Service ec2 = mock(Ec2Service.class);
                NetworkInterface eni = new NetworkInterface();
                eni.setNetworkInterfaceId("eni-owned");
                eni.setVpcId("vpc-owned");
                eni.setSubnetId("subnet-owned");
                eni.setPrivateIpAddress(frontendAddress);
                when(ec2.createServiceNetworkInterface(anyString(), anyString(), anyString(), anyList(), anyString()))
                        .thenReturn(eni);
                when(ec2.describeNetworkInterfaces(eq("us-east-1"), anyList(), anyMap()))
                        .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));
                Subnet subnet = new Subnet();
                subnet.setSubnetId("subnet-owned");
                subnet.setVpcId("vpc-owned");
                subnet.setAvailabilityZone("us-east-1a");
                when(ec2.describeSubnets(eq("us-east-1"), eq(List.of("subnet-owned")), anyMap()))
                        .thenReturn(List.of(subnet));
                SecurityGroup group = new SecurityGroup();
                group.setGroupId("sg-owned");
                group.setVpcId("vpc-owned");
                group.setOwnerId("111111111111");
                group.getIpPermissions().add(permission(18080, backendAddress + "/32"));
                group.getIpPermissionsEgress().add(permission(18081, backendAddress + "/32"));
                when(ec2.describeSecurityGroups(anyString(), anyList(), anyList(), anyMap())).thenReturn(List.of(group));
                Instance instance = new Instance();
                instance.setVpcId("vpc-owned");
                Placement placement = new Placement();
                placement.setAvailabilityZone("us-east-1a");
                instance.setPlacement(placement);
                instance.setPrivateIpAddress(backendAddress);
                when(ec2.getInstance("us-east-1", "i-owned")).thenReturn(Optional.of(instance));
                VpcRoutingManager routing = mock(VpcRoutingManager.class);
                StorageFactory factory = mock(StorageFactory.class);
                AccountAwareStorageBackend<ElbClassicFrontendManager.Ownership> store =
                        AccountAwareStorageBackend.inMemory("111111111111");
                doReturn(store).when(factory).create(eq("elb"), eq("elb-classic-frontends.json"), any());
                ImageCacheService images = mock(ImageCacheService.class);
                when(images.ensureImageExists(anyString())).thenAnswer(call -> call.getArgument(0));
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
                SecurityGroupFirewallManager firewall = new SecurityGroupFirewallManager(docker, builder, lifecycle, config, mock(EmbeddedDnsServer.class));
                AcmService acm = mock(AcmService.class);
                String certificateArn = "arn:aws:acm:us-east-1:111111111111:certificate/owned";
                Certificate storedCertificate = new Certificate();
                storedCertificate.setArn(certificateArn);
                storedCertificate.setStatus(CertificateStatus.ISSUED);
                storedCertificate.setCertificateBody(certificate.certificatePem());
                storedCertificate.setPrivateKey(certificate.privateKeyPem());
                storedCertificate.setCertificateChain(ca.caPem());
                when(acm.getCertificate(certificateArn, "us-east-1")).thenReturn(storedCertificate);
                when(acm.supplyWithCertificatesHeld(any())).thenAnswer(call -> {
                    Supplier<?> action = call.getArgument(0);
                    return action.get();
                });
                ElbClassicFrontendManager manager = new ElbClassicFrontendManager(config, ec2, firewall,
                        networks, builder, lifecycle, new ObjectMapper(), mock(IamService.class),
                        acm, routing, factory);
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
                listener.setSslCertificateId(certificateArn);
                listener.setLoadBalancerPort(18080);
                listener.setInstancePort(18081);
                lb.setListeners(List.of(listener));
                try {
                    assertTrue(manager.reconcile(lb));
                    assertEquals(Optional.of(frontendAddress), manager.address(lb));
                    assertEquals(1, store.scanAllAccounts().size());
                    ElbClassicFrontendManager.Probe probe = manager.probeFor(lb, "i-owned");
                    String proxy = probe.frontend().proxy();
                    assertTrue(docker.inspectContainerCmd(proxy).exec().getState().getRunning());
                    assertFalse(packet(docker, helper, frontendAddress, protocol).stdout().contains("owned-backend"));
                    manager.healthChanged(lb, probe, new ElbClassicHealthChecker.InstanceHealth("InService", "", ""));
                    assertPacket(docker, helper, frontendAddress, protocol);
                    if (tls) {
                        ContainerExec.Result untrusted = tlsPacket(docker, helper, frontendAddress, tcp, false,
                                "owned.classic.test");
                        assertNotEquals(0, untrusted.exitCode(), untrusted.summary());
                        assertFalse(untrusted.stdout().contains("owned-backend"), untrusted.summary());
                        ContainerExec.Result wrongHostname = tlsPacket(docker, helper, frontendAddress, tcp, true,
                                "wrong.classic.test");
                        assertNotEquals(0, wrongHostname.exitCode(), wrongHostname.summary());
                        assertFalse(wrongHostname.stdout().contains("owned-backend"), wrongHostname.summary());
                    }
                    assertFalse(manager.reconcile(lb), "Unchanged healthy proxy should be reused");
                    assertEquals(proxy, manager.probeFor(lb, "i-owned").frontend().proxy());
                    manager.healthChanged(lb, probe, new ElbClassicHealthChecker.InstanceHealth("OutOfService", "", ""));
                    assertFalse(packet(docker, helper, frontendAddress, protocol).stdout().contains("owned-backend"));
                    manager.healthChanged(lb, probe, new ElbClassicHealthChecker.InstanceHealth("InService", "", ""));
                    assertPacket(docker, helper, frontendAddress, protocol);
                    SecurityGroup denied = new SecurityGroup();
                    denied.setGroupId("sg-owned");
                    denied.setVpcId("vpc-owned");
                    denied.setOwnerId("111111111111");
                    denied.getIpPermissionsEgress().add(permission(18081, backendAddress + "/32"));
                    firewall.updateGroups("eni-owned", Set.of("sg-owned"), Map.of("sg-owned", denied), Map.of());
                    assertFalse(packet(docker, helper, frontendAddress, protocol).stdout().contains("owned-backend"));
                    firewall.updateGroups("eni-owned", Set.of("sg-owned"), Map.of("sg-owned", group), Map.of());
                    assertPacket(docker, helper, frontendAddress, protocol);
                    SecurityGroup egressDenied = new SecurityGroup();
                    egressDenied.setGroupId("sg-owned");
                    egressDenied.setVpcId("vpc-owned");
                    egressDenied.setOwnerId("111111111111");
                    egressDenied.getIpPermissions().add(permission(18080, backendAddress + "/32"));
                    firewall.updateGroups("eni-owned", Set.of("sg-owned"), Map.of("sg-owned", egressDenied), Map.of());
                    assertFalse(packet(docker, helper, frontendAddress, protocol).stdout().contains("owned-backend"),
                            "Allowing ingress must not bypass a missing backend egress grant");
                    firewall.updateGroups("eni-owned", Set.of("sg-owned"), Map.of("sg-owned", group), Map.of());
                    assertPacket(docker, helper, frontendAddress, protocol);
                    manager.remove(lb);
                    assertTrue(manager.address(lb).isEmpty());
                    assertFalse(manager.current(lb, probe));
                    assertTrue(store.scanAllAccounts().isEmpty());
                    verify(ec2).deleteNetworkInterface("us-east-1", "eni-owned");
                    assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(proxy).exec());
                    assertTrue(docker.inspectContainerCmd(helper).exec().getState().getRunning(), "Backend is not owned by the frontend manager");
                } finally {
                    manager.close();
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

    private static void assertPacket(DockerClient docker, String helper, String address, String protocol) {
        ContainerExec.Result response = packet(docker, helper, address, protocol);
        assertEquals("owned-backend", response.stdout(), response.summary());
    }

    private static ContainerExec.Result packet(DockerClient docker, String helper, String address, String protocol) {
        if ("HTTPS".equals(protocol) || "SSL".equals(protocol)) {
            return tlsPacket(docker, helper, address, "SSL".equals(protocol), true, "owned.classic.test");
        }
        if ("TCP".equals(protocol)) {
            return ContainerExec.run(docker, helper, new String[]{"sh", "-c",
                    "printf owned-backend | socat -T 2 - TCP:" + address + ":18080,connect-timeout=2"}, 5)
                    .throwIfTimedOut(helper);
        }
        return ContainerExec.run(docker, helper, new String[]{"curl", "--silent", "--show-error", "--max-time", "2",
                "--noproxy", "*", "http://" + address + ":18080/"}, 5).throwIfTimedOut(helper);
    }

    private static ContainerExec.Result tlsPacket(DockerClient docker, String helper, String address,
                                                   boolean tcp, boolean trusted, String hostname) {
        if (tcp) {
            return ContainerExec.run(docker, helper, new String[]{"sh", "-c",
                    "printf owned-backend | timeout 3 socat -T 2 - OPENSSL:" + address
                            + ":18080,connect-timeout=2,verify=1,commonname=" + hostname
                            + (trusted ? ",cafile=/tmp/ca.pem" : "")}, 5).throwIfTimedOut(helper);
        }
        List<String> command = new ArrayList<>(List.of("curl", "--silent", "--show-error", "--max-time", "2",
                "--noproxy", "*", "--resolve", hostname + ":18080:" + address));
        if (trusted) {
            command.addAll(List.of("--cacert", "/tmp/ca.pem"));
        }
        command.add("https://" + hostname + ":18080/");
        return ContainerExec.run(docker, helper, command.toArray(String[]::new), 5).throwIfTimedOut(helper);
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
