package io.github.hectorvent.floci.services.elb;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import io.github.hectorvent.floci.core.common.docker.FlociDockerHttpClient;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Repeats the packet contracts with an isolated control plane and native VPC address spaces. */
@QuarkusTest
@TestProfile(VpcInternalControlAwsApiPacketIntegrationTest.InternalControl.class)
@QuarkusTestResource(value = VpcInternalControlAwsApiPacketIntegrationTest.ControlNetwork.class,
        restrictToAnnotatedClass = true)
class VpcInternalControlAwsApiPacketIntegrationTest extends VpcPublicAwsApiPacketIntegrationTest {
    public static class InternalControl extends PublicAwsApis {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.remove("floci.dns.listen-address");
            overrides.put("floci.services.ec2.vpc-networks.isolate-address-spaces", "true");
            return overrides;
        }
    }

    public static class ControlNetwork implements QuarkusTestResourceLifecycleManager {
        private DockerClient docker;
        private String network;

        @Override
        public Map<String, String> start() {
            DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
            docker = DockerClientImpl.getInstance(config, new FlociDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig())
                    .connectionTimeout(Duration.ofSeconds(10)).responseTimeout(Duration.ofSeconds(30)).build());
            if (!DockerAvailability.answers(docker)) {
                stop();
                return Map.of();
            }
            try {
                network = docker.createNetworkCmd().withName("floci-internal-api-" + UUID.randomUUID())
                        .withInternal(true).exec().getId();
                String gateway = docker.inspectNetworkCmd().withNetworkId(network).exec()
                        .getIpam().getConfig().getFirst().getGateway();
                return Map.of("floci.services.docker-network", network,
                        "floci.services.lambda.docker-host-override", gateway,
                        "floci.dns.listen-address", gateway);
            } catch (RuntimeException failure) {
                try {
                    stop();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        @Override
        public void stop() {
            try {
                if (network != null) {
                    docker.removeNetworkCmd(network).exec();
                    network = null;
                }
            } finally {
                if (docker != null) {
                    try {
                        docker.close();
                    } catch (IOException failure) {
                        throw new IllegalStateException("Cannot close the internal-network Docker client", failure);
                    }
                }
            }
        }
    }
}
