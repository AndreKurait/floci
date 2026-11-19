package io.floci.aws.services.ec2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Network;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.LocallyBuiltHelperImage;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceNetworkInterface;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.net.Cidr4;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class Ec2HotAttachedRestartDockerIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String MAC = "02:11:22:33:44:55";
    private static final String OWNER_LABEL = "floci.test.hot-attach-owner";

    @Inject DockerClient docker;
    @Inject ContainerBuilder builder;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject EmulatorConfig emulatorConfig;

    @Test
    void attachedDeviceCarriesItsMacAndPacketsAndDetachRemovesOnlyThatEndpoint() throws Exception {
        assumeTrue(DockerAvailability.isAvailable(), "Docker must be available for the hot-attach packet test");
        String namespace = "root-mainmac-" + UUID.randomUUID();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4650);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services().ec2().mock()).thenReturn(false);
        when(config.services().ec2().imdsPort()).thenReturn(4567);
        when(config.services().ec2().hotAttachNetworkInterfaces()).thenReturn(true);
        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(true);
        when(config.services().ec2().vpcNetworks().driver()).thenReturn("bridge");
        when(config.services().ec2().vpcNetworks().fallbackPool()).thenReturn("10.240.0.0/12");
        when(config.services().ec2().vpcNetworks().fallbackPrefixLength()).thenReturn(24);
        when(config.docker().extraLabels()).thenReturn(List.of());
        when(config.docker().resourceNamespace()).thenReturn(Optional.of(namespace));
        List<Cidr4> taken = docker.listNetworksCmd().exec().stream()
                .filter(network -> network.getIpam() != null && network.getIpam().getConfig() != null)
                .flatMap(network -> network.getIpam().getConfig().stream())
                .flatMap(ipam -> Cidr4.parse(ipam.getSubnet()).stream()).toList();
        Cidr4 cidr = IntStream.range(128, 240)
                .mapToObj(block -> Cidr4.parse("10.201." + block + ".0/24").orElseThrow())
                .filter(candidate -> taken.stream().noneMatch(candidate::overlaps))
                .findFirst().orElseThrow(() -> new AssertionError("No free test CIDR"));
        CurrentContainerNetworkResolver resolver = mock(CurrentContainerNetworkResolver.class);
        VpcNetworkManager manager = new VpcNetworkManager(config, docker, resolver);
        Ec2ContainerManager runtime = runtime(config, manager);
        List<String> containers = new ArrayList<>();
        manager.declareVpc(ACCOUNT, REGION, "vpc-attached", cidr.toString());
        String network = manager.networkNameFor(ACCOUNT, REGION, "vpc-attached").orElseThrow();
        try {
            manager.declareSubnet(ACCOUNT, REGION, "vpc-attached", "subnet-attached", cidr.toString());
            String address = manager.allocatePrivateIp(ACCOUNT, REGION, "subnet-attached").orElseThrow();
            assertFalse(manager.isSubstituted(ACCOUNT, REGION, "vpc-attached"));
            String guest = namespace + "-guest";
            containers.add(guest);
            String helperImage = emulatorConfig.network().securityGroupEnforcement().helperImage();
            LocallyBuiltHelperImage.ensureBuilt(docker, builder, helperImage, "floci/network-helper:local",
                    "/docker/network-helper.Dockerfile");
            lifecycle.createAndStart(builder.newContainer(helperImage)
                    .withName(guest).withNetworkMode("bridge").withCapAdd("NET_ADMIN")
                    .withLabels(Map.of(OWNER_LABEL, namespace)).withCmd(List.of("sleep", "120")).build());
            String originalDefaultRoute = run(guest, "ip", "-4", "route", "show", "default");
            String originalMac = docker.inspectContainerCmd(guest).exec().getNetworkSettings()
                    .getNetworks().get("bridge").getMacAddress();
            Instance instance = new Instance();
            instance.setInstanceId("i-hot-attached");
            instance.setOwnerId(ACCOUNT);
            instance.setRegion(REGION);
            instance.setVpcId("vpc-primary");
            instance.setDockerContainerId(guest);
            instance.setState(InstanceState.running());
            InstanceNetworkInterface eni = new InstanceNetworkInterface();
            eni.setNetworkInterfaceId("eni-hot-attached");
            eni.setOwnerId(ACCOUNT);
            eni.setDeviceIndex(1);
            eni.setVpcId("vpc-attached");
            eni.setSubnetId("subnet-attached");
            eni.setPrivateIpAddress(address);
            eni.setMacAddress(MAC);
            instance.setNetworkInterfaces(new ArrayList<>(List.of(eni)));
            assertEquals(Optional.empty(), runtime.plugNetworkInterface(instance, eni));
            assertEquals(originalDefaultRoute, run(guest, "ip", "-4", "route", "show", "default"));
            JsonNode links = new ObjectMapper().readTree(run(guest, "ip", "-j", "address"));
            JsonNode device = null;
            for (JsonNode link : links) {
                if (MAC.equalsIgnoreCase(link.path("address").asText())) {
                    device = link;
                }
            }
            assertTrue(device != null, links.toString());
            boolean addressFound = false;
            for (JsonNode info : device.path("addr_info")) {
                addressFound |= address.equals(info.path("local").asText());
            }
            assertTrue(addressFound, device.toString());
            String peer = namespace + "-peer";
            containers.add(peer);
            lifecycle.createAndStart(builder.newContainer(helperImage).withName(peer).withNetworkMode(network)
                    .withCapAdd("NET_ADMIN")
                    .withLabels(Map.of(OWNER_LABEL, namespace)).withCmd(List.of("sleep", "120")).build());
            assertEquals(0L, ContainerExec.run(docker, peer, new String[]{"ping", "-c", "1", "-W", "2", address}, 5)
                    .throwIfTimedOut(peer).exitCode());
            docker.stopContainerCmd(guest).withTimeout(1).exec();
            instance.setState(InstanceState.stopped());
            CompletableFuture<Void> started = new CompletableFuture<>();
            runtime.start(instance, () -> started.complete(null));
            started.get(30, TimeUnit.SECONDS);
            assertTrue(run(guest, "ip", "-j", "link").contains(MAC));
            assertEquals(0L, ContainerExec.run(docker, peer, new String[]{"ping", "-c", "1", "-W", "2", address}, 5)
                    .throwIfTimedOut(peer).exitCode());
            String previousStart = docker.inspectContainerCmd(guest).exec().getState().getStartedAt();
            runtime.reboot(instance);
            await().atMost(30, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(() -> {
                assertNotEquals(previousStart, docker.inspectContainerCmd(guest).exec().getState().getStartedAt());
                assertTrue(run(guest, "ip", "-j", "link").contains(MAC));
            });
            assertTrue(runtime.restoreMetadataRegistration(instance));
            assertTrue(run(guest, "ip", "-j", "link").contains(MAC));
            assertEquals(originalDefaultRoute, run(guest, "ip", "-4", "route", "show", "default"));
            docker.stopContainerCmd(guest).withTimeout(1).exec();
            docker.startContainerCmd(guest).exec();
            recreateAttachedEndpoint(guest, network, address, peer);
            assertFalse(run(guest, "ip", "-j", "link").contains(MAC));
            ObjectMapper mapper = new ObjectMapper();
            InstanceNetworkInterface restored = mapper.readValue(mapper.writeValueAsBytes(eni),
                    InstanceNetworkInterface.class);
            assertEquals(guest, restored.getSharedNetworkContainerId());
            instance.setNetworkInterfaces(new ArrayList<>(List.of(restored)));
            restored.setSharedNetworkContainerId(null);
            assertTrue(runtime.restoreMetadataRegistration(instance));
            assertFalse(run(guest, "ip", "-j", "link").contains(MAC), "Record-only attachments cannot claim a device");
            restored.setSharedNetworkContainerId("another-container");
            assertTrue(runtime.restoreMetadataRegistration(instance));
            assertFalse(run(guest, "ip", "-j", "link").contains(MAC), "A different namespace cannot claim a device");
            restored.setSharedNetworkContainerId(guest);
            assertTrue(runtime.restoreMetadataRegistration(instance));
            assertTrue(run(guest, "ip", "-j", "link").contains(MAC));
            assertEquals(0L, ContainerExec.run(docker, peer, new String[]{"ping", "-c", "1", "-W", "2", address}, 5)
                    .throwIfTimedOut(peer).exitCode());
            docker.stopContainerCmd(guest).withTimeout(1).exec();
            docker.startContainerCmd(guest).exec();
            recreateAttachedEndpoint(guest, network, address, peer);
            VpcNetworkManager recoveredNetworks = new VpcNetworkManager(config, docker, resolver);
            recoveredNetworks.declareVpc(ACCOUNT, REGION, "vpc-attached", cidr.toString());
            recoveredNetworks.declareSubnet(ACCOUNT, REGION, "vpc-attached", "subnet-attached", cidr.toString());
            Ec2ContainerManager recoveredRuntime = runtime(config, recoveredNetworks);
            try {
                assertTrue(recoveredRuntime.restoreMetadataRegistration(instance));
                assertTrue(run(guest, "ip", "-j", "link").contains(MAC));
                assertEquals(0L, ContainerExec.run(docker, peer,
                        new String[]{"ping", "-c", "1", "-W", "2", address}, 5).throwIfTimedOut(peer).exitCode());
                recoveredRuntime.unplugNetworkInterface(instance, restored);
                assertTrue(recoveredRuntime.restoreMetadataRegistration(instance));
                assertFalse(docker.inspectContainerCmd(guest).exec().getNetworkSettings()
                        .getNetworks().containsKey(network));
            } finally {
                recoveredRuntime.stop();
                stopNetworks(recoveredNetworks);
            }
            String remainingLinks = run(guest, "ip", "-j", "link");
            assertFalse(remainingLinks.contains(MAC));
            assertTrue(remainingLinks.contains(originalMac), remainingLinks);
            assertTrue(docker.inspectContainerCmd(guest).exec().getNetworkSettings().getNetworks().containsKey("bridge"));
            assertTrue(docker.inspectContainerCmd(peer).exec().getNetworkSettings().getNetworks().containsKey(network));
            assertNotEquals(0L, ContainerExec.run(docker, peer, new String[]{"ping", "-c", "1", "-W", "1", address}, 5)
                    .throwIfTimedOut(peer).exitCode());
        } finally {
            runtime.stop();
            stopNetworks(manager);
            for (String name : containers.reversed()) {
                try {
                    assertEquals(namespace, docker.inspectContainerCmd(name).exec()
                            .getConfig().getLabels().get(OWNER_LABEL));
                    docker.removeContainerCmd(name).withForce(true).withRemoveVolumes(true).exec();
                } catch (NotFoundException expected) {
                    // Creation may have failed before this owned name existed.
                }
            }
            try {
                Network owned = docker.inspectNetworkCmd().withNetworkId(network).exec();
                assertEquals(ContainerStorageHelper.ownerIdentity(config),
                        owned.getLabels().get(ContainerStorageHelper.OWNER_LABEL));
                docker.removeNetworkCmd(owned.getId()).exec();
            } catch (NotFoundException expected) {
                // The failed attach may not have created the owned network.
            }
        }
    }

    private void recreateAttachedEndpoint(String guest, String network, String address, String peer) {
        docker.disconnectFromNetworkCmd().withContainerId(guest).withNetworkId(network).exec();
        docker.connectToNetworkCmd().withContainerId(guest).withNetworkId(network)
                .withContainerNetwork(new ContainerNetwork()
                        .withIpamConfig(new ContainerNetwork.Ipam().withIpv4Address(address))
                        .withMacAddress("02:66:77:88:99:aa"))
                .exec();
        assertFalse(run(guest, "ip", "-j", "link").contains(MAC));
        run(peer, "ip", "neigh", "flush", "to", address);
        run(peer, "ping", "-c", "1", "-W", "2", address);
        String temporaryMac = docker.inspectContainerCmd(guest).exec().getNetworkSettings()
                .getNetworks().get(network).getMacAddress();
        String neighbor = run(peer, "ip", "neigh", "show", "to", address);
        assertTrue(neighbor.contains(temporaryMac), neighbor);
    }

    private Ec2ContainerManager runtime(EmulatorConfig config, VpcNetworkManager manager) {
        DockerHostResolver hostResolver = mock(DockerHostResolver.class);
        when(hostResolver.resolve()).thenReturn("127.0.0.1");
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getAccountId()).thenReturn(ACCOUNT);
        return new Ec2ContainerManager(builder, lifecycle,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), hostResolver, docker,
                mock(PortAllocator.class), config, mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class),
                regions, mock(ContainerNetworkReachability.class), manager, mock(ContainerReachableEndpoint.class),
                mock(SecurityGroupFirewallManager.class));
    }

    private static void stopNetworks(VpcNetworkManager manager) throws Exception {
        Method shutdown = VpcNetworkManager.class.getDeclaredMethod("shutdown");
        shutdown.setAccessible(true);
        shutdown.invoke(manager);
    }

    private String run(String container, String... command) {
        ContainerExec.Result result = ContainerExec.run(docker, container, command, 5).throwIfTimedOut(container);
        assertEquals(0L, result.exitCode(), result.summary());
        return result.stdout();
    }
}
