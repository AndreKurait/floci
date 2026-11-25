package io.floci.aws.services.ec2.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Network;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.aws.core.common.docker.LocallyBuiltHelperImage;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class VpcHotAttachedDeviceDockerIntegrationTest {
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
        String namespace = "hot-attach-" + UUID.randomUUID();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4650);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services().ec2().mock()).thenReturn(false);
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
        List<String> containers = new ArrayList<>();
        String network = manager.networkName(ACCOUNT, REGION, "vpc-attached");
        try {
            manager.declareVpc(ACCOUNT, REGION, "vpc-attached", cidr.toString());
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
            assertEquals(Optional.of(network), manager.attachInterface(ACCOUNT, REGION, "vpc-attached",
                    "subnet-attached", guest, address, MAC));
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
            lifecycle.createAndStart(builder.newContainer(TestImages.BUSYBOX).withName(peer).withNetworkMode(network)
                    .withLabels(Map.of(OWNER_LABEL, namespace)).withCmd(List.of("sleep", "120")).build());
            assertEquals(0L, ContainerExec.run(docker, peer, new String[]{"ping", "-c", "1", "-W", "2", address}, 5)
                    .throwIfTimedOut(peer).exitCode());
            docker.stopContainerCmd(guest).withTimeout(1).exec();
            docker.startContainerCmd(guest).exec();
            manager.restoreInterface(ACCOUNT, REGION, "vpc-attached", guest, address, MAC);
            assertTrue(run(guest, "ip", "-j", "link").contains(MAC));
            assertEquals(0L, ContainerExec.run(docker, peer, new String[]{"ping", "-c", "1", "-W", "2", address}, 5)
                    .throwIfTimedOut(peer).exitCode());
            docker.restartContainerCmd(guest).withTimeout(1).exec();
            manager.restoreInterface(ACCOUNT, REGION, "vpc-attached", guest, address, MAC);
            manager.restoreInterface(ACCOUNT, REGION, "vpc-attached", guest, address, MAC);
            assertTrue(run(guest, "ip", "-j", "link").contains(MAC));
            assertEquals(originalDefaultRoute, run(guest, "ip", "-4", "route", "show", "default"));
            manager.detachInterface(ACCOUNT, REGION, "vpc-attached", guest, address, MAC);
            manager.restoreInterface(ACCOUNT, REGION, "vpc-attached", guest, address, MAC);
            assertFalse(docker.inspectContainerCmd(guest).exec().getNetworkSettings().getNetworks().containsKey(network));
            String remainingLinks = run(guest, "ip", "-j", "link");
            assertFalse(remainingLinks.contains(MAC));
            assertTrue(remainingLinks.contains(originalMac), remainingLinks);
            assertTrue(docker.inspectContainerCmd(guest).exec().getNetworkSettings().getNetworks().containsKey("bridge"));
            assertTrue(docker.inspectContainerCmd(peer).exec().getNetworkSettings().getNetworks().containsKey(network));
            assertNotEquals(0L, ContainerExec.run(docker, peer, new String[]{"ping", "-c", "1", "-W", "1", address}, 5)
                    .throwIfTimedOut(peer).exitCode());
        } finally {
            manager.shutdown();
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

    private String run(String container, String... command) {
        ContainerExec.Result result = ContainerExec.run(docker, container, command, 5).throwIfTimedOut(container);
        assertEquals(0L, result.exitCode(), result.summary());
        return result.stdout();
    }
}
