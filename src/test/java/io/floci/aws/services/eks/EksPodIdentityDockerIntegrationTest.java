package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterIdentity;
import io.floci.aws.services.eks.model.ClusterOidcKey;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.services.eks.model.CreateAddonRequest;
import io.floci.aws.services.eks.model.CreatePodIdentityAssociationRequest;
import io.floci.aws.services.eks.model.OidcIdentity;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.EksPodIdentityProfile;
import io.floci.aws.testing.ParallelLocks;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(EksPodIdentityDockerIntegrationTest.Profile.class)
@ResourceLock(ParallelLocks.TLS_CONFIG)
class EksPodIdentityDockerIntegrationTest {

    /** The public proxy is reachable from Docker; the real TLS bootstrap keeps its backends private. */
    public static class Profile extends EksPodIdentityProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            // The public host comes from the bootstrap below; the TLS config source pins the backends.
            overrides.remove("quarkus.http.host");
            return overrides;
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(TlsBootstrap.class));
        }
    }

    /** Profile overrides alone are not visible to TlsConfigSource's early bootstrap. */
    public static class TlsBootstrap implements QuarkusTestResourceLifecycleManager {
        private final Map<String, String> original = new HashMap<>();

        @Override
        public Map<String, String> start() {
            String directory = Path.of("target", "eks-pod-identity-tls").toAbsolutePath().toString();
            Map<String, String> properties = Map.of("floci.tls.enabled", "true",
                    "floci.storage.persistent-path", directory, "quarkus.http.host", "0.0.0.0");
            properties.forEach((name, value) -> {
                original.put(name, System.getProperty(name));
                System.setProperty(name, value);
            });
            return Map.of("floci.storage.persistent-path", directory);
        }

        @Override
        public void stop() {
            original.forEach((name, value) -> {
                if (value == null) {
                    System.clearProperty(name);
                } else {
                    System.setProperty(name, value);
                }
            });
            original.clear();
        }
    }

    private static final Logger LOG = Logger.getLogger(EksPodIdentityDockerIntegrationTest.class);
    private static final String TEST_IMAGE = "alpine:3.21";

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    EmulatorConfig config;

    @Inject
    EksService eksService;

    @Inject
    EksOidcService oidcService;

    @Inject
    EksPodIdentityAssociationService associationService;

    @Inject
    IamService iamService;

    @Inject
    EksClusterManager eksClusterManager;

    @Inject
    EksAddonService addonService;

    @Inject
    DockerHostResolver dockerHostResolver;

    private String containerId;
    private Cluster cluster;
    private String clusterName;
    private String roleName;

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = DockerAvailability.answers(dockerClient);
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksPodIdentityDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for EKS Pod Identity integration test");
        Assumptions.assumeTrue(dockerHostResolver.isLinuxHost(),
                "Link-local Pod Identity container test requires a Linux host; on macOS/Windows, container-to-host proxy traffic arrives from 127.0.0.1 which collides across clusters");
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            addonService.deleteClusterAddons(cluster);
        }
        if (clusterName != null) {
            try {
                eksService.deleteClusterForAccount("000000000000", clusterName);
            } catch (Exception ignored) {
            }
        }
        if (roleName != null) {
            try {
                iamService.deleteRole(roleName);
            } catch (Exception ignored) {
            }
        }
        if (containerId != null) {
            try {
                lifecycleManager.stopAndRemove(containerId, null);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void linkLocalPodIdentityEndpointAnswersInsideClusterContainer() throws Exception {
        clusterName = "pi-it-" + UUID.randomUUID().toString().substring(0, 8);
        roleName = "pi-it-role-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::000000000000:role/" + roleName;
        iamService.createRole(roleName, "/", "{\"Version\":\"2012-10-17\",\"Statement\":[]}", null, 3600, null);

        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setAccountId("000000000000");
        cluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/" + clusterName);
        cluster.setCreatedAt(Instant.now());
        cluster.setStatus(ClusterStatus.ACTIVE);
        String issuer = "https://oidc.eks.us-east-1.amazonaws.com/id/" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));
        eksService.putClusterForAccount("000000000000", cluster);

        ClusterOidcKey key = oidcService.ensureKeyForAccount("000000000000", EksService.clusterKey("us-east-1", clusterName), issuer);
        assertNotNull(key);

        associationService.create(cluster, new CreatePodIdentityAssociationRequest(
                clusterName, "default", "workload-sa", roleArn, null, null, null, null, null
        ));

        ContainerSpec spec = containerBuilder.newContainer(TEST_IMAGE)
                .withName("floci-eks-pi-test-" + clusterName)
                .withPrivileged(true)
                .withDockerNetwork(Optional.empty())
                .withHostDockerInternalOnLinux()
                // PID 1 gets no default SIGTERM handler, so a bare "sleep 300" sits out the
                // whole stopAndRemove grace period when @AfterEach tears the container down.
                .withCmd(List.of("sh", "-c", "trap 'exit 0' TERM; sleep 300 & wait"))
                .build();

        containerId = lifecycleManager.createAndStart(spec).containerId();
        assertNotNull(containerId, "Container ID must not be null");

        execInContainer(containerId, "command -v curl >/dev/null 2>&1 && command -v iptables >/dev/null 2>&1 && command -v ip >/dev/null 2>&1 || apk add --no-cache curl iptables iproute2");

        // The address answers while the agent add-on is stored, as on EKS.
        addonService.create(cluster, new CreateAddonRequest(EksPodIdentityWebhook.POD_IDENTITY_ADDON,
                "v1.4.0-eksbuild.3", null, null, null, null, null, null));
        eksClusterManager.configurePodIdentityForward(cluster, containerId);

        String validToken = oidcService.mintServiceAccountToken(EksService.clusterKey("us-east-1", clusterName), issuer, "default", "workload-sa",
                "pods.eks.amazonaws.com", 3600);

        String credsJson = execInContainer(containerId, "curl -s -f -H 'Authorization: " + validToken + "' http://169.254.170.23/v1/credentials");
        assertNotNull(credsJson);
        assertTrue(credsJson.contains("\"AccessKeyId\""));
        assertTrue(credsJson.contains("ASIA"));

        String unassociatedToken = oidcService.mintServiceAccountToken(EksService.clusterKey("us-east-1", clusterName), issuer, "default", "other-sa",
                "pods.eks.amazonaws.com", 3600);
        String missingCode = execInContainer(containerId, "curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: " + unassociatedToken + "' http://169.254.170.23/v1/credentials");
        assertEquals("404", missingCode.trim());

        for (int privatePort : List.of(4510, 4511)) {
            ContainerExec.Result direct = ContainerExec.runMerged(dockerClient, containerId,
                    new String[]{"sh", "-c", "curl -sS --connect-timeout 2 --max-time 3 -o /dev/null http://"
                            + dockerHostResolver.resolve() + ":" + privatePort + "/"}, 5);
            assertFalse(direct.timedOut(), direct.summary());
            assertEquals(7, direct.exitCode(), "Private API port " + privatePort + ": " + direct.summary());
        }
    }

    private String execInContainer(String containerId, String cmd) {
        ContainerExec.Result result = ContainerExec.runMerged(
                dockerClient, containerId, new String[]{"sh", "-c", cmd}, 30);
        assertFalse(result.timedOut(), "Container command timed out");
        assertEquals(0, result.exitCode(), result.summary());
        return result.stdout();
    }
}
