package io.floci.aws.services.elb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.FlociDockerHttpClient;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElbClassicDockerRecoveryTest {
    private String recoveryImage = System.getProperty("floci.test.classic.recovery-image", "alpine:3.21");

    @TempDir
    Path journalDirectory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startupRetiresOwnedWorkloadBeforeHelperAndRetainsBlockedLease(boolean blocked) throws Exception {
        String prefix = "classic-recovery-" + UUID.randomUUID();
        URI host = URI.create("unix:///var/run/docker.sock");
        try (FlociDockerHttpClient transport = new FlociDockerHttpClient.Builder()
                .dockerHost(host).responseTimeout(Duration.ofSeconds(10)).build();
             DockerClient docker = DockerClientImpl.getInstance(DefaultDockerClientConfig.createDefaultConfigBuilder()
                     .withDockerHost(host.toString()).build(), transport)) {
            EmulatorConfig imageConfig = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(imageConfig.docker().imagePullTimeoutSeconds()).thenReturn(300);
            recoveryImage = new ImageCacheService(docker, imageConfig).ensureImageExists(recoveryImage);
            List<String> created = new ArrayList<>();
            try {
                EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
                when(config.docker().resourceNamespace()).thenReturn(Optional.of(prefix));
                when(config.port()).thenReturn(4566);
                when(config.services().elb().vpcFrontends()).thenReturn(true);
                String owner = ContainerStorageHelper.ownerIdentity(config);
                Map<String, String> labels = new LinkedHashMap<>(Map.of(
                        ContainerStorageHelper.OWNER_LABEL, owner,
                        ContainerStorageHelper.SERVICE_LABEL, "elb-classic",
                        ContainerStorageHelper.ACCOUNT_LABEL, "111111111111",
                        ContainerStorageHelper.REGION_LABEL, "us-east-1",
                        "io.floci.elb-classic.network-interface", "eni-recovery"));
                labels.put(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true");
                String helper = create(docker, created, prefix + "-helper", labels, "none");
                labels.remove(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL);
                labels.put(ContainerStorageHelper.SECURITY_GROUP_WORKLOAD_LABEL, "true");
                String workload = create(docker, created, prefix + "-proxy", labels, "container:" + helper);
                labels.put(ContainerStorageHelper.OWNER_LABEL, owner + "-other");
                String other = create(docker, created, prefix + "-other", labels, "none");
                String consumer = blocked ? create(docker, created, prefix + "-consumer",
                        Map.of("test-owner", prefix), "container:" + helper) : null;
                Ec2Service ec2 = mock(Ec2Service.class);
                SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
                VpcRoutingManager routing = mock(VpcRoutingManager.class);
                StorageFactory factory = mock(StorageFactory.class);
                AccountAwareStorageBackend<ElbClassicFrontendManager.Ownership> store =
                        AccountAwareStorageBackend.inMemory("111111111111");
                doReturn(store).when(factory).create(eq("elb"), eq("elb-classic-frontends.json"), any());
                store.putAllAtomicallyForAccount("111111111111", Map.of("allocation",
                        new ElbClassicFrontendManager.Ownership(owner, "111111111111", "us-east-1",
                                "key", "allocation", "eni-recovery")));
                ContainerLifecycleManager lifecycle = new ContainerLifecycleManager(docker, docker,
                        mock(ImageCacheService.class), mock(ContainerDetector.class), config);
                ElbClassicFrontendManager manager = new ElbClassicFrontendManager(config, ec2, firewall,
                        mock(VpcNetworkManager.class), mock(ContainerBuilder.class), lifecycle, new ObjectMapper(),
                        mock(IamService.class), mock(AcmService.class), routing, factory);
                if (blocked) {
                    IllegalStateException failure = assertThrows(IllegalStateException.class, manager::initialize);
                    assertTrue(failure.getMessage().contains("Network namespace still has a consumer"));
                    assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(workload).exec());
                    assertTrue(docker.inspectContainerCmd(helper).exec().getState().getRunning());
                    verify(ec2, never()).deleteNetworkInterface(anyString(), anyString());
                    assertEquals(1, store.scanAllAccounts().size());
                    docker.removeContainerCmd(consumer).withForce(true).withRemoveVolumes(true).exec();
                }
                manager.initialize();
                assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(workload).exec());
                assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(helper).exec());
                assertTrue(docker.inspectContainerCmd(other).exec().getState().getRunning());
                verify(ec2, atLeastOnce()).deleteNetworkInterface("us-east-1", "eni-recovery");
                assertTrue(store.scanAllAccounts().isEmpty());
                clearInvocations(ec2);
                manager.initialize();
                verifyNoInteractions(ec2);
            } finally {
                for (String id : created.reversed()) {
                    try {
                        docker.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec();
                    } catch (NotFoundException ignored) {
                        // Recovery already removed this test-owned container.
                    }
                }
                for (String id : created) {
                    assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(id).exec());
                }
            }
        }
    }

    private String create(DockerClient docker, List<String> created, String name,
                                 Map<String, String> labels, String network) throws IOException {
        String configured = System.getenv("FLOCI_AGENT_RUN_DIR");
        Path directory = configured == null || configured.isBlank() ? journalDirectory : Path.of(configured);
        Path manifest = directory.resolve("docker-ownership.txt");
        Files.writeString(manifest, "planned " + name + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String id = docker.createContainerCmd(recoveryImage).withName(name).withLabels(labels)
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode(network))
                .withCmd("sleep", "300").exec().getId();
        created.add(id);
        Files.writeString(manifest, "created " + id + " " + name + "\n", StandardOpenOption.APPEND);
        docker.startContainerCmd(id).exec();
        return id;
    }
}
