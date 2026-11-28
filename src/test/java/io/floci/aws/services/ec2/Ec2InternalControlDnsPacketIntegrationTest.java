package io.floci.aws.services.ec2;

import com.github.dockerjava.api.model.HostConfig;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Typed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(Ec2PublicPeerPacketIntegrationTest.RoutedVpc.class)
@Typed(Ec2InternalControlDnsPacketIntegrationTest.class)
class Ec2InternalControlDnsPacketIntegrationTest extends Ec2PublicPeerPacketIntegrationTest {
    private final Deque<Executable> dnsCleanup = new ArrayDeque<>();

    @AfterEach
    void removeDnsGuestBeforeTheInheritedControlNetwork() {
        assertAll("Owned internal-control DNS guest", dnsCleanup);
    }

    @Test
    void internalControlNetworkServesLinkLocalDnsOverUdpAndTcpWithoutAnInternetRoute() {
        Ec2Client sdk = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("111111111111", "test"))).build();
        dnsCleanup.addFirst(sdk::close);
        String vpc = sdk.createVpc(request -> request.cidrBlock("10.92.0.0/16")).vpc().vpcId();
        dnsCleanup.addFirst(() -> sdk.deleteVpc(request -> request.vpcId(vpc)));
        String subnet = sdk.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.92.1.0/24"))
                .subnet().subnetId();
        dnsCleanup.addFirst(() -> sdk.deleteSubnet(request -> request.subnetId(subnet)));
        String group = sdk.createSecurityGroup(request -> request.vpcId(vpc)
                .groupName("internal-dns").description("Owned DNS packet fixture")).groupId();
        dnsCleanup.addFirst(() -> sdk.deleteSecurityGroup(request -> request.groupId(group)));
        String instance = sdk.runInstances(request -> request.imageId("ami-public-peers")
                .subnetId(subnet).securityGroupIds(group).privateIpAddress("10.92.1.10")
                .instanceType("t3.micro").minCount(1).maxCount(1)).instances().getFirst().instanceId();
        dnsCleanup.addFirst(() -> {
            sdk.terminateInstances(request -> request.instanceIds(instance));
            await().atMost(Duration.ofSeconds(60)).until(() -> "terminated".equals(
                    sdk.describeInstances(request -> request.instanceIds(instance))
                            .reservations().getFirst().instances().getFirst().state().nameAsString()));
        });
        await().atMost(Duration.ofSeconds(60)).until(() -> "running".equals(
                sdk.describeInstances(request -> request.instanceIds(instance))
                        .reservations().getFirst().instances().getFirst().state().nameAsString()));
        String workload = service.findInstanceById("111111111111", instance).getDockerContainerId();
        assertNotNull(workload);
        String networkMode = docker.inspectContainerCmd(workload).exec().getHostConfig().getNetworkMode();
        assertTrue(networkMode.startsWith("container:"));
        String helper = networkMode.substring("container:".length());
        String probe = docker.createContainerCmd(TestImages.PYTHON_ALPINE)
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode("container:" + helper))
                .withCmd("sleep", "2147483647")
                .withLabels(Map.of(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                        ContainerStorageHelper.SERVICE_LABEL, "test-internal-dns")).exec().getId();
        dnsCleanup.addFirst(() -> docker.removeContainerCmd(probe).withForce(true).exec());
        docker.startContainerCmd(probe).exec();
        String floci = currentContainer.resolveContainerId().orElseThrow();
        String expected = docker.inspectContainerCmd(floci).exec().getNetworkSettings().getNetworks()
                .get(config.services().dockerNetwork().orElseThrow()).getIpAddress();
        ContainerExec.Result result = ContainerExec.run(docker, probe, new String[]{"python3", "-c", """
                import socket, struct, sys
                query = struct.pack('!6H', 42, 256, 1, 0, 0, 0)
                query += b''.join(bytes([len(label)]) + label for label in
                                  [b'probe', b'localhost', b'floci', b'io'])
                query += b'\\x00' + struct.pack('!2H', 1, 1)
                def answer(data):
                    assert struct.unpack('!H', data[:2])[0] == 42
                    assert data[3] & 15 == 0
                    assert struct.unpack('!H', data[6:8])[0] == 1
                    assert data[-4:] == socket.inet_aton(sys.argv[1])
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
                    udp.settimeout(3)
                    udp.sendto(query, ('169.254.169.253', 53))
                    data, peer = udp.recvfrom(4096)
                    assert peer[0] == '169.254.169.253'
                    answer(data)
                with socket.create_connection(('169.254.169.253', 53), timeout=3) as tcp:
                    tcp.sendall(struct.pack('!H', len(query)) + query)
                    def read_exact(size):
                        data = b''
                        while len(data) < size:
                            part = tcp.recv(size - len(data))
                            assert part
                            data += part
                        return data
                    answer(read_exact(struct.unpack('!H', read_exact(2))[0]))
                print('UDP_AND_TCP_LINKLOCAL_DNS_PASSED')
                """, expected}, 15);
        assertEquals(0, result.exitCode(), result.summary() + "\n" + result.stdout());
        assertTrue(result.stdout().contains("UDP_AND_TCP_LINKLOCAL_DNS_PASSED"));
        assertTrue(sdk.describeRouteTables(request -> request.filters(
                        filter -> filter.name("vpc-id").values(vpc))).routeTables().stream()
                .flatMap(table -> table.routes().stream())
                .noneMatch(route -> "0.0.0.0/0".equals(route.destinationCidrBlock())),
                "DNS must work without admitting a general internet route");
    }
}
