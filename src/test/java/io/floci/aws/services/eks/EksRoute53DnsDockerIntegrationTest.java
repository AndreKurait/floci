package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import io.floci.aws.core.common.dns.DnsClientVpcSource;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ResourcesVpcConfig;
import io.floci.aws.services.route53.Route53Service;
import io.floci.aws.services.route53.model.ResourceRecord;
import io.floci.aws.services.route53.model.ResourceRecordSet;
import io.floci.aws.services.route53.model.VpcAssociation;
import io.floci.aws.testing.EksTestNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enables embedded DNS on the container it starts itself, so it needs no profile and runs on the
 * default one alongside the other EKS Docker classes. It needs Docker and a bound embedded DNS
 * server, and that server only binds when Floci itself runs in a container, so anywhere else the
 * class skips. The pod check starts a cluster through {@link EksClusterManager}, which runs a k3s
 * container whether or not EKS is mocked, and its pods resolve through CoreDNS.
 */
@QuarkusTest
class EksRoute53DnsDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksRoute53DnsDockerIntegrationTest.class);
    private static final String TEST_IMAGE = "alpine:3.21";
    private static final String ACCOUNT = "000000000000";
    private static final String POD = "dns-lookup-pod";

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    EmbeddedDnsServer embeddedDnsServer;

    @Inject
    Route53Service route53Service;

    @Inject
    EksClusterManager eksClusterManager;

    @Inject
    Ec2Service ec2Service;

    @Inject
    RawContainerVpc rawContainerVpc;

    private String vpcId;
    private String containerAddress;
    private String containerId;
    private String zoneId;
    private Cluster cluster;

    @BeforeEach
    void requireDockerAndEmbeddedDns() {
        boolean dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksRoute53DnsDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for EKS Route 53 DNS integration test");

        boolean dnsAvailable = embeddedDnsServer.getServerIp().isPresent();
        if (!dnsAvailable) {
            LOG.warn("Embedded DNS server is not running or has no bound IP; skipping EksRoute53DnsDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dnsAvailable, "Embedded DNS server must have a bound IP for Route 53 DNS integration test");
        vpcId = ec2Service.createVpc("us-east-1", "10.197.0.0/16", false).getVpcId();
    }

    @AfterEach
    void tearDown() {
        if (containerAddress != null) {
            rawContainerVpc.unregister(containerAddress);
        }
        if (cluster != null) {
            try {
                eksClusterManager.stopCluster(cluster);
            } catch (Exception e) {
                LOG.warnv("Failed to stop test cluster: {0}", e.getMessage());
            }
        }
        if (containerId != null) {
            try {
                lifecycleManager.stopAndRemove(containerId, null);
            } catch (Exception ignored) {
                // Best-effort cleanup of test container
            }
        }
        if (zoneId != null) {
            try {
                List<Map<String, Object>> deletes = route53Service.listResourceRecordSets(zoneId, null, null, 100).stream()
                        .filter(rrs -> !"NS".equals(rrs.getType()) && !"SOA".equals(rrs.getType()))
                        .map(rrs -> Map.<String, Object>of("action", "DELETE", "rrs", rrs))
                        .toList();
                if (!deletes.isEmpty()) {
                    route53Service.changeResourceRecordSets(zoneId, deletes, null);
                }
                route53Service.deleteHostedZone(zoneId);
            } catch (Exception ignored) {
                // Best-effort cleanup of test hosted zone
            }
        }
        if (vpcId != null) {
            ec2Service.deleteVpc("us-east-1", vpcId);
        }
    }

    @Test
    void clusterContainerResolvesRoute53PrivateHostedZoneRecords() throws Exception {
        String testDomain = privateZoneWithRecord("10.0.1.200");

        ContainerSpec spec = containerBuilder.newContainer(TEST_IMAGE)
                .withName("floci-eks-dns-test-" + UUID.randomUUID().toString().substring(0, 8))
                .withDockerNetwork(Optional.empty())
                .withEmbeddedDns()
                .withCmd(List.of("sh", "-c", "trap 'exit 0' TERM; sleep 300 & wait"))
                .build();

        containerId = lifecycleManager.createAndStart(spec).containerId();
        assertNotNull(containerId, "Container ID must not be null");

        String dnsServerIp = embeddedDnsServer.getServerIp().orElseThrow();

        // Docker's resolver stub forwards to the configured DNS server on a user-defined network.
        InspectContainerResponse inspection = dockerClient.inspectContainerCmd(containerId).exec();
        assertTrue(Arrays.asList(inspection.getHostConfig().getDns()).contains(dnsServerIp),
                "Container DNS configuration must name the embedded DNS server");
        String resolvConf = execInContainer(containerId, new String[]{"cat", "/etc/resolv.conf"});
        assertTrue(resolvConf.contains(dnsServerIp) || resolvConf.contains("nameserver 127.0.0.11"),
                "Container resolver must use the configured DNS server directly or through Docker: " + resolvConf);

        // Verify that standard resolver queries inside the container resolve the Route 53 private record
        String lookupCmd = "nslookup db." + testDomain.substring(0, testDomain.length() - 1);
        ContainerExec.Result unassociated = ContainerExec.run(
                dockerClient, containerId, new String[]{"sh", "-c", lookupCmd}, 30).throwIfTimedOut(containerId);
        assertNotEquals(0, unassociated.exitCode(), "an unassociated container must not see a private zone");
        containerAddress = inspection.getNetworkSettings().getNetworks()
                .values().stream().map(network -> network.getIpAddress())
                .filter(address -> address != null && !address.isBlank()).findFirst().orElseThrow();
        rawContainerVpc.register(containerAddress, new DnsClientVpcSource.ClientVpc(ACCOUNT, "us-east-1", vpcId));
        String output = execInContainer(containerId, new String[]{"sh", "-c", lookupCmd});
        assertNotNull(output, "Lookup output must not be null");
        assertTrue(output.contains("10.0.1.200"), "Lookup output must contain record IP: " + output);
    }

    @Test
    void podsResolveRoute53PrivateHostedZoneRecordsThroughCoreDns() {
        String testDomain = privateZoneWithRecord("10.0.1.201");
        cluster = EksTestNode.cluster(ACCOUNT, "dns-pod-" + UUID.randomUUID().toString().substring(0, 8));
        ResourcesVpcConfig network = new ResourcesVpcConfig();
        network.setVpcId(vpcId);
        cluster.setResourcesVpcConfig(network);
        EksTestNode node = EksTestNode.start(dockerClient, eksClusterManager, cluster, Duration.ofSeconds(90));

        // On a user-defined Docker network the node's own resolv.conf names only 127.0.0.11; without the
        // resolver file Floci hands k3s, CoreDNS would forward to a public resolver instead of to Floci.
        assertTrue(node.execOutput("cat", EksClusterManager.POD_RESOLV_CONF_PATH)
                        .contains(embeddedDnsServer.getServerIp().orElseThrow()),
                "the pods' resolver file must name Floci's DNS server");
        await("CoreDNS rolled out").atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2))
                .until(() -> node.kubectl("rollout", "status", "deployment/coredns", "-n", "kube-system",
                        "--timeout=10s").exitCode() == 0);
        node.apply("""
                apiVersion: v1
                kind: Pod
                metadata:
                  name: %s
                  namespace: default
                spec:
                  containers:
                  - name: lookup
                    image: %s
                    command: ["sleep", "3600"]
                """.formatted(POD, TEST_IMAGE));
        node.awaitPodRunning(POD, Duration.ofSeconds(90));

        String name = "db." + testDomain.substring(0, testDomain.length() - 1);
        String answer = node.kubectlOutput("exec", POD, "--", "nslookup", name);
        assertTrue(answer.contains("10.0.1.201"), "a pod must resolve the private record through CoreDNS: " + answer);
    }

    /** Creates a private hosted zone with an A record {@code db.<zone>} answering {@code address}; returns the zone. */
    private String privateZoneWithRecord(String address) {
        String testDomain = "corp-it-" + UUID.randomUUID().toString().substring(0, 8) + ".internal.";
        zoneId = route53Service.createHostedZone(
                testDomain,
                UUID.randomUUID().toString(),
                "EKS IT private zone",
                new VpcAssociation(vpcId, "us-east-1")).zone().getId();

        ResourceRecordSet aRrs = new ResourceRecordSet();
        aRrs.setName("db." + testDomain);
        aRrs.setType("A");
        aRrs.setTtl(60L);
        aRrs.setRecords(List.of(new ResourceRecord(address)));
        route53Service.changeResourceRecordSets(zoneId, List.of(Map.of("action", "CREATE", "rrs", aRrs)), null);
        return testDomain;
    }

    /** The raw DNS-injection fixture owns only its inspected address; EKS supplies its own node origin. */
    @ApplicationScoped
    public static class RawContainerVpc implements DnsClientVpcSource {
        private final Map<String, ClientVpc> origins = new ConcurrentHashMap<>();

        public void register(String address, ClientVpc origin) {
            origins.put(address, origin);
        }

        public void unregister(String address) {
            origins.remove(address);
        }

        @Override
        public Optional<ClientVpc> vpcForClient(String clientAddress) {
            return Optional.ofNullable(clientAddress == null ? null : origins.get(clientAddress));
        }
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception expected) {
            // Docker daemon is not running or unreachable in this test environment
            LOG.debugv("Docker ping failed: {0}", expected.getMessage());
            return false;
        }
    }

    private String execInContainer(String containerId, String[] cmd) {
        ContainerExec.Result result = ContainerExec.run(dockerClient, containerId, cmd, 30).throwIfTimedOut(containerId);
        if (result.exitCode() != 0) {
            throw new RuntimeException("exec failed with code " + result.exitCode() + ": " + result.stderr()
                    + " stdout=" + result.stdout());
        }
        return result.stdout();
    }
}
